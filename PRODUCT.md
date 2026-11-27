# Product

## Register
product

## Propósito
Llevar las finanzas personales y del hogar durante toda la vida, para ser responsable con el
dinero: saber cuánto tengo, cuánto entra y sale, si voy bien, y avanzar hacia cualquier meta
(fondo de emergencia, casarse, un carro, el retiro). En colones y dólares, sin hojas de cálculo.

Para quién: hoy, una pareja (gabito y Alli). Está preparada para que sus mejores amigos la
usen si les sirve, cada pareja en su propio servidor (ver [docs/instalar-para-amigos.md](docs/instalar-para-amigos.md)).
No es un servicio público.

## Usuario principal
Personas que no usan mucha tecnología ni saben de finanzas. La app se entiende sin explicación
desde la primera vez. Momentos de uso: de pie y con prisa al pagar (anotar en menos de 10 s),
y con calma en la noche (revisar cómo va el mes).

## Principios (no negociables)
1. Todo estado vacío explica qué falta y ofrece un botón para crearlo ahí mismo.
2. Lenguaje claro, sin jerga financiera; si una palabra es inevitable, se explica donde aparece.
3. Los estados llevan texto, no solo color. Nada de rojo de alarma: calma con el dinero.
4. Los saldos se muestran en tiempo real: lo que anota la otra persona aparece solo, sin recargar.
5. Un solo camino claro para empezar: cuenta, ingreso, gasto.
6. Privada y segura: se entra con huella o cara (passkeys), nunca con contraseñas.
7. La app registra y calcula; las decisiones con el dinero son de las personas.

## Reglas de dominio

### Dinero: tres partes
- **Ahorrado:** el saldo de los lugares marcados como «de ahorro» (los CDP, y sobres o cuentas
  que la persona marque así). Dejar dinero en la cuenta principal NO es ahorro.
- **Disponible:** lo que está en las cuentas que no son de ahorro.
- **Te sobra:** lo disponible menos lo que falta gastar del presupuesto del mes, los pagos
  fijos pendientes y las cuotas de deudas pendientes. Es lo que se puede usar sin tocar lo
  ahorrado ni lo planeado.

### Ahorro
- El ahorro es una transferencia hacia un lugar de ahorro, no un gasto.
- Dos métricas separadas, en tiempo real:
  (a) **Saldo ahorrado actual.**
  (b) **Tasa de ahorro del mes en curso:** lo que entró neto a lugares de ahorro este mes,
      dividido entre los ingresos del mes, comparada con el promedio de los meses cerrados.
- Un depósito a mitad de mes cuenta de inmediato en ambas. Sacar dinero de un ahorro la baja.

### Fondo de emergencia
- Meta por defecto: 3 meses de gastos, configurable por la persona.
- Gastos de referencia: promedio de los meses cerrados que tienen datos (hasta 3). Si todavía
  no hay meses cerrados, se usa el presupuesto del mes.
- Los gastos en dólares se convierten a colones con el tipo de cambio del BCCR. Los colones
  van siempre primero.

### Diezmo
- La casilla «Dar diezmo» viene apagada por defecto en cada ingreso; la persona decide si aplica.
- La casilla muestra el monto (10 % del bruto) mientras se escribe el ingreso, y destella una
  sola vez, en ocre, cuando se escribe el monto. Nunca palpita en bucle.
- Marcarla no anota el gasto: suma a **«Diezmo por dar»**. Anotar un gasto en la categoría
  «Diezmo» lo baja.
- Lo pendiente se arrastra de mes a mes hasta darlo.
- Los intereses del CDP son ingresos: si se marca al renovar, también suman.

### CDP
- La persona define todos los datos al crear Y al renovar: plazo, tasa, impuesto, base de
  días, capital y aporte. Al renovar se ofrecen los de la vez anterior, ya llenos.
- Al renovar se puede retirar parte del capital o sumarle. Lo que se suma sale de la cuenta de
  los intereses; lo que se retira va a la cuenta que la persona elija.
- El interés del plazo que terminó se calcula con los datos de ese plazo; los que se escriben
  al renovar valen para el plazo que empieza.
