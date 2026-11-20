import { useEffect, useState, type FormEvent } from "react";
import { api, messageOf } from "../api";
import { amountChange, amountToInput, dayMonthLabel, GROUP_LABEL, money, parseAmount, todayIso } from "../format";
import { useApp } from "../state";
import type { Currency, Recurring } from "../types";
import { EntrySheet } from "./EntrySheet";

const BEFORE_LABEL: Record<number, string> = { 0: "el mismo día", 1: "1 día antes", 3: "3 días antes" };

function useRecurring(year: number, month: number) {
  const { space, version } = useApp();
  const [items, setItems] = useState<Recurring[] | null>(null);
  useEffect(() => {
    if (!space) return;
    let cancelled = false;
    api.get<Recurring[]>(`/api/spaces/${space.id}/recurring?year=${year}&month=${month}`)
      .then((list) => !cancelled && setItems(list))
      .catch(() => !cancelled && setItems([]));
    return () => {
      cancelled = true;
    };
  }, [space, version, year, month]);
  return items;
}

function whenLabel(r: Recurring): string {
  if (!r.dueDate) return "sin fecha fija";
  const today = todayIso();
  if (r.dueDate === today) return "vence hoy";
  const tomorrow = new Date();
  tomorrow.setDate(tomorrow.getDate() + 1);
  const t = `${tomorrow.getFullYear()}-${String(tomorrow.getMonth() + 1).padStart(2, "0")}-${String(tomorrow.getDate()).padStart(2, "0")}`;
  if (r.dueDate === t) return "vence mañana";
  return r.dueDate < today ? `venció el ${dayMonthLabel(r.dueDate)}` : `vence el ${dayMonthLabel(r.dueDate)}`;
}

/**
 * "Por pagar" en el mes: los pagos fijos activos que aún no se pagaron. "Ya lo pagué" abre la
 * hoja de anotar llena con el pago fijo, que queda ligado y deja de recordarse ese mes.
 */
export function DueList() {
  const { month } = useApp();
  const items = useRecurring(month.year, month.month);
  const [paying, setPaying] = useState<Recurring | null>(null);
  if (!items) return null;
  const active = items.filter((r) => r.active);
  if (active.length === 0) return null;
  const pending = active.filter((r) => !r.paidTransactionId);
  const paid = active.length - pending.length;

  return (
    <section className="group due-list" aria-labelledby="due-title">
      <h2 id="due-title" className="group-title">Por pagar</h2>
      {pending.length === 0 ? (
        <p className="muted due-done">Todos los pagos fijos del mes están hechos.</p>
      ) : (
        <ul className="rows">
          {pending.map((r) => (
            <li key={r.id} className="row">
              <div className="row-main">
                <span className="due-text">
                  <span className="row-name">{r.name}</span>
                  <span className={`due-when${r.dueDate && r.dueDate <= todayIso() ? " late" : ""}`}>
                    {whenLabel(r)}
                    {r.expectedAmount != null && <> · <span className="figure">{money(r.expectedAmount, r.currency, true)}</span></>}
                  </span>
                </span>
                <button type="button" className="button secondary small" onClick={() => setPaying(r)}>Ya lo pagué</button>
              </div>
            </li>
          ))}
        </ul>
      )}
      {paid > 0 && pending.length > 0 && <p className="muted small due-paid">{paid} ya {paid === 1 ? "pagado" : "pagados"} este mes.</p>}
      {paying && (
        <EntrySheet
          preset={{
            categoryId: paying.categoryId, description: paying.name, amount: paying.expectedAmount,
            currency: paying.currency, placeId: paying.placeId, recurringId: paying.id,
          }}
          onClose={() => setPaying(null)}
        />
      )}
    </section>
  );
}

/** Lista y edición de los pagos fijos (en Plan). */
export function RecurringManager({ readOnly }: { readOnly: boolean }) {
  const today = new Date();
  const items = useRecurring(today.getFullYear(), today.getMonth() + 1);
  const [editing, setEditing] = useState<Recurring | "new" | null>(null);

  return (
    <section className="block" aria-labelledby="rec-title">
      <h2 id="rec-title" className="section-title">Pagos fijos</h2>
      <p className="hint">
        Agua, luz, internet, alquiler… Con día de pago te llega un aviso; sin día, solo aparece en "Por pagar" del mes.
      </p>
      <ul className="rows">
        {(items ?? []).map((r) => (
          <li key={r.id} className={`row${r.active ? "" : " inactive"}`}>
            <button type="button" className="row-main row-button" onClick={() => setEditing(editing === r ? null : r)}
                    disabled={readOnly} aria-expanded={editing === r}>
              <span className="due-text">
                <span className="row-name">
                  {r.name}
                  {!r.active && <span className="tag">en pausa</span>}
                </span>
                <span className="due-when">
                  {r.dueDay ? `día ${r.dueDay} · aviso ${BEFORE_LABEL[r.notifyDaysBefore]}` : "sin fecha fija, sin aviso"}
                </span>
              </span>
              {r.expectedAmount != null && <span className="figure">{money(r.expectedAmount, r.currency, true)}</span>}
            </button>
            {editing === r && <RecurringForm item={r} onDone={() => setEditing(null)} />}
          </li>
        ))}
      </ul>
      {!readOnly && (editing === "new"
        ? <RecurringForm onDone={() => setEditing(null)} />
        : <button type="button" className="button secondary add-place" onClick={() => setEditing("new")}>Agregar un pago fijo</button>)}
    </section>
  );
}

