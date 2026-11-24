import { useEffect, useRef, useState, type CSSProperties, type FormEvent } from "react";
import { api, messageOf } from "../api";
import { Icon } from "../components/Icon";
import { GoalSimulator } from "../components/GoalSimulator";
import { Shell } from "../components/Shell";
import { amountChange, amountToInput, money, parseAmount, pct } from "../format";
import { gsap, useGSAP, whenMotionAllowed } from "../motion";
import { useApp } from "../state";
import type { Currency, Goal, GoalStatus } from "../types";

const monthYear = new Intl.DateTimeFormat("es-CR", { month: "long", year: "numeric" });
const when = (iso: string) => {
  const [y, m] = iso.split("-").map(Number);
  return monthYear.format(new Date(y ?? 2000, (m ?? 1) - 1, 1));
};

/** Metas de ahorro: cuánto falta para lo importante y a qué ritmo se va. */
export function Goals() {
  const { space, version, touch } = useApp();
  const [goals, setGoals] = useState<Goal[] | null>(null);
  const [open, setOpen] = useState<string | "new" | null>(null);
  const [error, setError] = useState<string | null>(null);
  const list = useRef<HTMLDivElement>(null);
  const readOnly = Boolean(space?.archived);

  useEffect(() => {
    if (!space) return;
    let cancelled = false;
    api.get<Goal[]>(`/api/spaces/${space.id}/goals`)
      .then((g) => !cancelled && setGoals(g))
      .catch((e) => !cancelled && setError(messageOf(e, "No se pudieron cargar las metas.")));
    return () => {
      cancelled = true;
    };
  }, [space, version]);

  useGSAP(() => whenMotionAllowed(() => {
    gsap.from(".goal .rule-fill", { scaleX: 0, duration: 0.7, ease: "expo.out", stagger: 0.05, clearProps: "transform" });
  }), { scope: list, dependencies: [goals?.length] });

  const current = (goals ?? []).filter((g) => g.status === "ACTIVA" || g.status === "PAUSADA");
  const done = (goals ?? []).filter((g) => g.status === "CUMPLIDA");
  const archived = (goals ?? []).filter((g) => g.status === "ARCHIVADA");

  const move = async (goal: Goal, delta: number) => {
    const ids = (goals ?? []).map((g) => g.id);
    const i = ids.indexOf(goal.id);
    const j = i + delta;
    if (j < 0 || j >= current.length) return;
    [ids[i], ids[j]] = [ids[j]!, ids[i]!];
    try {
      setGoals(await api.put<Goal[]>(`/api/spaces/${space!.id}/goals/order`, { ids }));
    } catch (e) {
      setError(messageOf(e, "No se pudo cambiar el orden."));
    }
  };

  return (
    <Shell>
      <div ref={list}>
        <h1 className="page-title">Metas</h1>
        <p className="hint">El avance sale del saldo de las cuentas que ligas a cada meta, como tu CDP o un sobre.</p>
        {error && <p className="form-error" role="alert">{error}</p>}

        {goals?.length === 0 && open !== "new" && (
          <section className="empty">
            <p className="empty-title">Todavía no hay metas.</p>
            <p>Empieza por la que más importa ahora. El fondo de emergencia es una buena primera.</p>
          </section>
        )}

        <ul className="goals">
          {current.map((g, i) => (
            <GoalRow key={g.id} goal={g} goals={goals ?? []} open={open === g.id} readOnly={readOnly}
                     onToggle={() => setOpen(open === g.id ? null : g.id)}
                     onUp={i > 0 ? () => void move(g, -1) : undefined}
                     onDown={i < current.length - 1 ? () => void move(g, 1) : undefined}
                     onDone={() => { setOpen(null); touch(); }} />
          ))}
        </ul>

        {!readOnly && (open === "new"
          ? <GoalForm goals={goals ?? []} onDone={(saved) => { setOpen(null); if (saved) touch(); }} />
          : <button type="button" className="button secondary add-place" onClick={() => setOpen("new")}>
              <Icon name="plus" size={18} /> Nueva meta
            </button>)}

        {done.length > 0 && (
          <section className="block" aria-labelledby="done-title">
            <h2 id="done-title" className="section-title">Cumplidas</h2>
            <ul className="goals">
              {done.map((g) => (
                <GoalRow key={g.id} goal={g} goals={goals ?? []} open={open === g.id} readOnly={readOnly}
                         onToggle={() => setOpen(open === g.id ? null : g.id)} onDone={() => { setOpen(null); touch(); }} />
              ))}
            </ul>
          </section>
        )}

        {archived.length > 0 && (
          <details className="more archived-places">
            <summary>Archivadas ({archived.length})</summary>
            <ul className="goals">
              {archived.map((g) => (
                <GoalRow key={g.id} goal={g} goals={goals ?? []} open={open === g.id} readOnly={readOnly}
                         onToggle={() => setOpen(open === g.id ? null : g.id)} onDone={() => { setOpen(null); touch(); }} />
              ))}
            </ul>
          </details>
        )}
      </div>
    </Shell>
  );
}

