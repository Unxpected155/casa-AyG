import { useRef, useState, type CSSProperties, type RefObject } from "react";
import { money, PLACE_KIND_LABEL } from "../format";
import { gsap, useGSAP, whenMotionAllowed } from "../motion";
import { navigate } from "../router";
import { useApp } from "../state";
import type { Health, Place } from "../types";

/** La etiqueta del tipo sobra si el nombre ya lo dice ("Efectivo", "CDP ahorro"). */
export function showKind(place: Place): boolean {
  return !place.name.toLowerCase().includes(PLACE_KIND_LABEL[place.kind].toLowerCase());
}

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

/**
 * Apartado: un lugar de ahorro (los CDP, las cuentas de una meta y lo que la persona marque).
 * Lo demás está disponible. La misma regla que usa el servidor (V24).
 */
export function isReserved(place: Place): boolean {
  return place.savings;
}

/**
 * «Hoy tienes…» y sus tres partes: Ahorrado, Disponible y, de lo disponible, lo que te sobra
 * sin tocar el ahorro ni lo planeado del mes. La barra muestra la proporción de un vistazo;
 * los montos van escritos debajo (el color nunca es lo único que lo dice).
 */
export function Balance({ health }: { health: Health | null }) {
  const { places } = useApp();
  const [open, setOpen] = useState(false);
  const bar = useRef<HTMLDivElement>(null);
  const rows = orderedPlaces(places);

  // La barra se dibuja de izquierda a derecha una vez, como un trazo de lápiz.
  useGSAP(() => whenMotionAllowed(() => {
    if (!bar.current) return;
    gsap.from(bar.current, { scaleX: 0, transformOrigin: "left center", duration: 0.7, ease: "expo.out",
      clearProps: "transform" });
  }), { dependencies: [health === null] });

  if (rows.length === 0) {
    return (
      <section className="balance balance-empty" aria-label="Saldo">
        <p className="balance-line">¿Cuánto tienes hoy?</p>
        <p className="muted">Anota dónde está tu dinero (tu cuenta, tus sobres, tu CDP) y lo sumo por ti.</p>
        <button type="button" className="button secondary" onClick={() => navigate("/lugares")}>
          Agregar mis cuentas
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
        <span className="balance-more">{open ? "Ocultar el detalle" : "Ver por cuenta"}</span>
      </button>

      {health ? <MoneyParts parts={health.money} hasUsd={hasUsd} bar={bar} /> : <div className="money-parts-slot" aria-hidden="true" />}

      {open && (
        <div id="balance-detail" className="balance-detail">
          <ul className="rows">
            {rows.map(({ place, nested }) => (
              <li key={place.id} className={`row place-row${nested ? " nested" : ""}`}>
                <div className="row-main">
                  <span className="row-name">
                    {place.name}
                    {showKind(place) && <span className="tag">{PLACE_KIND_LABEL[place.kind]}</span>}
                    {place.goalName ? <span className="tag">Para {place.goalName}</span>
                      : place.savings && <span className="tag">Ahorro</span>}
                  </span>
                  <span className={`figure${place.balance < 0 ? " negative" : ""}`}>
                    {money(place.balance, place.currency, true)}
                  </span>
                </div>
              </li>
            ))}
          </ul>
          <button type="button" className="button ghost small" onClick={() => navigate("/lugares")}>
            Administrar cuentas
          </button>
        </div>
      )}
    </section>
  );
}

function MoneyParts({ parts, hasUsd, bar }: {
  parts: Health["money"];
  hasUsd: boolean;
  bar: RefObject<HTMLDivElement | null>;
}) {
  const saved = Math.max(parts.saved, 0);
  const leftover = Math.max(parts.leftover, 0);
  // Lo disponible que ya tiene uso este mes (presupuesto, pagos fijos, cuotas).
  const committed = Math.max(parts.available, 0) - leftover;
  const total = saved + committed + leftover;
  const short = parts.leftover < 0;

  return (
    <div className="money-parts">
      {total > 0 && (
        <div className="money-bar" ref={bar} aria-hidden="true">
          {/* Un tramo en cero no se dibuja (si no, deja una rayita de separación suelta). */}
          {saved > 0 && <span className="money-seg saved" style={{ "--grow": saved / total } as CSSProperties} />}
          {committed > 0 && <span className="money-seg committed" style={{ "--grow": committed / total } as CSSProperties} />}
          {leftover > 0 && <span className="money-seg leftover" style={{ "--grow": leftover / total } as CSSProperties} />}
        </div>
      )}
      <dl className="money-list">
        <div>
          <dt><span className="money-key saved" aria-hidden="true" />Ahorrado</dt>
          <dd className="figure">{money(parts.saved, "CRC")}</dd>
        </div>
        <div>
          <dt><span className="money-key committed" aria-hidden="true" />Disponible</dt>
          <dd className="figure">{money(parts.available, "CRC")}</dd>
        </div>
        <div className="money-leftover">
          <dt><span className="money-key leftover" aria-hidden="true" />{short ? "Te faltan" : "Te sobra"}</dt>
          <dd className={`figure${short ? " short" : ""}`}>{money(Math.abs(parts.leftover), "CRC")}</dd>
        </div>
      </dl>
      <p className="money-note">
        {short
          ? "Lo disponible no alcanza para lo que falta pagar este mes."
          : "Lo que puedes usar sin tocar tu ahorro ni lo que planeaste para este mes."}
        {parts.pending > 0 && <> De lo disponible, <span className="figure">{money(parts.pending, "CRC")}</span> ya tiene uso.</>}
        {hasUsd && !parts.missingRate && " Los dólares van convertidos a colones."}
        {parts.missingRate && " Todavía no hay tipo de cambio: los dólares no se sumaron."}
      </p>
    </div>
  );
}
