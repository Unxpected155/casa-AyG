import { useEffect, useRef, useState, type FormEvent } from "react";
import { api, messageOf, withPasskeyConfirm } from "../api";
import { Shell } from "../components/Shell";
import { navigate } from "../router";
import { useApp } from "../state";
import type { MergeRequest } from "../types";

/** Unir los espacios personales en el familiar: lo propone uno, lo confirma la otra persona. */
export function Wedding() {
  const { spaces, reloadSession, selectSpace } = useApp();
  const [requests, setRequests] = useState<MergeRequest[] | null>(null);
  const [partner, setPartner] = useState("");
  const [familyName, setFamilyName] = useState("Casa A&G");
  const [message, setMessage] = useState<{ tone: "ok" | "error"; text: string } | null>(null);
  const [busy, setBusy] = useState(false);

  const load = () => api.get<MergeRequest[]>("/api/merge-requests").then(setRequests).catch(() => setRequests([]));
  useEffect(() => {
    void load();
  }, []);

  const family = spaces.find((s) => s.kind === "FAMILIA" && !s.archived);
  const pending = requests?.find((r) => r.status === "PENDIENTE");

  const act = async (action: () => Promise<unknown>, ok: string) => {
    setBusy(true);
    setMessage(null);
    try {
      await withPasskeyConfirm(action);
      setMessage({ tone: "ok", text: ok });
      await load();
      await reloadSession();
    } catch (e) {
      setMessage({ tone: "error", text: messageOf(e, "No se pudo completar.") });
    } finally {
      setBusy(false);
    }
  };

  // Al aceptar, la app pasa sola al espacio de la casa: ahí es donde se sigue.
  const joined = useRef<string | null>(null);
  useEffect(() => {
    if (joined.current && spaces.some((s) => s.id === joined.current)) {
      selectSpace(joined.current);
      joined.current = null;
    }
  }, [spaces, selectSpace]);

  const approve = () => {
    if (!pending) return;
    void act(async () => {
      const result = await api.post<{ familySpaceId: string }>(`/api/merge-requests/${pending.id}/approve`);
      joined.current = result.familySpaceId;
    }, "¡Listo! Ya comparten la casa.");
  };

  const propose = (event: FormEvent) => {
    event.preventDefault();
    void act(() => api.post("/api/merge-requests", { partnerUsername: partner.trim().toLowerCase(), familyName: familyName.trim() }),
      "Listo. Ahora tu pareja tiene que confirmarlo desde su cuenta.");
  };

  return (
    <Shell>
      <h1 className="page-title">Espacio familiar</h1>
      {message && <p className={message.tone === "ok" ? "form-ok" : "form-error"} role="status">{message.text}</p>}

      {family && (
        <section className="block">
          <p className="lead">Comparten «{family.name}» con {family.members.map((m) => m.displayName).join(" y ")}.</p>
          <p className="hint">Todo lo que registren ahí lo ven los dos. Cada quien conserva su espacio personal, que sigue siendo privado.</p>
          <div className="actions">
            <button type="button" className="button ghost" onClick={() => selectSpace(family.id)}>Ir al espacio familiar</button>
            <button type="button" className="button secondary" onClick={() => { selectSpace(family.id); navigate("/casa"); }}>Ver la casa</button>
          </div>
        </section>
      )}

      {!family && pending && !pending.iAmRequester && (
        <section className="block">
          <p className="lead">{pending.otherPerson} propone unir sus finanzas en «{pending.familyName}».</p>
          <WhatHappens />
          <p className="hint">Para confirmar se te pedirá tu passkey.</p>
          <div className="actions">
            <button type="button" className="button ghost" disabled={busy}
                    onClick={() => act(() => api.post(`/api/merge-requests/${pending.id}/decline`), "Rechazaste la propuesta. Nada cambió.")}>
              Ahora no
            </button>
            <button type="button" className="button primary" disabled={busy}
                    onClick={approve}>
              Confirmar y unir
            </button>
          </div>
        </section>
      )}

      {!family && pending?.iAmRequester && (
        <section className="block">
          <p className="lead">Esperando a que {pending.otherPerson} confirme «{pending.familyName}».</p>
          <p className="hint">La propuesta vence el {new Date(pending.expiresAt).toLocaleDateString("es-CR", { day: "numeric", month: "long" })}.</p>
          <button type="button" className="button ghost" disabled={busy}
                  onClick={() => act(() => api.post(`/api/merge-requests/${pending.id}/decline`), "Cancelaste la propuesta.")}>
            Cancelar la propuesta
          </button>
        </section>
      )}

      {!family && !pending && requests !== null && (
        <section className="block">
          <p className="lead">Cuando se casen, pueden unir sus espacios en uno familiar.</p>
          <WhatHappens />
          <form className="stack" onSubmit={propose}>
            <label className="field">
              <span>Usuario de tu pareja</span>
              <input value={partner} maxLength={30} autoCapitalize="none" autoComplete="off" spellCheck={false}
                     onChange={(e) => setPartner(e.target.value)} required />
            </label>
            <label className="field">
              <span>Nombre del espacio familiar</span>
              <input value={familyName} maxLength={60} onChange={(e) => setFamilyName(e.target.value)} required />
            </label>
            <button type="submit" className="button primary" disabled={busy}>Proponer unir los espacios</button>
          </form>
        </section>
      )}
    </Shell>
  );
}

function WhatHappens() {
  return (
    <ul className="explain">
      <li>Se crea un espacio familiar, vacío, donde los dos ven y registran todo. Ahí entran los salarios.</li>
      <li>Cada quien conserva su espacio personal, privado: del otro solo se ve cuánto tiene, nunca en qué lo gasta.</li>
      <li>Al cerrar cada mes, la casa le da a cada quien lo mismo: un monto fijo más un bono de lo que sobró.</li>
      <li>Si alguien quiere poner de lo suyo, lo aporta a la casa.</li>
      <li>Necesita la confirmación de los dos, con passkey. Nadie puede hacerlo solo.</li>
    </ul>
  );
}
