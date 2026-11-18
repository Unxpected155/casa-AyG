-- V10: metas de ahorro. El avance de una meta es el saldo de los lugares ligados a ella (un
-- CDP, un sobre): nunca se anota dos veces. Una meta puede tener partes (boda, luna de miel…)
-- y el fondo de emergencia calcula su objetivo con los gastos reales.

-- ---------------------------------------------------------------------------
-- Saldo de un lugar a una fecha: UNA sola fórmula para la app, las metas y los avisos.
-- (Misma regla que V6: lo anotado desde la fecha del saldo inicial hasta p_day.)
-- ---------------------------------------------------------------------------
CREATE FUNCTION place_balance(p_place uuid, p_day date) RETURNS numeric
    LANGUAGE sql STABLE SET search_path = casaayg, pg_temp AS $$
    SELECT p.opening_balance
         + COALESCE((SELECT sum(CASE WHEN c.category_group = 'INGRESO' THEN t.amount ELSE -t.amount END)
                       FROM transaction t JOIN category c ON c.id = t.category_id
                      WHERE t.place_id = p.id AND t.tx_date >= p.opening_date AND t.tx_date <= p_day), 0)
         + COALESCE((SELECT sum(x.amount) FROM transfer x
                      WHERE x.to_place = p.id AND x.tx_date >= p.opening_date AND x.tx_date <= p_day), 0)
         - COALESCE((SELECT sum(x.amount) FROM transfer x
                      WHERE x.from_place = p.id AND x.tx_date >= p.opening_date AND x.tx_date <= p_day), 0)
      FROM place p WHERE p.id = p_place
$$;
GRANT EXECUTE ON FUNCTION place_balance(uuid, date) TO casaayg_app;

-- Gasto mensual promedio de los últimos 3 meses completos, en una moneda (base del fondo de
-- emergencia). Ingresos y dinero movido no cuentan.
CREATE FUNCTION monthly_expenses(p_space uuid, p_currency char(3), p_day date) RETURNS numeric
    LANGUAGE sql STABLE SET search_path = casaayg, pg_temp AS $$
    SELECT round(COALESCE(sum(t.amount), 0) / 3, 2)
      FROM transaction t JOIN category c ON c.id = t.category_id
     WHERE t.space_id = p_space AND t.currency = p_currency AND c.category_group <> 'INGRESO'
       AND t.tx_date >= (date_trunc('month', p_day) - interval '3 months')::date
       AND t.tx_date < date_trunc('month', p_day)::date
$$;
GRANT EXECUTE ON FUNCTION monthly_expenses(uuid, char, date) TO casaayg_app;

CREATE TABLE goal (
    id               uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    space_id         uuid          NOT NULL REFERENCES space (id),
    created_by       uuid          NOT NULL REFERENCES app_user (id),
    name             text          NOT NULL CHECK (length(name) BETWEEN 1 AND 60),
    kind             text          NOT NULL DEFAULT 'NORMAL' CHECK (kind IN ('NORMAL', 'EMERGENCIA')),
    target_amount    numeric(14,2) CHECK (target_amount > 0),
    emergency_months smallint      CHECK (emergency_months BETWEEN 1 AND 12),
    currency         char(3)       NOT NULL DEFAULT 'CRC' CHECK (currency IN ('CRC', 'USD')),
    target_date      date,
    priority         integer       NOT NULL DEFAULT 0,
    status           text          NOT NULL DEFAULT 'ACTIVA'
                                   CHECK (status IN ('ACTIVA', 'PAUSADA', 'CUMPLIDA', 'ARCHIVADA')),
    achieved_at      timestamptz,
    last_milestone   smallint      NOT NULL DEFAULT 0 CHECK (last_milestone IN (0, 25, 50, 75, 100)),
    created_at       timestamptz   NOT NULL DEFAULT now(),
    UNIQUE (id, space_id),
    -- Una meta normal tiene monto; el fondo de emergencia, cuántos meses de gastos.
    CHECK ((kind = 'NORMAL' AND target_amount IS NOT NULL AND emergency_months IS NULL)
        OR (kind = 'EMERGENCIA' AND emergency_months IS NOT NULL))
);
CREATE UNIQUE INDEX goal_name_idx ON goal (space_id, lower(name));

-- Lugares cuyo saldo cuenta para la meta. Un lugar va a UNA sola meta (si no, se contaría doble).
CREATE TABLE goal_place (
    goal_id  uuid NOT NULL,
    place_id uuid NOT NULL UNIQUE,
    space_id uuid NOT NULL,
    PRIMARY KEY (goal_id, place_id),
    FOREIGN KEY (goal_id, space_id)  REFERENCES goal (id, space_id) ON DELETE CASCADE,
    FOREIGN KEY (place_id, space_id) REFERENCES place (id, space_id) ON DELETE CASCADE
);

CREATE TABLE goal_part (
    id       uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    goal_id  uuid          NOT NULL,
    space_id uuid          NOT NULL,
    name     text          NOT NULL CHECK (length(name) BETWEEN 1 AND 60),
    amount   numeric(14,2) NOT NULL CHECK (amount > 0),
    position smallint      NOT NULL DEFAULT 0,
    FOREIGN KEY (goal_id, space_id) REFERENCES goal (id, space_id) ON DELETE CASCADE
);

