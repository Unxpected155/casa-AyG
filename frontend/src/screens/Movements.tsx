import { useEffect, useMemo, useRef, useState } from "react";
import { api, messageOf } from "../api";
import { EntrySheet } from "../components/EntrySheet";
import { Icon } from "../components/Icon";
import { Shell } from "../components/Shell";
import { dayLabel, initials, money } from "../format";
import { gsap, useGSAP, whenMotionAllowed } from "../motion";
import { useApp } from "../state";
import type { Transaction, TransactionPage, Transfer } from "../types";

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
  const list = useRef<HTMLDivElement>(null);

  useEffect(() => {
    setPage(0);
  }, [space, month.year, month.month, version]);

  useEffect(() => {
    if (!space) return;
    let cancelled = false;
    setError(null);
    api.get<TransactionPage>(`/api/spaces/${space.id}/transactions?year=${month.year}&month=${month.month}&page=${page}&size=${PAGE_SIZE}`)
      .then((result) => {
        if (cancelled) return;
        setItems((prev) => (page === 0 || !prev ? result.items : [...prev, ...result.items]));
        setTotal(result.total);
      })
      .catch((e) => !cancelled && setError(messageOf(e, "No se pudieron cargar los movimientos.")));
    return () => {
      cancelled = true;
    };
  }, [space, month.year, month.month, page, version]);

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
    for (const x of transfers) add(x.date, { kind: "transfer", x });
    return [...groups.entries()].sort(([a], [b]) => b.localeCompare(a));
  }, [items, transfers]);
  const placeName = (id: string | null) => places.find((p) => p.id === id)?.name;

  const category = (id: string) => categories.find((c) => c.id === id);
  const shared = (space?.members.length ?? 0) > 1;

  return (
    <Shell withMonth onAdd={() => setAdding(true)}>
      <div ref={list}>
        {error && <p className="form-error" role="alert">{error}</p>}
        {items === null && !error && <div className="skeleton" aria-hidden="true">{Array.from({ length: 8 }, (_, i) => <div key={i} className="sk-row" />)}</div>}
        {items?.length === 0 && transfers.length === 0 && (
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
