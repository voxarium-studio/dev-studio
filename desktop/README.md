# Voxarium Studio — Windows desktop

Electron wraps the existing Vue application and the same local FastAPI server.
See dev spec §2.7 for lifecycle, storage and distribution decisions.

## Build an installer

The approved logo is `assets/logo.png`. After replacing it, run `npm run icons` to
regenerate the tray icon, multi-size Windows ICO and web assets in `frontend/public`.
The same artwork appears in the title bar, project switcher, splash, browser tab,
Windows executable, shortcuts and installer (dev spec §2.7).

On Windows x64, install Node.js 22.12+ (x64, with npm) and uv on the build machine.

Run [`build-installer.bat`](../build-installer.bat) from the repository root to
install dependencies, run desktop tests and build the installer without publishing.
It pauses before closing; use `--no-pause` for automation. To run the steps manually:

```powershell
cd desktop
npm ci
npm test
npm run dist -- --publish never
```

`node test/installer-smoke.cjs` additionally builds two tiny Windows installers with
an isolated app ID and checks upgrade from the stock installer, a busy bundled
process, preserved paths/data, and updater reinstall. It uses `.scratch/installer-smoke`
and removes its test installation and profile afterwards; no Studio server is started.
Add `--interactive` to exercise the native process dialog: choose No and then Cancel
on the first upgrade attempt; on the second, choose Yes and finish the wizard.
The test checks that cancelling preserves the worker and old files, while confirmation
terminates the worker and completes the upgrade.

The NSIS installer is written to `desktop/dist/`. It installs for the current user,
offers an installation directory and a desktop shortcut. Python, Node.js, npm,
the backend, built-in plugins and frontend are bundled. First launch requires no
package installation. Agent credentials and external tools such as Git or game
engines are still supplied by the user. Plugins may download their own dependencies.
The installer is unsigned until a publisher certificate is configured.

Run a newer installer to update an existing installation. It shows the installed
and incoming versions and reuses the registered folder and installation scope.
Running the same version reinstalls the application. Projects and the Chromium
profile/settings are preserved. If Studio is running (including in the tray), setup
offers to end its processes and continue, check again after a manual exit, or cancel.
Forced termination requires confirmation and warns that running tasks are interrupted
and unsaved changes may be lost. Only executables inside this installation folder are
stopped, including bundled Python/Node workers; other installations are left alone.
Setup checks again before replacing files. Silent installation returns a failure code
if the installation is still in use, without terminating processes.

`npm run pack` produces an unpacked application for testing. The build downloads a
standalone Python 3.12 distribution using uv, installs the backend wheel with teams,
and copies Node/npm from the build machine. Python dependencies are locked in `backend/uv.lock`; npm dependencies are locked too.

The bundled runtime must not name the machine that built it (dev spec §2.7).
`scripts/strip-build-paths.py` removes the backend's `direct_url.json` and the
console-script launchers in `python\Scripts` (they embed the build interpreter and
cannot start anywhere else; `uv.exe` stays), then compiles bytecode with source paths
relative to the Python directory. Finally it reads every file of the runtime: the build
fails, listing the files, if one still contains the checkout path or the builder's
home directory.

## Develop

Prepare the backend virtual environment as described in the repository README and
build the frontend. From `desktop`, run `npm start`. The shell calls the repository's
`run.ps1` in a hidden process when no Studio is running, or attaches to a matching-version Studio on port 8765.
An older Studio backend is replaced through the same launcher, preserving its current
project. A newer backend is left running and the older desktop reports that it needs an
update. When different commit hashes are involved (including commit/release switches),
the desktop shows both builds and asks before switching to its bundled backend.
Cancelling leaves the server running and closes startup without an error. Confirming
stops running work, reads the current project, rechecks the server identity and starts
the selected build through the same launcher. Hashes are never sorted as releases.
The web interface also checks versions at startup and reconnect, then restarts
an outdated backend or reloads an outdated page. Recovery stops running work; failed
recovery remains visible and does not loop automatically (dev spec §2.5).
To test without touching a real managed project:

```powershell
$env:VOXARIUM_STUDIO_PROJECT = (Resolve-Path ../.scratch/demo-project).Path
npm start
```