/** La frase del plan: lo que hay que hacer, en palabras. */
function planText(g: Goal): string {
  if (g.status === "CUMPLIDA") return "¡Lo lograron!";
  if (g.status === "PAUSADA") return "En pausa.";
  if (g.target <= 0) return g.kind === "EMERGENCIA"
    ? "Cuando tengas un mes completo de gastos anotados, calculo cuánto necesitas."
    : "Sin objetivo todavía.";
  if (g.placeIds.length === 0) return "Liga una cuenta (tu CDP, un sobre) para ver el avance.";
  if (g.monthlyNeeded != null) {
    const behind = g.monthlyPace > 0 && g.monthlyPace < g.monthlyNeeded;
    return `Aparta ${money(g.monthlyNeeded, g.currency)} al mes para llegar en ${when(g.targetDate!)}.`
      + (behind ? ` Hoy vas a ${money(g.monthlyPace, g.currency)} al mes.` : "");
  }
  if (g.eta) return `A este ritmo llegas en ${when(g.eta)}.`;
  return "Todavía no le ha entrado dinero en los últimos meses.";
}

function GoalRow({ goal: g, goals, open, readOnly, onToggle, onUp, onDown, onDone }: {
  goal: Goal; goals: Goal[]; open: boolean; readOnly: boolean; onToggle: () => void;
  onUp?: () => void; onDown?: () => void; onDone: () => void;
}) {
  const ratio = g.target > 0 ? Math.min(g.saved / g.target, 1) : 0;
  return (
    <li className={`goal${g.status === "CUMPLIDA" ? " reached" : ""}${g.status === "PAUSADA" ? " paused" : ""}`}>
      <button type="button" className="goal-head row-button" onClick={onToggle} aria-expanded={open} disabled={readOnly}>
        <span className="goal-name">
          {g.name}
          {g.kind === "EMERGENCIA" && <span className="tag">emergencia</span>}
        </span>
        <span className="goal-figures">
          <span className="figure">{money(g.saved, g.currency)}</span>
          {g.target > 0 && <span className="muted"> de <span className="figure">{money(g.target, g.currency)}</span></span>}
          {g.target > 0 && <span className="goal-pct"> · {pct(g.percent)}</span>}
        </span>
      </button>
      <div className="row-rule">
        <span className={`rule-fill ${g.status === "CUMPLIDA" ? "ok" : "goal-tone"}`} style={{ "--fill": ratio } as CSSProperties}
              role="meter" aria-label={`${g.name}: ${pct(g.percent)}`} aria-valuemin={0} aria-valuemax={100} aria-valuenow={g.percent} />
      </div>
      <p className="goal-plan">{planText(g)}</p>
      {g.target > 0 && g.status !== "CUMPLIDA" && g.status !== "ARCHIVADA" && <GoalSimulator goal={g} />}
      {g.kind === "EMERGENCIA" && g.monthlyExpenses > 0 && (
        <p className="muted small">{g.emergencyMonths} meses de tus gastos ({money(g.monthlyExpenses, g.currency)} al mes en promedio).</p>
      )}
      {g.parts.length > 0 && (
        <ul className="goal-parts">
          {g.parts.map((p) => (
            <li key={p.name}><span>{p.name}</span><span className="figure">{money(p.amount, g.currency)}</span></li>
          ))}
        </ul>
      )}
      {open && <GoalForm goal={g} goals={goals} onUp={onUp} onDown={onDown} onDone={(saved) => saved ? onDone() : onToggle()} />}
    </li>
  );
}

