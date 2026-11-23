# Voxarium Studio

Local workspace for AI-assisted game development. Build pipelines, manage teams of
Claude Code and Codex agents, browse project files and extend the Studio with plugins.
Includes an English/Russian web UI and a Windows desktop app.

### [Official Website](https://voxarium.studio/studio/)

<img width="1920" height="1080" alt="producer-overview" src="https://github.com/user-attachments/assets/e1b4229d-6527-4006-8d46-c54d91add968" />

## Which version to choose?

The best choice for everyday work is the
[latest release](https://github.com/voxarium-studio/dev-studio/releases/latest): it is
the more stable option. The `main` branch includes commits where the application may be
unstable and features unfinished.

## Build a Windows installer

On Windows x64, install Node.js 22.12+ (x64, with npm) and
[uv](https://docs.astral.sh/uv/), both available on `PATH`. Double-click
[`build-installer.bat`](build-installer.bat) or run it from the repository root:

```powershell
.\build-installer.bat
```

The script installs desktop dependencies from the lockfile, runs desktop tests and
builds the NSIS installer, including the frontend, backend, built-in plugins and
bundled Python/Node.js runtimes. The first build needs internet access for downloads;
Python does not need to be installed separately. A failed step stops the build.

The result is `desktop/dist/Voxarium-Studio-Setup-<build>.exe`, where `<build>` is
the release tag or commit label. The script builds locally without publishing and
pauses before closing so the result stays visible. Use
`.\build-installer.bat --no-pause` in automation; failures return a nonzero exit code.
See [desktop setup](desktop/README.md) for installer and update details.

## Run from source

Requires Python 3.12+, Node.js 22.12+ and [uv](https://docs.astral.sh/uv/).
Install and authenticate your preferred agent CLI to use agent teams.

```sh
cd backend
uv sync --extra teams
cd ../frontend
npm ci
npm run build
cd ..
```

On Windows, run `run.cmd` to open the last project or the built-in demo.
Use `run.cmd -Project "C:/Projects/MyGame"` to choose a project.

On Linux/macOS:

```sh
cd backend
uv run --extra teams voxarium_studio run /path/to/game-project
```

Open <http://127.0.0.1:8765>. Project state lives in the managed project's
`.voxarium_studio/` directory. Studio renames an existing project’s `voxarium_studio/` directory the first time it opens it. See [desktop setup](desktop/README.md) for the Windows app.

## Trust and configuration

The Studio runs agents and plugin code with your user permissions. Use trusted projects
and plugins, and keep it on localhost. Remote access requires a token and HTTPS through
a trusted reverse proxy. Put API keys in environment variables, never in committed files.

## Working together

Each person runs their own Studio next to their own checkout. To edit a project's
Documents together — edits arriving as they are typed, with each other's cursors — the
Studios connect through a small shared server, [Voxarium Studio Remote](remote/README.md),
which runs in Docker. Give everyone a key on it, then set its address under
**Project Settings → Online sync**. To see it working first, run `remote_test.bat`: it
starts a local server and two Studios as two different people, side by side.

## Development

Run backend checks with `uv run --extra teams --extra dev pytest` from `backend/`;
run `npm test` and `npm run build` from `frontend/`, and `npm test` from `desktop/`.
The remote has its own suite: `pytest` from `remote/`. Install it into the backend's
environment (`uv pip install -e ../remote`) to run the end-to-end sync tests as well.

[Design spec](docs/voxarium-studio-dev-spec.md) · [Plugin guide](docs/plugins.md) ·
[Changelog](CHANGELOG.md)
