import { useEffect, useState } from "react";
import { api, messageOf } from "../api";

interface PushStatus {
  enabled: boolean;
  publicKey?: string;
  devices?: number;
}

type State = "loading" | "unsupported" | "ios-install" | "denied" | "off" | "on" | "server-off";

function keyBytes(base64url: string): Uint8Array<ArrayBuffer> {
  const base64 = base64url.replace(/-/g, "+").replace(/_/g, "/").padEnd(Math.ceil(base64url.length / 4) * 4, "=");
  return Uint8Array.from(atob(base64), (c) => c.charCodeAt(0));
}

const supported = () => "serviceWorker" in navigator && "PushManager" in window && "Notification" in window;
const isIos = () => /iPhone|iPad|iPod/.test(navigator.userAgent);
const standalone = () => window.matchMedia("(display-mode: standalone)").matches;

async function currentSubscription(): Promise<PushSubscription | null> {
  const registration = await navigator.serviceWorker.getRegistration("/");
  return registration ? registration.pushManager.getSubscription() : null;
}

/**
 * Notificaciones de ESTE dispositivo. El service worker solo se registra cuando la persona
 * las activa, y no guarda nada en caché (ver public/sw.js).
 */
export function Notifications() {
  const [state, setState] = useState<State>("loading");
  const [status, setStatus] = useState<PushStatus | null>(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ tone: "ok" | "error"; text: string } | null>(null);

  const refresh = async () => {
    const s = await api.get<PushStatus>("/api/push");
    setStatus(s);
    if (!s.enabled) return setState("server-off");
    if (!supported()) return setState(isIos() && !standalone() ? "ios-install" : "unsupported");
    if (Notification.permission === "denied") return setState("denied");
    setState((await currentSubscription()) ? "on" : "off");
  };

  useEffect(() => {
    refresh().catch(() => setState("unsupported"));
  }, []);

  const run = async (action: () => Promise<void>, fallback: string) => {
    setBusy(true);
    setMessage(null);
    try {
      await action();
    } catch (e) {
      setMessage({ tone: "error", text: messageOf(e, fallback) });
    } finally {
      setBusy(false);
    }
  };

  const enable = () => run(async () => {
    const permission = await Notification.requestPermission();
    if (permission !== "granted") {
      setState(permission === "denied" ? "denied" : "off");
      return;
    }
    const registration = await navigator.serviceWorker.register("/sw.js", { scope: "/" });
    await navigator.serviceWorker.ready;
    const subscription = await registration.pushManager.subscribe({
      userVisibleOnly: true,
      applicationServerKey: keyBytes(status!.publicKey!),
    });
    await api.post("/api/push/subscriptions", subscription.toJSON());
    await refresh();
    setMessage({ tone: "ok", text: "Listo: este dispositivo va a recibir los avisos." });
  }, "No se pudieron activar las notificaciones.");

  const disable = () => run(async () => {
    const subscription = await currentSubscription();
    if (subscription) {
      await api.del("/api/push/subscriptions", { endpoint: subscription.endpoint });
      await subscription.unsubscribe();
    }
    await refresh();
    setMessage({ tone: "ok", text: "Este dispositivo ya no recibe avisos." });
  }, "No se pudieron apagar.");

  const test = () => run(async () => {
    const result = await api.post<{ sent: number }>("/api/push/test");
    setMessage(result.sent > 0
      ? { tone: "ok", text: "Aviso de prueba enviado: debería llegarte en unos segundos." }
      : { tone: "error", text: "No se pudo entregar. Apágalas y vuélvelas a activar." });
  }, "No se pudo enviar la prueba.");

  if (state === "loading" || state === "server-off") return null;

  return (
    <section className="block" aria-labelledby="push-title">
      <h2 id="push-title" className="section-title">Notificaciones</h2>
      <p className="muted">
        Avisos como "tu CDP venció". Nunca llevan montos, porque se pueden ver con el teléfono bloqueado.
      </p>
      {message && <p className={message.tone === "ok" ? "form-ok" : "form-error"} role="status">{message.text}</p>}
      {state === "ios-install" && (
        <p className="notice">En iPhone, primero agrega la app a la pantalla de inicio (Compartir → Agregar a pantalla de inicio) y ábrela desde ahí.</p>
      )}
      {state === "unsupported" && <p className="notice">Este navegador no permite notificaciones.</p>}
      {state === "denied" && (
        <p className="notice">Las notificaciones están bloqueadas para este sitio. Actívalas en los ajustes del navegador o del teléfono.</p>
      )}
      {state === "off" && (
        <button type="button" className="button secondary" onClick={enable} disabled={busy}>
          Activar en este dispositivo
        </button>
      )}
      {state === "on" && (
        <div className="actions start">
          <button type="button" className="button secondary" onClick={test} disabled={busy}>Enviar una prueba</button>
          <button type="button" className="button ghost" onClick={disable} disabled={busy}>Apagar en este dispositivo</button>
        </div>
      )}
    </section>
  );
}
