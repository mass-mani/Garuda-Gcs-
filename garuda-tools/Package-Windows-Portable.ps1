param(
    [string]$PortableDirectory = (Join-Path (Split-Path $PSScriptRoot -Parent) 'artifacts/Garuda-GCS-Windows-x64-Portable'),
    [string]$OutputPath = (Join-Path (Split-Path $PSScriptRoot -Parent) 'artifacts/Garuda-GCS-Windows-x64-5.0.8-garuda.2-Setup.exe'),
    [string]$Version = '5.0.8-garuda.2'
)

$ErrorActionPreference = 'Stop'
$portable = (Resolve-Path -LiteralPath $PortableDirectory).Path.TrimEnd('\')
$output = [System.IO.Path]::GetFullPath($OutputPath)
$makensis = Join-Path ${env:ProgramFiles(x86)} 'NSIS/makensis.exe'
if (!(Test-Path -LiteralPath $makensis)) { throw 'NSIS is required. Install NSIS and rerun this packaging script.' }
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $output) | Out-Null

$template = @'
Unicode true
Name "Garuda GCS @@VERSION@@"
OutFile "@@OUTPUT@@"
InstallDir "$PROGRAMFILES64\Garuda GCS"
RequestExecutionLevel admin
ShowInstDetails show
Page directory
Page instfiles
UninstPage uninstConfirm
UninstPage instfiles

Section "Install"
    SetShellVarContext current
    Delete "$SMPROGRAMS\Garuda GCS\Garuda GCS.lnk"
    RMDir "$SMPROGRAMS\Garuda GCS"
    Delete "$DESKTOP\Garuda GCS.lnk"
    SetShellVarContext all
    SetOutPath "$INSTDIR"
    File /r "@@PORTABLE@@\*"
    WriteUninstaller "$INSTDIR\Uninstall.exe"
    CreateDirectory "$SMPROGRAMS\Garuda GCS"
    SetOutPath "$INSTDIR\bin"
    CreateShortCut "$SMPROGRAMS\Garuda GCS\Garuda GCS.lnk" "$INSTDIR\bin\GarudaGCS.exe" "" "$INSTDIR\bin\GarudaGCS.exe" 0
    CreateShortCut "$DESKTOP\Garuda GCS.lnk" "$INSTDIR\bin\GarudaGCS.exe" "" "$INSTDIR\bin\GarudaGCS.exe" 0
    WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\GarudaGCS" "DisplayName" "Garuda GCS"
    WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\GarudaGCS" "DisplayVersion" "@@VERSION@@"
    WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\GarudaGCS" "Publisher" "Garuda Aerospace"
    WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\GarudaGCS" "DisplayIcon" "$INSTDIR\bin\GarudaGCS.exe"
    WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\GarudaGCS" "InstallLocation" "$INSTDIR"
    WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\GarudaGCS" "UninstallString" '"$INSTDIR\Uninstall.exe"'
    WriteRegDWORD HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\GarudaGCS" "NoModify" 1
    WriteRegDWORD HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\GarudaGCS" "NoRepair" 1
    IfFileExists "$INSTDIR\Microsoft-VCRedist-x64.exe" redist_found redist_done
redist_found:
    ExecWait '"$INSTDIR\Microsoft-VCRedist-x64.exe" /install /quiet /norestart' $0
    IntCmp $0 0 redist_done
    IntCmp $0 1638 redist_done
    IntCmp $0 3010 redist_done
    MessageBox MB_ICONSTOP "The Microsoft Visual C++ runtime could not be installed (code $0)."
    Abort
redist_done:
SectionEnd

Section "Uninstall"
    SetShellVarContext all
    Delete "$DESKTOP\Garuda GCS.lnk"
    Delete "$SMPROGRAMS\Garuda GCS\Garuda GCS.lnk"
    RMDir "$SMPROGRAMS\Garuda GCS"
    DeleteRegKey HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\GarudaGCS"
    Delete "$INSTDIR\Uninstall.exe"
    RMDir /r "$INSTDIR"
SectionEnd
'@
$script = $template.Replace('@@VERSION@@', $Version).Replace('@@OUTPUT@@', $output).Replace('@@PORTABLE@@', $portable)
$nsi = Join-Path $env:TEMP 'GarudaGCS-Portable-Installer.nsi'
Set-Content -LiteralPath $nsi -Value $script -Encoding Ascii
& $makensis $nsi
if ($LASTEXITCODE -ne 0) { throw "NSIS packaging failed with exit code $LASTEXITCODE." }
if (!(Test-Path -LiteralPath $output)) { throw 'NSIS exited successfully but did not create the installer.' }
Write-Host "Installer created: $output"
