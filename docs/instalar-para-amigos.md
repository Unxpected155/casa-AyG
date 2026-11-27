# Instalar Casa A&G para otra pareja

Guía completa para darle a una pareja amiga **su propia** Casa A&G, en **su propio** servidor.
Está escrita para seguirla sin ayuda, de principio a fin, aunque pasen años.

- **Tú** = quien instala y mantiene (el administrador).
- **Ellos** = la pareja que va a usar la app.

**Tiempo:** unas 3 horas la primera vez, con ellos al lado (las cuentas son de ellos).
**Costo:** ₡0. Oracle pide una tarjeta solo para verificar la cuenta.

> Las consolas de Oracle, Tailscale y Google cambian de nombres y de lugar con los años. Si un
> botón no coincide con esta guía, busca la palabra clave (por ejemplo «Instances», «Users»,
> «Credentials») en el buscador de esa consola.

---

## 0. Antes de empezar

### Lo que tienen que saber ellos (léanlo juntos)

- **Sus datos quedan en su servidor, no en el tuyo.** Nadie más los tiene.
- **Tú, como administrador, técnicamente puedes ver sus datos**: tienes acceso al servidor para
  actualizarlo. Si prefieren que no, ellos mismos actualizan (sección 10 B) y te sacan de su
  red cuando termine la instalación.
- **La llave de los respaldos es de ellos.** Sin ella ningún respaldo se puede abrir. La guardan
  ellos, no tú.
- Las cuentas (Google, Tailscale, Oracle) quedan **a nombre de ellos**, con verificación en dos pasos.

### Lo que necesitan

| Qué | Para qué | Quién |
|---|---|---|
| Una cuenta de **Google nueva, solo para la casa** (por ejemplo `casa.apellidos@gmail.com`) | Respaldos en su Drive y, si quieren, el buzón de facturas | Ellos |
| Una cuenta de **Tailscale** | La red privada: la app no se publica en internet | Ellos |
| Una cuenta de **Oracle Cloud** (Always Free) | El servidor gratuito | Ellos |
| Un **gestor de contraseñas** (por ejemplo Bitwarden, gratis) | Guardar la llave de respaldos | Ellos |
| Una **PC con Windows** | Desde donde se instala | Tú |
| Teléfonos con **iOS 16.4+** o **Android** reciente | Usar la app | Ellos |

### En tu PC (una sola vez)

Abre PowerShell y instala lo que vas a usar:

```powershell
winget install --id Tailscale.Tailscale
winget install --id GitHub.cli
winget install --id FiloSottile.age
winget install --id Rclone.Rclone
```

Cierra y vuelve a abrir PowerShell para que reconozca los comandos. `ssh` y `scp` ya vienen con Windows.

---

## 1. La cuenta de Google de la casa

1. Creen la cuenta en <https://accounts.google.com/signup> con un nombre que identifique la casa.
2. Activen la verificación en dos pasos: <https://myaccount.google.com/security> → **Verificación en dos pasos**.
3. Guarden la contraseña en su gestor de contraseñas.

Esta cuenta se usa para dos cosas: los respaldos (sección 8) y el buzón de facturas (sección 9, opcional).

---

## 2. Tailscale: la red privada

Tailscale crea una red privada entre sus teléfonos y el servidor. La app solo se abre desde
dispositivos de esa red.

### 2.1 Crear la red (la hace uno de ellos)

1. Entren a <https://login.tailscale.com/start> con la cuenta que prefieran (su Google personal
   está bien). Quien la crea queda como **Owner** (dueño) de la red.
2. Activen la verificación en dos pasos en esa cuenta.
3. **DNS:** en <https://login.tailscale.com/admin/dns>:
   - Confirmen que **MagicDNS** está activo.
   - En **HTTPS Certificates**, toquen **Enable HTTPS**. Sin esto la app no abre.
   - Arriba aparece el nombre de la red, algo como `tail1234ab.ts.net`. Si quieren uno más
     bonito, **Rename tailnet** ofrece opciones. **Decídanlo ahora**: después no se debe
     cambiar (ver la advertencia de la sección 5).

### 2.2 Invitar a la pareja y al administrador

En <https://login.tailscale.com/admin/users> → **Invite users**:

1. Inviten a la otra persona de la pareja.
2. Te invitan a ti. Cuando aceptes, el Owner te cambia el rol a **Admin** (en la lista de
   usuarios, los tres puntos al lado de tu nombre → **Edit role** → **Admin**). Sin ese rol no
   puedes preparar el servidor.

> El plan gratuito tiene un límite de usuarios; revisen <https://tailscale.com/pricing>. Al
> momento de escribir esto, el plan Personal alcanza para la pareja más el administrador.

### 2.3 Instalar Tailscale en los dispositivos

- **Ellos:** instalen la app Tailscale (App Store o Play Store) y entren con su cuenta.
- **Tú:** en la app de Tailscale de tu PC, **agrega la cuenta** de esa red (menú de la cuenta →
  **Add account**). Vas a tener tu red y la de ellos. Se cambia de una a otra con:

```powershell
tailscale switch --list          # lista tus redes
tailscale switch <nombre-de-la-red-de-ellos>
```

### 2.4 Reglas de acceso para el servidor

El servidor va a llevar una **etiqueta** (`tag:casa`). Así no depende de la cuenta de nadie: si
algún día sales de su red, el servidor sigue ahí con el mismo nombre (importante para las passkeys).

En <https://login.tailscale.com/admin/acls/file>, el archivo de reglas ya trae una política por
defecto. **No la borren**: agreguen estas dos partes dentro de las llaves principales `{ }`:

```jsonc
  // Quién puede ponerle la etiqueta tag:casa a una máquina: los Admin y el Owner.
  "tagOwners": {
    "tag:casa": ["autogroup:admin"]
  },
```

Y en la lista `"ssh"` que ya existe, agreguen esta regla (va entre los corchetes `[ ]`, separada
de las demás por una coma):

```jsonc
    // Los Admin entran por SSH al servidor; Tailscale pide confirmar en el navegador cada tanto.
    {
      "action": "check",
      "src":    ["autogroup:admin"],
      "dst":    ["tag:casa"],
      "users":  ["ubuntu", "root"]
    }
```

Toquen **Save**. Si el editor marca un error, casi siempre es una coma de más o de menos.

---

## 3. Oracle Cloud: el servidor gratuito

### 3.1 Crear la cuenta (la hacen ellos)

1. Entren a <https://www.oracle.com/cloud/free/> → **Start for free**.
2. Llenen los datos. En **Home Region** elijan una región cercana (por ejemplo una de Estados
   Unidos o México). **La región no se puede cambiar después.**
3. Verifiquen la tarjeta. Oracle no cobra nada mientras se use lo marcado como **Always Free**.
4. Activen la verificación en dos pasos (el perfil, arriba a la derecha → **My profile** → **Security**).

> Oracle puede **recuperar instancias Always Free que pasan mucho tiempo inactivas**. Si les
> llega un correo de aviso, o si quieren evitarlo de raíz, pueden pasar la cuenta a **Pay As You
> Go**: lo Always Free sigue siendo gratis, pero cualquier cosa fuera de esos límites se cobra.
> Si lo hacen, creen un presupuesto con alerta (**Billing & Cost Management → Budgets**) de $1.
> Aunque se perdiera el servidor, todo vuelve desde los respaldos (`deploy/README.md`, escenario 2).

### 3.2 Crear la llave SSH (en tu PC)

```powershell
ssh-keygen -t ed25519 -f $HOME\.ssh\casa-<pareja> -C "casa-<pareja>"
Get-Content $HOME\.ssh\casa-<pareja>.pub    # esta es la PÚBLICA: la vas a pegar en Oracle
```

Cambia `<pareja>` por un nombre corto, por ejemplo `casa-lucia-pablo`. La privada
(`casa-<pareja>`, sin `.pub`) **nunca** sale de tu PC. Solo se usa la primera vez; después se
entra por Tailscale.

### 3.3 Crear la máquina

En la consola de Oracle: menú ☰ → **Compute** → **Instances** → **Create instance**.

