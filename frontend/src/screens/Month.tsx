import { useEffect, useRef, useState, type CSSProperties } from "react";
import { api, messageOf } from "../api";
import { Balance } from "../components/Balance";
import { cdpDue } from "../components/CdpPanel";
import { DueList } from "../components/Recurring";
import { DebtLine } from "./Debts";
import { EntrySheet } from "../components/EntrySheet";
import { Icon } from "../components/Icon";
import { Shell } from "../components/Shell";
import { GROUP_LABEL, money, pct } from "../format";
import { countUp, gsap, useGSAP, whenMotionAllowed } from "../motion";
import { navigate } from "../router";
import { useApp } from "../state";
import { VoyBien } from "../components/VoyBien";
import { showMovements } from "./Movements";
import { GROUPS, type Group, type Health, type MergeRequest, type Summary, type SummaryLine } from "../types";

/** Pantalla principal: el mes como una página de libreta. */
export function Month() {
  const { space, spaces, month, version, places, categories } = useApp();
  const hasHome = spaces.some((s) => s.kind === "FAMILIA" && !s.archived);
  // Si alguien te propuso unir los espacios, se ve aquí (no solo en Perfil → Espacio familiar).
  const [invite, setInvite] = useState<MergeRequest | null>(null);
  useEffect(() => {
    if (hasHome) {
      setInvite(null);
      return;
    }
    let cancelled = false;
    api.get<MergeRequest[]>("/api/merge-requests")
      .then((list) => !cancelled && setInvite(list.find((r) => r.status === "PENDIENTE" && !r.iAmRequester) ?? null))
      .catch(() => !cancelled && setInvite(null));
    return () => {
      cancelled = true;
    };
  }, [hasHome, version]);
  // Facturas electrónicas que llegaron a mi nombre y esperan que las anote o descarte.
  const [pendingInvoices, setPendingInvoices] = useState(0);
  useEffect(() => {
    let cancelled = false;
    api.get<unknown[]>("/api/invoices")
      .then((list) => !cancelled && setPendingInvoices(list.length))
      .catch(() => !cancelled && setPendingInvoices(0));
    return () => {
      cancelled = true;
    };
  }, [version]);
  const dueCdps = places.filter((p) => !p.archived && cdpDue(p));
  const [summary, setSummary] = useState<Summary | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  // «Darlo»: la hoja de anotar ya llena con lo que falta dar de diezmo en esa moneda.
  const [giving, setGiving] = useState<Health["tithe"][number] | null>(null);
  const titheCategory = categories.find((c) => c.name.toLowerCase() === "diezmo" && c.group !== "INGRESO");
  const [showIdle, setShowIdle] = useState(false);
  const ledger = useRef<HTMLDivElement>(null);
  // «¿Voy bien?» y las tres partes del dinero: siempre a hoy, así que solo en el mes en curso.
  const [health, setHealth] = useState<Health | null>(null);
  const now = new Date();
  const viewingNow = month.year === now.getFullYear() && month.month === now.getMonth() + 1;
  useEffect(() => {
    if (!space || !viewingNow) return;
    let cancelled = false;
    api.get<Health>(`/api/spaces/${space.id}/health`)
      .then((h) => !cancelled && setHealth(h))
      .catch(() => !cancelled && setHealth(null));
    return () => {
      cancelled = true;
    };
  }, [space, viewingNow, version]);

  useEffect(() => {
    if (!space) return;
    let cancelled = false;
    setError(null);
    api.get<Summary>(`/api/spaces/${space.id}/summary?year=${month.year}&month=${month.month}`)
      .then((s) => !cancelled && setSummary(s))
      .catch((e) => !cancelled && setError(messageOf(e, "No se pudo cargar el mes.")));
    return () => {
      cancelled = true;
    };
  }, [space, month.year, month.month, version]);

  // Las líneas de avance se llenan como un subrayado a lápiz; el total cuenta una vez.
  // clearProps: al terminar vuelve a mandar el CSS (--fill), no el transform en línea.
  useGSAP(() => {
    if (!summary) return;
    const total = ledger.current?.querySelector<HTMLElement>("[data-count]");
    if (total) countUp(total, Math.round(summary.expenses.crc), (n) => money(n, "CRC"));
    return whenMotionAllowed(() => {
      gsap.from(".group .rule-fill", { scaleX: 0, duration: 0.6, ease: "expo.out", stagger: 0.025, clearProps: "transform" });
    });
  }, { scope: ledger, dependencies: [summary] });

  const today = new Date();
  const isCurrentMonth = month.year === today.getFullYear() && month.month === today.getMonth() + 1;
  const lines = summary?.categories ?? [];
  const isEmpty = summary !== null && lines.every((l) => l.spentTotalUsd === 0 && l.spentCrc === 0 && l.plannedCrc === 0);

  return (
    <Shell withMonth onAdd={() => setAdding(true)}>
      <div ref={ledger} className="ledger" aria-busy={summary === null && !error}>
        {error && <p className="form-error" role="alert">{error}</p>}
        {summary === null && !error && <LedgerSkeleton />}

        {invite && (
          <p className="notice cdp-due">
            <span><strong>{invite.otherPerson}</strong> te propone unir sus finanzas en «{invite.familyName}».</span>
            <button type="button" className="button secondary small" onClick={() => navigate("/boda")}>Ver propuesta</button>
          </p>
        )}

        {pendingInvoices > 0 && (
          <p className="notice cdp-due">
            <span>
              {pendingInvoices === 1 ? "Tienes una factura por revisar." : `Tienes ${pendingInvoices} facturas por revisar.`}
            </span>
            <button type="button" className="button secondary small" onClick={() => navigate("/facturas")}>Revisar</button>
          </p>
        )}

        {summary && isCurrentMonth && health?.tithe.filter((t) => t.pending > 0).map((t) => (
          <p key={`diezmo-${t.currency}`} className="notice cdp-due">
            <span>Diezmo por dar: <strong className="figure">{money(t.pending, t.currency, true)}</strong>.</span>
            <button type="button" className="button secondary small" onClick={() => setGiving(t)}>Darlo</button>
          </p>
        ))}

        {summary && isCurrentMonth && dueCdps.map((p) => (
          <p key={p.id} className="notice cdp-due">
            <span>Tu <strong>{p.name}</strong> venció: toca renovarlo.</span>
            <button type="button" className="button secondary small" onClick={() => navigate("/lugares")}>Renovar</button>
          </p>
        ))}

        {summary && isCurrentMonth && <Balance health={health} />}
        {summary && isCurrentMonth && health && <VoyBien health={health} />}

        {summary && space?.kind === "FAMILIA" && (
          <button type="button" className="button ghost small history-link" onClick={() => navigate("/casa")}>
            <Icon name="home" size={18} /> La casa: lo personal y el cierre del mes
          </button>
        )}
        {summary && space?.kind === "PERSONAL" && hasHome && (
          <button type="button" className="button ghost small history-link"
                  onClick={() => { navigate("/casa"); window.history.replaceState({ focus: "aportar" }, ""); }}>
            <Icon name="home" size={18} /> Aportar a la casa
          </button>
        )}

        {summary && isEmpty && (
          <section className="empty">
            <p className="empty-title">Esta página está en blanco.</p>
            <p>Anota el primer gasto con <strong>Anotar</strong>, o arma el presupuesto del mes.</p>
            <button type="button" className="button secondary" onClick={() => navigate("/plan")}>
              Armar el presupuesto
            </button>
          </section>
        )}
        {/* Lo que hay que pagar no espera a que el mes tenga movimientos. */}
        {summary && isEmpty && isCurrentMonth && <><DueList /><DebtLine /></>}

        {summary && !isEmpty && (
          <>
            <section className="overview" aria-label="Resumen del mes">
              <MonthFlow summary={summary} />
              <p className="overview-detail">
                Si todo fuera en dólares:
                {summary.income.totalUsd > 0 && <> entraron <span className="figure income">{money(summary.income.totalUsd, "USD")}</span> y</>}
                {" "}salieron <span className="figure expense">{money(summary.expenses.totalUsd, "USD")}</span>.
              </p>
              <IncomeSplit summary={summary} />
              <button type="button" className="button ghost small history-link" onClick={() => navigate("/historia")}>
                Comparar con otros meses
              </button>
              <p className="rate">
                {summary.usdSellRate
                  ? <>Dólar a <span className="figure">{money(summary.usdSellRate, "CRC", true)}</span>, venta de referencia del BCCR.</>
                  : <>Todavía no hay tipo de cambio: el total en dólares cuenta solo los dólares.</>}
                {summary.missingRates && " Algunos movimientos no tienen tipo de cambio de su fecha."}
              </p>
            </section>

            {isCurrentMonth && <DueList />}
            {isCurrentMonth && <DebtLine />}

            {GROUPS.map((group) => (
              <GroupSection
                key={group}
                group={group}
                lines={lines.filter((l) => l.group === group)}
                showIdle={showIdle}
              />
            ))}

            {lines.some(isIdle) && (
              <button type="button" className="button ghost idle-toggle" onClick={() => setShowIdle(!showIdle)}>
                {showIdle ? "Ocultar categorías sin movimientos" : `Ver ${lines.filter(isIdle).length} categorías sin movimientos`}
              </button>
            )}
          </>
        )}
      </div>

      {adding && <EntrySheet onClose={() => setAdding(false)} />}
      {giving && (
        <EntrySheet
          preset={{ categoryId: titheCategory?.id ?? "", description: "Diezmo", amount: giving.pending,
            currency: giving.currency, placeId: null, title: "Dar el diezmo" }}
          onClose={() => setGiving(null)}
        />
      )}
    </Shell>
  );
}

