-- V11: la casa al casarse ("lo tuyo, lo mío y lo nuestro").
--
-- Antes (V5/V6), aprobar la fusión movía todo el historial personal al espacio familiar y
-- archivaba los personales. Ahora:
--   * Se crea el espacio "Nuestro" con sus categorías; los personales SIGUEN VIVOS y privados.
--   * Cada quien ve solo el SALDO personal del otro (partner_balances), nunca sus movimientos.
--   * Aporte al hogar: dinero de un lugar personal a uno de la casa (lo anota la app).
--   * Cerrar el mes: la casa paga a cada quien su dinero personal = piso fijo + bono (% de lo
--     que sobró), igual para los dos. Lo que entra al personal del otro lo escribe una función
--     SECURITY DEFINER: quien cierra nunca ve los lugares personales de su pareja.

-- ---------------------------------------------------------------------------
-- Ajustes de la casa (uno por espacio familiar)
-- ---------------------------------------------------------------------------
CREATE TABLE household_settings (
    space_id       uuid          PRIMARY KEY REFERENCES space (id),
    personal_floor numeric(14,2) NOT NULL DEFAULT 0 CHECK (personal_floor >= 0),
    bonus_pct      numeric(3,1)  NOT NULL DEFAULT 2.5 CHECK (bonus_pct >= 0 AND bonus_pct <= 5),
    updated_at     timestamptz   NOT NULL DEFAULT now()
);
GRANT SELECT, INSERT ON household_settings TO casaayg_app;
GRANT UPDATE (personal_floor, bonus_pct, updated_at) ON household_settings TO casaayg_app;
ALTER TABLE household_settings ENABLE ROW LEVEL SECURITY;
CREATE POLICY household_read ON household_settings FOR SELECT TO casaayg_app
    USING (space_id IN (SELECT my_space_ids()));
CREATE POLICY household_insert ON household_settings FOR INSERT TO casaayg_app
    WITH CHECK (space_id IN (SELECT my_writable_space_ids()));
CREATE POLICY household_update ON household_settings FOR UPDATE TO casaayg_app
    USING (space_id IN (SELECT my_writable_space_ids())) WITH CHECK (space_id IN (SELECT my_writable_space_ids()));

-- Dónde recibe cada quien su dinero personal: un lugar de SU espacio personal. Solo lo ve y lo
-- cambia la propia persona.
CREATE TABLE personal_destination (
    user_id  uuid PRIMARY KEY REFERENCES app_user (id),
    space_id uuid NOT NULL,
    place_id uuid NOT NULL,
    FOREIGN KEY (place_id, space_id) REFERENCES place (id, space_id) ON DELETE CASCADE
);
GRANT SELECT, INSERT, DELETE ON personal_destination TO casaayg_app;
GRANT UPDATE (space_id, place_id) ON personal_destination TO casaayg_app;
ALTER TABLE personal_destination ENABLE ROW LEVEL SECURITY;
CREATE POLICY personal_destination_own ON personal_destination FOR ALL TO casaayg_app
    USING (user_id = current_app_user())
    WITH CHECK (user_id = current_app_user() AND space_id IN (SELECT my_writable_space_ids()));

-- Meses cerrados: uno por mes; impide pagar dos veces.
CREATE TABLE month_close (
    space_id   uuid          NOT NULL REFERENCES space (id),
    year       smallint      NOT NULL CHECK (year BETWEEN 2000 AND 2100),
    month      smallint      NOT NULL CHECK (month BETWEEN 1 AND 12),
    closed_by  uuid          NOT NULL REFERENCES app_user (id),
    surplus    numeric(14,2) NOT NULL,
    bonus_pct  numeric(3,1)  NOT NULL,
    per_person numeric(14,2) NOT NULL CHECK (per_person >= 0),
    closed_at  timestamptz   NOT NULL DEFAULT now(),
    PRIMARY KEY (space_id, year, month)
);
GRANT SELECT, INSERT ON month_close TO casaayg_app;
ALTER TABLE month_close ENABLE ROW LEVEL SECURITY;
CREATE POLICY month_close_read ON month_close FOR SELECT TO casaayg_app USING (space_id IN (SELECT my_space_ids()));
CREATE POLICY month_close_insert ON month_close FOR INSERT TO casaayg_app
    WITH CHECK (space_id IN (SELECT my_writable_space_ids()) AND closed_by = current_app_user());

-- ---------------------------------------------------------------------------
-- Espacio personal activo de una persona (uno solo, por el guardia de V1).
-- ---------------------------------------------------------------------------
CREATE FUNCTION personal_space_of(p_user uuid) RETURNS uuid
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = casaayg, pg_temp AS $$
    SELECT s.id FROM space s JOIN space_member m ON m.space_id = s.id
     WHERE m.user_id = p_user AND s.kind = 'PERSONAL' AND s.archived_at IS NULL
     ORDER BY s.created_at LIMIT 1
$$;
REVOKE ALL ON FUNCTION personal_space_of(uuid) FROM PUBLIC;

