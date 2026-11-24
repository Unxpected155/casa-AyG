import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { messageOf } from "../api";
import { useApp } from "../state";

/** Cuánto se puede deshacer un borrado antes de que ocurra de verdad. */
const UNDO_MS = 6000;

interface Pending {
  id: string;
  label: string;
  run: () => Promise<unknown>;
  timer: number;
}

interface UndoState {
  /** Lo que ya no se muestra porque está por borrarse. */
  hidden: ReadonlySet<string>;
  /** Borra en unos segundos; mientras tanto se puede deshacer. */
  schedule: (id: string, label: string, run: () => Promise<unknown>) => void;
}

const Ctx = createContext<UndoState | null>(null);

export function useUndo(): UndoState {
  const value = useContext(Ctx);
  if (!value) throw new Error("useUndo fuera de UndoProvider");
  return value;
}

/**
 * Borrar con «Deshacer»: lo borrado desaparece al instante, pero el borrado de verdad ocurre
 * UNDO_MS después. Deshacer solo cancela; no se vuelve a crear nada (así no cambia quién lo
 * anotó ni le llega a la pareja un aviso de algo que no se borró). Si la app se cierra antes,
 * el borrado se intenta en ese momento; si no alcanza, el movimiento sigue ahí (nada se pierde).
 */
export function UndoProvider({ children }: { children: ReactNode }) {
  const { touch } = useApp();
  const [pending, setPending] = useState<Pending | null>(null);
  const [error, setError] = useState<string | null>(null);
  const current = useRef<Pending | null>(null);
  const toast = useRef<HTMLDivElement>(null);

  const commit = useCallback(async (p: Pending) => {
    window.clearTimeout(p.timer);
    if (current.current === p) {
      current.current = null;
      setPending(null);
    }
    try {
      await p.run();
    } catch (e) {
      setError(messageOf(e, "No se pudo borrar."));
    } finally {
      touch();
    }
  }, [touch]);

  const schedule = useCallback((id: string, label: string, run: () => Promise<unknown>) => {
    if (current.current) void commit(current.current);
    const p: Pending = { id, label, run, timer: 0 };
    // Si el foco está en «Deshacer» cuando se acaba el tiempo, se espera a que salga:
    // quien está por tocarlo con teclado o lector de pantalla no lo pierde.
    const expire = () => {
      if (toast.current?.contains(document.activeElement)) p.timer = window.setTimeout(expire, 1000);
      else void commit(p);
    };
    p.timer = window.setTimeout(expire, UNDO_MS);
    current.current = p;
    setError(null);
    setPending(p);
  }, [commit]);

  const undo = () => {
    const p = current.current;
    if (!p) return;
    window.clearTimeout(p.timer);
    current.current = null;
    setPending(null);
  };

  useEffect(() => {
    const flush = () => {
      if (current.current) void commit(current.current);
    };
    window.addEventListener("pagehide", flush);
    return () => window.removeEventListener("pagehide", flush);
  }, [commit]);

  const value = useMemo<UndoState>(() => ({
    hidden: pending ? new Set([pending.id]) : new Set<string>(),
    schedule,
  }), [pending, schedule]);

  return (
    <Ctx.Provider value={value}>
      {children}
      {/* La región existe desde el inicio (si no, los lectores de pantalla no anuncian lo que
          entra) y tiene nombre, para encontrar los avisos siempre en el mismo lugar. */}
      <div role="region" aria-label="Avisos">
        <div aria-live="polite">
          {(pending || error) && (
            <div ref={toast} className={`undo-toast${error ? " error" : ""}`}>
              <span>{error ?? pending?.label}</span>
              {pending && !error && (
                <button type="button" className="button secondary small" onClick={undo}>Deshacer</button>
              )}
              {error && (
                <button type="button" className="button ghost small" onClick={() => setError(null)}>Cerrar</button>
              )}
            </div>
          )}
        </div>
      </div>
    </Ctx.Provider>
  );
}
