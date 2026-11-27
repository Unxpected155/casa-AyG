// Enrutador mínimo con la History API (sin dependencias). Las rutas deben coincidir con
// WebPagesConfig.SPA_ROUTES en el backend.
import { useSyncExternalStore } from "react";

export type Route = "/" | "/login" | "/registrar" | "/movimientos" | "/plan" | "/cuenta" | "/boda" | "/lugares" | "/metas" | "/historia" | "/casa" | "/facturas" | "/privacidad" | "/mas" | "/deudas";
const ROUTES: Route[] = ["/", "/login", "/registrar", "/movimientos", "/plan", "/cuenta", "/boda", "/lugares", "/metas", "/historia", "/casa", "/facturas", "/privacidad", "/mas", "/deudas"];

function current(): Route {
  const path = window.location.pathname as Route;
  return ROUTES.includes(path) ? path : "/";
}

function subscribe(onChange: () => void): () => void {
  window.addEventListener("popstate", onChange);
  return () => window.removeEventListener("popstate", onChange);
}

export function useRoute(): Route {
  return useSyncExternalStore(subscribe, current);
}

/**
 * {@code state}: datos para la pantalla de destino (por ejemplo, con qué filtro abre
 * Movimientos). Quedan en esa entrada del historial: al volver con «atrás» se respetan.
 */
export function navigate(route: Route, replace = false, state: unknown = null): void {
  if (route === current() && !replace && state === null) return;
  if (replace) window.history.replaceState(state, "", route);
  else window.history.pushState(state, "", route);
  window.dispatchEvent(new PopStateEvent("popstate"));
  window.scrollTo({ top: 0 });
}
