-- V29: planeador de metas (reemplaza al simulador; decisiones del 2026-11-27, ver PRODUCT.md).
-- Un plan es OPCIONAL: se guarda solo si la pareja quiere comparar cada mes lo planeado con lo
-- que pasó. Nada se aparta solo: el plan es una foto de «si apartamos tanto al mes».
-- Un plan por espacio; guardar uno nuevo reemplaza al anterior.

CREATE TABLE goal_plan (
    id          uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    space_id    uuid          NOT NULL UNIQUE REFERENCES space (id),
    created_by  uuid          NOT NULL REFERENCES app_user (id),
    created_at  timestamptz   NOT NULL DEFAULT now(),
    start_month date          NOT NULL CHECK (extract(day FROM start_month) = 1),
    monthly_crc numeric(14,2) NOT NULL CHECK (monthly_crc >= 0),
    UNIQUE (id, space_id)
);

-- Lo que el plan le da a cada meta el primer mes (en colones) y cuándo llegaría.
CREATE TABLE goal_plan_item (
    plan_id     uuid          NOT NULL,
    goal_id     uuid          NOT NULL,
    space_id    uuid          NOT NULL,
    monthly_crc numeric(14,2) NOT NULL CHECK (monthly_crc >= 0),
    eta         date,
    PRIMARY KEY (plan_id, goal_id),
    FOREIGN KEY (plan_id, space_id) REFERENCES goal_plan (id, space_id) ON DELETE CASCADE,
    FOREIGN KEY (goal_id, space_id) REFERENCES goal (id, space_id) ON DELETE CASCADE
);

GRANT SELECT, INSERT, DELETE ON goal_plan, goal_plan_item TO casaayg_app;
ALTER TABLE goal_plan ENABLE ROW LEVEL SECURITY;
ALTER TABLE goal_plan_item ENABLE ROW LEVEL SECURITY;
CREATE POLICY goal_plan_read ON goal_plan FOR SELECT TO casaayg_app USING (space_id IN (SELECT my_space_ids()));
CREATE POLICY goal_plan_insert ON goal_plan FOR INSERT TO casaayg_app
    WITH CHECK (space_id IN (SELECT my_writable_space_ids()) AND created_by = current_app_user());
CREATE POLICY goal_plan_delete ON goal_plan FOR DELETE TO casaayg_app
    USING (space_id IN (SELECT my_writable_space_ids()));
CREATE POLICY goal_plan_item_read ON goal_plan_item FOR SELECT TO casaayg_app
    USING (space_id IN (SELECT my_space_ids()));
CREATE POLICY goal_plan_item_insert ON goal_plan_item FOR INSERT TO casaayg_app
    WITH CHECK (space_id IN (SELECT my_writable_space_ids()));
CREATE POLICY goal_plan_item_delete ON goal_plan_item FOR DELETE TO casaayg_app
    USING (space_id IN (SELECT my_writable_space_ids()));

-- Saldos en tiempo real (V26): guardar o borrar el plan avisa a la otra pantalla.
CREATE TRIGGER goal_plan_notify AFTER INSERT OR UPDATE OR DELETE ON goal_plan
    FOR EACH ROW EXECUTE FUNCTION notify_space_change();
CREATE TRIGGER goal_plan_item_notify AFTER INSERT OR UPDATE OR DELETE ON goal_plan_item
    FOR EACH ROW EXECUTE FUNCTION notify_space_change();
