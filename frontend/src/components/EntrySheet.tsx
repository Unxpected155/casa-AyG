import { useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { createPortal } from "react-dom";
import { api, messageOf } from "../api";
import { GROUP_LABEL, parseAmount, todayIso } from "../format";
import { gsap, useGSAP, whenMotionAllowed } from "../motion";
import { useApp } from "../state";
import type { Currency, Group, Transaction } from "../types";
import { Icon } from "./Icon";

interface EntrySheetProps {
  /** Movimiento a editar; sin él, es uno nuevo. */
  editing?: Transaction | null;
  onClose: () => void;
}

/**
 * Hoja inferior para anotar un movimiento. El monto va primero y es lo más grande:
 * la meta es anotar un gasto en menos de 10 segundos, de pie en la caja.
 */
export function EntrySheet({ editing, onClose }: EntrySheetProps) {
  const { space, categories, touch } = useApp();
  const sheet = useRef<HTMLDivElement>(null);
  const backdrop = useRef<HTMLDivElement>(null);
  const amountInput = useRef<HTMLInputElement>(null);

  const editingCategory = categories.find((c) => c.id === editing?.categoryId);
  const [kind, setKind] = useState<"gasto" | "ingreso">(editingCategory?.group === "INGRESO" ? "ingreso" : "gasto");
  const [amount, setAmount] = useState(editing ? String(editing.amount).replace(".", ",") : "");
  const [currency, setCurrency] = useState<Currency>(editing?.currency ?? "CRC");
  const [categoryId, setCategoryId] = useState(editing?.categoryId ?? "");
  const [date, setDate] = useState(editing?.date ?? todayIso());
  const [description, setDescription] = useState(editing?.description ?? "");
  const [paymentMethod, setPaymentMethod] = useState(editing?.paymentMethod ?? "");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);

  const options = useMemo(() => {
    const groups: Group[] = kind === "ingreso" ? ["INGRESO"] : ["NECESIDAD", "DESEO", "AHORRO"];
    return groups.map((g) => ({
      group: g,
      items: categories.filter((c) => c.group === g && (c.active || c.id === categoryId)),
    }));
  }, [categories, kind, categoryId]);

  useGSAP(() => whenMotionAllowed(() => {
    gsap.from(backdrop.current, { autoAlpha: 0, duration: 0.2 });
    gsap.from(sheet.current, { yPercent: 100, duration: 0.32, ease: "expo.out" });
  }));

  useEffect(() => {
    amountInput.current?.focus();
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    document.addEventListener("keydown", onKey);
    document.body.classList.add("sheet-open");
    return () => {
      document.removeEventListener("keydown", onKey);
      document.body.classList.remove("sheet-open");
    };
  }, [onClose]);

  if (!space) return null;
  const base = `/api/spaces/${space.id}/transactions`;

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const parsed = parseAmount(amount);
    if (!parsed) return setError("Escribe un monto válido, por ejemplo 12 500 o 12,50.");
    if (!categoryId) return setError("Elige una categoría.");
    if (!description.trim()) return setError("Agrega una descripción corta.");
    setBusy(true);
    setError(null);
    try {
      const body = { categoryId, date, description: description.trim(), amount: parsed, currency,
                     paymentMethod: paymentMethod.trim() || null };
      if (editing) await api.put(`${base}/${editing.id}`, body);
      else await api.post(base, body);
      touch();
      onClose();
    } catch (e) {
      setError(messageOf(e, "No se pudo guardar."));
      setBusy(false);
    }
  };

  const remove = async () => {
    if (!editing) return;
    setBusy(true);
    try {
      await api.del(`${base}/${editing.id}`);
      touch();
      onClose();
    } catch (e) {
      setError(messageOf(e, "No se pudo borrar."));
      setBusy(false);
    }
  };

  // Portal a <body>: un ancestro con transform (la animación de página) volvería relativo
  // a él cualquier position: fixed, y la hoja quedaría cortada.
  return createPortal(
    <div className="sheet-layer">
      <div className="sheet-backdrop" ref={backdrop} onClick={onClose} />
      <div className="sheet" ref={sheet} role="dialog" aria-modal="true" aria-labelledby="sheet-title">
        <div className="sheet-head">
          <h2 id="sheet-title">{editing ? "Editar movimiento" : "Anotar"}</h2>
          <button type="button" className="icon-button" onClick={onClose} aria-label="Cerrar">
            <Icon name="close" />
          </button>
        </div>

        <form onSubmit={submit} noValidate>
          <div className="segmented" role="group" aria-label="Tipo">
            <button type="button" aria-pressed={kind === "gasto"} onClick={() => { setKind("gasto"); setCategoryId(""); }}>
              Gasto
            </button>
            <button type="button" aria-pressed={kind === "ingreso"} onClick={() => { setKind("ingreso"); setCategoryId(""); }}>
              Ingreso
            </button>
          </div>

          <div className="amount-field">
            <button
              type="button"
              className="currency"
              onClick={() => setCurrency(currency === "CRC" ? "USD" : "CRC")}
              aria-label={`Moneda: ${currency === "CRC" ? "colones" : "dólares"}. Cambiar`}
            >
              {currency === "CRC" ? "₡" : "$"}
            </button>
            <label className="visually-hidden" htmlFor="amount">Monto</label>
            <input
              id="amount"
              ref={amountInput}
              inputMode="decimal"
              autoComplete="off"
              placeholder="0"
              maxLength={18}
              value={amount}
              onChange={(e) => setAmount(e.target.value)}
            />
          </div>

          <fieldset className="chips">
            <legend>Categoría</legend>
            {options.map(({ group, items }) => items.length > 0 && (
              <div key={group} className="chip-group">
                {kind === "gasto" && <span className="chip-group-label">{GROUP_LABEL[group]}</span>}
                <div className="chip-row">
                  {items.map((c) => (
                    <button
                      key={c.id}
                      type="button"
                      className="chip"
                      aria-pressed={c.id === categoryId}
                      onClick={() => setCategoryId(c.id)}
                    >
                      {c.name}
                    </button>
                  ))}
                </div>
              </div>
            ))}
          </fieldset>

          <div className="field-row">
            <label className="field grow">
              <span>Descripción</span>
              <input value={description} maxLength={200} onChange={(e) => setDescription(e.target.value)}
                     placeholder={kind === "gasto" ? "Súper de la semana" : "Salario de noviembre"} />
            </label>
            <label className="field">
              <span>Fecha</span>
              <input type="date" value={date} min="2000-01-01" onChange={(e) => setDate(e.target.value)} />
            </label>
          </div>

          <details className="more" open={Boolean(editing?.paymentMethod)}>
            <summary>Más detalles</summary>
            <label className="field">
              <span>Medio de pago</span>
              <input value={paymentMethod} maxLength={30} onChange={(e) => setPaymentMethod(e.target.value)}
                     placeholder="Tarjeta, SINPE, efectivo…" />
            </label>
          </details>

          {error && <p className="form-error" role="alert">{error}</p>}

          <div className="sheet-actions">
            {editing && !confirmDelete && (
              <button type="button" className="button ghost danger" onClick={() => setConfirmDelete(true)}>
                <Icon name="trash" size={18} /> Borrar
              </button>
            )}
            {editing && confirmDelete && (
              <span className="confirm-inline">
                ¿Borrarlo?
                <button type="button" className="button ghost danger" onClick={remove} disabled={busy}>Sí, borrar</button>
                <button type="button" className="button ghost" onClick={() => setConfirmDelete(false)}>No</button>
              </span>
            )}
            <button type="submit" className="button primary" disabled={busy}>
              {busy ? "Guardando…" : editing ? "Guardar cambios" : "Anotar"}
            </button>
          </div>
        </form>
      </div>
    </div>,
    document.body,
  );
}
