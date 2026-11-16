-- V5: Fase 4 — categorías borrables, tipo de cambio y fusión de espacios (ADR-005).

-- ---------------------------------------------------------------------------
-- Categorías: se pueden borrar si no tienen movimientos (la FK de transaction lo impide).
-- Sus líneas de presupuesto se borran con ellas.
-- ---------------------------------------------------------------------------
ALTER TABLE budget_line
    DROP CONSTRAINT budget_line_category_id_space_id_fkey,
    ADD CONSTRAINT budget_line_category_id_space_id_fkey
        FOREIGN KEY (category_id, space_id) REFERENCES category (id, space_id) ON DELETE CASCADE;

-- "Super" y "super" son la misma categoría.
CREATE UNIQUE INDEX category_space_lower_name_key ON category (space_id, lower(name));

GRANT DELETE ON category TO casaayg_app;
CREATE POLICY category_delete ON category FOR DELETE TO casaayg_app
    USING (space_id IN (SELECT my_writable_space_ids()));

-- ---------------------------------------------------------------------------
-- Tipo de cambio del dólar (referencia del BCCR publicada por Hacienda). Dato público:
-- sin RLS. Los rangos frenan un valor absurdo de una respuesta manipulada.
-- ---------------------------------------------------------------------------
CREATE TABLE exchange_rate (
    rate_date  date          PRIMARY KEY,
    buy        numeric(10,2) NOT NULL CHECK (buy BETWEEN 100 AND 2000),
    sell       numeric(10,2) NOT NULL CHECK (sell BETWEEN 100 AND 2000),
    source     text          NOT NULL CHECK (source IN ('HACIENDA', 'MANUAL')),
    fetched_at timestamptz   NOT NULL DEFAULT now()
);

GRANT SELECT, INSERT ON exchange_rate TO casaayg_app;
GRANT UPDATE (buy, sell, source, fetched_at) ON exchange_rate TO casaayg_app;

-- Tipo de venta para una fecha: el de ese día o el anterior más cercano; si no hay
-- anterior, el primero disponible. NULL si la tabla está vacía.
CREATE FUNCTION usd_sell_rate(p_date date) RETURNS numeric
    LANGUAGE sql STABLE SET search_path = casaayg, pg_temp AS $$
    SELECT coalesce(
        (SELECT sell FROM exchange_rate WHERE rate_date <= p_date ORDER BY rate_date DESC LIMIT 1),
        (SELECT sell FROM exchange_rate ORDER BY rate_date ASC LIMIT 1))
$$;
REVOKE ALL ON FUNCTION usd_sell_rate(date) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION usd_sell_rate(date) TO casaayg_app;

-- ---------------------------------------------------------------------------
-- Fusión de espacios al casarse: DOBLE CONSENTIMIENTO.
-- La app no toca merge_request directamente: solo llama a estas funciones, que actúan
-- siempre como el usuario actual (current_app_user) y validan todo dentro de la BD.
-- (La re-autenticación reciente con passkey la exige la app antes de llamarlas.)
-- ---------------------------------------------------------------------------
CREATE TABLE merge_request (
    id                    uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    requester_id          uuid        NOT NULL REFERENCES app_user (id),
    partner_id            uuid        NOT NULL REFERENCES app_user (id),
    family_name           text        NOT NULL CHECK (length(family_name) BETWEEN 1 AND 60),
    status                text        NOT NULL DEFAULT 'PENDIENTE'
                          CHECK (status IN ('PENDIENTE', 'COMPLETADA', 'RECHAZADA', 'CANCELADA', 'VENCIDA')),
    created_at            timestamptz NOT NULL DEFAULT now(),
    expires_at            timestamptz NOT NULL DEFAULT now() + interval '7 days',
    requester_approved_at timestamptz NOT NULL DEFAULT now(),
    partner_approved_at   timestamptz,
    closed_at             timestamptz,
    family_space_id       uuid        REFERENCES space (id),
    CHECK (requester_id <> partner_id),
    CHECK (status <> 'COMPLETADA' OR (partner_approved_at IS NOT NULL AND family_space_id IS NOT NULL))
);

-- Una sola solicitud pendiente por persona.
CREATE UNIQUE INDEX merge_request_one_pending_requester ON merge_request (requester_id) WHERE status = 'PENDIENTE';
CREATE UNIQUE INDEX merge_request_one_pending_partner ON merge_request (partner_id) WHERE status = 'PENDIENTE';

CREATE FUNCTION in_active_family(p_user uuid) RETURNS boolean
    LANGUAGE sql STABLE SET search_path = casaayg, pg_temp AS $$
    SELECT EXISTS (SELECT 1 FROM space_member m JOIN space s ON s.id = m.space_id
                    WHERE m.user_id = p_user AND s.kind = 'FAMILIA' AND s.archived_at IS NULL)