-- Saldo personal de cada miembro de una casa (solo totales por moneda, nunca el detalle).
-- Quien pregunta tiene que ser miembro de esa casa; si no, no hay filas.
CREATE FUNCTION partner_balances(p_family uuid)
    RETURNS TABLE (user_id uuid, display_name text, crc numeric, usd numeric)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = casaayg, pg_temp AS $$
    SELECT u.id, u.display_name,
           COALESCE(sum(place_balance(p.id, current_date)) FILTER (WHERE p.currency = 'CRC'), 0),
           COALESCE(sum(place_balance(p.id, current_date)) FILTER (WHERE p.currency = 'USD'), 0)
      FROM space fam
      JOIN space_member m ON m.space_id = fam.id
      JOIN app_user u ON u.id = m.user_id
      LEFT JOIN place p ON p.space_id = personal_space_of(u.id) AND NOT p.archived
     WHERE fam.id = p_family AND fam.kind = 'FAMILIA'
       AND EXISTS (SELECT 1 FROM space_member me WHERE me.space_id = fam.id AND me.user_id = current_app_user())
     GROUP BY u.id, u.display_name
     ORDER BY u.display_name
$$;
REVOKE ALL ON FUNCTION partner_balances(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION partner_balances(uuid) TO casaayg_app;

-- La casa le paga a un miembro su dinero personal: anota el INGRESO en el espacio personal de
-- esa persona (en su lugar de destino, si lo eligió). El GASTO en la casa lo anota la app.
-- Reglas: quien llama y quien recibe son miembros de la casa activa; monto > 0.
CREATE FUNCTION household_pay_personal(p_family uuid, p_user uuid, p_amount numeric, p_date date,
                                       p_description text) RETURNS uuid
    LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = casaayg, pg_temp AS $$
DECLARE
    me       uuid := current_app_user();
    target   uuid;
    cat      uuid;
    dest     uuid;
    v_id     uuid;
BEGIN
    IF me IS NULL OR p_amount IS NULL OR p_amount <= 0 THEN
        RAISE EXCEPTION 'datos inválidos' USING ERRCODE = '22023';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM space s JOIN space_member a ON a.space_id = s.id AND a.user_id = me
                     JOIN space_member b ON b.space_id = s.id AND b.user_id = p_user
                    WHERE s.id = p_family AND s.kind = 'FAMILIA' AND s.archived_at IS NULL) THEN
        RAISE EXCEPTION 'casa no encontrada' USING ERRCODE = 'P0002';
    END IF;
    target := personal_space_of(p_user);
    IF target IS NULL THEN
        RAISE EXCEPTION 'sin espacio personal' USING ERRCODE = 'P0002';
    END IF;
    -- En el personal es un INGRESO ("De la casa"); en la casa, el gasto "Dinero personal".
    SELECT id INTO cat FROM category
     WHERE space_id = target AND lower(name) = 'de la casa' AND category_group = 'INGRESO';
    IF cat IS NULL THEN
        INSERT INTO category (space_id, created_by, name, category_group)
            VALUES (target, p_user, 'De la casa', 'INGRESO') RETURNING id INTO cat;
    END IF;
    SELECT d.place_id INTO dest FROM personal_destination d JOIN place p ON p.id = d.place_id
     WHERE d.user_id = p_user AND d.space_id = target AND p.currency = 'CRC' AND NOT p.archived;
    INSERT INTO transaction (space_id, created_by, category_id, tx_date, description, amount, currency, place_id)
        VALUES (target, me, cat, p_date, left(p_description, 200), p_amount, 'CRC', dest) RETURNING id INTO v_id;
    INSERT INTO audit_log (user_id, space_id, event_type, details)
        VALUES (me, p_family, 'DINERO_PERSONAL', jsonb_build_object('para', p_user, 'monto', p_amount));
    RETURN v_id;
END $$;
REVOKE ALL ON FUNCTION household_pay_personal(uuid, uuid, numeric, date, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION household_pay_personal(uuid, uuid, numeric, date, text) TO casaayg_app;

-- ---------------------------------------------------------------------------
-- Fusión nueva: crea la casa; no mueve ni archiva nada.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION merge_approve(p_request uuid) RETURNS uuid
    LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = casaayg, pg_temp AS $$
DECLARE
    me  uuid := current_app_user();
    r   merge_request;
    fam uuid;
BEGIN
    -- Solo la PAREJA invitada aprueba (quien propuso ya aprobó al proponer).
    SELECT * INTO r FROM merge_request
     WHERE id = p_request AND partner_id = me AND status = 'PENDIENTE' FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'solicitud no encontrada' USING ERRCODE = 'P0002';
    END IF;
    IF r.expires_at <= now() THEN
        UPDATE merge_request SET status = 'VENCIDA', closed_at = now() WHERE id = r.id;
        RETURN NULL;
    END IF;
    IF in_active_family(r.requester_id) OR in_active_family(r.partner_id) THEN
        RAISE EXCEPTION 'ya existe un espacio familiar' USING ERRCODE = '55000';
    END IF;

    INSERT INTO space (kind, name) VALUES ('FAMILIA', r.family_name) RETURNING id INTO fam;
    INSERT INTO space_member (space_id, user_id) VALUES (fam, r.requester_id), (fam, r.partner_id);
    PERFORM create_default_categories(fam, r.requester_id);
    INSERT INTO category (space_id, created_by, name, category_group) VALUES
        (fam, r.requester_id, 'Aportes', 'INGRESO'),
        (fam, r.requester_id, 'Dinero personal', 'NECESIDAD');
    INSERT INTO household_settings (space_id) VALUES (fam);

    UPDATE merge_request
       SET status = 'COMPLETADA', partner_approved_at = now(), closed_at = now(), family_space_id = fam
     WHERE id = r.id;
    INSERT INTO audit_log (user_id, space_id, event_type, details) VALUES
        (r.requester_id, fam, 'FUSION_COMPLETADA', jsonb_build_object('solicitud', r.id)),
        (r.partner_id, fam, 'FUSION_COMPLETADA', jsonb_build_object('solicitud', r.id));
    RETURN fam;
END $$;
