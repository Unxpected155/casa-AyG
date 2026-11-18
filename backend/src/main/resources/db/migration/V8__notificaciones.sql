-- V8: notificaciones push (Web Push). Cada dispositivo que las activa deja una suscripción:
-- la URL del servicio de push de su sistema (Apple, Google, Mozilla, Microsoft) y las llaves
-- con las que el servidor cifra cada mensaje para ESE dispositivo (RFC 8291).
-- Una suscripción es de una persona: solo ella la ve, la crea o la borra.

CREATE TABLE push_subscription (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    endpoint    text        NOT NULL UNIQUE CHECK (length(endpoint) BETWEEN 10 AND 1000 AND endpoint LIKE 'https://%'),
    p256dh      text        NOT NULL CHECK (length(p256dh) BETWEEN 80 AND 100),
    auth        text        NOT NULL CHECK (length(auth) BETWEEN 16 AND 30),
    created_at  timestamptz NOT NULL DEFAULT now(),
    last_ok_at  timestamptz
);
CREATE INDEX push_subscription_user_idx ON push_subscription (user_id);

GRANT SELECT, INSERT, DELETE ON push_subscription TO casaayg_app;
GRANT UPDATE (last_ok_at) ON push_subscription TO casaayg_app;

ALTER TABLE push_subscription ENABLE ROW LEVEL SECURITY;
CREATE POLICY push_own ON push_subscription FOR ALL TO casaayg_app
    USING (user_id = current_app_user())
    WITH CHECK (user_id = current_app_user());

-- El aviso diario corre sin sesión: necesita saber A QUIÉN avisar, nada más. Devuelve solo
-- usuarios con un CDP que vence (o venció sin renovar) en la fecha dada; sin montos.
-- Después, el envío corre a nombre de cada usuario (RLS) para leer sus suscripciones.
CREATE FUNCTION cdp_due_users(p_day date) RETURNS TABLE (user_id uuid, cdp_count bigint)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = casaayg, pg_temp AS $$
    SELECT m.user_id, count(DISTINCT p.id)
      FROM place p
      JOIN space s ON s.id = p.space_id AND s.archived_at IS NULL
      JOIN space_member m ON m.space_id = p.space_id
     WHERE p.kind = 'CDP' AND NOT p.archived AND p.cdp_matures_on IS NOT NULL AND p.cdp_matures_on <= p_day
     GROUP BY m.user_id
$$;
REVOKE ALL ON FUNCTION cdp_due_users(date) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cdp_due_users(date) TO casaayg_app;
