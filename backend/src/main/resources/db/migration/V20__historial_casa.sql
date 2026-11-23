-- V20: historial de cambios de la casa (auditoría, fase 3). En la casa los dos pueden editar y
-- borrar los movimientos de los dos; aquí queda quién cambió qué, con el antes y el después.
-- Lo escribe un trigger (no la app): no se puede olvidar ni saltar, y la app no puede editarlo
-- ni borrarlo (solo leerlo). Los espacios personales no se registran.

CREATE TABLE change_log (
    id         bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    space_id   uuid        NOT NULL REFERENCES space (id),
    actor      uuid        NOT NULL REFERENCES app_user (id),
    -- Quién lo había anotado (para avisarle si otro lo cambia).
    owner      uuid        REFERENCES app_user (id),
    entity     text        NOT NULL CHECK (entity IN ('MOVIMIENTO', 'TRANSFERENCIA')),
    entity_id  uuid        NOT NULL,
    action     text        NOT NULL CHECK (action IN ('EDITO', 'BORRO')),
    before     jsonb       NOT NULL CHECK (pg_column_size(before) <= 2048),
    after      jsonb       CHECK (pg_column_size(after) <= 2048),
    changed_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX change_log_space_idx ON change_log (space_id, changed_at DESC);

ALTER TABLE change_log ENABLE ROW LEVEL SECURITY;
GRANT SELECT ON change_log TO casaayg_app;
CREATE POLICY change_log_read ON change_log FOR SELECT TO casaayg_app
    USING (space_id IN (SELECT my_space_ids()));

-- Foto legible de un movimiento: lo que se ve en pantalla, con nombres y no ids.
CREATE FUNCTION tx_snapshot(t transaction) RETURNS jsonb
    LANGUAGE sql STABLE SET search_path = casaayg, pg_temp AS $$
    SELECT jsonb_build_object(
        'descripcion', t.description,
        'monto', t.amount,
        'moneda', t.currency,
        'fecha', t.tx_date,
        'categoria', (SELECT c.name FROM category c WHERE c.id = t.category_id),
        'cuenta', (SELECT p.name FROM place p WHERE p.id = t.place_id))
$$;

CREATE FUNCTION transfer_snapshot(x transfer) RETURNS jsonb
    LANGUAGE sql STABLE SET search_path = casaayg, pg_temp AS $$
    SELECT jsonb_build_object(
        'descripcion', x.description,
        'monto', x.amount,
        'moneda', (SELECT p.currency FROM place p WHERE p.id = x.to_place),
        'fecha', x.tx_date,
        'desde', (SELECT p.name FROM place p WHERE p.id = x.from_place),
        'hacia', (SELECT p.name FROM place p WHERE p.id = x.to_place))
$$;

-- ¿Se registra? Solo en una casa, con una persona detrás (no las tareas sin sesión) y no lo que
-- hace el sistema por su cuenta (reabrir un mes borra lo que anotó el cierre).
CREATE FUNCTION change_log_applies(p_space uuid) RETURNS boolean
    LANGUAGE sql STABLE SET search_path = casaayg, pg_temp AS $$
    SELECT current_app_user() IS NOT NULL
       AND COALESCE(current_setting('casaayg.system_change', true), '') <> 'on'
       AND EXISTS (SELECT 1 FROM space s WHERE s.id = p_space AND s.kind = 'FAMILIA')
$$;

CREATE FUNCTION log_transaction_change() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = casaayg, pg_temp AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF change_log_applies(OLD.space_id) THEN
            INSERT INTO change_log (space_id, actor, owner, entity, entity_id, action, before)
            VALUES (OLD.space_id, current_app_user(), OLD.created_by, 'MOVIMIENTO', OLD.id, 'BORRO', tx_snapshot(OLD));
        END IF;
        RETURN OLD;
    END IF;
    -- Una edición de verdad: mismo espacio (al unir espacios se mueven filas; eso no es editar)
    -- y cambió algo de lo que se ve.
    IF OLD.space_id = NEW.space_id AND change_log_applies(NEW.space_id)
       AND (OLD.description, OLD.amount, OLD.currency, OLD.tx_date, OLD.category_id, OLD.place_id)
           IS DISTINCT FROM (NEW.description, NEW.amount, NEW.currency, NEW.tx_date, NEW.category_id, NEW.place_id) THEN
        INSERT INTO change_log (space_id, actor, owner, entity, entity_id, action, before, after)
        VALUES (NEW.space_id, current_app_user(), OLD.created_by, 'MOVIMIENTO', NEW.id, 'EDITO',
                tx_snapshot(OLD), tx_snapshot(NEW));
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER transaction_change_log AFTER UPDATE OR DELETE ON transaction
    FOR EACH ROW EXECUTE FUNCTION log_transaction_change();

-- Las transferencias no se editan (la app no tiene permiso): solo se borran.
CREATE FUNCTION log_transfer_change() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = casaayg, pg_temp AS $$
BEGIN
    IF change_log_applies(OLD.space_id) THEN
        INSERT INTO change_log (space_id, actor, owner, entity, entity_id, action, before)
        VALUES (OLD.space_id, current_app_user(), OLD.created_by, 'TRANSFERENCIA', OLD.id, 'BORRO',
                transfer_snapshot(OLD));
    END IF;
    RETURN OLD;
END $$;

CREATE TRIGGER transfer_change_log AFTER DELETE ON transfer
    FOR EACH ROW EXECUTE FUNCTION log_transfer_change();

-- Reabrir el mes: lo que borra es lo que anotó el cierre (ya queda MES_REABIERTO en la
-- auditoría). Misma función que V13, marcando esos borrados como del sistema.
CREATE OR REPLACE FUNCTION household_reopen(p_family uuid, p_year smallint, p_month smallint) RETURNS void
    LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = casaayg, pg_temp AS $$
DECLARE
    me  uuid := current_app_user();
    txs uuid[];
    trs uuid[];
BEGIN
    IF me IS NULL OR NOT EXISTS (
            SELECT 1 FROM space s JOIN space_member m ON m.space_id = s.id AND m.user_id = me
             WHERE s.id = p_family AND s.kind = 'FAMILIA' AND s.archived_at IS NULL) THEN
        RAISE EXCEPTION 'casa no encontrada' USING ERRCODE = 'P0002';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM month_close WHERE space_id = p_family AND year = p_year AND month = p_month) THEN
        RAISE EXCEPTION 'mes no cerrado' USING ERRCODE = 'P0002';
    END IF;
    IF EXISTS (SELECT 1 FROM month_close WHERE space_id = p_family
                  AND (year, month) > (p_year, p_month)) THEN
        RAISE EXCEPTION 'solo el último mes cerrado' USING ERRCODE = '55000';
    END IF;

    SELECT array_agg(tx_id) FILTER (WHERE tx_id IS NOT NULL),
           array_agg(transfer_id) FILTER (WHERE transfer_id IS NOT NULL)
      INTO txs, trs
      FROM month_close_item WHERE space_id = p_family AND year = p_year AND month = p_month;
    PERFORM set_config('casaayg.system_change', 'on', true);
    DELETE FROM month_close_item WHERE space_id = p_family AND year = p_year AND month = p_month;
    DELETE FROM transaction WHERE id = ANY (COALESCE(txs, '{}'));
    DELETE FROM transfer WHERE id = ANY (COALESCE(trs, '{}')) AND space_id = p_family;
    DELETE FROM month_close WHERE space_id = p_family AND year = p_year AND month = p_month;
    PERFORM set_config('casaayg.system_change', 'off', true);

    INSERT INTO audit_log (user_id, space_id, event_type, details)
        VALUES (me, p_family, 'MES_REABIERTO', jsonb_build_object('anio', p_year, 'mes', p_month));
END $$;

-- Los triggers no se llaman a mano.
REVOKE ALL ON FUNCTION log_transaction_change(), log_transfer_change() FROM PUBLIC;
