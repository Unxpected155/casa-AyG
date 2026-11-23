import { useEffect, useState, type FormEvent } from "react";
import { api, messageOf, withPasskeyConfirm } from "../api";
import { isReserved } from "../components/Balance";
import { Icon } from "../components/Icon";
import { Shell } from "../components/Shell";
import { amountChange, amountToInput, currentMonth, money, monthLabel, parseAmount, shiftMonth, todayIso, type YearMonth } from "../format";
import { navigate } from "../router";
import { useApp } from "../state";
import type { ClosePreview, Household, Place } from "../types";

type Message = { tone: "ok" | "error"; text: string } | null;

/** La casa: lo personal de cada quien (solo el saldo), aportes y el cierre del mes. */
export function House() {
  const { spaces, touch, version, selectSpace } = useApp();
  const family = spaces.find((s) => s.kind === "FAMILIA" && !s.archived);
  const [house, setHouse] = useState<Household | null>(null);
  const [homePlaces, setHomePlaces] = useState<Place[]>([]);
  const [homeLoaded, setHomeLoaded] = useState(false);
  const [myPlaces, setMyPlaces] = useState<Place[]>([]);
  const [message, setMessage] = useState<Message>(null);
  const [tick, setTick] = useState(0);

  useEffect(() => {
    if (!family) return;
    let cancelled = false;
    api.get<Household>(`/api/spaces/${family.id}/household`).then((h) => {
      if (cancelled) return;
      setHouse(h);
      if (h.myPersonalSpace) {
        api.get<Place[]>(`/api/spaces/${h.myPersonalSpace}/places`)
          .then((list) => !cancelled && setMyPlaces(list.filter((p) => !p.archived)))
          .catch(() => !cancelled && setMyPlaces([]));
      }
    }).catch((e) => !cancelled && setMessage({ tone: "error", text: messageOf(e, "No se pudo cargar la casa.") }));
    api.get<Place[]>(`/api/spaces/${family.id}/places`)
      .then((list) => !cancelled && setHomePlaces(list.filter((p) => !p.archived)))
      .catch(() => !cancelled && setHomePlaces([]))
      .finally(() => !cancelled && setHomeLoaded(true));
    return () => {
      cancelled = true;
    };
  }, [family?.id, tick, version]);

  // El aviso va arriba: se lleva a la vista, porque la acción suele estar más abajo.
  const show = (m: Message) => {
    setMessage(m);
    if (m) requestAnimationFrame(() => document.getElementById("casa-status")?.scrollIntoView({ block: "center", behavior: "smooth" }));
  };
  // Si se llegó con el atajo «Aportar a la casa», baja directo a esa sección.
  useEffect(() => {
    if ((window.history.state as { focus?: string } | null)?.focus !== "aportar") return;
    // La sección aparece cuando ya cargaron los lugares de los dos lados.
    requestAnimationFrame(() => {
      const target = document.getElementById("contrib-title");
      if (!target) return;
      window.history.replaceState(null, "");
      target.scrollIntoView({ block: "start", behavior: "smooth" });
    });
  }, [house, myPlaces.length, homePlaces.length]);

  const changed = (text: string) => {
    show({ tone: "ok", text });
    setTick((t) => t + 1);
    touch();
  };

  if (!family) {
    return (
      <Shell>
        <h1 className="page-title">La casa</h1>
        <section className="empty">
          <p className="empty-title">Todavía no tienen espacio familiar.</p>
          <p>Cuando se casen, lo crean entre los dos desde «Espacio familiar».</p>
          <button type="button" className="button secondary" onClick={() => navigate("/boda")}>
            <Icon name="rings" size={18} /> Ir a espacio familiar
          </button>
        </section>
      </Shell>
    );
  }

  return (
    <Shell>
      <h1 className="page-title">La casa</h1>
      <p className="hint">Todo el dinero entra a «{family.name}». De ahí sale lo personal de cada quien al cerrar el mes.</p>
      {message && <p id="casa-status" className={message.tone === "ok" ? "form-ok" : "form-error"} role="status">{message.text}</p>}

      {house && (
        <>
          <section className="block" aria-labelledby="partners-title">
            <h2 id="partners-title" className="section-title">Lo personal de cada quien</h2>
            <ul className="rows">
              {[...house.partners].sort((a, b) => Number(b.me) - Number(a.me)).map((p) => (
                <li key={p.userId} className="row">
                  <div className="row-main">
                    <span className="row-name">{p.me ? "Tú" : p.displayName}</span>
                    <span className="figure">
                      {money(p.crc, "CRC")}
                      {p.usd !== 0 && <> · {money(p.usd, "USD")}</>}
                    </span>
                  </div>
                </li>
              ))}
            </ul>
            <p className="hint">De tu pareja solo ves cuánto tiene, nunca en qué lo gasta.</p>
          </section>

          {homeLoaded && homePlaces.length === 0 ? (
            <FirstSteps onGo={(route) => { selectSpace(family.id); navigate(route); }} />
          ) : (
            <CloseMonth familyId={family.id} house={house} homePlaces={homePlaces} onDone={changed} onError={show} />
          )}

          <Contribute familyId={family.id} myPlaces={spendable(myPlaces)} homePlaces={homePlaces} onDone={changed} onError={show} />

          <Destination house={house} myPlaces={myPlaces} onDone={changed} onError={show} />

          <SettingsForm familyId={family.id} house={house} onDone={changed} onError={show} />

          {house.closes.length > 0 && (
            <section className="block" aria-labelledby="closes-title">
              <h2 id="closes-title" className="section-title">Meses cerrados</h2>
              <ul className="rows">
                {house.closes.map((c) => (
                  <li key={`${c.year}-${c.month}`} className="row">
                    <div className="row-main">
                      <span className="row-name">{monthLabel({ year: c.year, month: c.month })} {c.year}</span>
                      <span className="row-tools">
                        <span className="muted small">sobró {money(c.surplus, "CRC")} · bono {String(c.bonusPct).replace(".", ",")} %</span>
                        <span className="figure">{money(c.perPerson, "CRC")} c/u</span>
                      </span>
                    </div>
                  </li>
                ))}
              </ul>
            </section>
          )}
        </>
      )}
    </Shell>
  );
}

