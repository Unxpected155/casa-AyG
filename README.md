# Casa A&G

Sitio web privado (PWA) para las finanzas de una pareja: cada quien con su **espacio personal** y, al casarse, un **espacio familiar** compartido. Registra gastos desde las **facturas electrónicas de Costa Rica** (Hacienda v4.4). Construido para **aprender seguridad de aplicaciones** haciendo: threat modeling, seguridad de base de datos, supply chain, GitHub Advanced Security y Docker.

> 🚧 En construcción — Fase 2: backend y base de datos.

Acceso solo por red privada (Tailscale): el sitio no se publica en internet.

Stack: Java 25 + Spring Boot, PostgreSQL, React + TypeScript + GSAP (PWA), Docker.

## Desarrollo local

Requisitos: Java 25, Docker Desktop.

```powershell
./scripts/New-DevSecrets.ps1     # 1. genera ./secrets/ (una sola vez)
docker compose up -d --wait      # 2. Postgres en 127.0.0.1:5432
./backend/mvnw -f backend/pom.xml spring-boot:run   # 3. la app en http://localhost:8080
./scripts/New-User.ps1 -Username ana -DisplayName Ana   # 4. usuario + código de inscripción
```

### Acceso: solo passkeys

No hay contraseñas. El administrador crea a cada persona desde la consola (la app no puede crear usuarios) y le entrega un **código de inscripción** de un solo uso que vence en 24 horas. Con ese código, en `/login`, se abre una sesión que **solo** sirve para registrar passkeys. Después:

- El login es con passkey (Face ID, huella o Windows Hello).
- Conviene registrar **dos** passkeys: teléfono y computadora.
- `POST /api/me/recovery-codes` entrega 10 códigos de recuperación de un solo uso. Usar uno cierra las demás sesiones.
- Si se pierde todo: `./scripts/New-User.ps1 -Username ana -NewCode`.

| Sesión | Dura | Puede |
|---|---|---|
| Passkey | 30 min de inactividad, 7 días como máximo | Usar la API. Registrar o borrar passkeys, generar códigos y aprobar la fusión exige haber usado la passkey hace menos de 5 minutos. |
| Código | 10 minutos | Solo registrar passkeys |

### API

| Recurso | Rutas |
|---|---|
| Cuenta | `GET /api/me`, `GET/DELETE /api/me/passkeys`, `POST /api/me/recovery-codes`, `POST /api/me/sessions/revoke-others` |
| Espacios | `GET /api/spaces`, `GET /api/spaces/{id}/export` |
| Categorías | `GET/POST /api/spaces/{id}/categories`, `PUT/DELETE …/categories/{catId}` |
| Movimientos | `GET/POST /api/spaces/{id}/transactions`, `GET/PUT/DELETE …/transactions/{txId}` |
| Presupuesto | `GET/PUT /api/spaces/{id}/budgets/{año}/{mes}` |
| Resumen del mes | `GET /api/spaces/{id}/summary?year=&month=` (totales en ₡, en $ y consolidado en $) |
| Fusión de espacios | `GET/POST /api/merge-requests`, `POST …/{id}/approve`, `POST …/{id}/decline` |

Tests (levantan su propio Postgres con Testcontainers):

```powershell
./backend/mvnw -f backend/pom.xml verify
```

| Rol de Postgres | Para qué | Puede |
|---|---|---|
| `postgres` | Administración manual | Todo |
| `casaayg_owner` | Flyway (migraciones) | DDL en el esquema `casaayg` |
| `casaayg_app` | La app en runtime | Solo DML, sujeta a RLS por espacio; `audit_log` solo `INSERT`/`SELECT` |

## Seguridad

¿Encontraste una vulnerabilidad? No abras un issue: sigue [SECURITY.md](SECURITY.md).

## Licencia

[MIT](LICENSE)
