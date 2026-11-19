import { useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { createPortal } from "react-dom";
import { api, messageOf } from "../api";
import { GROUP_LABEL, money, parseAmount, todayIso } from "../format";
import { gsap, useGSAP, whenMotionAllowed } from "../motion";
import { useApp } from "../state";
import type { Currency, Group, Place, Suggestion, Transaction, Transfer } from "../types";
import { orderedPlaces } from "./Balance";
import { Icon } from "./Icon";

type Kind = "gasto" | "ingreso" | "mover";

interface EntrySheetProps {
  /** Movimiento a editar; sin él, es uno nuevo. */
  editing?: Transaction | null;
  /** Dinero movido que se está viendo (no se edita: se borra y se anota de nuevo). */
  editingTransfer?: Transfer | null;
  /** Con qué pestaña abre una hoja nueva. */
  initialKind?: Kind;
  /** Lugar de origen sugerido al abrir en modo "mover". */
  initialFrom?: string;
  /** Hoja nueva ya llena (pagar un pago fijo). */
  preset?: {
    categoryId: string;
    description: string;
    amount: number | null;
    currency: Currency;
    placeId: string | null;
    recurringId: string;
  };
  onClose: () => void;
}

/**
 * Hoja inferior para anotar. El monto va primero y es lo más grande: la meta es anotar un
 * gasto en menos de 10 segundos, de pie en la caja. La descripción va antes que la categoría
 * porque la app sugiere la categoría a partir de lo que escribes.
 */
export function EntrySheet({ editing, editingTransfer, initialKind, initialFrom, preset, onClose }: EntrySheetProps) {
  const { space, categories, places, touch } = useApp();
  const sheet = useRef<HTMLDivElement>(null);
  const backdrop = useRef<HTMLDivElement>(null);
  const amountInput = useRef<HTMLInputElement>(null);

  const editingCategory = categories.find((c) => c.id === editing?.categoryId);
  const [kind, setKind] = useState<Kind>(
    editingTransfer ? "mover" : editing ? (editingCategory?.group === "INGRESO" ? "ingreso" : "gasto") : initialKind ?? "gasto");
  const [amount, setAmount] = useState(editing ? String(editing.amount).replace(".", ",")
    : preset?.amount != null ? String(preset.amount).replace(".", ",") : "");
  const [currency, setCurrency] = useState<Currency>(editing?.currency ?? preset?.currency ?? "CRC");
  const [categoryId, setCategoryId] = useState(editing?.categoryId ?? preset?.categoryId ?? "");
  const [categoryTouched, setCategoryTouched] = useState(Boolean(editing || preset));
  const [suggested, setSuggested] = useState(false);
  const [placeId, setPlaceId] = useState<string | null>(editing ? editing.placeId : preset?.placeId ?? null);
  const [from, setFrom] = useState(initialFrom ?? "");
  const [to, setTo] = useState("");
  const [date, setDate] = useState(editing?.date ?? todayIso());
  const [description, setDescription] = useState(editing?.description ?? preset?.description ?? "");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);
  // Diezmo: siempre marcado en un ingreso nuevo; al editar, según si ya lo tiene.
  const [tithe, setTithe] = useState(editing ? Boolean(editing.titheId) : true);
  const [gross, setGross] = useState(editing?.grossAmount ? String(editing.grossAmount).replace(".", ",") : "");
  const [grossTouched, setGrossTouched] = useState(Boolean(editing));

  const activePlaces = useMemo(
    () => orderedPlaces(places).map((r) => r.place).filter((p) => !p.archived || p.id === placeId),
    [places, placeId]);
  const place = places.find((p) => p.id === placeId) ?? null;
  const fromPlace = places.find((p) => p.id === from) ?? null;

  const options = useMemo(() => {
    const groups: Group[] = kind === "ingreso" ? ["INGRESO"] : ["NECESIDAD", "DESEO", "AHORRO"];
    return groups.map((g) => ({
      group: g,
      items: categories.filter((c) => c.group === g && (c.active || c.id === categoryId)),
    }));
  }, [categories, kind, categoryId]);

  // El monto se enfoca cuando la hoja YA está en su lugar y sin desplazar la vista. Si se enfoca
  // mientras sube, el teléfono abre el teclado y corre la pantalla hacia donde estaba el campo
  // (abajo, fuera de la vista), y la hoja queda "empezando desde abajo".
  const focusAmount = () => {
    sheet.current?.scrollTo({ top: 0 });
    if (!editingTransfer) amountInput.current?.focus({ preventScroll: true });
  };

  useGSAP(() => {
    let animated = false;
    const revert = whenMotionAllowed(() => {
      animated = true;
      gsap.from(backdrop.current, { autoAlpha: 0, duration: 0.2 });
      gsap.from(sheet.current, { yPercent: 100, duration: 0.32, ease: "expo.out", onComplete: focusAmount });
    });
    if (!animated) focusAmount();
    return revert;
  });

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    document.addEventListener("keydown", onKey);
    document.body.classList.add("sheet-open");
    return () => {
      document.removeEventListener("keydown", onKey);
      document.body.classList.remove("sheet-open");
    };
  }, [onClose]);

  // Al abrir una hoja nueva: el último lugar que usaste.
  useEffect(() => {
    if (!space || editing || editingTransfer) return;
    let cancelled = false;
    api.get<Suggestion>(`/api/spaces/${space.id}/transactions/suggest`)
      .then((s) => {
        if (cancelled || !s.placeId) return;
        const last = places.find((p) => p.id === s.placeId && !p.archived);
        if (last) {
          setPlaceId((current) => current ?? last.id);
          setCurrency(last.currency);
        }
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
    // Solo al abrir: después manda lo que elijas.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [space?.id]);

  // Mientras escribes la descripción: la categoría que usaste antes con ese nombre.
  useEffect(() => {
    if (!space || categoryTouched || kind === "mover" || description.trim().length < 2) return;
    let cancelled = false;
    const timer = window.setTimeout(() => {
      api.get<Suggestion>(`/api/spaces/${space.id}/transactions/suggest?description=${encodeURIComponent(description.trim())}`)
        .then((s) => {
          if (cancelled || !s.categoryId) return;
          const allowed = options.some((o) => o.items.some((c) => c.id === s.categoryId));
          if (allowed) {
            setCategoryId(s.categoryId);
            setSuggested(true);
          }
        })
        .catch(() => undefined);
    }, 350);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [space, description, categoryTouched, kind, options]);

  // Al elegir la categoría de un ingreso: el último bruto que anotaste en ella (tu salario bruto).
  useEffect(() => {
    if (!space || kind !== "ingreso" || !categoryId || grossTouched) return;
    let cancelled = false;
    api.get<Suggestion>(`/api/spaces/${space.id}/transactions/suggest?categoryId=${encodeURIComponent(categoryId)}`)
      .then((s) => !cancelled && setGross(s.lastGross ? String(s.lastGross).replace(".", ",") : ""))
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [space, kind, categoryId, grossTouched]);

  if (!space) return null;
  const base = `/api/spaces/${space.id}`;
  const parsedGross = gross.trim() ? parseAmount(gross) : null;
  const titheBase = parsedGross ?? parseAmount(amount);
  const titheAmount = titheBase ? Math.round(Number(titheBase) * 10) / 100 : 0;

  const choosePlace = (p: Place) => {
    if (p.id === placeId) {
      setPlaceId(null);
      return;
    }
    setPlaceId(p.id);
    setCurrency(p.currency);
  };

  const chooseKind = (next: Kind) => {
    setKind(next);
    setCategoryId("");
    setSuggested(false);
    setCategoryTouched(false);
    setError(null);
  };

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const parsed = parseAmount(amount);
    if (!parsed) return setError("Escribe un monto válido, por ejemplo 12 500 o 12,50.");
    if (kind === "mover") {
      if (!from || !to) return setError("Elige de dónde sale y a dónde va el dinero.");
      if (from === to) return setError("El origen y el destino tienen que ser distintos.");
      if (parsed === "0" || Number(parsed) === 0) return setError("El monto tiene que ser mayor que cero.");
    } else {
      if (!categoryId) return setError("Elige una categoría.");
      if (!description.trim()) return setError("Agrega una descripción corta.");
      if (kind === "ingreso" && gross.trim() && !parsedGross) return setError("Revisa el monto bruto.");
    }
    setBusy(true);
    setError(null);
    try {
      if (kind === "mover") {
        await api.post(`${base}/transfers`, { from, to, date, amount: parsed, description: description.trim() || null });
      } else {
        const income = kind === "ingreso";
        const body = { categoryId, date, description: description.trim(), amount: parsed, currency,
                       paymentMethod: editing?.paymentMethod ?? null, placeId,
                       grossAmount: income ? parsedGross : null, tithe: income && tithe,
                       recurringId: editing ? undefined : preset?.recurringId ?? null };
        if (editing) await api.put(`${base}/transactions/${editing.id}`, body);
        else await api.post(`${base}/transactions`, body);
      }
      touch();
      onClose();
    } catch (e) {
      setError(messageOf(e, "No se pudo guardar."));
      setBusy(false);
    }
  };

  const remove = async () => {
    setBusy(true);
    try {
      if (editingTransfer) await api.del(`${base}/transfers/${editingTransfer.id}`);
      else if (editing) await api.del(`${base}/transactions/${editing.id}`);
      touch();
      onClose();
    } catch (e) {
      setError(messageOf(e, "No se pudo borrar."));
      setBusy(false);
    }
  };

  const placeName = (id: string) => places.find((p) => p.id === id)?.name ?? "un lugar";
  const title = editingTransfer ? "Dinero movido" : editing ? "Editar movimiento" : preset ? `Pagar ${preset.description}` : "Anotar";
  const destinations = activePlaces.filter((p) => p.id !== from && (!fromPlace || p.currency === fromPlace.currency));

  // Portal a <body>: un ancestro con transform (la animación de página) volvería relativo
  // a él cualquier position: fixed, y la hoja quedaría cortada.
  return createPortal(
    <div className="sheet-layer">
      <div className="sheet-backdrop" ref={backdrop} onClick={onClose} />
      <div className="sheet" ref={sheet} role="dialog" aria-modal="true" aria-labelledby="sheet-title">
        <div className="sheet-head">
          <h2 id="sheet-title">{title}</h2>
          <button type="button" className="icon-button" onClick={onClose} aria-label="Cerrar">
            <Icon name="close" />
          </button>
        </div>

        {editingTransfer ? (
          <div className="transfer-view">
            <p className="overview-line">
              <strong className="figure">{money(editingTransfer.amount, editingTransfer.currency, true)}</strong>{" "}
              de {placeName(editingTransfer.fromPlace)} a {placeName(editingTransfer.toPlace)}.
            </p>
            {editingTransfer.description && <p className="muted">{editingTransfer.description}</p>}
            <p className="hint">Para cambiarlo, bórralo y anótalo de nuevo.</p>
            {error && <p className="form-error" role="alert">{error}</p>}
            <div className="sheet-actions">
              {!confirmDelete ? (
                <button type="button" className="button ghost danger" onClick={() => setConfirmDelete(true)}>
                  <Icon name="trash" size={18} /> Borrar
                </button>
              ) : (
                <span className="confirm-inline">
                  ¿Borrarlo?
                  <button type="button" className="button ghost danger" onClick={remove} disabled={busy}>Sí, borrar</button>
                  <button type="button" className="button ghost" onClick={() => setConfirmDelete(false)}>No</button>
                </span>
              )}
              <button type="button" className="button primary" onClick={onClose}>Listo</button>
            </div>
          </div>
        ) : (
          <form onSubmit={submit} noValidate>
            <div className="segmented" role="group" aria-label="Tipo">
              <button type="button" aria-pressed={kind === "gasto"} onClick={() => chooseKind("gasto")}>Gasto</button>
              <button type="button" aria-pressed={kind === "ingreso"} onClick={() => chooseKind("ingreso")}>Ingreso</button>
              {!editing && activePlaces.length > 1 && (
                <button type="button" aria-pressed={kind === "mover"} onClick={() => chooseKind("mover")}>Mover</button>
              )}
            </div>

            <div className="amount-field">
              {kind === "mover" || place ? (
                <span className="currency static" aria-hidden="true">
                  {(kind === "mover" ? fromPlace?.currency ?? "CRC" : currency) === "CRC" ? "₡" : "$"}
                </span>
              ) : (
                <button
                  type="button"
                  className="currency"
                  onClick={() => setCurrency(currency === "CRC" ? "USD" : "CRC")}
                  aria-label={`Moneda: ${currency === "CRC" ? "colones" : "dólares"}. Cambiar`}
                >
                  {currency === "CRC" ? "₡" : "$"}
                </button>
              )}
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

            {kind === "mover" ? (
              <>
                <PlaceChips legend="Sale de" places={activePlaces} selected={from}
                            onSelect={(p) => { setFrom(p.id); if (to && places.find((x) => x.id === to)?.currency !== p.currency) setTo(""); }} />
                <PlaceChips legend="Va a" places={destinations} selected={to} onSelect={(p) => setTo(p.id)}
                            empty="Elige primero de dónde sale." />
              </>
            ) : (
              <>
                <label className="field">
                  <span>Descripción</span>
                  <input value={description} maxLength={200} onChange={(e) => setDescription(e.target.value)}
                         placeholder={kind === "gasto" ? "Súper de la semana" : "Salario de noviembre"} />
                </label>

                {activePlaces.length > 0 && (
                  <PlaceChips legend={kind === "gasto" ? "Sale de" : "Entra a"} places={activePlaces}
                              selected={placeId ?? ""} onSelect={choosePlace} />
                )}

                <fieldset className="chips">
                  <legend>
                    Categoría
                    {suggested && !categoryTouched && <span className="suggested"> · sugerida por lo que anotaste antes</span>}
                  </legend>
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
                            onClick={() => { setCategoryId(c.id); setCategoryTouched(true); }}
                          >
                            {c.name}
                          </button>
                        ))}
                      </div>
                    </div>
                  ))}
                </fieldset>

                {kind === "ingreso" && (
                  <div className="tithe">
                    <label className="check">
                      <input type="checkbox" checked={tithe} onChange={(e) => setTithe(e.target.checked)} />
                      <span>Dar diezmo (10 % del bruto)</span>
                    </label>
                    {tithe && (
                      <div className="field-row">
                        <label className="field grow">
                          <span>Monto bruto</span>
                          <input inputMode="decimal" maxLength={18} value={gross} placeholder={amount || "Igual al monto"}
                                 onChange={(e) => { setGross(e.target.value); setGrossTouched(true); }} />
                        </label>
                        <p className="tithe-amount" aria-live="polite">
                          Diezmo <strong className="figure">{money(titheAmount, currency, true)}</strong>
                        </p>
                      </div>
                    )}
                  </div>
                )}
                {editing?.titheOf && (
                  <p className="muted small">Este diezmo se anotó con su ingreso: si cambias el ingreso, se ajusta solo.</p>
                )}
              </>
            )}

            <div className="field-row">
              {kind === "mover" && (
                <label className="field grow">
                  <span>Nota (opcional)</span>
                  <input value={description} maxLength={200} onChange={(e) => setDescription(e.target.value)}
                         placeholder="Ahorro de noviembre" />
                </label>
              )}
              <label className="field">
                <span>Fecha</span>
                <input type="date" value={date} min="2000-01-01" onChange={(e) => setDate(e.target.value)} />
              </label>
            </div>

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
                {busy ? "Guardando…" : editing ? "Guardar cambios" : kind === "mover" ? "Mover" : "Anotar"}
              </button>
            </div>
          </form>
        )}
      </div>
    </div>,
    document.body,
  );
}

function PlaceChips({ legend, places, selected, onSelect, empty }: {
  legend: string;
  places: Place[];
  selected: string;
  onSelect: (place: Place) => void;
  empty?: string;
}) {
  // La moneda solo se marca si hay lugares en colones y en dólares.
  const mixed = new Set(places.map((p) => p.currency)).size > 1;
  return (
    <fieldset className="chips">
      <legend>{legend}</legend>
      {places.length === 0 && empty && <p className="muted small">{empty}</p>}
      <div className="chip-row">
        {places.map((p) => (
          <button key={p.id} type="button" className="chip" aria-pressed={p.id === selected} onClick={() => onSelect(p)}>
            {p.name}{mixed && <span className="chip-note"> {p.currency === "USD" ? "$" : "₡"}</span>}
          </button>
        ))}
      </div>
    </fieldset>
  );
}
