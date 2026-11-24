import { useRef, useState, type CSSProperties, type FormEvent } from "react";
import { api, messageOf } from "../api";
import { EntrySheet } from "../components/EntrySheet";
import { DEBT_CATEGORY, debtCategory, installmentLeft, namesText, payPreset, pendingText, useDebts } from "../debts";
import { Icon } from "../components/Icon";
import { Shell } from "../components/Shell";
import { amountChange, amountToInput, dayMonthLabel, initials, money, parseAmount, todayIso } from "../format";
import { gsap, useGSAP, whenMotionAllowed } from "../motion";
import { navigate } from "../router";
import { useApp } from "../state";
import type { Currency, Debt } from "../types";

function byCreditor(debts: Debt[]): [string, Debt[]][] {
  const groups = new Map<string, Debt[]>();
  for (const d of debts) {
    const key = d.creditor.toLocaleLowerCase("es");
    groups.set(key, [...(groups.get(key) ?? []), d]);
  }
  return [...groups.values()].map((list) => [list[0]!.creditor, list]);
}

function dueText(d: Debt): { text: string; late: boolean } | null {
  if (!d.dueDate) return null;
  const today = todayIso();
  if (d.dueDate === today) return { text: "acordaste pagarla hoy", late: true };
  if (d.dueDate < today) return { text: `acordaste pagarla el ${dayMonthLabel(d.dueDate)}`, late: true };
  return { text: `acordaste pagarla el ${dayMonthLabel(d.dueDate)}`, late: false };
}

/**
 * Deudas: lo que se le debe a alguien, agrupado por persona como un cuaderno de fiado. Cada
 * abono es un gasto ligado a la deuda; cuando no falta nada, la deuda pasa a «Saldadas».
 */
export function Debts() {
  const { space, categories } = useApp();
  const { debts, error } = useDebts();
  const [open, setOpen] = useState<string | "new" | null>(null);
  const [paying, setPaying] = useState<Debt | null>(null);
  const list = useRef<HTMLDivElement>(null);
  const readOnly = Boolean(space?.archived);
  const shared = space?.kind === "FAMILIA";
  const memberName = (id: string) => space?.members.find((m) => m.id === id)?.displayName ?? "Alguien";

  const pending = (debts ?? []).filter((d) => d.remaining > 0);
  const settled = (debts ?? []).filter((d) => d.remaining <= 0)
    .sort((a, b) => (b.settledOn ?? "").localeCompare(a.settledOn ?? ""));
  const groups = byCreditor(pending);
  const creditors = [...new Set((debts ?? []).map((d) => d.creditor))];

  useGSAP(() => whenMotionAllowed(() => {
    // clearProps: al terminar manda otra vez el CSS (--fill); si no, el transform en línea
    // dejaría la línea congelada en el valor del primer render cuando llega un abono.
    gsap.from(".debt .rule-fill", { scaleX: 0, duration: 0.6, ease: "expo.out", stagger: 0.04, clearProps: "transform" });
  }), { scope: list, dependencies: [debts?.length] });

  const category = debtCategory(categories);

  return (
    <Shell>
      <div ref={list}>
        <h1 className="page-title">Deudas</h1>
        {debts && pending.length > 0 && (
          <p className="overview-line debt-total">
            Te falta pagar <strong className="figure">{pendingText(pending)}</strong>
            {" "}a {namesText(groups.map(([name]) => name))}.
          </p>
        )}
        {error && <p className="form-error" role="alert">{error}</p>}

        {debts === null && !error && (
          <div className="skeleton" aria-hidden="true">
            <div className="sk-line wide" /><div className="sk-row" /><div className="sk-row" />
          </div>
        )}

        {debts && pending.length === 0 && open !== "new" && (
          <section className="empty">
            <p className="empty-title">{settled.length > 0 ? "No le debes nada a nadie." : "Sin deudas."}</p>
            <p>¿Alguien te prestó o pagó algo por ti? Anótalo aquí y mira cómo baja con cada abono.</p>
          </section>
        )}

        {groups.map(([creditor, items]) => (
          <section key={creditor} className="group debt-group" aria-labelledby={`debt-${items[0]!.id}`}>
            <h2 id={`debt-${items[0]!.id}`} className="group-title debt-creditor">
              <span>{creditor}</span>
              <span className="figure">{pendingText(items)}</span>
            </h2>
            <ul className="rows">
              {items.map((d) => (
                <DebtRow key={d.id} debt={d} open={open === d.id} readOnly={readOnly} creditors={creditors}
                         who={shared ? memberName(d.createdBy) : null}
                         onToggle={() => setOpen(open === d.id ? null : d.id)}
                         onPay={() => setPaying(d)} onDone={() => setOpen(null)} />
              ))}
            </ul>
          </section>
        ))}

        {!readOnly && debts && (open === "new"
          ? <DebtForm creditors={creditors} onDone={() => setOpen(null)} />
          : <button type="button" className="button secondary add-place" onClick={() => setOpen("new")}>
              <Icon name="plus" size={18} /> Anotar una deuda
            </button>)}

        {settled.length > 0 && (
          <details className="more debt-settled">
            <summary>Saldadas ({settled.length})</summary>
            <ul className="rows">
              {settled.map((d) => (
                <DebtRow key={d.id} debt={d} open={open === d.id} readOnly={readOnly} creditors={creditors}
                         who={shared ? memberName(d.createdBy) : null}
                         onToggle={() => setOpen(open === d.id ? null : d.id)} onDone={() => setOpen(null)} />
              ))}
            </ul>
          </details>
        )}

        <p className="hint debt-hint">
          Cada abono se anota como gasto en «{category?.name ?? DEBT_CATEGORY}» y rebaja lo que falta. Si borras un abono, la deuda vuelve.
          ¿Ya lo anotaste como gasto? Ábrelo en Movimientos y elige la deuda en «Es un abono a».
        </p>
      </div>

      {paying && (
        <EntrySheet preset={payPreset(paying, categories, installmentLeft(paying) || paying.remaining)}
                    onClose={() => setPaying(null)} />
      )}
    </Shell>
  );
}

