import { useEffect, useLayoutEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { createPortal } from "react-dom";
import { api, messageOf } from "../api";
import { amountChange, amountToInput, GROUP_LABEL, money, parseAmount, todayIso } from "../format";
import { gsap, useGSAP, whenMotionAllowed } from "../motion";
import { navigate } from "../router";
import { useApp } from "../state";
import type { Currency, Group, Place, Suggestion, Transaction, TransactionPage, Transfer } from "../types";
import { orderedPlaces } from "./Balance";
import { Icon } from "./Icon";
import { useUndo } from "./Undo";
import { useDebts } from "../debts";

type Kind = "gasto" | "ingreso" | "mover";

export interface EntrySheetProps {
  /** Movimiento a editar; sin él, es uno nuevo. */
  editing?: Transaction | null;
  /** Dinero movido que se está viendo (no se edita: se borra y se anota de nuevo). */
  editingTransfer?: Transfer | null;
  /** Con qué pestaña abre una hoja nueva. */
  initialKind?: Kind;
  /** Lugar de origen sugerido al abrir en modo "mover". */
  initialFrom?: string;
  /** Hoja nueva ya llena (pagar un pago fijo o abonar a una deuda). */
  preset?: {
    categoryId: string;
    description: string;
    amount: number | null;
    currency: Currency;
    placeId: string | null;
    recurringId?: string;
    debtId?: string;
    /** Lo más que se puede anotar (lo que falta de la deuda). */
    max?: number;
    /** Título de la hoja; si no, «Pagar …». */
    title?: string;
  };
  onClose: () => void;
}

/**
 * Hoja inferior para anotar. El monto va primero y es lo más grande: la meta es anotar un
 * gasto en menos de 10 segundos, de pie en la caja. La descripción va antes que la categoría
 * porque la app sugiere la categoría a partir de lo que escribes.
 */
export function EntrySheet({ editing, editingTransfer, initialKind, initialFrom, preset, onClose }: EntrySheetProps) {
  const { space, categories, places, touch, memberName } = useApp();
  const undo = useUndo();
  const sheet = useRef<HTMLDialogElement>(null);
  const amountInput = useRef<HTMLInputElement>(null);

  const editingCategory = categories.find((c) => c.id === editing?.categoryId);
  const [kind, setKind] = useState<Kind>(
    editingTransfer ? "mover" : editing ? (editingCategory?.group === "INGRESO" ? "ingreso" : "gasto") : initialKind ?? "gasto");
  const [amount, setAmount] = useState(editing ? amountToInput(editing.amount)
    : preset?.amount != null ? amountToInput(preset.amount) : "");
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
  // «Es un abono a…»: un gasto ya anotado se puede ligar a una deuda (o soltar). No aplica al
  // diezmo, a un pago fijo ni a lo que anotó el cierre del mes.
  const linkable = Boolean(editing && !editing.titheOf && !editing.recurringId && !editing.fromClose);
  const { debts } = useDebts(linkable);
  const [debtId, setDebtId] = useState(editing?.debtId ?? "");
  const debtOptions = (debts ?? []).filter((d) => d.id === editing?.debtId || (d.remaining > 0 && d.currency === currency));
  const chosenDebt = debtOptions.find((d) => d.id === debtId);
  // Lo más que puede ser este abono: lo que falta, más lo que ya era suyo si ya estaba ligado.
  const debtRoom = chosenDebt ? chosenDebt.remaining + (editing?.debtId === chosenDebt.id ? editing.amount : 0) : null;
  // Un movimiento que se parece a uno ya anotado (mismo día, monto y descripción): se pregunta
  // antes de guardar. Pasa sobre todo en el espacio familiar, cuando los dos anotan lo mismo.
  const [duplicate, setDuplicate] = useState<Transaction | null>(null);
  // Si se corrige lo anotado, la pregunta ya no aplica: se vuelve a revisar al guardar.
  useEffect(() => setDuplicate(null), [amount, description, date, currency]);
  const duplicateBox = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (duplicate) duplicateBox.current?.scrollIntoView({ block: "center", behavior: "smooth" });
  }, [duplicate]);
  // Diezmo: siempre marcado en un ingreso nuevo; al editar, según si ya lo tiene.
  const [tithe, setTithe] = useState(editing ? Boolean(editing.titheId) : true);
  const [gross, setGross] = useState(editing?.grossAmount ? amountToInput(editing.grossAmount) : "");
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

  // <dialog> nativo en modo modal: el navegador deja la página de atrás inerte, mantiene el foco
  // adentro, y Esc o el gesto de «atrás» de Android la cierran (evento close → onClose).
  // Va antes de la animación: el monto solo se puede enfocar con la hoja ya abierta.
  useLayoutEffect(() => {
    const dialog = sheet.current;
    if (dialog && !dialog.open) dialog.showModal();
  }, []);

  useGSAP(() => {
    let animated = false;
    const revert = whenMotionAllowed(() => {
      animated = true;
      gsap.from(sheet.current, { yPercent: 100, duration: 0.32, ease: "expo.out", onComplete: focusAmount });
    });
    if (!animated) focusAmount();
    return revert;
  });

  // Al abrir una hoja nueva: el último lugar que usaste.
  useEffect(() => {
    if (!space || editing || editingTransfer) return;
    let cancelled = false;
    api.get<Suggestion>(`/api/spaces/${space.id}/transactions/suggest`)
      .then((s) => {
        if (cancelled) return;
        // Sin historial: si hay un solo lugar, ese. Un movimiento sin lugar no mueve ningún saldo.
        const open = places.filter((p) => !p.archived);
        const last = places.find((p) => p.id === s.placeId && !p.archived) ?? (open.length === 1 ? open[0] : undefined);
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
      .then((s) => !cancelled && setGross(s.lastGross ? amountToInput(s.lastGross) : ""))
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

  // El lugar es obligatorio: tocar el elegido no lo quita.
  const choosePlace = (p: Place) => {
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
    if (!parsed) return setError("Escribe un monto válido, por ejemplo 12.500 o 12,50.");
    if (kind === "mover") {
      if (!from || !to) return setError("Elige de dónde sale y a dónde va el dinero.");
      if (from === to) return setError("El origen y el destino tienen que ser distintos.");
      if (parsed === "0" || Number(parsed) === 0) return setError("El monto tiene que ser mayor que cero.");
    } else {
      if (!placeId) return setError(kind === "gasto" ? "Elige de dónde sale el dinero." : "Elige a dónde entra el dinero.");
      if (!categoryId) return setError("Elige una categoría.");
      if (!description.trim()) return setError("Agrega una descripción corta.");
      if (kind === "ingreso" && gross.trim() && !parsedGross) return setError("Revisa el monto bruto.");
      if (preset?.max != null && Number(parsed) > preset.max) {
        return setError(`Solo faltan ${money(preset.max, currency, true)}. Escribe ese monto o uno menor.`);
      }
      if (preset?.debtId && Number(parsed) === 0) return setError("El abono tiene que ser mayor que cero.");
      if (kind === "gasto" && debtRoom != null && Number(parsed) > debtRoom) {
        return setError(`A esa deuda solo le faltan ${money(debtRoom, currency, true)}. Escribe ese monto o uno menor.`);
      }
    }
    setBusy(true);
    setError(null);
    if (kind !== "mover" && !editing && !duplicate) {
      const twin = await findDuplicate(parsed);
      if (twin) {
        setDuplicate(twin);
        setBusy(false);
        return;
      }
    }
    try {
      if (kind === "mover") {
        await api.post(`${base}/transfers`, { from, to, date, amount: parsed, description: description.trim() || null });
      } else {
        const income = kind === "ingreso";
        const body = { categoryId, date, description: description.trim(), amount: parsed, currency,
                       paymentMethod: editing?.paymentMethod ?? null, placeId,
                       grossAmount: income ? parsedGross : null, tithe: income && tithe,
                       recurringId: editing ? undefined : preset?.recurringId ?? null,
                       debtId: editing ? undefined : preset?.debtId ?? null };
        if (editing) {
          const before = editing.debtId ?? null;
          const after = kind === "gasto" && debtId ? debtId : null;
          // Soltar primero (para poder cambiarlo a ingreso o subirle el monto); ligar después.
          if (before && before !== after) await api.put(`${base}/transactions/${editing.id}/debt`, { debtId: null });
          await api.put(`${base}/transactions/${editing.id}`, body);
          if (after && after !== before) await api.put(`${base}/transactions/${editing.id}/debt`, { debtId: after });
        } else {
          await api.post(`${base}/transactions`, body);
        }
      }
      touch();
      onClose();
    } catch (e) {
      setError(messageOf(e, "No se pudo guardar."));
      setBusy(false);
    }
  };

  /** El mismo día, monto, moneda y descripción (sin importar mayúsculas ni tildes). */
  const findDuplicate = async (parsed: string): Promise<Transaction | null> => {
    const [y, m] = date.split("-").map(Number);
    const params = new URLSearchParams({ year: String(y), month: String(m), size: "100", q: description.trim() });
    try {
      const page = await api.get<TransactionPage>(`${base}/transactions?${params}`);
      const norm = (t: string) => t.normalize("NFD").replace(/\p{M}/gu, "").trim().toLowerCase();
      return page.items.find((t) => t.date === date && t.currency === currency
        && Number(t.amount) === Number(parsed) && norm(t.description) === norm(description) && !t.titheOf) ?? null;
    } catch {
      return null; // si no se puede revisar, no se bloquea el anotar
    }
  };

  // Se borra en unos segundos, con «Deshacer» mientras tanto (components/Undo.tsx).
  const remove = () => {
    if (editingTransfer) {
      const id = editingTransfer.id;
      undo.schedule(id, "Borraste el movimiento de dinero.", () => api.del(`${base}/transfers/${id}`));
    } else if (editing) {
      const id = editing.id;
      undo.schedule(id, `Borraste «${editing.description}».`, () => api.del(`${base}/transactions/${id}`));
    }
    onClose();
  };

  const placeName = (id: string) => places.find((p) => p.id === id)?.name ?? "una cuenta";
  const title = editingTransfer ? "Dinero movido" : editing ? "Editar movimiento" : preset ? preset.title ?? `Pagar ${preset.description}` : "Anotar";
  const destinations = activePlaces.filter((p) => p.id !== from && (!fromPlace || p.currency === fromPlace.currency));

  // Portal a <body> para que la hoja no dependa de la página que la abre (el top layer de
  // <dialog> ya la saca de cualquier ancestro con transform).
  return createPortal(
    <dialog
      className="sheet"
      ref={sheet}
      aria-labelledby="sheet-title"
      // Tocar fuera de la hoja la cierra (closedby="any"; donde no existe, el clic en el fondo).
      {...({ closedby: "any" } as Record<string, string>)}
      onClose={onClose}
      onClick={(e) => {
        // El clic en el relleno de la hoja también tiene como target el <dialog>: solo cierra
        // si cae fuera de su rectángulo (en el fondo).
        const d = sheet.current;
        if (!d || e.target !== d) return;
        const r = d.getBoundingClientRect();
        if (e.clientX < r.left || e.clientX > r.right || e.clientY < r.top || e.clientY > r.bottom) d.close();
      }}
    >
        <div className="sheet-head">
          <h2 id="sheet-title">{title}</h2>
          <button type="button" className="icon-button" onClick={() => sheet.current?.close()} aria-label="Cerrar">
            <Icon name="close" />
          </button>
        </div>

        {editing?.fromClose ? (
          <div className="transfer-view">
            <p className="overview-line">
              <strong className="figure">{money(editing.amount, editing.currency, true)}</strong> · {editing.description}
            </p>
            <p className="hint">
              Esto lo anotó el cierre del mes, así que no se cambia a mano. Si hubo un error, reabran el mes en «La casa»
              y ciérrenlo de nuevo.
            </p>
            <div className="sheet-actions">
              <button type="button" className="button secondary" onClick={() => { onClose(); navigate("/casa"); }}>
                Ir a La casa
              </button>
            </div>
          </div>
        ) : editingTransfer ? (
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
            {/* Un abono siempre es un gasto: sin pestañas. */}
            {!preset?.debtId && (
              <div className="segmented" role="group" aria-label="Tipo">
                <button type="button" aria-pressed={kind === "gasto"} onClick={() => chooseKind("gasto")}>Gasto</button>
                <button type="button" aria-pressed={kind === "ingreso"} onClick={() => chooseKind("ingreso")}>Ingreso</button>
                {!editing && activePlaces.length > 1 && (
                  <button type="button" aria-pressed={kind === "mover"} onClick={() => chooseKind("mover")}>Mover</button>
                )}
              </div>
            )}

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
                onChange={(e) => setAmount(amountChange(e))}
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
                         placeholder={kind === "gasto" ? "Súper de la semana" : "Salario del mes"} />
                </label>

                {activePlaces.length > 0 && (
                  <PlaceChips legend={kind === "gasto" ? "Sale de" : "Entra a"} places={activePlaces}
                              selected={placeId ?? ""} onSelect={choosePlace} />
                )}
                {activePlaces.length === 0 && (
                  <div className="notice">
                    <p>Todo lo que anotas sale de una cuenta o entra a una. Primero agrega la tuya.</p>
                    <button type="button" className="button secondary small" onClick={() => { onClose(); navigate("/lugares"); }}>
                      Agregar una cuenta
                    </button>
                  </div>
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
                                 onChange={(e) => { setGross(amountChange(e)); setGrossTouched(true); }} />
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
                {kind === "gasto" && linkable && debtOptions.length > 0 && (
                  <label className="field">
                    <span>Es un abono a</span>
                    <select value={debtId} onChange={(e) => setDebtId(e.target.value)} aria-describedby="debt-room">
                      <option value="">No es un abono</option>
                      {debtOptions.map((d) => (
                        <option key={d.id} value={d.id}>{d.creditor} · {d.concept}</option>
                      ))}
                    </select>
                    {debtRoom != null && (
                      <span className="hint" id="debt-room">Le faltan {money(debtRoom, currency, true)}, contando este gasto.</span>
                    )}
                  </label>
                )}
              </>
            )}

            <div className="field-row">
              {kind === "mover" && (
                <label className="field grow">
                  <span>Nota (opcional)</span>
                  <input value={description} maxLength={200} onChange={(e) => setDescription(e.target.value)}
                         placeholder="Ahorro del mes" />
                </label>
              )}
              <label className="field">
                <span>Fecha</span>
                <input type="date" value={date} min="2000-01-01" onChange={(e) => setDate(e.target.value)} />
              </label>
            </div>

            {error && <p className="form-error" role="alert">{error}</p>}
            {duplicate && (
              <div className="duplicate" role="alert" ref={duplicateBox}>
                <p>
                  ¿Ya lo anotaste? Hay <strong>{duplicate.description}</strong> por{" "}
                  <strong className="figure">{money(duplicate.amount, duplicate.currency, true)}</strong> el mismo día
                  {(space.members.length > 1) && <>, anotado por {memberName(duplicate.createdBy)}</>}.
                </p>
                <div className="actions start">
                  <button type="submit" className="button secondary small">Sí, anotarlo igual</button>
                  <button type="button" className="button ghost small" onClick={onClose}>No, cancelar</button>
                </div>
              </div>
            )}

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
    </dialog>,
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
