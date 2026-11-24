import { useEffect, useState } from "react";
import { api } from "../api";
import { Icon, type IconName } from "../components/Icon";
import { Shell } from "../components/Shell";
import { navigate, type Route } from "../router";
import { useApp } from "../state";
import { logout } from "../webauthn";

interface Item {
  route: Route;
  icon: IconName;
  title: string;
  detail: string;
  badge?: number;
}

const GLOSSARY: [string, string][] = [
  ["Hoy tienes", "Todo lo que hay en tus cuentas, sobres y CDP, sumado."],
  ["Disponible", "Lo que puedes usar sin tocar lo apartado."],
  ["Apartado", "Lo que está en un CDP o en la cuenta de una meta: ya tiene dueño."],
  ["Ahorro del mes", "Lo que pasaste al CDP o a una meta. No es un gasto: sigue siendo tuyo."],
  ["Sobre", "Una cuenta dentro de otra para separar plata, como «Comida» o «Salidas»."],
  ["CDP", "Plata a plazo en el banco. La app calcula intereses e impuesto y te avisa cuando vence."],
  ["La casa", "El espacio que comparten. Todo el dinero entra ahí."],
  ["Aporte", "Pasar plata de lo tuyo a la casa."],
  ["Dinero personal", "Lo que la casa le da a cada quien al cerrar el mes: un monto fijo más el bono."],
  ["Bono", "Un porcentaje de lo que sobró en la casa ese mes, igual para los dos."],
  ["Cerrar el mes", "Calcula lo que sobró y paga el dinero personal. Si se equivocan, se reabre."],
  ["Por revisar", "Facturas que llegaron al buzón. Nada se anota solo: tú eliges dónde va."],
  ["Firma verificada", "La factura viene firmada con una llave oficial. Si no, revisa que reconozcas la compra."],
];

/** «Más»: todo lo que no cabe en la barra de abajo, a un toque y explicado en una línea. */
export function More() {
  const { spaces, selectSpace, reloadSession, version } = useApp();
  const family = spaces.find((s) => s.kind === "FAMILIA" && !s.archived);
  const [pending, setPending] = useState(0);

  useEffect(() => {
    let cancelled = false;
    api.get<unknown[]>("/api/invoices").then((l) => !cancelled && setPending(l.length)).catch(() => {});
    return () => {
      cancelled = true;
    };
  }, [version]);

  const items: Item[] = [
    family
      ? { route: "/casa", icon: "home", title: "La casa", detail: "Dinero personal, cierre del mes y quién cambió qué" }
      : { route: "/boda", icon: "rings", title: "La casa", detail: "Cuando se casen, unan sus espacios en uno compartido" },
    { route: "/deudas", icon: "debt", title: "Deudas", detail: "Lo que le debes a alguien y cuánto te falta" },
    { route: "/facturas", icon: "receipt", title: "Facturas", detail: "Las que llegan al buzón, para anotarlas con un toque",
      badge: pending },
    { route: "/historia", icon: "history", title: "Historia", detail: "Cómo van los meses y qué cambió contra tu promedio" },
    { route: "/cuenta", icon: "user", title: "Perfil y seguridad", detail: "Passkeys, avisos, tu cédula y tus datos" },
  ];

  const go = (item: Item) => {
    if (item.route === "/casa" && family) selectSpace(family.id);
    navigate(item.route);
  };

  const exit = async () => {
    await logout();
    await reloadSession();
    navigate("/login", true);
  };

  return (
    <Shell>
      <h1 className="page-title">Más</h1>
      <ul className="menu-list">
        {items.map((item) => (
          <li key={item.route}>
            <button type="button" className="menu-item row-button" onClick={() => go(item)}>
              <span className="menu-icon" aria-hidden="true"><Icon name={item.icon} /></span>
              <span className="menu-text">
                <span className="menu-title">
                  {item.title}
                  {item.badge ? <span className="count-badge" aria-label={`${item.badge} por revisar`}>{item.badge}</span> : null}
                </span>
                <span className="menu-detail">{item.detail}</span>
              </span>
            </button>
          </li>
        ))}
      </ul>
      <details className="more-section glossary">
        <summary>
          <span className="section-title">¿Qué significa cada cosa?</span>
          <span className="muted small">Las palabras de la app, en una línea cada una</span>
        </summary>
        <dl>
          {GLOSSARY.map(([term, meaning]) => (
            <div key={term}><dt>{term}</dt><dd>{meaning}</dd></div>
          ))}
        </dl>
      </details>

      <button type="button" className="button ghost danger menu-exit" onClick={exit}>
        <Icon name="logout" size={18} /> Cerrar sesión
      </button>
    </Shell>
  );
}
