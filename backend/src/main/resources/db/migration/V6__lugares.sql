-- V6: lugares donde vive el dinero (banco, sobre, CDP, efectivo), su saldo y el dinero
-- movido entre ellos. Mover dinero NO es gasto ni ingreso: solo cambia de lugar.
--
-- Saldo de un lugar = saldo inicial
--                   + ingresos y dinero recibido desde la fecha del saldo inicial
--                   - gastos y dinero enviado desde esa fecha.
-- Lo anotado antes de la fecha del saldo inicial no cuenta: ya está dentro de ese saldo.

CREATE TABLE place (
    id              uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    space_id        uuid          NOT NULL REFERENCES space (id),
    created_by      uuid          NOT NULL REFERENCES app_user (id),
    name            text          NOT NULL CHECK (length(name) BETWEEN 1 AND 40),
    kind            text          NOT NULL CHECK (kind IN ('BANCO', 'SOBRE', 'CDP', 'EFECTIVO')),
    currency        char(3)       NOT NULL CHECK (currency IN ('CRC', 'USD')),
    opening_balance numeric(14,2) NOT NULL DEFAULT 0 CHECK (opening_balance >= 0),
    opening_date    date          NOT NULL CHECK (opening_date >= DATE '2000-01-01'),
    -- Un sobre puede ir dentro de su banco (solo para agrupar al mostrar).
    parent_id       uuid,
    archived        boolean       NOT NULL DEFAULT false,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    UNIQUE (id, space_id),
    CHECK (parent_id IS NULL OR parent_id <> id),
    -- El lugar padre es del MISMO espacio (FK compuesta, como en V1).
    FOREIGN KEY (parent_id, space_id) REFERENCES place (id, space_id)
);
CREATE UNIQUE INDEX place_space_name_idx ON place (space_id, lower(name));

-- Cada movimiento puede decir de qué lugar sale o a cuál entra (opcional: lo anotado antes
-- de V6 no tiene lugar). FK compuesta: el lugar es del mismo espacio que el movimiento.
ALTER TABLE transaction ADD COLUMN place_id uuid;
ALTER TABLE transaction ADD FOREIGN KEY (place_id, space_id) REFERENCES place (id, space_id);
CREATE INDEX transaction_place_idx ON transaction (place_id) WHERE place_id IS NOT NULL;

CREATE TABLE transfer (
    id          uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    space_id    uuid          NOT NULL REFERENCES space (id),
    created_by  uuid          NOT NULL REFERENCES app_user (id),
    from_place  uuid          NOT NULL,
    to_place    uuid          NOT NULL,
    tx_date     date          NOT NULL CHECK (tx_date >= DATE '2000-01-01'),
    amount      numeric(14,2) NOT NULL CHECK (amount > 0),
    description text          CHECK (length(description) BETWEEN 1 AND 200),
    -- "Repartir" crea varias transferencias juntas con el mismo lote.
    batch_id    uuid,
    created_at  timestamptz   NOT NULL DEFAULT now(),
    CHECK (from_place <> to_place),
    FOREIGN KEY (from_place, space_id) REFERENCES place (id, space_id),
    FOREIGN KEY (to_place, space_id)   REFERENCES place (id, space_id)
);
CREATE INDEX transfer_space_date_idx ON transfer (space_id, tx_date);

-- La moneda del movimiento tiene que ser la de su lugar, y una transferencia solo mueve
-- dinero entre lugares de la misma moneda (cambiar de moneda queda para después).
-- Barrera en la BD aunque la app se equivoque. Corre con los permisos de quien escribe
-- (no SECURITY DEFINER): solo ve lugares que RLS ya le deja ver.
CREATE FUNCTION check_place_currency() RETURNS trigger
    LANGUAGE plpgsql SET search_path = casaayg, pg_temp AS $$
BEGIN
    IF TG_TABLE_NAME = 'transaction' THEN
        IF NEW.place_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM place WHERE id = NEW.place_id AND currency = NEW.currency) THEN
            RAISE EXCEPTION 'la moneda no coincide con la del lugar' USING ERRCODE = '23514';
        END IF;
    ELSIF (SELECT count(DISTINCT currency) FROM place WHERE id IN (NEW.from_place, NEW.to_place)) <> 1 THEN
        RAISE EXCEPTION 'solo se mueve dinero entre lugares de la misma moneda' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER transaction_place_currency BEFORE INSERT OR UPDATE OF place_id, currency ON transaction
    FOR EACH ROW EXECUTE FUNCTION check_place_currency();
