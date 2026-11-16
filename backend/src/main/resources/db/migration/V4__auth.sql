-- V4: autenticación con passkeys (Fase 3, ADR-007).
--
-- Flujo:
--   1. El administrador crea el usuario desde la consola (admin_create_user) y recibe un
--      CÓDIGO DE INSCRIPCIÓN de un solo uso (24 h). La app no puede crear usuarios.
--   2. Con ese código se abre una sesión limitada que SOLO puede registrar passkeys.
--   3. Desde ahí, el login es con passkey. Los CÓDIGOS DE RECUPERACIÓN (10, de un solo uso)
--      abren la misma sesión limitada si se pierden los dispositivos.
-- Los códigos se guardan como SHA-256: tienen 96 bits aleatorios, así que no hace falta un
-- hash lento; quien lea la tabla no puede usarlos.

ALTER TABLE app_user ADD COLUMN username text;
-- Filas de desarrollo previas (no hay datos reales todavía).
UPDATE app_user SET username = 'u' || substr(replace(id::text, '-', ''), 1, 12) WHERE username IS NULL;
ALTER TABLE app_user
    ALTER COLUMN username SET NOT NULL,
    ADD CONSTRAINT app_user_username_format CHECK (username ~ '^[a-z][a-z0-9_]{2,29}$'),
    ADD CONSTRAINT app_user_username_key UNIQUE (username);

-- "User handle" de WebAuthn: los 16 bytes del UUID del usuario en base64url, que es como
-- Spring lo guarda. No contiene datos personales (la especificación lo pide así).
CREATE FUNCTION user_handle(p_id uuid) RETURNS text
    LANGUAGE sql IMMUTABLE SET search_path = casaayg, pg_temp AS $$
    SELECT rtrim(translate(encode(uuid_send(p_id), 'base64'), '+/', '-_'), '=')
$$;

-- Tablas que usan los repositorios JDBC de Spring Security (nombres y columnas fijos).
CREATE TABLE user_entities (
    id           text PRIMARY KEY CHECK (length(id) <= 64),
    name         text NOT NULL UNIQUE REFERENCES app_user (username),
    display_name text CHECK (length(display_name) <= 100)
);

CREATE TABLE user_credentials (
    credential_id                text PRIMARY KEY CHECK (length(credential_id) <= 1024),
    user_entity_user_id          text NOT NULL REFERENCES user_entities (id),
    public_key                   bytea NOT NULL CHECK (length(public_key) <= 4096),
    signature_count              bigint,
    uv_initialized               boolean,
    backup_eligible              boolean NOT NULL,
    authenticator_transports     text CHECK (length(authenticator_transports) <= 200),
    public_key_credential_type   text CHECK (length(public_key_credential_type) <= 50),
    backup_state                 boolean NOT NULL,
    attestation_object           bytea CHECK (length(attestation_object) <= 16384),
    attestation_client_data_json bytea CHECK (length(attestation_client_data_json) <= 4096),
    created                      timestamptz,
    last_used                    timestamptz,
    label                        text NOT NULL CHECK (length(label) BETWEEN 1 AND 100)
);

CREATE INDEX user_credentials_user_idx ON user_credentials (user_entity_user_id);

-- Una passkey no cambia de dueño ni de llave, y hay un máximo de 10 por usuario.
CREATE FUNCTION user_credentials_guard() RETURNS trigger
    LANGUAGE plpgsql SET search_path = casaayg, pg_temp AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND (NEW.credential_id IS DISTINCT FROM OLD.credential_id
                             OR NEW.user_entity_user_id IS DISTINCT FROM OLD.user_entity_user_id
                             OR NEW.public_key IS DISTINCT FROM OLD.public_key) THEN
        RAISE EXCEPTION 'la identidad de una passkey no se puede cambiar' USING ERRCODE = '42501';
    END IF;
    IF TG_OP = 'INSERT' AND (SELECT count(*) FROM user_credentials
                             WHERE user_entity_user_id = NEW.user_entity_user_id) >= 10 THEN
        RAISE EXCEPTION 'máximo 10 passkeys por usuario' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER user_credentials_guard
    BEFORE INSERT OR UPDATE ON user_credentials
    FOR EACH ROW EXECUTE FUNCTION user_credentials_guard();

