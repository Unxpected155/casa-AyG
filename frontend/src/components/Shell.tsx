import { useRef, type ReactNode } from "react";
import { gsap, useGSAP, whenMotionAllowed } from "../motion";
import { monthLabel } from "../format";
import { navigate, useRoute, type Route } from "../router";
import { useApp } from "../state";
import { Icon, type IconName } from "./Icon";

const NAV: { route: Route; label: string; icon: IconName }[] = [
  { route: "/", label: "Mes", icon: "ledger" },
  { route: "/movimientos", label: "Movimientos", icon: "list" },
  { route: "/plan", label: "Plan", icon: "plan" },
  { route: "/metas", label: "Metas", icon: "target" },
  { route: "/lugares", label: "Cuentas", icon: "wallet" },
];

interface ShellProps {
  children: ReactNode;
  /** Muestra el selector de mes (pantallas que dependen del mes). */
  withMonth?: boolean;
  onAdd?: () => void;
}

export function Shell({ children, withMonth = false, onAdd }: ShellProps) {
  const { spaces, space, selectSpace, month, moveMonth } = useApp();
  const route = useRoute();
  const page = useRef<HTMLDivElement>(null);
  const lastDelta = useRef(0);

  // Cambiar de mes se siente como pasar una página.
  useGSAP(() => whenMotionAllowed(() => {
    if (!lastDelta.current) return;
    gsap.fromTo(page.current, { x: 18 * lastDelta.current, autoAlpha: 0.2 }, { x: 0, autoAlpha: 1 });
  }), { dependencies: [month.year, month.month] });

  const go = (delta: number) => {
    lastDelta.current = delta;
    moveMonth(delta);
  };

  const visible = spaces.filter((s) => !s.archived || s.id === space?.id);

  return (
    <div className="shell">
      <header className="topbar">
        <a className="wordmark" href="/" onClick={(e) => { e.preventDefault(); navigate("/"); }}>
          Casa <span>A&amp;G</span>
        </a>
        {visible.length > 1 && (
          <div className="space-switch" role="group" aria-label="Espacio">
            {visible.map((s) => (
              <button
                key={s.id}
                type="button"
                className="space-chip"
                aria-pressed={s.id === space?.id}
                onClick={() => selectSpace(s.id)}
              >
                {s.kind === "FAMILIA" ? "Familia" : s.name}
                {s.archived && <span className="tag">archivado</span>}
              </button>
            ))}
          </div>
        )}
        {/* Cuenta va arriba: la barra de abajo queda para lo del día a día. */}
        <a className="account-link icon-button" href="/cuenta" aria-label="Perfil"
           aria-current={route === "/cuenta" ? "page" : undefined}
           onClick={(e) => { e.preventDefault(); navigate("/cuenta"); }}>
          <Icon name="user" />
        </a>
      </header>

      {withMonth && (
        <nav className="month-nav" aria-label="Mes">
          <button type="button" className="icon-button" onClick={() => go(-1)} aria-label="Mes anterior">
            <Icon name="left" />
          </button>
          <h1 className="month-title">
            {monthLabel(month)} <span className="year">{month.year}</span>
          </h1>
          <button type="button" className="icon-button" onClick={() => go(1)} aria-label="Mes siguiente">
            <Icon name="right" />
          </button>
        </nav>
      )}

      <main className="page" ref={page} id="contenido">
        {space?.archived && (
          <p className="notice">
            Este espacio quedó archivado al unirse al espacio familiar. Es de solo lectura.
          </p>
        )}
        {children}
      </main>

      {onAdd && !space?.archived && (
        <button type="button" className="fab" onClick={onAdd}>
          <Icon name="plus" />
          <span>Anotar</span>
        </button>
      )}

      <nav className="tabbar" aria-label="Secciones">
        {NAV.map((item) => (
          <a
            key={item.route}
            href={item.route}
            className="tab"
            aria-current={route === item.route ? "page" : undefined}
            onClick={(e) => {
              e.preventDefault();
              navigate(item.route);
            }}
          >
            <Icon name={item.icon} />
            <span>{item.label}</span>
          </a>
        ))}
      </nav>
    </div>
  );
}
