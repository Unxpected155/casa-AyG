import { useEffect, useState } from "react";
import { api, messageOf, withPasskeyConfirm } from "../api";
import { Icon } from "../components/Icon";
import { Shell } from "../components/Shell";
import { dateLabel } from "../format";
import { navigate } from "../router";
import { useApp } from "../state";
import type { Passkey } from "../types";
import { logout, registerPasskey } from "../webauthn";

export function Account() {
  const { session, spaces, reloadSession } = useApp();
  const [passkeys, setPasskeys] = useState<Passkey[]>([]);
  const [codes, setCodes] = useState<string[] | null>(null);
  const [label, setLabel] = useState("");
  const [message, setMessage] = useState<{ tone: "ok" | "error"; text: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const me = session.kind === "ready" ? session.me : null;

  const load = () => api.get<Passkey[]>("/api/me/passkeys").then(setPasskeys).catch(() => setPasskeys([]));
  useEffect(() => {
    void load();
  }, []);

  const run = async (action: () => Promise<void>, ok: string, fallback: string) => {
    setBusy(true);
    setMessage(null);
    try {
      await action();
      if (ok) setMessage({ tone: "ok", text: ok });
    } catch (e) {
      setMessage({ tone: "error", text: messageOf(e, fallback) });
    } finally {
      setBusy(false);
    }
  };

  const addPasskey = () => run(async () => {
    await registerPasskey(label.trim() || "Otro dispositivo");
    setLabel("");
    await load();
    await reloadSession();
  }, "Passkey agregada.", "No se pudo agregar la passkey.");

  const removePasskey = (id: string) => run(async () => {
    await withPasskeyConfirm(() => api.del(`/api/me/passkeys/${encodeURIComponent(id)}`));
    await load();
    await reloadSession();
  }, "Passkey borrada.", "No se pudo borrar la passkey.");

  const newCodes = () => run(async () => {
    const result = await withPasskeyConfirm(() => api.post<{ codes: string[] }>("/api/me/recovery-codes"));
    setCodes(result.codes);
  }, "", "No se pudieron generar los códigos.");

  const revokeOthers = () => run(async () => {
    const result = await api.post<{ revoked: number }>("/api/me/sessions/revoke-others");
    setMessage({ tone: "ok", text: result.revoked === 0 ? "No había otras sesiones abiertas."
      : `Se cerraron ${result.revoked} sesiones en otros dispositivos.` });
  }, "", "No se pudieron cerrar las sesiones.");

  const exportSpace = (spaceId: string) => run(async () => {
    const response = await fetch(`/api/spaces/${spaceId}/export`, { credentials: "same-origin" });
    if (!response.ok) throw new Error("export");
    const url = URL.createObjectURL(await response.blob());
    const link = document.createElement("a");
    link.href = url;
    link.download = `casa-ayg-${new Date().toISOString().slice(0, 10)}.json`;
    link.click();
    URL.revokeObjectURL(url);
  }, "Descarga lista.", "No se pudo exportar.");

  const exit = async () => {
    await logout();
    await reloadSession();
    navigate("/login", true);
  };

  if (!me) return null;

  return (
    <Shell>
      <h1 className="page-title">{me.displayName}</h1>
      <p className="muted">Usuario <span className="mono">{me.username}</span></p>

      {message && <p className={message.tone === "ok" ? "form-ok" : "form-error"} role="status">{message.text}</p>}

      <section className="block" aria-labelledby="pk-title">
        <h2 id="pk-title" className="section-title">Passkeys</h2>
        {passkeys.length < 2 && (
          <p className="notice">
            Tienes una sola passkey. Registra otra en un segundo dispositivo para no quedarte fuera si pierdes este.
          </p>
        )}
        <ul className="rows">
          {passkeys.map((p) => (
            <li key={p.id} className="row">
              <div className="row-main">
                <span className="row-name"><Icon name="key" size={18} /> {p.label}</span>
                <span className="row-tools">
                  <span className="muted small">Último uso: {dateLabel(p.lastUsed)}</span>
                  {passkeys.length > 1 && (
                    <button type="button" className="icon-button" aria-label={`Borrar ${p.label}`}
                            onClick={() => removePasskey(p.id)} disabled={busy}>
                      <Icon name="trash" size={18} />
                    </button>
                  )}
                </span>
              </div>
            </li>
          ))}
        </ul>
        <div className="add-category">
          <label className="field grow">
            <span>Nombre del dispositivo nuevo</span>
            <input value={label} maxLength={100} onChange={(e) => setLabel(e.target.value)} placeholder="Laptop" />
          </label>
          <button type="button" className="button secondary" onClick={addPasskey} disabled={busy}>
            <Icon name="plus" size={18} /> Agregar passkey
          </button>
        </div>
      </section>

      <section className="block" aria-labelledby="rc-title">
        <h2 id="rc-title" className="section-title">Códigos de recuperación</h2>
        {codes ? (
          <div className="codes">
            <p>Guárdalos fuera del teléfono: en papel o en tu gestor de contraseñas. <strong>No se vuelven a mostrar.</strong></p>
            <ol className="code-list">{codes.map((c) => <li key={c} className="mono">{c}</li>)}</ol>
            <div className="actions">
              <button type="button" className="button ghost" onClick={() => window.print()}>Imprimir</button>
              <button type="button" className="button primary" onClick={() => { setCodes(null); void reloadSession(); }}>
                Ya los guardé
              </button>
            </div>
          </div>
        ) : (
          <>
            <p className="hint">
              Te quedan <strong className="figure">{me.recoveryCodesLeft}</strong> códigos. Cada uno sirve una vez para
              entrar si pierdes tus dispositivos. Generar nuevos anula los anteriores.
            </p>
            <button type="button" className="button secondary" onClick={newCodes} disabled={busy}>
              Generar códigos nuevos
            </button>
          </>
        )}
      </section>

      <section className="block" aria-labelledby="ses-title">
        <h2 id="ses-title" className="section-title">Sesiones y datos</h2>
        <div className="stack">
          <button type="button" className="button ghost" onClick={revokeOthers} disabled={busy}>
            Cerrar sesión en otros dispositivos
          </button>
          {spaces.map((s) => (
            <button key={s.id} type="button" className="button ghost" onClick={() => exportSpace(s.id)} disabled={busy}>
              <Icon name="download" size={18} /> Descargar todo de «{s.kind === "FAMILIA" ? "Familia" : s.name}»
            </button>
          ))}
          <button type="button" className="button ghost" onClick={() => navigate("/boda")}>
            <Icon name="rings" size={18} /> Espacio familiar
          </button>
          <button type="button" className="button ghost danger" onClick={exit}>
            <Icon name="logout" size={18} /> Cerrar sesión
          </button>
        </div>
      </section>
    </Shell>
  );
}
