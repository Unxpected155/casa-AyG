-- V23: los casos que le faltaban a las deudas (V22).
--   1. paid_before: lo que ya se había pagado antes de anotar la deuda. Rebaja lo que falta pero
--      NO es un gasto de ningún mes (la plata salió antes de usar la app).
--   2. Ligar un gasto ya anotado a una deuda: la app puede cambiar transaction.debt_id; la FK
--      compuesta y el trigger de V22 siguen exigiendo mismo espacio, moneda y que no se pase.
--   3. forgiven: lo que la persona perdonó («olvídalo»). Salda sin gasto; se puede quitar.
--   4. installment / installment_day: cuota acordada (₡10 000 los días 15). Aparece en «Por
--      pagar» del mes y tiene aviso, hasta saldar la deuda.
-- Lo que falta = amount − paid_before − forgiven − abonos. Nunca se guarda.

ALTER TABLE debt
    ADD COLUMN paid_before     numeric(14,2) NOT NULL DEFAULT 0 CHECK (paid_before >= 0),
    ADD COLUMN forgiven        numeric(14,2) NOT NULL DEFAULT 0 CHECK (forgiven >= 0),
    ADD COLUMN forgiven_on     date,
    ADD COLUMN installment     numeric(14,2) CHECK (installment > 0),
    ADD COLUMN installment_day smallint      CHECK (installment_day BETWEEN 1 AND 31),
    -- Una deuda pagada entera antes de anotarla no es una deuda.
    ADD CONSTRAINT debt_paid_before_lt CHECK (paid_before < amount),
    ADD CONSTRAINT debt_settled_le CHECK (paid_before + forgiven <= amount),
    ADD CONSTRAINT debt_forgiven_on CHECK ((forgiven = 0) = (forgiven_on IS NULL)),
    -- Día sin monto no tiene sentido; monto sin día sí (aparece en el mes, sin aviso).
    ADD CONSTRAINT debt_installment_day CHECK (installment_day IS NULL OR installment IS NOT NULL),
    ADD CONSTRAINT debt_installment_le CHECK (installment IS NULL OR installment <= amount);

-- Lo ya cubierto de una deuda sin contar sus abonos.
CREATE FUNCTION debt_covered(d debt) RETURNS numeric
    LANGUAGE sql IMMUTABLE SET search_path = casaayg, pg_temp AS $$
    SELECT d.paid_before + d.forgiven
$$;
-- Las funciones nuevas no se pueden ejecutar por defecto (V2): permiso explícito.
GRANT EXECUTE ON FUNCTION debt_covered(debt) TO casaayg_app;

CREATE OR REPLACE FUNCTION check_debt_payment() RETURNS trigger
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
    IF debt_covered(d) + paid + NEW.amount > d.amount THEN
        RAISE EXCEPTION 'el abono pasa lo que falta' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION check_debt_change() RETURNS trigger
    LANGUAGE plpgsql SET search_path = casaayg, pg_temp AS $$
DECLARE
    paid numeric(14,2);
BEGIN
    SELECT COALESCE(sum(amount), 0) INTO paid FROM transaction WHERE debt_id = NEW.id;
    IF NEW.currency <> OLD.currency AND paid > 0 THEN
        RAISE EXCEPTION 'la deuda ya tiene abonos en otra moneda' USING ERRCODE = '23514';
    END IF;
    IF NEW.amount < debt_covered(NEW) + paid THEN
        RAISE EXCEPTION 'la deuda no puede ser menor a lo cubierto' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
DROP TRIGGER debt_change ON debt;
CREATE TRIGGER debt_change BEFORE UPDATE OF amount, currency, paid_before, forgiven ON debt
    FOR EACH ROW EXECUTE FUNCTION check_debt_change();

-- Permisos: la app ahora puede ligar o soltar un abono (debt_id) y fijar lo pagado antes, el
-- perdón y la cuota. Sigue sin poder cambiar espacio, autor ni tipo.
GRANT UPDATE (debt_id) ON transaction TO casaayg_app;
GRANT UPDATE (paid_before, forgiven, forgiven_on, installment, installment_day) ON debt TO casaayg_app;

-- Aviso diario: además de la fecha acordada (FIN), la cuota de cada mes (CUOTA) el día antes y
-- el día, si ese mes todavía no se abonó la cuota y la deuda no está saldada.
DROP FUNCTION debt_reminders(date);
CREATE FUNCTION debt_reminders(p_day date)
    RETURNS TABLE (user_id uuid, creditor text, due_date date, kind text)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = casaayg, pg_temp AS $$
    WITH open_debt AS (
        SELECT d.*, debt_covered(d)
                    + (SELECT COALESCE(sum(t.amount), 0) FROM transaction t WHERE t.debt_id = d.id) AS covered
          FROM debt d
          JOIN space s ON s.id = d.space_id AND s.archived_at IS NULL
    ), due AS (
        SELECT o.space_id, o.creditor, o.due_date, 'FIN' AS kind
          FROM open_debt o
         WHERE o.covered < o.amount AND o.due_date IN (p_day, p_day + 1)
        UNION
        SELECT o.space_id, o.creditor, x.due_date, 'CUOTA'
          FROM open_debt o
         CROSS JOIN LATERAL (VALUES (recurring_due_date(o.installment_day, p_day)),
                                    (recurring_due_date(o.installment_day, p_day + 1))) AS x (due_date)
         WHERE o.covered < o.amount AND o.installment_day IS NOT NULL
           AND x.due_date IN (p_day, p_day + 1)
           AND (SELECT COALESCE(sum(t.amount), 0) FROM transaction t
                 WHERE t.debt_id = o.id AND date_trunc('month', t.tx_date) = date_trunc('month', x.due_date))
               < least(o.installment, o.amount - o.covered)
    )
    SELECT DISTINCT m.user_id, due.creditor, due.due_date, due.kind
      FROM due JOIN space_member m ON m.space_id = due.space_id
$$;
REVOKE ALL ON FUNCTION debt_reminders(date) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION debt_reminders(date) TO casaayg_app;
