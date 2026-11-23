import { useEffect, useState } from "react";
import { api } from "../api";
import { dateLabel, dayMonthLabel, money } from "../format";
import { useApp } from "../state";
import type { Currency } from "../types";

interface Snapshot {
  descripcion?: string | null;
  monto?: number;
  moneda?: Currency;
  fecha?: string;
  categoria?: string | null;
  cuenta?: string | null;
  desde?: string | null;
  hacia?: string | null;
}

interface Change {
  at: string;
  actor: string;
  actorName: string;
  owner: string | null;
  entity: "MOVIMIENTO" | "TRANSFERENCIA";
  action: "EDITO" | "BORRO";
  before: Snapshot;
  after: Snapshot | null;
}

/** Qué cambió entre la foto de antes y la de después, en palabras. */
function differences(before: Snapshot, after: Snapshot): string[] {
  const out: string[] = [];
  const cur = after.moneda ?? before.moneda ?? "CRC";
  if (before.monto !== after.monto) {
    out.push(`${money(before.monto ?? 0, before.moneda ?? cur, true)} → ${money(after.monto ?? 0, cur, true)}`);
  }
  if (before.descripcion !== after.descripcion) out.push(`ahora se llama «${after.descripcion ?? ""}»`);
  if (before.fecha !== after.fecha && after.fecha) out.push(`fecha ${dayMonthLabel(after.fecha)}`);
  if (before.categoria !== after.categoria) out.push(`categoría ${after.categoria ?? "sin categoría"}`);
  if (before.cuenta !== after.cuenta) out.push(`cuenta ${after.cuenta ?? "sin cuenta"}`);
  return out;
}

/** Historial de la casa: quién editó o borró qué. Lo que anota cada quien ya se ve en Movimientos. */
export function Changes({ spaceId }: { spaceId: string }) {
  const { session, version } = useApp();
  const me = session.kind === "ready" ? session.me.id : null;
  const [items, setItems] = useState<Change[] | null>(null);

  useEffect(() => {
    let cancelled = false;
    api.get<Change[]>(`/api/spaces/${spaceId}/changes?limit=20`)
      .then((list) => !cancelled && setItems(list))
      .catch(() => !cancelled && setItems(null));
    return () => {
      cancelled = true;
    };
  }, [spaceId, version]);

  if (!items) return null;

  return (
    <section className="block" aria-labelledby="changes-title">
      <h2 id="changes-title" className="section-title">Cambios recientes</h2>
      {items.length === 0 ? (
        <p className="hint">Cuando alguno edite o borre algo de la casa, aquí queda quién lo hizo y qué cambió.</p>
      ) : (
        <ul className="rows">
          {items.map((c, i) => {
            const who = c.actor === me ? "Tú" : c.actorName;
            const name = c.entity === "TRANSFERENCIA"
              ? `la transferencia de ${c.before.desde ?? "una cuenta"} a ${c.before.hacia ?? "otra"}`
              : `«${c.before.descripcion ?? "un movimiento"}»`;
            const verb = c.action === "BORRO" ? (c.actor === me ? "borraste" : "borró") : (c.actor === me ? "editaste" : "editó");
            const detail = c.action === "BORRO"
              ? [money(c.before.monto ?? 0, c.before.moneda ?? "CRC", true), c.before.categoria].filter(Boolean).join(" · ")
              : differences(c.before, c.after ?? {}).join(" · ");
            const someoneElses = c.owner !== null && c.owner !== c.actor && c.owner === me;
            return (
              <li key={`${c.at}-${i}`} className="row change">
                <p className="change-line">
                  <strong>{who}</strong> {verb} {name}
                  {someoneElses && <span className="tag">lo habías anotado tú</span>}
                </p>
                <p className="muted small">
                  {detail && <span className="figure">{detail}</span>}
                  {detail && " · "}
                  {dateLabel(c.at)}
                </p>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}
