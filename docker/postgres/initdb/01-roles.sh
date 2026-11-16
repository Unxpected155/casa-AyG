#!/bin/sh
# Se ejecuta UNA sola vez, cuando el volumen de datos está vacío (primer arranque).
# Crea los roles con privilegio mínimo y el esquema. Las contraseñas llegan como
# archivos en /run/secrets/ (secretos de Docker), nunca como variables de entorno.
set -eu

# Las contraseñas las lee psql (\set con `cat`) y no el shell: así nunca aparecen
# en los argumentos del proceso (visibles con `ps`). :'var' las escapa como literales.
psql -v ON_ERROR_STOP=1 \
     --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
     -v db="$POSTGRES_DB" <<'SQL'
\set owner_pw `cat /run/secrets/db_owner_password`
\set app_pw `cat /run/secrets/db_app_password`

-- Dueño del esquema: lo usa SOLO Flyway. No es superusuario.
CREATE ROLE casaayg_owner LOGIN PASSWORD :'owner_pw' NOSUPERUSER NOCREATEDB NOCREATEROLE;

-- Rol de la aplicación en runtime: no es dueño de nada, así que no puede hacer DDL
-- y no puede saltarse RLS. Sus permisos se dan tabla por tabla en V2__app_grants.sql.
CREATE ROLE casaayg_app LOGIN PASSWORD :'app_pw' NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS
    CONNECTION LIMIT 30;

-- Nadie más puede conectarse ni usar el esquema public.
REVOKE ALL ON DATABASE :"db" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"db" TO casaayg_owner, casaayg_app;
REVOKE ALL ON SCHEMA public FROM PUBLIC;

-- Esquema propio, del owner. La app solo puede "ver" que existe (USAGE), no crear objetos.
CREATE SCHEMA casaayg AUTHORIZATION casaayg_owner;
GRANT USAGE ON SCHEMA casaayg TO casaayg_app;

-- Las funciones nuevas NO son ejecutables por PUBLIC (por defecto sí lo serían).
-- Cada migración da EXECUTE a la app de forma explícita.
ALTER DEFAULT PRIVILEGES FOR ROLE casaayg_owner REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;

ALTER ROLE casaayg_owner SET search_path = casaayg;
ALTER ROLE casaayg_app SET search_path = casaayg;

-- Una consulta lenta o una transacción abandonada no puede acaparar la BD (DoS).
ALTER ROLE casaayg_app SET statement_timeout = '10s';
ALTER ROLE casaayg_app SET idle_in_transaction_session_timeout = '30s';
SQL
