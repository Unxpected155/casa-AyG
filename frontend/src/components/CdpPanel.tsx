import { useEffect, useState, type FormEvent } from "react";
import { api, messageOf } from "../api";
import { amountChange, amountToInput, dateLabel, money, parseAmount, todayIso } from "../format";
import { useApp } from "../state";
import type { Place, Renewal } from "../types";

/** ¿Ya venció (o vence hoy) este CDP? */
export function cdpDue(place: Place): boolean {
  return Boolean(place.cdp?.maturesOn && place.cdp.maturesOn <= todayIso());
}

function configured(place: Place): boolean {
  return Boolean(place.cdp?.rate && place.cdp.maturesOn && place.cdp.payoutPlace);
}

const decimal = (n: number | null | undefined) => (n == null ? "" : String(n).replace(".", ","));

/**
 * El CDP dentro del editor del lugar: sus datos (como vienen en el comprobante) y, cuando
 * vence, la renovación calculada por el servidor igual que el banco.
 */
export function CdpPanel({ place, places, onDone }: { place: Place; places: Place[]; onDone: () => void }) {
  const [editing, setEditing] = useState(!configured(place));
  if (editing) return <CdpSettings place={place} places={places} onDone={() => { setEditing(false); onDone(); }} />;
  const c = place.cdp!;
  const payout = places.find((p) => p.id === c.payoutPlace);
  return (
    <div className="cdp">
      <p className="cdp-line">
        {decimal(c.rate)} % anual a {c.termDays} días ·{" "}
        {c.maturesOn === todayIso() ? <strong>vence hoy</strong>
          : <>{cdpDue(place) ? "venció" : "vence"} el <strong>{dateLabel(c.maturesOn)}</strong></>}
        {" "}· intereses a {payout?.name ?? "otra cuenta"}
      </p>
      {cdpDue(place) && <Renew place={place} onDone={onDone} />}
      <button type="button" className="button ghost small" onClick={() => setEditing(true)}>Cambiar datos del CDP</button>
    </div>
  );
}

