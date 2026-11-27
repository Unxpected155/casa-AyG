-- V25: «Diezmo por dar» (PRODUCT.md, «Diezmo»). Marcar «Dar diezmo» en un ingreso ya no anota
-- el gasto solo: deja el diezmo PENDIENTE. Lo pendiente baja cuando se anota un gasto en la
-- categoría «Diezmo» (el diezmo que de verdad se dio), y se arrastra de mes a mes hasta darlo.

ALTER TABLE transaction ADD COLUMN tithe_owed boolean NOT NULL DEFAULT false;
GRANT UPDATE (tithe_owed) ON transaction TO casaayg_app;

-- Lo anotado antes: los ingresos que ya tienen su gasto de diezmo ligado (V7) quedan marcados.
-- Su diezmo debido y su diezmo dado son el mismo monto, así que no aparece nada pendiente.
UPDATE transaction t SET tithe_owed = true
 WHERE EXISTS (SELECT 1 FROM transaction d WHERE d.tithe_of = t.id AND d.space_id = t.space_id);

-- Diezmo de un espacio, por moneda: lo debido (10 % del bruto de cada ingreso marcado), lo dado
-- (gastos en la categoría «Diezmo») y lo que falta. Lo dado cuenta desde el primer ingreso
-- marcado en esa moneda: un diezmo dado antes de empezar a usar la app no tapa los de después.
-- Si se dio de más, lo pendiente es 0 (no queda como saldo a favor).
CREATE FUNCTION tithe_pending(p_space uuid)
    RETURNS TABLE (currency char(3), owed numeric, given numeric, pending numeric)
    LANGUAGE sql STABLE SET search_path = casaayg, pg_temp AS $$
    WITH owed AS (
        SELECT t.currency, round(COALESCE(t.gross_amount, t.amount) * 0.10, 2) AS amount, t.tx_date
          FROM transaction t
         WHERE t.space_id = p_space AND t.tithe_owed
    ), totals AS (
        SELECT o.currency, sum(o.amount) AS owed, min(o.tx_date) AS since FROM owed o GROUP BY o.currency
    ), given AS (
        SELECT t.currency, sum(t.amount) AS given
          FROM transaction t
          JOIN category c ON c.id = t.category_id AND c.space_id = t.space_id
          JOIN totals s ON s.currency = t.currency
         WHERE t.space_id = p_space AND lower(c.name) = 'diezmo' AND c.category_group <> 'INGRESO'
           AND t.tx_date >= s.since
         GROUP BY t.currency
    )
    SELECT s.currency, s.owed, COALESCE(g.given, 0), greatest(s.owed - COALESCE(g.given, 0), 0)
      FROM totals s LEFT JOIN given g ON g.currency = s.currency
     ORDER BY s.currency
$$;
GRANT EXECUTE ON FUNCTION tithe_pending(uuid) TO casaayg_app;
