-- V2: permisos del rol de la aplicación (casaayg_app). Privilegio mínimo, tabla por tabla
-- y, donde importa, COLUMNA por columna.
-- No se usan ALTER DEFAULT PRIVILEGES para dar permisos: cada tabla nueva debe recibirlos
-- de forma explícita y revisada en su propia migración.

-- Usuarios y espacios: la app solo los LEE. Crearlos, unir miembros y fusionar espacios
-- se hará con funciones dedicadas (la fusión exige el consentimiento de ambos, ADR-005),
-- nunca con INSERT/UPDATE directos desde la app.
GRANT SELECT ON app_user, space, space_member TO casaayg_app;
GRANT UPDATE (display_name, last_login_at) ON app_user TO casaayg_app;

-- Datos de negocio. La app NO puede cambiar space_id ni created_by de una fila existente:
-- mover datos entre espacios o falsificar quién registró algo no es un UPDATE posible.
GRANT SELECT, INSERT ON category, budget, budget_line, transaction TO casaayg_app;
GRANT UPDATE (name, category_group, active) ON category TO casaayg_app;
GRANT UPDATE (planned_amount) ON budget_line TO casaayg_app;
GRANT UPDATE (category_id, tx_date, description, amount, currency, payment_method, updated_at)
    ON transaction TO casaayg_app;

-- Borrables por el usuario. Categorías y presupuestos no se borran (se desactivan o se editan).
GRANT DELETE ON budget_line, transaction TO casaayg_app;

-- Auditoría append-only: la app escribe y lee, pero NO puede modificar ni borrar.
-- Así, quien comprometa la app no puede borrar su rastro.
GRANT SELECT, INSERT ON audit_log TO casaayg_app;
GRANT USAGE ON SEQUENCE audit_log_id_seq TO casaayg_app;