function CdpSettings({ place, places, onDone }: { place: Place; places: Place[]; onDone: () => void }) {
  const { space } = useApp();
  const c = place.cdp;
  const payouts = places.filter((p) => !p.archived && p.id !== place.id && p.kind !== "CDP" && p.currency === place.currency);
  const [rate, setRate] = useState(decimal(c?.rate));
  const [term, setTerm] = useState(String(c?.termDays ?? 30));
  const [basis, setBasis] = useState(c?.dayBasis ?? 360);
  const [tax, setTax] = useState(decimal(c?.taxPct ?? 15));
  const [maturesOn, setMaturesOn] = useState(c?.maturesOn ?? "");
  const [payout, setPayout] = useState(c?.payoutPlace ?? payouts[0]?.id ?? "");
  const [contribution, setContribution] = useState(amountToInput(c?.lastContribution));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const number = (text: string) => {
    const n = Number(text.replace(",", "."));
    return text.trim() && Number.isFinite(n) ? n : null;
  };

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const r = number(rate);
    const t = number(tax);
    const days = Number(term);
    if (!r || r <= 0 || r >= 100) return setError("Escribe la tasa anual, por ejemplo 2,85.");
    if (!Number.isInteger(days) || days < 1) return setError("El plazo va en días, por ejemplo 30.");
    if (t === null || t < 0 || t >= 100) return setError("Escribe el impuesto en porcentaje, por ejemplo 15.");
    if (!maturesOn) return setError("Pon la fecha de vencimiento del comprobante.");
    if (!payout) return setError("Elige la cuenta donde caen los intereses.");
    const amount = contribution.trim() ? parseAmount(contribution) : null;
    if (contribution.trim() && !amount) return setError("Revisa el aporte de cada mes.");
    setBusy(true);
    setError(null);
    try {
      await api.put(`/api/spaces/${space!.id}/places/${place.id}/cdp`, {
        rate: r, termDays: days, dayBasis: basis, taxPct: t, maturesOn, payoutPlace: payout, lastContribution: amount,
      });
      onDone();
    } catch (e) {
      setError(messageOf(e, "No se pudo guardar."));
      setBusy(false);
    }
  };

  return (
    <form className="cdp" onSubmit={submit} noValidate>
      <h3 className="form-title">Datos del CDP</h3>
      <p className="muted small">Cópialos del comprobante del banco. Con ellos la app calcula los intereses de cada mes.</p>
      <div className="field-row">
        <label className="field">
          <span>Tasa anual (%)</span>
          <input inputMode="decimal" value={rate} onChange={(e) => setRate(e.target.value)} placeholder="2,85" />
        </label>
        <label className="field">
          <span>Plazo (días)</span>
          <input inputMode="numeric" value={term} onChange={(e) => setTerm(e.target.value)} />
        </label>
        <label className="field">
          <span>Impuesto (%)</span>
          <input inputMode="decimal" value={tax} onChange={(e) => setTax(e.target.value)} />
        </label>
      </div>
      <div className="field-row">
        <label className="field">
          <span>Vence el</span>
          <input type="date" value={maturesOn} onChange={(e) => setMaturesOn(e.target.value)} />
        </label>
        <label className="field grow">
          <span>Los intereses caen en</span>
          <select value={payout} onChange={(e) => setPayout(e.target.value)}>
            {payouts.length === 0 && <option value="">Agrega primero tu cuenta del banco</option>}
            {payouts.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
          </select>
        </label>
      </div>
      <div className="field-row">
        <label className="field grow">
          <span>Aporte de cada mes (sugerido al renovar)</span>
          <input inputMode="decimal" value={contribution} onChange={(e) => setContribution(amountChange(e))} placeholder="200.000" />
        </label>
        <div className="field">
          <span>Base de días</span>
          <div className="segmented compact" role="group" aria-label="Base de días">
            <button type="button" aria-pressed={basis === 360} onClick={() => setBasis(360)}>360</button>
            <button type="button" aria-pressed={basis === 365} onClick={() => setBasis(365)}>365</button>
          </div>
        </div>
      </div>
      {error && <p className="form-error" role="alert">{error}</p>}
      <div className="actions">
        <button type="submit" className="button primary" disabled={busy}>{busy ? "Guardando…" : "Guardar datos"}</button>
      </div>
    </form>
  );
}

/** Qué se hace con el capital al renovar. */
type CapitalMove = "sumar" | "retirar" | "igual";

/**
 * La renovación, en tres bloques: lo que ganó el plazo que terminó (con sus datos de entonces),
 * las condiciones del plazo nuevo (llenas con las de la vez anterior) y el capital: sumarle,
 * retirar una parte a la cuenta que se elija o dejarlo igual. El servidor hace las cuentas.
 */
