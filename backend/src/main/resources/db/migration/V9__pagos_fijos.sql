-- V9: pagos fijos (agua, luz, internet, alquiler…). Cada uno tiene categoría y, si se quiere,
-- un día de pago: con día hay aviso push; sin día solo aparece en "Por pagar" del mes.
-- Pagarlo es anotar un movimiento ligado (transaction.recurring_id): así se sabe qué falta.

CREATE TABLE recurring_payment (
    id                 uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    space_id           uuid          NOT NULL REFERENCES space (id),
    created_by         uuid          NOT NULL REFERENCES app_user (id),
    name               text          NOT NULL CHECK (length(name) BETWEEN 1 AND 60),
    category_id        uuid          NOT NULL,
    place_id           uuid,
    -- Día del mes (en meses cortos, el último día). NULL = sin fecha fija, sin aviso.
    due_day            smallint      CHECK (due_day BETWEEN 1 AND 31),
    expected_amount    numeric(14,2) CHECK (expected_amount >= 0),
    currency           char(3)       NOT NULL DEFAULT 'CRC' CHECK (currency IN ('CRC', 'USD')),
    notify_days_before smallint      NOT NULL DEFAULT 0 CHECK (notify_days_before IN (0, 1, 3)),
    active             boolean       NOT NULL DEFAULT true,
    created_at         timestamptz   NOT NULL DEFAULT now(),
    UNIQUE (id, space_id),
    -- Mismo espacio que su categoría y su lugar. Si se borra la categoría, se va el pago fijo;
    -- si se borra el lugar, el pago fijo queda sin lugar.
    FOREIGN KEY (category_id, space_id) REFERENCES category (id, space_id) ON DELETE CASCADE,
    FOREIGN KEY (place_id, space_id) REFERENCES place (id, space_id) ON DELETE SET NULL (place_id)
);
CREATE UNIQUE INDEX recurring_payment_name_idx ON recurring_payment (space_id, lower(name));

-- El movimiento con el que se pagó. FK simple (no compuesta) para que la fusión pueda mover
-- movimientos de espacio; la regla "mismo espacio" la revisa un trigger al anotarlo.
ALTER TABLE transaction ADD COLUMN recurring_id uuid REFERENCES recurring_payment (id) ON DELETE SET NULL;
CREATE INDEX transaction_recurring_idx ON transaction (recurring_id, tx_date) WHERE recurring_id IS NOT NULL;

CREATE FUNCTION check_recurring_space() RETURNS trigger
    LANGUAGE plpgsql SET search_path = casaayg, pg_temp AS $$
BEGIN
    IF NEW.recurring_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM recurring_payment WHERE id = NEW.recurring_id AND space_id = NEW.space_id) THEN
        RAISE EXCEPTION 'el pago fijo es de otro espacio' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER transaction_recurring_space BEFORE INSERT ON transaction
    FOR EACH ROW EXECUTE FUNCTION check_recurring_space();

GRANT SELECT, INSERT, DELETE ON recurring_payment TO casaayg_app;
GRANT UPDATE (name, category_id, place_id, due_day, expected_amount, currency, notify_days_before, active)
    ON recurring_payment TO casaayg_app;

ALTER TABLE recurring_payment ENABLE ROW LEVEL SECURITY;
CREATE POLICY recurring_read ON recurring_payment FOR SELECT TO casaayg_app
    USING (space_id IN (SELECT my_space_ids()));
CREATE POLICY recurring_insert ON recurring_payment FOR INSERT TO casaayg_app
    WITH CHECK (space_id IN (SELECT my_writable_space_ids()) AND created_by = current_app_user());
CREATE POLICY recurring_update ON recurring_payment FOR UPDATE TO casaayg_app
    USING (space_id IN (SELECT my_writable_space_ids()))
    WITH CHECK (space_id IN (SELECT my_writable_space_ids()));
CREATE POLICY recurring_delete ON recurring_payment FOR DELETE TO casaayg_app
    USING (space_id IN (SELECT my_writable_space_ids()));

-- Día de pago de un mes concreto (31 en febrero → 28/29).
CREATE FUNCTION recurring_due_date(p_due_day smallint, p_month date) RETURNS date
    LANGUAGE sql IMMUTABLE SET search_path = casaayg, pg_temp AS $$
    SELECT make_date(extract(year FROM p_month)::int, extract(month FROM p_month)::int,
                     least(p_due_day::int, extract(day FROM (date_trunc('month', p_month) + interval '1 month - 1 day'))::int))
$$;
GRANT EXECUTE ON FUNCTION recurring_due_date(smallint, date) TO casaayg_app;

-- Aviso diario (corre sin sesión): a quién avisar hoy y de qué pago. Solo el nombre y la
-- fecha, nunca montos. Se avisa el día que toca (fecha − días antes) si ese mes no está pagado.
CREATE FUNCTION recurring_reminders(p_day date)
    RETURNS TABLE (user_id uuid, name text, due_date date)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = casaayg, pg_temp AS $$
    WITH due AS (
        SELECT r.id, r.space_id, r.name, d.due_date, r.notify_days_before
          FROM recurring_payment r
          JOIN space s ON s.id = r.space_id AND s.archived_at IS NULL
         CROSS JOIN LATERAL (VALUES
               (recurring_due_date(r.due_day, p_day::date)),
               (recurring_due_date(r.due_day, (p_day + 3)::date))) AS d (due_date)
         WHERE r.active AND r.due_day IS NOT NULL
    )
    SELECT DISTINCT m.user_id, due.name, due.due_date
      FROM due
      JOIN space_member m ON m.space_id = due.space_id
     WHERE due.due_date - due.notify_days_before = p_day
       AND NOT EXISTS (SELECT 1 FROM transaction t
                        WHERE t.recurring_id = due.id
                          AND date_trunc('month', t.tx_date) = date_trunc('month', due.due_date))
$$;
REVOKE ALL ON FUNCTION recurring_reminders(date) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION recurring_due_date(smallint, date) TO casaayg_app;
GRANT EXECUTE ON FUNCTION recurring_reminders(date) TO casaayg_app;
