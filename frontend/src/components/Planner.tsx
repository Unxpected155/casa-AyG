import { useEffect, useState } from "react";
import { api, messageOf } from "../api";
import { amountChange, amountToInput, money, parseAmount } from "../format";
import { useApp } from "../state";
import type { Currency } from "../types";

interface PlanItem {
  goalId: string;
  name: string;
  currency: Currency;
  remaining: number;
  targetDate: string | null;
  monthlyCrc: number;
  eta: string | null;
  /** null: la meta no tiene fecha. */
  onTime: boolean | null;
}

interface PlannerData {
  averageSurplus: number | null;
  averageMonths: number;
  recommended: number | null;
  recommendedGoal: string | null;
  recommendedBy: string | null;
  monthly: number;
  items: PlanItem[];
  skipped: string[];
}

interface Progress {
  createdAt: string;
  startMonth: string;
  monthly: number;
  months: { month: string; planned: number; actual: number; partial: boolean }[];
}

const monthYear = new Intl.DateTimeFormat("es-CR", { month: "long", year: "numeric" });
const monthOnly = new Intl.DateTimeFormat("es-CR", { month: "long" });
const dayMonth = new Intl.DateTimeFormat("es-CR", { day: "numeric", month: "short" });
const asDate = (iso: string) => {
  const [y, m, d] = iso.split("-").map(Number);
  return new Date(y ?? 2000, (m ?? 1) - 1, d ?? 1);
};
const capital = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);

/**
 * El planeador (reemplaza al simulador). Nada es obligatorio y nada se aparta solo: responde
 * «si apartamos tanto al mes, ¿cuándo llegamos?». Guardar un plan es opcional; con uno guardado,
 * cada mes muestra lo planeado y lo que entró, sin juicios.
 */
