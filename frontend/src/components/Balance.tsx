import { useState } from "react";
import { money, PLACE_KIND_LABEL } from "../format";
import { navigate } from "../router";
import { useApp } from "../state";
import type { Place } from "../types";

/** Lugares activos ordenados como se muestran: cada lugar y, debajo, sus sobres. */
export function orderedPlaces(places: Place[]): { place: Place; nested: boolean }[] {
  const active = places.filter((p) => !p.archived);
  const ids = new Set(active.map((p) => p.id));
  const roots = active.filter((p) => !p.parentId || !ids.has(p.parentId));
  return roots.flatMap((root) => [
    { place: root, nested: false },
    ...active.filter((p) => p.parentId === root.id).map((p) => ({ place: p, nested: true })),
  ]);
}

export function totals(places: Place[]): { crc: number; usd: number } {
  return places.filter((p) => !p.archived).reduce(
    (acc, p) => (p.currency === "USD" ? { ...acc, usd: acc.usd + p.balance } : { ...acc, crc: acc.crc + p.balance }),
    { crc: 0, usd: 0 },
  );
}

/** "Hoy tienes…": la primera línea de la libreta. El desglose se abre ahí mismo, sin modal. */
export function Balance() {
  const { places } = useApp();
  const [open, setOpen] = useState(false);
  const rows = orderedPlaces(places);

  if (rows.length === 0) {
    return (
      <section className="balance balance-empty" aria-label="Saldo">
        <p className="balance-line">¿Cuánto tienes hoy?</p>
        <p className="muted">Anota dónde está tu dinero (tu cuenta, tus sobres, tu CDP) y lo sumo por ti.</p>
        <button type="button" className="button secondary" onClick={() => navigate("/lugares")}>
          Agregar mis lugares
        </button>
      </section>
    );
  }

  const { crc, usd } = totals(places);
  const hasUsd = rows.some((r) => r.place.currency === "USD");
  const hasCrc = rows.some((r) => r.place.currency === "CRC");

  return (
    <section className="balance" aria-label="Saldo">
      <button type="button" className="balance-toggle" aria-expanded={open} aria-controls="balance-detail"
              onClick={() => setOpen(!open)}>
        <span className="balance-line">
          Hoy tienes{" "}
          {hasCrc && <strong className="figure">{money(crc, "CRC")}</strong>}
          {hasCrc && hasUsd && " y "}
          {hasUsd && <strong className="figure">{money(usd, "USD")}</strong>}
        </span>
        <span className="balance-more">{open ? "Ocultar" : "Ver por lugar"}</span>
      </button>
      {open && (
        <div id="balance-detail" className="balance-detail">
          <ul className="rows">
            {rows.map(({ place, nested }) => (
              <li key={place.id} className={`row place-row${nested ? " nested" : ""}`}>
                <div className="row-main">
                  <span className="row-name">
                    {place.name}
                    <span className="tag">{PLACE_KIND_LABEL[place.kind]}</span>
                  </span>
                  <span className={`figure${place.balance < 0 ? " negative" : ""}`}>
                    {money(place.balance, place.currency, true)}
                  </span>
                </div>
              </li>
            ))}
          </ul>
          <button type="button" className="button ghost small" onClick={() => navigate("/lugares")}>
            Administrar lugares
          </button>
        </div>
      )}
    </section>
  );
}
