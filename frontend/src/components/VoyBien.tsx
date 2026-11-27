import { useRef, type CSSProperties } from "react";
import { money, monthLabel, parseDate } from "../format";
import { gsap, useGSAP, whenMotionAllowed } from "../motion";
import { navigate, type Route } from "../router";
import type { Health } from "../types";
import { Icon } from "./Icon";

type Tone = "ok" | "near" | "neutral";

interface Signal {
  key: string;
  title: string;
  /** La frase principal: lo que pasa, en palabras. */
  text: string;
  /** El estado en una o dos palabras (nunca solo el color). */
  status: string | null;
  /** Una línea de apoyo opcional (qué hacer, o con qué se compara). */
  detail?: string;
  tone: Tone;
  /** Avance de 0 a 1, o null si no hay nada que medir. */
  fill: number | null;
  route: Route;
}

const monthYear = (iso: string) => {
  const d = parseDate(iso);
  return `${monthLabel({ year: d.getFullYear(), month: d.getMonth() + 1 }).toLowerCase()} de ${d.getFullYear()}`;
};
const oneDecimal = new Intl.NumberFormat("es-CR", { maximumFractionDigits: 1 });

/** Las cuatro señales de «¿Voy bien?», cada una como un renglón de la libreta que lleva a su pantalla. */
export function VoyBien({ health }: { health: Health }) {
  const signals = [emergency(health), savings(health), debts(health), goals(health)];
  const scope = useRef<HTMLElement>(null);
  // Las líneas se llenan una vez, como el subrayado de las categorías.
  useGSAP(() => whenMotionAllowed(() => {
    gsap.from(".rule-fill", { scaleX: 0, duration: 0.6, ease: "expo.out", stagger: 0.05, clearProps: "transform" });
  }), { scope, dependencies: [] });
  return (
    <section className="health" aria-labelledby="health-title" ref={scope}>
      <h2 id="health-title" className="group-title">¿Voy bien?</h2>
      <ul className="rows">
        {signals.map((s) => (
          <li key={s.key} className="row health-row">
            <button type="button" className="row-button health-button" onClick={() => navigate(s.route)}>
              <span className="health-body">
                <span className="health-title">{s.title}</span>
                <span className="health-text">{s.text}</span>
                {s.fill !== null && (
                  // Decorativa: la frase de al lado ya dice el valor (no se lee dos veces).
                  <span className="row-rule health-rule" aria-hidden="true">
                    <span className={`rule-fill ${s.tone === "near" ? "near" : "ok"}`}
                          style={{ "--fill": Math.max(0, Math.min(1, s.fill)) } as CSSProperties} />
                  </span>
                )}
                {s.detail && <span className="health-detail">{s.detail}</span>}
              </span>
              {s.status && (
                <span className={`health-status ${s.tone}`}>
                  {s.tone !== "neutral" && <Icon name={s.tone === "ok" ? "check" : "alert"} size={14} />}
                  {s.status}
                </span>
              )}
            </button>
          </li>
        ))}
      </ul>
    </section>
  );
}

function emergency(h: Health): Signal {
  const e = h.emergency;
  const base = { key: "emergency", title: "Fondo de emergencia", route: "/metas" as Route };
  if (!e) {
    return { ...base, text: "Todavía no tienes uno.", status: "Crearlo", tone: "neutral", fill: null,
      detail: "Es dinero guardado para vivir unos meses si algo pasa (por defecto, 3 meses de gastos)." };
  }
  if (e.monthsCovered === null) {
    return { ...base, text: `Tiene ${money(e.saved, e.currency)}.`, status: null, tone: "neutral", fill: null,
      detail: "Cuando termine un mes con gastos anotados, te digo cuántos meses cubre." };
  }
  const months = oneDecimal.format(e.monthsCovered);
  const complete = e.monthsCovered >= e.targetMonths;
  const missing = e.monthlyExpenses ? e.monthlyExpenses * e.targetMonths - e.saved : 0;
  return { ...base, text: `Te cubre ${months} de ${e.targetMonths} meses de gastos.`,
    status: complete ? "Completo" : "Falta", tone: complete ? "ok" : "near",
    fill: e.targetMonths > 0 ? e.monthsCovered / e.targetMonths : null,
    detail: complete ? undefined : `Te faltan ${money(Math.max(missing, 0), e.currency)} para completarlo.` };
}

