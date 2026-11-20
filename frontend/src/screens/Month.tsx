import { useEffect, useRef, useState, type CSSProperties } from "react";
import { api, messageOf } from "../api";
import { Balance } from "../components/Balance";
import { cdpDue } from "../components/CdpPanel";
import { DueList } from "../components/Recurring";
import { EntrySheet } from "../components/EntrySheet";
import { Icon } from "../components/Icon";
import { Shell } from "../components/Shell";
import { GROUP_LABEL, money, pct } from "../format";
import { countUp, gsap, useGSAP, whenMotionAllowed } from "../motion";
import { navigate } from "../router";
import { useApp } from "../state";
import { GROUPS, type Group, type MergeRequest, type Summary, type SummaryLine } from "../types";

/** Pantalla principal: el mes como una página de libreta. */
export function Month() {
  const { space, spaces, month, version, places } = useApp();
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
  const dueCdps = places.filter((p) => !p.archived && cdpDue(p));
  const [summary, setSummary] = useState<Summary | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  const [showIdle, setShowIdle] = useState(false);
  const ledger = useRef<HTMLDivElement>(null);

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
  useGSAP(() => {
    if (!summary) return;
    const total = ledger.current?.querySelector<HTMLElement>("[data-count]");
    if (total) countUp(total, Math.round(summary.expenses.crc), (n) => money(n, "CRC"));
    return whenMotionAllowed(() => {
      gsap.from(".rule-fill", { scaleX: 0, duration: 0.6, ease: "expo.out", stagger: 0.025 });
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

        {summary && isCurrentMonth && dueCdps.map((p) => (
          <p key={p.id} className="notice cdp-due">
            <span>Tu <strong>{p.name}</strong> venció: toca renovarlo.</span>
            <button type="button" className="button secondary small" onClick={() => navigate("/lugares")}>Renovar</button>
          </p>
        ))}

        {summary && isCurrentMonth && <Balance />}

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

        {summary && !isEmpty && (
          <>
            <section className="overview" aria-label="Resumen del mes">
              <MonthFlow summary={summary} />
              <p className="overview-detail">
                Todo en dólares: <span className="figure">{money(summary.expenses.totalUsd, "USD")}</span> en gastos
                {summary.income.totalUsd > 0 && <> de <span className="figure">{money(summary.income.totalUsd, "USD")}</span></>}.
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
  const left = inCrc - outCrc;
  const { income, expenses } = summary;
  return (
    <>
      <p className="overview-line">
        {income.crc > 0 ? (
          <>
            Entraron <span className="figure">{money(inCrc, "CRC")}</span> y salieron{" "}
            <strong data-count className="figure">{money(outCrc, "CRC")}</strong>
            {left >= 0
              ? <>: te quedan <strong className="figure">{money(left, "CRC")}</strong> del mes.</>
              : <>: gastaste <strong className="figure">{money(-left, "CRC")}</strong> más de lo que entró.</>}
          </>
        ) : (
          <>Van <strong data-count className="figure">{money(outCrc, "CRC")}</strong> en gastos este mes.</>
        )}
      </p>
      {(income.usd > 0 || expenses.usd > 0) && (
        <p className="overview-detail">
          En dólares:
          {income.usd > 0 && <> entraron <span className="figure">{money(income.usd, "USD")}</span></>}
          {income.usd > 0 && expenses.usd > 0 && " y"}
          {expenses.usd > 0 && <> salieron <span className="figure">{money(expenses.usd, "USD")}</span></>}.
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
  const tone = income ? "income"
    : line.status === "EXCEDIDO" ? "over"
    : line.status === "CERCA" ? "near"
    : line.status === "AL_LIMITE" ? "full"
    : "ok";
  const text = income ? null : statusText(line);

  return (
    <li className="row">
      <div className="row-main">
        <span className="row-name">{line.name}</span>
        <span className="row-amounts">
          <span className="figure">{spent}</span>
          {line.plannedCrc > 0 && <span className="planned figure"> / {money(line.plannedCrc, "CRC")}</span>}
        </span>
      </div>
      {line.plannedCrc > 0 && (
        <div className="row-rule">
          <span
            className={`rule-fill ${tone}`}
            style={{ "--fill": fill } as CSSProperties}
            role="meter"
            aria-label={`${line.name}: ${pct(ratio * 100)} de lo planeado`}
            aria-valuemin={0}
            aria-valuemax={100}
            aria-valuenow={Math.round(ratio * 100)}
          />
        </div>
      )}
      {text && (
        <span className={`row-status ${tone}`}>
          <Icon name={line.status === "AL_LIMITE" ? "check" : "alert"} size={14} /> {text}
        </span>
      )}
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