/**
 * El mes en colones, que es como se piensa el dinero aquí: lo que entró, lo que salió y lo
 * que queda. Los dólares van aparte, sin convertir, si hubo movimientos en dólares.
 */
function MonthFlow({ summary }: { summary: Summary }) {
  // Se redondea antes de restar, para que la frase cuadre a la vista (sin ₡1 de diferencia).
  const inCrc = Math.round(summary.income.crc);
  const outCrc = Math.round(summary.expenses.crc);
  // Lo apartado (al CDP o a una meta) no se gastó, pero tampoco está libre: se resta aparte.
  const savedCrc = Math.round(summary.saved.crc);
  const left = inCrc - outCrc - savedCrc;
  const { income, expenses } = summary;
  return (
    <>
      <p className="overview-line">
        {income.crc > 0 ? (
          <>
            Entraron <span className="figure income">{money(inCrc, "CRC")}</span> y salieron{" "}
            <strong data-count className="figure expense">{money(outCrc, "CRC")}</strong>
            {savedCrc > 0 && <>; apartaste <span className="figure">{money(savedCrc, "CRC")}</span></>}
            {left >= 0
              ? <>: te quedan <strong className="figure">{money(left, "CRC")}</strong> del mes.</>
              : savedCrc > 0
                ? <>: entre gastos y ahorro van <strong className="figure">{money(-left, "CRC")}</strong> más de lo que entró.</>
                : <>: gastaste <strong className="figure">{money(-left, "CRC")}</strong> más de lo que entró.</>}
          </>
        ) : (
          <>Van <strong data-count className="figure expense">{money(outCrc, "CRC")}</strong> en gastos este mes.</>
        )}
      </p>
      {(income.usd > 0 || expenses.usd > 0) && (
        <p className="overview-detail">
          En dólares:
          {income.usd > 0 && <> entraron <span className="figure income">{money(income.usd, "USD")}</span></>}
          {income.usd > 0 && expenses.usd > 0 && " y"}
          {expenses.usd > 0 && <> salieron <span className="figure expense">{money(expenses.usd, "USD")}</span></>}.
        </p>
      )}
    </>
  );
}

