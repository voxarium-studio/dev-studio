# Voxarium Studio desktop

Windows x64 client built with Kotlin, Compose Multiplatform and JetBrains Jewel.
It connects directly to the Studio API and uses the existing project files, databases
and application settings. See dev spec §2.7.1 for migration coverage.

## Run from a checkout

Set `JAVA_HOME` to a Windows x64 [JetBrains Runtime SDK 25](https://github.com/JetBrains/JetBrainsRuntime/releases).
Use Node.js 22.12+ with npm and uv. Prepare the backend and build the SPA as
described in the [root README](../README.md). From the repository root:

```powershell
.\desktop-native\gradlew.bat -p desktop-native runStudio --args='--project .scratch/demo-project'
```

The argument is optional. With no explicit path, the client restores its previous
windows and presents the recent-project picker when there is no saved session.
The client attaches to a matching backend on port 8765 or starts it through
`run.ps1 -Desktop -NoOpen`. It refuses mismatched or occupied ports. The tray
stays active when the last window closes. Quit from the tray or the status bar
to stop a backend owned by this instance.

Native launcher logs are in `%APPDATA%\voxarium_studio\kotlin-desktop\run`,
relative to `VOXARIUM_STUDIO_HOME` when that variable is set. A token-protected
backend accepts the token from `VOXARIUM_STUDIO_TOKEN`. The browser fallback uses
the browser's existing Studio authentication.

## Build the Windows package

Set `JAVA_HOME` to the JetBrains Runtime SDK as above. From the repository root:

```powershell
.\build-installer.bat --no-pause
```

The batch builds the Kotlin NSIS installer directly. It accepts the legacy
`--native` flag and pauses before closing unless `--no-pause` is supplied.

Run `Voxarium-Studio-Setup-<build>.exe` from `desktop/dist/native`. It uses the
same NSIS application identity as Electron. Setup reuses the registered directory
and installation scope, runs the old uninstaller, and installs Kotlin in its
place. Managed projects, shared Studio settings, and the saved window session
remain available after the upgrade. Close Studio from the tray before replacing
its installation. Setup checks for running processes from that installation.

To build an unpacked application, run
`.\desktop-native\gradlew.bat -p desktop-native createDistributable`. The
executable will be under `desktop-native/build/compose/binaries/main/app`.
The installer and unpacked application both include the JVM and backend runtime. The
checkout's `Voxarium Studio.vbs` opens the unpacked Kotlin app when present.

In a registered Windows installation, Settings → Studio updates downloads and
verifies the installer declared by the GitHub release's `latest.yml`. Install and
restart saves the window session, checks for active work, then starts NSIS after
Studio exits. An Electron installation can also download the Kotlin installer
when it is published on its update feed with a higher installer version.
