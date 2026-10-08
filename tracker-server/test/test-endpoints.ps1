# Test E2E del TrackerServer: POST /announce, GET /list, DELETE /announce.
$ErrorActionPreference = 'Stop'
$baseUrl = 'http://localhost:9090'

# Generar IDs válidos.
$gameId = [guid]::NewGuid().ToString()
$bytes = New-Object byte[] 32
(New-Object Random).NextBytes($bytes)
$hostToken = -join ($bytes | ForEach-Object { '{0:x2}' -f $_ })
$inviteCode = 'AZ-ABC123-DEF456-GHI789-JKL012'

# Announcement válido.
$announcement = @{
    azoreaProtocol     = 1
    azoreaVersion      = '0.1.0'
    gameId             = $gameId
    hostToken          = $hostToken
    hostDisplayName    = 'test-host'
    mcVersion          = '1.21.1'
    neoForgeVersion    = '21.1.250'
    mods               = @(@{ modid = 'azorea'; version = '0.1.0'; required = $true })
    connection         = @{
        type       = 'direct'
        address    = '127.0.0.1:25565'
        relayUrl   = $null
        inviteCode = $inviteCode
    }
    maxPlayers         = 8
    currentPlayers     = 1
    worldName          = 'Test world'
    timestamp          = [int][double]::Parse((Get-Date -UFormat %s))
    ttlSeconds         = 300
} | ConvertTo-Json -Depth 5

Write-Host "Game ID: $gameId"
Write-Host "Host token (first 16 chars): $($hostToken.Substring(0,16))..."

Write-Host "`n--- POST /announce ---"
$r = Invoke-WebRequest -Uri "$baseUrl/announce" -Method POST -ContentType 'application/json' -Body $announcement -UseBasicParsing
Write-Host "STATUS: $($r.StatusCode)"
Write-Host $r.Content

Write-Host "`n--- GET /list ---"
$r = Invoke-WebRequest -Uri "$baseUrl/list" -UseBasicParsing
Write-Host "STATUS: $($r.StatusCode)"
Write-Host $r.Content

Write-Host "`n--- DELETE /announce ---"
$r = Invoke-WebRequest -Uri "$baseUrl/announce?game_id=$gameId&token=$hostToken" -Method DELETE -UseBasicParsing
Write-Host "STATUS: $($r.StatusCode)"

Write-Host "`n--- GET /list (post-delete) ---"
$r = Invoke-WebRequest -Uri "$baseUrl/list" -UseBasicParsing
Write-Host "STATUS: $($r.StatusCode)"
Write-Host $r.Content
