param([switch]$InstallDependencies)
$ErrorActionPreference = 'Stop'
$Source = Split-Path $PSScriptRoot -Parent
$Viewer = Join-Path $Source 'third_party/UAVLogViewer'
if (Test-Path 'C:/Program Files/nodejs') { $env:Path = "C:/Program Files/nodejs;$env:Path" }
Push-Location $Viewer
try {
    if ($InstallDependencies -or !(Test-Path 'node_modules/webpack/bin/webpack.js')) {
        # Old upstream vue-jest/deasync has no Node 24 native addon. That addon
        # belongs to its legacy test runner, not the viewer/browser build.
        npm.cmd ci --ignore-scripts --no-audit --no-fund
        if ($LASTEXITCODE) { throw 'Viewer dependency installation failed.' }
        node.exe node_modules/patch-package/index.js
        if ($LASTEXITCODE) { throw 'Viewer dependency patches failed.' }
    }
    npm.cmd run debug:garuda
    if ($LASTEXITCODE) { throw 'Viewer debug bundle failed.' }
} finally { Pop-Location }