function savings(h: Health): Signal {
  const r = h.savingsRate;
  const base = { key: "savings", title: "Ahorro del mes", route: "/historia" as Route };
  if (r.month === null) {
    return { ...base, text: "Este mes todavía no entra ningún ingreso.", status: null, tone: "neutral", fill: null,
      detail: "Cuando anotes lo que te entra, te digo qué parte guardas." };
  }
  const text = r.month < 0
    ? `Este mes sacaste ${money(-r.savedMonth, "CRC")} de tus ahorros.`
    : `Guardas el ${r.month} % de lo que te entra.`;
  if (r.average === null) {
    return { ...base, text, status: null, tone: "neutral", fill: null,
      detail: "Todavía no hay meses terminados para comparar." };
  }
  const good = r.month >= r.average;
  return { ...base, text, status: good ? "Vas bien" : "Ojo", tone: good ? "ok" : "near",
    fill: r.average > 0 ? r.month / r.average : good ? 1 : 0,
    detail: `Tu promedio es ${r.average} % (${r.averageMonths === 1 ? "el mes pasado" : `últimos ${r.averageMonths} meses`}).` };
}

function debts(h: Health): Signal {
  const items = h.debts.items;
  const base = { key: "debts", title: "Deudas", route: "/deudas" as Route, fill: null };
  if (items.length === 0) {
    return { ...base, text: "No debes nada.", status: "Al día", tone: "ok" };
  }
  const text = items.length === 1
    ? `Le debes ${money(items[0]!.remaining, items[0]!.currency)} a ${items[0]!.creditor}.`
    : `Debes ${money(h.debts.remainingCrc, "CRC")} en ${items.length} deudas.`;
  const late = items.find((d) => d.status === "ATRASADA");
  if (late) {
    const detail = late.monthlyNeeded !== null
      ? `${late.creditor}: necesitas ${money(late.monthlyNeeded, late.currency)} al mes para llegar a tiempo; tu promedio es ${money(late.monthlyAverage, late.currency)}.`
      : `${late.creditor}: se pasó la fecha acordada.`;
    return { ...base, text, status: "Ojo", tone: "near", detail };
  }
  if (items.every((d) => d.status === "A_TIEMPO")) {
    return { ...base, text, status: "A tiempo", tone: "ok" };
  }
  // Sin fecha límite: no se juzga; solo el estimado, si hay abonos.
  const ends = items.map((d) => d.estimatedEnd).filter((d): d is string => d !== null).sort();
  const last = ends[ends.length - 1];
  return { ...base, text, status: null, tone: "neutral",
    detail: last ? `A tu ritmo de abonos terminarías en ${monthYear(last)}.` : "Todavía no hay abonos para estimar cuándo terminas." };
}

function goals(h: Health): Signal {
  const g = h.goals;
  const base = { key: "goals", title: "Metas", route: "/metas" as Route };
  if (g.judged === 0 && g.withoutDate === 0) {
    return { ...base, text: "Todavía no tienes metas.", status: "Crear una", tone: "neutral", fill: null,
      detail: "Un carro, un viaje, el retiro: ponle monto y fecha y te digo si vas a tiempo." };
  }
  if (g.judged === 0) {
    return { ...base, text: g.withoutDate === 1 ? "Tu meta no tiene fecha." : "Tus metas no tienen fecha.",
      status: null, tone: "neutral", fill: null, detail: "Ponles una fecha para saber si vas a tiempo." };
  }
  const all = g.onTime === g.judged;
  return { ...base,
    text: g.judged === 1 ? (all ? "Tu meta va a tiempo." : "Tu meta va atrasada.")
      : `${g.onTime} de ${g.judged} metas van a tiempo.`,
    status: all ? "A tiempo" : "Ojo", tone: all ? "ok" : "near", fill: g.onTime / g.judged,
    detail: all ? undefined : `${g.behind.slice(0, 2).join(" y ")} ${g.behind.length === 1 ? "necesita" : "necesitan"} más al mes.` };
}
