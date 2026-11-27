-- V28: tiquetes electrónicos en el buzón (decisiones del 2026-11-27, ver PRODUCT.md).
-- - Un tiquete casi nunca trae la cédula de quien compra: si la trae, decide la cédula; si no,
--   la variante del correo de la casa a la que llegó (casa+usuario@...).
-- - Un tiquete puede entrar sin la respuesta de Hacienda: queda marcado «sin confirmar por
--   Hacienda». Si la respuesta llega después, se marca confirmado.

ALTER TABLE invoice_inbox DROP CONSTRAINT invoice_inbox_kind_check;
ALTER TABLE invoice_inbox ADD CONSTRAINT invoice_inbox_kind_check
    CHECK (kind IN ('FACTURA', 'NOTA_CREDITO', 'TIQUETE'));

-- Las facturas siempre llegan con la respuesta de Hacienda: las de antes quedan confirmadas.
ALTER TABLE invoice_inbox ADD COLUMN hacienda_confirmed boolean NOT NULL DEFAULT true;
-- Solo un tiquete puede quedar sin confirmar.
ALTER TABLE invoice_inbox ADD CONSTRAINT invoice_inbox_hacienda_check
    CHECK (hacienda_confirmed OR kind = 'TIQUETE');

-- Un correo sin dueño: ni cédula de la casa ni una variante de correo de alguien.
ALTER TABLE mailbox_message DROP CONSTRAINT mailbox_message_result_check;
ALTER TABLE mailbox_message ADD CONSTRAINT mailbox_message_result_check
    CHECK (result IN ('NUEVA', 'REPETIDA', 'AJENA', 'INCOMPLETA', 'RECHAZADA', 'SIN_XML', 'SIN_DUENO', 'CONFIRMADA'));

DROP FUNCTION invoice_inbox_add(uuid, text, text, date, text, text, text, numeric, boolean);
CREATE FUNCTION invoice_inbox_add(p_user uuid, p_clave text, p_kind text, p_issued_on date, p_merchant text,
                                  p_emisor_id text, p_currency text, p_total numeric, p_verified boolean,
                                  p_hacienda boolean)
    RETURNS boolean
    LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = casaayg, pg_temp AS $$
BEGIN
    INSERT INTO invoice_inbox (user_id, clave, kind, issued_on, merchant, emisor_id, currency, total,
                               signature_verified, hacienda_confirmed)
    VALUES (p_user, p_clave, p_kind, p_issued_on, p_merchant, p_emisor_id, p_currency, p_total, p_verified, p_hacienda)
    ON CONFLICT (clave) DO NOTHING;
    RETURN FOUND;
END $$;
REVOKE ALL ON FUNCTION invoice_inbox_add(uuid, text, text, date, text, text, text, numeric, boolean, boolean) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION invoice_inbox_add(uuid, text, text, date, text, text, text, numeric, boolean, boolean)
    TO casaayg_app;

-- La respuesta de Hacienda de un tiquete que ya había entrado sin ella. true = se marcó.
CREATE FUNCTION invoice_inbox_hacienda_confirmed(p_clave text, p_verified boolean) RETURNS boolean
    LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = casaayg, pg_temp AS $$
BEGIN
    UPDATE invoice_inbox
       SET hacienda_confirmed = true, signature_verified = signature_verified OR p_verified
     WHERE clave = p_clave AND NOT hacienda_confirmed;
    RETURN FOUND;
END $$;
REVOKE ALL ON FUNCTION invoice_inbox_hacienda_confirmed(text, boolean) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION invoice_inbox_hacienda_confirmed(text, boolean) TO casaayg_app;