CREATE TABLE auth_code (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    uuid        NOT NULL REFERENCES app_user (id),
    purpose    text        NOT NULL CHECK (purpose IN ('INSCRIPCION', 'RECUPERACION')),
    code_hash  bytea       NOT NULL UNIQUE CHECK (length(code_hash) = 32),
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz,
    used_at    timestamptz,
    -- Los de inscripción siempre vencen.
    CHECK (purpose = 'RECUPERACION' OR expires_at IS NOT NULL)
);

CREATE INDEX auth_code_user_idx ON auth_code (user_id);

-- ---------------------------------------------------------------------------
-- Funciones para ANTES del login. No hay usuario en la sesión, así que RLS no las dejaría
-- leer nada: son SECURITY DEFINER, devuelven lo mínimo y no aceptan SQL libre.
-- ---------------------------------------------------------------------------

CREATE FUNCTION auth_find_user(p_username text)
    RETURNS TABLE (id uuid, username text, display_name text)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = casaayg, pg_temp AS $$
    SELECT u.id, u.username, u.display_name FROM app_user u WHERE u.username = lower(p_username)
$$;

-- Canjea un código: lo marca como usado en la MISMA sentencia (no se puede usar dos veces,
-- ni con dos peticiones simultáneas).
CREATE FUNCTION auth_redeem_code(p_username text, p_code_hash bytea)
    RETURNS TABLE (user_id uuid, purpose text)
    LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = casaayg, pg_temp AS $$
    UPDATE auth_code c SET used_at = now()
      FROM app_user u
     WHERE u.username = lower(p_username) AND c.user_id = u.id AND c.code_hash = p_code_hash
       AND c.used_at IS NULL AND (c.expires_at IS NULL OR c.expires_at > now())
    RETURNING c.user_id, c.purpose
$$;

-- Reemplaza los códigos de recuperación del usuario ACTUAL (nunca de otro).
CREATE FUNCTION auth_replace_recovery_codes(p_hashes bytea[]) RETURNS void
    LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = casaayg, pg_temp AS $$
DECLARE
    me uuid := current_app_user();
BEGIN
    IF me IS NULL THEN
        RAISE EXCEPTION 'sin usuario' USING ERRCODE = '42501';
    END IF;
    IF cardinality(p_hashes) IS DISTINCT FROM 10 THEN
        RAISE EXCEPTION 'se esperan 10 códigos' USING ERRCODE = '22023';
    END IF;
    DELETE FROM auth_code WHERE user_id = me AND purpose = 'RECUPERACION' AND used_at IS NULL;
    INSERT INTO auth_code (user_id, purpose, code_hash)
        SELECT me, 'RECUPERACION', h FROM unnest(p_hashes) AS h;
END $$;

CREATE FUNCTION auth_recovery_codes_left() RETURNS integer
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = casaayg, pg_temp AS $$
    SELECT count(*)::int FROM auth_code
     WHERE user_id = current_app_user() AND purpose = 'RECUPERACION' AND used_at IS NULL
$$;

REVOKE ALL ON FUNCTION user_handle(uuid), auth_find_user(text), auth_redeem_code(text, bytea),
    auth_replace_recovery_codes(bytea[]), auth_recovery_codes_left() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION user_handle(uuid), auth_find_user(text), auth_redeem_code(text, bytea),
    auth_replace_recovery_codes(bytea[]), auth_recovery_codes_left() TO casaayg_app;

-- ---------------------------------------------------------------------------
-- Permisos y RLS de las tablas de passkeys.
-- Spring las lee ANTES del login (buscar una passkey por su id), así que SELECT no puede
-- depender del usuario. Lo que sí: solo el dueño puede BORRAR una passkey.
-- user_entities la crea solo el administrador; la app únicamente la lee.
-- ---------------------------------------------------------------------------

GRANT SELECT ON user_entities TO casaayg_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON user_credentials TO casaayg_app;

ALTER TABLE user_credentials ENABLE ROW LEVEL SECURITY;
CREATE POLICY user_credentials_read ON user_credentials FOR SELECT TO casaayg_app USING (true);
CREATE POLICY user_credentials_insert ON user_credentials FOR INSERT TO casaayg_app WITH CHECK (true);
CREATE POLICY user_credentials_update ON user_credentials FOR UPDATE TO casaayg_app
    USING (true) WITH CHECK (true);
CREATE POLICY user_credentials_delete_own ON user_credentials FOR DELETE TO casaayg_app
    USING (user_entity_user_id = user_handle(current_app_user()));

-- ---------------------------------------------------------------------------
-- Administración (SOLO casaayg_owner, desde la consola del servidor; la app no puede).
-- Uso:  docker compose exec db psql -U casaayg_owner -d casaayg \
--         -c "SELECT admin_create_user('ana', 'Ana')"
-- ---------------------------------------------------------------------------