CREATE TRIGGER transfer_place_currency BEFORE INSERT OR UPDATE ON transfer
    FOR EACH ROW EXECUTE FUNCTION check_place_currency();

-- Un lugar no cambia de moneda si ya tiene movimientos (el saldo dejaría de tener sentido).
-- La app tampoco puede: no hay GRANT UPDATE sobre currency.

-- ---------------------------------------------------------------------------
-- Permisos (privilegio mínimo, columna por columna; ver V2)
-- ---------------------------------------------------------------------------
GRANT SELECT, INSERT ON place, transfer TO casaayg_app;
GRANT UPDATE (name, opening_balance, opening_date, parent_id, archived) ON place TO casaayg_app;
GRANT UPDATE (place_id) ON transaction TO casaayg_app;
-- Una transferencia no se edita: se borra y se anota de nuevo.
GRANT DELETE ON transfer TO casaayg_app;

-- ---------------------------------------------------------------------------
-- RLS: igual que los movimientos (V3)
-- ---------------------------------------------------------------------------
ALTER TABLE place ENABLE ROW LEVEL SECURITY;
CREATE POLICY place_read ON place FOR SELECT TO casaayg_app
    USING (space_id IN (SELECT my_space_ids()));
CREATE POLICY place_insert ON place FOR INSERT TO casaayg_app
    WITH CHECK (space_id IN (SELECT my_writable_space_ids()) AND created_by = current_app_user());
CREATE POLICY place_update ON place FOR UPDATE TO casaayg_app
    USING (space_id IN (SELECT my_writable_space_ids()))
    WITH CHECK (space_id IN (SELECT my_writable_space_ids()));

ALTER TABLE transfer ENABLE ROW LEVEL SECURITY;
CREATE POLICY transfer_read ON transfer FOR SELECT TO casaayg_app
    USING (space_id IN (SELECT my_space_ids()));
CREATE POLICY transfer_insert ON transfer FOR INSERT TO casaayg_app
    WITH CHECK (space_id IN (SELECT my_writable_space_ids()) AND created_by = current_app_user());
CREATE POLICY transfer_delete ON transfer FOR DELETE TO casaayg_app
    USING (space_id IN (SELECT my_writable_space_ids()));

-- ---------------------------------------------------------------------------
-- Fusión (V5): ahora también lleva los lugares y el dinero movido al espacio familiar.
-- Mismo patrón que las categorías: se copia cada lugar y se reapuntan las filas.
-- (La fusión se rediseña en la parte 4; esto la mantiene correcta mientras tanto.)
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION merge_approve(p_request uuid) RETURNS uuid
    LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = casaayg, pg_temp AS $$
DECLARE
    me        uuid := current_app_user();
    r         merge_request;
    fam       uuid;
    src       record;
    c         record;
    b         record;
    p         record;
    target    uuid;
    tb        uuid;
    cat_map   jsonb;
    place_map jsonb;