function isIdle(line: SummaryLine): boolean {
  return line.plannedCrc === 0 && line.spentCrc === 0 && line.spentUsd === 0;
}

function GroupSection({ group, lines, showIdle }: { group: Group; lines: SummaryLine[]; showIdle: boolean }) {
  const visible = lines.filter((l) => showIdle || !isIdle(l));
  if (visible.length === 0) return null;
  return (
    <section className="group" aria-labelledby={`g-${group}`}>
      <h2 id={`g-${group}`} className="group-title">{GROUP_LABEL[group]}</h2>
      <ul className="rows">
        {visible.map((line) => <CategoryRow key={line.categoryId} line={line} income={group === "INGRESO"} />)}
      </ul>
    </section>
  );
}

/** Lo que dice cada renglón: claro con el dinero, sin alarmar (PRODUCT.md, principio 2). */
function statusText(line: SummaryLine): string | null {
  const left = Math.abs(line.remainingCrc ?? 0);
  if (line.group === "AHORRO") {
    switch (line.status) {
      case "OK":
      case "CERCA": return `faltan ${money(left, "CRC")} por apartar`;
      case "AL_LIMITE": return "ahorro del mes cumplido";
      case "EXCEDIDO": return `apartaste ${money(left, "CRC")} más de lo planeado`;
      default: return null;
    }
  }
  switch (line.status) {
    case "CERCA": return `quedan ${money(left, "CRC")}`;
    case "AL_LIMITE": return "justo lo planeado";
    case "EXCEDIDO": return `te pasaste por ${money(left, "CRC")}`;
    default: return null;
  }
}

