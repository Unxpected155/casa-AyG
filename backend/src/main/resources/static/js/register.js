import { registerPasskey } from "./webauthn.js";

const message = document.getElementById("message");

document.getElementById("register-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const label = new FormData(event.target).get("label");
  try {
    await registerPasskey(label);
    message.textContent = "Passkey registrada. Ya puedes iniciar sesión con ella.";
  } catch {
    message.textContent = "No se pudo registrar. Si ya tenías sesión, vuelve a entrar con tu passkey y reintenta.";
  }
});
