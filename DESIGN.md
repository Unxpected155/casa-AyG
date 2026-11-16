# Design

Sistema visual de Casa A&G: **libreta del hogar**. Papel crema, tinta cálida, un solo acento terracota. Ver PRODUCT.md para el porqué.

## Theme

- **Día (por defecto):** papel crema con grano sutil. Pensado para usar de pie, con luz fuerte.
- **Papel de noche** (`prefers-color-scheme: dark`): sepia oscuro, nunca negro puro ni neón. Para revisar en el sofá.

## Color (OKLCH)

Estrategia: **Restrained**. Neutros teñidos hacia el cálido + un acento terracota solo para acciones, selección y estado.

| Token | Día | Noche | Uso |
|---|---|---|---|
| `--paper` | `oklch(0.965 0.018 85)` | `oklch(0.215 0.014 60)` | Fondo |
| `--paper-raised` | `oklch(0.985 0.012 85)` | `oklch(0.255 0.016 60)` | Paneles, hoja de "anotar" |
| `--paper-sunk` | `oklch(0.935 0.022 82)` | `oklch(0.19 0.012 60)` | Campos, zonas hundidas |
| `--rule` | `oklch(0.86 0.025 80)` | `oklch(0.35 0.018 60)` | Renglones y bordes |
| `--ink` | `oklch(0.25 0.02 55)` | `oklch(0.93 0.018 85)` | Texto |
| `--ink-soft` | `oklch(0.45 0.025 60)` | `oklch(0.75 0.022 80)` | Texto secundario (≥ 4.5:1) |
| `--accent` | `oklch(0.55 0.135 40)` | `oklch(0.73 0.12 48)` | Terracota: acción principal, selección |
| `--on-accent` | `oklch(0.985 0.012 85)` | `oklch(0.2 0.02 50)` | Texto sobre el acento |
| `--ok` | `oklch(0.5 0.09 128)` | `oklch(0.76 0.1 125)` | Oliva: vas bien |
| `--near` | `oklch(0.52 0.1 72)` | `oklch(0.8 0.11 80)` | Ocre: cerca del límite |
| `--over` | `oklch(0.5 0.15 30)` | `oklch(0.72 0.14 35)` | Terracota oscuro: excedido |

El estado nunca va solo en color: siempre con texto o ícono.

## Typography

- **Literata** (serifa de libro, variable): títulos, montos grandes. Cifras tabulares (`font-variant-numeric: tabular-nums`) para que los montos se alineen como en un libro de cuentas.
- **Instrument Sans** (variable): UI, etiquetas, botones, formularios, cuerpo.
- Fuentes empaquetadas con la app (@fontsource), nunca desde un CDN: la CSP solo permite `'self'`.
- Escala fija (producto), razón 1.2: 13.3 / 16 / 19.2 / 23 / 27.6 / 33.2 / 40 px. Cuerpo 16 px mínimo.

## Spacing & Shape

- Escala de 4 px: 4, 8, 12, 16, 24, 32, 48, 64. Ritmo variado: secciones con 32–48, renglones con 12–16.
- Radios pequeños (6 px controles, 14 px hoja inferior). La libreta es de papel, no de burbujas.
- Sin sombras decorativas; la hoja de "anotar" tiene una sombra suave para separarse del papel.

## Components

- **Renglón de categoría:** nombre (sans), gastado / planeado (serifa tabular), y debajo una línea fina que se llena como subrayado a lápiz con el color de estado.
- **Asiento de movimiento:** fecha agrupada, descripción, categoría, quién lo registró (iniciales) y monto alineado a la derecha.
- **Botón principal:** terracota sólido. Secundario: tinta con borde. Fantasma: solo texto.
- **Hoja inferior** (no modal): sube desde abajo; el monto es el primer campo y el más grande.
- Todo control tiene estados: normal, hover, foco visible (anillo terracota 2 px), activo, deshabilitado, cargando, error.

## Motion (GSAP)

- Solo para comunicar estado: líneas de avance que se llenan, montos que cuentan una vez, hoja que sube, cambio de mes como pasar página.
- 150–300 ms, curvas `power3.out` / `expo.out`. Sin rebotes.
- `prefers-reduced-motion: reduce` → sin animaciones (estado final inmediato).
- Solo `transform` y `opacity`.
