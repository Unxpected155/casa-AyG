import { useEffect, useRef, useState, type FormEvent } from "react";
import { RecurringManager } from "../components/Recurring";
import { api, messageOf } from "../api";
import { Icon } from "../components/Icon";
import { Shell } from "../components/Shell";
import { amountChange, amountToInput, GROUP_LABEL, money, monthLabel, parseAmount, shiftMonth } from "../format";
import { useApp } from "../state";
import { GROUPS, type BudgetLine, type Category, type Group } from "../types";

/** Presupuesto del mes (en colones) y las categorías del espacio, en una sola página. */
export function Plan() {
  const { space, spaces, month, categories, touch, version } = useApp();
  const [planned, setPlanned] = useState<Record<string, string>>({});
  // Lo traído de lo personal se suma cuando el presupuesto (y las categorías nuevas) recargan.
  const imported = useRef<Record<string, number> | null>(null);
  const personal = spaces.find((s) => s.kind === "PERSONAL" && !s.archived);
  const [status, setStatus] = useState<{ tone: "ok" | "error"; text: string } | null>(null);
  const [saving, setSaving] = useState(false);
  const readOnly = space?.archived ?? true;

  useEffect(() => {
    if (!space) return;
    let cancelled = false;
    api.get<BudgetLine[]>(`/api/spaces/${space.id}/budgets/${month.year}/${month.month}`)
      .then((lines) => {
        if (cancelled) return;
        const extra = imported.current;
        imported.current = null;
        setPlanned(extra ? addTo(toForm(lines), extra) : toForm(lines));
      })
      .catch(() => !cancelled && setPlanned({}));
    return () => {
      cancelled = true;
    };
  }, [space, month.year, month.month, version]);

  // El aviso se limpia al cambiar de espacio o de mes; no con la recarga que sigue a guardar.
  useEffect(() => setStatus(null), [space?.id, month.year, month.month]);

  if (!space) return <Shell withMonth><p>Cargando…</p></Shell>;

  const total = Object.values(planned).reduce((sum, v) => sum + Number(parseAmount(v) ?? 0), 0);

  const save = async () => {
    const lines: BudgetLine[] = [];
    for (const [categoryId, text] of Object.entries(planned)) {
      if (!text.trim()) continue;
      const amount = parseAmount(text);
      if (amount === null) {
        setStatus({ tone: "error", text: "Hay un monto con formato inválido." });
        return;
      }
      if (Number(amount) > 0) lines.push({ categoryId, plannedAmount: Number(amount) });
    }
    setSaving(true);
    try {
      await api.put(`/api/spaces/${space.id}/budgets/${month.year}/${month.month}`, {
        lines: lines.map((l) => ({ categoryId: l.categoryId, plannedAmount: String(l.plannedAmount) })),
      });
      setStatus({ tone: "ok", text: `Presupuesto de ${monthLabel(month).toLowerCase()} guardado.` });
      touch();
    } catch (e) {
      setStatus({ tone: "error", text: messageOf(e, "No se pudo guardar el presupuesto.") });
    } finally {
      setSaving(false);
    }
  };

  /**
   * En la casa: suma mi presupuesto personal de este mes (o del anterior, si este no tiene),
   * por nombre de categoría. Las que la casa no tiene se crean. Queda en el formulario para
   * revisar antes de guardar; si los dos lo hacen, se suman.
   */
  const bringMine = async () => {
    if (!personal) return;
    setStatus(null);
    try {
      const prev = shiftMonth(month, -1);
      let from = month;
      let lines = await api.get<BudgetLine[]>(`/api/spaces/${personal.id}/budgets/${month.year}/${month.month}`);
      if (lines.length === 0) {
        from = prev;
        lines = await api.get<BudgetLine[]>(`/api/spaces/${personal.id}/budgets/${prev.year}/${prev.month}`);
      }
      if (lines.length === 0) {
        setStatus({ tone: "error", text: "Tu espacio personal no tiene presupuesto este mes ni el anterior." });
        return;
      }
      const mine = await api.get<Category[]>(`/api/spaces/${personal.id}/categories`);
      const key = (c: { name: string; group: Group }) => `${c.group}:${norm(c.name)}`;
      const home = new Map(categories.map((c) => [key(c), c.id]));
      const extra: Record<string, number> = {};
      for (const line of lines) {
        const cat = mine.find((c) => c.id === line.categoryId);
        if (!cat || cat.group === "INGRESO" || SKIP.has(norm(cat.name)) || line.plannedAmount <= 0) continue;
        let id = home.get(key(cat));
        if (!id) {
          id = (await api.post<Category>(`/api/spaces/${space.id}/categories`, { name: cat.name, group: cat.group })).id;
          home.set(key(cat), id);
        }
        extra[id] = (extra[id] ?? 0) + Number(line.plannedAmount);
      }
      // Lo que ya está escrito en el formulario (aunque no se haya guardado) también cuenta.
      setPlanned((current) => addTo(current, extra));
      if (Object.keys(extra).some((id) => !categories.some((c) => c.id === id))) {
        imported.current = extra;
        touch(); // recarga las categorías nuevas; el presupuesto vuelve con lo traído sumado
      }
      setStatus({ tone: "ok", text: `Sumé tu presupuesto personal de ${monthLabel(from).toLowerCase()}. Revisa y guarda.` });
    } catch (e) {
      setStatus({ tone: "error", text: messageOf(e, "No se pudo traer tu presupuesto.") });
    }
  };

  const copyPrevious = async () => {
    const prev = shiftMonth(month, -1);
    try {
      const lines = await api.get<BudgetLine[]>(`/api/spaces/${space.id}/budgets/${prev.year}/${prev.month}`);
      if (lines.length === 0) {
        setStatus({ tone: "error", text: `${monthLabel(prev)} no tiene presupuesto para copiar.` });
        return;
      }
      setPlanned(toForm(lines));
      setStatus({ tone: "ok", text: `Copiado de ${monthLabel(prev).toLowerCase()}. Revisa y guarda.` });
    } catch (e) {
      setStatus({ tone: "error", text: messageOf(e, "No se pudo copiar.") });
    }
  };

  return (
    <Shell withMonth>
      <section className="plan" aria-labelledby="plan-title">
        <div className="section-head">
          <h2 id="plan-title" className="section-title">Presupuesto</h2>
          <span className="figure muted">{money(total, "CRC")} planeados</span>
        </div>
        <p className="hint">Cuánto planean gastar o ahorrar este mes en cada categoría, en colones.</p>

        {/* Un <form>: «Ir/Enter» en el teclado del teléfono guarda el presupuesto. */}
        <form noValidate onSubmit={(e) => { e.preventDefault(); if (!readOnly && !saving) void save(); }}>
        {GROUPS.filter((g) => g !== "INGRESO").map((group) => {
          const items = categories.filter((c) => c.group === group && plannable(c, planned));
          if (items.length === 0) return null;
          return (
          <div key={group} className="plan-group">
            <h3 className="group-title">{GROUP_LABEL[group]}</h3>
            {group === "AHORRO" && (
              <p className="hint small">Se cumple moviendo dinero a tu CDP o a una meta (en Cuentas → Mover dinero).</p>
            )}
            <ul className="rows">
              {items.map((c) => (
                <li key={c.id} className="row plan-row">
                  <label htmlFor={`p-${c.id}`} className="row-name">{c.name}</label>
                  <span className="money-input">
                    <span aria-hidden="true">₡</span>
                    <input
                      id={`p-${c.id}`}
                      inputMode="decimal"
                      placeholder="0"
                      maxLength={18}
                      disabled={readOnly}
                      value={planned[c.id] ?? ""}
                      onChange={(e) => setPlanned({ ...planned, [c.id]: amountChange(e) })}
                    />
                  </span>
                </li>
              ))}
            </ul>
          </div>
          );
        })}

        {status && <p className={status.tone === "ok" ? "form-ok" : "form-error"} role="status">{status.text}</p>}
        {!readOnly && (
          <div className="actions">
            <button type="button" className="button ghost" onClick={copyPrevious}>Copiar del mes anterior</button>
            {space.kind === "FAMILIA" && personal && (
              <button type="button" className="button ghost" onClick={bringMine}>Sumar mi presupuesto personal</button>
            )}
            <button type="submit" className="button primary" disabled={saving}>
              {saving ? "Guardando…" : "Guardar presupuesto"}
            </button>
          </div>
        )}
        </form>
      </section>

      <RecurringManager readOnly={readOnly} />

      <Categories categories={categories} readOnly={readOnly} spaceId={space.id} onChange={touch} />
    </Shell>
  );
}

