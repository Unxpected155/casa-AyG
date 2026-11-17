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
