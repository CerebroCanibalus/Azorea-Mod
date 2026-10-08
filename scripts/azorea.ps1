# azorea.ps1 - dispatcher simple para arrancar Azorea manualmente.
#
# Uso:
#   powershell -File scripts\azorea.ps1 <subcomando>
#
# Subcomandos:
#   build              Compilar mod.
#   server             Arrancar :v1_21_1:runServer (background, espera "Done").
#   client             Arrancar :v1_21_1:runClient con el mod cargado.
#   publish-mod        Copia el jar a test-server/mods/.
#   kill               Mata java/gradle residuales.
#   help               Este menu.

[CmdletBinding()]
param(
    [Parameter(Mandatory=$true, Position=0)][string]$Command,
    [Parameter(ValueFromRemainingArguments=$true)][string[]]$Rest
)

$ErrorActionPreference = 'Continue'
$ScriptRoot = Split-Path -Parent $PSCommandPath
$RepoRoot   = Split-Path -Parent $ScriptRoot
$LogsDir    = Join-Path $RepoRoot 'v1_21_1\run\logs'

function Show-Usage {
    Write-Host ''
    Write-Host 'USO: azorea <subcomando>'
    Write-Host ''
    Write-Host '  build        Compilar mod.'
    Write-Host '  server       Arrancar runServer en background.'
    Write-Host '  client       Arrancar runClient c/ el mod cargado.'
    Write-Host '  publish-mod  Copia jar a test-server/mods/.'
    Write-Host '  kill         Mata java/gradle residuales.'
    Write-Host ''
}

function Do-Build {
    Push-Location $RepoRoot
    .\gradlew.bat :v1_21_1:build --no-daemon --console=plain 2>&1 | Select-String -Pattern 'BUILD|error:' | Select-Object -First 5
    Pop-Location
}

function Do-Server {
    Write-Host '[INFO] arrancando server en background...'
    $logPath = Join-Path $RepoRoot 'build\server-gradle.log'
    $proc = Start-Process -FilePath 'cmd.exe' -ArgumentList @('/c', "gradlew.bat :v1_21_1:runServer > `"$logPath`" 2>&1") -WindowStyle Hidden -PassThru
    Write-Host "[INFO] gradle pid=$($proc.Id), logs en $logPath"
    $deadline = (Get-Date).AddSeconds(60)
    $done = $false
    while ((Get-Date) -lt $deadline) {
        if (Test-Path $logPath) {
            $content = Get-Content $logPath -Raw -ErrorAction SilentlyContinue
            if ($content -match 'Done \([\d.]+s\)') {
                $done = $true
                break
            }
        }
        Start-Sleep -Seconds 1
    }
    if ($done) {
        Write-Host '[OK] server arrancado'
        Write-Host "  - Server log: $LogsDir"
        Write-Host '  - Server escucha en :25565'
    } else {
        Write-Host "[WARN] server no alcanzo 'Done' en 60 s" -ForegroundColor Yellow
    }
}

function Do-Client {
    Write-Host '[INFO] arrancando cliente c/ Azorea mod...'
    Write-Host '  - Presiona B en el juego para abrir el menu Azorea'
    Write-Host '  - Opciones: Host Game / Browse Games'
    Write-Host '  - Cuando termines, cierra el juego (X) o envia /stop en consola'
    Write-Host ''
    Push-Location $RepoRoot
    .\gradlew.bat :v1_21_1:runClient --no-daemon --console=plain
    Pop-Location
}

function Do-PublishMod {
    $libDir = Join-Path $RepoRoot 'v1_21_1\build\libs'
    $modJar = Get-ChildItem $libDir -Filter 'v1_21_1-*.jar' -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $modJar) {
        Write-Host "[ERROR] no se encontro jar en $libDir. Ejecuta 'build' primero." -ForegroundColor Red
        return
    }
    $destDir = Join-Path $RepoRoot 'test-server\mods'
    if (-not (Test-Path $destDir)) { New-Item -ItemType Directory -Path $destDir -Force | Out-Null }
    # Limpiar version vieja.
    Get-ChildItem $destDir -Filter 'v1_21_1-*.jar' -ErrorAction SilentlyContinue | Remove-Item -Force
    Copy-Item $modJar.FullName $destDir -Force
    Write-Host "[OK] $(Split-Path $modJar.FullName -Leaf) copiado a $destDir"
}

function Do-Kill {
    Write-Host '[INFO] matando java/gradle residuales...'
    Get-Process -Name java,gradle -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 2
    Get-Process -Name java,gradle -ErrorAction SilentlyContinue | Format-Table Id
    Write-Host '[OK] limpio'
}

switch ($Command) {
    'build'       { Do-Build }
    'server'      { Do-Server }
    'client'      { Do-Client }
    'publish-mod' { Do-PublishMod }
    'kill'        { Do-Kill }
    'help'        { Show-Usage }
    default {
        Write-Host "[ERROR] subcomando desconocido: $Command" -ForegroundColor Red
        Show-Usage
        exit 1
    }
}
