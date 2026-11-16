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
```

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
