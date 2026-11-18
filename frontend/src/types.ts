// Tipos de la API (ver backend/src/main/java/com/casaayg).

export type Currency = "CRC" | "USD";
export type Group = "INGRESO" | "NECESIDAD" | "DESEO" | "AHORRO";
export const GROUPS: Group[] = ["INGRESO", "NECESIDAD", "DESEO", "AHORRO"];

export interface Me {
  id: string;
  username: string;
  displayName: string;
  passkeys: number;
  recoveryCodesLeft: number;
}

export interface Member {
  id: string;
  displayName: string;
}

export interface Space {
  id: string;
  kind: "PERSONAL" | "FAMILIA";
  name: string;
  archived: boolean;
  members: Member[];
}

export interface Category {
  id: string;
  name: string;
  group: Group;
  active: boolean;
}

export interface Transaction {
  id: string;
  categoryId: string;
  date: string;
  description: string;
  amount: number;
  currency: Currency;
  paymentMethod: string | null;
  source: string;
  createdBy: string;
  beforeMerge: boolean;
  placeId: string | null;
  /** Monto bruto de un ingreso (base del diezmo). */
  grossAmount: number | null;
  /** Si es un diezmo: el ingreso del que salió. */
  titheOf: string | null;
  /** Si es un ingreso con diezmo: el gasto del diezmo. */
  titheId: string | null;
  /** El pago fijo que se pagó con este movimiento. */
  recurringId: string | null;
}

export interface HistoryMonth {
  month: string; // "2026-11"
  income: number;
  expenses: number;
  saved: number;
  savingsRate: number | null;
}

export interface Trend {
  categoryId: string;
  name: string;
  group: Group;
  current: number;
  average: number | null;
  expected: number | null;
  delta: number | null;
  deltaPct: number | null;
  series: number[];
}

export interface History {
  month: string;
  partial: boolean;
  elapsed: number;
  baselineMonths: number;
  months: HistoryMonth[];
  categories: Trend[];
}

export type GoalStatus = "ACTIVA" | "PAUSADA" | "CUMPLIDA" | "ARCHIVADA";

/** Una meta con su avance (calculado por el servidor con el saldo de sus lugares). */
export interface Goal {
  id: string;
  name: string;
  kind: "NORMAL" | "EMERGENCIA";
  targetAmount: number | null;
  emergencyMonths: number | null;
  currency: Currency;
  targetDate: string | null;
  priority: number;
  status: GoalStatus;
  target: number;
  saved: number;
  remaining: number;
  percent: number;
  monthlyNeeded: number | null;
  monthlyPace: number;
  eta: string | null;
  monthlyExpenses: number;
  placeIds: string[];
  parts: { name: string; amount: number }[];
}

/** Un pago fijo visto en un mes (fecha de ese mes y, si ya se pagó, el movimiento). */
export interface Recurring {
  id: string;
  name: string;
  categoryId: string;
  placeId: string | null;
  dueDay: number | null;
  expectedAmount: number | null;
  currency: Currency;
  notifyDaysBefore: number;
  active: boolean;
  dueDate: string | null;
  paidTransactionId: string | null;
  paidAmount: number | null;
  paidOn: string | null;
}

export type PlaceKind = "BANCO" | "SOBRE" | "CDP" | "EFECTIVO";

/** Un lugar donde vive el dinero. `balance` es el saldo a hoy, calculado por el servidor. */
export interface Place {
  id: string;
  name: string;
  kind: PlaceKind;
  currency: Currency;
  openingBalance: number;
  openingDate: string;
  parentId: string | null;
  archived: boolean;
  balance: number;
  /** Solo en un CDP; sus campos quedan null hasta configurarlo. */
  cdp: CdpData | null;
}

export interface CdpData {
  rate: number | null;
  termDays: number | null;
  dayBasis: number | null;
  taxPct: number | null;
  maturesOn: string | null;
  payoutPlace: string | null;
  lastContribution: number | null;
}

/** Lo que pasa al renovar un CDP (calculado por el servidor, igual que el banco). */
export interface Renewal {
  principal: number;
  rate: number;
  grossInterest: number;
  tax: number;
  netInterest: number;
  tithe: number;
  contribution: number;
  newPrincipal: number;
  maturesOn: string;
  nextMaturity: string;
  payoutPlace: string;
  currency: Currency;
}

/** Dinero movido entre dos lugares: no es gasto ni ingreso. */
export interface Transfer {
  id: string;
  fromPlace: string;
  toPlace: string;
  date: string;
  amount: number;
  currency: Currency;
  description: string | null;
  batchId: string | null;
  createdBy: string;
}

export interface Suggestion {
  categoryId: string | null;
  placeId: string | null;
  /** Último bruto anotado en la categoría pedida (el salario bruto). */
  lastGross: number | null;
}

export interface TransactionPage {
  items: Transaction[];
  page: number;
  size: number;
  total: number;
}

export interface Totals {
  crc: number;
  usd: number;
  totalUsd: number;
}

export type Status = "OK" | "CERCA" | "AL_LIMITE" | "EXCEDIDO" | "SIN_PRESUPUESTO";

export interface SummaryLine {
  categoryId: string;
  name: string;
  group: Group;
  plannedCrc: number;
  plannedUsd: number | null;
  spentCrc: number;
  spentUsd: number;
  spentTotalUsd: number;
  /** Planeado menos lo gastado, en colones (negativo si se pasó). null sin presupuesto. */
  remainingCrc: number | null;
  status: Status;
}

export interface Summary {
  month: string;
  usdSellRate: number | null;
  missingRates: boolean;
  categories: SummaryLine[];
  byGroup: Record<Group, Totals>;
  income: Totals;
  expenses: Totals;
  percentOfIncome: Partial<Record<Group, number>>;
}

export interface BudgetLine {
  categoryId: string;
  plannedAmount: number;
}

export interface MergeRequest {
  id: string;
  familyName: string;
  status: "PENDIENTE" | "COMPLETADA" | "RECHAZADA" | "CANCELADA" | "VENCIDA";
  iAmRequester: boolean;
  otherPerson: string;
  createdAt: string;
  expiresAt: string;
  familySpaceId: string | null;
}

export interface Passkey {
  id: string;
  label: string;
  created: string | null;
  lastUsed: string | null;
}
