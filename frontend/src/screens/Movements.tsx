import { useEffect, useMemo, useRef, useState } from "react";
import { api, messageOf } from "../api";
import { EntrySheet } from "../components/EntrySheet";
import { Shell } from "../components/Shell";
import { dayLabel, initials, money } from "../format";
import { gsap, useGSAP, whenMotionAllowed } from "../motion";
import { useApp } from "../state";
import type { Transaction, TransactionPage } from "../types";

const PAGE_SIZE = 50;

/** Los movimientos del mes como asientos de libreta, agrupados por día. */
export function Movements() {
  const { space, month, version, categories, memberName } = useApp();
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

  useGSAP(() => whenMotionAllowed(() => {
    gsap.from(".entry", { autoAlpha: 0, y: 6, stagger: 0.012, duration: 0.2 });
  }), { scope: list, dependencies: [items?.length === 0, month.year, month.month] });

  const byDay = useMemo(() => {
    const groups = new Map<string, Transaction[]>();
    for (const t of items ?? []) groups.set(t.date, [...(groups.get(t.date) ?? []), t]);
    return [...groups.entries()];
  }, [items]);

  const category = (id: string) => categories.find((c) => c.id === id);
  const shared = (space?.members.length ?? 0) > 1;

  return (
    <Shell withMonth onAdd={() => setAdding(true)}>
      <div ref={list}>
        {error && <p className="form-error" role="alert">{error}</p>}
        {items === null && !error && <div className="skeleton" aria-hidden="true">{Array.from({ length: 8 }, (_, i) => <div key={i} className="sk-row" />)}</div>}
        {items?.length === 0 && (
          <section className="empty">
            <p className="empty-title">Ningún movimiento este mes.</p>
            <p>Lo que anoten va a aparecer aquí, día por día.</p>
          </section>
        )}

        {byDay.map(([day, entries]) => (
          <section key={day} className="day" aria-label={dayLabel(day)}>
            <h2 className="day-title">{dayLabel(day)}</h2>
            <ul className="entries">
              {entries.map((t) => {
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
                          {t.paymentMethod && <> · {t.paymentMethod}</>}
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

      {(adding || editing) && (
        <EntrySheet editing={editing} onClose={() => { setAdding(false); setEditing(null); }} />
      )}
    </Shell>
  );
}
