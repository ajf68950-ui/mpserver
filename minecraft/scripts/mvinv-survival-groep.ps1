# ==========================================================================
#  Eenmalig: survivalwereld, nether en end op 1 inventaris (Multiverse-Inventories)
#  De groep 'default' noemde nog de oude werelden (meediasmp...). Daardoor hadden
#  MPServer, MPServer_nether en MPServer_the_end elk een eigen inventaris.
#  Dit script:
#   - zet de groep 'default' op mpserver, mpserver_nether en mpserver_the_end (alles gedeeld)
#   - zet de huidige spullen uit de survivalwereld (worlds\MPServer) in het groepsprofiel,
#     zodat niemand oude spullen van voor de hernoeming terugkrijgt
#   - maakt eerst een backup in Multiverse-Inventories\backup-survival-groep_<datum>
#  Draait vanuit Start-alles.bat als de servers uit staan, en maar 1 keer.
# ==========================================================================
$ErrorActionPreference = 'Stop'
$Main = Split-Path -Parent $PSScriptRoot
$Mv   = Join-Path $Main 'Velocity MPServer SMP\MPServerSMP\plugins\Multiverse-Inventories'
$Klaar = Join-Path $Mv 'survival-groep-gedaan.txt'
if (-not (Test-Path -LiteralPath $Mv)) { exit 0 }
if (Test-Path -LiteralPath $Klaar) { exit 0 }

# veiligheid: niet als de server nog draait (dan schrijft Multiverse-Inventories zelf in deze bestanden)
$props = Join-Path $Main 'Velocity MPServer SMP\MPServerSMP\server.properties'
$poort = 0
if (Test-Path -LiteralPath $props) {
    $m = Select-String -LiteralPath $props -Pattern '^server-port=(\d+)' | Select-Object -First 1
    if ($m) { $poort = [int]$m.Matches[0].Groups[1].Value }
}
if ($poort -gt 0 -and (Get-Command Get-NetTCPConnection -ErrorAction SilentlyContinue)) {
    if (Get-NetTCPConnection -LocalPort $poort -State Listen -ErrorAction SilentlyContinue) {
        Write-Host 'Multiverse-Inventories: de server draait nog, de inventaris-groep wordt de volgende keer gezet.' -ForegroundColor Yellow
        exit 0
    }
}

Write-Host 'Survivalwereld, nether en end op 1 inventaris zetten...' -ForegroundColor Cyan
$stempel = Get-Date -Format 'yyyy-MM-dd_HH-mm'
$backup = Join-Path $Mv "backup-survival-groep_$stempel"
New-Item -ItemType Directory -Path $backup -Force | Out-Null
$groepen = Join-Path $Mv 'groups.yml'
if (Test-Path -LiteralPath $groepen) { Copy-Item -LiteralPath $groepen -Destination $backup -Force }
$groepMap = Join-Path $Mv 'groups\default'
if (Test-Path -LiteralPath $groepMap) { Copy-Item -LiteralPath $groepMap -Destination (Join-Path $backup 'groups-default') -Recurse -Force }
New-Item -ItemType Directory -Path $groepMap -Force | Out-Null

# 1. groepsprofielen = de huidige profielen uit de survivalwereld (dan nether, dan end voor wie daar alleen was)
$gezet = @{}
foreach ($w in @('MPServer', 'MPServer_nether', 'MPServer_the_end')) {
    $map = Join-Path $Mv "worlds\$w"
    if (-not (Test-Path -LiteralPath $map)) { continue }
    foreach ($f in @(Get-ChildItem -LiteralPath $map -Filter '*.json' -File)) {
        $naam = $f.Name.ToLowerInvariant()
        if ($gezet.ContainsKey($naam)) {
            if ($w -ne 'MPServer') { Write-Host "  let op: $($f.BaseName) had ook spullen in $w; die staan nog in worlds\$w (backup)." -ForegroundColor Yellow }
            continue
        }
        Copy-Item -LiteralPath $f.FullName -Destination (Join-Path $groepMap $f.Name) -Force
        $gezet[$naam] = $w
        Write-Host "  $($f.BaseName): spullen uit $w gebruikt"
    }
}

# 2. de groep zelf
$yml = @"
# Multiverse-Inventories Groups
# Survivalwereld, nether en end delen alles (inventaris, enderkist, levens, xp...).
# Aangepast door Resources\mvinv-survival-groep.ps1 op $stempel (backup: $(Split-Path -Leaf $backup)).
groups:
  default:
    worlds:
    - mpserver
    - mpserver_nether
    - mpserver_the_end
    shares:
    - all
"@
[IO.File]::WriteAllText($groepen, (($yml -replace "`r?`n", "`r`n") + "`r`n"), (New-Object Text.UTF8Encoding $false))
[IO.File]::WriteAllText($Klaar, "Gedaan op $stempel. Backup: $(Split-Path -Leaf $backup)`r`n", (New-Object Text.UTF8Encoding $false))
Write-Host "  Klaar ($($gezet.Count) spelers). Backup staat in $(Split-Path -Leaf $backup)." -ForegroundColor Green
exit 0
