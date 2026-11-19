-- V12: el dinero personal siempre cae en un lugar, para que el saldo de cada quien cuadre.
-- Si la persona no eligió destino, va a su cuenta principal en colones.
CREATE OR REPLACE FUNCTION household_pay_personal(p_family uuid, p_user uuid, p_amount numeric, p_date date,
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
    -- Sin destino elegido: su cuenta principal en colones (un banco primero; nunca un CDP,
    -- que es dinero a plazo). Así el saldo que ve la pareja cuadra con lo que recibió.
    IF dest IS NULL THEN
        SELECT p.id INTO dest FROM place p
         WHERE p.space_id = target AND p.currency = 'CRC' AND NOT p.archived AND p.kind <> 'CDP'
           AND p.parent_id IS NULL
         ORDER BY (p.kind = 'BANCO') DESC, p.created_at
         LIMIT 1;
    END IF;
    INSERT INTO transaction (space_id, created_by, category_id, tx_date, description, amount, currency, place_id)
        VALUES (target, me, cat, p_date, left(p_description, 200), p_amount, 'CRC', dest) RETURNING id INTO v_id;
    INSERT INTO audit_log (user_id, space_id, event_type, details)
        VALUES (me, p_family, 'DINERO_PERSONAL', jsonb_build_object('para', p_user, 'monto', p_amount));
    RETURN v_id;
END $$;
