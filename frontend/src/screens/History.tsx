import { useEffect, useState } from "react";
import { api, messageOf } from "../api";
import { Shell } from "../components/Shell";
import { GROUP_LABEL, money, monthLabel } from "../format";
import { useApp } from "../state";
import type { Group, History as HistoryData, HistoryMonth, Trend } from "../types";

/** Cambios chicos no son noticia: menos de ₡5 000 o de 10 % se quedan fuera de "Qué cambió". */
const MIN_DELTA = 5000;
const MIN_PCT = 10;

const shortMonth = (ym: string) => {
  const [y, m] = ym.split("-").map(Number);
  return monthLabel({ year: y ?? 2000, month: m ?? 1 }).slice(0, 3);
};

/** Historia: qué cambió este mes contra tu promedio, y cómo van los últimos 6 meses. */
export function History() {
  const { space, month, version } = useApp();
  const [data, setData] = useState<HistoryData | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [openCategory, setOpenCategory] = useState<string | null>(null);

  useEffect(() => {
    if (!space) return;
    let cancelled = false;
    setError(null);
    api.get<HistoryData>(`/api/spaces/${space.id}/history?year=${month.year}&month=${month.month}`)
      .then((d) => !cancelled && setData(d))
      .catch((e) => !cancelled && setError(messageOf(e, "No se pudo cargar la historia.")));
    return () => {
      cancelled = true;
    };
  }, [space, month.year, month.month, version]);

  const expenses = (data?.categories ?? []).filter((c) => c.group !== "INGRESO");
  const comparable = expenses.filter((c) => c.delta != null && c.expected != null);
  const up = comparable.filter((c) => c.delta! >= MIN_DELTA && (c.deltaPct == null || c.deltaPct >= MIN_PCT))
    .sort((a, b) => b.delta! - a.delta!).slice(0, 3);
  const down = comparable.filter((c) => c.delta! <= -MIN_DELTA && (c.deltaPct == null || c.deltaPct <= -MIN_PCT))
    .sort((a, b) => a.delta! - b.delta!).slice(0, 3);

  return (
    <Shell withMonth ownTitle>
      <h1 className="page-title">Historia</h1>
      {error && <p className="form-error" role="alert">{error}</p>}
      {!data && !error && <div className="skeleton" aria-hidden="true"><div className="sk-line wide" /><div className="sk-row" /><div className="sk-row" /></div>}
      {data && (
        <>
          <section className="block" aria-labelledby="changed-title">
            <h2 id="changed-title" className="section-title">Qué cambió contra tu promedio</h2>
            {data.baselineMonths === 0 ? (
              <p className="muted">Todavía hay un solo mes: la comparación aparece desde el próximo.</p>
            ) : (
              <>
                <p className="hint">
                  Contra tu promedio de {data.baselineMonths === 1 ? "el mes anterior" : `los ${data.baselineMonths} meses anteriores`}
                  {data.partial && ", a esta altura del mes"}.
                </p>
                {up.length === 0 && down.length === 0 && (
                  <p className="changed-calm">Nada se movió mucho: vas parecido a tu promedio.</p>
                )}
                {up.length > 0 && (
                  <ul className="changed">
                    {up.map((c) => (
                      <li key={c.categoryId}>
                        <span className="changed-mark up" aria-hidden="true">▲</span>
                        <span><strong>{c.name}</strong>: <span className="figure">{money(c.current, "CRC")}</span>,{" "}
                          <span className="figure">{money(c.delta!, "CRC")}</span> más que tu promedio
                          {c.deltaPct != null && <> (+{c.deltaPct} %)</>}.</span>
                      </li>
                    ))}
                  </ul>
                )}
                {down.length > 0 && (
                  <ul className="changed">
                    {down.map((c) => (
                      <li key={c.categoryId}>
                        <span className="changed-mark down" aria-hidden="true">▼</span>
                        <span><strong>{c.name}</strong>: <span className="figure">{money(c.current, "CRC")}</span>,{" "}
                          <span className="figure">{money(-c.delta!, "CRC")}</span> menos que tu promedio.</span>
                      </li>
                    ))}
                  </ul>
                )}
              </>
            )}
          </section>

          <section className="block" aria-labelledby="months-title">
            <h2 id="months-title" className="section-title">Los últimos 6 meses</h2>
            <MonthsChart months={data.months} />
            <MonthsTable months={data.months} />
            <p className="muted small">Todo en colones; los dólares, al tipo de cambio del día de cada movimiento. El dinero movido entre tus cuentas no se suma.</p>
          </section>

          <section className="block" aria-labelledby="cat-title">
            <h2 id="cat-title" className="section-title">Por categoría</h2>
            {(["NECESIDAD", "DESEO", "AHORRO"] as Group[]).map((g) => {
              const rows = expenses.filter((c) => c.group === g && c.series.some((v) => v > 0));
              if (rows.length === 0) return null;
              return (
                <div key={g} className="group">
                  <h3 className="group-title">{GROUP_LABEL[g]}</h3>
                  <ul className="rows">
                    {rows.map((c) => (
                      <CategoryRow key={c.categoryId} trend={c} months={data.months}
                                   open={openCategory === c.categoryId}
                                   onToggle={() => setOpenCategory(openCategory === c.categoryId ? null : c.categoryId)} />
                    ))}
                  </ul>
                </div>
              );
            })}
          </section>
        </>
      )}
    </Shell>
  );
}