export function Planner() {
  const { space, version, touch } = useApp();
  const [data, setData] = useState<PlannerData | null>(null);
  const [progress, setProgress] = useState<Progress | null>(null);
  const [monthly, setMonthly] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const readOnly = Boolean(space?.archived);

  const typed = monthly === null ? null : parseAmount(monthly);
  const query = typed ? `?monthly=${typed}` : "";

  useEffect(() => {
    if (!space) return;
    let cancelled = false;
    const timer = window.setTimeout(() => {
      api.get<PlannerData>(`/api/spaces/${space.id}/planner${query}`)
        .then((d) => {
          if (cancelled) return;
          setData(d);
          setError(null);
          if (monthly === null) setMonthly(amountToInput(d.monthly));
        })
        .catch((e) => !cancelled && setError(messageOf(e, "No se pudo calcular el plan.")));
    }, 250);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [space, version, query]);

  useEffect(() => {
    if (!space) return;
    let cancelled = false;
    api.get<Progress | undefined>(`/api/spaces/${space.id}/planner/plan`)
      .then((p) => !cancelled && setProgress(p ?? null))
      .catch(() => !cancelled && setProgress(null));
    return () => {
      cancelled = true;
    };
  }, [space, version]);

  if (!data || data.items.length === 0) return null;

  const save = async () => {
    if (typed === null) return;
    setBusy(true);
    setError(null);
    try {
      setProgress(await api.post<Progress>(`/api/spaces/${space!.id}/planner/plan`, { monthly: typed }));
      setSaved(true);
      touch();
    } catch (e) {
      setError(messageOf(e, "No se pudo guardar el plan."));
    } finally {
      setBusy(false);
    }
  };

  const remove = async () => {
    setBusy(true);
    try {
      await api.del(`/api/spaces/${space!.id}/planner/plan`);
      setProgress(null);
      setSaved(false);
      touch();
    } catch (e) {
      setError(messageOf(e, "No se pudo borrar el plan."));
    } finally {
      setBusy(false);
    }
  };

  // Una sola recomendación concreta: el mínimo para llegar a tiempo, o lo que en promedio sobró.
  const recommendation = data.recommended !== null && data.recommendedGoal && data.recommendedBy
    ? <>Recomiendo apartar <strong className="figure">{money(data.recommended, "CRC")}</strong> al mes para llegar a{" "}
        {data.recommendedGoal} en {monthYear.format(asDate(data.recommendedBy))}.</>
    : data.averageSurplus !== null && data.items[0]
      ? <>Recomiendo apartar <strong className="figure">{money(data.averageSurplus, "CRC")}</strong> al mes, lo que en
          promedio les sobró{data.items[0].eta ? <> : llegan a {data.items[0].name} en {monthYear.format(asDate(data.items[0].eta))}</> : null}.</>
      : null;
  const aboveAverage = data.recommended !== null && data.averageSurplus !== null && data.recommended > data.averageSurplus;

  return (
    <section className="block planner" aria-labelledby="planner-title">
      <h2 id="planner-title" className="section-title">¿Cuándo llego?</h2>
      {recommendation && <p>{recommendation}</p>}
      {aboveAverage && (
        <p className="hint">Es más de lo que en promedio les sobra ({money(data.averageSurplus!, "CRC")}).</p>
      )}
      <label className="field planner-amount">
        <span>Si apartan al mes</span>
        <input inputMode="decimal" value={monthly ?? ""} onChange={(e) => { setMonthly(amountChange(e)); setSaved(false); }} />
      </label>

      <ol className="planner-list">
        {data.items.map((i) => (
          <li key={i.goalId} className="planner-item">
            <div className="planner-row">
              <span className="planner-name">{i.name}</span>
              <span className="figure">
                {i.remaining <= 0 ? "Completa" : i.monthlyCrc > 0 ? `${money(i.monthlyCrc, "CRC")}/mes` : "₡0/mes por ahora"}
              </span>
            </div>
            {i.remaining > 0 && (
              <p className="muted small">
                {i.eta ? <>Llegan en {monthYear.format(asDate(i.eta))}</> : <>Con este monto no llegan</>}
                {i.monthlyCrc <= 0 && i.eta && <> · empieza cuando terminen las de arriba</>}
                {i.onTime === true && i.targetDate && <> · <span className="planner-ok">a tiempo</span></>}
                {i.onTime === false && i.targetDate && (
                  <> · <span className="planner-late">después de su fecha ({monthYear.format(asDate(i.targetDate))})</span></>
                )}
              </p>
            )}
          </li>
        ))}
      </ol>
      {data.skipped.length > 0 && (
        <p className="hint">Sin el tipo de cambio de hoy no se calculan: {data.skipped.join(", ")}.</p>
      )}
      {error && <p className="form-error" role="alert">{error}</p>}
      {!readOnly && (progress && typed !== null && Number(typed) === progress.monthly ? (
        <p className="muted small">{saved ? "Plan guardado." : "Este es el plan que guardaron."}</p>
      ) : (
        <div className="actions start">
          <button type="button" className="button secondary" disabled={busy || typed === null} onClick={() => void save()}>
            {progress ? "Cambiar el plan a este monto" : "Guardar este plan"}
          </button>
          <span className="muted small">Opcional: sirve para comparar cada mes.</span>
        </div>
      ))}

      {progress && (
        <div className="planner-progress">
          <h3 className="planner-progress-title">¿Voy como planeé?</h3>
          <p className="muted small">
            Plan del {dayMonth.format(new Date(progress.createdAt))}: {money(progress.monthly, "CRC")} al mes.
          </p>
          <ul className="planner-months">
            {progress.months.map((m) => (
              <li key={m.month} className="planner-month">
                <span>{capital(monthOnly.format(asDate(`${m.month}-01`)))}</span>
                <span className="muted small">
                  {m.partial
                    ? <>en curso · van <span className="figure">{money(m.actual, "CRC")}</span></>
                    : <>planearon <span className="figure">{money(m.planned, "CRC")}</span> · apartaron{" "}
                        <span className="figure">{money(m.actual, "CRC")}</span></>}
                </span>
              </li>
            ))}
          </ul>
          {!readOnly && (
            <button type="button" className="button ghost small" disabled={busy} onClick={() => void remove()}>
              Borrar el plan
            </button>
          )}
        </div>
      )}
    </section>
  );
}
