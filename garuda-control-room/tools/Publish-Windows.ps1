[CmdletBinding()]
param(
    [switch]$SkipTests
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$solution = Join-Path $projectRoot 'Garuda.ControlRoom.sln'
$appProject = Join-Path $projectRoot 'src\Garuda.ControlRoom\Garuda.ControlRoom.csproj'
$testProject = Join-Path $projectRoot 'tests\Garuda.ControlRoom.SmokeTests.csproj'
$publishDirectory = Join-Path $projectRoot 'release\win-x64'
$archive = Join-Path $projectRoot 'release\Garuda-Live-Viewer-win-x64.zip'

Push-Location $projectRoot
try {
    dotnet restore $solution -p:NuGetAudit=false
    if ($LASTEXITCODE -ne 0) { throw 'NuGet restore failed.' }

    dotnet build $solution -c Release --no-restore
    if ($LASTEXITCODE -ne 0) { throw 'Release build failed.' }

    if (-not $SkipTests) {
        dotnet run --no-build --project $testProject -c Release
        if ($LASTEXITCODE -ne 0) { throw 'Smoke checks failed.' }
    }

    $workspaceRoot = [System.IO.Path]::GetFullPath($projectRoot).TrimEnd('\') + '\'
    foreach ($staleArchitecture in @('win-x86', 'win-arm64')) {
        $staleNativeFolder = [System.IO.Path]::GetFullPath((Join-Path $publishDirectory "libvlc\$staleArchitecture"))
        if (-not $staleNativeFolder.StartsWith($workspaceRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing to remove native files outside the project folder: $staleNativeFolder"
        }
        if (Test-Path -LiteralPath $staleNativeFolder) {
            Remove-Item -LiteralPath $staleNativeFolder -Recurse -Force
        }
    }

    dotnet publish $appProject -c Release -r win-x64 --self-contained true -p:NuGetAudit=false -o $publishDirectory
    if ($LASTEXITCODE -ne 0) { throw 'Windows publish failed.' }

    $appExe = Join-Path $publishDirectory 'Garuda.LiveViewer.exe'
    $pluginCache = Join-Path $publishDirectory 'libvlc\win-x64\plugins\plugins.dat'
    if (-not (Test-Path -LiteralPath $pluginCache)) {
        $cacheProcess = Start-Process -FilePath $appExe -ArgumentList '--generate-vlc-cache' -PassThru
        if (-not $cacheProcess.WaitForExit(180000)) {
            Stop-Process -Id $cacheProcess.Id -Force
            throw 'VLC plugin-cache generation exceeded 180 seconds.'
        }
        if ($cacheProcess.ExitCode -ne 0) { throw "VLC plugin-cache generation failed (exit $($cacheProcess.ExitCode))." }
    }
    if (-not (Test-Path -LiteralPath $pluginCache)) { throw 'VLC plugin cache was not created.' }

    $smokeProcess = Start-Process -FilePath $appExe -ArgumentList '--vlc-smoke' -PassThru
    if (-not $smokeProcess.WaitForExit(90000)) {
        Stop-Process -Id $smokeProcess.Id -Force
        throw 'The VLC runtime smoke check exceeded 90 seconds.'
    }
    if ($smokeProcess.ExitCode -ne 0) { throw "VLC runtime smoke check failed (exit $($smokeProcess.ExitCode))." }

    $xamlSmokeProcess = Start-Process -FilePath $appExe -ArgumentList '--xaml-smoke' -PassThru
    if (-not $xamlSmokeProcess.WaitForExit(30000)) {
        Stop-Process -Id $xamlSmokeProcess.Id -Force
        throw 'The main-window XAML smoke check exceeded 30 seconds.'
    }
    if ($xamlSmokeProcess.ExitCode -ne 0) { throw "The main-window XAML smoke check failed (exit $($xamlSmokeProcess.ExitCode))." }

    Copy-Item (Join-Path $projectRoot 'THIRD-PARTY-NOTICES.txt') $publishDirectory -Force
    Copy-Item (Join-Path $projectRoot 'LICENSES\LGPL-2.1.txt') $publishDirectory -Force
    Copy-Item (Join-Path $projectRoot 'README.md') $publishDirectory -Force
    New-Item -ItemType Directory -Path (Split-Path -Parent $archive) -Force | Out-Null
    Compress-Archive -Path (Join-Path $publishDirectory '*') -DestinationPath $archive -Force
    Write-Host "Release executable: $appExe"
    Write-Host "Portable archive:   $archive"
}
finally {
    Pop-Location
}