/** Una casa recién creada no tiene cuentas: primero eso, luego el monto fijo y el presupuesto. */
function FirstSteps({ onGo }: { onGo: (route: "/lugares" | "/plan") => void }) {
  return (
    <section className="block" aria-labelledby="start-title">
      <h2 id="start-title" className="section-title">Para empezar</h2>
      <ol className="explain">
        <li>
          Agreguen la cuenta de la casa: la conjunta, donde entran los salarios.{" "}
          <button type="button" className="button secondary small" onClick={() => onGo("/lugares")}>
            Agregar la cuenta de la casa
          </button>
        </li>
        <li>Pongan el monto fijo que recibe cada quien, en «Ajustes del dinero personal», aquí abajo.</li>
        <li>
          Si ya tenían presupuesto, súmenlo al de la casa.{" "}
          <button type="button" className="button ghost small" onClick={() => onGo("/plan")}>Ir al Plan de la casa</button>
        </li>
      </ol>
    </section>
  );
}

interface PartProps {
  onDone: (text: string) => void;
  onError: (m: Message) => void;
}

const crcOnly = (list: Place[]) => list.filter((p) => p.currency === "CRC");
/** El dinero de un CDP está a plazo: no sirve para aportar ni para recibir. */
const spendable = (list: Place[]) => list.filter((p) => p.kind !== "CDP");
const before = (a: YearMonth, b: YearMonth) => a.year < b.year || (a.year === b.year && a.month < b.month);

