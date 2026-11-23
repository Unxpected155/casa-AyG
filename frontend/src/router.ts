// Enrutador mínimo con la History API (sin dependencias). Las rutas deben coincidir con
// WebPagesConfig.SPA_ROUTES en el backend.
import { useSyncExternalStore } from "react";

export type Route = "/" | "/login" | "/registrar" | "/movimientos" | "/plan" | "/cuenta" | "/boda" | "/lugares" | "/metas" | "/historia" | "/casa" | "/facturas" | "/privacidad";
const ROUTES: Route[] = ["/", "/login", "/registrar", "/movimientos", "/plan", "/cuenta", "/boda", "/lugares", "/metas", "/historia", "/casa", "/facturas", "/privacidad"];

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

export function navigate(route: Route, replace = false): void {
  if (route === current() && !replace) return;
  if (replace) window.history.replaceState(null, "", route);
  else window.history.pushState(null, "", route);
  window.dispatchEvent(new PopStateEvent("popstate"));
  window.scrollTo({ top: 0 });
}
