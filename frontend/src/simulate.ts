// El simulador de una meta: cuentas exactas, sin IA. Todo en la moneda de la meta.
import type { YearMonth } from "./format";

export interface SimInput {
  /** Lo que ya tiene la meta hoy. */
  saved: number;
  /** El objetivo (0 = sin objetivo). */
  target: number;
  /** Lo que se aparta cada mes, a partir del mes que viene. */
  monthly: number;
  /** Un monto extra una sola vez (aguinaldo, bono) y el mes en que entra. */
  extra: number;
  extraMonth: YearMonth | null;
  /** El mes que se quiere mirar ("¿cuánto tendré en…?"). */
  until: YearMonth;
  /** Este mes. */
  now: YearMonth;
}

export interface SimResult {
  /** Cuántos meses de aporte hay entre ahora y `until`. */
  months: number;
  /** Lo que habría en `until`. */
  atUntil: number;
  /** Lo que sobra (+) o falta (−) contra el objetivo en `until`. */
  diff: number;
  /** Cuánto habría que apartar al mes para llegar justo en `until` (null si ya alcanza). */
  neededMonthly: number | null;
  /** El primer mes en que se llega al objetivo (null si nunca, con estos números). */
  reachedAt: YearMonth | null;
}

const index = (ym: YearMonth) => ym.year * 12 + (ym.month - 1);
const fromIndex = (i: number): YearMonth => ({ year: Math.floor(i / 12), month: (i % 12) + 1 });

/** Lo que habría después de k meses de aporte (k = 0 es hoy). */
function amountAfter(input: SimInput, k: number): number {
  const extraAt = input.extraMonth ? index(input.extraMonth) - index(input.now) : -1;
  const extra = extraAt >= 1 && extraAt <= k ? input.extra : 0;
  return input.saved + input.monthly * k + extra;
}

export function simulate(input: SimInput): SimResult {
  const months = Math.max(0, index(input.until) - index(input.now));
  const atUntil = amountAfter(input, months);
  const diff = atUntil - input.target;

  let neededMonthly: number | null = null;
  if (input.target > 0 && diff < 0) {
    neededMonthly = months > 0 ? Math.ceil((input.monthly * months - diff) / months) : null;
  }

  let reachedAt: YearMonth | null = null;
  if (input.target > 0) {
    // 50 años como máximo: más allá no es un plan.
    for (let k = 0; k <= 600; k++) {
      if (amountAfter(input, k) >= input.target) {
        reachedAt = fromIndex(index(input.now) + k);
        break;
      }
      if (input.monthly <= 0 && k > (input.extraMonth ? index(input.extraMonth) - index(input.now) : 0)) break;
    }
  }
  return { months, atUntil, diff, neededMonthly, reachedAt };
}

/** Qué partes de la meta alcanzan con un monto, en el orden en que se anotaron. */
export function partsCovered(parts: { name: string; amount: number }[], amount: number): { covered: string[]; missing: string[] } {
  const covered: string[] = [];
  const missing: string[] = [];
  let left = amount;
  for (const p of parts) {
    if (left >= p.amount) {
      covered.push(p.name);
      left -= p.amount;
    } else {
      missing.push(p.name);
      left = -1; // en orden: si una no alcanza, las siguientes tampoco cuentan como cubiertas
    }
  }
  return { covered, missing };
}
