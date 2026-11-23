-- V18: ahorrar es MOVER dinero (banco → CDP o sobre de una meta), no gastarlo. Anotado como gasto,
-- el dinero salía de la cuenta y no entraba a ninguna (el saldo bajaba sin razón) o, si además se
-- hacía la transferencia, se contaba dos veces. Y el fondo de emergencia lo tomaba como gasto de vida.

-- Apartado: un CDP (está a plazo) o una cuenta ligada a una meta activa o pausada. Misma regla
-- que el saldo «disponible» de la pantalla (Balance.tsx).
CREATE FUNCTION place_is_reserved(p_place uuid) RETURNS boolean
    LANGUAGE sql STABLE SET search_path = casaayg, pg_temp AS $$
    SELECT p.kind = 'CDP' OR EXISTS (
               SELECT 1 FROM goal_place gp JOIN goal g ON g.id = gp.goal_id AND g.space_id = gp.space_id
                WHERE gp.place_id = p.id AND g.status IN ('ACTIVA', 'PAUSADA'))
      FROM place p WHERE p.id = p_place
$$;
GRANT EXECUTE ON FUNCTION place_is_reserved(uuid) TO casaayg_app;

-- Lo ahorrado en un período, por moneda: lo que pasó de una cuenta disponible a una apartada,
-- menos lo que volvió. Renovar un CDP (sale el capital y vuelve con el aporte) cuenta solo el
-- aporte. Lo que mueve el cierre del mes de la casa no cuenta.
CREATE FUNCTION period_savings(p_space uuid, p_from date, p_to date)
    RETURNS TABLE (currency char(3), amount numeric)
    LANGUAGE sql STABLE SET search_path = casaayg, pg_temp AS $$
    SELECT p.currency,
           sum(CASE WHEN place_is_reserved(x.to_place) THEN x.amount ELSE -x.amount END)
      FROM transfer x JOIN place p ON p.id = x.to_place AND p.space_id = x.space_id
     WHERE x.space_id = p_space AND x.tx_date >= p_from AND x.tx_date < p_to
       AND place_is_reserved(x.to_place) <> place_is_reserved(x.from_place)
       AND NOT EXISTS (SELECT 1 FROM month_close_item i WHERE i.transfer_id = x.id)
     GROUP BY p.currency
$$;
GRANT EXECUTE ON FUNCTION period_savings(uuid, date, date) TO casaayg_app;

-- Base del fondo de emergencia: lo que cuesta VIVIR (necesidades y deseos). El ahorro no.
CREATE OR REPLACE FUNCTION monthly_expenses(p_space uuid, p_currency char(3), p_day date) RETURNS numeric
    LANGUAGE sql STABLE SET search_path = casaayg, pg_temp AS $$
    SELECT round(COALESCE(sum(t.amount), 0) / 3, 2)
      FROM transaction t JOIN category c ON c.id = t.category_id
     WHERE t.space_id = p_space AND t.currency = p_currency AND c.category_group IN ('NECESIDAD', 'DESEO')
       AND t.tx_date >= (date_trunc('month', p_day) - interval '3 months')::date
       AND t.tx_date < date_trunc('month', p_day)::date
$$;

-- Categorías: «Ahorro» e «Inversiones» como gasto ya no tienen sentido (se ahorra con una
-- transferencia). Se desactivan donde no tienen movimientos; donde sí, se dejan (no se toca la
-- historia). «Pago de deudas» sí es una salida de dinero: pasa a necesidad (el signo no cambia).
UPDATE category c SET active = false
 WHERE c.category_group = 'AHORRO' AND c.name IN ('Ahorro', 'Inversiones') AND c.active
   AND NOT EXISTS (SELECT 1 FROM transaction t WHERE t.category_id = c.id AND t.space_id = c.space_id);
UPDATE category SET category_group = 'NECESIDAD'
 WHERE category_group = 'AHORRO' AND name = 'Pago de deudas';

-- Igual que V7, sin Ahorro ni Inversiones y con Pago de deudas como necesidad.
CREATE OR REPLACE FUNCTION create_default_categories(p_space uuid, p_by uuid) RETURNS void
    LANGUAGE sql SET search_path = casaayg, pg_temp AS $$
    INSERT INTO category (space_id, created_by, name, category_group)
    SELECT p_space, p_by, v.name, v.grp FROM (VALUES
        ('Salario', 'INGRESO'), ('Intereses', 'INGRESO'), ('Otros ingresos', 'INGRESO'),
        ('Supermercado', 'NECESIDAD'), ('Vivienda', 'NECESIDAD'),
        ('Servicios (luz, agua, internet)', 'NECESIDAD'), ('Transporte', 'NECESIDAD'),
        ('Salud', 'NECESIDAD'), ('Seguros', 'NECESIDAD'), ('Educación', 'NECESIDAD'),
        ('Diezmo', 'NECESIDAD'), ('Pago de deudas', 'NECESIDAD'),
        ('Restaurantes y salidas', 'DESEO'), ('Entretenimiento', 'DESEO'), ('Ropa', 'DESEO'),
        ('Viajes', 'DESEO'), ('Regalos', 'DESEO')
    ) AS v (name, grp)
$$;
