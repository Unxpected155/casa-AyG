import { useEffect, useState } from "react";
import { api, messageOf } from "../api";
import { navigate } from "../router";

type Status = { set: boolean; available: boolean };

/** Mi cédula para reconocer las facturas electrónicas. Se escribe, nunca se vuelve a mostrar. */
export function TaxId() {
  const [status, setStatus] = useState<Status | null>(null);
  const [number, setNumber] = useState("");
  const [editing, setEditing] = useState(false);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ tone: "ok" | "error"; text: string } | null>(null);

  const load = () => api.get<Status>("/api/me/tax-id").then(setStatus).catch(() => setStatus(null));
  useEffect(() => {
    void load();
  }, []);

  const run = async (action: () => Promise<void>, ok: string, fallback: string) => {
    setBusy(true);
    setMessage(null);
    try {
      await action();
      setMessage({ tone: "ok", text: ok });
      await load();
    } catch (e) {
      setMessage({ tone: "error", text: messageOf(e, fallback) });
    } finally {
      setBusy(false);
    }
  };

  const save = () => run(async () => {
    await api.put("/api/me/tax-id", { number: number.trim() });
    setNumber("");
    setEditing(false);
  }, "Cédula guardada.", "No se pudo guardar la cédula.");

  const remove = () => run(() => api.del("/api/me/tax-id"), "Cédula quitada.", "No se pudo quitar la cédula.");

  if (!status || !status.available) return null;

  return (
    <section className="block" aria-labelledby="tax-title">
      <h2 id="tax-title" className="section-title">Facturas electrónicas</h2>
      <p className="hint">
        Con tu cédula, la app reconoce las facturas que llegan a tu nombre. No se guarda el número: solo una huella
        que sirve para compararlo y que nadie puede convertir de vuelta en tu cédula.
      </p>
      {message && <p className={message.tone === "ok" ? "form-ok" : "form-error"} role="status">{message.text}</p>}
      {status.set && !editing ? (
        <div className="stack">
          <p><strong>Tu cédula ya está guardada.</strong></p>
          <div className="actions">
            <button type="button" className="button ghost" onClick={() => setEditing(true)} disabled={busy}>
              Cambiarla
            </button>
            <button type="button" className="button ghost danger" onClick={remove} disabled={busy}>
              Quitarla
            </button>
          </div>
        </div>
      ) : (
        <form className="add-category" onSubmit={(e) => { e.preventDefault(); void save(); }}>
          <label className="field grow">
            <span>Tu cédula</span>
            <input value={number} inputMode="numeric" autoComplete="off" maxLength={20}
                   onChange={(e) => setNumber(e.target.value.replace(/[^0-9 -]/g, ""))} placeholder="1-2345-6789" />
          </label>
          <button type="submit" className="button secondary" disabled={busy || number.replace(/\D/g, "").length < 9}>
            Guardar
          </button>
          {editing && (
            <button type="button" className="button ghost" onClick={() => { setEditing(false); setNumber(""); }}>
              Cancelar
            </button>
          )}
        </form>
      )}
      <button type="button" className="button ghost small" onClick={() => navigate("/facturas")}>
        Buzón y facturas por revisar
      </button>
    </section>
  );
}
