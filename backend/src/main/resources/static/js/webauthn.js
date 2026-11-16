// Ceremonias WebAuthn contra los endpoints de Spring Security (mismo formato que su
// spring-security-webauthn.js). Sin dependencias y sin scripts inline (CSP estricta).
"use strict";

const b64url = {
  encode(buffer) {
    return btoa(String.fromCharCode(...new Uint8Array(buffer)))
      .replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
  },
  decode(text) {
    const bin = atob(text.replace(/-/g, "+").replace(/_/g, "/"));
    return Uint8Array.from(bin, (c) => c.charCodeAt(0)).buffer;
  },
};

// Patrón SPA de CSRF: el token viene en la cookie XSRF-TOKEN y vuelve en un header.
export function csrfHeaders() {
  const match = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]+)/);
  return match ? { "X-XSRF-TOKEN": decodeURIComponent(match[1]) } : {};
}

export async function post(url, body, contentType = "application/json") {
  const response = await fetch(url, {
    method: "POST",
    credentials: "same-origin",
    headers: { "Content-Type": contentType, ...csrfHeaders() },
    body: body === undefined ? undefined
      : contentType === "application/json" ? JSON.stringify(body) : body,
  });
  if (!response.ok) {
    throw new Error(`HTTP ${response.status}`);
  }
  return response.status === 204 ? null : response.json();
}

export async function loginWithPasskey() {
  const options = await post("/webauthn/authenticate/options");
  const credential = await navigator.credentials.get({
    publicKey: {
      ...options,
      challenge: b64url.decode(options.challenge),
      allowCredentials: (options.allowCredentials || []).map((c) => ({ ...c, id: b64url.decode(c.id) })),
    },
  });
  const r = credential.response;
  const result = await post("/login/webauthn", {
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
    authenticatorAttachment: credential.authenticatorAttachment,
  });
  return result.redirectUrl;
}

export async function registerPasskey(label) {
  const options = await post("/webauthn/register/options");
  const credential = await navigator.credentials.create({
    publicKey: {
      ...options,
      user: { ...options.user, id: b64url.decode(options.user.id) },
      challenge: b64url.decode(options.challenge),
      excludeCredentials: (options.excludeCredentials || []).map((c) => ({ ...c, id: b64url.decode(c.id) })),
    },
  });
  const r = credential.response;
  await post("/webauthn/register", {
    publicKey: {
      credential: {
        id: credential.id,
        rawId: b64url.encode(credential.rawId),
        response: {
          attestationObject: b64url.encode(r.attestationObject),
          clientDataJSON: b64url.encode(r.clientDataJSON),
          transports: r.getTransports ? r.getTransports() : [],
        },
        type: credential.type,
        clientExtensionResults: credential.getClientExtensionResults(),
        authenticatorAttachment: credential.authenticatorAttachment,
      },
      label,
    },
  });
}
