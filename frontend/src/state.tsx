import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { api, ApiError } from "./api";
import { currentMonth, shiftMonth, type YearMonth } from "./format";
import { applyTheme } from "./theme";
import type { Category, Me, Place, Space } from "./types";

export type Session =
  | { kind: "loading" }
  | { kind: "anonymous" }
  | { kind: "enrolling" } // sesión de código: solo puede registrar passkeys
  | { kind: "ready"; me: Me };

interface AppState {
  session: Session;
  reloadSession: () => Promise<void>;
  spaces: Space[];
  space: Space | null;
  selectSpace: (id: string) => void;
  categories: Category[];
  /** Lugares del espacio (incluye archivados), con su saldo a hoy. */
  places: Place[];
  month: YearMonth;
  moveMonth: (delta: number) => void;
  /** Sube cada vez que cambian los datos, para que las pantallas recarguen. */
  version: number;
  touch: () => void;
  memberName: (userId: string) => string;
}

const Ctx = createContext<AppState | null>(null);

export function useApp(): AppState {
  const value = useContext(Ctx);
  if (!value) throw new Error("useApp fuera de AppProvider");
  return value;
}

export function AppProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session>({ kind: "loading" });
  const [spaces, setSpaces] = useState<Space[]>([]);
  const [spaceId, setSpaceId] = useState<string | null>(null);
  const [categories, setCategories] = useState<Category[]>([]);
  const [places, setPlaces] = useState<Place[]>([]);
  const [month, setMonth] = useState<YearMonth>(currentMonth);
  const [version, setVersion] = useState(0);

  const reloadSession = useCallback(async () => {
    try {
      const me = await api.get<Me>("/api/me");
      const list = await api.get<Space[]>("/api/spaces");
      setSpaces(list);
      // Por defecto: el espacio familiar activo si existe; si no, el personal activo.
      setSpaceId((prev) => {
        if (prev && list.some((s) => s.id === prev)) return prev;
        const active = list.filter((s) => !s.archived);
        return (active.find((s) => s.kind === "FAMILIA") ?? active[0] ?? list[0])?.id ?? null;
      });
      setSession({ kind: "ready", me });
      applyTheme(me.theme ?? "AUTO");
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) setSession({ kind: "enrolling" });
      else setSession({ kind: "anonymous" });
    }
  }, []);

  useEffect(() => {
    void reloadSession();
  }, [reloadSession]);

  const space = spaces.find((s) => s.id === spaceId) ?? null;

  useEffect(() => {
    if (!space) {
      setCategories([]);
      setPlaces([]);
      return;
    }
    let cancelled = false;
    api.get<Category[]>(`/api/spaces/${space.id}/categories`)
      .then((list) => !cancelled && setCategories(list))
      .catch(() => !cancelled && setCategories([]));
    api.get<Place[]>(`/api/spaces/${space.id}/places`)
      .then((list) => !cancelled && setPlaces(list))
      .catch(() => !cancelled && setPlaces([]));
    return () => {
      cancelled = true;
    };
  }, [space, version]);

  // Saldos en tiempo real: el servidor avisa cuando cambia algo de este espacio (lo anotó la
  // otra persona, u otro teléfono tuyo) y las pantallas vuelven a pedir sus datos. El aviso no
  // trae datos. Solo se escucha mientras la app está a la vista; al volver, se pone al día.
  const sessionReady = session.kind === "ready";
  const liveSpaceId = space?.id ?? null;
  useEffect(() => {
    if (!sessionReady || !liveSpaceId) return;
    let source: EventSource | null = null;
    let connectedBefore = false;
    let failures = 0;
    let debounce: number | undefined;
    let retry: number | undefined;

    // Varios avisos seguidos (un cierre de mes, por ejemplo) hacen una sola recarga.
    const refresh = () => {
      window.clearTimeout(debounce);
      debounce = window.setTimeout(() => setVersion((v) => v + 1), 300);
    };
    const close = () => {
      source?.close();
      source = null;
      window.clearTimeout(retry);
    };
    const open = () => {
      if (source || document.visibilityState !== "visible") return;
      const es = new EventSource(`/api/spaces/${liveSpaceId}/events`);
      source = es;
      es.addEventListener("ready", () => {
        failures = 0;
        // Al reconectarse pudo haberse perdido algo: se recarga una vez.
        if (connectedBefore) refresh();
        connectedBefore = true;
      });
      es.addEventListener("change", refresh);
      es.onerror = () => {
        // Cerrado del todo (el servidor dijo que no, o se cayó): se reintenta con calma.
        // Si no, el navegador ya está reintentando solo.
        if (es.readyState !== EventSource.CLOSED) return;
        source = null;
        failures += 1;
        retry = window.setTimeout(open, Math.min(60_000, 2_000 * 2 ** Math.min(failures, 5)));
      };
    };
    const onVisibility = () => (document.visibilityState === "visible" ? open() : close());

    open();
    document.addEventListener("visibilitychange", onVisibility);
    return () => {
      document.removeEventListener("visibilitychange", onVisibility);
      window.clearTimeout(debounce);
      close();
    };
  }, [sessionReady, liveSpaceId]);

  const value = useMemo<AppState>(() => {
    const names = new Map<string, string>();
    for (const s of spaces) for (const m of s.members) names.set(m.id, m.displayName);
    return {
      session,
      reloadSession,
      spaces,
      space,
      selectSpace: setSpaceId,
      categories,
      places,
      month,
      moveMonth: (delta) => setMonth((m) => shiftMonth(m, delta)),
      version,
      touch: () => setVersion((v) => v + 1),
      memberName: (id) => names.get(id) ?? "Alguien",
    };
  }, [session, reloadSession, spaces, space, categories, places, month, version]);

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}
