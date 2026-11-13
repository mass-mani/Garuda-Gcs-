param(
    [ValidateSet('Debug', 'RelWithDebInfo', 'Release')]
    [string]$Configuration = 'RelWithDebInfo',
    [switch]$Build,
    [string]$SettingsDirectory,
    [ValidateRange(1, 128)]
    [int]$Parallel = 2
)

$ErrorActionPreference = 'Stop'
$DebugSource = Split-Path $PSScriptRoot -Parent
$DebugWorkspace = Split-Path $DebugSource -Parent
$DebugQt = Join-Path $DebugWorkspace '.build-tools/Qt/6.8.3/msvc2022_64'
$DebugGStreamer = Join-Path $DebugWorkspace '.build-tools/gstreamer/1.0/msvc_x86_64'
if (!(Test-Path (Join-Path $DebugGStreamer 'bin/gstreamer-1.0-0.dll'))) { throw 'GStreamer runtime missing. Run Setup-WindowsGStreamer.ps1 first.' }
$DebugDirectory = Join-Path $DebugWorkspace "windows-$Configuration/$Configuration"
$DebugExecutable = Join-Path $DebugDirectory 'GarudaGCS.exe'

if ($Build) {
    & (Join-Path $PSScriptRoot 'Build-Windows.ps1') -Configuration $Configuration -Parallel $Parallel
}
if (!(Test-Path -LiteralPath $DebugExecutable -PathType Leaf)) {
    throw "Application not found: $DebugExecutable. Run this script with -Build first."
}

$DebugQtSuffix = if ($Configuration -eq 'Debug') { 'd' } else { '' }
$DebugQtBin = Join-Path $DebugQt 'bin'
$DebugQtPlugins = Join-Path $DebugQt 'plugins'
$DebugQtQml = Join-Path $DebugQt 'qml'
$DebugWebEngineProcess = Join-Path $DebugQtBin "QtWebEngineProcess$DebugQtSuffix.exe"
$DebugWebEngineResources = Join-Path $DebugQt 'resources'
$DebugWebEngineLocales = Join-Path $DebugQt 'translations/qtwebengine_locales'
foreach ($DebugRequiredDirectory in @($DebugQtBin, $DebugQtPlugins, $DebugQtQml, $DebugWebEngineResources, $DebugWebEngineLocales)) {
    if (!(Test-Path -LiteralPath $DebugRequiredDirectory -PathType Container)) {
        throw "Required Qt directory is missing: $DebugRequiredDirectory"
    }
}
foreach ($DebugRequiredFile in @(
    (Join-Path $DebugQtBin "Qt6Core$DebugQtSuffix.dll"),
    (Join-Path $DebugQtBin "Qt6WebEngineCore$DebugQtSuffix.dll"),
    $DebugWebEngineProcess,
    (Join-Path $DebugWebEngineResources 'qtwebengine_resources.pak')
)) {
    if (!(Test-Path -LiteralPath $DebugRequiredFile -PathType Leaf)) {
        throw "Required Qt runtime file is missing: $DebugRequiredFile"
    }
}

$DebugDevenv = $null
$DebugVsWhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio/Installer/vswhere.exe'
if (Test-Path -LiteralPath $DebugVsWhere -PathType Leaf) {
    $DebugIdeCandidates = @(& $DebugVsWhere -latest -prerelease -products Microsoft.VisualStudio.Product.Community Microsoft.VisualStudio.Product.Professional Microsoft.VisualStudio.Product.Enterprise -property productPath)
    if ($LASTEXITCODE -eq 0) {
        $DebugDevenv = $DebugIdeCandidates | Where-Object {
            $_ -and (Test-Path -LiteralPath $_ -PathType Leaf)
        } | Select-Object -First 1
    }
}
if (!$DebugDevenv) {
    $DebugFallback = Join-Path $env:ProgramFiles 'Microsoft Visual Studio/18/Professional/Common7/IDE/devenv.exe'
    if (Test-Path -LiteralPath $DebugFallback -PathType Leaf) {
        $DebugDevenv = $DebugFallback
    }
}
if (!$DebugDevenv) {
    throw 'Visual Studio Community, Professional, or Enterprise is required to open the native debugger. Build Tools alone do not include the IDE.'
}

# Configure only the new IDE process and its children; no user or machine settings change.
$DebugStartInfo = New-Object System.Diagnostics.ProcessStartInfo
$DebugStartInfo.FileName = $DebugDevenv
$DebugStartInfo.UseShellExecute = $false
$DebugStartInfo.WindowStyle = [System.Diagnostics.ProcessWindowStyle]::Normal
$DebugStartInfo.WorkingDirectory = $DebugDirectory
# Windows filenames cannot contain quotes; quote the complete executable argument for spaces.
$DebugStartInfo.Arguments = '/debugexe "{0}" --log-output' -f $DebugExecutable
$DebugEnvironment = @{
    PATH = "$DebugQtBin;$DebugGStreamer/bin;$env:PATH"
    GST_PLUGIN_PATH = "$DebugGStreamer/lib/gstreamer-1.0"
    GST_PLUGIN_SYSTEM_PATH = "$DebugGStreamer/lib/gstreamer-1.0"
    GST_PLUGIN_SCANNER = "$DebugGStreamer/libexec/gstreamer-1.0/gst-plugin-scanner.exe"
    QT_PLUGIN_PATH = $DebugQtPlugins
    QML_IMPORT_PATH = $DebugQtQml
    QTWEBENGINEPROCESS_PATH = $DebugWebEngineProcess
    QTWEBENGINE_RESOURCES_PATH = $DebugWebEngineResources
    QTWEBENGINE_LOCALES_PATH = $DebugWebEngineLocales
    QT_FORCE_STDERR_LOGGING = '1'
}
if ($SettingsDirectory) {
    $DebugEnvironment['GARUDA_SETTINGS_DIR'] = [System.IO.Path]::GetFullPath($SettingsDirectory)
}
foreach ($DebugEntry in $DebugEnvironment.GetEnumerator()) {
    $DebugStartInfo.EnvironmentVariables[$DebugEntry.Key] = $DebugEntry.Value
}
$DebugIdeProcess = [System.Diagnostics.Process]::Start($DebugStartInfo)
if (!$DebugIdeProcess) { throw 'Visual Studio could not be started.' }
Write-Host "Visual Studio opened for $DebugExecutable (IDE process $($DebugIdeProcess.Id))."
Write-Host 'Press F5 in Visual Studio to start debugging, then open Application Settings > Video to configure all three sources.'