| Campo | Valor |
|---|---|
| Name | `casa-ayg` |
| Image | **Change image** → **Canonical Ubuntu** → **24.04** (la versión `aarch64`) |
| Shape | **Change shape** → **Ampere** → `VM.Standard.A1.Flex`, **1 OCPU** y **6 GB** de memoria. Debe decir **Always Free-eligible** |
| Networking | **Create new virtual cloud network** y **public subnet**; **Assign a public IPv4 address**: sí |
| Add SSH keys | **Paste public keys** → pega lo que mostró `Get-Content` |
| Boot volume | Lo que viene por defecto |

Toquen **Create**. En un par de minutos el estado pasa a **Running**. Anoten la
**Public IP address**.

> Si sale **Out of capacity**, Oracle no tiene máquinas libres en ese momento. Prueben otro
> **Availability domain** en la misma pantalla, o intenten más tarde (a veces tarda días).

---

## 4. Preparar el servidor

### 4.1 Entrar por primera vez (por la IP pública)

```powershell
ssh -i $HOME\.ssh\casa-<pareja> ubuntu@<IP-pública>
```

La primera vez pregunta si confías en la máquina: escribe `yes`.

### 4.2 Instalar todo con el script del repo

Ya dentro del servidor:

```bash
curl -fsSLO https://raw.githubusercontent.com/Unxpected155/casa-AyG/main/deploy/setup-server.sh
less setup-server.sh            # léelo antes de correrlo (q para salir)
sudo bash setup-server.sh
```

El script actualiza Ubuntu, instala Docker, Tailscale, `age` y `rclone`, genera las contraseñas
de la base de datos y las llaves de la app (solo existen en ese servidor) y baja el código a
`/opt/casa-ayg`. No abre puertos.

### 4.3 Unir el servidor a la red de ellos, con la etiqueta

```bash
sudo tailscale up --ssh --hostname casa-ayg --advertise-tags=tag:casa
```

Imprime un enlace: ábrelo en tu PC y entra con **tu** cuenta de la red de ellos (la que tiene
rol Admin). En <https://login.tailscale.com/admin/machines> aparece `casa-ayg` con la etiqueta
`tag:casa`.

En el panel de máquinas, `casa-ayg` debe decir **Expiry disabled** (las máquinas con etiqueta
lo traen así). Si no, en sus tres puntos toquen **Disable key expiry**: si la llave vence,
Tailscale saca al servidor de la red.

### 4.4 Cerrar la entrada pública

1. En Oracle: menú ☰ → **Networking** → **Virtual cloud networks** → la red que se creó →
   **Subnets** → la subred pública → **Security Lists** → **Default Security List** → en
   **Ingress Rules**, borren la regla del puerto **22**.
2. En el servidor (todavía conectado):

```bash
sudo systemctl disable --now ssh.socket ssh.service
exit
```

3. Desde ahora entras solo por Tailscale. En tu PC:

```powershell
tailscale switch <nombre-de-la-red-de-ellos>
ssh ubuntu@casa-ayg
```

La primera vez, Tailscale abre el navegador para confirmar que eres tú (por la regla `check`).

---

## 5. El nombre de la app y el HTTPS

> ⚠️ **El nombre `casa-ayg.<su-red>.ts.net` es para siempre.** Las passkeys quedan atadas a ese
> nombre. Si cambia (porque se renombra la red, o el servidor vuelve a entrar como `casa-ayg-1`),
> **ninguna passkey funciona** y cada persona tiene que inscribirse de nuevo.

En el servidor:

```bash
tailscale status --json | grep -m1 '"DNSName"'     # muestra casa-ayg.<su-red>.ts.net.
sudo tailscale serve --bg --https=443 http://127.0.0.1:8080
```

Crea el archivo de configuración con ese nombre (sin el punto final):

```bash
echo "PUBLIC_HOST=casa-ayg.<su-red>.ts.net" | sudo install -m 0600 /dev/stdin /etc/casa-ayg/casa-ayg.env
```

---

## 6. Desplegar la app

### 6.1 En tu PC: elegir la versión y verificarla

Cada versión de la app es una imagen en `ghcr.io/unxpected155/casa-ayg`, identificada por su
**digest** (`sha256:…`). Para encontrar el de la última:

1. Abre <https://github.com/Unxpected155/casa-AyG/actions>, el workflow **Imagen**, la
   última ejecución en verde sobre `main`.