/** Barras agrupadas: entró (azul petróleo) y salió (terracota) por mes. Sin doble eje. */
function MonthsChart({ months }: { months: HistoryMonth[] }) {
  const [hover, setHover] = useState<number | null>(null);
  const max = Math.max(1, ...months.flatMap((m) => [m.income, m.expenses]));
  const W = 340, H = 160, top = 12, bottom = 24, left = 4;
  const plotH = H - top - bottom;
  const slot = (W - left) / months.length;
  const bar = Math.min(18, slot / 3);
  const y = (v: number) => top + plotH - (v / max) * plotH;
  const shown = hover ?? months.length - 1;
  const m = months[shown]!;

  return (
    <figure className="chart">
      <div className="chart-legend" aria-hidden="true">
        <span><i className="swatch in" /> Entró</span>
        <span><i className="swatch out" /> Salió</span>
      </div>
      <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label="Lo que entró y lo que salió en los últimos 6 meses (detalle en la tabla)">
        <line x1={0} x2={W} y1={top + plotH} y2={top + plotH} className="chart-axis" />
        {months.map((mo, i) => {
          const cx = left + slot * i + slot / 2;
          return (
            <g key={mo.month} onMouseEnter={() => setHover(i)} onMouseLeave={() => setHover(null)}
               onClick={() => setHover(hover === i ? null : i)}>
              <rect x={left + slot * i} y={0} width={slot} height={H} className="chart-hit" />
              {mo.income > 0 && <path className="bar in" d={barPath(cx - bar - 1, y(mo.income), bar, top + plotH - y(mo.income))} />}
              {mo.expenses > 0 && <path className="bar out" d={barPath(cx + 1, y(mo.expenses), bar, top + plotH - y(mo.expenses))} />}
              <text x={cx} y={H - 6} textAnchor="middle" className={`chart-label${i === shown ? " current" : ""}`}>{shortMonth(mo.month)}</text>
            </g>
          );
        })}
      </svg>
      <figcaption className="chart-readout" aria-live="polite">
        <strong>{monthLabel({ year: Number(m.month.slice(0, 4)), month: Number(m.month.slice(5, 7)) })}</strong>:{" "}
        entró <span className="figure income">{money(m.income, "CRC")}</span>, salió <span className="figure expense">{money(m.expenses, "CRC")}</span>
        {m.savingsRate != null && <>; te sobró el <strong className="figure">{m.savingsRate} %</strong></>}.
      </figcaption>
    </figure>
  );
}

/** Barra con las esquinas de arriba redondeadas (4px) y la base recta sobre el eje. */
function barPath(x: number, y: number, w: number, h: number): string {
  if (h <= 0) return "";
  const r = Math.min(4, w / 2, h);
  return `M${x},${y + h} V${y + r} Q${x},${y} ${x + r},${y} H${x + w - r} Q${x + w},${y} ${x + w},${y + r} V${y + h} Z`;
}

function MonthsTable({ months }: { months: HistoryMonth[] }) {
  return (
    <table className="months-table">
      <caption className="visually-hidden">Últimos 6 meses</caption>
      <thead>
        <tr><th scope="col">Mes</th><th scope="col">Entró</th><th scope="col">Salió</th><th scope="col">Sobró</th></tr>
      </thead>
      <tbody>
        {[...months].reverse().map((m) => (
          <tr key={m.month}>
            <th scope="row">{shortMonth(m.month)}</th>
            <td className="figure income">{money(m.income, "CRC")}</td>
            <td className="figure expense">{money(m.expenses, "CRC")}</td>
            <td className={`figure${m.saved < 0 ? " negative" : ""}`}>
              {m.savingsRate != null ? `${m.savingsRate} %` : "—"}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function CategoryRow({ trend: c, months, open, onToggle }: {
  trend: Trend; months: HistoryMonth[]; open: boolean; onToggle: () => void;
}) {
  const max = Math.max(1, ...c.series);
  const previous = c.series[c.series.length - 2] ?? 0;
  return (
    <li className="row">
      <button type="button" className="row-main row-button" onClick={onToggle} aria-expanded={open}>
        <span className="row-name">{c.name}</span>
        <span className="trend-cell">
          <svg viewBox="0 0 60 18" className="spark" aria-hidden="true">
            {c.series.map((v, i) => {
              const h = (v / max) * 16;
              return <rect key={i} x={i * 10 + 1} y={18 - h} width={7} height={Math.max(h, v > 0 ? 1 : 0)} rx={1.5}
                           className={i === c.series.length - 1 ? "spark-now" : "spark-past"} />;
            })}
          </svg>
          <span className="figure">{money(c.current, "CRC")}</span>
        </span>
      </button>
      {open && (
        <div className="trend-detail">
          <p className="muted small">
            Mes anterior: <span className="figure">{money(previous, "CRC")}</span>
            {c.average != null && <> · promedio: <span className="figure">{money(c.average, "CRC")}</span></>}
          </p>
          <ul className="trend-months">
            {c.series.map((v, i) => (
              <li key={months[i]!.month}><span>{shortMonth(months[i]!.month)}</span><span className="figure">{money(v, "CRC")}</span></li>
            ))}
          </ul>
        </div>
      )}
    </li>
  );
}
