"""Take the build machine's paths out of the bundled runtime (dev spec §2.7).

The runtime is assembled inside a checkout and then installed on other people's machines,
and three things in it remember where it was assembled:

* ``direct_url.json`` (PEP 610) names the directory the backend was installed from;
* every ``.pyc`` stores the absolute path of the source it was compiled from;
* each console-script launcher in ``Scripts`` embeds the interpreter it was made for.

None of them is needed there — the launchers cannot even start, their interpreter being on
the build machine — and all of them publish a path that may hold a user name.

    python -I -B strip-build-paths.py <runtime> <forbidden root> [<forbidden root> ...]

``prepare-runtime.ps1`` runs this with the bundled interpreter once the runtime is complete,
naming the checkout and the builder's home directory. The last step is the one that keeps
the others honest: every file is read, and the build fails if one still names a forbidden
root. A dependency that starts recording paths somewhere new stops the build instead of
reaching a release.
"""

from __future__ import annotations

import compileall
import json
import os
import sys
from collections.abc import Iterable
from pathlib import Path

CHUNK = 8 * 1024 * 1024
"""Files are searched in pieces: the largest one in the runtime is 300 MB."""


def spellings(roots: Iterable[str]) -> list[bytes]:
    """Every way a file might spell *roots*: either slash, JSON's doubled backslash, UTF-8 or UTF-16.

    Lower-cased byte by byte, which is how :func:`names_any` reads the file — Windows paths
    differ in case freely (``D:\\`` and ``d:\\``), and only the ASCII letters need folding.
    """
    found: set[bytes] = set()
    for root in roots:
        text = root.rstrip("\\/")
        if len(Path(text).parts) < 2:  # a bare drive or `/` would match every file
            continue
        for spelling in {text, text.replace("\\", "/"), text.replace("\\", "\\\\")}:
            found.update(spelling.encode(encoding).lower() for encoding in ("utf-8", "utf-16-le"))
    return sorted(found)


def names_any(path: Path, marks: list[bytes]) -> bool:
    """Whether *path* contains one of *marks*."""
    keep = max(map(len, marks)) - 1
    tail = b""
    with path.open("rb") as stream:
        # read() allocates all it is asked for before it knows how much there is. Asking for
        # CHUNK from each of the runtime's 11 000 small files took 30 s; this takes 2.
        size = min(CHUNK, os.fstat(stream.fileno()).st_size + 1)
        while chunk := stream.read(size):
            window = tail + chunk.lower()
            if any(mark in window for mark in marks):
                return True
            tail = window[-keep:]
    return False


def forget_local_sources(site_packages: Path) -> list[Path]:
    """Delete each ``direct_url.json`` that records the local path a package was installed from.

    That is the backend, and also pip: the downloaded Python records the path its own
    builder installed pip from.
    """
    removed = []
    for record in sorted(site_packages.glob("*.dist-info/direct_url.json")):
        try:
            url = json.loads(record.read_text(encoding="utf-8"))["url"]
        except (OSError, ValueError, KeyError, TypeError):
            continue  # not ours to interpret; the final scan still reads it
        if str(url).lower().startswith("file:"):
            record.unlink()
            removed.append(record)
    return removed


def remove_bound_launchers(scripts: Path, marks: list[bytes]) -> list[Path]:
    """Delete the launchers in *scripts* that embed an interpreter under a forbidden root.

    Real executables that merely live there (``uv.exe``) name nothing and are left alone.
    """
    removed = []
    for launcher in sorted(scripts.glob("*")):
        if launcher.is_file() and names_any(launcher, marks):
            launcher.unlink()
            removed.append(launcher)
    return removed


def compile_relative(lib: Path, home: Path) -> None:
    """Compile everything under *lib*, recording each source relative to *home*.

    Forced, so bytecode left by an earlier build, or written by an import during this one,
    is replaced as well. The import system restores the real path when it loads a module,
    so tracebacks on the installed machine are unaffected. A file that does not compile —
    packages ship templates and Python 2 leftovers — simply has no bytecode.
    """
    compileall.compile_dir(lib, quiet=2, force=True, stripdir=str(home), workers=0)


def offenders(runtime: Path, marks: list[bytes]) -> list[Path]:
    """The files of *runtime* that still name a forbidden root."""
    return [path for path in sorted(runtime.rglob("*")) if path.is_file() and names_any(path, marks)]


def main(argv: list[str]) -> int:
    marks = spellings(argv[2:])
    if not marks:
        print("usage: strip-build-paths.py <runtime> <forbidden root> [<forbidden root> ...]", file=sys.stderr)
        return 2
    runtime = Path(argv[1]).resolve()
    home = runtime / "python"
    sources = forget_local_sources(home / "Lib" / "site-packages")
    launchers = remove_bound_launchers(home / "Scripts", marks)
    compile_relative(home / "Lib", home)
    print(
        f"Build paths: removed {len(sources)} direct_url.json and {len(launchers)} launcher(s), "
        "recompiled bytecode with relative source paths."
    )
    left = offenders(runtime, marks)
    if left:
        print(f"ERROR: {len(left)} file(s) in the runtime still name the build machine:", file=sys.stderr)
        for path in left[:20]:
            print(f"  {path.relative_to(runtime)}", file=sys.stderr)
        if len(left) > 20:
            print(f"  ... and {len(left) - 20} more", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":  # the compile step spawns workers that import this file
    sys.exit(main(sys.argv))
