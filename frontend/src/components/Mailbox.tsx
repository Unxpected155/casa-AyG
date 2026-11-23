import { useEffect, useState } from "react";
import { api, messageOf, withPasskeyConfirm } from "../api";
import { dateLabel } from "../format";

interface MailboxStatus {
  available: boolean;
  connected: boolean;
  email: string | null;
  connectedAt: string | null;
  lastCheckAt: string | null;
  lastError: string | null;
  lastMonth: { result: string; count: number }[];
}

const RESULT_LABEL: Record<string, string> = {
  NUEVA: "nuevas",
  REPETIDA: "repetidas",
  AJENA: "de otras personas",
  INCOMPLETA: "esperando la respuesta de Hacienda",
  RECHAZADA: "rechazadas",
  SIN_XML: "sin factura",
};

/**
 * El buzón de facturas: la cuenta de Gmail dedicada, conectada con permiso de solo lectura.
 * Al volver de Google, la URL trae ?code&state: se mandan al servidor con la sesión.
 */
export function Mailbox({ onRead }: { onRead: () => void }) {
  const [status, setStatus] = useState<MailboxStatus | null>(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ tone: "ok" | "error"; text: string } | null>(null);

  const load = () => api.get<MailboxStatus>("/api/invoices/mailbox").then(setStatus).catch(() => setStatus(null));

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const code = params.get("code");
    const state = params.get("state");
    const cancelled = params.get("buzon") === "cancelado";
    if (code || state || cancelled) {
      // Que el código no quede en el historial ni en la barra.
      window.history.replaceState(null, "", "/facturas");
    }
    if (code && state) {
      setBusy(true);
      api.post<{ email: string }>("/api/invoices/mailbox/callback", { code, state })
        .then((r) => setMessage({ tone: "ok", text: `Buzón conectado: ${r.email}.` }))
        .catch((e) => setMessage({ tone: "error", text: messageOf(e, "No se pudo conectar el buzón.") }))
        .finally(() => { setBusy(false); void load(); });
    } else {
      if (cancelled) setMessage({ tone: "error", text: "No se conectó el buzón: Google no dio el permiso." });
      void load();
    }
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
      void load();
    }
  };

  const connect = () => run(async () => {
    const { url } = await withPasskeyConfirm(() => api.post<{ url: string }>("/api/invoices/mailbox/connect"));
    window.location.assign(url);
  }, "No se pudo empezar la conexión.");

  const check = () => run(async () => {
    const r = await api.post<{ read: number; added: number }>("/api/invoices/mailbox/check");
    setMessage({ tone: "ok", text: r.added > 0 ? `Llegaron ${r.added} facturas nuevas.`
      : r.read > 0 ? "Se leyeron los correos nuevos; no había facturas para ustedes." : "No hay correos nuevos." });
    onRead();
  }, "No se pudo revisar el buzón.");

  const disconnect = () => run(async () => {
    await withPasskeyConfirm(() => api.del("/api/invoices/mailbox"));
    setMessage({ tone: "ok", text: "Buzón desconectado y permiso revocado en Google." });
  }, "No se pudo desconectar el buzón.");

  if (!status) return null;
  const counts = status.lastMonth.filter((c) => c.count > 0)
    .map((c) => `${c.count} ${RESULT_LABEL[c.result] ?? c.result.toLowerCase()}`).join(" · ");

  return (
    <section className="block mailbox" aria-labelledby="mailbox-title">
      <h2 id="mailbox-title" className="section-title">Buzón de facturas</h2>
      {message && <p className={message.tone === "ok" ? "form-ok" : "form-error"} role="status">{message.text}</p>}
      {!status.available ? (
        <p className="hint">El buzón todavía no está configurado en el servidor.</p>
      ) : !status.connected ? (
        <>
          <p className="hint">
            Conecta la cuenta de Gmail de las facturas. La app solo puede leerla: no envía, no borra y no ve ningún
            otro correo. Google te va a avisar que la app no está verificada: es normal, toca «Avanzado» y continúa.
          </p>
          <button type="button" className="button primary" onClick={connect} disabled={busy}>Conectar el buzón</button>
        </>
      ) : (
        <>
          <p>
            Conectado a <strong>{status.email}</strong>. Se revisa cada dos horas de día
            {status.lastCheckAt ? <>; la última vez, {dateLabel(status.lastCheckAt)}.</> : "."}
          </p>
          {status.lastError && <p className="notice">{status.lastError}</p>}
          {counts && <p className="muted small">Último mes: {counts}.</p>}
          <div className="actions">
            <button type="button" className="button ghost danger" onClick={disconnect} disabled={busy}>Desconectar</button>
            <button type="button" className="button secondary" onClick={check} disabled={busy}>Revisar ahora</button>
          </div>
        </>
      )}
    </section>
  );
}
