import type { Currency, Group, PlaceKind } from "./types";

const crc = new Intl.NumberFormat("es-CR", { style: "currency", currency: "CRC", currencyDisplay: "narrowSymbol", maximumFractionDigits: 0 });
const crcCents = new Intl.NumberFormat("es-CR", { style: "currency", currency: "CRC", currencyDisplay: "narrowSymbol", minimumFractionDigits: 2, maximumFractionDigits: 2 });
const usd = new Intl.NumberFormat("es-CR", { style: "currency", currency: "USD", currencyDisplay: "narrowSymbol", minimumFractionDigits: 2, maximumFractionDigits: 2 });
const percent = new Intl.NumberFormat("es-CR", { maximumFractionDigits: 0 });

export function money(amount: number, currency: Currency, exact = false): string {
  if (currency === "USD") return usd.format(amount);
  // Exacto: con céntimos solo si los hay, y entonces siempre dos (₡950 179,50, nunca ₡950 179,5).
  return exact && !Number.isInteger(Math.round(amount * 100) / 100) ? crcCents.format(amount) : crc.format(amount);
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
export function parseAmount(text: string): string | null {
  const cleaned = text.replace(/[\s₡$]/g, "");
  if (!cleaned) return null;
  // Si hay coma como decimal (formato es-CR), los puntos son de miles.
  const normalized = cleaned.includes(",") ? cleaned.replace(/\./g, "").replace(",", ".") : cleaned;
  if (!/^\d{1,12}(\.\d{1,2})?$/.test(normalized)) return null;
  return normalized;
}
