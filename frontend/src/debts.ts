// Deudas: datos y cálculos que usan varias pantallas (Deudas, Mes, Movimientos, la hoja de
// anotar). Va aparte de screens/Debts.tsx para no crear un import circular con EntrySheet.
import { useEffect, useState } from "react";
import { api, messageOf } from "./api";
import type { EntrySheetProps } from "./components/EntrySheet";
import { money } from "./format";
import { useApp } from "./state";
import type { Category, Currency, Debt } from "./types";

export const DEBT_CATEGORY = "Pago de deudas";

/** Las deudas del espacio. `null` mientras cargan. */
export function useDebts(enabled = true): { debts: Debt[] | null; error: string | null } {
  const { space, version } = useApp();
  const [debts, setDebts] = useState<Debt[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    if (!space || !enabled) return;
    let cancelled = false;
    setError(null);
    api.get<Debt[]>(`/api/spaces/${space.id}/debts`)
      .then((list) => !cancelled && setDebts(list))
      .catch((e) => {
        if (cancelled) return;
        setDebts([]);
        setError(messageOf(e, "No se pudieron cargar las deudas."));
      });
    return () => {
      cancelled = true;
    };
  }, [space, version, enabled]);
  return { debts, error };
}

/** La categoría de los abonos: «Pago de deudas»; si la desactivaron o borraron, la primera necesidad. */
export function debtCategory(categories: Category[]): Category | undefined {
  const expense = categories.filter((c) => c.active && c.group !== "INGRESO");
  return expense.find((c) => c.name === DEBT_CATEGORY) ?? expense.find((c) => c.group === "NECESIDAD") ?? expense[0];
}

/** La hoja de anotar llena para abonar a una deuda (por defecto, todo lo que falta). */
export function payPreset(d: Debt, categories: Category[], amount = d.remaining): NonNullable<EntrySheetProps["preset"]> {
  return {
    categoryId: debtCategory(categories)?.id ?? "", description: `${d.creditor} · ${d.concept}`.slice(0, 200),
    amount: Math.min(amount, d.remaining), max: d.remaining, currency: d.currency, placeId: null, debtId: d.id,
    title: `Abonar a ${d.creditor}`,
  };
}

/** Lo que falta de la cuota de este mes (0 si ya se abonó o no hay cuota). */
export function installmentLeft(d: Debt): number {
  if (d.installment == null || d.remaining <= 0) return 0;
  return Math.max(0, Math.min(d.installment - d.paidThisMonth, d.remaining));
}

/** Lo que falta, sumado por moneda: «₡85.000» o «₡85.000 y $40,00». */
export function pendingText(debts: Debt[]): string {
  const sum = (c: Currency) => debts.filter((d) => d.currency === c).reduce((s, d) => s + d.remaining, 0);
  return (["CRC", "USD"] as const).filter((c) => sum(c) > 0).map((c) => money(sum(c), c, true)).join(" y ");
}

/** «Mamá», «Mamá y Juan», «Mamá, Juan y Ana». */
export function namesText(names: string[]): string {
  if (names.length <= 1) return names[0] ?? "";
  return `${names.slice(0, -1).join(", ")} y ${names[names.length - 1]}`;
}