- Al renovar, el CDP no puede quedar en cero: para cerrarlo se saca el dinero y se archiva.
- La app calcula intereses e impuesto con esos datos, nunca con valores fijos.

### Deudas
- Lo que falta sale de los abonos (y de lo pagado antes y lo perdonado).
- Con fecha límite: «Necesitas ₡X al mes para terminar a tiempo; tu promedio es ₡Y», con estado.
- Sin fecha límite: lo que llevas, lo que falta, y «a tu ritmo terminarías en…» como estimado.

### La casa
- Todo el dinero de la pareja entra a la casa; cada quien conserva su espacio personal.
- Un aporte de lo personal a la casa cuenta como ingreso de la casa.
- Ambos editan todo, con historial de quién cambió qué.
- **Dinero personal: no es fijo ni obligatorio.** Se decide juntos al cerrar el mes:
  1. La app muestra cuánto se puede repartir sin tocar ahorros ni lo que necesita el mes
     siguiente (presupuesto, pagos fijos, cuotas).
  2. Sugerencia: «el porcentaje que les recomiendo es X», con un botón «Usar la sugerencia»
     que llena el mismo monto para los dos.
  3. Cada monto se puede cambiar, y puede ser distinto para cada uno, o «este mes no».
  4. Uno propone y el otro aprueba con passkey. Sin el sí de los dos no se paga nada.
  5. Queda registrado en el historial del mes.

### Facturas
- Se leen del buzón de correo las facturas Y los tiquetes electrónicos. Nada se anota solo:
  la persona elige dónde va. La categoría se sugiere por lo que eligió antes en ese comercio.

### Planeador de metas (reemplaza al simulador actual)
Responde, con los datos reales, dos preguntas:
1. **«¿Cuándo llego a cada meta?»** Toma lo que de verdad se puede apartar al mes (según la
   historia) y lo reparte entre todas las metas por prioridad, con una fecha realista para cada una.
2. **«¿Voy como planeé?»** Se guarda un plan y cada mes se compara contra lo que pasó.
Los cálculos son exactos y en código, sin IA.

### Notificaciones push
- Nunca llevan montos en el texto.
- Tipos:
  - **Seguridad** (siempre encendidas): passkey nueva o uso de un código de recuperación.
  - **Por pagar:** un pago fijo o la cuota de una deuda vence en N días.
  - **CDP:** el día antes y el día que vence (no insiste después).
  - **Presupuesto:** una categoría llegó al 80 % o al 100 %, una vez por mes.
  - **La casa:** propuesta de dinero personal; el mes está listo para cerrar (día 1).
  - **Facturas:** un resumen al día (6 p. m.), no una por factura.
  - **Diezmo:** un recordatorio cada quincena si hay diezmo por dar (días 1 y 16, 9 a. m.).
  - **Metas:** una meta llegó al 25, 50, 75 o 100 %.
  - **Nunca:** cada movimiento que anota la otra persona.
- Cada tipo se enciende y apaga desde Perfil (menos seguridad).
- Horas de silencio de 9 p. m. a 7 a. m. (menos seguridad): lo que pase en ese rato se guarda y
  llega a las 7 a. m.
- Como máximo, un aviso de cada tipo al día.

## Alcance
**Dentro:** cuentas y sobres, movimientos, presupuesto, metas y planeador, CDP, deudas, La casa
y el dinero personal, facturas y tiquetes, historia, «¿Voy bien?», notificaciones push.

**Fuera:**
- Consejos de inversión (decirle a alguien en qué invertir o cuándo comprar dólares).
- IA de pago.
- Tarjetas de crédito [PENDIENTE: hoy no las usan; ¿se quedan fuera?].

## Criterios de calidad (una pantalla está bien si…)
- Alguien nuevo sabe qué hacer en 5 segundos, sin leer el glosario.
- Cada estado vacío tiene su explicación y su botón.
- Ningún número aparece sin una frase que diga qué significa.
- Contraste 4.5:1, toques de 44 px, foco visible, el lector de pantalla lee bien ₡ y $.
- Funciona a 375 px, en modo noche y con las animaciones apagadas.
- Se revisa en el teléfono antes del merge.