/**
 * Qué se planea: las categorías en uso, las que ya tienen algo planeado (aunque se hayan
 * desactivado) y «Ahorro», que se cumple con transferencias aunque ya no sea un gasto.
 */
function plannable(c: Category, planned: Record<string, string>): boolean {
  return c.active || Boolean(planned[c.id]) || (c.group === "AHORRO" && c.name.trim().toLowerCase() === "ahorro");
}

/** Categorías de la casa misma: no se traen de lo personal. */
const SKIP = new Set(["aporte al hogar", "de la casa"]);

function norm(text: string): string {
  return text.normalize("NFD").replace(/\p{M}/gu, "").trim().toLowerCase();
}

/** Suma montos a un formulario de presupuesto (texto es-CR). */
function addTo(form: Record<string, string>, extra: Record<string, number>): Record<string, string> {
  const out = { ...form };
  for (const [id, amount] of Object.entries(extra)) {
    const base = Number(parseAmount(out[id] ?? "") ?? 0);
    out[id] = amountToInput(Math.round((base + amount) * 100) / 100);
  }
  return out;
}

function toForm(lines: BudgetLine[]): Record<string, string> {
  return Object.fromEntries(lines.map((l) => [l.categoryId, amountToInput(l.plannedAmount)]));
}

function Categories({ categories, readOnly, spaceId, onChange }: {
  categories: Category[]; readOnly: boolean; spaceId: string; onChange: () => void;
}) {
  const [name, setName] = useState("");
  const [group, setGroup] = useState<Group>("NECESIDAD");
  const [error, setError] = useState<string | null>(null);
  const base = `/api/spaces/${spaceId}/categories`;

  const add = async (event: FormEvent) => {
    event.preventDefault();
    if (!name.trim()) return;
    try {
      await api.post(base, { name: name.trim(), group });
      setName("");
      setError(null);
      onChange();
    } catch (e) {
      setError(messageOf(e, "No se pudo agregar.").replace("Ya existe", "Ya existe una categoría con ese nombre."));
    }
  };

  return (
    <details className="plan more-section">
      <summary>
        <span className="section-title">Editar categorías</span>
        <span className="muted small">{categories.filter((c) => c.active).length} en uso · toca una para cambiarla</span>
      </summary>
      {GROUPS.map((g) => categories.some((c) => c.group === g) && (
        <div key={g} className="plan-group">
          <h3 className="group-title">{GROUP_LABEL[g]}</h3>
          <ul className="rows">
            {categories.filter((c) => c.group === g).map((c) => (
              <CategoryItem key={c.id} category={c} base={base} readOnly={readOnly} onChange={onChange} />
            ))}
          </ul>
        </div>
      ))}

      {!readOnly && (
        <form className="add-category" onSubmit={add}>
          <label className="field grow">
            <span>Nueva categoría</span>
            <input value={name} maxLength={50} onChange={(e) => setName(e.target.value)} placeholder="Mascotas" />
          </label>
          <label className="field">
            <span>Grupo</span>
            <select value={group} onChange={(e) => setGroup(e.target.value as Group)}>
              {GROUPS.map((g) => <option key={g} value={g}>{GROUP_LABEL[g]}</option>)}
            </select>
          </label>
          <button type="submit" className="button secondary"><Icon name="plus" size={18} /> Agregar</button>
        </form>
      )}
      {error && <p className="form-error" role="alert">{error}</p>}
    </details>
  );
}

