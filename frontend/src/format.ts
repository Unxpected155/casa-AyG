import type { ChangeEvent } from "react";
import type { Currency, Group, PlaceKind } from "./types";

const percent = new Intl.NumberFormat("es-CR", { maximumFractionDigits: 0 });

/**
 * Un monto para mostrar, con punto de miles y coma decimal, igual que al escribirlo:
 * ₡1.234.567 · ₡950.179,50 · $1.234,56. Los dólares siempre con dos decimales.
 */
export function money(amount: number, currency: Currency, exact = false): string {
  // Exacto: con céntimos solo si los hay, y entonces siempre dos (₡950.179,50, nunca ₡950.179,5).
  const cents = currency === "USD" || (exact && !Number.isInteger(Math.round(amount * 100) / 100));
  const [int, dec] = Math.abs(amount).toFixed(cents ? 2 : 0).split(".");
  const body = groupThousands(int ?? "0") + (dec ? `,${dec}` : "");
  return `${amount < 0 && body.replace(/[.,0]/g, "") !== "" ? "-" : ""}${currency === "USD" ? "$" : "₡"}${body}`;
}

export function pct(value: number): string {
  return `${percent.format(value)} %`;
}

export const GROUP_LABEL: Record<Group, string> = {
  INGRESO: "Ingresos",
  NECESIDAD: "Necesidades",
  DESEO: "Deseos",
  AHORRO: "Ahorro",
};

export const PLACE_KIND_LABEL: Record<PlaceKind, string> = {
  BANCO: "Banco",
  SOBRE: "Sobre",
  CDP: "CDP",
  EFECTIVO: "Efectivo",
};


export interface YearMonth {
  year: number;
  month: number;
}

export function currentMonth(): YearMonth {
  const now = new Date();
  return { year: now.getFullYear(), month: now.getMonth() + 1 };
}

export function shiftMonth(ym: YearMonth, delta: number): YearMonth {
  const date = new Date(ym.year, ym.month - 1 + delta, 1);
  return { year: date.getFullYear(), month: date.getMonth() + 1 };
}

const monthName = new Intl.DateTimeFormat("es-CR", { month: "long" });
const dayHeading = new Intl.DateTimeFormat("es-CR", { weekday: "long", day: "numeric", month: "long" });
const shortDate = new Intl.DateTimeFormat("es-CR", { day: "numeric", month: "short", year: "numeric" });
const dayMonth = new Intl.DateTimeFormat("es-CR", { day: "numeric", month: "short" });

export function monthLabel(ym: YearMonth): string {
  const name = monthName.format(new Date(ym.year, ym.month - 1, 1));
  return name.charAt(0).toUpperCase() + name.slice(1);
}

/** "2026-11-16" → fecha local (sin desfase de zona horaria). */
export function parseDate(iso: string): Date {
  const [y, m, d] = iso.split("-").map(Number);
  return new Date(y ?? 2000, (m ?? 1) - 1, d ?? 1);
}

export function dayLabel(iso: string): string {
  const today = new Date();
  const date = parseDate(iso);
  const same = (a: Date, b: Date) => a.toDateString() === b.toDateString();
  if (same(date, today)) return "Hoy";
  const yesterday = new Date(today);
  yesterday.setDate(today.getDate() - 1);
  if (same(date, yesterday)) return "Ayer";
  const text = dayHeading.format(date);
  return text.charAt(0).toUpperCase() + text.slice(1);
}

export function dateLabel(iso: string | null): string {
  if (!iso) return "nunca";
  // "2026-12-09" es una fecha sin hora: se lee en hora local (si no, en Costa Rica sale un día antes).
  return shortDate.format(/^\d{4}-\d{2}-\d{2}$/.test(iso) ? parseDate(iso) : new Date(iso));
}

/** "2026-11-25" → "25 nov" (sin año: para fechas de este mes). */
export function dayMonthLabel(iso: string): string {
  return dayMonth.format(parseDate(iso));
}

export function todayIso(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

export function initials(name: string): string {
  return name.split(/\s+/).filter(Boolean).slice(0, 2).map((w) => w.charAt(0).toUpperCase()).join("");
}

/** Convierte lo que se escribe ("12.500,50", "12500.5") en un decimal con punto, o null. */
/**
 * Lee un monto escrito como en Costa Rica: el punto separa miles y la coma, los decimales
 * ("1.200.000,50"). El punto NUNCA es decimal: "1.200" son mil doscientos.
 * @return el monto en formato de la API ("1200000.50"), o null si no es válido.
 */
export function parseAmount(text: string): string | null {
  const cleaned = text.replace(/[\s₡$]/g, "");
  if (!cleaned || !/^[\d.]*(,\d{0,2})?$/.test(cleaned)) return null;
  const normalized = cleaned.replace(/\./g, "").replace(",", ".").replace(/\.$/, "");
  if (!/^\d{1,12}(\.\d{1,2})?$/.test(normalized)) return null;
  return normalized;
}

const groupThousands = (digits: string) => digits.replace(/\B(?=(\d{3})+(?!\d))/g, ".");

/** Mientras se escribe: "1200000,5" → "1.200.000,5". Una sola coma, hasta dos decimales. */
export function formatAmountInput(text: string): string {
  const cleaned = text.replace(/[^\d,]/g, "");
  const comma = cleaned.indexOf(",");
  const intPart = (comma < 0 ? cleaned : cleaned.slice(0, comma)).replace(/^0+(?=\d)/, "").slice(0, 12);
  if (comma < 0) return groupThousands(intPart);
  const decimals = cleaned.slice(comma + 1).replace(/,/g, "").slice(0, 2);
  return `${groupThousands(intPart) || "0"},${decimals}`;
}

/** Un monto de la API, listo para un campo: 1234567.5 → "1.234.567,50". */
export function amountToInput(value: number | string | null | undefined): string {
  if (value == null || value === "") return "";
  const n = Number(value);
  if (!Number.isFinite(n)) return "";
  const [int, dec] = (Math.round(n * 100) / 100).toFixed(2).split(".");
  return dec === "00" ? groupThousands(int ?? "0") : `${groupThousands(int ?? "0")},${dec}`;
}

/**
 * Para el onChange de un campo de monto: devuelve el texto con formato y, como al agregar o
 * quitar puntos el texto cambia de largo, deja el cursor donde estaba (contando solo dígitos y coma).
 */
export function amountChange(event: ChangeEvent<HTMLInputElement>): string {
  const el = event.target;
  const caret = el.selectionStart ?? el.value.length;
  const before = el.value.slice(0, caret).replace(/[^\d,]/g, "").length;
  const next = formatAmountInput(el.value);
  requestAnimationFrame(() => {
    if (document.activeElement !== el) return;
    let pos = 0;
    for (let seen = 0; pos < next.length && seen < before; pos++) {
      if (/[\d,]/.test(next[pos] ?? "")) seen++;
    }
    el.setSelectionRange(pos, pos);
  });
  return next;
}
