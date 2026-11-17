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

```
Postgres ──pg_dump──▶ copia completa ──age (llave PÚBLICA)──▶ .dump.age ──rclone──▶ Google Drive
                                                                  └──▶ /var/backups/casa-ayg (14 días)
```

| Pieza | Qué hace |
|---|---|
| `pg_dump -Fc` | Copia completa de la base (formato custom, comprimido). Nunca toca el disco en claro: va por un pipe al cifrado. |
| **age** | Cifra con la llave pública (`/etc/casa-ayg/backup-recipients.txt`). El servidor puede cifrar pero **no descifrar**. |
| **rclone** | Sube a Drive (`gdrive:casa-ayg-respaldos`) con scope `drive.file`: solo ve lo que él mismo creó. Config en `/etc/casa-ayg/rclone.conf` (0600, root). |
| `casa-ayg-backup.timer` | Todos los días a las 03:15 UTC (21:15 en Costa Rica). `Persistent=true`: si el servidor estaba apagado, corre al encender. |
| `restore.sh` | Descifra y restaura; por defecto en una BD de prueba. Destruye la llave con `shred` al terminar. |

Retención: 14 días en el servidor, 90 en Drive. Lo máximo que se pierde ante un desastre es lo anotado
desde el último respaldo (menos de 24 h).

### Lo que hay que cuidar (fuera del servidor)

1. **La llave privada de age** (`AGE-SECRET-KEY-1…`): en el gestor de contraseñas **y** una copia impresa en un
   lugar seguro. Es lo único insustituible: sin ella ningún respaldo se puede abrir.
2. Las cuentas de **Google** (respaldos), **GitHub** (código e imágenes), **Tailscale** y **Oracle**, todas con
   verificación en dos pasos.
3. Las passkeys y los códigos de recuperación de cada persona (para entrar a la app).

### Revisar que los respaldos siguen corriendo

```bash
systemctl list-timers casa-ayg-backup.timer          # próxima ejecución
sudo journalctl -u casa-ayg-backup.service -n 20      # resultado de las últimas
sudo rclone --config /etc/casa-ayg/rclone.conf ls gdrive:casa-ayg-respaldos | tail -3
```

Una vez al año: repetir la **prueba de restauración** (escenario 1 en la BD de prueba).

## Recuperación ante desastres

### Llevar la llave privada al servidor (solo mientras se restaura)

```powershell
# En tu PC: crea el archivo con el contenido de la nota del gestor de contraseñas, súbelo y bórralo.
notepad $HOME\llave.txt
scp $HOME\llave.txt ubuntu@casa-ayg:/tmp/llave.txt
Remove-Item $HOME\llave.txt
```

`restore.sh` la destruye al terminar. Si algo falla antes: `sudo shred -u /tmp/llave.txt`.

### Escenario 1: se borró algo por error (o prueba anual)

```bash
sudo ls /var/backups/casa-ayg/                          # respaldos de los últimos 14 días
# Más viejos: traerlos de Drive
sudo rclone --config /etc/casa-ayg/rclone.conf copy gdrive:casa-ayg-respaldos/<archivo> /var/backups/casa-ayg/

# Restaurar en la BD de PRUEBA (casaayg_restore_test) para mirar cómo estaban las cosas
sudo bash /opt/casa-ayg/deploy/restore.sh /var/backups/casa-ayg/<archivo> /tmp/llave.txt
```

Si hay que volver la base real a ese respaldo (se pierde lo anotado después): agregar `--real`. Pide escribir
`RESTAURAR`, detiene la app, restaura y la vuelve a encender. Al terminar de revisar la BD de prueba, borrarla:
`sudo docker compose --env-file /etc/casa-ayg/casa-ayg.env -f deploy/compose.yaml exec -T db dropdb -U postgres casaayg_restore_test`.

### Escenario 2: se perdió el servidor

Oracle recuperó la instancia, se dañó el disco o se borró. Tiempo estimado: una hora.

1. **Tailscale → Machines:** borrar la máquina vieja `casa-ayg` **antes** de unir la nueva. Las passkeys están
   atadas al dominio `casa-ayg.<tu-red>.ts.net`: si la nueva queda como `casa-ayg-1`, **ninguna passkey funciona**.
2. Crear la instancia nueva en Oracle (Ubuntu 24.04 aarch64, A1.Flex) y seguir **Primera vez**, pasos 1 a 6.
   `setup-server.sh` genera contraseñas de BD nuevas; no importa, el respaldo se restaura sin dueños (`--no-owner`).
3. **Tailscale → Access controls:** el servidor nuevo tiene **otra IP 100.x**. Actualizar la entrada
   `"hosts": { "casa-ayg": "100.x.y.z" }` con la nueva (`tailscale ip -4` en el servidor); si no, solo el
   administrador llega a la app.
4. Desplegar la última imagen (digest en el resumen del workflow **Imagen** en GitHub):
   `sudo bash /opt/casa-ayg/deploy/deploy.sh ghcr.io/unxpected155/casa-ayg@sha256:<digest>`. La app arranca vacía.
5. Volver a conectar Drive (la config vieja se fue con el servidor). En tu PC:
   ```powershell
   rclone config create gdrive drive scope=drive.file --config $HOME\casa-ayg-rclone.conf
   scp $HOME\casa-ayg-rclone.conf ubuntu@casa-ayg:/tmp/rclone.conf.tmp
   Remove-Item $HOME\casa-ayg-rclone.conf
   ```
   En el servidor:
   ```bash
   sudo install -m 0600 -o root -g root /tmp/rclone.conf.tmp /etc/casa-ayg/rclone.conf && shred -u /tmp/rclone.conf.tmp
   echo "<llave pública age1…>" | sudo install -m 0644 /dev/stdin /etc/casa-ayg/backup-recipients.txt
   sudo install -m 0644 /opt/casa-ayg/deploy/systemd/casa-ayg-backup.* /etc/systemd/system/
   sudo systemctl daemon-reload && sudo systemctl enable --now casa-ayg-backup.timer
   ```
6. Traer el último respaldo de Drive y restaurar en la base real:
   ```bash
   sudo rclone --config /etc/casa-ayg/rclone.conf ls gdrive:casa-ayg-respaldos | sort -k2 | tail -1
   sudo rclone --config /etc/casa-ayg/rclone.conf copy gdrive:casa-ayg-respaldos/<archivo> /var/backups/casa-ayg/
   sudo bash /opt/casa-ayg/deploy/restore.sh /var/backups/casa-ayg/<archivo> /tmp/llave.txt --real
   ```
   Vuelven usuarios, passkeys, movimientos y lugares: cada quien entra con su passkey de siempre.
7. Correr un respaldo a mano para confirmar: `sudo systemctl start casa-ayg-backup.service`.

### Escenario 3: se perdió la llave privada de age

Los respaldos existentes **no se pueden recuperar**. Mientras el servidor siga sano no se pierde nada:
generar una llave nueva (`age-keygen`), guardar la privada (gestor + copia impresa), reemplazar
`/etc/casa-ayg/backup-recipients.txt` con la pública nueva y correr un respaldo a mano.

### Escenario 4: se perdió la PC

Nada importante vive solo ahí: el código está en GitHub, los datos en el servidor y en Drive, la llave en el
gestor. En la PC nueva: instalar Tailscale (misma cuenta), generar una llave SSH nueva si hace falta y entrar con
`ssh ubuntu@casa-ayg` (Tailscale SSH no usa la llave de Oracle).
