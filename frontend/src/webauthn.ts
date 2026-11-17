// Ceremonias WebAuthn contra los endpoints de Spring Security 7.
// El navegador firma un desafío del servidor con la llave privada del dispositivo; la llave
// nunca sale de él y queda ligada al dominio (por eso una página falsa no puede usarla).

import { csrfHeaders } from "./api";

const b64url = {
  encode(buffer: ArrayBuffer): string {
    let binary = "";
    for (const byte of new Uint8Array(buffer)) binary += String.fromCharCode(byte);
    return btoa(binary).replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
  },
  decode(text: string): ArrayBuffer {
    const binary = atob(text.replace(/-/g, "+").replace(/_/g, "/"));
    return Uint8Array.from(binary, (c) => c.charCodeAt(0)).buffer;
  },
};

class CeremonyError extends Error {
  readonly status: number;
  constructor(status: number) {
    super(`HTTP ${status}`);
    this.status = status;
  }
}

async function post<T>(url: string, body?: unknown): Promise<T> {
  const response = await fetch(url, {
    method: "POST",
    credentials: "same-origin",
    headers: { "Content-Type": "application/json", ...csrfHeaders() },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!response.ok) throw new CeremonyError(response.status);
  return (await response.json()) as T;
}

type Descriptor = { id: string; type: PublicKeyCredentialType; transports?: AuthenticatorTransport[] };

export function passkeysSupported(): boolean {
  return typeof window.PublicKeyCredential === "function";
}

export async function loginWithPasskey(): Promise<void> {
  const options = await post<Omit<PublicKeyCredentialRequestOptions, "challenge" | "allowCredentials"> & {
    challenge: string;
    allowCredentials?: Descriptor[];
  }>("/webauthn/authenticate/options");

  const credential = (await navigator.credentials.get({
    publicKey: {
      ...options,
      challenge: b64url.decode(options.challenge),
      allowCredentials: (options.allowCredentials ?? []).map((c) => ({ ...c, id: b64url.decode(c.id) })),
    },
  })) as PublicKeyCredential | null;
  if (!credential) throw new DOMException("cancelado", "NotAllowedError");

  const r = credential.response as AuthenticatorAssertionResponse;
  await post("/login/webauthn", {
    id: credential.id,
    rawId: b64url.encode(credential.rawId),
    response: {
      authenticatorData: b64url.encode(r.authenticatorData),
      clientDataJSON: b64url.encode(r.clientDataJSON),
      signature: b64url.encode(r.signature),
      userHandle: r.userHandle ? b64url.encode(r.userHandle) : undefined,
    },
    credType: credential.type,
    clientExtensionResults: credential.getClientExtensionResults(),
    authenticatorAttachment: credential.authenticatorAttachment ?? undefined,
  });
}

/**
 * Registra una passkey. Con sesión de passkey, el servidor exige haberla usado hace menos de
 * 5 minutos: si responde 403, se confirma con la passkey actual y se reintenta.
 */
export async function registerPasskey(label: string, confirmIfNeeded = true): Promise<void> {
  type CreationOptions = Omit<PublicKeyCredentialCreationOptions, "challenge" | "user" | "excludeCredentials"> & {
    challenge: string;
    user: { id: string; name: string; displayName: string };
    excludeCredentials?: Descriptor[];
  };
  let options: CreationOptions;
  try {
    options = await post<CreationOptions>("/webauthn/register/options");
  } catch (error) {
    if (confirmIfNeeded && error instanceof CeremonyError && error.status === 403) {
      await loginWithPasskey();
      return registerPasskey(label, false);
    }
    throw error;
  }

  const credential = (await navigator.credentials.create({
    publicKey: {
      ...options,
      user: { ...options.user, id: b64url.decode(options.user.id) },
      challenge: b64url.decode(options.challenge),
      excludeCredentials: (options.excludeCredentials ?? []).map((c) => ({ ...c, id: b64url.decode(c.id) })),
    },
  })) as PublicKeyCredential | null;
  if (!credential) throw new DOMException("cancelado", "NotAllowedError");

  const r = credential.response as AuthenticatorAttestationResponse;
  await post("/webauthn/register", {
    publicKey: {
      label,
      credential: {
        id: credential.id,
        rawId: b64url.encode(credential.rawId),
        type: credential.type,
        clientExtensionResults: credential.getClientExtensionResults(),
        authenticatorAttachment: credential.authenticatorAttachment ?? undefined,
        response: {
          attestationObject: b64url.encode(r.attestationObject),
          clientDataJSON: b64url.encode(r.clientDataJSON),
          transports: r.getTransports ? r.getTransports() : [],
        },
      },
    },
  });
}

export async function loginWithCode(username: string, code: string): Promise<void> {
  const response = await fetch("/login/code", {
    method: "POST",
    credentials: "same-origin",
    headers: { "Content-Type": "application/x-www-form-urlencoded", ...csrfHeaders() },
    body: new URLSearchParams({ username, code }),
  });
  if (!response.ok) throw new CeremonyError(response.status);
}

export async function logout(): Promise<void> {
  await fetch("/logout", { method: "POST", credentials: "same-origin", headers: csrfHeaders() });
}
