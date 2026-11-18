#!/usr/bin/env bash
# Despliega una versión de Casa A&G por DIGEST (nunca por tag: un tag puede moverse).
# Uso (en el servidor):  sudo bash deploy.sh ghcr.io/unxpected155/casa-ayg@sha256:<digest>
# Antes, desde tu PC, verifica que esa imagen la construyó el pipeline del repo:
#   gh attestation verify oci://<imagen@sha256:...> --repo Unxpected155/casa-AyG
set -euo pipefail

[[ $EUID -eq 0 ]] || { echo "Correr con sudo." >&2; exit 1; }
IMAGE="${1:-}"
[[ $IMAGE =~ ^ghcr\.io/unxpected155/casa-ayg@sha256:[0-9a-f]{64}$ ]] \
  || { echo "Uso: deploy.sh ghcr.io/unxpected155/casa-ayg@sha256:<64 hex>" >&2; exit 1; }

ENV_FILE=/etc/casa-ayg/casa-ayg.env
[[ -f $ENV_FILE ]] || { echo "Falta $ENV_FILE (con PUBLIC_HOST)." >&2; exit 1; }

cd /opt/casa-ayg
git pull --ff-only --quiet

# Guarda la imagen en el archivo de entorno (y conserva la anterior para volver atrás).
previous=$(grep -E '^APP_IMAGE=' "$ENV_FILE" | cut -d= -f2- || true)
grep -v '^APP_IMAGE=' "$ENV_FILE" > "$ENV_FILE.tmp" || true
echo "APP_IMAGE=$IMAGE" >> "$ENV_FILE.tmp"
mv "$ENV_FILE.tmp" "$ENV_FILE" && chmod 0600 "$ENV_FILE"

compose=(docker compose --env-file "$ENV_FILE" -f deploy/compose.yaml)
"${compose[@]}" pull --quiet app
"${compose[@]}" up -d --wait --wait-timeout 180 \
  || {
    echo "La versión nueva no quedó sana." >&2
    [[ -n $previous ]] && echo "Para volver atrás: sudo bash deploy/deploy.sh $previous" >&2
    exit 1
  }
"${compose[@]}" ps
echo "Desplegado: $IMAGE"
