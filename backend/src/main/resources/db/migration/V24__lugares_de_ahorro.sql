-- V24: el ahorro es lo que está en un LUGAR DE AHORRO (PRODUCT.md, «Dinero: tres partes»).
-- Hasta ahora «apartado» se deducía (un CDP o una cuenta ligada a una meta); ahora es una marca
-- de cada lugar, que la persona puede cambiar. Además, el fondo de emergencia deja de inflarse
-- cuando hay pocos meses de datos.

ALTER TABLE place ADD COLUMN savings boolean NOT NULL DEFAULT false;
GRANT UPDATE (savings) ON place TO casaayg_app;

-- Al empezar quedan marcados los que ya contaban como apartados: los CDP y las cuentas de una meta.
UPDATE place p SET savings = true
 WHERE p.kind = 'CDP'
    OR EXISTS (SELECT 1 FROM goal_place gp JOIN goal g ON g.id = gp.goal_id AND g.space_id = gp.space_id
                WHERE gp.place_id = p.id AND g.status IN ('ACTIVA', 'PAUSADA'));

-- Un CDP nuevo es de ahorro (también los que copia la unión de espacios).
CREATE FUNCTION place_cdp_is_savings() RETURNS trigger
    LANGUAGE plpgsql SET search_path = casaayg, pg_temp AS $$
BEGIN
    IF NEW.kind = 'CDP' THEN
        NEW.savings := true;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER place_cdp_is_savings BEFORE INSERT ON place
    FOR EACH ROW EXECUTE FUNCTION place_cdp_is_savings();

-- Ligar una cuenta a una meta la vuelve de ahorro: ese dinero ya tiene un propósito.
-- Corre con los permisos de quien escribe: solo toca lugares que RLS ya le deja ver.
CREATE FUNCTION goal_place_is_savings() RETURNS trigger
    LANGUAGE plpgsql SET search_path = casaayg, pg_temp AS $$
BEGIN
    UPDATE place SET savings = true WHERE id = NEW.place_id AND space_id = NEW.space_id AND NOT savings;
    RETURN NEW;
END $$;
CREATE TRIGGER goal_place_is_savings AFTER INSERT ON goal_place
    FOR EACH ROW EXECUTE FUNCTION goal_place_is_savings();

-- «Apartado» = lugar de ahorro. Se conserva el nombre para que el resumen del mes (lo movido al
-- ahorro) y el saldo disponible sigan usando una sola regla.
CREATE OR REPLACE FUNCTION place_is_reserved(p_place uuid) RETURNS boolean
    LANGUAGE sql STABLE SET search_path = casaayg, pg_temp AS $$
    SELECT p.savings FROM place p WHERE p.id = p_place
$$;

-- Lo que entró NETO a los lugares de ahorro en un período, en colones: lo movido desde otra
-- cuenta, menos lo que volvió, más los ingresos que entraron directo, menos lo que se gastó
-- desde un ahorro. Es la base de la tasa de ahorro del mes. Lo que mueve el cierre del mes de la
-- casa no cuenta. Sin tipo de cambio, los dólares cuentan 0 (como en la Historia).
CREATE FUNCTION period_savings_net_crc(p_space uuid, p_from date, p_to date) RETURNS numeric
    LANGUAGE sql STABLE SET search_path = casaayg, pg_temp AS $$
    SELECT COALESCE((
        SELECT sum(CASE WHEN tp.savings THEN 1 ELSE -1 END
                   * CASE WHEN tp.currency = 'CRC' THEN x.amount
                          ELSE COALESCE(x.amount * usd_sell_rate(x.tx_date), 0) END)
          FROM transfer x
          JOIN place tp ON tp.id = x.to_place AND tp.space_id = x.space_id
          JOIN place fp ON fp.id = x.from_place AND fp.space_id = x.space_id
         WHERE x.space_id = p_space AND x.tx_date >= p_from AND x.tx_date < p_to
           AND tp.savings <> fp.savings
           AND NOT EXISTS (SELECT 1 FROM month_close_item i WHERE i.transfer_id = x.id)), 0)
         + COALESCE((
        SELECT sum(CASE WHEN c.category_group = 'INGRESO' THEN 1 ELSE -1 END
                   * CASE WHEN t.currency = 'CRC' THEN t.amount
                          ELSE COALESCE(t.amount * usd_sell_rate(t.tx_date), 0) END)
          FROM transaction t
          JOIN category c ON c.id = t.category_id
          JOIN place p ON p.id = t.place_id AND p.space_id = t.space_id
         WHERE t.space_id = p_space AND t.tx_date >= p_from AND t.tx_date < p_to AND p.savings), 0)
$$;
GRANT EXECUTE ON FUNCTION period_savings_net_crc(uuid, date, date) TO casaayg_app;

-- Base del fondo de emergencia: lo que cuesta VIVIR (necesidades y deseos) al mes, en la moneda
-- de la meta, con los gastos de la otra moneda convertidos al tipo de cambio de su fecha.
-- Se promedian los meses COMPLETOS con datos entre los últimos 3 (antes se dividía siempre entre
-- 3, y con un solo mes de datos el fondo parecía cubrir el triple). Si todavía no hay ningún mes
-- completo con datos, se usa lo planeado este mes en necesidades y deseos.
CREATE OR REPLACE FUNCTION monthly_expenses(p_space uuid, p_currency char(3), p_day date) RETURNS numeric
    LANGUAGE sql STABLE SET search_path = casaayg, pg_temp AS $$
    WITH bounds AS (
        SELECT (date_trunc('month', p_day) - interval '3 months')::date AS from_day,
               date_trunc('month', p_day)::date AS to_day
    ), months AS (
        SELECT count(DISTINCT date_trunc('month', t.tx_date)) AS n
          FROM transaction t, bounds b
         WHERE t.space_id = p_space AND t.tx_date >= b.from_day AND t.tx_date < b.to_day
    ), spent AS (
        SELECT COALESCE(sum(CASE WHEN t.currency = p_currency THEN t.amount
                                 WHEN p_currency = 'CRC' THEN COALESCE(t.amount * usd_sell_rate(t.tx_date), 0)
                                 ELSE COALESCE(t.amount / NULLIF(usd_sell_rate(t.tx_date), 0), 0) END), 0) AS total
          FROM transaction t JOIN category c ON c.id = t.category_id, bounds b
         WHERE t.space_id = p_space AND c.category_group IN ('NECESIDAD', 'DESEO')
           AND t.tx_date >= b.from_day AND t.tx_date < b.to_day
    ), planned AS (
        SELECT COALESCE(sum(bl.planned_amount), 0) AS crc
          FROM budget b
          JOIN budget_line bl ON bl.budget_id = b.id AND bl.space_id = b.space_id
          JOIN category c ON c.id = bl.category_id AND c.space_id = bl.space_id
         WHERE b.space_id = p_space AND b.year = extract(year FROM p_day) AND b.month = extract(month FROM p_day)
           AND c.category_group IN ('NECESIDAD', 'DESEO')
    )
    SELECT round(CASE
               WHEN m.n > 0 THEN s.total / m.n
               WHEN p_currency = 'CRC' THEN pl.crc
               ELSE COALESCE(pl.crc / NULLIF(usd_sell_rate(p_day), 0), 0) END, 2)
      FROM months m, spent s, planned pl
$$;