/** En «Mes»: cuánto falta y a quién, a un toque de la pantalla. Nada si no hay deudas. */
export function DebtLine() {
  const { debts } = useDebts();
  const pending = (debts ?? []).filter((d) => d.remaining > 0);
  if (pending.length === 0) return null;
  const names = byCreditor(pending).map(([name]) => name);
  return (
    <section className="group debt-line" aria-labelledby="debt-line-title">
      <h2 id="debt-line-title" className="group-title">Deudas</h2>
      <ul className="rows">
        <li className="row">
          <button type="button" className="row-main row-button" onClick={() => navigate("/deudas")}>
            <span className="due-text">
              <span className="row-name">{namesText(names)}</span>
              <span className="due-when">{pending.length === 1 ? "1 deuda" : `${pending.length} deudas`} por pagar</span>
            </span>
            <span className="row-amounts"><span className="debt-left">falta </span><span className="figure">{pendingText(pending)}</span></span>
          </button>
        </li>
      </ul>
    </section>
  );
}

function DebtRow({ debt: d, open, readOnly, creditors, who, onToggle, onPay, onDone }: {
  debt: Debt; open: boolean; readOnly: boolean; creditors: string[]; who: string | null;
  onToggle: () => void; onPay?: () => void; onDone: () => void;
}) {
  const settled = d.remaining <= 0;
  const covered = d.amount - d.remaining;
  const ratio = d.amount > 0 ? Math.min(covered / d.amount, 1) : 0;
  const due = settled ? null : dueText(d);
  const forgivenOnly = settled && d.forgiven > 0 && d.settledOn === d.forgivenOn;
  // Lo que explica el saldo, en el orden en que pasó: antes de anotarla, abonos, cuota.
  const notes: string[] = [];
  if (!settled) {
    if (d.paidBefore > 0) notes.push(`${money(d.paidBefore, d.currency, true)} pagados antes`);
    notes.push(d.payments === 0
      ? (d.paidBefore > 0 ? "sin abonos aquí" : `desde el ${dayMonthLabel(d.borrowedOn)}, sin abonos`)
      : `${d.payments === 1 ? "1 abono" : `${d.payments} abonos`}, el último el ${dayMonthLabel(d.lastPaidOn!)}`);
    if (d.installment != null) {
      notes.push(`cuota ${money(d.installment, d.currency, true)}${d.installmentDay ? ` los días ${d.installmentDay}` : " al mes"}`);
    }
  }
  const sentence = (parts: string[]) => {
    const text = parts.join(" · ");
    return text.charAt(0).toUpperCase() + text.slice(1);
  };
  return (
    <li className={`row debt${settled ? " settled" : ""}`}>
      <button type="button" className="row-main row-button" onClick={onToggle} aria-expanded={open} disabled={readOnly}>
        <span className="due-text">
          <span className="row-name">
            {settled && d.creditor + " · "}{d.concept}
            {who && (
              <span className="who" title={`La anotó ${who}`}>
                <span aria-hidden="true">{initials(who)}</span>
                <span className="visually-hidden">La anotó {who}</span>
              </span>
            )}
          </span>
          <span className={`due-when${due?.late ? " late" : ""}`}>
            {settled
              ? forgivenOnly
                ? `Te perdonó ${money(d.forgiven, d.currency, true)} el ${dayMonthLabel(d.forgivenOn!)}`
                : `Saldada el ${dayMonthLabel(d.settledOn ?? d.lastPaidOn ?? d.borrowedOn)}`
                  + (d.forgiven > 0 ? ` · perdonó ${money(d.forgiven, d.currency, true)}` : "")
              : sentence(notes)}
            {due && <> · {due.late && <Icon name="alert" size={14} />} {due.text}</>}
          </span>
        </span>
        <span className="row-amounts">
          {settled
            ? <span className="figure">{money(d.amount, d.currency, true)}</span>
            : <>
                <span><span className="debt-left">falta </span><span className="figure">{money(d.remaining, d.currency, true)}</span></span>
                {covered > 0 && <span className="planned small">de <span className="figure">{money(d.amount, d.currency, true)}</span></span>}
              </>}
        </span>
      </button>
      {!settled && (
        <div className="row-rule">
          <span className="rule-fill" style={{ "--fill": ratio } as CSSProperties} role="meter"
                aria-label={`${d.concept}: cubierto ${money(covered, d.currency, true)} de ${money(d.amount, d.currency, true)}`}
                aria-valuemin={0} aria-valuemax={d.amount} aria-valuenow={covered} />
        </div>
      )}
      {!settled && !readOnly && onPay && !open && (
        <div className="debt-actions">
          <button type="button" className="button secondary small" onClick={onPay}>Abonar</button>
        </div>
      )}
      {open && <DebtForm debt={d} creditors={creditors} onDone={onDone} />}
    </li>
  );
}

