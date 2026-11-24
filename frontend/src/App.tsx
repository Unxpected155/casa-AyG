import { useEffect } from "react";
import { navigate, useRoute } from "./router";
import { Account } from "./screens/Account";
import { Enroll, Login } from "./screens/Login";
import { Month } from "./screens/Month";
import { Movements } from "./screens/Movements";
import { Goals } from "./screens/Goals";
import { History } from "./screens/History";
import { Places } from "./screens/Places";
import { Plan } from "./screens/Plan";
import { Wedding } from "./screens/Wedding";
import { House } from "./screens/House";
import { Invoices } from "./screens/Invoices";
import { More } from "./screens/More";
import { Debts } from "./screens/Debts";
import { Privacy } from "./screens/Privacy";
import { useApp } from "./state";

export function App() {
  const { session } = useApp();
  const route = useRoute();

  // La sesión decide a dónde se puede ir: sin sesión, al login; con código, a registrar passkey.
  useEffect(() => {
    if (route === "/privacidad") return;
    if (session.kind === "anonymous" && route !== "/login") navigate("/login", true);
    if (session.kind === "enrolling" && route !== "/registrar") navigate("/registrar", true);
    if (session.kind === "ready" && (route === "/login" || route === "/registrar")) navigate("/", true);
  }, [session.kind, route]);

  // Pública: se ve con o sin sesión.
  if (route === "/privacidad") return <Privacy />;
  if (session.kind === "loading") {
    return <div className="boot" aria-busy="true"><span className="boot-mark">Casa A&amp;G</span></div>;
  }
  if (session.kind === "anonymous") return <Login />;
  if (session.kind === "enrolling") return <Enroll />;

  switch (route) {
    case "/movimientos": return <Movements />;
    case "/plan": return <Plan />;
    case "/cuenta": return <Account />;
    case "/boda": return <Wedding />;
    case "/lugares": return <Places />;
    case "/metas": return <Goals />;
    case "/historia": return <History />;
    case "/casa": return <House />;
    case "/facturas": return <Invoices />;
    case "/mas": return <More />;
    case "/deudas": return <Debts />;
    default: return <Month />;
  }
}
