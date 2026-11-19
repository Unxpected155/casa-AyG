import { useEffect, useMemo, useRef, useState } from "react";
import { api, messageOf } from "../api";
import { EntrySheet } from "../components/EntrySheet";
import { Icon } from "../components/Icon";
import { Shell } from "../components/Shell";
import { dayLabel, initials, money } from "../format";
import { gsap, useGSAP, whenMotionAllowed } from "../motion";
import { useApp } from "../state";
import type { Transaction, TransactionPage, Transfer } from "../types";

interface Filters {
  q: string;
  categoryId: string;
  placeId: string;
  all: boolean;
}

const NO_FILTERS: Filters = { q: "", categoryId: "", placeId: "", all: false };

const PAGE_SIZE = 50;

/** Los movimientos del mes como asientos de libreta, agrupados por día. */
export function Movements() {
  const { space, month, version, categories, places, memberName } = useApp();
  const [transfers, setTransfers] = useState<Transfer[]>([]);
  const [viewingTransfer, setViewingTransfer] = useState<Transfer | null>(null);
  const [items, setItems] = useState<Transaction[] | null>(null);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<Transaction | null>(null);
  const [adding, setAdding] = useState(false);
  const [filters, setFilters] = useState<Filters>(NO_FILTERS);
  const [query, setQuery] = useState(""); // lo que se escribe; se aplica con una pausa corta
  const [totals, setTotals] = useState<TransactionPage["totals"] | null>(null);
  const list = useRef<HTMLDivElement>(null);
  const filtering = filters.q !== "" || filters.categoryId !== "" || filters.placeId !== "" || filters.all;
  const [showFilters, setShowFilters] = useState(false);
  const activeFilters = [filters.categoryId, filters.placeId].filter(Boolean).length + (filters.all ? 1 : 0);

  useEffect(() => {
    const timer = window.setTimeout(() => setFilters((f) => (f.q === query.trim() ? f : { ...f, q: query.trim() })), 300);
    return () => window.clearTimeout(timer);
  }, [query]);

  useEffect(() => {
    setPage(0);
  }, [space, month.year, month.month, version, filters]);

  useEffect(() => {
    if (!space) return;
    let cancelled = false;
    setError(null);
    const params = new URLSearchParams({ year: String(month.year), month: String(month.month), page: String(page), size: String(PAGE_SIZE) });
    if (filters.q) params.set("q", filters.q);
    if (filters.categoryId) params.set("categoryId", filters.categoryId);
    if (filters.placeId) params.set("placeId", filters.placeId);
    if (filters.all) params.set("all", "true");
    api.get<TransactionPage>(`/api/spaces/${space.id}/transactions?${params}`)
      .then((result) => {
        if (cancelled) return;
        setItems((prev) => (page === 0 || !prev ? result.items : [...prev, ...result.items]));
        setTotal(result.total);
        setTotals(result.totals);
      })
      .catch((e) => !cancelled && setError(messageOf(e, "No se pudieron cargar los movimientos.")));
    return () => {
      cancelled = true;
    };
  }, [space, month.year, month.month, page, version, filters]);

  useEffect(() => {
    if (!space) return;
    let cancelled = false;
    api.get<Transfer[]>(`/api/spaces/${space.id}/transfers?year=${month.year}&month=${month.month}`)
      .then((list) => !cancelled && setTransfers(list))
      .catch(() => !cancelled && setTransfers([]));
    return () => {
      cancelled = true;
    };
  }, [space, month.year, month.month, version]);

  useGSAP(() => whenMotionAllowed(() => {
    gsap.from(".entry", { autoAlpha: 0, y: 6, stagger: 0.012, duration: 0.2 });
  }), { scope: list, dependencies: [items?.length === 0, month.year, month.month] });

  // Movimientos y dinero movido, juntos por día (los días más recientes primero).
  const byDay = useMemo(() => {
    const groups = new Map<string, Row[]>();
    const add = (date: string, row: Row) => groups.set(date, [...(groups.get(date) ?? []), row]);
    for (const t of items ?? []) add(t.date, { kind: "tx", t });
    // El dinero movido se filtra aquí: por lugar (origen o destino) y por nota. Con categoría o en
    // toda la historia no aplica (no tiene categoría, y solo se cargó el del mes).
    const shownTransfers = filters.categoryId || filters.all ? [] : transfers.filter((x) =>
      (!filters.placeId || x.fromPlace === filters.placeId || x.toPlace === filters.placeId)
      && (!filters.q || (x.description ?? "").toLowerCase().includes(filters.q.toLowerCase())));
    for (const x of shownTransfers) add(x.date, { kind: "transfer", x });
    return [...groups.entries()].sort(([a], [b]) => b.localeCompare(a));
  }, [items, transfers, filters]);
  const placeName = (id: string | null) => places.find((p) => p.id === id)?.name;

  const category = (id: string) => categories.find((c) => c.id === id);
  const shared = (space?.members.length ?? 0) > 1;

  return (
    <Shell withMonth onAdd={() => setAdding(true)}>
      <div ref={list}>
        {error && <p className="form-error" role="alert">{error}</p>}
        {items === null && !error && <div className="skeleton" aria-hidden="true">{Array.from({ length: 8 }, (_, i) => <div key={i} className="sk-row" />)}</div>}
        <search className="movements-search" aria-label="Buscar movimientos">
          <div className="search-bar">
            <label className="visually-hidden" htmlFor="mq">Buscar</label>
            <input id="mq" type="search" value={query} maxLength={100} placeholder="Buscar: uber, súper, alquiler…"
                   onChange={(e) => setQuery(e.target.value)} />
            <button type="button" className="button secondary small" aria-expanded={showFilters}
                    aria-controls="movement-filters" onClick={() => setShowFilters(!showFilters)}>
              Filtros{activeFilters > 0 && <span className="filter-count"> · {activeFilters}</span>}
            </button>
          </div>
          {showFilters && <div id="movement-filters" className="filters-panel">
          <div className="field-row">
            <label className="field grow">
              <span>Categoría</span>
              <select value={filters.categoryId} onChange={(e) => setFilters({ ...filters, categoryId: e.target.value })}>
                <option value="">Todas</option>
                {categories.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
              </select>
            </label>
            {places.length > 0 && (
              <label className="field grow">
                <span>Lugar</span>
                <select value={filters.placeId} onChange={(e) => setFilters({ ...filters, placeId: e.target.value })}>
                  <option value="">Todos</option>
                  {places.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
                </select>
              </label>
            )}
          </div>
          <div className="search-foot">
            <label className="check">
              <input type="checkbox" checked={filters.all} onChange={(e) => setFilters({ ...filters, all: e.target.checked })} />
              <span>En toda la historia</span>
            </label>
            {filtering && (
              <button type="button" className="button ghost small" onClick={() => { setQuery(""); setFilters(NO_FILTERS); }}>
                Limpiar
              </button>
            )}
          </div>
          </div>}
          {filtering && totals && items && (
            <p className="search-result" aria-live="polite">
              {total === 0 ? "No encontré movimientos." : <>
                {total} {total === 1 ? "movimiento" : "movimientos"}
                {totals.expenseCrc > 0 && <> · salieron <strong className="figure">{money(totals.expenseCrc, "CRC")}</strong></>}
                {totals.expenseUsd > 0 && <> y <strong className="figure">{money(totals.expenseUsd, "USD")}</strong></>}
                {totals.incomeCrc > 0 && <> · entraron <strong className="figure">{money(totals.incomeCrc, "CRC")}</strong></>}
                {totals.incomeUsd > 0 && <> y <strong className="figure">{money(totals.incomeUsd, "USD")}</strong></>}
                {filters.all ? " en toda la historia." : " este mes."}
              </>}
            </p>
          )}
        </search>

        {!filtering && items?.length === 0 && transfers.length === 0 && (
          <section className="empty">
            <p className="empty-title">Ningún movimiento este mes.</p>
            <p>Lo que anoten va a aparecer aquí, día por día.</p>
          </section>
        )}

        {byDay.map(([day, entries]) => (
          <section key={day} className="day" aria-label={dayLabel(day)}>
            <h2 className="day-title">{dayLabel(day)}</h2>
            <ul className="entries">
              {entries.map((row) => {
                if (row.kind === "transfer") {
                  const x = row.x;
                  return (
                    <li key={x.id} className="entry">
                      <button type="button" className="entry-button" onClick={() => setViewingTransfer(x)}
                              disabled={space?.archived}>
                        <span className="entry-text">
                          <span className="entry-desc">
                            <Icon name="swap" size={16} /> {x.description ?? `A ${placeName(x.toPlace) ?? "otro lugar"}`}
                          </span>
                          <span className="entry-meta">
                            Movido de {placeName(x.fromPlace) ?? "un lugar"} a {placeName(x.toPlace) ?? "otro lugar"}
                          </span>
                        </span>
                        <span className="entry-amount figure moved">{money(x.amount, x.currency, true)}</span>
                      </button>
                    </li>
                  );
                }
                const t = row.t;
                const c = category(t.categoryId);
                const income = c?.group === "INGRESO";
                return (
                  <li key={t.id} className="entry">
                    <button type="button" className="entry-button" onClick={() => setEditing(t)}
                            disabled={space?.archived}>
                      <span className="entry-text">
                        <span className="entry-desc">{t.description}</span>
                        <span className="entry-meta">
                          {c?.name ?? "Categoría"}
                          {placeName(t.placeId) ? <> · {placeName(t.placeId)}</> : t.paymentMethod && <> · {t.paymentMethod}</>}
                          {t.beforeMerge && <> · antes de la boda</>}
                        </span>
                      </span>
                      {shared && (
                        <span className="who" title={`Lo anotó ${memberName(t.createdBy)}`}>
                          <span aria-hidden="true">{initials(memberName(t.createdBy))}</span>
                          <span className="visually-hidden">Lo anotó {memberName(t.createdBy)}</span>
                        </span>
                      )}
                      <span className={`entry-amount figure ${income ? "income" : ""}`}>
                        {income ? "+" : ""}{money(t.amount, t.currency, true)}
                      </span>
                    </button>
                  </li>
                );
              })}
            </ul>
          </section>
        ))}

        {items && items.length < total && (
          <button type="button" className="button ghost load-more" onClick={() => setPage(page + 1)}>
            Ver más ({total - items.length})
          </button>
        )}
      </div>

      {(adding || editing || viewingTransfer) && (
        <EntrySheet editing={editing} editingTransfer={viewingTransfer}
                    onClose={() => { setAdding(false); setEditing(null); setViewingTransfer(null); }} />
      )}
    </Shell>
  );
}

type Row = { kind: "tx"; t: Transaction } | { kind: "transfer"; x: Transfer };