## Decisiones
| Fecha | Decisión | Por qué | Provisional |
|---|---|---|---|
| 2026-11 | El ahorro es una transferencia, no un gasto | Sigue siendo mío | No |
| 2026-11-20 | Aportar a la casa cuenta como ingreso de la casa | Equipo con un mismo objetivo | No |
| 2026-11-23 | El espacio compartido se llama siempre «La casa» | Un solo nombre | No |
| 2026-11-23 | Sin IA de pago, ni en el simulador ni en facturas | Cálculo exacto, sin costo | No |
| 2026-11-26 | La app es para toda la vida, no solo para casarse | Responsabilidad financiera | No |
| 2026-11-26 | Por ahora para la pareja; los amigos, cada uno en su servidor | Privacidad y sin usar recursos ajenos | No |
| 2026-11-26 | Gabito actualiza los servidores de los amigos por Tailscale, salvo que ellos prefieran hacerlo | Ellos no tienen que saber de Docker | No |
| 2026-11-26 | Saldos en tiempo real entre las dos personas | Evitar duplicados al anotar a la vez | No |
| 2026-11-26 | Dinero en tres partes: Ahorrado / Disponible / Te sobra | Saber qué usar sin tocar el ahorro | No |
| 2026-11-26 | Ahorro = lugares de ahorro (CDP); la tasa del mes cuenta depósitos y retiros al instante | Definición humana del ahorro | No |
| 2026-11-26 | Fondo de emergencia: 3 meses por defecto, con el promedio de los meses cerrados con datos, en colones | Dato real, no inflado | No |
| 2026-11-26 | Diezmo apagado por defecto, monto visible y destello único; pendiente acumulado; incluye intereses del CDP | No olvidar darlo, sin molestar | No |
| 2026-11-26 | El CDP se define completo al renovar, con retiro o aporte | Cada renovación es distinta | No |
| 2026-11-26 | El interés del plazo que terminó sale de los datos de ese plazo; los nuevos valen para el siguiente | Que cuadre con el comprobante del banco | No |
| 2026-11-26 | El retiro del CDP va a la cuenta que la persona elija | Puede no ser la cuenta de los intereses | No |
| 2026-11-26 | Al renovar el CDP no queda en cero; para cerrarlo se archiva | Evitar cerrar un CDP por un toque equivocado | No |
| 2026-11-26 | Deudas: necesario contra promedio si hay fecha; estimado si no | Saber si se llega a tiempo | No |
| 2026-11-26 | Dinero personal decidido por los dos al cerrar el mes; sugerencia igual para ambos; montos ajustables y distintos | No es un derecho fijo | No |
| 2026-11-26 | Agregar tiquetes electrónicos al buzón | Quien no está inscrito en Hacienda recibe tiquetes | No |
| 2026-11-26 | El planeador reemplaza al simulador; empieza por «¿cuándo llego?» y «¿voy como planeé?» | El simulador actual no sirve | No |
| 2026-11-26 | Push: la lista de tipos, diezmo quincenal, silencio de 9 p. m. a 7 a. m. | Avisar lo útil, sin ruido | No |
| 2026-11-27 | Tiempo real: el servidor avisa al instante y la pantalla se actualiza sola, sin mensaje; solo mientras la app está a la vista | Ver lo de la otra persona sin ruido ni gasto de batería | No |
| 2026-11-27 | Push: «Metas» se queda como tipo propio (25, 50, 75 y 100 %) | Son buenas noticias y llegan pocas veces | No |
| 2026-11-27 | Push del CDP: el día antes y el día que vence, sin insistir después | Prepararse y no olvidarlo, sin ruido | No |
| 2026-11-27 | Push en horas de silencio: se guarda y sale a las 7 a. m. (sin repetir un tipo que ya salió ese día) | No perder avisos | No |
| 2026-11-27 | Push del diezmo: días 1 y 16 a las 9 a. m. | El día después de cada quincena | No |
| 2026-11-27 | Push de facturas: resumen a las 6 p. m.; presupuesto una vez al día a las 7 p. m.; recordatorio de cierre solo el día 1 | Uno al día por tipo, antes del silencio | Sí |