-- Código de 96 bits: los 12 primeros hex de dos UUID v4 (esos 48 bits son aleatorios;
-- el nibble de versión está en la posición 13). gen_random_uuid usa el RNG seguro de Postgres.
CREATE FUNCTION new_auth_code() RETURNS text
    LANGUAGE sql VOLATILE SET search_path = casaayg, pg_temp AS $$
    SELECT upper(substr(replace(gen_random_uuid()::text, '-', ''), 1, 12)
              || substr(replace(gen_random_uuid()::text, '-', ''), 1, 12))
$$;

CREATE FUNCTION format_auth_code(p_code text) RETURNS text
    LANGUAGE sql IMMUTABLE SET search_path = casaayg, pg_temp AS $$
    SELECT regexp_replace(p_code, '(.{4})(?!$)', '\1-', 'g')
$$;

-- Categorías iniciales de un espacio (se pueden editar, agregar y borrar).
CREATE FUNCTION create_default_categories(p_space uuid, p_by uuid) RETURNS void
    LANGUAGE sql SET search_path = casaayg, pg_temp AS $$
    INSERT INTO category (space_id, created_by, name, category_group)
    SELECT p_space, p_by, v.name, v.grp FROM (VALUES
        ('Salario', 'INGRESO'), ('Otros ingresos', 'INGRESO'),
        ('Supermercado', 'NECESIDAD'), ('Vivienda', 'NECESIDAD'),
        ('Servicios (luz, agua, internet)', 'NECESIDAD'), ('Transporte', 'NECESIDAD'),
        ('Salud', 'NECESIDAD'), ('Seguros', 'NECESIDAD'), ('Educación', 'NECESIDAD'),
        ('Restaurantes y salidas', 'DESEO'), ('Entretenimiento', 'DESEO'), ('Ropa', 'DESEO'),
        ('Viajes', 'DESEO'), ('Regalos', 'DESEO'),
        ('Ahorro', 'AHORRO'), ('Inversiones', 'AHORRO'), ('Pago de deudas', 'AHORRO')
    ) AS v (name, grp)
$$;

CREATE FUNCTION admin_create_user(p_username text, p_display_name text) RETURNS text
    LANGUAGE plpgsql VOLATILE SET search_path = casaayg, pg_temp AS $$
DECLARE
    v_id    uuid;
    v_space uuid;
    v_code  text := new_auth_code();
BEGIN
    INSERT INTO app_user (username, display_name)
        VALUES (lower(p_username), p_display_name) RETURNING id INTO v_id;
    INSERT INTO user_entities (id, name, display_name)
        VALUES (user_handle(v_id), lower(p_username), p_display_name);
    INSERT INTO space (kind, name) VALUES ('PERSONAL', left(p_display_name, 60)) RETURNING id INTO v_space;
    INSERT INTO space_member (space_id, user_id) VALUES (v_space, v_id);
    PERFORM create_default_categories(v_space, v_id);
    INSERT INTO auth_code (user_id, purpose, code_hash, expires_at)
        VALUES (v_id, 'INSCRIPCION', sha256(convert_to(v_code, 'UTF8')), now() + interval '24 hours');
    INSERT INTO audit_log (user_id, event_type) VALUES (v_id, 'USUARIO_CREADO');
    RETURN format_auth_code(v_code);
END $$;

-- Si alguien pierde TODOS sus dispositivos y sus códigos: nuevo código de inscripción.
CREATE FUNCTION admin_new_enrollment_code(p_username text) RETURNS text
    LANGUAGE plpgsql VOLATILE SET search_path = casaayg, pg_temp AS $$
DECLARE
    v_id   uuid;
    v_code text := new_auth_code();
BEGIN
    SELECT id INTO STRICT v_id FROM app_user WHERE username = lower(p_username);
    UPDATE auth_code SET used_at = now()
     WHERE user_id = v_id AND purpose = 'INSCRIPCION' AND used_at IS NULL;
    INSERT INTO auth_code (user_id, purpose, code_hash, expires_at)
        VALUES (v_id, 'INSCRIPCION', sha256(convert_to(v_code, 'UTF8')), now() + interval '24 hours');
    INSERT INTO audit_log (user_id, event_type) VALUES (v_id, 'CODIGO_INSCRIPCION_NUEVO');
    RETURN format_auth_code(v_code);
END $$;