function Renew({ place, onDone }: { place: Place; onDone: () => void }) {
  const { space, places } = useApp();
  const c = place.cdp!;
  const cur = place.currency;
  const payoutName = places.find((p) => p.id === c.payoutPlace)?.name ?? "tu cuenta";
  const targets = places.filter((p) => !p.archived && p.id !== place.id && p.kind !== "CDP" && p.currency === cur);

  const [rate, setRate] = useState(decimal(c.rate));
  const [term, setTerm] = useState(String(c.termDays ?? 30));
  const [basis, setBasis] = useState(c.dayBasis ?? 360);
  const [tax, setTax] = useState(decimal(c.taxPct ?? 15));
  const [move, setMove] = useState<CapitalMove>(c.lastContribution ? "sumar" : "igual");
  const [contribution, setContribution] = useState(amountToInput(c.lastContribution ?? 0));
  const [withdrawal, setWithdrawal] = useState("");
  const [withdrawTo, setWithdrawTo] = useState(c.payoutPlace ?? targets[0]?.id ?? "");
  // Como en cualquier ingreso: apagado por defecto; marcado, suma a «Diezmo por dar».
  const [tithe, setTithe] = useState(false);
  // Cada cálculo va con lo que se escribió: solo vale mientras eso no cambie.
  const [preview, setPreview] = useState<{ query: string; renewal: Renewal } | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const number = (text: string) => {
    const n = Number(text.replace(",", "."));
    return text.trim() && Number.isFinite(n) ? n : null;
  };
  const rateValue = number(rate);
  const termValue = Number(term);
  const taxValue = number(tax);
  const amountOf = (text: string) => (text.trim() ? parseAmount(text) : "0");
  const contributionValue = move === "sumar" ? amountOf(contribution) : "0";
  const withdrawalValue = move === "retirar" ? amountOf(withdrawal) : "0";

  // Lo que falta o está mal, dicho en palabras; mientras haya algo, no se calcula ni se renueva.
  const problem =
    !rateValue || rateValue <= 0 || rateValue >= 100 ? "Escribe la tasa anual, por ejemplo 2,85."
    : !Number.isInteger(termValue) || termValue < 1 || termValue > 3660 ? "El plazo va en días, por ejemplo 30."
    : taxValue === null || taxValue < 0 || taxValue >= 100 ? "Escribe el impuesto en porcentaje, por ejemplo 15."
    : !contributionValue ? "Revisa cuánto le sumas."
    : !withdrawalValue ? "Revisa cuánto retiras."
    : move === "retirar" && Number(withdrawalValue) <= 0 ? "Escribe cuánto retiras."
    : move === "retirar" && !withdrawTo ? "Elige a qué cuenta va lo que retiras."
    : null;

  const query = problem ? null : new URLSearchParams({
    rate: String(rateValue), termDays: String(termValue), dayBasis: String(basis), taxPct: String(taxValue),
    contribution: contributionValue!, withdrawal: withdrawalValue!,
  }).toString();

  useEffect(() => {
    setError(null);
    if (!space || !query) return;
    let cancelled = false;
    const timer = window.setTimeout(() => {
      api.get<Renewal>(`/api/spaces/${space.id}/places/${place.id}/cdp/preview?${query}`)
        .then((renewal) => {
          if (cancelled) return;
          setPreview({ query, renewal });
          setError(null);
        })
        .catch((e) => !cancelled && setError(messageOf(e, "No se pudo calcular.")));
    }, 250);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [space, place.id, query]);

  const renew = async () => {
    if (problem) return setError(problem);
    if (!shown) return;
    setBusy(true);
    setError(null);
    try {
      await api.post(`/api/spaces/${space!.id}/places/${place.id}/cdp/renew`, {
        rate: rateValue, termDays: termValue, dayBasis: basis, taxPct: taxValue,
        contribution: contributionValue, withdrawal: withdrawalValue,
        withdrawTo: move === "retirar" ? withdrawTo : null, tithe,
      });
      onDone();
    } catch (e) {
      setError(messageOf(e, "No se pudo renovar."));
      setBusy(false);
    }
  };

  const withdrawName = places.find((p) => p.id === withdrawTo)?.name ?? "la cuenta";
  // Lo ganado no depende de lo que se escribe: vale el último cálculo. El resto, solo el actual.
  const earned = preview?.renewal ?? null;
  const shown = preview && preview.query === query ? preview.renewal : null;

  return (
    <div className="renew">
      <h3 className="form-title">Renovar</h3>

      <section className="renew-block" aria-labelledby={`earned-${place.id}`}>
        <h4 className="renew-step" id={`earned-${place.id}`}>Lo que ganó este plazo</h4>
        {earned ? (
          <>
            <dl className="renew-sheet">
              <dt>Interés bruto</dt><dd className="figure">{money(earned.grossInterest, cur, true)}</dd>
              <dt>Impuesto ({decimal(earned.earned.taxPct)} %)</dt><dd className="figure">− {money(earned.tax, cur, true)}</dd>
              <dt>Neto a {payoutName}</dt><dd className="figure"><strong>{money(earned.netInterest, cur, true)}</strong></dd>
            </dl>
            <p className="muted small">
              Con los datos de ese plazo: {decimal(earned.earned.rate)} % a {earned.earned.termDays} días.
            </p>
            <label className="check tithe-check">
              <input type="checkbox" checked={tithe} onChange={(e) => setTithe(e.target.checked)} />
              <span>Dar diezmo · <strong className="figure">{money(earned.tithe, cur, true)}</strong></span>
            </label>
            {tithe && <p className="muted small tithe-help">Se suma a «Diezmo por dar».</p>}
          </>
        ) : (
          <p className="muted small">Calculando…</p>
        )}
      </section>

      <div className="renew-block" role="group" aria-labelledby={`next-${place.id}`}>
        <h4 className="renew-step" id={`next-${place.id}`}>El nuevo plazo</h4>
        <p className="muted small">Vienen los de la vez anterior. Cámbialos si el banco te dio otros.</p>
        <div className="field-row">
          <label className="field">
            <span>Tasa anual (%)</span>
            <input inputMode="decimal" value={rate} onChange={(e) => setRate(e.target.value)} />
          </label>
          <label className="field">
            <span>Plazo (días)</span>
            <input inputMode="numeric" value={term} onChange={(e) => setTerm(e.target.value)} />
          </label>
        </div>
        <div className="field-row">
          <label className="field">
            <span>Impuesto (%)</span>
            <input inputMode="decimal" value={tax} onChange={(e) => setTax(e.target.value)} />
          </label>
          <div className="field">
            <span id={`basis-${place.id}`}>Base de días</span>
            <div className="segmented compact" role="group" aria-labelledby={`basis-${place.id}`}>
              <button type="button" aria-pressed={basis === 360} onClick={() => setBasis(360)}>360</button>
              <button type="button" aria-pressed={basis === 365} onClick={() => setBasis(365)}>365</button>
            </div>
          </div>
        </div>
        {shown && <p className="renew-note">Vence el <strong>{dateLabel(shown.nextMaturity)}</strong></p>}
      </div>

      <div className="renew-block" role="group" aria-labelledby={`capital-${place.id}`}>
        <h4 className="renew-step" id={`capital-${place.id}`}>El capital</h4>
        <div className="segmented" role="group" aria-label="Qué haces con el capital">
          <button type="button" aria-pressed={move === "sumar"} onClick={() => setMove("sumar")}>Sumar</button>
          <button type="button" aria-pressed={move === "retirar"} onClick={() => setMove("retirar")}>Retirar</button>
          <button type="button" aria-pressed={move === "igual"} onClick={() => setMove("igual")}>Dejarlo igual</button>
        </div>
        {move === "sumar" && (
          <label className="field">
            <span>Le sumas desde {payoutName}</span>
            <input inputMode="decimal" value={contribution} onChange={(e) => setContribution(amountChange(e))} />
          </label>
        )}
        {move === "retirar" && (
          <div className="field-row">
            <label className="field">
              <span>Retiras</span>
              <input inputMode="decimal" value={withdrawal} onChange={(e) => setWithdrawal(amountChange(e))} placeholder="0" />
            </label>
            <label className="field grow">
              <span>A la cuenta</span>
              <select value={withdrawTo} onChange={(e) => setWithdrawTo(e.target.value)}>
                {targets.length === 0 && <option value="">Agrega primero una cuenta</option>}
                {targets.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
              </select>
            </label>
          </div>
        )}
        {shown && (
          <p className="renew-result">
            El CDP queda en <strong className="figure">{money(shown.newPrincipal, cur, true)}</strong>
            <span className="muted small">
              {shown.withdrawal > 0 ? ` · ${money(shown.withdrawal, cur, true)} van a ${withdrawName}`
                : shown.contribution > 0 ? ` · ${money(shown.contribution, cur, true)} salen de ${payoutName}`
                : " · sin cambios al capital"}
            </span>
          </p>
        )}
      </div>

      {(error ?? problem) && <p className="form-error" role="alert">{error ?? problem}</p>}
      <div className="actions">
        <button type="button" className="button primary" onClick={renew} disabled={busy || !shown}>
          {busy ? "Renovando…" : "Renovar"}
        </button>
      </div>
    </div>
  );
}
