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

/** Posición de cada pantalla: las de la barra en su orden; las demás («Más», Perfil…) van después. */
function screenIndex(route: Route): number {
  const i = NAV.findIndex((n) => n.route === route);
  return i >= 0 ? i : NAV.length;
}

/** La última pantalla que se mostró (cada pantalla monta su propio Shell). */
let lastScreen: number | null = null;

interface ShellProps {
  children: ReactNode;
  /** Muestra el selector de mes (pantallas que dependen del mes). */
  withMonth?: boolean;
  /** La pantalla ya tiene su propio título principal: el mes no es <h1> (uno solo por página). */
  ownTitle?: boolean;
  onAdd?: () => void;
}

export function Shell({ children, withMonth = false, ownTitle = false, onAdd }: ShellProps) {
  const MonthTag = ownTitle ? "p" : "h1";
  const { spaces, space, selectSpace, month, moveMonth } = useApp();
  const route = useRoute();
  const page = useRef<HTMLDivElement>(null);
  const lastDelta = useRef(0);

  // Cambiar de pantalla: la nueva entra deslizando un poco desde el lado de la barra de abajo
  // (a la derecha si está más adelante). Solo transform y opacity; al terminar se limpian, porque
  // un transform puesto convierte a .page en el contenedor de lo fijo (la hoja de anotar).
  useGSAP(() => {
    const index = screenIndex(route);
    const previous = lastScreen;
    lastScreen = index;
    if (previous === null || previous === index) return;
    return whenMotionAllowed(() => {
      const direction = index > previous ? 1 : -1;
      gsap.fromTo(page.current, { x: 24 * direction, autoAlpha: 0 },
        { x: 0, autoAlpha: 1, duration: 0.22, ease: "power3.out", clearProps: "transform,opacity,visibility" });
    });
  }, { dependencies: [route] });

  // Cambiar de mes se siente como pasar una página.
  useGSAP(() => whenMotionAllowed(() => {
    if (!lastDelta.current) return;
    gsap.fromTo(page.current, { x: 18 * lastDelta.current, autoAlpha: 0.2 },
      { x: 0, autoAlpha: 1, clearProps: "transform,opacity,visibility" });
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
                {s.kind === "FAMILIA" ? "La casa" : s.name}
                {s.archived && <span className="tag">archivado</span>}
              </button>
            ))}
          </div>
        )}
        {/* «Más» va arriba: la barra de abajo queda para lo del día a día. */}
        <a className="account-link icon-button" href="/mas" aria-label="Más: la casa, deudas, facturas, historia y perfil"
           aria-current={route === "/mas" ? "page" : undefined}
           onClick={(e) => { e.preventDefault(); navigate("/mas"); }}>
          <Icon name="menu" />
        </a>
      </header>

      {withMonth && (
        <nav className="month-nav" aria-label="Mes">
          <button type="button" className="icon-button" onClick={() => go(-1)} aria-label="Mes anterior">
            <Icon name="left" />
          </button>
          <MonthTag className="month-title">
            {monthLabel(month)} <span className="year">{month.year}</span>
          </MonthTag>
          <button type="button" className="icon-button" onClick={() => go(1)} aria-label="Mes siguiente">
            <Icon name="right" />
          </button>
        </nav>
      )}

      <main className="page" ref={page} id="contenido">
        {space?.archived && (
          <p className="notice">
            Este espacio quedó archivado al unirse a la casa. Es de solo lectura.
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
