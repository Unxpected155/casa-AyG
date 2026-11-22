-- V15: la cédula de cada persona, para reconocer las facturas electrónicas que son suyas
-- (ADR-006). Nunca se guarda en texto: solo un HMAC-SHA256 con una llave que vive en los
-- secretos del servidor (fuera de la BD y de los respaldos). Sin la llave, la huella no sirve
-- ni para probar cédulas una por una.
--
-- La app no tiene permisos sobre la tabla: solo usa estas funciones. Cada persona pone,
-- consulta o quita SOLO la suya; tax_id_owner() la usa la lectura programada del buzón.

CREATE TABLE tax_id (
    user_id uuid        PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    id_hmac bytea       NOT NULL UNIQUE CHECK (length(id_hmac) = 32),
    set_at  timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE tax_id ENABLE ROW LEVEL SECURITY;

-- false si esa cédula ya la tiene otra persona (no dice quién).
CREATE FUNCTION tax_id_set(p_hmac bytea) RETURNS boolean
    LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = casaayg, pg_temp AS $$
DECLARE
    me uuid := current_app_user();
BEGIN
    IF me IS NULL THEN
        RAISE EXCEPTION 'sin sesión' USING ERRCODE = '42501';
    END IF;
    IF EXISTS (SELECT 1 FROM tax_id WHERE id_hmac = p_hmac AND user_id <> me) THEN
        RETURN false;
    END IF;
    INSERT INTO tax_id (user_id, id_hmac) VALUES (me, p_hmac)
    ON CONFLICT (user_id) DO UPDATE SET id_hmac = EXCLUDED.id_hmac, set_at = now();
    RETURN true;
END $$;

CREATE FUNCTION tax_id_clear() RETURNS void
    LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = casaayg, pg_temp AS $$
    DELETE FROM tax_id WHERE user_id = current_app_user()
$$;

CREATE FUNCTION tax_id_is_set() RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = casaayg, pg_temp AS $$
    SELECT EXISTS (SELECT 1 FROM tax_id WHERE user_id = current_app_user())
$$;

-- De quién es una factura: el receptor del XML, convertido a huella por la app.
CREATE FUNCTION tax_id_owner(p_hmac bytea) RETURNS uuid
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = casaayg, pg_temp AS $$
    SELECT user_id FROM tax_id WHERE id_hmac = p_hmac
$$;

REVOKE ALL ON FUNCTION tax_id_set(bytea), tax_id_clear(), tax_id_is_set(), tax_id_owner(bytea) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION tax_id_set(bytea), tax_id_clear(), tax_id_is_set(), tax_id_owner(bytea) TO casaayg_app;