function CategoryRow({ line, income }: { line: SummaryLine; income: boolean }) {
  const spent = [
    line.spentCrc > 0 || line.spentUsd === 0 ? money(line.spentCrc, "CRC") : null,
    line.spentUsd > 0 ? money(line.spentUsd, "USD") : null,
  ].filter(Boolean).join(" + ");

  // El avance se calcula en colones, igual que el estado del servidor.
  const ratio = line.plannedCrc > 0 && line.remainingCrc !== null
    ? (line.plannedCrc - line.remainingCrc) / line.plannedCrc
    : 0;
  const fill = Math.min(ratio, 1);
  // En el ahorro, llegar o pasarse es bueno: verde, sin alarma.
  const savedEnough = line.group === "AHORRO" && (line.status === "EXCEDIDO" || line.status === "AL_LIMITE");
  const tone = income ? "income"
    : savedEnough ? "ok"
    : line.status === "EXCEDIDO" ? "over"
    : line.status === "CERCA" ? "near"
    : line.status === "AL_LIMITE" ? "full"
    : "ok";
  const text = income ? null : statusText(line);

  // Toda la fila lleva a los movimientos de esa categoría en este mes («¿en qué se me fue?»).
  // La línea es decorativa dentro del botón: el porcentaje va dicho en su nombre accesible.
  return (
    <li className="row category-row">
      <button type="button" className="row-button category-link" onClick={() => showMovements({ categoryId: line.categoryId })}
              aria-label={`${line.name}: ${spent}${line.plannedCrc > 0 ? ` de ${money(line.plannedCrc, "CRC")}, ${pct(ratio * 100)} de lo planeado` : ""}${text ? `, ${text}` : ""}. Ver sus movimientos`}>
        <span className="category-body">
          <span className="row-main">
            <span className="row-name">{line.name}</span>
            <span className="row-amounts">
              <span className="figure">{spent}</span>
              {line.plannedCrc > 0 && <span className="planned figure"> / {money(line.plannedCrc, "CRC")}</span>}
            </span>
          </span>
          {line.plannedCrc > 0 && (
            <span className="row-rule" aria-hidden="true">
              <span className={`rule-fill ${tone}`} style={{ "--fill": fill } as CSSProperties} />
            </span>
          )}
          {text && (
            <span className={`row-status ${tone}`}>
              <Icon name={line.status === "AL_LIMITE" || savedEnough ? "check" : "alert"} size={14} /> {text}
            </span>
          )}
        </span>
      </button>
    </li>
  );
}

/**
 * Cómo se repartió lo que entró, sin juzgarlo: la meta de cada casa es su propio presupuesto
 * (Plan), no una regla genérica como el 50/30/20, que no contempla el diezmo ni un ahorro fuerte.
 */
function IncomeSplit({ summary }: { summary: Summary }) {
  if (summary.income.totalUsd <= 0) return null;
  const parts = (["NECESIDAD", "DESEO", "AHORRO"] as const).map((g) => ({
    group: g,
    value: summary.percentOfIncome[g] ?? 0,
  }));
  return (
    <p className="income-split">
      De lo que entró:{" "}
      {parts.map((p, i) => (
        <span key={p.group}>
          {i > 0 && " · "}
          {GROUP_LABEL[p.group].toLowerCase()} <span className="figure">{pct(p.value)}</span>
        </span>
      ))}
    </p>
  );
}

function LedgerSkeleton() {
  return (
    <div className="skeleton" aria-hidden="true">
      <div className="sk-line wide" />
      <div className="sk-line" />
      {Array.from({ length: 6 }, (_, i) => <div key={i} className="sk-row" />)}
    </div>
  );
}
