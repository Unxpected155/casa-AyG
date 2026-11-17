-- V7: diezmo ligado a cada ingreso y renovación automática del CDP.
--
-- Diezmo: al anotar un ingreso con "Dar diezmo", se anota a la vez un gasto en la categoría
-- Diezmo por el 10 % del monto BRUTO. El gasto queda ligado a su ingreso (tithe_of): si se
-- borra el ingreso, se borra su diezmo.
--
-- CDP: un lugar de tipo CDP guarda su tasa, plazo, base de días, impuesto, vencimiento y la
-- cuenta donde caen los intereses. Al renovar, la app calcula el interés con el saldo del CDP.

-- ---------------------------------------------------------------------------
-- Movimientos: monto bruto (base del diezmo) y diezmo ligado a su ingreso
-- ---------------------------------------------------------------------------
ALTER TABLE transaction
    ADD COLUMN gross_amount numeric(14,2) CHECK (gross_amount >= 0),
    ADD COLUMN tithe_of uuid,
    ADD CONSTRAINT transaction_id_space_key UNIQUE (id, space_id),
    ADD CHECK (tithe_of IS NULL OR tithe_of <> id);
-- FK compuesta: el diezmo y su ingreso son del mismo espacio.
ALTER TABLE transaction ADD FOREIGN KEY (tithe_of, space_id)
    REFERENCES transaction (id, space_id) ON DELETE CASCADE;
CREATE UNIQUE INDEX transaction_tithe_idx ON transaction (tithe_of) WHERE tithe_of IS NOT NULL;

GRANT UPDATE (gross_amount) ON transaction TO casaayg_app;

-- ---------------------------------------------------------------------------
-- CDP
-- ---------------------------------------------------------------------------
ALTER TABLE place
    ADD COLUMN cdp_rate              numeric(6,4)  CHECK (cdp_rate > 0 AND cdp_rate < 100),
    ADD COLUMN cdp_term_days         smallint      CHECK (cdp_term_days BETWEEN 1 AND 3660),
    ADD COLUMN cdp_day_basis         smallint      CHECK (cdp_day_basis IN (360, 365)),
    ADD COLUMN cdp_tax_pct           numeric(5,2)  CHECK (cdp_tax_pct >= 0 AND cdp_tax_pct < 100),
    ADD COLUMN cdp_matures_on        date,
    ADD COLUMN cdp_payout_place      uuid,
    ADD COLUMN cdp_last_contribution numeric(14,2) CHECK (cdp_last_contribution >= 0),
    ADD CHECK (cdp_payout_place IS NULL OR cdp_payout_place <> id),
    -- Solo un CDP tiene datos de CDP.
    ADD CHECK (kind = 'CDP' OR (cdp_rate IS NULL AND cdp_term_days IS NULL AND cdp_day_basis IS NULL
                                AND cdp_tax_pct IS NULL AND cdp_matures_on IS NULL
                                AND cdp_payout_place IS NULL AND cdp_last_contribution IS NULL));
ALTER TABLE place ADD FOREIGN KEY (cdp_payout_place, space_id) REFERENCES place (id, space_id);

GRANT UPDATE (cdp_rate, cdp_term_days, cdp_day_basis, cdp_tax_pct, cdp_matures_on, cdp_payout_place,
              cdp_last_contribution) ON place TO casaayg_app;

-- La fusión (V6) copia los lugares sin estos datos: el saldo se conserva y la configuración del
-- CDP se vuelve a poner en el espacio familiar. La fusión se rediseña en la parte 4.

-- ---------------------------------------------------------------------------
-- Categorías nuevas: Diezmo (necesidad) e Intereses (ingreso)
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION create_default_categories(p_space uuid, p_by uuid) RETURNS void
    LANGUAGE sql SET search_path = casaayg, pg_temp AS $$
    INSERT INTO category (space_id, created_by, name, category_group)
    SELECT p_space, p_by, v.name, v.grp FROM (VALUES
        ('Salario', 'INGRESO'), ('Intereses', 'INGRESO'), ('Otros ingresos', 'INGRESO'),
        ('Supermercado', 'NECESIDAD'), ('Vivienda', 'NECESIDAD'),
        ('Servicios (luz, agua, internet)', 'NECESIDAD'), ('Transporte', 'NECESIDAD'),
        ('Salud', 'NECESIDAD'), ('Seguros', 'NECESIDAD'), ('Educación', 'NECESIDAD'),
        ('Diezmo', 'NECESIDAD'),
        ('Restaurantes y salidas', 'DESEO'), ('Entretenimiento', 'DESEO'), ('Ropa', 'DESEO'),
        ('Viajes', 'DESEO'), ('Regalos', 'DESEO'),
        ('Ahorro', 'AHORRO'), ('Inversiones', 'AHORRO'), ('Pago de deudas', 'AHORRO')
    ) AS v (name, grp)
$$;

-- Espacios que ya existen: se agregan las dos si no las tienen.
INSERT INTO category (space_id, created_by, name, category_group)
SELECT s.id, (SELECT m.user_id FROM space_member m WHERE m.space_id = s.id ORDER BY m.user_id LIMIT 1),
       v.name, v.grp
  FROM space s
 CROSS JOIN (VALUES ('Diezmo', 'NECESIDAD'), ('Intereses', 'INGRESO')) AS v (name, grp)
 WHERE s.archived_at IS NULL
   AND EXISTS (SELECT 1 FROM space_member m WHERE m.space_id = s.id)
   AND NOT EXISTS (SELECT 1 FROM category c WHERE c.space_id = s.id AND lower(c.name) = lower(v.name));
