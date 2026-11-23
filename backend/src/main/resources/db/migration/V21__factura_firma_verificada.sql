-- V21: modo práctico de facturas. Una factura bien formada, a tu nombre, aceptada por Hacienda
-- y sin repetir entra a «Por revisar» aunque su firma todavía no se pueda confirmar contra una
-- autoridad de confianza (nada se anota solo: la persona la revisa). Se guarda si la firma
-- quedó verificada, para mostrarlo.
ALTER TABLE invoice_inbox ADD COLUMN signature_verified boolean NOT NULL DEFAULT false;

DROP FUNCTION invoice_inbox_add(uuid, text, text, date, text, text, text, numeric);
CREATE FUNCTION invoice_inbox_add(p_user uuid, p_clave text, p_kind text, p_issued_on date, p_merchant text,
                                  p_emisor_id text, p_currency text, p_total numeric, p_verified boolean)
    RETURNS boolean
    LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = casaayg, pg_temp AS $$
BEGIN
    INSERT INTO invoice_inbox (user_id, clave, kind, issued_on, merchant, emisor_id, currency, total, signature_verified)
    VALUES (p_user, p_clave, p_kind, p_issued_on, p_merchant, p_emisor_id, p_currency, p_total, p_verified)
    ON CONFLICT (clave) DO NOTHING;
    RETURN FOUND;
END $$;
REVOKE ALL ON FUNCTION invoice_inbox_add(uuid, text, text, date, text, text, text, numeric, boolean) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION invoice_inbox_add(uuid, text, text, date, text, text, text, numeric, boolean) TO casaayg_app;
