# Despliegue (Fase 7)

Servidor: Oracle Cloud **Always Free**, Ubuntu 24.04 ARM64 (1 OCPU, 6 GB). Acceso **solo por Tailscale**: ningún puerto abierto a internet (ADR-003).

```
iPhone / laptop ──(tailnet, WireGuard)──▶ tailscale serve :443 (HTTPS *.ts.net)
                                              │
                                              ▼ 127.0.0.1:8080
                                   ┌──── app (Spring, uid 10001, solo lectura)
                       red "egress"│          │ red "db" (internal: sin internet)
                  (tipo de cambio) ▼          ▼
                                internet    db (Postgres, solo lectura + volumen)
```

## Primera vez

1. Crear la instancia (ver bitácora) y entrar por SSH con la llave `casa-ayg`.
2. Preparar el servidor:
   ```bash
   curl -fsSLO https://raw.githubusercontent.com/Unxpected155/casa-AyG/main/deploy/setup-server.sh
   less setup-server.sh            # léelo antes de correrlo
   sudo bash setup-server.sh
   ```
3. Unir a la tailnet con Tailscale SSH: `sudo tailscale up --ssh --hostname casa-ayg`.
4. Cerrar el SSH público: quitar la regla del puerto 22 en la *Security List* de Oracle y
   `sudo systemctl disable --now ssh.socket ssh.service`. Desde ahí: `ssh <usuario>@casa-ayg`
   por Tailscale.
5. HTTPS solo para la tailnet: `sudo tailscale serve --bg --https=443 http://127.0.0.1:8080`.
6. `/etc/casa-ayg/casa-ayg.env` (modo 0600) con `PUBLIC_HOST=casa-ayg.<tu-red>.ts.net`.
7. Desplegar (ver abajo) y crear los usuarios:
   ```bash
   cd /opt/casa-ayg && sudo docker compose --env-file /etc/casa-ayg/casa-ayg.env -f deploy/compose.yaml \
     exec -T db psql -U casaayg_owner -d casaayg -tAc "SELECT admin_create_user('ana', 'Ana')"
   ```

## Desplegar una versión

Cada push a `main` publica `ghcr.io/unxpected155/casa-ayg@sha256:…` (ver el resumen del workflow **Imagen**).

```bash
# En tu PC: verifica que la imagen la construyó el pipeline de este repo
gh attestation verify oci://ghcr.io/unxpected155/casa-ayg@sha256:<digest> --repo Unxpected155/casa-AyG
# En el servidor
sudo bash /opt/casa-ayg/deploy/deploy.sh ghcr.io/unxpected155/casa-ayg@sha256:<digest>
```

## Respaldos

- Diarios a las 03:15, cifrados con **age** (el servidor solo tiene la llave pública) y subidos a Google Drive con **rclone** (`drive.file`).
- La llave privada vive en tu gestor de contraseñas, **nunca** en el servidor.
- Restaurar en una BD de prueba: `sudo bash deploy/restore.sh <respaldo> <llave>`.
