import { useMemo, useState } from "react";
import { amountChange, amountToInput, currentMonth, money, monthLabel, parseAmount, shiftMonth, type YearMonth } from "../format";
import { partsCovered, simulate } from "../simulate";
import type { Goal } from "../types";

const toInput = (ym: YearMonth) => `${ym.year}-${String(ym.month).padStart(2, "0")}`;
const fromInput = (v: string): YearMonth | null => {
  const m = /^(\d{4})-(\d{2})$/.exec(v);
  return m ? { year: Number(m[1]), month: Number(m[2]) } : null;
};
const label = (ym: YearMonth) => `${monthLabel(ym).toLowerCase()} de ${ym.year}`;

/** «¿Y si…?»: cuánto tendrías en una fecha moviendo el aporte mensual y un extra. Sin IA. */
export function GoalSimulator({ goal }: { goal: Goal }) {
  const now = currentMonth();
  const defaultUntil = goal.targetDate ? { year: Number(goal.targetDate.slice(0, 4)), month: Number(goal.targetDate.slice(5, 7)) }
    : shiftMonth(now, 12);
  const decemberAhead = now.month === 12 ? { year: now.year + 1, month: 12 } : { year: now.year, month: 12 };
  const [monthly, setMonthly] = useState(amountToInput(Math.round(goal.monthlyNeeded ?? goal.monthlyPace)));
  const [extra, setExtra] = useState("");
  const [extraMonth, setExtraMonth] = useState(toInput(decemberAhead));
  const [until, setUntil] = useState(toInput(defaultUntil));

  const result = useMemo(() => {
    const u = fromInput(until);
    if (!u) return null;
    return simulate({
      saved: goal.saved,
      target: goal.target,
      monthly: Number(parseAmount(monthly) ?? 0),
      extra: Number(parseAmount(extra) ?? 0),
      extraMonth: fromInput(extraMonth),
      until: u,
      now,
    });
  }, [goal.saved, goal.target, monthly, extra, extraMonth, until]);

  const cur = goal.currency;
  const u = fromInput(until);
  const parts = result && goal.parts.length > 0 ? partsCovered(goal.parts, result.atUntil) : null;

  return (
    <details className="simulator">
      <summary>¿Y si…? Simula tu ahorro</summary>
      <div className="simulator-fields">
        <label className="field">
          <span>Apartas al mes</span>
          <input inputMode="decimal" value={monthly} onChange={(e) => setMonthly(amountChange(e))} placeholder="0" />
        </label>
        <label className="field">
          <span>Para mirar en</span>
          <input type="month" value={until} min={toInput(now)} onChange={(e) => setUntil(e.target.value)} />
        </label>
        <label className="field">
          <span>Un extra una vez <span className="muted">(aguinaldo, bono)</span></span>
          <input inputMode="decimal" value={extra} onChange={(e) => setExtra(amountChange(e))} placeholder="0" />
        </label>
        <label className="field">
          <span>Entra en</span>
          <input type="month" value={extraMonth} min={toInput(shiftMonth(now, 1))} onChange={(e) => setExtraMonth(e.target.value)} />
        </label>
      </div>
      {result && u && (
        <div className="simulator-result" aria-live="polite">
          <p>
            En {label(u)} tendrías <strong className="figure">{money(result.atUntil, cur)}</strong>.
            {goal.target > 0 && (result.diff >= 0
              ? (result.diff < 1 ? <> Llegas justo.</>
                : <> Llegas y te sobran <strong className="figure">{money(result.diff, cur)}</strong>.</>)
              : <> Te faltarían <strong className="figure">{money(-result.diff, cur)}</strong>.</>)}
          </p>
          {result.neededMonthly != null && (
            <p>Para llegar justo en {label(u)}, aparta <strong className="figure">{money(result.neededMonthly, cur)}</strong> al mes.</p>
          )}
          {goal.target > 0 && (
            <p className="muted">
              {result.reachedAt
                ? <>Con estos números llegas a {money(goal.target, cur)} en {label(result.reachedAt)}.</>
                : <>Con estos números no llegas: sube lo que apartas al mes.</>}
            </p>
          )}
          {parts && (parts.covered.length > 0 || parts.missing.length > 0) && (
            <p className="muted">
              {parts.covered.length > 0 && <>Alcanza para: {parts.covered.join(", ")}. </>}
              {parts.missing.length > 0 && <>Todavía no: {parts.missing.join(", ")}.</>}
            </p>
          )}
          <p className="hint small">No incluye los intereses del CDP: lo real puede ser un poco más.</p>
        </div>
      )}
    </details>
  );
}
