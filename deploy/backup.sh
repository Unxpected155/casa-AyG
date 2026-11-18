#!/usr/bin/env bash
# Respaldo cifrado de la BD → Google Drive. Lo corre un timer de systemd todos los días.
#   1. pg_dump dentro del contenedor (formato custom, comprimido).
#   2. Se cifra con age usando SOLO la llave pública: el servidor puede cifrar pero no
#      descifrar. La llave privada vive fuera del servidor (tu gestor de contraseñas).
#   3. Se sube a Google Drive con rclone (scope drive.file: solo ve lo que él mismo creó).
# El dump en claro nunca toca el disco: va por un pipe directo al cifrado.
set -euo pipefail

RECIPIENTS=/etc/casa-ayg/backup-recipients.txt
RCLONE_CONF=/etc/casa-ayg/rclone.conf
REMOTE=gdrive:casa-ayg-respaldos
DIR=/var/backups/casa-ayg

[[ -s $RECIPIENTS ]] || { echo "Falta $RECIPIENTS (llave pública de age)." >&2; exit 1; }

stamp=$(date -u +%Y%m%dT%H%M%SZ)
file="$DIR/casa-ayg-$stamp.dump.age"
umask 077

cd /opt/casa-ayg
docker compose --env-file /etc/casa-ayg/casa-ayg.env -f deploy/compose.yaml \
  exec -T db pg_dump -U postgres -d casaayg --format=custom \
  | age --encrypt --recipients-file "$RECIPIENTS" > "$file.tmp"
mv "$file.tmp" "$file"

rclone --config "$RCLONE_CONF" copy "$file" "$REMOTE"

# Retención: 14 días en el servidor, 90 en Drive.
find "$DIR" -name 'casa-ayg-*.dump.age' -mtime +14 -delete
rclone --config "$RCLONE_CONF" delete --min-age 90d "$REMOTE"

echo "Respaldo: $(basename "$file") ($(stat -c %s "$file") bytes)"