function GoalForm({ goal, goals = [], onUp, onDown, onDone }: {
  goal?: Goal; goals?: Goal[]; onUp?: () => void; onDown?: () => void; onDone: (saved: boolean) => void;
}) {
  const { space, places } = useApp();
  const [kind, setKind] = useState<Goal["kind"]>(goal?.kind ?? "NORMAL");
  const [name, setName] = useState(goal?.name ?? "");
  const [target, setTarget] = useState(goal?.targetAmount != null ? amountToInput(goal.targetAmount) : "");
  const [months, setMonths] = useState(goal?.emergencyMonths ?? 3);
  const [currency, setCurrency] = useState<Currency>(goal?.currency ?? "CRC");
  const [date, setDate] = useState(goal?.targetDate ?? "");
  const [placeIds, setPlaceIds] = useState<string[]>(goal?.placeIds ?? []);
  const [parts, setParts] = useState(goal?.parts.map((p) => ({ name: p.name, amount: amountToInput(p.amount) })) ?? []);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);

  // Un lugar cuenta para una sola meta: los que ya usa otra no se ofrecen.
  const taken = new Set(goals.filter((g) => g.id !== goal?.id).flatMap((g) => g.placeIds));
  const options = places.filter((p) => !p.archived && p.currency === currency && (!taken.has(p.id) || placeIds.includes(p.id)));
  const partsTotal = parts.reduce((sum, p) => sum + Number(parseAmount(p.amount) ?? 0), 0);

  const save = async (event: FormEvent) => {
    event.preventDefault();
    if (!name.trim()) return setError("Ponle nombre a la meta, por ejemplo «Comprar un carro».");
    const amount = kind === "NORMAL" ? parseAmount(target) : null;
    if (kind === "NORMAL" && !amount) return setError("Escribe cuánto quieres juntar.");
    const cleanParts = parts.filter((p) => p.name.trim() || p.amount.trim());
    if (cleanParts.some((p) => !p.name.trim() || !parseAmount(p.amount))) return setError("Cada parte necesita nombre y monto.");
    setBusy(true);
    setError(null);
    const body = {
      name: name.trim(), kind, currency, targetDate: date || null, placeIds,
      targetAmount: amount, emergencyMonths: kind === "EMERGENCIA" ? months : null,
      parts: cleanParts.map((p) => ({ name: p.name.trim(), amount: parseAmount(p.amount) })),
    };
    try {
      if (goal) await api.put(`/api/spaces/${space!.id}/goals/${goal.id}`, body);
      else await api.post(`/api/spaces/${space!.id}/goals`, body);
      onDone(true);
    } catch (e) {
      setError(messageOf(e, "No se pudo guardar."));
      setBusy(false);
    }
  };

  const setStatus = async (status: GoalStatus) => {
    setBusy(true);
    try {
      await api.put(`/api/spaces/${space!.id}/goals/${goal!.id}/status`, { status });
      onDone(true);
    } catch (e) {
      setError(messageOf(e, "No se pudo cambiar."));
      setBusy(false);
    }
  };

  const remove = async () => {
    setBusy(true);
    try {
      await api.del(`/api/spaces/${space!.id}/goals/${goal!.id}`);
      onDone(true);
    } catch (e) {
      setError(messageOf(e, "No se pudo borrar."));
      setBusy(false);
    }
  };

  return (
    <form className="place-form inline" onSubmit={save} noValidate>
      {!goal && (
        <div className="segmented" role="group" aria-label="Tipo de meta">
          <button type="button" aria-pressed={kind === "NORMAL"} onClick={() => setKind("NORMAL")}>Meta</button>
          <button type="button" aria-pressed={kind === "EMERGENCIA"}
                  onClick={() => { setKind("EMERGENCIA"); if (!name.trim()) setName("Fondo de emergencia"); }}>
            Fondo de emergencia
          </button>
        </div>
      )}
      <div className="field-row">
        <label className="field grow">
          <span>Nombre</span>
          <input value={name} maxLength={60} onChange={(e) => setName(e.target.value)} placeholder="Comprar un carro" />
        </label>
        <div className="field">
          <span>Moneda</span>
          <div className="segmented compact" role="group" aria-label="Moneda">
            <button type="button" aria-pressed={currency === "CRC"} onClick={() => { setCurrency("CRC"); setPlaceIds([]); }}>₡</button>
            <button type="button" aria-pressed={currency === "USD"} onClick={() => { setCurrency("USD"); setPlaceIds([]); }}>$</button>
          </div>
        </div>
      </div>
      <div className="field-row">
        {kind === "NORMAL" ? (
          <label className="field grow">
            <span>¿Cuánto quieres juntar?</span>
            <input inputMode="decimal" value={target} maxLength={18} onChange={(e) => setTarget(amountChange(e))} placeholder="5.000.000" />
          </label>
        ) : (
          <div className="field grow">
            <span>¿Cuántos meses de gastos?</span>
            <div className="segmented" role="group" aria-label="Meses de gastos">
              {[3, 4, 6].map((m) => <button key={m} type="button" aria-pressed={months === m} onClick={() => setMonths(m)}>{m} meses</button>)}
            </div>
          </div>
        )}
        <label className="field">
          <span>Para cuándo (opcional)</span>
          <input type="date" value={date} onChange={(e) => setDate(e.target.value)} />
        </label>
      </div>

      <fieldset className="chips">
        <legend>Cuentas donde junta el dinero</legend>
        {options.length === 0 && <p className="muted small">Agrega primero una cuenta en Cuentas (tu CDP o un sobre).</p>}
        <div className="chip-row wrap">
          {options.map((p) => (
            <button key={p.id} type="button" className="chip" aria-pressed={placeIds.includes(p.id)}
                    onClick={() => setPlaceIds(placeIds.includes(p.id) ? placeIds.filter((x) => x !== p.id) : [...placeIds, p.id])}>
              {p.name}
            </button>
          ))}
        </div>
      </fieldset>

      {kind === "NORMAL" && (
        <fieldset className="goal-parts-form">
          <legend>Partes (opcional)</legend>
          {parts.map((p, i) => (
            <div key={i} className="field-row">
              <input aria-label="Nombre de la parte" value={p.name} maxLength={60} placeholder="Prima"
                     onChange={(e) => setParts(parts.map((x, j) => (j === i ? { ...x, name: e.target.value } : x)))} />
              <input aria-label="Monto de la parte" inputMode="decimal" value={p.amount} maxLength={18} placeholder="0"
                     onChange={(e) => setParts(parts.map((x, j) => (j === i ? { ...x, amount: amountChange(e) } : x)))} />
              <button type="button" className="icon-button" aria-label="Quitar parte" onClick={() => setParts(parts.filter((_, j) => j !== i))}>
                <Icon name="close" size={18} />
              </button>
            </div>
          ))}
          {parts.length < 12 && (
            <button type="button" className="button ghost small" onClick={() => setParts([...parts, { name: "", amount: "" }])}>
              <Icon name="plus" size={16} /> Agregar parte
            </button>
          )}
          {parts.length > 0 && partsTotal > 0 && (
            <p className="muted small">Las partes suman {money(partsTotal, currency)}.</p>
          )}
        </fieldset>
      )}

      {error && <p className="form-error" role="alert">{error}</p>}
      <div className="actions">
        {goal && (onUp || onDown) && (
          <span className="reorder">
            <button type="button" className="icon-button" aria-label="Subir prioridad" onClick={onUp} disabled={!onUp}>
              <Icon name="up" size={18} />
            </button>
            <button type="button" className="icon-button" aria-label="Bajar prioridad" onClick={onDown} disabled={!onDown}>
              <Icon name="down" size={18} />
            </button>
          </span>
        )}
        {goal && goal.status === "ACTIVA" && <button type="button" className="button ghost" onClick={() => void setStatus("PAUSADA")} disabled={busy}>Pausar</button>}
        {goal && goal.status === "PAUSADA" && <button type="button" className="button ghost" onClick={() => void setStatus("ACTIVA")} disabled={busy}>Reanudar</button>}
        {goal && goal.status === "CUMPLIDA" && <button type="button" className="button ghost" onClick={() => void setStatus("ARCHIVADA")} disabled={busy}>Archivar</button>}
        {goal && goal.status === "ARCHIVADA" && <button type="button" className="button ghost" onClick={() => void setStatus("ACTIVA")} disabled={busy}>Volver a usar</button>}
        {goal && !confirmDelete && <button type="button" className="button ghost danger" onClick={() => setConfirmDelete(true)}>Borrar</button>}
        {goal && confirmDelete && (
          <span className="confirm-inline">
            ¿Borrarla?
            <button type="button" className="button ghost danger" onClick={remove} disabled={busy}>Sí, borrar</button>
            <button type="button" className="button ghost" onClick={() => setConfirmDelete(false)}>No</button>
          </span>
        )}
        {!goal && <button type="button" className="button ghost" onClick={() => onDone(false)}>Cancelar</button>}
        <button type="submit" className="button primary" disabled={busy}>{busy ? "Guardando…" : "Guardar"}</button>
      </div>
    </form>
  );
}
