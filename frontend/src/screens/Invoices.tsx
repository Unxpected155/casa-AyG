import { useEffect, useMemo, useState } from "react";
import { api, messageOf } from "../api";
import { Icon } from "../components/Icon";
import { Mailbox } from "../components/Mailbox";
import { Shell } from "../components/Shell";
import { dayLabel, money } from "../format";
import { useApp } from "../state";
import type { Category, Currency, Place, Space } from "../types";

export interface PendingInvoice {
  id: string;
  kind: "FACTURA" | "NOTA_CREDITO" | "TIQUETE";
  date: string;
  merchant: string;
  currency: Currency;
  total: number;
  /** La firma se confirmó contra la autoridad oficial. */
  verified: boolean;
  /** Llegó con la respuesta «aceptado» de Hacienda (un tiquete puede no traerla). */
  haciendaConfirmed: boolean;
  suggestion: { spaceId: string; categoryId: string; placeId: string } | null;
}

const KIND_LABEL: Record<PendingInvoice["kind"], string> = {
  FACTURA: "Factura",
  TIQUETE: "Tiquete",
  NOTA_CREDITO: "Nota de crédito",
};

/**
 * La variante del correo de la casa de esta persona (casa+usuario@...): dada en las tiendas,
 * sus tiquetes le llegan a ella aunque no traigan cédula.
 */
function TicketAddress() {
  const { session } = useApp();
  const [mailbox, setMailbox] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    api.get<{ connected: boolean; email: string | null }>("/api/invoices/mailbox")
      .then((s) => setMailbox(s.connected ? s.email : null))
      .catch(() => setMailbox(null));
  }, []);

  if (!mailbox || session.kind !== "ready" || !mailbox.includes("@")) return null;
  const at = mailbox.lastIndexOf("@");
  const address = `${mailbox.slice(0, at)}+${session.me.username}${mailbox.slice(at)}`;

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(address);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
    } catch {
      setCopied(false);
    }
  };

  return (
    <div className="ticket-address">
      <h2 className="ticket-address-title">Tu correo para tiquetes</h2>
      <div className="ticket-address-row">
        <span className="mono ticket-address-value">{address}</span>
        <button type="button" className="button ghost small" onClick={() => void copy()}>
          {copied ? "Copiado" : "Copiar"}
        </button>
      </div>
      <p className="muted small">Dalo en las tiendas cuando te pidan correo: tus tiquetes llegan aquí, a tu nombre.</p>
      <span className="visually-hidden" role="status">{copied ? "Correo copiado" : ""}</span>
    </div>
  );
}

