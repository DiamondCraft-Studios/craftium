param([string]$Version = '0.3.1-dev')

$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
if ($Version -notmatch '^[A-Za-z0-9_.-]+$') { throw 'Use a plain release version' }
$distributionDirectory = Join-Path $projectDirectory 'dist'
$releaseDirectory = Join-Path $distributionDirectory "Craftium-$Version"
$pluginJar = Join-Path $projectDirectory "versions\26.2\server-plugin\build\libs\craftium-server-$Version.jar"
$modJar = Join-Path $projectDirectory "versions\26.2\build\libs\craftium-$Version.jar"
if (-not (Test-Path -LiteralPath $modJar)) {
    throw 'Build Craftium first with .\gradlew.bat build'
}

if (-not (Test-Path -LiteralPath $pluginJar)) { throw 'Build the Craftium server plugin first' }
New-Item -ItemType Directory -Force -Path $releaseDirectory | Out-Null
Copy-Item -LiteralPath $pluginJar -Destination $releaseDirectory -Force
Copy-Item -LiteralPath $pluginJar -Destination $distributionDirectory -Force
Copy-Item -LiteralPath $modJar -Destination $releaseDirectory -Force
Copy-Item -LiteralPath $modJar -Destination $distributionDirectory -Force
foreach ($name in @('README.md', 'LICENSE', 'THIRD-PARTY-NOTICES.md')) {
    Copy-Item -LiteralPath (Join-Path $projectDirectory $name) -Destination $releaseDirectory -Force
}
$releaseDocs = Join-Path $releaseDirectory 'docs'
New-Item -ItemType Directory -Force -Path $releaseDocs | Out-Null
foreach ($name in @('FEATURES.md', 'PARITY-AUDIT.md', 'AXIOM-RESEARCH.md', 'VALIDATION.md', 'SERVER-PLUGIN.md', 'KEYBINDS.md')) {
    $documentPath = Join-Path $projectDirectory "docs\$name"
    if (Test-Path -LiteralPath $documentPath) {
        Copy-Item -LiteralPath $documentPath -Destination $releaseDocs -Force
    }
}

$previews = Join-Path $releaseDirectory 'previews'
$releaseBranding = Join-Path $releaseDirectory 'branding'
New-Item -ItemType Directory -Force -Path $releaseBranding | Out-Null
foreach ($name in @('craftium.svg', 'craftium.png', 'README.md')) {
    Copy-Item -LiteralPath (Join-Path $projectDirectory "branding\$name") -Destination $releaseBranding -Force
}
New-Item -ItemType Directory -Force -Path $previews | Out-Null
# Replace numbered captures from previous runs of this generated release bundle.
foreach ($image in Get-ChildItem -LiteralPath $previews -File) {
    if ($image.Name -match '^\d{4}_craftium-.*\.png$') {
        Remove-Item -LiteralPath $image.FullName
    }
}
$screenshotDirectory = Join-Path $projectDirectory 'build\run\clientGameTest\screenshots'
if (Test-Path -LiteralPath $screenshotDirectory) {
    foreach ($image in Get-ChildItem -LiteralPath $screenshotDirectory -Filter '*craftium*.png' -File) {
        Copy-Item -LiteralPath $image.FullName -Destination $previews -Force
    }
}

$checksum = (Get-FileHash -LiteralPath (Join-Path $releaseDirectory "craftium-$Version.jar") -Algorithm SHA256).Hash.ToLowerInvariant()
$pluginChecksum = (Get-FileHash -LiteralPath $pluginJar -Algorithm SHA256).Hash.ToLowerInvariant()
Set-Content -LiteralPath (Join-Path $releaseDirectory 'SHA256SUMS.txt') -Value @("$checksum  craftium-$Version.jar", "$pluginChecksum  craftium-server-$Version.jar") -Encoding ascii
$archiveItems = @(
    (Join-Path $releaseDirectory "craftium-server-$Version.jar"),
    (Join-Path $releaseDirectory "craftium-$Version.jar"),
    (Join-Path $releaseDirectory 'README.md'),
    (Join-Path $releaseDirectory 'LICENSE'),
    (Join-Path $releaseDirectory 'THIRD-PARTY-NOTICES.md'),
    (Join-Path $releaseDirectory 'SHA256SUMS.txt'),
    $releaseDocs,
    $releaseBranding,
    $previews
)
Compress-Archive -LiteralPath $archiveItems -DestinationPath (Join-Path $distributionDirectory "Craftium-$Version-Minecraft-26.2.zip") -Force
Write-Output "Packaged Craftium $Version in $releaseDirectory"
