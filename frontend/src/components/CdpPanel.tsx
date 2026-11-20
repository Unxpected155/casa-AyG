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

/** La renovación: el servidor calcula; aquí se ajusta la tasa o el aporte y se confirma. */
function Renew({ place, onDone }: { place: Place; onDone: () => void }) {
  const { space, places } = useApp();
  const c = place.cdp!;
  const [rate, setRate] = useState(decimal(c.rate));
  const [contribution, setContribution] = useState(amountToInput(c.lastContribution ?? 0));
  const [tithe, setTithe] = useState(true);
  const [preview, setPreview] = useState<Renewal | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const rateValue = Number(rate.replace(",", "."));
  const contributionValue = contribution.trim() ? parseAmount(contribution) : "0";

  useEffect(() => {
    if (!space || !(rateValue > 0 && rateValue < 100) || !contributionValue) return;
    let cancelled = false;
    const timer = window.setTimeout(() => {
      api.get<Renewal>(`/api/spaces/${space.id}/places/${place.id}/cdp/preview?rate=${rateValue}&contribution=${contributionValue}`)
        .then((r) => !cancelled && setPreview(r))
        .catch((e) => !cancelled && setError(messageOf(e, "No se pudo calcular.")));
    }, 250);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [space, place.id, rateValue, contributionValue]);

  const renew = async () => {
    if (!preview || !contributionValue) return;
    setBusy(true);
    setError(null);
    try {
      await api.post(`/api/spaces/${space!.id}/places/${place.id}/cdp/renew`, {
        rate: rateValue, contribution: contributionValue, tithe,
      });
      onDone();
    } catch (e) {
      setError(messageOf(e, "No se pudo renovar."));
      setBusy(false);
    }
  };

  const payout = places.find((p) => p.id === c.payoutPlace)?.name ?? "tu cuenta";
  const cur = place.currency;

  return (
    <div className="renew">
      <h3 className="form-title">Renovar</h3>
      <div className="field-row">
        <label className="field">
          <span>Tasa anual (%)</span>
          <input inputMode="decimal" value={rate} onChange={(e) => setRate(e.target.value)} />
        </label>
        <label className="field grow">
          <span>Le sumas este mes</span>
          <input inputMode="decimal" value={contribution} onChange={(e) => setContribution(amountChange(e))} />
        </label>
      </div>
      <label className="check">
        <input type="checkbox" checked={tithe} onChange={(e) => setTithe(e.target.checked)} />
        <span>Dar diezmo de los intereses</span>
      </label>
      {preview && (
        <dl className="renew-sheet">
          <dt>Interés bruto</dt><dd className="figure">{money(preview.grossInterest, cur, true)}</dd>
          <dt>Impuesto</dt><dd className="figure">− {money(preview.tax, cur, true)}</dd>
          <dt>Interés neto a {payout}</dt><dd className="figure"><strong>{money(preview.netInterest, cur, true)}</strong></dd>
          {tithe && <><dt>Diezmo (10 % del bruto)</dt><dd className="figure">− {money(preview.tithe, cur, true)}</dd></>}
          <dt>Aporte de {payout} al CDP</dt><dd className="figure">{money(preview.contribution, cur, true)}</dd>
          <dt>El CDP queda en</dt><dd className="figure"><strong>{money(preview.newPrincipal, cur, true)}</strong></dd>
          <dt>Próximo vencimiento</dt><dd>{dateLabel(preview.nextMaturity)}</dd>
        </dl>
      )}
      {error && <p className="form-error" role="alert">{error}</p>}
      <div className="actions">
        <button type="button" className="button primary" onClick={renew} disabled={busy || !preview}>
          {busy ? "Renovando…" : "Renovar"}
        </button>
      </div>
    </div>
  );
}