$$;

-- Errores: P0002 = no existe / no es tuyo (la app responde 404, sin revelar cuál).
--          55000 = estado no permite la operación (la app responde 409).
CREATE FUNCTION merge_propose(p_partner_username text, p_family_name text) RETURNS uuid
    LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = casaayg, pg_temp AS $$
DECLARE
    me      uuid := current_app_user();
    partner uuid;
    v_id    uuid;
BEGIN
    IF me IS NULL THEN
        RAISE EXCEPTION 'sin usuario' USING ERRCODE = '42501';
    END IF;
    SELECT id INTO partner FROM app_user WHERE username = lower(p_partner_username);
    IF partner IS NULL OR partner = me THEN
        RAISE EXCEPTION 'pareja no encontrada' USING ERRCODE = 'P0002';
    END IF;
    IF in_active_family(me) OR in_active_family(partner) THEN
        RAISE EXCEPTION 'ya existe un espacio familiar' USING ERRCODE = '55000';
    END IF;
    UPDATE merge_request SET status = 'VENCIDA', closed_at = now()
     WHERE status = 'PENDIENTE' AND expires_at <= now();
    INSERT INTO merge_request (requester_id, partner_id, family_name)
        VALUES (me, partner, p_family_name) RETURNING id INTO v_id;
    INSERT INTO audit_log (user_id, event_type, details)
        VALUES (me, 'FUSION_PROPUESTA', jsonb_build_object('solicitud', v_id));
    RETURN v_id;
EXCEPTION WHEN unique_violation THEN
    RAISE EXCEPTION 'ya hay una solicitud pendiente' USING ERRCODE = '55000';
END $$;

CREATE FUNCTION merge_decline(p_request uuid) RETURNS void
    LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = casaayg, pg_temp AS $$
DECLARE
    me uuid := current_app_user();
    r  merge_request;
BEGIN
    SELECT * INTO r FROM merge_request
     WHERE id = p_request AND status = 'PENDIENTE' AND me IN (requester_id, partner_id) FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'solicitud no encontrada' USING ERRCODE = 'P0002';
    END IF;
    UPDATE merge_request
       SET status = CASE WHEN me = r.partner_id THEN 'RECHAZADA' ELSE 'CANCELADA' END, closed_at = now()
     WHERE id = r.id;
    INSERT INTO audit_log (user_id, event_type, details)
        VALUES (me, 'FUSION_RECHAZADA', jsonb_build_object('solicitud', r.id));
END $$;

CREATE FUNCTION merge_approve(p_request uuid) RETURNS uuid
    LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = casaayg, pg_temp AS $$
DECLARE
    me      uuid := current_app_user();
    r       merge_request;
    fam     uuid;
    src     record;
    c       record;
    b       record;
    target  uuid;
    tb      uuid;
    cat_map jsonb;
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

    FOR src IN SELECT s.id FROM space s JOIN space_member m ON m.space_id = s.id
                WHERE m.user_id IN (r.requester_id, r.partner_id)
                  AND s.kind = 'PERSONAL' AND s.archived_at IS NULL
                ORDER BY s.created_at
    LOOP
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
               SET space_id = fam, category_id = target, origin_space_id = src.id
             WHERE space_id = src.id AND category_id = c.id;
        END LOOP;

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

-- Lo que la app puede ver de las solicitudes: solo las propias, con nombres para mostrar.
CREATE FUNCTION merge_requests_mine()
    RETURNS TABLE (id uuid, family_name text, status text, i_am_requester boolean,
                   other_display_name text, created_at timestamptz, expires_at timestamptz,
                   family_space_id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = casaayg, pg_temp AS $$
    SELECT r.id, r.family_name,
           CASE WHEN r.status = 'PENDIENTE' AND r.expires_at <= now() THEN 'VENCIDA' ELSE r.status END,
           r.requester_id = current_app_user(),
           o.display_name, r.created_at, r.expires_at, r.family_space_id
      FROM merge_request r
      JOIN app_user o ON o.id = CASE WHEN r.requester_id = current_app_user()
                                     THEN r.partner_id ELSE r.requester_id END
     WHERE current_app_user() IN (r.requester_id, r.partner_id)
     ORDER BY r.created_at DESC
$$;

REVOKE ALL ON FUNCTION in_active_family(uuid), merge_propose(text, text), merge_decline(uuid),
    merge_approve(uuid), merge_requests_mine() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION merge_propose(text, text), merge_decline(uuid), merge_approve(uuid),
    merge_requests_mine() TO casaayg_app;