-- Objetivo vigente de una meta (el del fondo de emergencia cambia con los gastos).
CREATE FUNCTION goal_target(p_goal uuid, p_day date) RETURNS numeric
    LANGUAGE sql STABLE SET search_path = casaayg, pg_temp AS $$
    SELECT CASE WHEN g.kind = 'EMERGENCIA'
                THEN monthly_expenses(g.space_id, g.currency, p_day) * g.emergency_months
                ELSE g.target_amount END
      FROM goal g WHERE g.id = p_goal
$$;
GRANT EXECUTE ON FUNCTION goal_target(uuid, date) TO casaayg_app;

-- Lo ahorrado: suma de los saldos de sus lugares (solo los de la moneda de la meta).
CREATE FUNCTION goal_saved(p_goal uuid, p_day date) RETURNS numeric
    LANGUAGE sql STABLE SET search_path = casaayg, pg_temp AS $$
    SELECT COALESCE(sum(place_balance(gp.place_id, p_day)), 0)
      FROM goal_place gp JOIN goal g ON g.id = gp.goal_id JOIN place p ON p.id = gp.place_id
     WHERE gp.goal_id = p_goal AND p.currency = g.currency
$$;
GRANT EXECUTE ON FUNCTION goal_saved(uuid, date) TO casaayg_app;

GRANT SELECT, INSERT, DELETE ON goal, goal_place, goal_part TO casaayg_app;
GRANT UPDATE (name, target_amount, emergency_months, currency, target_date, priority, status, achieved_at)
    ON goal TO casaayg_app;

ALTER TABLE goal ENABLE ROW LEVEL SECURITY;
CREATE POLICY goal_read ON goal FOR SELECT TO casaayg_app USING (space_id IN (SELECT my_space_ids()));
CREATE POLICY goal_insert ON goal FOR INSERT TO casaayg_app
    WITH CHECK (space_id IN (SELECT my_writable_space_ids()) AND created_by = current_app_user());
CREATE POLICY goal_update ON goal FOR UPDATE TO casaayg_app
    USING (space_id IN (SELECT my_writable_space_ids())) WITH CHECK (space_id IN (SELECT my_writable_space_ids()));
CREATE POLICY goal_delete ON goal FOR DELETE TO casaayg_app USING (space_id IN (SELECT my_writable_space_ids()));

ALTER TABLE goal_place ENABLE ROW LEVEL SECURITY;
CREATE POLICY goal_place_read ON goal_place FOR SELECT TO casaayg_app USING (space_id IN (SELECT my_space_ids()));
CREATE POLICY goal_place_write ON goal_place FOR INSERT TO casaayg_app
    WITH CHECK (space_id IN (SELECT my_writable_space_ids()));
CREATE POLICY goal_place_delete ON goal_place FOR DELETE TO casaayg_app
    USING (space_id IN (SELECT my_writable_space_ids()));

ALTER TABLE goal_part ENABLE ROW LEVEL SECURITY;
CREATE POLICY goal_part_read ON goal_part FOR SELECT TO casaayg_app USING (space_id IN (SELECT my_space_ids()));
CREATE POLICY goal_part_write ON goal_part FOR INSERT TO casaayg_app
    WITH CHECK (space_id IN (SELECT my_writable_space_ids()));
CREATE POLICY goal_part_delete ON goal_part FOR DELETE TO casaayg_app
    USING (space_id IN (SELECT my_writable_space_ids()));

-- ---------------------------------------------------------------------------
-- Momentos de avance (25, 50, 75 y 100 %). Corre sin sesión desde el aviso diario: marca el
-- nuevo hito y devuelve a quién avisar. Solo el nombre de la meta y el %, nunca montos.
-- ---------------------------------------------------------------------------
CREATE FUNCTION goal_milestones(p_day date) RETURNS TABLE (user_id uuid, goal_name text, milestone smallint)
    LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = casaayg, pg_temp AS $$
DECLARE
    g      record;
    target numeric;
    pct    numeric;
    reached smallint;
BEGIN
    FOR g IN SELECT x.* FROM goal x JOIN space s ON s.id = x.space_id AND s.archived_at IS NULL
              WHERE x.status = 'ACTIVA' LOOP
        target := goal_target(g.id, p_day);
        CONTINUE WHEN target IS NULL OR target <= 0;
        pct := goal_saved(g.id, p_day) * 100 / target;
        reached := CASE WHEN pct >= 100 THEN 100 WHEN pct >= 75 THEN 75 WHEN pct >= 50 THEN 50
                        WHEN pct >= 25 THEN 25 ELSE 0 END;
        CONTINUE WHEN reached <= g.last_milestone;
        UPDATE goal SET last_milestone = reached,
                        status = CASE WHEN reached = 100 THEN 'CUMPLIDA' ELSE status END,
                        achieved_at = CASE WHEN reached = 100 THEN now() ELSE achieved_at END
         WHERE id = g.id;
        RETURN QUERY SELECT m.user_id, g.name, reached FROM space_member m WHERE m.space_id = g.space_id;
    END LOOP;
END $$;
REVOKE ALL ON FUNCTION goal_milestones(date) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION goal_milestones(date) TO casaayg_app;
