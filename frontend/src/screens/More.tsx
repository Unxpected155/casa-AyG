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
      <button type="button" className="button ghost danger menu-exit" onClick={exit}>
        <Icon name="logout" size={18} /> Cerrar sesión
      </button>
    </Shell>
  );
}
