# ==========================================================================
#  Eigen plugins bijwerken
#  De jars van de eigen plugins (Vaulty, Muntly, Ordely, Pocketly, Toolwise,
#  Statusbord) staan in Resources\eigen-plugins. Dit script zet de nieuwste
#  versie in MPServerSMP\plugins, met de juiste naam (Naam-versie.jar).
#  - Nieuwere versie in Resources  -> komt in plugins (oude jar gaat weg)
#  - Nieuwere versie in plugins (bijv. via Update-plugins.bat) -> wordt ook in Resources gezet
#  Draait vanuit Start-alles.bat als de servers uit staan (een geladen jar is op Windows vergrendeld).
# ==========================================================================
$ErrorActionPreference = 'Stop'
$Main    = Split-Path -Parent $PSScriptRoot
$Bron    = Join-Path $PSScriptRoot 'eigen-plugins'
$Plugins = Join-Path (Join-Path (Join-Path $Main 'Velocity MPServer SMP') 'MPServerSMP') 'plugins'
$Update  = Join-Path $Plugins 'update'
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Get-PluginInfo([string]$path) {
    try {
        $zip = [IO.Compression.ZipFile]::OpenRead($path)
        try {
            $e = $zip.GetEntry('plugin.yml')
            if (-not $e) { $e = $zip.GetEntry('paper-plugin.yml') }
            if (-not $e) { return $null }
            $r = New-Object IO.StreamReader($e.Open())
            try { $t = $r.ReadToEnd() } finally { $r.Close() }
        } finally { $zip.Dispose() }
        $naam = $null; $versie = '0'
        if ($t -match '(?m)^name:\s*[''"]?([^''"\r\n]+?)[''"]?\s*$') { $naam = $Matches[1].Trim() }
        if ($t -match '(?m)^version:\s*[''"]?([^''"\r\n]+?)[''"]?\s*$') { $versie = $Matches[1].Trim() }
        if (-not $naam) { return $null }
        return [pscustomobject]@{ Naam = $naam; Versie = $versie; Pad = $path }
    } catch { return $null }
}

# -1 = a ouder dan b, 0 = gelijk, 1 = a nieuwer
function Compare-Versie([string]$a, [string]$b) {
    $pa = @($a -split '[.\-+]'); $pb = @($b -split '[.\-+]')
    for ($i = 0; $i -lt [Math]::Max($pa.Count, $pb.Count); $i++) {
        $x = if ($i -lt $pa.Count) { $pa[$i] } else { '0' }
        $y = if ($i -lt $pb.Count) { $pb[$i] } else { '0' }
        $nx = 0; $ny = 0
        if ([int]::TryParse($x, [ref]$nx) -and [int]::TryParse($y, [ref]$ny)) {
            if ($nx -lt $ny) { return -1 }; if ($nx -gt $ny) { return 1 }
        } else {
            $c = [string]::Compare($x, $y, $true); if ($c -ne 0) { return [Math]::Sign($c) }
        }
    }
    return 0
}

function Get-Hash([string]$p) { return (Get-FileHash -LiteralPath $p -Algorithm SHA1).Hash }

if (-not (Test-Path -LiteralPath $Bron)) { Write-Host 'Geen map Resources\eigen-plugins gevonden.'; exit 0 }
if (-not (Test-Path -LiteralPath $Plugins)) { Write-Host "Pluginmap niet gevonden: $Plugins"; exit 0 }

Write-Host 'Eigen plugins controleren...' -ForegroundColor Cyan
$geinstalleerd = @(Get-ChildItem -LiteralPath $Plugins -Filter '*.jar' -File | ForEach-Object { Get-PluginInfo $_.FullName } | Where-Object { $_ })
$alleBron = @(Get-ChildItem -LiteralPath $Bron -Filter '*.jar' -File | ForEach-Object { Get-PluginInfo $_.FullName } | Where-Object { $_ })
# per plugin alleen de nieuwste jar uit Resources gebruiken; oudere versies daar weghalen (staan ook op GitHub)
$bronJars = @()
foreach ($groep in @($alleBron | Group-Object Naam)) {
    $nieuwste = $groep.Group[0]
    foreach ($g in $groep.Group) { if ((Compare-Versie $g.Versie $nieuwste.Versie) -gt 0) { $nieuwste = $g } }
    foreach ($g in $groep.Group) {
        if ($g.Pad -ne $nieuwste.Pad) {
            Remove-Item -LiteralPath $g.Pad -Force -ErrorAction SilentlyContinue
            Write-Host "  $(Split-Path -Leaf $g.Pad): oudere versie uit Resources\eigen-plugins gehaald (nieuwste is $($nieuwste.Versie))." -ForegroundColor DarkGray
        }
    }
    $bronJars += $nieuwste
}
$veranderd = 0

