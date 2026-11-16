-- V3: Row Level Security. Segunda barrera contra IDOR (threat model 5.1-E): aunque la app
-- olvide un "WHERE space_id = ?", Postgres solo devuelve filas de los espacios del usuario.
--
-- Contrato con la app: al inicio de CADA transacción,
--     SELECT set_config('app.current_user_id', '<uuid>', true);   -- true = SET LOCAL
-- Sin ese valor, todo falla CERRADO: 0 filas visibles y ninguna escritura permitida.
--
-- Límite conocido (riesgo aceptado, ver threat model): quien logre SQL injection puede
-- llamar a set_config. RLS protege de errores de lógica de la app, no reemplaza
-- las consultas parametrizadas.
--
-- El dueño (casaayg_owner, Flyway) no está sujeto a RLS: no se usa FORCE a propósito.

-- ---------------------------------------------------------------------------
-- Funciones auxiliares
-- ---------------------------------------------------------------------------

-- Usuario actual de la transacción, o NULL. Un valor que no sea UUID lanza error (22P02).
CREATE FUNCTION current_app_user() RETURNS uuid
    LANGUAGE sql STABLE SET search_path = casaayg, pg_temp AS $$
    SELECT NULLIF(current_setting('app.current_user_id', true), '')::uuid
$$;

-- Espacios donde el usuario actual es miembro (incluye archivados: se pueden leer).
-- SECURITY DEFINER: corre como el dueño para poder leer space_member sin caer en una
-- recursión de políticas. search_path fijo para que nadie la secuestre con objetos propios.
CREATE FUNCTION my_space_ids() RETURNS SETOF uuid
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = casaayg, pg_temp AS $$
    SELECT m.space_id FROM space_member m WHERE m.user_id = current_app_user()
$$;

-- Espacios donde además se puede ESCRIBIR: los no archivados.
CREATE FUNCTION my_writable_space_ids() RETURNS SETOF uuid
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = casaayg, pg_temp AS $$
    SELECT m.space_id
      FROM space_member m JOIN space s ON s.id = m.space_id
     WHERE m.user_id = current_app_user() AND s.archived_at IS NULL
$$;

REVOKE ALL ON FUNCTION current_app_user(), my_space_ids(), my_writable_space_ids() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION current_app_user(), my_space_ids(), my_writable_space_ids() TO casaayg_app;

-- ---------------------------------------------------------------------------
-- Usuarios y espacios (la app solo lee; ver V2)
-- ---------------------------------------------------------------------------

ALTER TABLE space ENABLE ROW LEVEL SECURITY;
CREATE POLICY space_read ON space FOR SELECT TO casaayg_app
    USING (id IN (SELECT my_space_ids()));

ALTER TABLE space_member ENABLE ROW LEVEL SECURITY;
CREATE POLICY space_member_read ON space_member FOR SELECT TO casaayg_app
    USING (space_id IN (SELECT my_space_ids()));

-- Cada quien se ve a sí mismo y a quienes comparten un espacio con él. Nadie más.
ALTER TABLE app_user ENABLE ROW LEVEL SECURITY;
CREATE POLICY app_user_read ON app_user FOR SELECT TO casaayg_app
    USING (id = current_app_user()
           OR id IN (SELECT user_id FROM space_member WHERE space_id IN (SELECT my_space_ids())));
CREATE POLICY app_user_update_self ON app_user FOR UPDATE TO casaayg_app
    USING (id = current_app_user())
    WITH CHECK (id = current_app_user());

-- ---------------------------------------------------------------------------
-- Datos de negocio: leer en mis espacios; escribir solo en mis espacios activos;
-- al crear, created_by tiene que ser el usuario actual (nadie registra "a nombre de" otro).
-- ---------------------------------------------------------------------------

ALTER TABLE category ENABLE ROW LEVEL SECURITY;
CREATE POLICY category_read ON category FOR SELECT TO casaayg_app
    USING (space_id IN (SELECT my_space_ids()));
CREATE POLICY category_insert ON category FOR INSERT TO casaayg_app
    WITH CHECK (space_id IN (SELECT my_writable_space_ids()) AND created_by = current_app_user());
CREATE POLICY category_update ON category FOR UPDATE TO casaayg_app
    USING (space_id IN (SELECT my_writable_space_ids()))
    WITH CHECK (space_id IN (SELECT my_writable_space_ids()));

ALTER TABLE budget ENABLE ROW LEVEL SECURITY;
CREATE POLICY budget_read ON budget FOR SELECT TO casaayg_app
    USING (space_id IN (SELECT my_space_ids()));
CREATE POLICY budget_insert ON budget FOR INSERT TO casaayg_app
    WITH CHECK (space_id IN (SELECT my_writable_space_ids()) AND created_by = current_app_user());

ALTER TABLE budget_line ENABLE ROW LEVEL SECURITY;
CREATE POLICY budget_line_read ON budget_line FOR SELECT TO casaayg_app
    USING (space_id IN (SELECT my_space_ids()));
CREATE POLICY budget_line_insert ON budget_line FOR INSERT TO casaayg_app
    WITH CHECK (space_id IN (SELECT my_writable_space_ids()));
CREATE POLICY budget_line_update ON budget_line FOR UPDATE TO casaayg_app
    USING (space_id IN (SELECT my_writable_space_ids()))
    WITH CHECK (space_id IN (SELECT my_writable_space_ids()));
CREATE POLICY budget_line_delete ON budget_line FOR DELETE TO casaayg_app
    USING (space_id IN (SELECT my_writable_space_ids()));

ALTER TABLE transaction ENABLE ROW LEVEL SECURITY;
CREATE POLICY transaction_read ON transaction FOR SELECT TO casaayg_app
    USING (space_id IN (SELECT my_space_ids()));
CREATE POLICY transaction_insert ON transaction FOR INSERT TO casaayg_app
    WITH CHECK (space_id IN (SELECT my_writable_space_ids()) AND created_by = current_app_user());
CREATE POLICY transaction_update ON transaction FOR UPDATE TO casaayg_app
    USING (space_id IN (SELECT my_writable_space_ids()))
    WITH CHECK (space_id IN (SELECT my_writable_space_ids()));
CREATE POLICY transaction_delete ON transaction FOR DELETE TO casaayg_app
    USING (space_id IN (SELECT my_writable_space_ids()));

-- ---------------------------------------------------------------------------
-- Auditoría: cada quien lee sus eventos; nadie escribe eventos a nombre de otro.
-- (user_id NULL se permite para eventos sin sesión, como un login fallido.)
-- ---------------------------------------------------------------------------

ALTER TABLE audit_log ENABLE ROW LEVEL SECURITY;
CREATE POLICY audit_log_read_own ON audit_log FOR SELECT TO casaayg_app
    USING (user_id = current_app_user());
CREATE POLICY audit_log_insert ON audit_log FOR INSERT TO casaayg_app
    WITH CHECK ((user_id IS NULL OR user_id = current_app_user())
                AND (space_id IS NULL OR space_id IN (SELECT my_space_ids())));
