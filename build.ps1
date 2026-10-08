# build.ps1 — compila :v1_21_1 y copia el jar a test-server/mods/.
# Uso (PowerShell): .\build.ps1
# Sin tocar mundos ni configs del test-server.

$ErrorActionPreference = 'Stop'

$ROOT = $PSScriptRoot
Set-Location $ROOT

Write-Host "[build] Compilando :v1_21_1..." -ForegroundColor Cyan
& .\gradlew.bat :v1_21_1:build --no-daemon | Out-Host

# Localizar jar generado (excluyendo -sources y -javadoc).
$libDir = Join-Path $ROOT 'v1_21_1\build\libs'
$jar = Get-ChildItem -Path $libDir -Filter 'v1_21_1-*.jar' -ErrorAction SilentlyContinue |
       Where-Object { $_.Name -notmatch '-(sources|javadoc)\.jar$' } |
       Select-Object -First 1
if (-not $jar) {
    Write-Host "[build] ERROR: no se encontró jar en $libDir" -ForegroundColor Red
    exit 1
}

$modsDir = Join-Path $ROOT 'test-server\mods'
New-Item -ItemType Directory -Path $modsDir -Force | Out-Null

# Eliminar SOLO jars viejos del mod (no mundos, configs, eula.txt, etc.).
# § Seguridad: respetar test-server/ (AGENTS.md § Reglas operativas).
Write-Host "[build] Limpiando jars viejos en test-server/mods/..." -ForegroundColor Cyan
Get-ChildItem -Path $modsDir -Filter 'v1_21_1-*.jar' -ErrorAction SilentlyContinue | Remove-Item -Force
Get-ChildItem -Path $modsDir -Filter 'azorea-*.jar' -ErrorAction SilentlyContinue | Remove-Item -Force

Copy-Item -Path $jar.FullName -Destination $modsDir
Write-Host "[build] OK. Jar listo en: test-server\mods\$($jar.Name)" -ForegroundColor Green
