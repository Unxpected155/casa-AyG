# Crea un usuario de Casa A&G y muestra su CÓDIGO DE INSCRIPCIÓN (un solo uso, 24 horas).
# Corre como casaayg_owner dentro del contenedor: la app no puede crear usuarios (ADR-007).
#
#   ./scripts/New-User.ps1 -Username ana -DisplayName Ana
#   ./scripts/New-User.ps1 -Username ana -NewCode     # perdió todo: código nuevo
#
# El código es un secreto: entrégaselo a la persona en mano, no por chat ni correo.
param(
    [Parameter(Mandatory)] [ValidatePattern('^[a-z][a-z0-9_]{2,29}$')] [string] $Username,
    [ValidateLength(1, 100)] [string] $DisplayName,
    [switch] $NewCode
)
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

if ($NewCode) {
    $sql = "SELECT admin_new_enrollment_code(:'u');"
} else {
    if (-not $DisplayName) { throw "Falta -DisplayName" }
    $sql = "SELECT admin_create_user(:'u', :'d');"
}

# Los valores viajan como variables de psql (:'u'), que psql escapa como literales SQL.
$code = $sql | docker compose exec -T db psql -U casaayg_owner -d casaayg -v ON_ERROR_STOP=1 -tA `
    -v "u=$Username" -v "d=$DisplayName"
if ($LASTEXITCODE -ne 0) { throw "No se pudo crear el código." }

Write-Host ""
Write-Host "Código de inscripción para '$Username' (vence en 24 horas):" -ForegroundColor Yellow
Write-Host "    $($code.Trim())" -ForegroundColor Green
Write-Host "Entra a /login > 'Tengo un código' y registra DOS passkeys (teléfono y computadora)."
