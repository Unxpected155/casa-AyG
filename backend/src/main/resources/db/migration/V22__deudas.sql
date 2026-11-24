-- V22: deudas. Lo que se le debe a una persona (a mamá: unas citas médicas, un suéter…).
-- Cada abono es un gasto ligado a la deuda (transaction.debt_id); lo que falta se calcula con
-- los abonos, nunca se guarda: borrar un abono devuelve la deuda sola.
-- `kind` deja lugar para los préstamos con intereses (tasa, plazo) cuando hagan falta.

CREATE TABLE debt (
    id          uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    space_id    uuid          NOT NULL REFERENCES space (id),
    created_by  uuid          NOT NULL REFERENCES app_user (id),
    kind        text          NOT NULL DEFAULT 'PERSONA' CHECK (kind IN ('PERSONA')),
    creditor    text          NOT NULL CHECK (length(creditor) BETWEEN 1 AND 40),
    concept     text          NOT NULL CHECK (length(concept) BETWEEN 1 AND 60),
    amount      numeric(14,2) NOT NULL CHECK (amount > 0),
    currency    char(3)       NOT NULL DEFAULT 'CRC' CHECK (currency IN ('CRC', 'USD')),
    borrowed_on date          NOT NULL CHECK (borrowed_on >= DATE '2000-01-01'),
    -- Fecha acordada para terminar de pagar (opcional): con fecha hay aviso.
    due_date    date          CHECK (due_date >= borrowed_on),
    created_at  timestamptz   NOT NULL DEFAULT now(),
    UNIQUE (id, space_id)
);
CREATE INDEX debt_space_idx ON debt (space_id, lower(creditor));

-- El abono. FK compuesta: un abono solo puede ser de una deuda de su mismo espacio. Si se
-- borra la deuda, los abonos quedan como gastos normales (sí se pagaron).
ALTER TABLE transaction ADD COLUMN debt_id uuid;
ALTER TABLE transaction ADD CONSTRAINT transaction_debt_fk
    FOREIGN KEY (debt_id, space_id) REFERENCES debt (id, space_id) ON DELETE SET NULL (debt_id);
ALTER TABLE transaction ADD CONSTRAINT transaction_debt_or_recurring
    CHECK (debt_id IS NULL OR (recurring_id IS NULL AND tithe_of IS NULL));
CREATE INDEX transaction_debt_idx ON transaction (debt_id) WHERE debt_id IS NOT NULL;

-- Un abono es un gasto, en la moneda de la deuda, y nunca pasa lo que falta. Se bloquea la
-- deuda (FOR UPDATE) para que dos abonos a la vez no la pasen entre los dos.
CREATE FUNCTION check_debt_payment() RETURNS trigger
    LANGUAGE plpgsql SET search_path = casaayg, pg_temp AS $$
DECLARE
    d    debt;
    paid numeric(14,2);
BEGIN
    IF NEW.debt_id IS NULL THEN
        RETURN NEW;
    END IF;
    SELECT * INTO d FROM debt WHERE id = NEW.debt_id AND space_id = NEW.space_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'la deuda es de otro espacio' USING ERRCODE = '23514';
    END IF;
    IF d.currency <> NEW.currency THEN
        RAISE EXCEPTION 'el abono va en la moneda de la deuda' USING ERRCODE = '23514';
    END IF;
    IF EXISTS (SELECT 1 FROM category WHERE id = NEW.category_id AND category_group = 'INGRESO') THEN
        RAISE EXCEPTION 'un abono es un gasto' USING ERRCODE = '23514';
    END IF;
    SELECT COALESCE(sum(amount), 0) INTO paid FROM transaction
     WHERE debt_id = NEW.debt_id AND id <> NEW.id;
    IF paid + NEW.amount > d.amount THEN
        RAISE EXCEPTION 'el abono pasa lo que falta' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER transaction_debt_payment BEFORE INSERT OR UPDATE OF debt_id, amount, currency, category_id
    ON transaction FOR EACH ROW EXECUTE FUNCTION check_debt_payment();

-- La deuda no puede quedar por debajo de lo ya abonado ni cambiar de moneda con abonos.
CREATE FUNCTION check_debt_change() RETURNS trigger
    LANGUAGE plpgsql SET search_path = casaayg, pg_temp AS $$
DECLARE
    paid numeric(14,2);
BEGIN
    SELECT COALESCE(sum(amount), 0) INTO paid FROM transaction WHERE debt_id = NEW.id;
    IF NEW.currency <> OLD.currency AND paid > 0 THEN
        RAISE EXCEPTION 'la deuda ya tiene abonos en otra moneda' USING ERRCODE = '23514';
    END IF;
    IF NEW.amount < paid THEN
        RAISE EXCEPTION 'la deuda no puede ser menor a lo abonado' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER debt_change BEFORE UPDATE OF amount, currency ON debt
    FOR EACH ROW EXECUTE FUNCTION check_debt_change();

-- Permisos (privilegio mínimo, ver V2): la app no cambia espacio, autor ni tipo de una deuda,
-- ni a qué deuda pertenece un abono ya anotado.
GRANT SELECT, INSERT, DELETE ON debt TO casaayg_app;
GRANT UPDATE (creditor, concept, amount, currency, borrowed_on, due_date) ON debt TO casaayg_app;

ALTER TABLE debt ENABLE ROW LEVEL SECURITY;
CREATE POLICY debt_read ON debt FOR SELECT TO casaayg_app
    USING (space_id IN (SELECT my_space_ids()));
CREATE POLICY debt_insert ON debt FOR INSERT TO casaayg_app
    WITH CHECK (space_id IN (SELECT my_writable_space_ids()) AND created_by = current_app_user());
CREATE POLICY debt_update ON debt FOR UPDATE TO casaayg_app
    USING (space_id IN (SELECT my_writable_space_ids()))
    WITH CHECK (space_id IN (SELECT my_writable_space_ids()));
CREATE POLICY debt_delete ON debt FOR DELETE TO casaayg_app
    USING (space_id IN (SELECT my_writable_space_ids()));

-- Aviso diario (corre sin sesión): a quién avisar y a quién le debe. Solo el nombre de la
-- persona, nunca montos ni el concepto. Se avisa el día antes y el día acordado, si falta algo.
CREATE FUNCTION debt_reminders(p_day date)
    RETURNS TABLE (user_id uuid, creditor text, due_date date)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = casaayg, pg_temp AS $$
    SELECT DISTINCT m.user_id, d.creditor, d.due_date
      FROM debt d
      JOIN space s ON s.id = d.space_id AND s.archived_at IS NULL
      JOIN space_member m ON m.space_id = d.space_id
     WHERE d.due_date IN (p_day, p_day + 1)
       AND d.amount > (SELECT COALESCE(sum(t.amount), 0) FROM transaction t WHERE t.debt_id = d.id)
$$;
REVOKE ALL ON FUNCTION debt_reminders(date) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION debt_reminders(date) TO casaayg_app;
