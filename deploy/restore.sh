#!/usr/bin/env bash
# Restaura un respaldo cifrado. Por defecto en una BD de PRUEBA (casaayg_restore_test), para
# practicar la restauración sin tocar los datos reales (ejercicio 7.4).
#
# Uso:  sudo bash restore.sh <respaldo.dump.age> <archivo-llave-privada-age> [--real]
#   La llave privada se copia al servidor solo para esto y se borra al terminar.
set -euo pipefail

[[ $EUID -eq 0 ]] || { echo "Correr con sudo." >&2; exit 1; }
BACKUP="${1:?respaldo .dump.age}"
KEY="${2:?archivo con la llave privada de age}"
TARGET=casaayg_restore_test
[[ "${3:-}" == "--real" ]] && TARGET=casaayg

cd /opt/casa-ayg
compose=(docker compose --env-file /etc/casa-ayg/casa-ayg.env -f deploy/compose.yaml)

if [[ $TARGET == casaayg ]]; then
  read -r -p "Esto REEMPLAZA los datos reales. Escribe RESTAURAR para seguir: " answer
  [[ $answer == RESTAURAR ]] || exit 1
  "${compose[@]}" stop app
else
  "${compose[@]}" exec -T db dropdb -U postgres --if-exists "$TARGET"
  "${compose[@]}" exec -T db createdb -U postgres -O casaayg_owner "$TARGET"
fi

age --decrypt --identity "$KEY" "$BACKUP" \
  | "${compose[@]}" exec -T db pg_restore -U postgres -d "$TARGET" --clean --if-exists --no-owner --role=casaayg_owner

shred -u "$KEY" 2>/dev/null || rm -f "$KEY"

"${compose[@]}" exec -T db psql -U postgres -d "$TARGET" -c \
  "SELECT (SELECT count(*) FROM casaayg.app_user) AS usuarios,
          (SELECT count(*) FROM casaayg.transaction) AS movimientos,
          (SELECT max(version) FROM casaayg.flyway_schema_history) AS migracion"

[[ $TARGET == casaayg ]] && "${compose[@]}" start app
echo "Restaurado en $TARGET. La llave privada se borró del servidor."
