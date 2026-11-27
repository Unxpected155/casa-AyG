// El tema elegido en Perfil (se guarda por persona en el servidor, nunca en el navegador).
// AUTO sigue al teléfono; CLARO y OSCURO lo fijan con data-theme en <html> (ver tokens.css).

export type Theme = "AUTO" | "CLARO" | "OSCURO";

/** Color de la barra del navegador en cada tema (lino de día, cacao profundo de noche). */
const BAR = { light: "#f3eee8", dark: "#1f1915" };

export function applyTheme(theme: Theme): void {
  const root = document.documentElement;
  const forced = theme === "CLARO" ? "light" : theme === "OSCURO" ? "dark" : null;
  if (forced) root.dataset.theme = forced;
  else delete root.dataset.theme;
  for (const meta of document.querySelectorAll<HTMLMetaElement>('meta[name="theme-color"]')) {
    const own = meta.media.includes("dark") ? BAR.dark : BAR.light;
    meta.content = forced ? BAR[forced] : own;
  }
}
