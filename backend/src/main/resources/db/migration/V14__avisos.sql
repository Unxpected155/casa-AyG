-- V14: avisos de presupuesto y de la casa. Igual que las metas (V10): funciones SECURITY DEFINER
-- que corren sin sesión desde el aviso programado y devuelven a quién avisar. El texto que arma
-- la app nunca lleva montos: solo el nombre de la categoría y el %.

-- Último nivel avisado por categoría y mes (80 o 100): cada nivel se avisa una sola vez.
-- Solo lo toca budget_alerts(); la app no tiene permisos sobre esta tabla.
CREATE TABLE budget_alert (
    space_id    uuid     NOT NULL REFERENCES space (id),
    category_id uuid     NOT NULL,
    year        smallint NOT NULL CHECK (year BETWEEN 2000 AND 2100),
    month       smallint NOT NULL CHECK (month BETWEEN 1 AND 12),
    level       smallint NOT NULL CHECK (level IN (80, 100)),
    sent_at     timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (space_id, category_id, year, month),
    FOREIGN KEY (category_id, space_id) REFERENCES category (id, space_id)
);
ALTER TABLE budget_alert ENABLE ROW LEVEL SECURITY;

-- Categorías de gasto (necesidades y deseos; pasarse en ahorro no es malo) que este mes
-- cruzaron el 80 % o el 100 % de lo planeado. Lo gastado va en colones; los dólares, al tipo
-- de cambio del día de cada movimiento (como el resumen del mes).
CREATE FUNCTION budget_alerts(p_day date)
    RETURNS TABLE (user_id uuid, category_name text, level smallint, family boolean)
    LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = casaayg, pg_temp AS $$
DECLARE
    r       record;
    reached smallint;
    y       smallint := extract(year FROM p_day);
    m       smallint := extract(month FROM p_day);
BEGIN
    FOR r IN
        WITH planned AS (
            SELECT bl.space_id, bl.category_id, bl.planned_amount
              FROM budget b
              JOIN budget_line bl ON bl.budget_id = b.id AND bl.space_id = b.space_id
              JOIN space s ON s.id = b.space_id AND s.archived_at IS NULL
             WHERE b.year = y AND b.month = m AND bl.planned_amount > 0),
        spent AS (
            SELECT t.space_id, t.category_id,
                   sum(CASE WHEN t.currency = 'CRC' THEN t.amount
                            ELSE COALESCE(t.amount * usd_sell_rate(t.tx_date), 0) END) AS amount
              FROM transaction t
             WHERE t.tx_date >= date_trunc('month', p_day)::date AND t.tx_date <= p_day
               AND t.space_id IN (SELECT space_id FROM planned)
             GROUP BY 1, 2)
        SELECT p.space_id, p.category_id, c.name, s.kind,
               COALESCE(sp.amount, 0) * 100 / p.planned_amount AS pct,
               COALESCE(a.level, 0) AS sent
          FROM planned p
          JOIN category c ON c.id = p.category_id AND c.category_group IN ('NECESIDAD', 'DESEO')
          JOIN space s ON s.id = p.space_id
          LEFT JOIN spent sp ON sp.space_id = p.space_id AND sp.category_id = p.category_id
          LEFT JOIN budget_alert a ON a.space_id = p.space_id AND a.category_id = p.category_id
                                  AND a.year = y AND a.month = m
    LOOP
        reached := CASE WHEN r.pct >= 100 THEN 100 WHEN r.pct >= 80 THEN 80 ELSE 0 END;
        CONTINUE WHEN reached <= r.sent;
        INSERT INTO budget_alert (space_id, category_id, year, month, level)
             VALUES (r.space_id, r.category_id, y, m, reached)
        ON CONFLICT (space_id, category_id, year, month)
             DO UPDATE SET level = EXCLUDED.level, sent_at = now();
        RETURN QUERY SELECT sm.user_id, r.name, reached, r.kind = 'FAMILIA'
                       FROM space_member sm WHERE sm.space_id = r.space_id;
    END LOOP;
END $$;
REVOKE ALL ON FUNCTION budget_alerts(date) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION budget_alerts(date) TO casaayg_app;

-- Casas activas que todavía no cerraron el mes anterior (si la casa ya existía ese mes).
CREATE FUNCTION month_close_reminders(p_day date)
    RETURNS TABLE (user_id uuid, year smallint, month smallint)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = casaayg, pg_temp AS $$
    WITH prev AS (SELECT (date_trunc('month', p_day) - interval '1 month')::date AS d)
    SELECT m.user_id, extract(year FROM prev.d)::smallint, extract(month FROM prev.d)::smallint
      FROM space s
      JOIN space_member m ON m.space_id = s.id
      CROSS JOIN prev
     WHERE s.kind = 'FAMILIA' AND s.archived_at IS NULL
       AND date_trunc('month', s.created_at AT TIME ZONE 'America/Costa_Rica')::date <= prev.d
       AND NOT EXISTS (SELECT 1 FROM month_close c
                        WHERE c.space_id = s.id
                          AND c.year = extract(year FROM prev.d) AND c.month = extract(month FROM prev.d))
$$;
REVOKE ALL ON FUNCTION month_close_reminders(date) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION month_close_reminders(date) TO casaayg_app;

-- La otra persona de una propuesta de unir espacios (para avisarle). Solo si quien pregunta
-- es parte de esa propuesta; si no, null.
CREATE FUNCTION merge_other_party(p_request uuid) RETURNS uuid
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = casaayg, pg_temp AS $$
    SELECT CASE WHEN r.requester_id = current_app_user() THEN r.partner_id ELSE r.requester_id END
      FROM merge_request r
     WHERE r.id = p_request AND current_app_user() IN (r.requester_id, r.partner_id)
$$;
REVOKE ALL ON FUNCTION merge_other_party(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION merge_other_party(uuid) TO casaayg_app;
