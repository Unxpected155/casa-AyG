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
  /** Si es un ingreso con diezmo anotado antes de V25: el gasto del diezmo. */
  titheId: string | null;
  /** Ingreso que debe diezmo (V25): suma a «Diezmo por dar». */
  titheOwed: boolean;
  /** El pago fijo que se pagó con este movimiento. */
  recurringId: string | null;
  /** Lo anotó el cierre del mes de la casa: no se edita a mano (se reabre el mes). */
  fromClose: boolean;
  /** La deuda que abona (V22), o null. */
  debtId: string | null;
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

/** Una deuda con una persona (V22). Lo que falta sale de los abonos; nunca se guarda. */
export interface Debt {
  id: string;
  creditor: string;
  concept: string;
  amount: number;
  currency: Currency;
  borrowedOn: string;
  dueDate: string | null;
  paid: number;
  remaining: number;
  payments: number;
  lastPaidOn: string | null;
  settledOn: string | null;
  createdBy: string;
  /** Lo pagado antes de anotarla (no es un gasto de ningún mes). */
  paidBefore: number;
  /** Lo que la persona perdonó, y cuándo. */
  forgiven: number;
  forgivenOn: string | null;
  /** Cuota acordada y su día (opcionales); `installmentDueDate`: el día de la cuota este mes. */
  installment: number | null;
  installmentDay: number | null;
  installmentDueDate: string | null;
  /** Abonos del mes en curso. */
  paidThisMonth: number;
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
  /** La meta para la que está apartado este dinero (si alguna). */
  goalName: string | null;
  /** Lugar de ahorro (V24): su saldo es «Ahorrado», no «Disponible». */
  savings: boolean;
}

/** «¿Voy bien?» (GET /api/spaces/{id}/health). Montos en colones salvo los de cada deuda. */
export interface Health {
  today: string;
  money: { saved: number; available: number; pending: number; leftover: number; missingRate: boolean };
  savingsRate: { month: number | null; savedMonth: number; incomeMonth: number; average: number | null; averageMonths: number };
  emergency: {
    goalId: string; name: string; currency: Currency; saved: number;
    monthlyExpenses: number | null; monthsCovered: number | null; targetMonths: number;
  } | null;
  debts: {
    remainingCrc: number;
    items: {
      id: string; creditor: string; currency: Currency; remaining: number; monthlyAverage: number;
      monthlyNeeded: number | null; dueDate: string | null; estimatedEnd: string | null;
      status: "A_TIEMPO" | "ATRASADA" | "SIN_FECHA";
    }[];
  };
  goals: { judged: number; onTime: number; behind: string[]; withoutDate: number };
  /** Diezmo por moneda: lo debido, lo dado y lo que falta dar (se arrastra de mes a mes). */
  tithe: { currency: Currency; owed: number; given: number; pending: number }[];
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

/** Las condiciones de un plazo del CDP. */
export interface CdpTerms {
  rate: number;
  termDays: number;
  dayBasis: number;
  taxPct: number;
}

/**
 * Lo que pasa al renovar un CDP (calculado por el servidor, igual que el banco). Los intereses
 * salen de `earned` (el plazo que terminó); `next` es el plazo que empieza.
 */
export interface Renewal {
  principal: number;
  earned: CdpTerms;
  grossInterest: number;
  tax: number;
  netInterest: number;
  tithe: number;
  contribution: number;
  withdrawal: number;
  withdrawTo: string | null;
  newPrincipal: number;
  next: CdpTerms;
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
  /** Sumas de lo filtrado (o de todo el mes sin filtros). */
  totals: { incomeCrc: number; expenseCrc: number; incomeUsd: number; expenseUsd: number };
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
  /** Lo ahorrado (movido a un CDP o a una meta): no es gasto, pero cuenta en el % de ahorro. */
  saved: Totals;
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

/** La casa (espacio familiar, Parte 4). */
export interface Partner {
  userId: string;
  displayName: string;
  crc: number;
  usd: number;
  me: boolean;
}

export interface HouseholdSettings {
  personalFloor: number;
  bonusPct: number;
}

export interface MonthClosed {
  year: number;
  month: number;
  surplus: number;
  bonusPct: number;
  perPerson: number;
}

export interface Household {
  settings: HouseholdSettings;
  partners: Partner[];
  myDestination: string | null;
  myPersonalSpace: string | null;
  closes: MonthClosed[];
  /** Mes en que se creó la casa ("2026-11"). */
  since: string;
}

export interface ClosePreview {
  year: number;
  month: number;
  income: number;
  expenses: number;
  /** Lo movido a un CDP o a una meta de la casa ese mes: sale antes de calcular lo que sobró. */
  saved: number;
  surplus: number;
  personalFloor: number;
  bonusPct: number;
  bonus: number;
  perPerson: number;
  members: number;
  rest: number;
  closed: MonthClosed | null;
}