function RecurringForm({ item, onDone }: { item?: Recurring; onDone: () => void }) {
  const { space, categories, places, touch } = useApp();
  const expenseCategories = categories.filter((c) => c.group !== "INGRESO" && (c.active || c.id === item?.categoryId));
  const [name, setName] = useState(item?.name ?? "");
  const [categoryId, setCategoryId] = useState(item?.categoryId
    ?? expenseCategories.find((c) => c.name.toLowerCase().startsWith("servicios"))?.id ?? expenseCategories[0]?.id ?? "");
  const [day, setDay] = useState(item?.dueDay ? String(item.dueDay) : "");
  const [amount, setAmount] = useState(item?.expectedAmount != null ? amountToInput(item.expectedAmount) : "");
  const [currency, setCurrency] = useState<Currency>(item?.currency ?? "CRC");
  const [placeId, setPlaceId] = useState(item?.placeId ?? "");
  const [before, setBefore] = useState(item?.notifyDaysBefore ?? 1);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const accounts = places.filter((p) => !p.archived && p.currency === currency);

  const save = async (event: FormEvent | null, active = item?.active ?? true) => {
    event?.preventDefault();
    const dueDay = day.trim() ? Number(day) : null;
    if (!name.trim()) return setError("Ponle un nombre, por ejemplo Internet.");
    if (dueDay !== null && !(Number.isInteger(dueDay) && dueDay >= 1 && dueDay <= 31)) return setError("El día va de 1 a 31.");
    const expected = amount.trim() ? parseAmount(amount) : null;
    if (amount.trim() && !expected) return setError("Revisa el monto aproximado.");
    setBusy(true);
    setError(null);
    const body = { name: name.trim(), categoryId, placeId: placeId || null, dueDay, expectedAmount: expected, currency,
                   notifyDaysBefore: before, active };
    try {
      if (item) await api.put(`/api/spaces/${space!.id}/recurring/${item.id}`, body);
      else await api.post(`/api/spaces/${space!.id}/recurring`, body);
      touch();
      onDone();
    } catch (e) {
      setError(messageOf(e, "No se pudo guardar."));
      setBusy(false);
    }
  };

  const remove = async () => {
    setBusy(true);
    try {
      await api.del(`/api/spaces/${space!.id}/recurring/${item!.id}`);
      touch();
      onDone();
    } catch (e) {
      setError(messageOf(e, "No se pudo borrar."));
      setBusy(false);
    }
  };

  return (
    <form className="place-form inline" onSubmit={(e) => void save(e)} noValidate>
      <div className="field-row">
        <label className="field grow">
          <span>Nombre</span>
          <input value={name} maxLength={60} onChange={(e) => setName(e.target.value)} placeholder="Internet" />
        </label>
        <label className="field grow">
          <span>Categoría</span>
          <select value={categoryId} onChange={(e) => setCategoryId(e.target.value)}>
            {(["NECESIDAD", "DESEO", "AHORRO"] as const).map((g) => (
              <optgroup key={g} label={GROUP_LABEL[g]}>
                {expenseCategories.filter((c) => c.group === g).map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
              </optgroup>
            ))}
          </select>
        </label>
      </div>
      <div className="field-row">
        <label className="field">
          <span>Día de pago (opcional)</span>
          <input inputMode="numeric" value={day} maxLength={2} onChange={(e) => setDay(e.target.value)} placeholder="15" />
        </label>
        <label className="field grow">
          <span>Monto aproximado (opcional)</span>
          <input inputMode="decimal" value={amount} maxLength={18} onChange={(e) => setAmount(amountChange(e))} placeholder="18.500" />
        </label>
        <div className="field">
          <span>Moneda</span>
          <div className="segmented compact" role="group" aria-label="Moneda">
            <button type="button" aria-pressed={currency === "CRC"} onClick={() => { setCurrency("CRC"); setPlaceId(""); }}>₡</button>
            <button type="button" aria-pressed={currency === "USD"} onClick={() => { setCurrency("USD"); setPlaceId(""); }}>$</button>
          </div>
        </div>
      </div>
      {day.trim() && (
        <div className="field">
          <span>Avisarme</span>
          <div className="segmented" role="group" aria-label="Cuándo avisar">
            {[0, 1, 3].map((d) => (
              <button key={d} type="button" aria-pressed={before === d} onClick={() => setBefore(d)}>{BEFORE_LABEL[d]}</button>
            ))}
          </div>
        </div>
      )}
      {accounts.length > 0 && (
        <label className="field">
          <span>Se paga desde (opcional)</span>
          <select value={placeId} onChange={(e) => setPlaceId(e.target.value)}>
            <option value="">Elegir al pagar</option>
            {accounts.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
          </select>
        </label>
      )}
      {error && <p className="form-error" role="alert">{error}</p>}
      <div className="actions">
        {item && !confirmDelete && (
          <>
            <button type="button" className="button ghost" onClick={() => void save(null, !item.active)} disabled={busy}>
              {item.active ? "Pausar" : "Reanudar"}
            </button>
            <button type="button" className="button ghost danger" onClick={() => setConfirmDelete(true)}>Borrar</button>
          </>
        )}
        {item && confirmDelete && (
          <span className="confirm-inline">
            ¿Borrarlo?
            <button type="button" className="button ghost danger" onClick={remove} disabled={busy}>Sí, borrar</button>
            <button type="button" className="button ghost" onClick={() => setConfirmDelete(false)}>No</button>
          </span>
        )}
        {!item && <button type="button" className="button ghost" onClick={onDone}>Cancelar</button>}
        <button type="submit" className="button primary" disabled={busy}>{busy ? "Guardando…" : "Guardar"}</button>
      </div>
    </form>
  );
}
