import { useRef, useState, type FormEvent } from "react";
import { messageOf } from "../api";
import { gsap, useGSAP, whenMotionAllowed } from "../motion";
import { navigate } from "../router";
import { useApp } from "../state";
import { loginWithCode, loginWithPasskey, passkeysSupported, registerPasskey } from "../webauthn";

export function Login() {
  const { reloadSession } = useApp();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [username, setUsername] = useState("");
  const [code, setCode] = useState("");
  const cover = useRef<HTMLDivElement>(null);

  useGSAP(() => whenMotionAllowed(() => {
    gsap.from(".cover > *", { autoAlpha: 0, y: 10, stagger: 0.06, duration: 0.4 });
    gsap.from(".roof", { strokeDashoffset: 120, duration: 0.9, ease: "power2.inOut" });
  }), { scope: cover });

  const enter = async () => {
    setBusy(true);
    setError(null);
    try {
      await loginWithPasskey();
      await reloadSession();
      navigate("/", true);
    } catch (e) {
      setError(messageOf(e, "No se pudo entrar con la passkey."));
      setBusy(false);
    }
  };

  const withCode = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await loginWithCode(username.trim().toLowerCase(), code.trim());
      await reloadSession();
      navigate("/registrar", true);
    } catch {
      setError("Usuario o código incorrecto, o el código ya se usó.");
      setBusy(false);
    }
  };

  return (
    <div className="cover-page">
      <div className="cover" ref={cover}>
        <svg className="mark" viewBox="0 0 120 60" aria-hidden="true">
          <path className="roof" d="M10 52 L60 10 L110 52" />
        </svg>
        <h1 className="cover-title">Casa A&amp;G</h1>
        <p className="cover-sub">Las cuentas de la casa.</p>

        {passkeysSupported() ? (
          <button type="button" className="button primary large" onClick={enter} disabled={busy}>
            {busy ? "Esperando la passkey…" : "Entrar con passkey"}
          </button>
        ) : (
          <p className="form-error" role="alert">Este navegador no admite passkeys. Prueba con Safari o Chrome actualizados.</p>
        )}

        {error && <p className="form-error" role="alert">{error}</p>}

        <details className="more">
          <summary>Tengo un código de inscripción o de recuperación</summary>
          <form className="stack" onSubmit={withCode}>
            <label className="field">
              <span>Usuario</span>
              <input value={username} maxLength={30} autoComplete="username" autoCapitalize="none" spellCheck={false}
                     onChange={(e) => setUsername(e.target.value)} required />
            </label>
            <label className="field">
              <span>Código</span>
              <input value={code} maxLength={40} autoComplete="one-time-code" autoCapitalize="characters"
                     spellCheck={false} className="mono" placeholder="XXXX-XXXX-XXXX-XXXX-XXXX-XXXX"
                     onChange={(e) => setCode(e.target.value)} required />
            </label>
            <button type="submit" className="button secondary" disabled={busy}>Continuar</button>
          </form>
        </details>
      </div>
    </div>
  );
}

/** Tras entrar con un código: la sesión solo sirve para registrar una passkey. */
export function Enroll() {
  const { reloadSession } = useApp();
  const [label, setLabel] = useState("");
  const [done, setDone] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const register = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await registerPasskey(label.trim() || "Mi dispositivo", false);
      setDone(true);
    } catch (e) {
      setError(messageOf(e, "No se pudo registrar. Si pasaron más de 10 minutos, vuelve a entrar con el código."));
    } finally {
      setBusy(false);
    }
  };

  const enter = async () => {
    setBusy(true);
    try {
      await loginWithPasskey();
      await reloadSession();
      navigate("/", true);
    } catch (e) {
      setError(messageOf(e, "No se pudo entrar."));
      setBusy(false);
    }
  };

  return (
    <div className="cover-page">
      <div className="cover">
        <h1 className="cover-title small">{done ? "Passkey lista" : "Tu passkey"}</h1>
        {!done ? (
          <form className="stack" onSubmit={register}>
            <p>
              Una passkey reemplaza a la contraseña: entras con Face ID, la huella o el PIN del dispositivo.
              Nadie puede usarla desde otro sitio.
            </p>
            <label className="field">
              <span>Nombre de este dispositivo</span>
              <input value={label} maxLength={100} onChange={(e) => setLabel(e.target.value)} placeholder="iPhone" />
            </label>
            <button type="submit" className="button primary large" disabled={busy}>
              {busy ? "Esperando…" : "Crear passkey"}
            </button>
          </form>
        ) : (
          <div className="stack">
            <p>Ahora entra con ella. Después, desde <strong>Perfil</strong>, registra una segunda en otro dispositivo y guarda tus códigos de recuperación.</p>
            <button type="button" className="button primary large" onClick={enter} disabled={busy}>Entrar</button>
          </div>
        )}
        {error && <p className="form-error" role="alert">{error}</p>}
      </div>
    </div>
  );
}
