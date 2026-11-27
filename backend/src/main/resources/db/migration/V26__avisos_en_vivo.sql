-- Saldos en tiempo real (decisión del 2026-11-26): lo que anota una persona le aparece a la
-- otra sin recargar.
--
-- Cada cambio en una tabla con datos de un espacio avisa por el canal 'space_changed' con el
-- id del espacio, y nada más: quien recibe el aviso vuelve a pedir los datos por la API, que
-- pasa por la membresía y por RLS. Postgres entrega los avisos solo al confirmar la
-- transacción y junta los repetidos de una misma transacción: un cierre de mes que toca cien
-- filas manda un solo aviso.
-- Fuera quedan audit_log (bitácora de seguridad) y budget_alert (control interno de avisos push).

CREATE FUNCTION notify_space_change() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog
AS $$
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        PERFORM pg_notify('space_changed', OLD.space_id::text);
    END IF;
    IF TG_OP IN ('INSERT', 'UPDATE') THEN
        PERFORM pg_notify('space_changed', NEW.space_id::text);
    END IF;
    RETURN NULL;
END
$$;

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['transaction', 'transfer', 'place', 'category', 'budget', 'budget_line',
                             'goal', 'goal_place', 'goal_part', 'recurring_payment', 'debt',
                             'household_settings', 'personal_destination', 'month_close',
                             'month_close_item', 'change_log', 'space_member']
    LOOP
        EXECUTE format('CREATE TRIGGER %I AFTER INSERT OR UPDATE OR DELETE ON %I '
                       'FOR EACH ROW EXECUTE FUNCTION notify_space_change()', t || '_notify', t);
    END LOOP;
END
$$;
