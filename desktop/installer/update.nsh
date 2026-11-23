# Manual upgrades use the registered installation, including its scope and path (§2.7).
# Keep electron-builder's uninstall/install transaction and profile preservation (§12.1).
!include LogicLib.nsh
!include nsDialogs.nsh

!ifndef BUILD_UNINSTALLER
  Var studioInstallDir
  Var studioInstalledVersion
  Var studioExistingInstall
!endif

!macro customHeader
  !include "${BUILD_RESOURCES_DIR}\installer-messages.nsh"
  BrandingText "${PRODUCT_NAME} $(studio_buildVersion)"
!macroend

!macro customInstall
  # Keep the actual build label for Windows and the next upgrade page (§2.7).
  # Electron's VERSION remains numeric for packaging and the updater.
  WriteRegStr SHELL_CONTEXT "${UNINSTALL_REGISTRY_KEY}" "DisplayVersion" "$(studio_buildVersion)"
  WriteRegStr SHELL_CONTEXT "${UNINSTALL_REGISTRY_KEY}" "DisplayName" "${PRODUCT_NAME} $(studio_buildVersion)"
!macroend

!macro customInit
  Call StudioDetectInstallation
!macroend

!macro customWelcomePage
  # Supply our directory page below: the stock instFilesPre appends the product name
  # even to an existing installation's path, which can silently move an upgrade.
  !undef allowToChangeInstallationDirectory
  Page custom StudioUpdatePage

  Function StudioDetectInstallation
    StrCpy $studioExistingInstall "0"
    ReadRegStr $studioInstallDir SHELL_CONTEXT "${INSTALL_REGISTRY_KEY}" InstallLocation
    ${If} $studioInstallDir != ""
    ${AndIf} ${FileExists} "$studioInstallDir\*.*"
      StrCpy $studioExistingInstall "1"
      StrCpy $INSTDIR $studioInstallDir
      ReadRegStr $studioInstalledVersion SHELL_CONTEXT "${UNINSTALL_REGISTRY_KEY}" DisplayVersion
      ${If} $studioInstalledVersion == ""
        StrCpy $studioInstalledVersion "$(studio_unknownVersion)"
      ${EndIf}
    ${EndIf}
  FunctionEnd

  Function StudioUpdatePage
    ${If} $studioExistingInstall != "1"
    ${OrIf} ${isUpdated}
    ${OrIf} ${UAC_IsInnerInstance}
      Abort
    ${EndIf}
    !insertmacro MUI_HEADER_TEXT "$(studio_updateTitle)" "$(studio_updateSubtitle)"
    nsDialogs::Create 1018
    Pop $0
    ${If} $0 == error
      Abort
    ${EndIf}
    ${NSD_CreateLabel} 0 0 100% 30u "$(studio_updateVersions)"
    Pop $0
    ${NSD_CreateLabel} 0 36u 100% 30u "$(studio_updateDirectory)"
    Pop $0
    ${NSD_CreateLabel} 0 76u 100% 56u "$(studio_updateData)"
    Pop $0
    GetDlgItem $0 $HWNDPARENT 1
    SendMessage $0 ${WM_SETTEXT} 0 "STR:$(studio_updateButton)"
    nsDialogs::Show
  FunctionEnd
!macroend

!macro customInstallMode
  !ifndef BUILD_UNINSTALLER
    ${If} $studioExistingInstall == "1"
      ${If} $installMode == "all"
        StrCpy $isForceMachineInstall "1"
      ${Else}
        StrCpy $isForceCurrentInstall "1"
      ${EndIf}
    ${EndIf}
  !endif
!macroend

!macro customPageAfterChangeDir
  !include StrContains.nsh
  !define MUI_PAGE_CUSTOMFUNCTION_PRE StudioDirectoryPre
  !insertmacro MUI_PAGE_DIRECTORY
  !define MUI_PAGE_CUSTOMFUNCTION_PRE StudioInstallPre

  Function StudioDirectoryPre
    Call StudioDetectInstallation
    ${If} $studioExistingInstall == "1"
    ${OrIf} ${isUpdated}
      Abort
    ${EndIf}
  FunctionEnd

  Function StudioInstallPre
    ${If} $studioExistingInstall == "1"
      StrCpy $INSTDIR $studioInstallDir
    ${Else}
      # Preserve the stock fresh-install directory convention.
      ${StrContains} $0 "${APP_FILENAME}" $INSTDIR
      ${If} $0 == ""
        StrCpy $INSTDIR "$INSTDIR\${APP_FILENAME}"
      ${EndIf}
    ${EndIf}
  FunctionEnd
!macroend

!macro customCheckAppRunning
  # Explicitly offer termination of this installation's processes (dev spec §2.7).
  # Silent installs still fail safely; updater shutdown gets its usual grace period.
  InitPluginsDir
  File /oname=$PLUGINSDIR\studio-check-installation.ps1 "${PROJECT_DIR}\scripts\check-installation.ps1"
  System::Call 'kernel32::GetCurrentProcessId() i.r2'
  ${Do}
    StrCpy $1 "0"
    ${If} ${isUpdated}
      StrCpy $1 "15"
    ${EndIf}
    nsExec::ExecToStack '"$PowerShellPath" -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "$PLUGINSDIR\studio-check-installation.ps1" -Directory "$INSTDIR" -WaitSeconds $1 -InstallerProcessId $2'
    Pop $0
    Pop $1
    ${If} $0 == 0
      ${Break}
    ${EndIf}
    ${If} $0 == 1
      MessageBox MB_YESNOCANCEL|MB_ICONEXCLAMATION|MB_DEFBUTTON3 "$(studio_appBusy)" /SD IDCANCEL IDYES studioTerminate IDNO studioRetry
    ${Else}
      MessageBox MB_RETRYCANCEL|MB_ICONEXCLAMATION "$(studio_checkFailed)" /SD IDCANCEL IDRETRY studioRetry
    ${EndIf}
    SetErrorLevel 2
    Quit
    studioTerminate:
      nsExec::ExecToStack '"$PowerShellPath" -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "$PLUGINSDIR\studio-check-installation.ps1" -Directory "$INSTDIR" -WaitSeconds 10 -InstallerProcessId $2 -Terminate'
      Pop $0
      Pop $1
      ${If} $0 == 0
        ${Break}
      ${EndIf}
      MessageBox MB_RETRYCANCEL|MB_ICONEXCLAMATION "$(studio_closeFailed)" /SD IDCANCEL IDRETRY studioRetry
      SetErrorLevel 2
      Quit
    studioRetry:
  ${Loop}
!macroend
