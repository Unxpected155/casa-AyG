-- V13: lo que crea el cierre del mes queda ligado a ese cierre. Así:
--   1. No se puede editar ni borrar a mano (la FK lo impide aunque la app se equivoque).
--   2. Se puede REABRIR el mes: se borra exactamente lo que el cierre creó, en la casa y en
--      el espacio personal de cada quien, y el mes vuelve a quedar abierto.

CREATE TABLE month_close_item (
    space_id    uuid     NOT NULL,
    year        smallint NOT NULL,
    month       smallint NOT NULL,
    -- Un movimiento (de la casa o del espacio personal de alguien) o un traslado de la casa.
    tx_id       uuid     REFERENCES transaction (id),
    transfer_id uuid     REFERENCES transfer (id),
    CHECK ((tx_id IS NULL) <> (transfer_id IS NULL)),
    FOREIGN KEY (space_id, year, month) REFERENCES month_close (space_id, year, month)
);
CREATE UNIQUE INDEX month_close_item_tx_idx ON month_close_item (tx_id) WHERE tx_id IS NOT NULL;
CREATE UNIQUE INDEX month_close_item_transfer_idx ON month_close_item (transfer_id) WHERE transfer_id IS NOT NULL;
CREATE INDEX month_close_item_close_idx ON month_close_item (space_id, year, month);

-- La app solo agrega (al cerrar) y lee; borrar es cosa de household_reopen.
GRANT SELECT, INSERT ON month_close_item TO casaayg_app;
ALTER TABLE month_close_item ENABLE ROW LEVEL SECURITY;
CREATE POLICY month_close_item_read ON month_close_item FOR SELECT TO casaayg_app
    USING (space_id IN (SELECT my_space_ids()));
-- La app solo liga cosas DE LA CASA a un cierre de una casa donde puede escribir. Las
-- subconsultas corren con RLS de quien escribe. El ingreso en el espacio personal de cada
-- quien lo liga household_pay_personal (abajo), nunca la app: así nadie puede ligar (y luego
-- borrar al reabrir) un movimiento personal de la pareja.
CREATE POLICY month_close_item_insert ON month_close_item FOR INSERT TO casaayg_app
    WITH CHECK (space_id IN (SELECT my_writable_space_ids())
        AND (tx_id IS NULL OR tx_id IN (SELECT t.id FROM transaction t WHERE t.space_id = month_close_item.space_id))
        AND (transfer_id IS NULL OR transfer_id IN (SELECT r.id FROM transfer r WHERE r.space_id = month_close_item.space_id)));

-- Reabrir un mes. Solo el ÚLTIMO mes cerrado (si no, los meses siguientes quedarían calculados
-- sobre un cierre que ya no existe). Quien llama tiene que ser miembro de la casa activa.
-- SECURITY DEFINER porque borra el ingreso que el cierre anotó en el espacio personal de la
-- pareja; solo toca filas ligadas a ese cierre en month_close_item.
CREATE FUNCTION household_reopen(p_family uuid, p_year smallint, p_month smallint) RETURNS void
    LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = casaayg, pg_temp AS $$
DECLARE
    me  uuid := current_app_user();
    txs uuid[];
    trs uuid[];
BEGIN
    IF me IS NULL OR NOT EXISTS (
            SELECT 1 FROM space s JOIN space_member m ON m.space_id = s.id AND m.user_id = me
             WHERE s.id = p_family AND s.kind = 'FAMILIA' AND s.archived_at IS NULL) THEN
        RAISE EXCEPTION 'casa no encontrada' USING ERRCODE = 'P0002';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM month_close WHERE space_id = p_family AND year = p_year AND month = p_month) THEN
        RAISE EXCEPTION 'mes no cerrado' USING ERRCODE = 'P0002';
    END IF;
    IF EXISTS (SELECT 1 FROM month_close WHERE space_id = p_family
                  AND (year, month) > (p_year, p_month)) THEN
        RAISE EXCEPTION 'solo el último mes cerrado' USING ERRCODE = '55000';
    END IF;

    SELECT array_agg(tx_id) FILTER (WHERE tx_id IS NOT NULL),
           array_agg(transfer_id) FILTER (WHERE transfer_id IS NOT NULL)
      INTO txs, trs
      FROM month_close_item WHERE space_id = p_family AND year = p_year AND month = p_month;
    DELETE FROM month_close_item WHERE space_id = p_family AND year = p_year AND month = p_month;
    DELETE FROM transaction WHERE id = ANY (COALESCE(txs, '{}'));
    DELETE FROM transfer WHERE id = ANY (COALESCE(trs, '{}')) AND space_id = p_family;
    DELETE FROM month_close WHERE space_id = p_family AND year = p_year AND month = p_month;

    INSERT INTO audit_log (user_id, space_id, event_type, details)
        VALUES (me, p_family, 'MES_REABIERTO', jsonb_build_object('anio', p_year, 'mes', p_month));
END $$;
REVOKE ALL ON FUNCTION household_reopen(uuid, smallint, smallint) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION household_reopen(uuid, smallint, smallint) TO casaayg_app;

-- El pago del dinero personal ahora recibe el mes y se liga a su cierre. La versión de V11/V12
-- (sin mes) se retira: ya nadie la llama.
DROP FUNCTION household_pay_personal(uuid, uuid, numeric, date, text);
CREATE FUNCTION household_pay_personal(p_family uuid, p_user uuid, p_amount numeric, p_date date,
                                       p_description text, p_year smallint, p_month smallint) RETURNS uuid
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
    -- Solo dentro del cierre del mes: el cierre se insertó en ESTA misma transacción
    -- (closed_at = now(), que es la hora de inicio de la transacción) y el monto es el suyo.
    IF NOT EXISTS (SELECT 1 FROM month_close
                    WHERE space_id = p_family AND year = p_year AND month = p_month
                      AND closed_at = now() AND per_person = p_amount) THEN
        RAISE EXCEPTION 'fuera de un cierre' USING ERRCODE = '55000';
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
    -- Queda ligado a su cierre: así se puede reabrir el mes.
    INSERT INTO month_close_item (space_id, year, month, tx_id) VALUES (p_family, p_year, p_month, v_id);
    INSERT INTO audit_log (user_id, space_id, event_type, details)
        VALUES (me, p_family, 'DINERO_PERSONAL', jsonb_build_object('para', p_user, 'monto', p_amount));
    RETURN v_id;
END $$;
REVOKE ALL ON FUNCTION household_pay_personal(uuid, uuid, numeric, date, text, smallint, smallint) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION household_pay_personal(uuid, uuid, numeric, date, text, smallint, smallint) TO casaayg_app;
