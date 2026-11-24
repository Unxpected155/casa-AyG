import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
// Fuentes empaquetadas con la app (la CSP solo permite 'self', nada de CDNs).
import "@fontsource-variable/literata";
import "@fontsource-variable/instrument-sans";
import "./styles/tokens.css";
import "./styles/base.css";
import "./styles/app.css";
import { App } from "./App";
import { AppProvider } from "./state";
import { UndoProvider } from "./components/Undo";

const root = document.getElementById("root");
if (root) {
  createRoot(root).render(
    <StrictMode>
      <AppProvider>
        <UndoProvider>
          <App />
        </UndoProvider>
      </AppProvider>
    </StrictMode>,
  );
}
