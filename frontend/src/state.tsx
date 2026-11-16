import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { api, ApiError } from "./api";
import { currentMonth, shiftMonth, type YearMonth } from "./format";
import type { Category, Me, Space } from "./types";

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
      return;
    }
    let cancelled = false;
    api.get<Category[]>(`/api/spaces/${space.id}/categories`)
      .then((list) => !cancelled && setCategories(list))
      .catch(() => !cancelled && setCategories([]));
    return () => {
      cancelled = true;
    };
  }, [space, version]);

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
      month,
      moveMonth: (delta) => setMonth((m) => shiftMonth(m, delta)),
      version,
      touch: () => setVersion((v) => v + 1),
      memberName: (id) => names.get(id) ?? "Alguien",
    };
  }, [session, reloadSession, spaces, space, categories, month, version]);

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}
