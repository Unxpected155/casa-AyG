-- V16: facturas electrónicas por revisar (ADR-006). Una factura verificada NO es un movimiento:
-- espera aquí hasta que su dueño (el receptor) la confirme en un espacio, con categoría y
-- cuenta, o la descarte. Así nada entra solo y un error del lector no toca los saldos.
--
-- Solo se guarda lo que se muestra: comercio, fecha, monto, moneda, la clave (para no repetirla)
-- y la cédula del EMISOR (un comercio, para sugerir la categoría la próxima vez). La cédula del
-- receptor no se guarda: el dueño ya está en user_id.

CREATE TABLE invoice_inbox (
    id             uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        uuid          NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    -- Única para siempre: una factura descartada tampoco vuelve a entrar.
    clave          text          NOT NULL UNIQUE CHECK (clave ~ '^506[0-9]{47}$'),
    kind           text          NOT NULL CHECK (kind IN ('FACTURA', 'NOTA_CREDITO')),
    issued_on      date          NOT NULL CHECK (issued_on >= DATE '2000-01-01'),
    merchant       text          NOT NULL CHECK (length(merchant) BETWEEN 1 AND 100),
    emisor_id      text          NOT NULL CHECK (emisor_id ~ '^[0-9]{1,12}$'),
    currency       char(3)       NOT NULL CHECK (currency IN ('CRC', 'USD')),
    total          numeric(14,2) NOT NULL CHECK (total > 0),
    status         text          NOT NULL DEFAULT 'PENDIENTE'
                                 CHECK (status IN ('PENDIENTE', 'CONFIRMADA', 'DESCARTADA')),
    transaction_id uuid          REFERENCES transaction (id) ON DELETE SET NULL,
    received_at    timestamptz   NOT NULL DEFAULT now(),
    decided_at     timestamptz,
    CHECK ((status = 'PENDIENTE') = (decided_at IS NULL))
);
CREATE INDEX invoice_inbox_user_status_idx ON invoice_inbox (user_id, status);

ALTER TABLE invoice_inbox ENABLE ROW LEVEL SECURITY;

-- Cada quien ve y decide SOLO sus facturas. La app no inserta ni borra: solo cambia el estado.
GRANT SELECT ON invoice_inbox TO casaayg_app;
GRANT UPDATE (status, transaction_id, decided_at) ON invoice_inbox TO casaayg_app;
CREATE POLICY invoice_inbox_read ON invoice_inbox FOR SELECT TO casaayg_app
    USING (user_id = current_app_user());
CREATE POLICY invoice_inbox_decide ON invoice_inbox FOR UPDATE TO casaayg_app
    USING (user_id = current_app_user())
    WITH CHECK (user_id = current_app_user());

-- La lectura del buzón (sin sesión) deja una factura YA VERIFICADA para su dueño.
-- false = esa clave ya había entrado (no se repite).
CREATE FUNCTION invoice_inbox_add(p_user uuid, p_clave text, p_kind text, p_issued_on date, p_merchant text,
                                  p_emisor_id text, p_currency text, p_total numeric) RETURNS boolean
    LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = casaayg, pg_temp AS $$
BEGIN
    INSERT INTO invoice_inbox (user_id, clave, kind, issued_on, merchant, emisor_id, currency, total)
    VALUES (p_user, p_clave, p_kind, p_issued_on, p_merchant, p_emisor_id, p_currency, p_total)
    ON CONFLICT (clave) DO NOTHING;
    RETURN FOUND;
END $$;
REVOKE ALL ON FUNCTION invoice_inbox_add(uuid, text, text, date, text, text, text, numeric) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION invoice_inbox_add(uuid, text, text, date, text, text, text, numeric) TO casaayg_app;
