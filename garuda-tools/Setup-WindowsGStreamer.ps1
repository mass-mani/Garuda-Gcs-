param()
$ErrorActionPreference = 'Stop'
$Workspace = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$Tools = Join-Path $Workspace '.build-tools'
$Downloads = Join-Path $Tools 'gstreamer-downloads'
New-Item -ItemType Directory -Force $Downloads | Out-Null
$Packages = @{
    'gstreamer-1.0-msvc-x86_64-1.22.12.msi' = 'e5cbc6fb9f40fc2850806163df4b9d92012f967c842dc000a2b254cbcd7901d6'
    'gstreamer-1.0-devel-msvc-x86_64-1.22.12.msi' = '82d018533750b250f177fd94b1de05db02c9215dc8743004ff6e1947ec1fa923'
}
foreach ($Name in $Packages.Keys) {
    $Archive = Join-Path $Downloads "verified-$Name"
    if (!(Test-Path $Archive)) {
        & curl.exe -fL --retry 3 --max-time 900 "https://origin.gstreamer.freedesktop.org/data/pkg/windows/1.22.12/msvc/$Name" -o $Archive
        if ($LASTEXITCODE) { throw "Download failed: $Name" }
    }
    if ((Get-FileHash $Archive -Algorithm SHA256).Hash -ne $Packages[$Name]) { throw "Checksum mismatch: $Archive" }
    # Administrative extraction puts the SDK in the workspace, without installing it system-wide.
    $Log = Join-Path $Downloads "$Name.extract.log"
    $Arguments = '/a "{0}" /qn TARGETDIR="{1}" /L*v "{2}"' -f $Archive, $Tools, $Log
    $Extraction = Start-Process msiexec.exe -ArgumentList $Arguments -WindowStyle Hidden -Wait -PassThru
    if ($Extraction.ExitCode -ne 0) { throw "GStreamer extraction failed ($($Extraction.ExitCode)); see $Log" }
}
Write-Host "GStreamer SDK ready under $Tools/gstreamer/1.0/msvc_x86_64"

