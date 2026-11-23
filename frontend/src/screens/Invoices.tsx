import { useEffect, useMemo, useState } from "react";
import { api, messageOf } from "../api";
import { Shell } from "../components/Shell";
import { dayLabel, money } from "../format";
import { useApp } from "../state";
import type { Category, Currency, Place, Space } from "../types";

export interface PendingInvoice {
  id: string;
  kind: "FACTURA" | "NOTA_CREDITO";
  date: string;
  merchant: string;
  currency: Currency;
  total: number;
  suggestion: { spaceId: string; categoryId: string; placeId: string } | null;
}

/** Facturas electrónicas que llegaron a mi nombre y esperan que las confirme o las descarte. */
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
        Facturas electrónicas que llegaron a tu nombre. Ya se comprobó que son auténticas: tú decides dónde van.
      </p>
      {error && <p className="form-error" role="alert">{error}</p>}
      {items && items.length === 0 && <p className="empty">No hay facturas por revisar.</p>}
      <ul className="invoice-list">
        {items?.map((i) => (
          <InvoiceCard key={i.id} invoice={i} spaces={writable} fallbackSpace={space?.id ?? writable[0]?.id}
                       onDone={() => done(i.id)} />
        ))}
      </ul>
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
      setCategories(c.filter((x) => x.active));
      const usable = p.filter((x) => !x.archived && x.currency === invoice.currency);
      setPlaces(usable);
      setCategoryId((current) => (c.some((x) => x.id === current) ? current : ""));
      setPlaceId((current) => usable.some((x) => x.id === current) ? current
        : usable.length === 1 ? (usable[0]?.id ?? "") : "");
    }).catch(() => !cancelled && setError("No se pudieron cargar las categorías y cuentas."));
    return () => {
      cancelled = true;
    };
  }, [spaceId, invoice.currency]);

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

  const confirm = () => act(() => api.post(`/api/invoices/${invoice.id}/confirm`, { spaceId, categoryId, placeId }),
    "No se pudo anotar la factura.");
  const discard = () => act(() => api.post(`/api/invoices/${invoice.id}/discard`), "No se pudo descartar la factura.");

  return (
    <li className="invoice">
      <div className="invoice-head">
        <span className="invoice-merchant">{invoice.merchant}</span>
        <span className="invoice-total figure">{money(invoice.total, invoice.currency, true)}</span>
      </div>
      <p className="muted small">
        {dayLabel(invoice.date)}
        {refund && <> · <strong>Devolución</strong> (nota de crédito): anótala como ingreso o descártala.</>}
      </p>
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
