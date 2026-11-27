import { useEffect, useState } from "react";
import { api, messageOf } from "../api";
import { Icon } from "./Icon";

interface PushStatus {
  enabled: boolean;
  publicKey?: string;
  devices?: number;
}

type State = "loading" | "unsupported" | "ios-install" | "denied" | "off" | "on" | "server-off";

type PushKind = "SEGURIDAD" | "POR_PAGAR" | "CDP" | "PRESUPUESTO" | "CASA" | "FACTURAS" | "DIEZMO" | "METAS";

interface Preference {
  kind: PushKind;
  enabled: boolean;
}

/** Cómo se llama cada tipo y cuándo llega (PRODUCT.md, «Notificaciones push»). */
const KINDS: Record<PushKind, { name: string; when: string }> = {
  POR_PAGAR: { name: "Por pagar", when: "Pagos fijos y cuotas que vencen pronto" },
  CDP: { name: "CDP", when: "El día antes y el día que vence" },
  PRESUPUESTO: { name: "Presupuesto", when: "Una categoría llegó al 80 % o al 100 %" },
  CASA: { name: "La casa", when: "El cierre del mes y cambios a lo que anotaste" },
  FACTURAS: { name: "Facturas", when: "Un resumen a las 6 p. m. si llegaron nuevas" },
  DIEZMO: { name: "Diezmo", when: "Los días 1 y 16 si hay diezmo por dar" },
  METAS: { name: "Metas", when: "Cuando una meta llega al 25, 50, 75 o 100 %" },
  SEGURIDAD: { name: "Seguridad", when: "Una passkey nueva o un código de recuperación usado" },
};

/**
 * Qué tipos de aviso quiere la persona. Vale para todos sus dispositivos (lo guarda el
 * servidor). Seguridad no se apaga.
 */
function PushKinds() {
  const [prefs, setPrefs] = useState<Preference[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.get<Preference[]>("/api/push/preferences").then(setPrefs).catch(() => setPrefs(null));
  }, []);

  if (!prefs) return null;

  const toggle = async (kind: PushKind, enabled: boolean) => {
    const before = prefs;
    // Se ve el cambio al instante; si el servidor no lo guarda, vuelve a como estaba.
    setPrefs(prefs.map((p) => (p.kind === kind ? { ...p, enabled } : p)));
    setError(null);
    try {
      await api.put(`/api/push/preferences/${kind}`, { enabled });
    } catch (e) {
      setPrefs(before);
      setError(messageOf(e, "No se pudo guardar."));
    }
  };

  // Seguridad al final: es la que no se toca.
  const ordered = [...prefs.filter((p) => p.kind !== "SEGURIDAD"), ...prefs.filter((p) => p.kind === "SEGURIDAD")];

  return (
    <div className="push-kinds" role="group" aria-labelledby="push-kinds-title">
      <h3 className="push-kinds-title" id="push-kinds-title">Qué te avisamos</h3>
      <ul className="push-kinds-list">
        {ordered.map((p) => {
          const id = `push-kind-${p.kind}`;
          const text = (
            <span className="push-kind-text">
              <span className="push-kind-name">
                {KINDS[p.kind].name}
                {p.kind === "SEGURIDAD" && <span className="muted"> · siempre encendidas</span>}
              </span>
              <span className="push-kind-when muted small" id={`${id}-when`}>{KINDS[p.kind].when}</span>
            </span>
          );
          // Seguridad no es una casilla deshabilitada (se vería gris, como apagada): es una marca fija.
          return (
            <li key={p.kind}>
              {p.kind === "SEGURIDAD" ? (
                <div className="push-kind fixed">
                  <span className="push-kind-mark" aria-hidden="true"><Icon name="check" size={16} /></span>
                  {text}
                </div>
              ) : (
                <label className="push-kind" htmlFor={id}>
                  <input id={id} type="checkbox" checked={p.enabled} aria-describedby={`${id}-when`}
                         onChange={(e) => void toggle(p.kind, e.target.checked)} />
                  {text}
                </label>
              )}
            </li>
          );
        })}
      </ul>
      {error && <p className="form-error" role="alert">{error}</p>}
      <p className="muted small">
        De 9 p. m. a 7 a. m. no suena nada (menos seguridad): lo que pase en ese rato llega a las 7.
        Como máximo, un aviso de cada tipo al día.
      </p>
    </div>
  );
}

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
        Avisos en este dispositivo, sin montos: se pueden ver con el teléfono bloqueado.
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
      <PushKinds />
    </section>
  );
}
