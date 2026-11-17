#!/usr/bin/env bash
# Prepara un Ubuntu 24.04 (ARM64, Oracle Always Free) para Casa A&G. Idempotente.
# Uso (en el servidor):  sudo bash setup-server.sh
#
# Qué hace:
#   - Actualizaciones de seguridad automáticas (con reinicio de madrugada si hace falta).
#   - Docker y Tailscale desde sus repositorios oficiales firmados.
#   - Endurece el daemon de Docker.
#   - Genera los secretos de la BD (aleatorios, solo en este servidor) con permisos mínimos.
#   - Clona el repo (público) en /opt/casa-ayg.
# NO abre puertos: el acceso es solo por Tailscale.
set -euo pipefail

[[ $EUID -eq 0 ]] || { echo "Correr con sudo." >&2; exit 1; }
export DEBIAN_FRONTEND=noninteractive

echo "==> Sistema al día y actualizaciones automáticas de seguridad"
apt-get update -q
apt-get upgrade -yq
apt-get install -yq ca-certificates curl gnupg git unattended-upgrades age rclone
cat > /etc/apt/apt.conf.d/52casa-ayg-unattended <<'EOF'
Unattended-Upgrade::Automatic-Reboot "true";
Unattended-Upgrade::Automatic-Reboot-Time "04:30";
Unattended-Upgrade::Remove-Unused-Dependencies "true";
EOF
cat > /etc/apt/apt.conf.d/20auto-upgrades <<'EOF'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
EOF

install -d -m 0755 /etc/apt/keyrings
. /etc/os-release

echo "==> Docker (repositorio oficial)"
if ! command -v docker >/dev/null; then
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu ${VERSION_CODENAME} stable" \
    > /etc/apt/sources.list.d/docker.list
  apt-get update -q
  apt-get install -yq docker-ce docker-ce-cli containerd.io docker-compose-plugin
fi
# Daemon endurecido: sin escalada de privilegios por defecto, contenedores vivos durante
# un reinicio del daemon y logs con tope de tamaño.
cat > /etc/docker/daemon.json <<'EOF'
{
  "no-new-privileges": true,
  "live-restore": true,
  "log-driver": "json-file",
  "log-opts": { "max-size": "10m", "max-file": "5" }
}
EOF
systemctl restart docker

echo "==> Tailscale (repositorio oficial)"
if ! command -v tailscale >/dev/null; then
  curl -fsSL "https://pkgs.tailscale.com/stable/ubuntu/${VERSION_CODENAME}.noarmor.gpg" -o /usr/share/keyrings/tailscale-archive-keyring.gpg
  curl -fsSL "https://pkgs.tailscale.com/stable/ubuntu/${VERSION_CODENAME}.tailscale-keyring.list" -o /etc/apt/sources.list.d/tailscale.list
  apt-get update -q
  apt-get install -yq tailscale
fi

echo "==> Secretos de la base de datos (se generan una sola vez)"
install -d -m 0700 -o root -g root /etc/casa-ayg /etc/casa-ayg/secrets
for name in db_superuser_password db_owner_password db_app_password; do
  f=/etc/casa-ayg/secrets/$name
  [[ -s $f ]] || { openssl rand -hex 32 | tr -d '\n' > "$f"; }
done
# Cada contenedor lee solo lo suyo: postgres (uid 999) los tres; la app (gid 10001) owner y app.
chown 999:999   /etc/casa-ayg/secrets/db_superuser_password && chmod 0400 /etc/casa-ayg/secrets/db_superuser_password
chown 999:10001 /etc/casa-ayg/secrets/db_owner_password /etc/casa-ayg/secrets/db_app_password
chmod 0440      /etc/casa-ayg/secrets/db_owner_password /etc/casa-ayg/secrets/db_app_password

echo "==> Código (repo público) en /opt/casa-ayg"
if [[ ! -d /opt/casa-ayg/.git ]]; then
  git clone --depth 1 https://github.com/Unxpected155/casa-AyG.git /opt/casa-ayg
fi
install -d -m 0700 /var/backups/casa-ayg

echo
echo "Listo. Siguiente paso: sudo tailscale up --ssh --hostname casa-ayg"
