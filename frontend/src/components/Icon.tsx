// Íconos SVG propios, trazo de 1.75 sobre 24×24 (como una pluma fina). Sin emojis.
const PATHS = {
  ledger: "M5 4h11a3 3 0 0 1 3 3v13H8a3 3 0 0 1-3-3V4Zm0 13a3 3 0 0 1 3-3h11M9 8h6",
  list: "M9 6h11M9 12h11M9 18h11M4.5 6h.01M4.5 12h.01M4.5 18h.01",
  plan: "M4 20h4l10.5-10.5a2.12 2.12 0 0 0-3-3L5 17v3Zm9.5-12.5 3 3",
  user: "M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8Zm-7 8a7 7 0 0 1 14 0",
  rings: "M9 19a5 5 0 1 0 0-10 5 5 0 0 0 0 10Zm6 0a5 5 0 1 0 0-10 5 5 0 0 0 0 10ZM10 5l2-2 2 2",
  plus: "M12 5v14M5 12h14",
  menu: "M4 7h16M4 12h16M4 17h16",
  receipt: "M6 3h12v18l-3-2-3 2-3-2-3 2V3Zm3 5h6m-6 4h6m-6 4h4",
  history: "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18Zm0-13v5l3 2",
  left: "M15 18l-6-6 6-6",
  right: "M9 6l6 6-6 6",
  close: "M6 6l12 12M18 6 6 18",
  key: "M15 7a4 4 0 1 1-3.87 5H9v2H7v2H4v-3l6.13-6.13A4 4 0 0 1 15 7Zm1 1h.01",
  trash: "M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3",
  download: "M12 4v11m0 0-4-4m4 4 4-4M5 20h14",
  logout: "M15 17l5-5-5-5M20 12H9M11 4H5v16h6",
  check: "M5 12.5 10 17 19 7",
  home: "M4 11.5 12 5l8 6.5M6.5 10v9h11v-9",
  up: "M6 15l6-6 6 6",
  down: "M6 9l6 6 6-6",
  target: "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18Zm0-4a5 5 0 1 0 0-10 5 5 0 0 0 0 10Zm0-4a1 1 0 1 0 0-2 1 1 0 0 0 0 2Z",
  wallet: "M4 7h14a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a1 1 0 0 1-1-1V7Zm0 0 11-3v3M16 13.5h.01",
  swap: "M7 7h12m0 0-3-3m3 3-3 3M17 17H5m0 0 3-3m-3 3 3 3",
  debt: "M6 3h12v18H6V3Zm3 5h6M9 12h6M9 16h3m4 0 2 2",
  alert: "M12 8v5m0 3.5h.01M10.3 4.2 2.8 17.5A2 2 0 0 0 4.5 20.5h15a2 2 0 0 0 1.7-3L13.7 4.2a2 2 0 0 0-3.4 0Z",
} as const;

export type IconName = keyof typeof PATHS;

export function Icon({ name, size = 22, label }: { name: IconName; size?: number; label?: string }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.75}
      strokeLinecap="round"
      strokeLinejoin="round"
      role={label ? "img" : undefined}
      aria-label={label}
      aria-hidden={label ? undefined : true}
      focusable="false"
    >
      <path d={PATHS[name]} />
    </svg>
  );
}
