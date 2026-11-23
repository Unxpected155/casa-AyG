-- V19: el buzón de facturas (ADR-006). Una sola conexión para la casa: la cuenta de Gmail
-- dedicada, con permiso de SOLO LECTURA. El refresh token se guarda cifrado (AES-GCM) con la
-- llave gmail_token_key, que vive en los secretos del servidor (fuera de la BD y de los
-- respaldos): con la BD sola no se puede leer el buzón.

CREATE TABLE mailbox (
    id                smallint    PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    email             text        NOT NULL CHECK (length(email) BETWEEN 3 AND 254),
    refresh_token_enc bytea       NOT NULL CHECK (length(refresh_token_enc) BETWEEN 29 AND 4096),
    connected_by      uuid        NOT NULL REFERENCES app_user (id),
    connected_at      timestamptz NOT NULL DEFAULT now(),
    last_check_at     timestamptz,
    last_error        text        CHECK (length(last_error) <= 200)
);

-- Correos ya leídos (por su id de Gmail): no se procesan dos veces. La clave sirve para juntar
-- la factura y la respuesta de Hacienda si llegan en correos distintos. No se guarda el correo.
CREATE TABLE mailbox_message (
    message_id   text        PRIMARY KEY CHECK (message_id ~ '^[A-Za-z0-9_-]{1,64}$'),
    clave        text        CHECK (clave ~ '^506[0-9]{47}$'),
    has_invoice  boolean     NOT NULL DEFAULT false,
    has_response boolean     NOT NULL DEFAULT false,
    result       text        NOT NULL CHECK (result IN ('NUEVA', 'REPETIDA', 'AJENA', 'INCOMPLETA', 'RECHAZADA', 'SIN_XML')),
    detail       text        CHECK (length(detail) <= 300),
    processed_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX mailbox_message_clave_idx ON mailbox_message (clave) WHERE clave IS NOT NULL;

-- Sin datos de ninguna persona (el correo y el token son de la casa): la app los maneja sin RLS,
-- igual que el tipo de cambio. Solo la app autenticada llega a estos endpoints.
GRANT SELECT, INSERT, UPDATE, DELETE ON mailbox TO casaayg_app;
GRANT SELECT, INSERT, UPDATE ON mailbox_message TO casaayg_app;