2. En el resumen aparece el digest (`sha256:` y 64 letras y números).

Verifica que esa imagen la construyó el pipeline del repo, y su lista de componentes (SBOM):

```powershell
$env:D = "sha256:<digest>"
gh attestation verify "oci://ghcr.io/unxpected155/casa-ayg@$env:D" --repo Unxpected155/casa-AyG
gh attestation verify "oci://ghcr.io/unxpected155/casa-ayg@$env:D" --repo Unxpected155/casa-AyG --predicate-type https://cyclonedx.org/bom
```

Las dos deben terminar con **Verification succeeded**. Si alguna falla, **no despliegues**.

### 6.2 En el servidor

```bash
sudo bash /opt/casa-ayg/deploy/deploy.sh ghcr.io/unxpected155/casa-ayg@sha256:<digest>
```

Termina con `Desplegado: …`. En el teléfono de ellos (con Tailscale encendido), abran
`https://casa-ayg.<su-red>.ts.net`: debe salir la portada de Casa A&G.

---

## 7. Crear a cada persona

En el servidor, una vez por persona (el primer nombre es el usuario, sin espacios ni tildes; el
segundo es como lo va a mostrar la app):

```bash
cd /opt/casa-ayg && sudo docker compose --env-file /etc/casa-ayg/casa-ayg.env -f deploy/compose.yaml \
  exec -T db psql -U casaayg_owner -d casaayg -tAc "SELECT admin_create_user('lucia', 'Lucía')"
```

Devuelve un **código de inscripción** de un solo uso, que vence en **24 horas**. Dáselo en
persona (no por chat).

### En el teléfono de cada persona

1. Tailscale encendido.
2. Abrir `https://casa-ayg.<su-red>.ts.net` → **Tengo un código** → escribir el usuario y el código.
3. Registrar la passkey (huella o cara) y luego **Entrar**.
4. **Agregar a la pantalla de inicio:**
   - iPhone (Safari): botón Compartir → **Agregar a inicio**.
   - Android (Chrome): menú ⋮ → **Instalar app**.
5. Desde la app instalada, en **Más → Perfil y seguridad**:
   - Registrar una **segunda passkey** en otro dispositivo, si tienen.
   - Generar los **códigos de recuperación** y guardarlos en el gestor de contraseñas.
   - Activar las **notificaciones** (en iPhone solo funcionan desde la app agregada a inicio).

Cuando ya estén los dos: uno entra a **Más → La casa** y le propone al otro unir sus finanzas.

---

## 8. Respaldos: la llave y Google Drive

Cada noche el servidor copia la base de datos, la **cifra** con una llave pública y la sube a
su Google Drive. El servidor puede cifrar pero **no descifrar**: para abrir un respaldo hace falta
la llave privada, que guardan ellos.

### 8.1 Crear la llave (en tu PC, con ellos al lado)

```powershell
age-keygen -o $HOME\llave-casa.txt
Get-Content $HOME\llave-casa.txt
```

El archivo tiene dos líneas importantes:
- `# public key: age1…` → la **pública**. Cópiala; va al servidor.
- `AGE-SECRET-KEY-1…` → la **privada**.

Ellos guardan **todo el contenido del archivo** como una nota segura en su gestor de
contraseñas, y además **una copia impresa** en un lugar seguro de su casa. Es lo único que no
se puede reponer: sin ella, ningún respaldo sirve.

Cuando confirmen que la guardaron, bórrala de tu PC:

```powershell
Remove-Item $HOME\llave-casa.txt
```

### 8.2 Conectar Google Drive

En tu PC:

```powershell
rclone config create gdrive drive scope=drive.file --config $HOME\casa-rclone.conf
```

Se abre el navegador: entren con la **cuenta de Google de la casa** y toquen **Permitir**.
`drive.file` significa que rclone solo ve los archivos que él mismo crea, no el resto de su Drive.

Súbelo al servidor y bórralo de tu PC:

```powershell
scp $HOME\casa-rclone.conf ubuntu@casa-ayg:/tmp/rclone.conf.tmp
Remove-Item $HOME\casa-rclone.conf
```

### 8.3 Activar los respaldos (en el servidor)

