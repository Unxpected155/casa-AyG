import { useState } from "react";
import { api, messageOf } from "../api";
import { useApp } from "../state";
import { applyTheme, type Theme } from "../theme";

const OPTIONS: { value: Theme; label: string }[] = [
  { value: "AUTO", label: "Automático" },
  { value: "CLARO", label: "Claro" },
  { value: "OSCURO", label: "Oscuro" },
];

/** Apariencia: sigue al teléfono, o siempre clara u oscura. Vale en todos tus dispositivos. */
export function ThemePicker() {
  const { session } = useApp();
  const initial = session.kind === "ready" ? session.me.theme ?? "AUTO" : "AUTO";
  const [theme, setTheme] = useState<Theme>(initial);
  const [error, setError] = useState<string | null>(null);

  const choose = async (next: Theme) => {
    const before = theme;
    setTheme(next);
    applyTheme(next);
    setError(null);
    try {
      await api.put("/api/me/theme", { theme: next });
    } catch (e) {
      setTheme(before);
      applyTheme(before);
      setError(messageOf(e, "No se pudo guardar."));
    }
  };

  return (
    <section className="block theme-picker" aria-labelledby="theme-title">
      <h2 id="theme-title" className="section-title">Apariencia</h2>
      <p className="muted">Automático sigue al teléfono: claro de día, oscuro de noche.</p>
      <div className="segmented" role="group" aria-labelledby="theme-title">
        {OPTIONS.map((o) => (
          <button key={o.value} type="button" aria-pressed={theme === o.value} onClick={() => void choose(o.value)}>
            {o.label}
          </button>
        ))}
      </div>
      {error && <p className="form-error" role="alert">{error}</p>}
    </section>
  );
}
