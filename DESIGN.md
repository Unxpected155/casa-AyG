# Design

Sistema visual de Casa A&G: **cacao y arena dorada** (desde el 2026-11-27). Moderna, serena y
ordenada: fondo de lino, estructura de cacao y un solo punto de luz dorado. Ver PRODUCT.md para el porqué.

## Brand Personality
Moderna, serena y ordenada. Calma con un punto de luz: transmite paz con el dinero, nunca
ansiedad. Lo moderno sale de la jerarquía, los números grandes y bien alineados, el aire y las
transiciones, no de colores llamativos. Voz en español de Costa Rica, cercana y breve, de "tú".

## Anti-references
- App de banco: azul marino corporativo, frío, sin personalidad.
- App de cripto o trading: fondo negro, neón, gráficos agresivos.
- Colores vivos y saturados por todas partes: cansan la vista (decisión del 2026-11-27).
- Blanco puro con muchos colores de datos: se siente estándar y carga visualmente.
- Plantilla SaaS: tarjetas idénticas en cuadrícula, número gigante con gradiente.

## Design Principles
1. **La tarea primero.** Registrar y revisar son las dos acciones; todo lo demás queda a un paso.
2. **Lo esencial arriba, el detalle al tocar.** Cada pantalla abre con lo que importa.
3. **Calma con el dinero.** Los excesos se muestran con claridad pero sin alarma; el color comunica estado, no miedo.
4. **Un solo punto de luz.** La arena dorada marca lo importante (lo que sobra, lo ahorrado, lo elegido); si todo brilla, nada brilla.
5. **Lo compartido es visible.** Siempre se sabe en qué espacio se está y quién registró qué.
6. **Seguridad tranquila.** Las confirmaciones con passkey se sienten naturales, no como obstáculos.

## Accessibility & Inclusion
WCAG 2.2 AA: contraste 4.5:1 en texto (todos los pares de la paleta están comprobados), objetivos
táctiles de 44 px, foco visible, formularios con etiquetas. Respeta `prefers-reduced-motion` (GSAP
y las transiciones se apagan o se reducen). El estado nunca se comunica solo con color (texto o
ícono además). Montos leídos correctamente por lectores de pantalla (₡ y $).

## Theme

La persona elige en Perfil → Apariencia (se guarda por persona, en el servidor):
- **Automático** (por defecto): sigue al teléfono.
- **Claro:** lino. Para usar de pie, con luz fuerte.
- **Oscuro:** cacao profundo, nunca negro puro. Para revisar en el sofá; la arena dorada es el punto de luz.

Técnica: los tokens de noche aplican con `@media (prefers-color-scheme: dark)` sobre
`:root:not([data-theme="light"])` y con `:root[data-theme="dark"]`.

## Color (OKLCH)

Estrategia: **60-30-10**. Lino de fondo (60), cacao de estructura (30), arena dorada de acento
(10, con la intensidad suave elegida: 70 %).

| Token | Claro | Oscuro | Uso |
|---|---|---|---|
| `--paper` | `oklch(0.95 0.012 65)` | `oklch(0.2 0.02 48)` | 60 · Fondo (lino / cacao profundo) |
| `--paper-raised` | `oklch(0.975 0.008 65)` | `oklch(0.245 0.022 48)` | Paneles, hoja de "anotar" |
| `--paper-sunk` | `oklch(0.925 0.015 62)` | `oklch(0.175 0.018 48)` | Campos, zonas hundidas |
| `--rule` | `oklch(0.86 0.018 60)` | `oklch(0.33 0.02 48)` | Renglones y bordes |
| `--ink` | `oklch(0.27 0.03 50)` | `oklch(0.93 0.012 70)` | Texto |
| `--ink-soft` | `oklch(0.46 0.025 52)` | `oklch(0.74 0.018 65)` | Texto secundario |
| `--structure` | `oklch(0.3 0.04 48)` | `oklch(0.27 0.026 48)` | 30 · Cacao: el resumen del mes, superficies que ordenan |
| `--on-structure` | `oklch(0.93 0.015 70)` | `oklch(0.93 0.012 70)` | Texto sobre cacao |
| `--highlight` | `oklch(0.83 0.049 82)` | `oklch(0.83 0.049 82)` | 10 · Arena dorada: lo que sobra, lo ahorrado |
| `--highlight-ink` | `oklch(0.47 0.06 78)` | `oklch(0.85 0.055 82)` | Dorado como texto sobre el fondo |
| `--accent` | `oklch(0.33 0.04 48)` | `oklch(0.83 0.049 82)` | Acción principal: cacao de día, dorado de noche |
| `--accent-ink` | `oklch(0.38 0.045 50)` | `oklch(0.85 0.055 82)` | Texto de acción (enlaces, botones fantasma) |
| `--accent-wash` | dorado al 35 % | dorado al 14 % | Selección (espacio activo, opción elegida) |
| `--ok` | `oklch(0.46 0.07 150)` | `oklch(0.78 0.08 150)` | Vas bien |
| `--near` | `oklch(0.52 0.11 55)` | `oklch(0.8 0.1 62)` | Cerca del límite: naranja tostado (no se confunde con el dorado) |
| `--over` | `oklch(0.5 0.14 28)` | `oklch(0.74 0.12 30)` | Te pasaste |
| `--expense` | `oklch(0.5 0.15 25)` | `oklch(0.74 0.12 25)` | Gastos (nunca el color de una acción) |

Datos: en tonos de la estructura y el acento (dorado = ahorro / lo importante), siempre con su
nombre al lado. El estado nunca va solo en color.

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
- **Botón principal:** terracota sólido. Secundario: tinta con borde. Fantasma: texto de acción (`--accent-ink`) con borde fino (`--rule-strong`); nunca solo texto, para que no se confunda con un párrafo.
- **Enlaces que abren algo ahí mismo** («Ver por cuenta», «Tengo un código…»): texto de acción subrayado.
- **Filas que se tocan** (cuentas, movimientos, historia, metas): chevron «›» al final; apunta abajo cuando la fila está abierta.
- **Hoja inferior** (no modal): sube desde abajo; el monto es el primer campo y el más grande.
- Todo control tiene estados: normal, hover, foco visible (anillo terracota 2 px), activo, deshabilitado, cargando, error.

## Motion (GSAP)

- Solo para comunicar estado: líneas de avance que se llenan, montos que cuentan una vez, hoja que sube, cambio de mes como pasar página.
- 150–300 ms, curvas `power3.out` / `expo.out`. Sin rebotes.
- `prefers-reduced-motion: reduce` → sin animaciones (estado final inmediato).
- Solo `transform` y `opacity`.