foreach ($b in $bronJars) {
    $goedeNaam = "$($b.Naam)-$($b.Versie).jar"
    $op = @($geinstalleerd | Where-Object { $_.Naam -eq $b.Naam -and (Test-Path -LiteralPath $_.Pad) })
    try {
        if ($op.Count -eq 0) {
            Copy-Item -LiteralPath $b.Pad -Destination (Join-Path $Plugins $goedeNaam) -Force
            Write-Host "  $($b.Naam) $($b.Versie): nieuw in plugins gezet." -ForegroundColor Green
            $veranderd++
            continue
        }
        # de nieuwste geinstalleerde versie
        $hoogste = $op[0]
        foreach ($o in $op) { if ((Compare-Versie $o.Versie $hoogste.Versie) -gt 0) { $hoogste = $o } }
        $cmp = Compare-Versie $b.Versie $hoogste.Versie
        if ($cmp -lt 0) {
            # in plugins staat iets nieuwers (bijv. via Update-plugins.bat): ook in Resources zetten
            $nieuw = Join-Path $Bron "$($hoogste.Naam)-$($hoogste.Versie).jar"
            Copy-Item -LiteralPath $hoogste.Pad -Destination $nieuw -Force
            if ($b.Pad -ne $nieuw) { Remove-Item -LiteralPath $b.Pad -Force }
            Write-Host "  $($b.Naam): plugins heeft al $($hoogste.Versie), die staat nu ook in Resources." -ForegroundColor Yellow
            $b = Get-PluginInfo $nieuw; $goedeNaam = "$($b.Naam)-$($b.Versie).jar"
        }
        $doelPad = Join-Path $Plugins $goedeNaam
        $alGoed = ($op.Count -eq 1) -and ($op[0].Pad -eq $doelPad) -and ((Get-Hash $op[0].Pad) -eq (Get-Hash $b.Pad))
        if ($alGoed) { Write-Host "  $($b.Naam) $($b.Versie): is al bijgewerkt."; continue }
        $oud = ($op | ForEach-Object { Split-Path -Leaf $_.Pad }) -join ', '
        foreach ($o in $op) { Remove-Item -LiteralPath $o.Pad -Force -ErrorAction SilentlyContinue }
        Copy-Item -LiteralPath $b.Pad -Destination $doelPad -Force
        Write-Host "  $($b.Naam): $oud -> $goedeNaam" -ForegroundColor Green
        $veranderd++
    } catch {
        Write-Host "  $($b.Naam): bijwerken mislukt: $($_.Exception.Message)" -ForegroundColor Red
    }
}

# oude kopieen in plugins\update van deze plugins weghalen (die zouden een nieuwere versie weer overschrijven)
if (Test-Path -LiteralPath $Update) {
    $namen = @($bronJars | ForEach-Object { $_.Naam })
    foreach ($u in @(Get-ChildItem -LiteralPath $Update -Filter '*.jar' -File -ErrorAction SilentlyContinue)) {
        $i = Get-PluginInfo $u.FullName
        if ($i -and $namen -contains $i.Naam) {
            Remove-Item -LiteralPath $u.FullName -Force
            Write-Host "  plugins\update\$($u.Name) weggehaald (staat nu in Resources\eigen-plugins)." -ForegroundColor DarkYellow
        }
    }
}
if ($veranderd -eq 0) { Write-Host '  Alles is al up-to-date.' }

# eenmalige stappen die moeten gebeuren terwijl de servers uit staan
$eenmalig = Join-Path $PSScriptRoot 'mvinv-survival-groep.ps1'
if (Test-Path -LiteralPath $eenmalig) {
    try { & $eenmalig } catch { Write-Host "  mvinv-survival-groep.ps1 mislukt: $($_.Exception.Message)" -ForegroundColor Red }
}
exit 0
