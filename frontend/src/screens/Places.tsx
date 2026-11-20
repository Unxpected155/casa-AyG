import { useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { api, messageOf } from "../api";
import { orderedPlaces, showKind, totals } from "../components/Balance";
import { CdpPanel, cdpDue } from "../components/CdpPanel";
import { EntrySheet } from "../components/EntrySheet";
import { Icon } from "../components/Icon";
import { Shell } from "../components/Shell";
import { amountChange, amountToInput, money, parseAmount, PLACE_KIND_LABEL, todayIso } from "../format";
import { useApp } from "../state";
import type { Currency, Place, PlaceKind } from "../types";

const KINDS: PlaceKind[] = ["BANCO", "SOBRE", "CDP", "EFECTIVO"];

const KIND_HINT: Record<PlaceKind, string> = {
  BANCO: "Tu cuenta del banco.",
  SOBRE: "Un apartado: digital dentro de tu banco o en efectivo. Para gastar o para ahorrar.",
  CDP: "Un certificado de depósito a plazo.",
  EFECTIVO: "Lo que traes en la billetera.",
};

/** Dónde vive el dinero: cada lugar con su saldo a hoy. */
export function Places() {
  const { space, places, touch } = useApp();
  const [adding, setAdding] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  // Si un CDP ya venció, abre directo su renovación (se llega aquí desde el aviso del mes).
  // Una sola vez: los lugares pueden llegar después de abrir la pantalla.
  const autoOpened = useRef(false);
  useEffect(() => {
    if (autoOpened.current || places.length === 0) return;
    autoOpened.current = true;
    const due = places.find((p) => !p.archived && cdpDue(p));
    if (due) setEditingId(due.id);
  }, [places]);
  const [distributeFrom, setDistributeFrom] = useState<string | null>(null);
  const [moving, setMoving] = useState<string | null>(null);
  const [entry, setEntry] = useState(false);

  const rows = orderedPlaces(places);
  const archived = places.filter((p) => p.archived);
  const { crc, usd } = totals(places);
  const readOnly = Boolean(space?.archived);

  return (
    // Con un lugar abierto, el botón flotante taparía «Guardar»: se esconde mientras se edita.
    <Shell onAdd={editingId || distributeFrom || moving !== null ? undefined : () => setEntry(true)}>
      <h1 className="page-title">{space?.kind === "FAMILIA" ? "Cuentas de la casa" : "Tus cuentas"}</h1>
      <p className="hint">Cada cuenta parte de lo que tenía el día que la agregaste y suma lo que anotas.</p>

      {rows.length > 0 && (
        <div className="places-head">
          <p className="places-total">
            Hoy tienes{" "}
            {rows.some((r) => r.place.currency === "CRC") && <strong className="figure">{money(crc, "CRC")}</strong>}
            {rows.some((r) => r.place.currency === "CRC") && rows.some((r) => r.place.currency === "USD") && " y "}
            {rows.some((r) => r.place.currency === "USD") && <strong className="figure">{money(usd, "USD")}</strong>}
          </p>
          {rows.length > 1 && !readOnly && (
            <button type="button" className="button secondary small" onClick={() => setMoving("")}>
              <Icon name="swap" size={18} /> Mover dinero
            </button>
          )}
        </div>
      )}

      <ul className="rows">
        {rows.map(({ place, nested }) => (
          <li key={place.id} className={`row place-row${nested ? " nested" : ""}`}>
            <button type="button" className="row-main row-button" aria-expanded={editingId === place.id}
                    onClick={() => setEditingId(editingId === place.id ? null : place.id)} disabled={readOnly}>
              <span className="row-name">
                {place.name}
                {showKind(place) && <span className="tag">{PLACE_KIND_LABEL[place.kind]}</span>}
                {cdpDue(place) && <span className="tag due">toca renovar</span>}
              </span>
              <span className={`figure${place.balance < 0 ? " negative" : ""}`}>
                {money(place.balance, place.currency, true)}
              </span>
            </button>
            {editingId === place.id && (
              <PlaceEditor
                place={place}
                places={places}
                onDone={() => { setEditingId(null); touch(); }}
                onDistribute={() => { setDistributeFrom(place.id); setEditingId(null); }}
                onMove={() => { setMoving(place.id); setEditingId(null); }}
              />
            )}
            {distributeFrom === place.id && (
              <Distribute from={place} places={places} onDone={(changed) => { setDistributeFrom(null); if (changed) touch(); }} />
            )}
          </li>
        ))}
      </ul>

      {rows.length === 0 && !adding && (
        <section className="empty">
          <p className="empty-title">Todavía no hay cuentas.</p>
          <p>Empieza por tu cuenta principal, con lo que tiene hoy. Después agrega tus sobres, tu CDP o el efectivo.</p>
        </section>
      )}

      {!readOnly && (adding
        ? <NewPlace places={places} onDone={(created) => { setAdding(false); if (created) touch(); }} />
        : (
          <button type="button" className="button secondary add-place" onClick={() => setAdding(true)}>
            <Icon name="plus" size={18} /> Agregar una cuenta
          </button>
        ))}

      {archived.length > 0 && (
        <details className="more archived-places">
          <summary>Archivados ({archived.length})</summary>
          <ul className="rows">
            {archived.map((p) => (
              <li key={p.id} className="row">
                <button type="button" className="row-main row-button" onClick={() => setEditingId(p.id)} disabled={readOnly}>
                  <span className="row-name">{p.name}</span>
                  <span className="figure">{money(p.balance, p.currency, true)}</span>
                </button>
                {editingId === p.id && (
                  <PlaceEditor place={p} places={places} onDone={() => { setEditingId(null); touch(); }} />
                )}
              </li>
            ))}
          </ul>
        </details>
      )}

      {(entry || moving !== null) && (
        <EntrySheet
          initialKind={moving !== null ? "mover" : "gasto"}
          initialFrom={moving || undefined}
          onClose={() => { setEntry(false); setMoving(null); }}
        />
      )}
    </Shell>
  );
}

/** Lugares donde puede ir un sobre: los que no son sobres, de la misma moneda. */
function parentOptions(places: Place[], currency: Currency, self?: string): Place[] {
  return places.filter((p) => !p.archived && p.kind !== "SOBRE" && p.currency === currency && p.id !== self);
}

function NewPlace({ places, onDone }: { places: Place[]; onDone: (created: boolean) => void }) {
  const { space } = useApp();
  const [name, setName] = useState("");
  const [kind, setKind] = useState<PlaceKind>(places.length === 0 ? "BANCO" : "SOBRE");
  const [currency, setCurrency] = useState<Currency>("CRC");
  const [opening, setOpening] = useState("");
  const [date, setDate] = useState(todayIso());
  const [parentId, setParentId] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const parents = parentOptions(places, currency);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const amount = opening.trim() === "" ? "0" : parseAmount(opening);
    if (!name.trim()) return setError("Ponle un nombre, por ejemplo «Cuenta principal».");
    if (!amount) return setError("Escribe el saldo de hoy, por ejemplo 250 000.");
    setBusy(true);
    setError(null);
    try {
      await api.post(`/api/spaces/${space!.id}/places`, {
        name: name.trim(), kind, currency, openingBalance: amount, openingDate: date,
        parentId: kind === "SOBRE" && parentId ? parentId : null,
      });
      onDone(true);
    } catch (e) {
      setError(messageOf(e, "No se pudo agregar."));
      setBusy(false);
    }
  };

  return (
    <form className="place-form" onSubmit={submit} noValidate>
      <h2 className="section-title">Cuenta nueva</h2>
      <div className="segmented" role="group" aria-label="Tipo de cuenta">
        {KINDS.map((k) => (
          <button key={k} type="button" aria-pressed={kind === k} onClick={() => setKind(k)}>{PLACE_KIND_LABEL[k]}</button>
        ))}
      </div>
      <p className="muted small">{KIND_HINT[kind]}</p>
      <div className="field-row">
        <label className="field grow">
          <span>Nombre</span>
          <input value={name} maxLength={40} onChange={(e) => setName(e.target.value)}
                 placeholder={kind === "SOBRE" ? "Comida" : kind === "CDP" ? "Mi CDP" : kind === "EFECTIVO" ? "Billetera" : "Cuenta principal"} />
        </label>
        <div className="field">
          <span>Moneda</span>
          <div className="segmented compact" role="group" aria-label="Moneda">
            <button type="button" aria-pressed={currency === "CRC"} onClick={() => { setCurrency("CRC"); setParentId(""); }}>₡</button>
            <button type="button" aria-pressed={currency === "USD"} onClick={() => { setCurrency("USD"); setParentId(""); }}>$</button>
          </div>
        </div>
      </div>
      <div className="field-row split">
        <label className="field grow">
          <span>¿Cuánto tiene hoy?</span>
          <input inputMode="decimal" value={opening} maxLength={18} onChange={(e) => setOpening(amountChange(e))} placeholder="0" />
        </label>
        <label className="field">
          <span>Al día</span>
          <input type="date" value={date} min="2000-01-01" onChange={(e) => setDate(e.target.value)} />
        </label>
      </div>
      {kind === "SOBRE" && parents.length > 0 && (
        <label className="field">
          <span>Dentro de (opcional)</span>
          <select value={parentId} onChange={(e) => setParentId(e.target.value)}>
            <option value="">Ninguno</option>
            {parents.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
          </select>
        </label>
      )}
      {error && <p className="form-error" role="alert">{error}</p>}
      <div className="actions">
        <button type="button" className="button ghost" onClick={() => onDone(false)}>Cancelar</button>
        <button type="submit" className="button primary" disabled={busy}>{busy ? "Guardando…" : "Agregar"}</button>
      </div>
    </form>
  );
}

function PlaceEditor({ place, places, onDone, onDistribute, onMove }: {
  place: Place;
  places: Place[];
  onDone: () => void;
  onDistribute?: () => void;
  onMove?: () => void;
}) {
  const { space } = useApp();
  const [name, setName] = useState(place.name);
  const [opening, setOpening] = useState(amountToInput(place.openingBalance));
  const [date, setDate] = useState(place.openingDate);
  const [parentId, setParentId] = useState(place.parentId ?? "");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const parents = parentOptions(places, place.currency, place.id);
  const others = places.filter((p) => !p.archived && p.id !== place.id && p.currency === place.currency);

  const save = async (archived: boolean) => {
    const amount = parseAmount(opening) ?? (opening.trim() === "" ? "0" : null);
    if (!name.trim()) return setError("El nombre no puede quedar vacío.");
    if (!amount) return setError("Escribe un saldo válido.");
    setBusy(true);
    setError(null);
    try {
      await api.put(`/api/spaces/${space!.id}/places/${place.id}`, {
        name: name.trim(), openingBalance: amount, openingDate: date,
        parentId: place.kind === "SOBRE" && parentId ? parentId : null, archived,
      });
      onDone();
    } catch (e) {
      setError(messageOf(e, "No se pudo guardar."));
      setBusy(false);
    }
  };

  return (
    <form className="place-form inline" onSubmit={(e) => { e.preventDefault(); void save(place.archived); }} noValidate>
      {!place.archived && others.length > 0 && (
        <div className="place-actions">
          {onMove && (
            <button type="button" className="button secondary small" onClick={onMove}>
              <Icon name="swap" size={16} /> Mover desde aquí
            </button>
          )}
          {onDistribute && (
            <button type="button" className="button secondary small" onClick={onDistribute}>Repartir</button>
          )}
        </div>
      )}
      <div className="field-row">
        <label className="field grow">
          <span>Nombre</span>
          <input value={name} maxLength={40} onChange={(e) => setName(e.target.value)} />
        </label>
      </div>
      <div className="field-row">
        <label className="field grow">
          <span>Saldo inicial</span>
          <input inputMode="decimal" value={opening} maxLength={18} onChange={(e) => setOpening(amountChange(e))} />
        </label>
        <label className="field">
          <span>Al día</span>
          <input type="date" value={date} min="2000-01-01" onChange={(e) => setDate(e.target.value)} />
        </label>
      </div>
      <p className="muted small">
        Si el saldo no cuadra con el banco, corrige el saldo inicial o la fecha: lo anotado antes de esa fecha no cuenta.
      </p>
      {place.kind === "CDP" && !place.archived && <CdpPanel place={place} places={places} onDone={onDone} />}
      {place.kind === "SOBRE" && parents.length > 0 && (
        <label className="field">
          <span>Dentro de</span>
          <select value={parentId} onChange={(e) => setParentId(e.target.value)}>
            <option value="">Ninguno</option>
            {parents.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
          </select>
        </label>
      )}
      {error && <p className="form-error" role="alert">{error}</p>}
      <div className="actions">
        <button type="button" className="button ghost" onClick={() => void save(!place.archived)} disabled={busy}>
          {place.archived ? "Volver a usar" : "Archivar"}
        </button>
        <button type="submit" className="button primary" disabled={busy}>{busy ? "Guardando…" : "Guardar"}</button>
      </div>
    </form>
  );
}

/** Repartir: del banco a varios sobres en un solo paso (por ejemplo, al recibir el pago). */
function Distribute({ from, places, onDone }: { from: Place; places: Place[]; onDone: (changed: boolean) => void }) {
  const { space } = useApp();
  const targets = useMemo(() => {
    const same = places.filter((p) => !p.archived && p.id !== from.id && p.currency === from.currency);
    // Primero los sobres que están dentro de este lugar.
    return [...same.filter((p) => p.parentId === from.id), ...same.filter((p) => p.parentId !== from.id)];
  }, [places, from]);
  const [amounts, setAmounts] = useState<Record<string, string>>({});
  const [date, setDate] = useState(todayIso());
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const parsed = targets
    .map((p) => ({ to: p.id, amount: amounts[p.id]?.trim() ? parseAmount(amounts[p.id] ?? "") : "" }))
    .filter((i) => i.amount !== "");
  const invalid = parsed.some((i) => i.amount === null || Number(i.amount) === 0);
  const total = parsed.reduce((sum, i) => sum + Number(i.amount ?? 0), 0);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (parsed.length === 0) return setError("Escribe cuánto va a cada cuenta.");
    if (invalid) return setError("Revisa los montos: tienen que ser mayores que cero.");
    setBusy(true);
    setError(null);
    try {
      await api.post(`/api/spaces/${space!.id}/transfers/distribute`, {
        from: from.id, date, description: note.trim() || null,
        items: parsed.map((i) => ({ to: i.to, amount: i.amount })),
      });
      onDone(true);
    } catch (e) {
      setError(messageOf(e, "No se pudo repartir."));
      setBusy(false);
    }
  };

  return (
    <form className="place-form inline" onSubmit={submit} noValidate>
      <h3 className="form-title">Repartir desde {from.name}</h3>
      <ul className="rows">
        {targets.map((p) => (
          <li key={p.id} className="row distribute-row">
            <label className="row-main">
              <span className="row-name">{p.name}</span>
              <span className="money-input">
                <span aria-hidden="true">{p.currency === "USD" ? "$" : "₡"}</span>
                <input inputMode="decimal" maxLength={18} placeholder="0" aria-label={`Monto para ${p.name}`}
                       value={amounts[p.id] ?? ""} onChange={(e) => setAmounts({ ...amounts, [p.id]: amountChange(e) })} />
              </span>
            </label>
          </li>
        ))}
      </ul>
      <p className="distribute-total">
        Total: <strong className="figure">{money(total, from.currency, true)}</strong>
        {" · "}en {from.name} quedarían <span className="figure">{money(from.balance - total, from.currency, true)}</span>
      </p>
      <div className="field-row">
        <label className="field grow">
          <span>Nota (opcional)</span>
          <input value={note} maxLength={200} onChange={(e) => setNote(e.target.value)} placeholder="Para el súper" />
        </label>
        <label className="field">
          <span>Fecha</span>
          <input type="date" value={date} min="2000-01-01" onChange={(e) => setDate(e.target.value)} />
        </label>
      </div>
      {error && <p className="form-error" role="alert">{error}</p>}
      <div className="actions">
        <button type="button" className="button ghost" onClick={() => onDone(false)}>Cancelar</button>
        <button type="submit" className="button primary" disabled={busy}>{busy ? "Repartiendo…" : "Repartir"}</button>
      </div>
    </form>
  );
}