function CloseMonth({ familyId, house, homePlaces, onDone, onError }: PartProps & {
  familyId: string; house: Household; homePlaces: Place[];
}) {
  // Por defecto el mes pasado, si la casa ya existía y no está cerrado; si no, el actual.
  const [sinceYear, sinceMonth] = house.since.split("-").map(Number);
  const since: YearMonth = { year: sinceYear ?? 2000, month: sinceMonth ?? 1 };
  const last = shiftMonth(currentMonth(), -1);
  const lastClosed = house.closes.some((c) => c.year === last.year && c.month === last.month);
  const [month, setMonth] = useState<YearMonth>(lastClosed || before(last, since) ? currentMonth() : last);
  const [bonusPct, setBonusPct] = useState(house.settings.bonusPct);
  const [from, setFrom] = useState("");
  const [rest, setRest] = useState("");
  const [preview, setPreview] = useState<ClosePreview | null>(null);
  const [busy, setBusy] = useState(false);
  const options = crcOnly(homePlaces);
  // El dinero personal no sale de lo apartado (un CDP a plazo, el sobre de una meta).
  const sources = options.filter((p) => !isReserved(p));
  const source = options.find((p) => p.id === from);

  useEffect(() => {
    if (!from && sources[0]) setFrom(sources[0].id);
  }, [options.length]);
  // Si cambian el bono habitual en los ajustes, el cierre lo toma.
  useEffect(() => setBonusPct(house.settings.bonusPct), [house.settings.bonusPct]);

  useEffect(() => {
    let cancelled = false;
    api.get<ClosePreview>(`/api/spaces/${familyId}/household/close?year=${month.year}&month=${month.month}&bonusPct=${bonusPct}`)
      .then((p) => !cancelled && setPreview(p))
      .catch(() => !cancelled && setPreview(null));
    return () => {
      cancelled = true;
    };
  }, [familyId, month.year, month.month, bonusPct, house]);

  const now = currentMonth();
  const isFuture = month.year > now.year || (month.year === now.year && month.month > now.month);
  const isCurrent = month.year === now.year && month.month === now.month;

  const [confirmReopen, setConfirmReopen] = useState(false);
  const latest = house.closes[0];
  const isLatestClose = latest?.year === month.year && latest?.month === month.month;

  const reopen = async () => {
    setBusy(true);
    onError(null);
    try {
      await withPasskeyConfirm(() => api.del(`/api/spaces/${familyId}/household/close?year=${month.year}&month=${month.month}`));
      setConfirmReopen(false);
      onDone(`${monthLabel(month)} quedó abierto otra vez.`);
    } catch (e) {
      onError({ tone: "error", text: messageOf(e, "No se pudo reabrir el mes.") });
    } finally {
      setBusy(false);
    }
  };

  const close = async () => {
    if (!preview || !from) return;
    setBusy(true);
    onError(null);
    try {
      await withPasskeyConfirm(() => api.post(`/api/spaces/${familyId}/household/close`, {
        year: month.year, month: month.month, bonusPct, fromPlace: from, restPlace: rest || null,
      }));
      onDone(`Mes cerrado. Cada quien recibió ${money(preview.perPerson, "CRC")}.`);
    } catch (e) {
      onError({ tone: "error", text: messageOf(e, "No se pudo cerrar el mes.") });
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="block" aria-labelledby="close-title">
      <h2 id="close-title" className="section-title">Cerrar el mes</h2>
      <div className="close-month-nav">
        <button type="button" className="icon-button" aria-label="Mes anterior" disabled={!before(since, month)}
                onClick={() => setMonth(shiftMonth(month, -1))}>
          <Icon name="left" />
        </button>
        <span className="close-month-label">{monthLabel(month)} {month.year}</span>
        <button type="button" className="icon-button" aria-label="Mes siguiente" disabled={isCurrent || isFuture}
                onClick={() => setMonth(shiftMonth(month, 1))}>
          <Icon name="right" />
        </button>
      </div>

      {preview?.closed ? (
        <>
          <p className="lead">
            Ya está cerrado: cada quien recibió <strong className="figure">{money(preview.closed.perPerson, "CRC")}</strong>.
          </p>
          {isLatestClose && (confirmReopen ? (
            <div className="stack">
              <p className="notice">
                Se borra lo que anotó el cierre: lo que recibió cada quien en lo personal, el gasto en la casa y lo que se
                pasó a otra cuenta. Después lo pueden cerrar de nuevo.
              </p>
              <div className="actions start">
                <button type="button" className="button ghost danger" disabled={busy} onClick={reopen}>Sí, reabrir</button>
                <button type="button" className="button ghost" onClick={() => setConfirmReopen(false)}>No</button>
              </div>
            </div>
          ) : (
            <button type="button" className="button ghost small" onClick={() => setConfirmReopen(true)}>
              Reabrir {monthLabel(month).toLowerCase()}
            </button>
          ))}
        </>
      ) : preview && (
        <>
          <dl className="close-sums">
            <div><dt>Entró</dt><dd className="figure">{money(preview.income, "CRC")}</dd></div>
            <div><dt>Salió</dt><dd className="figure">{money(preview.expenses, "CRC")}</dd></div>
            {preview.saved !== 0 && (
              <div><dt>Se ahorró</dt><dd className="figure">{money(preview.saved, "CRC")}</dd></div>
            )}
            <div><dt>Sobró</dt><dd className={`figure${preview.surplus < 0 ? " negative" : ""}`}>{money(preview.surplus, "CRC")}</dd></div>
          </dl>

          <label className="field">
            <span>Bono para cada quien: {String(bonusPct).replace(".", ",")} % de lo que sobró</span>
            <input type="range" min={0} max={5} step={0.5} value={bonusPct}
                   onChange={(e) => setBonusPct(Number(e.target.value))} />
          </label>

          <p className="lead">
            Cada quien recibe <strong className="figure">{money(preview.perPerson, "CRC")}</strong>
            <span className="muted small"> ({money(preview.personalFloor, "CRC")} fijo + {money(preview.bonus, "CRC")} de bono)</span>
          </p>
          {preview.surplus <= 0 && preview.perPerson > 0 && <p className="hint">Este mes no sobró: solo va el monto fijo.</p>}

          {preview.perPerson <= 0 ? (
            <p className="notice">Todavía no le toca nada a nadie: pongan el monto fijo en «Ajustes del dinero personal», más abajo.</p>
          ) : options.length === 0 ? (
            <p className="notice">Agreguen primero una cuenta en colones en la casa (por ejemplo, la cuenta conjunta).</p>
          ) : (
            <>
              <label className="field">
                <span>Sale de</span>
                <select value={from} onChange={(e) => setFrom(e.target.value)}>
                  {sources.map((p) => <option key={p.id} value={p.id}>{p.name} · {money(p.balance, "CRC")}</option>)}
                </select>
              </label>
              {preview.rest > 0 && (
                <label className="field">
                  <span>Lo que queda ({money(preview.rest, "CRC")})</span>
                  <select value={rest} onChange={(e) => setRest(e.target.value)}>
                    <option value="">Se queda donde está</option>
                    {options.filter((p) => p.id !== from).map((p) => <option key={p.id} value={p.id}>Pasarlo a {p.name}</option>)}
                  </select>
                </label>
              )}
              {source && source.balance < preview.perPerson * preview.members && (
                <p className="notice">
                  {source.name} tiene {money(source.balance, "CRC")} y el cierre saca {money(preview.perPerson * preview.members, "CRC")}: quedaría en negativo.
                </p>
              )}
              {isCurrent && <p className="hint">El mes todavía no termina: si cierran ahora, lo que anoten después no cuenta.</p>}
              <button type="button" className="button primary" disabled={busy || !from} onClick={close}>
                Cerrar {monthLabel(month).toLowerCase()}
              </button>
              <p className="hint">Se pide tu passkey. Si se equivocan, lo pueden reabrir.</p>
            </>
          )}
        </>
      )}
    </section>
  );
}

function Contribute({ familyId, myPlaces, homePlaces, onDone, onError }: PartProps & {
  familyId: string; myPlaces: Place[]; homePlaces: Place[];
}) {
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [amount, setAmount] = useState("");
  const [busy, setBusy] = useState(false);
  const source = myPlaces.find((p) => p.id === from);
  const targets = homePlaces.filter((p) => !source || p.currency === source.currency);

  useEffect(() => {
    if (!from && myPlaces[0]) setFrom(myPlaces[0].id);
  }, [myPlaces.length]);
  useEffect(() => {
    if (!targets.some((p) => p.id === to)) setTo(targets[0]?.id ?? "");
  }, [from, homePlaces.length]);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const value = parseAmount(amount);
    if (!value || Number(value) <= 0) {
      onError({ tone: "error", text: "Escribe cuánto vas a aportar." });
      return;
    }
    setBusy(true);
    onError(null);
    try {
      await api.post(`/api/spaces/${familyId}/household/contributions`, {
        fromPlace: from, toPlace: to, amount: value, date: todayIso(),
      });
      setAmount("");
      onDone(`Aportaste ${money(Number(value), source?.currency ?? "CRC")} a la casa.`);
    } catch (e) {
      onError({ tone: "error", text: messageOf(e, "No se pudo registrar el aporte.") });
    } finally {
      setBusy(false);
    }
  };

  if (myPlaces.length === 0 || homePlaces.length === 0) return null;

  return (
    <section className="block" aria-labelledby="contrib-title">
      <h2 id="contrib-title" className="section-title">Aportar a la casa</h2>
      <p className="hint">Pasa dinero de lo tuyo a la casa: queda como gasto tuyo y como ingreso de la casa.</p>
      <form className="stack" onSubmit={submit}>
        <label className="field">
          <span>De lo tuyo</span>
          <select value={from} onChange={(e) => setFrom(e.target.value)}>
            {myPlaces.map((p) => <option key={p.id} value={p.id}>{p.name} · {money(p.balance, p.currency)}</option>)}
          </select>
        </label>
        <label className="field">
          <span>A la casa</span>
          <select value={to} onChange={(e) => setTo(e.target.value)}>
            {targets.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
          </select>
        </label>
        <label className="field">
          <span>Monto</span>
          <input inputMode="decimal" maxLength={18} value={amount} onChange={(e) => setAmount(amountChange(e))} placeholder="0" />
        </label>
        <button type="submit" className="button secondary" disabled={busy || !to}>Aportar</button>
      </form>
    </section>
  );
}

function Destination({ house, myPlaces, onDone, onError }: PartProps & { house: Household; myPlaces: Place[] }) {
  const options = crcOnly(spendable(myPlaces));
  const save = async (placeId: string) => {
    onError(null);
    try {
      await api.put("/api/me/personal-destination", { placeId: placeId || null });
      onDone("Listo, ahí te llega tu dinero personal.");
    } catch (e) {
      onError({ tone: "error", text: messageOf(e, "No se pudo guardar.") });
    }
  };
  if (options.length === 0) return null;
  return (
    <section className="block" aria-labelledby="dest-title">
      <h2 id="dest-title" className="section-title">Dónde recibes tu dinero personal</h2>
      <p className="hint">Si no eliges, llega a tu cuenta principal en colones.</p>
      <label className="field">
        <span>Cuenta de tu espacio personal</span>
        <select value={house.myDestination ?? ""} onChange={(e) => void save(e.target.value)}>
          <option value="">Mi cuenta principal en colones</option>
          {options.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
        </select>
      </label>
    </section>
  );
}

function SettingsForm({ familyId, house, onDone, onError }: PartProps & { familyId: string; house: Household }) {
  const [floor, setFloor] = useState(amountToInput(house.settings.personalFloor));
  const [bonus, setBonus] = useState(house.settings.bonusPct);
  const [busy, setBusy] = useState(false);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const value = parseAmount(floor);
    if (value === null) {
      onError({ tone: "error", text: "El monto fijo tiene que ser un número." });
      return;
    }
    setBusy(true);
    onError(null);
    try {
      await api.put(`/api/spaces/${familyId}/household/settings`, { personalFloor: value, bonusPct: bonus });
      onDone("Ajustes guardados.");
    } catch (e) {
      onError({ tone: "error", text: messageOf(e, "No se pudieron guardar los ajustes.") });
    } finally {
      setBusy(false);
    }
  };

  return (
    <details className="more">
      <summary>Ajustes del dinero personal</summary>
      <form className="stack" onSubmit={submit}>
        <p className="hint">Los dos reciben lo mismo: un monto fijo cada mes, más un bono de lo que sobró.</p>
        <label className="field">
          <span>Monto fijo para cada quien</span>
          <input inputMode="decimal" maxLength={18} value={floor} onChange={(e) => setFloor(amountChange(e))} />
        </label>
        <label className="field">
          <span>Bono habitual: {String(bonus).replace(".", ",")} % de lo que sobró</span>
          <input type="range" min={0} max={5} step={0.5} value={bonus} onChange={(e) => setBonus(Number(e.target.value))} />
        </label>
        <button type="submit" className="button secondary" disabled={busy}>Guardar ajustes</button>
      </form>
    </details>
  );
}