```bash
sudo install -m 0600 -o root -g root /tmp/rclone.conf.tmp /etc/casa-ayg/rclone.conf && shred -u /tmp/rclone.conf.tmp
echo "age1<la-llave-pública>" | sudo install -m 0644 /dev/stdin /etc/casa-ayg/backup-recipients.txt
sudo install -m 0644 /opt/casa-ayg/deploy/systemd/casa-ayg-backup.* /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now casa-ayg-backup.timer
```

Corre uno a mano y revisa que llegó a Drive:

```bash
sudo systemctl start casa-ayg-backup.service
sudo journalctl -u casa-ayg-backup.service -n 20
sudo rclone --config /etc/casa-ayg/rclone.conf ls gdrive:casa-ayg-respaldos
```

En el Drive de la casa aparece la carpeta `casa-ayg-respaldos`. Los respaldos corren todos los
días a las 9:15 p. m. (hora de Costa Rica); se guardan 14 días en el servidor y 90 en Drive.

### 8.4 Probar que se puede restaurar

Hazlo el mismo día de la instalación y después una vez al año. Sigue el **escenario 1** de
[`deploy/README.md`](../deploy/README.md): restaura en una base de prueba, revisa, y borra la base
de prueba. Para eso ellos te pasan la llave privada solo durante la prueba (el script la destruye al terminar).

---

## 9. Buzón de facturas (opcional)

Sirve para que las facturas y tiquetes electrónicos que les mandan los comercios por correo
aparezcan en **Facturas**, listos para anotarlos con un toque. Si no lo quieren, sáltense esta
sección: la app funciona igual.

### 9.1 Crear el proyecto de Google Cloud (con la cuenta de Google de la casa)

1. Entren a <https://console.cloud.google.com/> con la cuenta de la casa y acepten los términos.
   **No activen facturación**: no hace falta.
2. Arriba, en el selector de proyectos → **New project** → nombre `Casa facturas` → **Create**.
3. Con ese proyecto elegido: **APIs & Services → Library** → busquen **Gmail API** → **Enable**.
4. **Google Auth Platform** (o **OAuth consent screen**) → **Get started**:
   - **App name:** `Casa A&G`.
   - **User support email** y **Developer contact:** la cuenta de la casa.
   - **Audience:** **External**.
5. **Branding:** en **Application privacy policy link** pongan
   `https://casa-ayg.<su-red>.ts.net/privacidad`.
6. **Data access → Add or remove scopes:** busquen y marquen `.../auth/gmail.readonly` → **Update** → **Save**.
7. **Audience → Publish app** → **Confirm**. Queda **In production**. **No pidan verificación**:
   es para uso propio y no hace falta.
8. **Clients → Create client:**
   - **Application type:** **Web application**.
   - **Name:** `Casa A&G`.
   - **Authorized redirect URIs → Add URI:** `https://casa-ayg.<su-red>.ts.net/api/invoices/oauth/callback`
   - **Create**, y luego **Download JSON**.

> Ese JSON es un secreto: no lo manden por chat ni lo suban a ningún repositorio.

### 9.2 Ponerlo en el servidor

En tu PC (cambia el nombre del archivo por el que bajó):

```powershell
scp $HOME\Downloads\client_secret_XXXX.json ubuntu@casa-ayg:/tmp/google.json
Remove-Item $HOME\Downloads\client_secret_XXXX.json
```

En el servidor:

```bash
sudo install -m 0440 -o root -g 10001 /tmp/google.json /etc/casa-ayg/secrets/google_oauth_client && shred -u /tmp/google.json
cd /opt/casa-ayg && sudo docker compose --env-file /etc/casa-ayg/casa-ayg.env -f deploy/compose.yaml up -d --force-recreate app
```

### 9.3 Conectar el buzón en la app

1. En la app: **Más → Facturas** → conectar el buzón.
2. Entren con la **cuenta de Google de la casa**.
3. Google muestra **«Google no verificó esta app»**: es normal, porque la app es de ustedes.
   Toquen **Configuración avanzada → Ir a Casa A&G** → **Permitir**.
4. Al pagar, pidan que el comprobante se envíe a ese correo: con su cédula si quieren
   **factura**, o solo el correo para el **tiquete electrónico**.

---

## 10. Actualizar la app