function DebtForm({ debt, creditors, onDone }: { debt?: Debt; creditors: string[]; onDone: () => void }) {
  const { space, touch } = useApp();
  const [creditor, setCreditor] = useState(debt?.creditor ?? "");
  const [concept, setConcept] = useState(debt?.concept ?? "");
  const [amount, setAmount] = useState(debt ? amountToInput(debt.amount) : "");
  const [currency, setCurrency] = useState<Currency>(debt?.currency ?? "CRC");
  const [borrowedOn, setBorrowedOn] = useState(debt?.borrowedOn ?? todayIso());
  const [dueDate, setDueDate] = useState(debt?.dueDate ?? "");
  const [paidBefore, setPaidBefore] = useState(debt?.paidBefore ? amountToInput(debt.paidBefore) : "");
  const [installment, setInstallment] = useState(debt?.installment != null ? amountToInput(debt.installment) : "");
  const [installmentDay, setInstallmentDay] = useState(debt?.installmentDay ? String(debt.installmentDay) : "");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [confirmForgive, setConfirmForgive] = useState(false);
  const locked = Boolean(debt && debt.paid > 0);
  const extrasOpen = Boolean(debt && (debt.paidBefore > 0 || debt.installment != null));

  const save = async (event: FormEvent) => {
    event.preventDefault();
    if (!creditor.trim()) return setError("Escribe a quién le debes, por ejemplo Mamá.");
    if (!concept.trim()) return setError("Escribe por qué, por ejemplo Citas médicas.");
    const parsed = parseAmount(amount);
    if (!parsed || Number(parsed) <= 0) return setError("Escribe cuánto debes, por ejemplo 60.000.");
    const before = paidBefore.trim() ? parseAmount(paidBefore) : "0";
    if (before === null) return setError("Revisa lo que ya le pagaste.");
    if (Number(before) >= Number(parsed)) {
      return setError("Lo que ya le pagaste tiene que ser menor a la deuda. Si ya está pagada, no hace falta anotarla.");
    }
    if (debt && Number(parsed) < debt.paid + debt.forgiven + Number(before)) {
      return setError(`Ya está cubierto ${money(debt.paid + debt.forgiven + Number(before), debt.currency, true)}: el monto no puede ser menor.`);
    }
    const quota = installment.trim() ? parseAmount(installment) : null;
    if (installment.trim() && (!quota || Number(quota) <= 0)) return setError("Revisa la cuota, por ejemplo 10.000.");
    if (quota && Number(quota) > Number(parsed)) return setError("La cuota no puede ser mayor a la deuda.");
    const day = installmentDay.trim() ? Number(installmentDay) : null;
    if (day !== null && !(Number.isInteger(day) && day >= 1 && day <= 31)) return setError("El día de la cuota va de 1 a 31.");
    if (day !== null && !quota) return setError("Escribe de cuánto es la cuota, o borra el día.");
    if (!borrowedOn) return setError("Elige desde cuándo la debes.");
    if (dueDate && dueDate < borrowedOn) return setError("La fecha acordada va después de la fecha de la deuda.");
    setBusy(true);
    setError(null);
    const body = { creditor: creditor.trim(), concept: concept.trim(), amount: parsed, currency, borrowedOn,
                   dueDate: dueDate || null, paidBefore: before, installment: quota, installmentDay: day };
    try {
      if (debt) await api.put(`/api/spaces/${space!.id}/debts/${debt.id}`, body);
      else await api.post(`/api/spaces/${space!.id}/debts`, body);
      touch();
      onDone();
    } catch (e) {
      setError(messageOf(e, "No se pudo guardar."));
      setBusy(false);
    }
  };

  const forgive = async (undo: boolean) => {
    setBusy(true);
    try {
      const url = `/api/spaces/${space!.id}/debts/${debt!.id}/forgive`;
      if (undo) await api.del(url);
      else await api.post(url, {});
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
      await api.del(`/api/spaces/${space!.id}/debts/${debt!.id}`);
      touch();
      onDone();
    } catch (e) {
      setError(messageOf(e, "No se pudo borrar."));
      setBusy(false);
    }
  };

  return (
    <form className="place-form inline" onSubmit={(e) => void save(e)} noValidate aria-label={debt ? "Editar deuda" : "Nueva deuda"}>
      <div className="field-row">
        <label className="field grow">
          <span>A quién</span>
          <input value={creditor} maxLength={40} onChange={(e) => setCreditor(e.target.value)} placeholder="Mamá"
                 list="debt-creditors" autoComplete="off" autoFocus={!debt} />
          <datalist id="debt-creditors">
            {creditors.map((c) => <option key={c} value={c} />)}
          </datalist>
        </label>
        <label className="field grow">
          <span>Por qué</span>
          <input value={concept} maxLength={60} onChange={(e) => setConcept(e.target.value)} placeholder="Citas médicas"
                 autoComplete="off" />
        </label>
      </div>
      <div className="field-row">
        <label className="field grow">
          <span>Cuánto</span>
          <input inputMode="decimal" value={amount} maxLength={18} onChange={(e) => setAmount(amountChange(e))}
                 placeholder="60.000" />
        </label>
        <div className="field">
          <span>Moneda</span>
          <div className="segmented compact" role="group" aria-label="Moneda">
            <button type="button" aria-pressed={currency === "CRC"} disabled={locked} onClick={() => setCurrency("CRC")}>₡</button>
            <button type="button" aria-pressed={currency === "USD"} disabled={locked} onClick={() => setCurrency("USD")}>$</button>
          </div>
        </div>
      </div>
      <div className="field-row">
        <label className="field grow">
          <span>Desde cuándo</span>
          <input type="date" value={borrowedOn} max={todayIso()} onChange={(e) => setBorrowedOn(e.target.value)} />
        </label>
        <label className="field grow">
          <span>Acordaste pagar para (opcional)</span>
          <input type="date" value={dueDate} min={borrowedOn} onChange={(e) => setDueDate(e.target.value)} />
        </label>
      </div>
      {dueDate && <p className="hint">Te aviso el día antes y ese día, si todavía falta algo.</p>}
      <details className="more debt-extras" open={extrasOpen}>
        <summary>¿Ya le pagaste algo o acordaron cuotas?</summary>
        <label className="field">
          <span>Ya le pagaste, antes de anotarla</span>
          <input inputMode="decimal" value={paidBefore} maxLength={18} onChange={(e) => setPaidBefore(amountChange(e))}
                 placeholder="20.000" aria-describedby="paid-before-hint" />
        </label>
        <p className="hint" id="paid-before-hint">Rebaja lo que falta, pero no cuenta como gasto de este mes: esa plata salió antes.</p>
        <div className="field-row">
          <label className="field grow">
            <span>Cuota acordada</span>
            <input inputMode="decimal" value={installment} maxLength={18} onChange={(e) => setInstallment(amountChange(e))}
                   placeholder="10.000" />
          </label>
          <label className="field">
            <span>Día del mes</span>
            <input inputMode="numeric" value={installmentDay} maxLength={2} onChange={(e) => setInstallmentDay(e.target.value)}
                   placeholder="15" />
          </label>
        </div>
        <p className="hint">La cuota aparece en «Por pagar» del mes; con día, te aviso el día antes y ese día.</p>
      </details>
      {locked && <p className="hint">Ya tiene abonos: la moneda no cambia y el monto no baja de lo cubierto.</p>}
      {error && <p className="form-error" role="alert">{error}</p>}
      {debt && (debt.remaining > 0 || debt.forgiven > 0) && (
        <div className="debt-forgive">
          {debt.forgiven > 0 ? (
            <button type="button" className="button ghost" onClick={() => void forgive(true)} disabled={busy}>
              Quitar el perdón de {money(debt.forgiven, debt.currency, true)}
            </button>
          ) : confirmForgive ? (
            <span className="confirm-inline">
              ¿Te perdonó los {money(debt.remaining, debt.currency, true)} que faltan?
              <button type="button" className="button ghost" onClick={() => void forgive(false)} disabled={busy}>Sí</button>
              <button type="button" className="button ghost" onClick={() => setConfirmForgive(false)}>No</button>
            </span>
          ) : (
            <button type="button" className="button ghost" onClick={() => setConfirmForgive(true)}>Me lo perdonó</button>
          )}
        </div>
      )}
      <div className="actions">
        {debt && !confirmDelete && (
          <button type="button" className="button ghost danger" onClick={() => setConfirmDelete(true)}>Borrar</button>
        )}
        {debt && confirmDelete && (
          <span className="confirm-inline">
            {debt.paid > 0 ? "¿Borrarla? Los abonos quedan como gastos." : "¿Borrarla?"}
            <button type="button" className="button ghost danger" onClick={() => void remove()} disabled={busy}>Sí, borrar</button>
            <button type="button" className="button ghost" onClick={() => setConfirmDelete(false)}>No</button>
          </span>
        )}
        <button type="button" className="button ghost" onClick={onDone}>Cancelar</button>
        <button type="submit" className="button primary" disabled={busy}>{busy ? "Guardando…" : "Guardar"}</button>
      </div>
    </form>
  );
}
