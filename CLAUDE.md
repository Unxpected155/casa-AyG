# Casa A&G: instrucciones para Claude

Antes de cada tarea, lee [PRODUCT.md](PRODUCT.md): propósito, usuario, principios, reglas de
dominio, alcance y decisiones. Para cualquier cambio de interfaz, lee también [DESIGN.md](DESIGN.md).

- No inventes reglas de negocio. Si una tarea choca con PRODUCT.md o necesita una regla que no
  está, pregunta antes de escribir código.
- Cuando el usuario no sepa decidir: explica en 2 líneas qué hay que decidir, da 2 o 3 opciones
  (ventaja, desventaja, qué tan difícil es cambiarla después), recomienda una pensando en un
  usuario poco técnico y avisa si es difícil de revertir (datos, cálculos financieros, flujos
  principales).
- Toda decisión nueva, del usuario o provisional, va a la sección «Decisiones» de PRODUCT.md
  con fecha, qué se decidió, por qué y si es provisional. Las provisionales se revisan antes de
  cada entrega.
- Cada cambio va por PR; el merge y el despliegue solo con la aprobación explícita del usuario.
- Repo público: nada de secretos ni datos reales (los tests usan Ana y Beto).
