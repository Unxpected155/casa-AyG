import { loginWithPasskey, post } from "./webauthn.js";

const message = document.getElementById("message");
const show = (text) => { message.textContent = text; };

// Toca la página una vez para recibir la cookie CSRF antes de cualquier POST.
fetch("/login", { credentials: "same-origin" });

document.getElementById("passkey").addEventListener("click", async () => {
  try {
    window.location.href = await loginWithPasskey();
  } catch {
    show("No se pudo iniciar sesión con la passkey.");
  }
});

document.getElementById("code-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = new FormData(event.target);
  try {
    const result = await post("/login/code", new URLSearchParams(form), "application/x-www-form-urlencoded");
    window.location.href = result.redirectUrl;
  } catch {
    show("Código o usuario incorrecto.");
  }
});
