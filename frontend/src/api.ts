// Cliente de la API. Reglas de seguridad:
// - Mismo origen y cookie de sesión (HttpOnly): el JavaScript nunca ve ni guarda la sesión.
// - CSRF: el token de la cookie XSRF-TOKEN vuelve en un header en cada petición que modifica.
// - Nada se guarda en localStorage/sessionStorage.

import { loginWithPasskey } from "./webauthn";

export class ApiError extends Error {
  readonly status: number;
  readonly type: string | null;

  constructor(status: number, type: string | null, message: string) {
    super(message);
    this.status = status;
    this.type = type;
  }
}

export function csrfHeaders(): Record<string, string> {
  const match = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]+)/);
  return match?.[1] ? { "X-XSRF-TOKEN": decodeURIComponent(match[1]) } : {};
}

type Method = "GET" | "POST" | "PUT" | "DELETE";

async function request<T>(method: Method, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { Accept: "application/json" };
  if (body !== undefined) headers["Content-Type"] = "application/json";
  if (method !== "GET") Object.assign(headers, csrfHeaders());

  const response = await fetch(path, {
    method,
    credentials: "same-origin",
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });

  if (!response.ok) {
    let type: string | null = null;
    let message = `HTTP ${response.status}`;
    try {
      const problem = (await response.json()) as { type?: string; detail?: string; title?: string };
      type = problem.type ?? null;
      message = problem.detail ?? problem.title ?? message;
    } catch {
      // Respuesta sin cuerpo JSON (por ejemplo, 401 de la cadena de seguridad).
    }
    throw new ApiError(response.status, type, message);
  }
  if (response.status === 204) return undefined as T;
  const contentType = response.headers.get("content-type") ?? "";
  return (contentType.includes("json") ? await response.json() : undefined) as T;
}

export const api = {
  get: <T>(path: string) => request<T>("GET", path),
  post: <T>(path: string, body?: unknown) => request<T>("POST", path, body),
  put: <T>(path: string, body?: unknown) => request<T>("PUT", path, body),
  del: <T>(path: string, body?: unknown) => request<T>("DELETE", path, body),
};

export function isReauthRequired(error: unknown): boolean {
  return error instanceof ApiError && error.status === 403
    && (error.type === "urn:casaayg:reautenticacion" || error.type === null);
}

/**
 * Acciones sensibles: si el servidor pide confirmar con la passkey (403 de reautenticación),
 * se pide la passkey y se reintenta UNA vez.
 */
export async function withPasskeyConfirm<T>(action: () => Promise<T>): Promise<T> {
  try {
    return await action();
  } catch (error) {
    if (!isReauthRequired(error)) throw error;
    await loginWithPasskey();
    return action();
  }
}

/** Mensaje para mostrar: los de la API ya son seguros y genéricos. */
export function messageOf(error: unknown, fallback: string): string {
  if (error instanceof ApiError) {
    if (error.status === 404) return "No se encontró. Puede que ya no exista.";
    if (error.status === 400) return "Revisa los datos: algo no tiene el formato esperado.";
    if (error.status === 409) return error.message !== `HTTP 409` && error.message !== "Conflicto"
      ? error.message : "No se puede hacer en este momento.";
    if (error.status >= 500) return "Algo falló de nuestro lado. Intenta de nuevo.";
  }
  if (error instanceof DOMException && error.name === "NotAllowedError") {
    return "Se canceló la confirmación con la passkey.";
  }
  return fallback;
}