The shell uses the built SPA. For browser HMR continue using `run.cmd -Dev`.
Close confirms that running tasks will stop when the shell owns the backend.
Exit waits for that backend process to finish saving state, then ends its entire
process tree. A Windows job also cleans up orphaned tools after a crash; shutdown
has a 15-second grace period before a hung backend and its descendants are stopped.
The title bar's **Minimize to system tray** button hides the window while tasks keep
running. Click the tray icon or choose **Open Voxarium Studio** to restore it.
**Quit** in the tray menu uses the same exit confirmation. The ordinary minimize
button still minimizes to the taskbar. Tray labels follow the language in Settings.
An independently started server is left running. Runtime logs are under
`%APPDATA%/Voxarium Studio/run/`; `desktop.log` includes shell startup errors and the
release, expected backend and observed backend versions; `launcher.log` includes launcher errors.
The desktop, backend and compiled frontend use the same exact HEAD tag (normalized, for
example `v5.2.0` → `5.2.0`), or short commit hash when untagged. Startup requires equality.
The wheel stamp and packaged shell's `studioVersion` preserve this identity after installation.
Only Electron/NSIS installer metadata needs a numeric SemVer fallback for untagged builds;
it is not used in place of the Studio version for startup checks or installer text.
The installer footer, upgrade page and Windows installed-app entry show the wheel stamp's
tag or short commit hash. The next upgrade reads that label from `DisplayVersion`;
older installations retain their previously recorded version until upgraded.
The build verifies all three identities and includes the version in the frontend cache key.
Project data survives uninstall. The update button above Settings downloads new
desktop releases with progress, then offers Install and restart. Installation asks
before stopping tasks and shuts down only the owned backend.

## Publish updates

The default release repository is [voxarium-studio/dev-studio](https://github.com/voxarium-studio/dev-studio).
To override it, set `VOXARIUM_STUDIO_UPDATE_REPO=owner/repository` on the build machine
and at runtime. The value must be exactly `owner/repository`, not a URL.
Upload the installer, blockmap and latest.yml to a GitHub release. Public
feeds are the default: checking and downloading updates requires no GitHub login and
does not read personal tokens or invoke `gh`. Publication credentials belong only on
the build machine and are never embedded in the application.

For a private repository, explicitly set `VOXARIUM_STUDIO_UPDATE_PRIVATE=1` both when
building and at runtime. Only that mode reads `GH_TOKEN` / `GITHUB_TOKEN` or the
user's existing `gh` login at check time; credentials stay in the main process.

The app ID is `studio.voxarium.desktop`; the profile is `%APPDATA%/Voxarium Studio`.
The CLI and Python package are `voxarium_studio`; project data uses `.voxarium_studio/`.
The old application's working copy is not deleted.

## Build and startup performance

Desktop and frontend dependencies are installed only when their package-lock.json changes
(`build-installer.bat` and `prepare-runtime.ps1` keep a stamp in each `node_modules`;
delete `node_modules` to force a clean install). The frontend
build is cached by source-content hashes. Python's downloaded runtime is retained,
Python dependencies follow backend/uv.lock, and bytecode is precompiled for startup.
The installer uses a ZIP payload with normal compression for faster extraction; this trades a larger download for less decompression work during installation. Differential update packages are disabled for this payload. Only English and Russian Electron locale packs are shipped (§6.4).
Packing that payload is the longest build step, and 7za spends it in one thread, mostly
on the two 300 MB executables. `electron-builder.cjs` therefore lowers Deflate from level 7
to 5: 7za alone needs 50 s instead of 130 s, for 3 MB more. Set
`ELECTRON_BUILDER_COMPRESSION_LEVEL` to choose another level — `1` packs in 14 s for
22 MB more, `7` restores the previous size.
Inside a real build the step takes about 100 s rather than 50: Microsoft Defender scans
the freshly written `dist/win-unpacked` while 7za reads it, and 7za waits for it. Only a
Defender exclusion for `desktop/dist` removes that wait, which is the build machine
owner's decision; without one, levels below 5 gain little.
Most of the payload is the Electron/Chromium runtime, the Claude Code executable
bundled by the agent SDK, and the standalone Python and Node runtimes. The Claude
executable is required for Teams; uv provisions plugin environments, and Node/npm
run JavaScript plugins. These are runtime dependencies, not build caches.
No package installation
or Electron install script runs during application launch. The desktop calls run.ps1
with a hidden PowerShell process and shows a splash while waiting for the backend.

`Voxarium Studio.vbs` is the console-free checkout entry point. `run.cmd` and `run.ps1`
remain developer/server entry points with their original command-line options.
