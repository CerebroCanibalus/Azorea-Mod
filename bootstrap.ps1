# Bootstrap Gradle wrapper for Azorea (Windows / PowerShell).
# Uso: .\bootstrap.ps1
# Descarga Gradle 8.10, genera el wrapper, limpia temporales.
# Solo se ejecuta una vez; si ./gradlew ya existe, sale sin hacer nada.

$ErrorActionPreference = 'Stop'

$gradleVersion = '9.7.1'
$gradleDistUrl = "https://services.gradle.org/distributions/gradle-${gradleVersion}-bin.zip"
$tempDir = Join-Path $env:TEMP "azorea-gradle-bootstrap"
$zipPath = Join-Path $tempDir "gradle-${gradleVersion}-bin.zip"
$gradleHome = Join-Path $tempDir "gradle-${gradleVersion}"

if (Test-Path 'gradlew') {
    Write-Host "[bootstrap] gradlew ya existe. Nada que hacer." -ForegroundColor Yellow
    exit 0
}

if (-not (Test-Path $tempDir)) {
    New-Item -ItemType Directory -Path $tempDir -Force | Out-Null
}

Write-Host "[bootstrap] Descargando Gradle $gradleVersion..." -ForegroundColor Cyan
Invoke-WebRequest -Uri $gradleDistUrl -OutFile $zipPath -UseBasicParsing

Write-Host "[bootstrap] Extrayendo..." -ForegroundColor Cyan
Expand-Archive -Path $zipPath -DestinationPath $tempDir -Force

$gradleBat = Join-Path $gradleHome 'bin\gradle.bat'
if (-not (Test-Path $gradleBat)) {
    throw "No se encontró gradle.bat en $gradleHome"
}

Write-Host "[bootstrap] Generando wrapper..." -ForegroundColor Cyan
& $gradleBat wrapper --gradle-version $gradleVersion --distribution-type bin | Out-Host

Write-Host "[bootstrap] Limpiando temporales..." -ForegroundColor Cyan
Remove-Item -Recurse -Force $tempDir

Write-Host "[bootstrap] Listo. Ahora puedes usar: .\gradlew :v1_21_1:build" -ForegroundColor Green
Write-Host "[bootstrap] El primer build descargara JDK 21 desde Adoptium (Gradle toolchain)." -ForegroundColor Green