/** Facturas y tiquetes electrónicos que llegaron a mi nombre y esperan que los confirme o los descarte. */
export function Invoices() {
  const { spaces, space, touch } = useApp();
  const [items, setItems] = useState<PendingInvoice[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const writable = spaces.filter((s) => !s.archived);

  const load = () => api.get<PendingInvoice[]>("/api/invoices")
    .then((list) => { setItems(list); setError(null); })
    .catch((e) => setError(messageOf(e, "No se pudieron cargar las facturas.")));
  useEffect(() => {
    void load();
  }, []);

  const done = (id: string) => {
    setItems((list) => list?.filter((i) => i.id !== id) ?? null);
    touch();
  };

  return (
    <Shell>
      <h1 className="page-title">Por revisar</h1>
      <p className="muted">
        Facturas y tiquetes electrónicos a tu nombre. Nada se anota solo: tú decides dónde va cada uno.
      </p>
      <TicketAddress />
      {error && <p className="form-error" role="alert">{error}</p>}
      {items && items.length === 0 && <p className="empty">No hay nada por revisar.</p>}
      <ul className="invoice-list">
        {items?.map((i) => (
          <InvoiceCard key={i.id} invoice={i} spaces={writable} fallbackSpace={space?.id ?? writable[0]?.id}
                       onDone={() => done(i.id)} />
        ))}
      </ul>
      <Mailbox onRead={() => void load()} />
    </Shell>
  );
}

function InvoiceCard({ invoice, spaces, fallbackSpace, onDone }: {
  invoice: PendingInvoice; spaces: Space[]; fallbackSpace: string | undefined; onDone: () => void;
}) {
  const suggested = invoice.suggestion && spaces.some((s) => s.id === invoice.suggestion?.spaceId)
    ? invoice.suggestion : null;
  const [spaceId, setSpaceId] = useState(suggested?.spaceId ?? fallbackSpace ?? "");
  const [categoryId, setCategoryId] = useState(suggested?.categoryId ?? "");
  const [placeId, setPlaceId] = useState(suggested?.placeId ?? "");
  const [categories, setCategories] = useState<Category[]>([]);
  const [places, setPlaces] = useState<Place[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!spaceId) return;
    let cancelled = false;
    Promise.all([
      api.get<Category[]>(`/api/spaces/${spaceId}/categories`),
      api.get<Place[]>(`/api/spaces/${spaceId}/places`),
    ]).then(([c, p]) => {
      if (cancelled) return;
      // Una compra va a un gasto; una devolución (nota de crédito), a un ingreso.
      setCategories(c.filter((x) => x.active && (x.group === "INGRESO") === (invoice.kind === "NOTA_CREDITO")));
      const usable = p.filter((x) => !x.archived && x.currency === invoice.currency);
      setPlaces(usable);
      setCategoryId((current) => (c.some((x) => x.id === current) ? current : ""));
      setPlaceId((current) => usable.some((x) => x.id === current) ? current
        : usable.length === 1 ? (usable[0]?.id ?? "") : "");
    }).catch(() => !cancelled && setError("No se pudieron cargar las categorías y cuentas."));
    return () => {
      cancelled = true;
    };
  }, [spaceId, invoice.currency, invoice.kind]);

  const sortedCategories = useMemo(() => [...categories].sort((a, b) => a.name.localeCompare(b.name, "es")), [categories]);
  const refund = invoice.kind === "NOTA_CREDITO";

  const act = async (action: () => Promise<unknown>, fallback: string) => {
    setBusy(true);
    setError(null);
    try {
      await action();
      onDone();
    } catch (e) {
      setError(messageOf(e, fallback));
      setBusy(false);
    }
  };

  const what = invoice.kind === "TIQUETE" ? "el tiquete" : "la factura";
  const confirm = () => act(() => api.post(`/api/invoices/${invoice.id}/confirm`, { spaceId, categoryId, placeId }),
    `No se pudo anotar ${what}.`);
  const discard = () => act(() => api.post(`/api/invoices/${invoice.id}/discard`), `No se pudo descartar ${what}.`);

  return (
    <li className="invoice">
      <p className="invoice-kind muted small">{KIND_LABEL[invoice.kind]} · {dayLabel(invoice.date)}</p>
      <div className="invoice-head">
        <span className="invoice-merchant">{invoice.merchant}</span>
        <span className="invoice-total figure">{money(invoice.total, invoice.currency, true)}</span>
      </div>
      <p className={`invoice-trust${invoice.verified && invoice.haciendaConfirmed ? " ok" : ""}`}>
        {!invoice.haciendaConfirmed
          ? <><Icon name="alert" size={16} /> Sin confirmar por Hacienda: revisa que reconozcas esta compra</>
          : invoice.verified
            ? <><Icon name="check" size={16} /> Firma verificada</>
            : <><Icon name="alert" size={16} /> Revisa que reconozcas esta compra</>}
      </p>
      {refund && (
        <p className="muted small"><strong>Devolución</strong> (nota de crédito): anótala como ingreso o descártala.</p>
      )}
      {suggested && <p className="hint small">Lo mismo que la última vez en este comercio.</p>}
      {error && <p className="form-error" role="alert">{error}</p>}
      <div className="invoice-fields">
        {spaces.length > 1 && (
          <label className="field">
            <span>Espacio</span>
            <select value={spaceId} onChange={(e) => setSpaceId(e.target.value)}>
              {spaces.map((s) => <option key={s.id} value={s.id}>{s.kind === "FAMILIA" ? "La casa" : "Personal"}</option>)}
            </select>
          </label>
        )}
        <label className="field">
          <span>Categoría</span>
          <select value={categoryId} onChange={(e) => setCategoryId(e.target.value)}>
            <option value="">Elige…</option>
            {sortedCategories.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
        </label>
        <label className="field">
          <span>Cuenta</span>
          <select value={placeId} onChange={(e) => setPlaceId(e.target.value)}>
            <option value="">Elige…</option>
            {places.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
          </select>
        </label>
      </div>
      {places.length === 0 && spaceId && (
        <p className="hint small">No hay cuentas en {invoice.currency === "USD" ? "dólares" : "colones"} en este espacio.</p>
      )}
      <div className="actions">
        <button type="button" className="button ghost" onClick={discard} disabled={busy}>Descartar</button>
        <button type="button" className="button primary" onClick={confirm}
                disabled={busy || !spaceId || !categoryId || !placeId}>
          Anotar
        </button>
      </div>
    </li>
  );
}