### A) La actualizas tú (lo acordado por defecto)

Cuando haya una versión nueva en `main`:

1. En tu PC, busca el digest y verifícalo (sección 6.1).
2. Cámbiate a la red de ellos y entra. Ojo: cada red tiene **su propio** `casa-ayg`. Confirma en
   cuál estás antes de tocar nada.

```powershell
tailscale switch <nombre-de-la-red-de-ellos>
ssh ubuntu@casa-ayg
```

3. En el servidor: primero un respaldo, después el despliegue.

```bash
sudo systemctl start casa-ayg-backup.service && sudo journalctl -u casa-ayg-backup.service -n 5
sudo bash /opt/casa-ayg/deploy/deploy.sh ghcr.io/unxpected155/casa-ayg@sha256:<digest>
```

4. Abre la app desde un dispositivo de esa red y revisa que entra. Si algo salió mal, `deploy.sh`
   imprime el comando para volver a la versión anterior.

### B) Si ellos prefieren actualizar solos (por privacidad)

Uno de ellos (con rol Admin en su red) instala Tailscale y `gh` en su PC (sección 0) y sigue los
mismos pasos de la opción A. Después te sacan de su red (sección 12).

---

## 11. Revisiones de rutina

| Cada cuánto | Qué | Cómo |
|---|---|---|
| Cada mes | Que los respaldos siguen llegando | `sudo rclone --config /etc/casa-ayg/rclone.conf ls gdrive:casa-ayg-respaldos \| sort -k2 \| tail -3` |
| Cada mes | Que la máquina está sana | `systemctl list-timers casa-ayg-backup.timer` y `df -h /` |
| Cada año | Prueba de restauración | Sección 8.4 |
| Cuando llegue | Correos de Oracle, Tailscale o Google | Leerlos: avisos de inactividad, de seguridad o de cambios |

Ubuntu se actualiza solo y reinicia de madrugada cuando hace falta (lo configuró `setup-server.sh`).

---

## 12. Si ya no puedes ayudarles

Todo es de ellos, así que pueden seguir sin ti:

1. **Sacarte de la red:** <https://login.tailscale.com/admin/users> → tu usuario → **Remove**.
   El servidor tiene etiqueta (sección 2.4), así que no se va contigo.
2. **Actualizar:** seguir la sección 10 B, o simplemente no actualizar. La app sigue funcionando
   con la versión que tenga.
3. **Si se pierde el servidor:** `deploy/README.md`, escenario 2, con su llave privada y su Drive.
4. **Si dejan de usar la app:** borren la instancia en Oracle (**Terminate**, marcando borrar el
   disco) y, si quieren, la carpeta `casa-ayg-respaldos` de su Drive.

---

## 13. Problemas comunes

| Pasa esto | Causa probable | Qué hacer |
|---|---|---|
| La app no abre en el teléfono | Tailscale apagado en el teléfono | Encenderlo en la app de Tailscale |
| «Esta conexión no es privada» o no carga | HTTPS no está activo en la red | Sección 2.1, paso 3, y `tailscale serve` de la sección 5 |
| Ninguna passkey funciona | Cambió el nombre del servidor | Machines: borrar la máquina vieja, dejar el nombre `casa-ayg` exacto (sección 5) |
| `ssh ubuntu@casa-ayg` no conecta | Estás en otra red de Tailscale | `tailscale switch <red-de-ellos>` |
| `tailscale up` dice que no puede usar la etiqueta | Tu usuario no es Admin, o falta `tagOwners` | Secciones 2.2 y 2.4 |
| Out of capacity al crear la máquina | Oracle sin máquinas libres | Otro Availability domain, o más tarde |
| No llegan notificaciones en iPhone | La app no está agregada a inicio, o iOS viejo | Sección 7, paso 4; iOS 16.4 o más nuevo |
| El buzón dice que no está configurado | Falta o está mal el JSON de Google | Sección 9.2; el JSON debe ser de tipo **Web application** |
| `deploy.sh` dice que la versión no quedó sana | Algo falló al arrancar | Volver con el comando que imprime y revisar `sudo docker compose --env-file /etc/casa-ayg/casa-ayg.env -f /opt/casa-ayg/deploy/compose.yaml logs app --tail 100` |
