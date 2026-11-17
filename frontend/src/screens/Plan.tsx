import { useEffect, useState, type FormEvent } from "react";
import { api, messageOf } from "../api";
import { Icon } from "../components/Icon";
import { Shell } from "../components/Shell";
import { GROUP_LABEL, money, monthLabel, parseAmount, shiftMonth } from "../format";
import { useApp } from "../state";
import { GROUPS, type BudgetLine, type Category, type Group } from "../types";

/** Presupuesto del mes (en colones) y las categorías del espacio, en una sola página. */
export function Plan() {
  const { space, month, categories, touch, version } = useApp();
  const [planned, setPlanned] = useState<Record<string, string>>({});
  const [status, setStatus] = useState<{ tone: "ok" | "error"; text: string } | null>(null);
  const [saving, setSaving] = useState(false);
  const readOnly = space?.archived ?? true;

  useEffect(() => {
    if (!space) return;
    let cancelled = false;
    setStatus(null);
    api.get<BudgetLine[]>(`/api/spaces/${space.id}/budgets/${month.year}/${month.month}`)
      .then((lines) => !cancelled && setPlanned(toForm(lines)))
      .catch(() => !cancelled && setPlanned({}));
    return () => {
      cancelled = true;
    };
  }, [space, month.year, month.month, version]);

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

        {GROUPS.filter((g) => g !== "INGRESO").map((group) => (
          <div key={group} className="plan-group">
            <h3 className="group-title">{GROUP_LABEL[group]}</h3>
            <ul className="rows">
              {categories.filter((c) => c.group === group && c.active).map((c) => (
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
                      onChange={(e) => setPlanned({ ...planned, [c.id]: e.target.value })}
                    />
                  </span>
                </li>
              ))}
            </ul>
          </div>
        ))}

        {status && <p className={status.tone === "ok" ? "form-ok" : "form-error"} role="status">{status.text}</p>}
        {!readOnly && (
          <div className="actions">
            <button type="button" className="button ghost" onClick={copyPrevious}>Copiar del mes anterior</button>
            <button type="button" className="button primary" onClick={save} disabled={saving}>
              {saving ? "Guardando…" : "Guardar presupuesto"}
            </button>
          </div>
        )}
      </section>

      <Categories categories={categories} readOnly={readOnly} spaceId={space.id} onChange={touch} />
    </Shell>
  );
}

function toForm(lines: BudgetLine[]): Record<string, string> {
  return Object.fromEntries(lines.map((l) => [l.categoryId, String(l.plannedAmount)]));
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
    <section className="plan" aria-labelledby="cat-title">
      <h2 id="cat-title" className="section-title">Categorías</h2>
      {GROUPS.map((g) => (
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
    </section>
  );
}

function CategoryItem({ category, base, readOnly, onChange }: {
  category: Category; base: string; readOnly: boolean; onChange: () => void;
}) {
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
      <div className="row-main">
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
          <span className="row-name">
            {category.name}
            {!category.active && <span className="tag">desactivada</span>}
          </span>
        )}
        {!readOnly && !editing && (
          <span className="row-tools">
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
              <button type="button" className="icon-button" aria-label={`Borrar ${category.name}`} onClick={() => setConfirm(true)}>
                <Icon name="trash" size={18} />
              </button>
            )}
          </span>
        )}
      </div>
      {error && <p className="form-error small" role="alert">{error}</p>}
    </li>
  );
}
