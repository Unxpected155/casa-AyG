-- Notificaciones por tipo (decisiones del 2026-11-27, ver PRODUCT.md):
-- - cada tipo se enciende y apaga por persona (menos SEGURIDAD, siempre encendido);
-- - de 9 p. m. a 7 a. m. no suena nada (menos SEGURIDAD): lo de ese rato se guarda y sale a las 7;
-- - como máximo un aviso de cada tipo al día.

-- Lo que la persona apagó (o volvió a encender). Sin fila: encendido.
CREATE TABLE push_preference (
    user_id uuid    NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    kind    text    NOT NULL CHECK (kind IN ('POR_PAGAR', 'CDP', 'PRESUPUESTO', 'CASA', 'FACTURAS', 'DIEZMO', 'METAS')),
    enabled boolean NOT NULL,
    PRIMARY KEY (user_id, kind)
);
GRANT SELECT, INSERT, UPDATE (enabled) ON push_preference TO casaayg_app;
ALTER TABLE push_preference ENABLE ROW LEVEL SECURITY;
CREATE POLICY push_preference_own ON push_preference FOR ALL TO casaayg_app
    USING (user_id = current_app_user()) WITH CHECK (user_id = current_app_user());

-- El día (hora de Costa Rica) en que ya salió un aviso de cada tipo: el segundo no sale.
CREATE TABLE push_sent (
    user_id uuid NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    kind    text NOT NULL,
    day     date NOT NULL,
    PRIMARY KEY (user_id, kind, day)
);
GRANT SELECT, INSERT, DELETE ON push_sent TO casaayg_app;
ALTER TABLE push_sent ENABLE ROW LEVEL SECURITY;
CREATE POLICY push_sent_own ON push_sent FOR ALL TO casaayg_app
    USING (user_id = current_app_user()) WITH CHECK (user_id = current_app_user());

-- Avisos que nacieron en horas de silencio: salen a las 7 a. m. El texto nunca lleva montos.
CREATE TABLE push_pending (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    kind       text        NOT NULL,
    title      text        NOT NULL CHECK (length(title) BETWEEN 1 AND 60),
    body       text        NOT NULL CHECK (length(body) BETWEEN 1 AND 300),
    url        text        NOT NULL CHECK (url ~ '^/[a-z/]*$'),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX push_pending_user_idx ON push_pending (user_id, created_at);
GRANT SELECT, INSERT, DELETE ON push_pending TO casaayg_app;
ALTER TABLE push_pending ENABLE ROW LEVEL SECURITY;
CREATE POLICY push_pending_own ON push_pending FOR ALL TO casaayg_app
    USING (user_id = current_app_user()) WITH CHECK (user_id = current_app_user());

-- A las 7 a. m., sin sesión: a quién le quedaron avisos guardados (solo los ids).
CREATE FUNCTION push_pending_users() RETURNS SETOF uuid
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = casaayg, pg_temp AS $$
    SELECT DISTINCT user_id FROM push_pending
$$;
REVOKE ALL ON FUNCTION push_pending_users() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION push_pending_users() TO casaayg_app;

-- CDP: el día antes y el día que vence (no insiste después). Sin montos.
CREATE FUNCTION cdp_reminders(p_day date) RETURNS TABLE (user_id uuid, today bigint, tomorrow bigint)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = casaayg, pg_temp AS $$
    SELECT m.user_id,
           count(DISTINCT p.id) FILTER (WHERE p.cdp_matures_on = p_day),
           count(DISTINCT p.id) FILTER (WHERE p.cdp_matures_on = p_day + 1)
      FROM place p
      JOIN space s ON s.id = p.space_id AND s.archived_at IS NULL
      JOIN space_member m ON m.space_id = p.space_id
     WHERE p.kind = 'CDP' AND NOT p.archived AND p.cdp_matures_on IN (p_day, p_day + 1)
     GROUP BY m.user_id
$$;
REVOKE ALL ON FUNCTION cdp_reminders(date) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cdp_reminders(date) TO casaayg_app;

-- Diezmo: quién tiene diezmo por dar en algún espacio activo (sin montos).
CREATE FUNCTION tithe_reminders() RETURNS TABLE (user_id uuid, family boolean)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = casaayg, pg_temp AS $$
    SELECT DISTINCT m.user_id, s.kind = 'FAMILIA'
      FROM space s
      JOIN space_member m ON m.space_id = s.id
     WHERE s.archived_at IS NULL
       AND EXISTS (SELECT 1 FROM tithe_pending(s.id) t WHERE t.pending > 0)
$$;
REVOKE ALL ON FUNCTION tithe_reminders() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION tithe_reminders() TO casaayg_app;

-- Facturas: cuántas llegaron por revisar desde la hora dada, por persona.
CREATE FUNCTION invoice_digest(p_since timestamptz) RETURNS TABLE (user_id uuid, invoices bigint)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = casaayg, pg_temp AS $$
    SELECT i.user_id, count(*)
      FROM invoice_inbox i
     WHERE i.status = 'PENDIENTE' AND i.received_at >= p_since
     GROUP BY i.user_id
$$;
REVOKE ALL ON FUNCTION invoice_digest(timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION invoice_digest(timestamptz) TO casaayg_app;