BEGIN
    -- Solo la PAREJA invitada aprueba (quien propuso ya aprobó al proponer).
    SELECT * INTO r FROM merge_request
     WHERE id = p_request AND partner_id = me AND status = 'PENDIENTE' FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'solicitud no encontrada' USING ERRCODE = 'P0002';
    END IF;
    IF r.expires_at <= now() THEN
        UPDATE merge_request SET status = 'VENCIDA', closed_at = now() WHERE id = r.id;
        RETURN NULL;
    END IF;
    IF in_active_family(r.requester_id) OR in_active_family(r.partner_id) THEN
        RAISE EXCEPTION 'ya existe un espacio familiar' USING ERRCODE = '55000';
    END IF;

    INSERT INTO space (kind, name) VALUES ('FAMILIA', r.family_name) RETURNING id INTO fam;
    INSERT INTO space_member (space_id, user_id) VALUES (fam, r.requester_id), (fam, r.partner_id);

    FOR src IN SELECT s.id, s.name FROM space s JOIN space_member m ON m.space_id = s.id
                WHERE m.user_id IN (r.requester_id, r.partner_id)
                  AND s.kind = 'PERSONAL' AND s.archived_at IS NULL
                ORDER BY s.created_at
    LOOP
        -- Lugares: se copian al espacio familiar. Si el nombre ya existe (los dos tienen
        -- "Efectivo"), se le agrega el nombre del espacio de origen para no mezclarlos.
        place_map := '{}'::jsonb;
        FOR p IN SELECT * FROM place WHERE space_id = src.id ORDER BY parent_id NULLS FIRST, created_at LOOP
            INSERT INTO place (space_id, created_by, name, kind, currency, opening_balance, opening_date,
                               parent_id, archived, created_at)
                VALUES (fam, p.created_by,
                        CASE WHEN EXISTS (SELECT 1 FROM place WHERE space_id = fam AND lower(name) = lower(p.name))
                             THEN left(p.name || ' (' || src.name || ')', 40) ELSE p.name END,
                        p.kind, p.currency, p.opening_balance, p.opening_date,
                        (place_map ->> p.parent_id::text)::uuid, p.archived, p.created_at)
                RETURNING id INTO target;
            place_map := place_map || jsonb_build_object(p.id::text, target::text);
        END LOOP;

        cat_map := '{}'::jsonb;
        -- Categorías: misma categoría (sin importar mayúsculas) se unifica; si no, se copia.
        FOR c IN SELECT * FROM category WHERE space_id = src.id LOOP
            SELECT id INTO target FROM category WHERE space_id = fam AND lower(name) = lower(c.name);
            IF target IS NULL THEN
                INSERT INTO category (space_id, created_by, name, category_group, active, created_at)
                    VALUES (fam, c.created_by, c.name, c.category_group, c.active, c.created_at)
                    RETURNING id INTO target;
            END IF;
            cat_map := cat_map || jsonb_build_object(c.id::text, target::text);
            -- Los movimientos pasan al espacio familiar recordando de dónde vienen.
            UPDATE transaction
               SET space_id = fam, category_id = target, origin_space_id = src.id,
                   place_id = (place_map ->> place_id::text)::uuid
             WHERE space_id = src.id AND category_id = c.id;
        END LOOP;

        UPDATE transfer
           SET space_id = fam,
               from_place = (place_map ->> from_place::text)::uuid,
               to_place = (place_map ->> to_place::text)::uuid
         WHERE space_id = src.id;

        -- Presupuestos del mismo mes se combinan sumando lo planeado por categoría.
        FOR b IN SELECT * FROM budget WHERE space_id = src.id LOOP
            SELECT id INTO tb FROM budget WHERE space_id = fam AND year = b.year AND month = b.month;
            IF tb IS NULL THEN
                INSERT INTO budget (space_id, created_by, year, month)
                    VALUES (fam, b.created_by, b.year, b.month) RETURNING id INTO tb;
            END IF;
            INSERT INTO budget_line (space_id, budget_id, category_id, planned_amount)
                SELECT fam, tb, (cat_map ->> l.category_id::text)::uuid, l.planned_amount
                  FROM budget_line l WHERE l.budget_id = b.id
            ON CONFLICT (budget_id, category_id)
                DO UPDATE SET planned_amount = budget_line.planned_amount + EXCLUDED.planned_amount;
            DELETE FROM budget WHERE id = b.id;
        END LOOP;

        DELETE FROM category WHERE space_id = src.id;
        -- Primero los sobres (tienen padre), luego el resto.
        DELETE FROM place WHERE space_id = src.id AND parent_id IS NOT NULL;
        DELETE FROM place WHERE space_id = src.id;
        UPDATE space SET archived_at = now(), merged_into = fam WHERE id = src.id;
    END LOOP;

    UPDATE merge_request
       SET status = 'COMPLETADA', partner_approved_at = now(), closed_at = now(), family_space_id = fam
     WHERE id = r.id;
    INSERT INTO audit_log (user_id, space_id, event_type, details) VALUES
        (r.requester_id, fam, 'FUSION_COMPLETADA', jsonb_build_object('solicitud', r.id)),
        (r.partner_id, fam, 'FUSION_COMPLETADA', jsonb_build_object('solicitud', r.id));
    RETURN fam;
END $$;