function CategoryItem({ category, base, readOnly, onChange }: {
  category: Category; base: string; readOnly: boolean; onChange: () => void;
}) {
  const [open, setOpen] = useState(false);
  const [editing, setEditing] = useState(false);
  const [name, setName] = useState(category.name);
  const [confirm, setConfirm] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const update = async (changes: Partial<Category>) => {
    try {
      await api.put(`${base}/${category.id}`, {
        name: changes.name ?? category.name,
        group: changes.group ?? category.group,
        active: changes.active ?? category.active,
      });
      setEditing(false);
      setError(null);
      onChange();
    } catch (e) {
      setError(messageOf(e, "No se pudo guardar."));
    }
  };

  const remove = async () => {
    try {
      await api.del(`${base}/${category.id}`);
      onChange();
    } catch (e) {
      setConfirm(false);
      setError(messageOf(e, "No se pudo borrar."));
    }
  };

  return (
    <li className={`row category-item ${category.active ? "" : "inactive"}`}>
      <div className="category-line">
        {editing ? (
          <form className="inline-edit" onSubmit={(e) => { e.preventDefault(); void update({ name: name.trim() }); }}>
            <label className="visually-hidden" htmlFor={`n-${category.id}`}>Nombre</label>
            <input id={`n-${category.id}`} value={name} maxLength={50} onChange={(e) => setName(e.target.value)} autoFocus />
            <button type="submit" className="icon-button" aria-label="Guardar nombre"><Icon name="check" /></button>
            <button type="button" className="icon-button" aria-label="Cancelar" onClick={() => { setEditing(false); setName(category.name); }}>
              <Icon name="close" />
            </button>
          </form>
        ) : (
          <button type="button" className="row-main row-button category-toggle" aria-expanded={open}
                  onClick={() => setOpen(!open)} disabled={readOnly}>
            <span className="row-name">
              {category.name}
              {!category.active && <span className="tag">desactivada</span>}
            </span>
          </button>
        )}
      </div>
      {!readOnly && !editing && open && (
          <div className="row-tools category-tools">
            <button type="button" className="button ghost small" onClick={() => setEditing(true)}>Renombrar</button>
            <button type="button" className="button ghost small" onClick={() => void update({ active: !category.active })}>
              {category.active ? "Desactivar" : "Activar"}
            </button>
            {confirm ? (
              <span className="confirm-inline">
                <button type="button" className="button ghost small danger" onClick={remove}>Sí, borrar</button>
                <button type="button" className="button ghost small" onClick={() => setConfirm(false)}>No</button>
              </span>
            ) : (
              <button type="button" className="button ghost small danger" onClick={() => setConfirm(true)}>
                <Icon name="trash" size={16} /> Borrar
              </button>
            )}
          </div>
        )}
      {error && <p className="form-error small" role="alert">{error}</p>}
    </li>
  );
}
