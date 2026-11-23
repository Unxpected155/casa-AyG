-- V17: el signo de un movimiento sale del grupo de su categoría (ingreso suma, lo demás resta).
-- Cambiar una categoría con movimientos entre ingreso y gasto reescribiría los saldos pasados:
-- la app ya lo impide, y esta regla lo impide también en la BD (defensa en profundidad).
CREATE FUNCTION category_group_guard() RETURNS trigger
    LANGUAGE plpgsql SET search_path = casaayg, pg_temp AS $$
BEGIN
    IF (OLD.category_group = 'INGRESO') <> (NEW.category_group = 'INGRESO')
       AND EXISTS (SELECT 1 FROM transaction t WHERE t.category_id = OLD.id AND t.space_id = OLD.space_id) THEN
        RAISE EXCEPTION 'la categoría tiene movimientos: no puede pasar entre ingreso y gasto'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER category_group_guard BEFORE UPDATE OF category_group ON category
    FOR EACH ROW EXECUTE FUNCTION category_group_guard();
