"""Project documentation as a team medium (§10.4).

Kanban tasks carry the *work*; documents carry the *decisions* — a game designer writes
the feature spec, the developer implements against it, the tester checks against it. The
files live in the managed project's ``.voxarium_studio/docs`` so they are diffable and reviewable
in git next to the pipelines and the team roster (§5).

Deliberately a plain directory of Markdown, not a table: a document is worth more when a
human can open it in their own editor, and the Explorer already knows how to show it.

Everything that has to know what is *in* the tree — link resolution, backlinks, search,
the graph — goes through one signature-validated index (:meth:`DocStore._index`) rather
than re-walking the directory. What made that necessary is recorded on
:meth:`DocStore.backlinks`.

A folder of the tree has a look of its own — a colour and an icon, kept in a
``vxs-folder.json`` inside it — and can be renamed, which mends every link the new name
would otherwise break (block-editor spec §5.2, :meth:`DocStore.rename_folder`), or deleted
with everything in it (:meth:`DocStore.delete_folder`).
"""

from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import os
import re
import secrets
import threading
from collections import Counter
from collections.abc import AsyncIterator, Callable
from contextlib import asynccontextmanager
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath
from stat import S_ISREG
from typing import TYPE_CHECKING
from urllib.parse import quote, unquote, urlsplit

from ..core.paths import ProjectPaths
from ..explorer.service import IMAGE_SUFFIXES, PathOutsideProject, remove_path
from ..ws.bus import EventBus
from ..ws.events import EventType

if TYPE_CHECKING:
    from ..sync.transitions import BindingTransitions, MutationAdmission

log = logging.getLogger(__name__)

ALLOWED_SUFFIXES = frozenset({".md", ".markdown", ".txt"})
MAX_DOC_BYTES = 1_000_000

MAX_ASSET_BYTES = 32 * 1024 * 1024
"""The largest picture that may be dropped into a document.

Generous because it bounds a *local* file copy rather than anything on a network, and a
game project's source art really is tens of megabytes. It is a bound rather than no bound
because the browser decides what to send: a mistaken drag of a video would otherwise be
written into the docs folder and committed with it.
"""

#: Characters no filesystem this runs on will take, plus the separators. Replaced rather
#: than rejected: a name is a label here, and refusing a dropped screenshot because Windows
#: dislikes its colon would be pedantry.
_ILLEGAL_NAME_CHARS = re.compile(r'[\x00-\x1f<>:"|?*/\\]+')

MAX_INDEX_TEXT_BYTES = 64_000_000
"""Past this many characters, the index keeps its maps but drops the bodies it read.

Characters rather than encoded bytes because that is the floor on what they cost in memory
and it needs no second pass over the corpus to measure.

The bodies are read either way — the titles and the links come out of them — so this only
decides whether they are *kept* for :meth:`DocStore.search`. ``write`` caps a document at
``MAX_DOC_BYTES``, but a document that arrived some other way (a checkout, the user's own
editor) has no such ceiling, and one copy of a pathological docs folder would otherwise be
pinned for the life of the ``AppContext``.
"""

WIKI_LINK = re.compile(r"\[\[([^\[\]|]+?)(?:\|([^\[\]]*))?\]\]")
"""``[[target]]`` or ``[[target|label]]`` — the way documents point at each other.

Chosen over plain Markdown links because a document is addressed by *name*, not by a
relative path: an agent writing a brief should be able to say ``[[dash-ability]]``
without knowing where the file it wants ended up in the folder tree.
"""

SUFFIX_ORDER = tuple(sorted(ALLOWED_SUFFIXES))
"""``.markdown``, ``.md``, ``.txt`` — the order :meth:`DocStore.resolve_link` tries when a
link carries no suffix of its own, and therefore which file ``[[plan]]`` means when several
exist. Hoisted out of the loop it used to be computed in, so the precedence is stated once.
"""

FOLDER_META_NAME = "vxs-folder.json"
"""How a folder of the tree looks, kept *inside* the folder it describes (block-editor spec §5.2).

Inside it rather than in a table of the project's, for the reason the rest of the section is
files: a folder renamed or moved in Explorer, Plastic or git takes its colour along, and no
entry can go on describing a directory that is no longer called that. The suffix is what keeps
it out of the corpus — ``ALLOWED_SUFFIXES`` has no ``.json``, so the index, search and the
agents' ``list_docs`` never see it.
"""

LEGACY_FOLDER_META_NAME = "tds-folder.json"

FOLDER_COLORS = (
    "red",
    "orange",
    "yellow",
    "green",
    "cyan",
    "blue",
    "purple",
    "pink",
    "brown",
    "gray",
)
"""Every colour a folder may wear: the ten text colours of block-editor spec §3.2.1.

One vocabulary for both, closed for the same reason: each word has a dark and a light value
tuned for contrast (``--vxs-md-*`` in ``tokens.css``), so a folder painted on a dark screen still
reads on a light one, which a hex picked by eye would not. ``grey`` is read as ``gray``, as it is
in a document, and never written.
"""

MAX_ICON_NAME = 64
_ICON_NAME = re.compile(r"[a-z0-9]+(?:-[a-z0-9]+)*")
"""A Lucide identifier's shape — ``book-open``, ``gamepad-2``.

Only the shape is checked here. Which icons exist is the catalogue in ``lib/folder-style.ts``,
and a name that catalogue does not know draws the plain folder: a Studio that adds icons must
not need a backend that agrees first, and a file written by a newer one must still be served.
"""

_ILLEGAL_FOLDER_CHARS = re.compile(r'[\x00-\x1f<>:"|?*\[\]]')
"""What a folder may not be renamed to contain: everything Windows refuses in a name, plus the
three a wiki link cannot carry in its target. A folder called ``a|b`` could never be linked
into by path again — and the links into it could not be mended, which is the rename's job."""

_RESERVED_FOLDER_NAMES = frozenset(
    {"con", "prn", "aux", "nul"}
    | {f"com{n}" for n in range(1, 10)}
    | {f"lpt{n}" for n in range(1, 10)}
)

RENAME_ATTEMPTS = 5
RENAME_RETRY_SECONDS = 0.1
"""A directory rename refused while something holds a handle inside it is retried this often.

Some of what holds one is momentary — the search indexer, an antivirus scan, a version control
client walking the tree — and a refusal caused by that is not worth reporting. What stays open
(an Explorer window on the folder, a file open in an editor) still fails, after half a second.
"""


class DocumentChangedOnDisk(Exception):
    """The file moved under the editor between opening it and saving it.

    One user, and still reachable: an agent's ``write_doc`` lands while a document is open,
    or a branch switch in Plastic rewrites the working copy. The block editor holds a whole
    parsed document in memory, so a blind write would silently replace the other change with
    a version of the file that predates it. Refusing costs a dialog; not refusing costs work.
    """


class SnapshotChangedOnDisk(DocumentChangedOnDisk):
    """The raw document corpus changed outside DocStore's mutation gate (§16.3)."""


@dataclass(frozen=True, slots=True)
class DocSnapshot:
    """Immutable raw Documents corpus, valid only inside ``final_snapshot`` (§16.3).

    Paths are relative POSIX paths with their original suffix; bytes are not decoded or
    newline-normalised. Assets and folder metadata are deliberately not synchronised.
    """

    docs: tuple[tuple[str, bytes], ...]
    fingerprint: str
    generation: int
    _verify: Callable[[], None]
    _advance: Callable[[], int]

    def verify(self) -> None:
        """Re-read content, refusing external edits even with unchanged size and mtime."""
        self._verify()

    def advance_generation(self) -> int:
        """Verify immediately before asking the live fence to advance its generation."""
        self.verify()
        return self._advance()


class FolderStyleUnreadable(Exception):
    """A folder's ``vxs-folder.json`` is there and is not a JSON object.

    Refused rather than replaced. The file is the project's and may be under version control,
    and a write that silently dropped what somebody put there by hand is worse than a colour
    that did not change — the rule the Studio already keeps for ``.mcp.json``.
    """


class FolderInUse(Exception):
    """The operating system would not rename a folder, even after a few tries — to rename it, or
    to move it aside before deleting it (:meth:`DocStore.delete_folder`).

    Kept apart from ``PermissionError`` on purpose: the app answers that one with 403, which
    reads as "you may not", and what almost always happened is "something has it open".
    """


@dataclass(frozen=True, slots=True)
class DocEntry:
    path: str
    title: str
    size_bytes: int
    updated_at: str

    def to_json(self) -> dict[str, object]:
        return {
            "path": self.path,
            "title": self.title,
            "size_bytes": self.size_bytes,
            "updated_at": self.updated_at,
        }


@dataclass(frozen=True, slots=True)
class _Scanned:
    """One file as the cheap pass sees it: everything a :class:`DocEntry` needs but the title.

    ``updated_at`` is formatted here, from the same ``stat_result`` the signature is taken
    from, so a cached entry and a fresh ``_entry()`` produce byte-identical strings — and the
    editor's ``expected_updated_at`` round-trips. Recomputing it from ``mtime_ns`` later would
    round differently in the last microsecond.
    """

    path: Path
    relative: str
    size_bytes: int
    mtime_ns: int
    updated_at: str


@dataclass(frozen=True, slots=True)
class _DocIndex:
    """One pass over ``.voxarium_studio/docs``, reused until the tree's signature moves (§10.4).

    Published under a lock and never mutated afterwards: a reader holds a reference to it
    while another thread may be swapping a newer one in, so every collection here is handed
    out as a copy rather than by reference.
    """

    signature: tuple[tuple[str, int, int], ...]
    entries: tuple[DocEntry, ...]
    """``list_docs`` order — ``sorted(rglob)``, which is also resolve_link's tie-breaker."""
    texts: dict[str, str]
    """Body per path, read with the *default* newline translation, exactly as ``search`` and
    ``backlinks`` used to read it. A path missing here either failed to open — the old code's
    ``except OSError: continue`` — or the whole corpus was too large to keep (see
    ``texts_cached`` and ``MAX_INDEX_TEXT_BYTES``)."""
    texts_cached: bool
    """False when the bodies were dropped, which is what tells ``search`` that a missing entry
    means "re-read it" rather than "this file is unreadable"."""
    by_path: dict[str, DocEntry]
    by_lowered: dict[str, str]
    """Lowered stem *and* lowered path in one map, because the pass it replaces tested both
    with ``or`` inside a single loop — see :func:`_resolve_against`."""
    by_title: dict[str, str]
    links_out: dict[str, tuple[str, ...]]
    """Source path → resolved, deduplicated, non-self targets, in first-appearance order.
    This is the graph's edge set; it is deliberately *not* ``_outgoing``'s payload, which
    keeps unresolved and self links and is computed from the caller's own text."""
    referrers: dict[str, dict[str, list[str]]]
    """Target path → source path → the context lines, prebuilt so ``backlinks`` is a lookup."""


@dataclass(frozen=True, slots=True)
class _Relink:
    """A document holding links a folder rename broke, as the rename planned it."""

    path: str
    """Where the document is after the rename."""
    previous_path: str | None
    """Where it was, when the rename moved it; ``None`` for a document outside the folder."""
    title: str
    links: int
    """How many links the index says need mending. The write reports what it actually changed,
    which differs only when the file was edited in between."""

    def to_json(self) -> dict[str, object]:
        return {
            "path": self.path,
            "previous_path": self.previous_path,
            "title": self.title,
            "links": self.links,
        }


class FolderRename:
    """A folder renamed on disk, and the frames of mending the links into it (§5.2).

    The rename itself is done by the time one of these exists — a refusal is an exception out
    of :meth:`DocStore.rename_folder`, which an HTTP caller answers with a status code rather
    than a frame. What follows is progress: ``renamed`` with the plan, one ``document`` frame
    per file as it is mended, and ``done`` — or ``error`` — last. One reader.
    """

    def __init__(self, path: str, previous_path: str, moved: int, plan: list[_Relink]) -> None:
        self.path = path
        self.previous_path = previous_path
        self.moved = moved
        """Documents that moved with the folder."""
        self.plan = plan
        self._frames: asyncio.Queue[dict[str, object] | None] = asyncio.Queue()

    def summary(self) -> dict[str, object]:
        return {"path": self.path, "previous_path": self.previous_path, "moved": self.moved}

    def _put(self, frame: dict[str, object] | None) -> None:
        self._frames.put_nowait(frame)

    async def frames(self) -> AsyncIterator[dict[str, object]]:
        """Every frame in order, until the mending is over. Abandoning it stops nothing."""
        while True:
            frame = await self._frames.get()
            if frame is None:
                return
            yield frame


class DocStore:
    """One resident context's Documents boundary (§16.3).

    Async corpus mutations share ``transitions``. An explicit binding generation is
    never silently adopted; ``None`` preserves local/in-process legacy calls. Image
    uploads and external filesystem writers are outside this Markdown-only boundary.
    """

    def __init__(
        self,
        paths: ProjectPaths,
        bus: EventBus | None = None,
        *,
        transitions: BindingTransitions | None = None,
    ) -> None:
        # sync.__init__ imports RemoteSync, which imports DocStore: keep direct DocStore
        # imports usable without relying on AppContext having imported sync first.
        from ..sync.transitions import BindingTransitions

        self.paths = paths
        self.bus = bus
        self.transitions = transitions if transitions is not None else BindingTransitions()
        # A ``threading`` lock rather than an ``asyncio`` one because these methods are
        # synchronous and may be called from an anyio worker thread as well as from the event
        # loop. Every caller is on the loop today, so contention is nil — the lock is what
        # keeps that an implementation detail rather than a constraint.
        self._lock = threading.Lock()
        self._cached: _DocIndex | None = None
        # Held through the rename's FS phase and all link mending, never through event
        # publication: subscribers may synchronously await another rename/delete.
        self._folder_lock = asyncio.Lock()
        self._mending: set[asyncio.Task[None]] = set()

    @property
    def directory(self) -> Path:
        return self.paths.docs

    def _snapshot_docs(self) -> tuple[tuple[str, bytes], ...]:
        # No index/metadata signature: external writes may preserve both mtime and size.
        # Fail closed on unreadable/vanishing documents instead of silently omitting them.
        root = _plain(self.directory.resolve())
        try:
            self.directory.stat()
        except FileNotFoundError:
            return ()

        def unreadable(exc: OSError) -> None:
            raise exc

        documents: list[tuple[str, bytes]] = []
        # rglob suppresses scandir errors and could silently snapshot an incomplete tree.
        for folder, _dirs, files in self.directory.walk(on_error=unreadable):
            for name in files:
                path = folder / name
                if path.suffix.lower() not in ALLOWED_SUFFIXES:
                    continue
                if not S_ISREG(path.stat().st_mode):
                    continue
                resolved = _plain(path.resolve())
                if root not in resolved.parents:
                    raise PathOutsideProject(f"Document escapes the docs tree: {path}")
                documents.append((path.relative_to(self.directory).as_posix(), path.read_bytes()))
        return tuple(sorted(documents))

    @staticmethod
    def _snapshot_fingerprint(documents: tuple[tuple[str, bytes], ...]) -> str:
        digest = hashlib.sha256(b"voxarium-doc-snapshot-v1\0")
        for path, data in documents:
            encoded = path.encode("utf-8", errors="surrogateescape")
            # Length framing distinguishes concatenations and includes the exact path.
            for part in (encoded, data):
                digest.update(len(part).to_bytes(8, "big"))
                digest.update(part)
        return digest.hexdigest()

    @asynccontextmanager
    async def final_snapshot(self) -> AsyncIterator[DocSnapshot]:
        """Drain admitted mutations and fence the final raw corpus (§16.3).

        External editors cannot be locked out: verify before committing a binding and
        automatically on successful exit. A failure must abort the caller's transition.
        """
        async with self.transitions.final_snapshot() as fence:
            documents = self._snapshot_docs()
            fingerprint = self._snapshot_fingerprint(documents)
            active = True

            def verify() -> None:
                if not active:
                    raise RuntimeError("The document snapshot fence is no longer active")
                if self._snapshot_fingerprint(self._snapshot_docs()) != fingerprint:
                    raise SnapshotChangedOnDisk("Documents changed during the final snapshot")

            snapshot = DocSnapshot(
                documents, fingerprint, fence.generation, verify, fence.advance_generation
            )
            try:
                yield snapshot
                snapshot.verify()
            finally:
                active = False

    # ------------------------------------------------------------------ paths

    def resolve(self, relative: str) -> Path:
        """Map a caller-supplied doc path to an absolute one inside ``.voxarium_studio/docs``.

        The caller may be an agent acting on model output, so this is a trust boundary:
        traversal, absolute paths and unexpected suffixes are rejected rather than
        normalised into something surprising.
        """
        raw = (relative or "").strip().replace("\\", "/").lstrip("/")
        if not raw:
            raise ValueError("A document path is required")

        pure = PurePosixPath(raw)
        if pure.is_absolute() or any(part in ("..", "") for part in pure.parts):
            raise PathOutsideProject(f"Illegal document path: {relative!r}")

        suffix = pure.suffix.lower()
        if not suffix:
            pure = pure.with_suffix(".md")
        elif suffix not in ALLOWED_SUFFIXES:
            raise ValueError(
                f"Documents must be one of {', '.join(sorted(ALLOWED_SUFFIXES))}, got {suffix!r}"
            )

        candidate = _plain((self.directory / pure).resolve())
        directory = _plain(self.directory.resolve())
        if candidate != directory and directory not in candidate.parents:
            raise PathOutsideProject(f"Illegal document path: {relative!r}")
        return candidate

    def relative(self, path: Path) -> str:
        return _plain(path.resolve()).relative_to(_plain(self.directory.resolve())).as_posix()

    def asset(self, document: str, src: str) -> Path:
        """Map a picture referenced by *document* onto the file it means.

        A document's images sit beside it — ``![](shot.png)`` next to ``shot.png`` — so the
        reference is resolved against the document's own folder, the way any other Markdown
        reader resolves it. Nothing in the file is rewritten to make that work: the reference
        stays relative and turning it into something a browser can fetch happens at read time
        (``ImageView.vue``), which is what keeps opening a document with pictures from
        producing a diff.

        The boundary is the **managed project**, not ``.voxarium_studio/docs``. A game project keeps
        its art beside the game, so ``![](../../Assets/hero.png)`` in a design document is a
        reference to the project, not an escape from it; anything landing outside the project
        is refused. A leading ``/`` means the docs root, as it does for a document path.

        Percent-encoding is decoded first, because an exported document writes a space as
        ``%20`` — but the raw form is tried as well, since a file may legitimately be named
        with a ``%`` in it and guessing wrong would hide a picture that is really there.
        """
        base = self.resolve(document).parent
        raw = (src or "").strip().replace("\\", "/")
        if not raw:
            raise ValueError("An image reference is required")
        if urlsplit(raw).scheme or raw.startswith("//"):
            # Remote images are not fetched at all — see ``ImageView.vue``, which shows them
            # as links. Reaching here means the client asked for something it should not have.
            raise ValueError(f"Not a project file: {src!r}")

        decoded = unquote(raw)
        docs_root = _plain(self.directory.resolve())
        candidates: list[Path] = []
        for value in (decoded,) if decoded == raw else (decoded, raw):
            root = docs_root if value.startswith("/") else base
            candidate = _plain((root / value.lstrip("/")).resolve())
            if not self.paths.is_inside_project(candidate):
                raise PathOutsideProject(f"Image escapes the managed project: {src!r}")
            candidates.append(candidate)

        target = next((path for path in candidates if path.is_file()), None)
        if target is None:
            raise FileNotFoundError(f"No image {src!r} beside {document!r}")
        if target.suffix.lower() not in IMAGE_SUFFIXES:
            raise ValueError(f"Not an image: {src!r}")
        return target

    def add_asset(self, document: str, filename: str, data: bytes) -> dict[str, object]:
        """Put a dropped picture beside *document* and say how to reference it.

        The mirror of :meth:`asset`: that resolves a reference to a file, this creates the
        file a reference will point at. Beside the document, because that is where a document's
        pictures live — and because it keeps the two halves of a document, its text and its
        images, in one folder that can be moved, committed and reviewed together.

        The name is the one the file arrived with, kept: someone who opens the folder should
        recognise their screenshot. It is *not* trusted, though — it comes from a browser — so
        every path separator and every character Windows will not put in a name is stripped,
        and what is left has to still be an image. An existing name is never overwritten;
        ``shot.png`` becomes ``shot-2.png``, which is visible in the folder in a way that a
        silently replaced file is not.

        Returns the reference to write into the Markdown, percent-encoded, so a name with a
        space in it is a valid URL rather than a link that ends at the space.
        """
        if len(data) > MAX_ASSET_BYTES:
            raise ValueError(f"Image exceeds {MAX_ASSET_BYTES // (1024 * 1024)} MiB")
        if not data:
            raise ValueError("The file is empty")

        folder = self.resolve(document).parent
        name = _asset_filename(filename)
        if PurePosixPath(name).suffix.lower() not in IMAGE_SUFFIXES:
            allowed = ", ".join(sorted(IMAGE_SUFFIXES))
            raise ValueError(f"Only images can be added to a document ({allowed})")

        folder.mkdir(parents=True, exist_ok=True)
        target = _free_name(folder, name)
        if not self.paths.is_inside_project(target):
            # Belt and braces: `_asset_filename` has already taken the separators out, so
            # reaching here would mean that function stopped doing its job.
            raise PathOutsideProject(f"Image escapes the managed project: {filename!r}")

        # Written beside the target and moved onto it, as a document is (§7) — a half-written
        # picture is a file the editor would immediately try to display.
        tmp = target.with_suffix(target.suffix + ".tmp")
        try:
            tmp.write_bytes(data)
            tmp.replace(target)
        except OSError:
            tmp.unlink(missing_ok=True)
            raise

        log.info("Image %s added beside %s", target.name, document)
        return {
            "src": quote(target.name),
            "alt": target.stem,
            "name": target.name,
            "path": self.relative(target),
            "size": len(data),
        }

    # ------------------------------------------------------------------ the index

    def _scan(self) -> list[_Scanned]:
        """The cheap half: one ``rglob`` and one ``stat`` per candidate, no file opened.

        This runs on *every* public call — it is what makes a cached index safe to serve, so
        it may never be skipped. ``mkdir`` is the side effect ``list_docs`` used to carry:
        the first hit on any docs endpoint is what creates ``.voxarium_studio/docs``.
        """
        self.directory.mkdir(parents=True, exist_ok=True)
        # ``relative()`` with the docs root resolved once instead of once per file. On Windows
        # ``Path.resolve`` opens a handle to ask the filesystem for the canonical name, and it
        # was two thirds of this pass's cost — 8.7 ms of 12.3 ms over 49 documents. The
        # per-file ``resolve()`` below stays: it is what makes a symlink pointing out of
        # ``.voxarium_studio/docs`` raise ``ValueError`` here, as it always has.
        root = _plain(self.directory.resolve())
        scanned: list[_Scanned] = []
        # ``sorted(rglob("*"))`` verbatim, and not a sort of the relative posix strings:
        # ``Path`` comparison is case-folded on Windows and compares *parts*, so it puts
        # ``features/dash.md`` before ``features.md`` where the strings put it after. That
        # order is resolve_link's stem/title tie-breaker and backlinks' result order.
        for path in sorted(self.directory.rglob("*")):
            # Suffix first, so a directory costs no stat at all. ``is_file()`` used to be the
            # first test; the set of files it admits is unchanged.
            if path.suffix.lower() not in ALLOWED_SUFFIXES:
                continue
            try:
                stat = path.stat()
            except OSError:
                # A file that vanished between the rglob and the stat used to raise out of
                # ``list_docs``; a tree being rewritten under us is the ordinary case here.
                continue
            if not S_ISREG(stat.st_mode):
                continue
            scanned.append(
                _Scanned(
                    path=path,
                    relative=_plain(path.resolve()).relative_to(root).as_posix(),
                    size_bytes=stat.st_size,
                    mtime_ns=stat.st_mtime_ns,
                    updated_at=datetime.fromtimestamp(stat.st_mtime, tz=timezone.utc).isoformat(),
                )
            )
        return scanned

    def files(self) -> list[tuple[str, Path, int, int]]:
        """Every document as ``(relative path, file, mtime_ns, size)`` — no file opened.

        What online sync compares the tree with (§16.3): the same pass every read is
        validated by, so the two can never disagree about which files are documents.
        """
        return [
            (item.relative, item.path, item.mtime_ns, item.size_bytes) for item in self._scan()
        ]

    def _build(
        self, scanned: list[_Scanned], signature: tuple[tuple[str, int, int], ...]
    ) -> _DocIndex:
        """The expensive half: one open per document, then every map in memory."""
        entries: list[DocEntry] = []
        texts: dict[str, str] = {}
        total_bytes = 0
        for item in scanned:
            try:
                text: str | None = item.path.read_text(encoding="utf-8", errors="replace")
            except OSError:
                text = None
            else:
                texts[item.relative] = text
                total_bytes += len(text)
            entries.append(
                DocEntry(
                    path=item.relative,
                    title=_title_from_text(text, item.path),
                    size_bytes=item.size_bytes,
                    updated_at=item.updated_at,
                )
            )

        by_path = {entry.path: entry for entry in entries}
        by_lowered: dict[str, str] = {}
        for entry in entries:
            # One map for the stem *and* the path because the pass they replace tested both
            # in a single loop joined by ``or``: for a given key the winner is the first entry
            # matching *either*, not the first stem match and then the first path match. Two
            # maps consulted in sequence would answer differently. ``setdefault`` is what
            # makes "first in ``sorted(rglob)`` order" win.
            by_lowered.setdefault(PurePosixPath(entry.path).stem.lower(), entry.path)
            by_lowered.setdefault(entry.path.lower(), entry.path)
        by_title: dict[str, str] = {}
        # A second loop, not a second branch in the first: titles are a later *stage* than
        # stems, so every document's stem has to be considered before any document's title.
        for entry in entries:
            by_title.setdefault(entry.title.lower(), entry.path)

        links_out: dict[str, tuple[str, ...]] = {}
        referrers: dict[str, dict[str, list[str]]] = {}
        for entry in entries:
            text = texts.get(entry.path)
            if text is None:
                continue
            targets: list[str] = []
            for line in text.splitlines():
                # One context per (source, target, line) even when the line names the target
                # twice: ``backlinks`` used to ask ``any(...)`` per line, which short-circuits,
                # and the panel showed such a line once. A line naming two *different* targets
                # still contributes to both.
                matched: set[str] = set()
                for match in WIKI_LINK.finditer(line):
                    resolved = _resolve_against(
                        match.group(1).strip(), by_path, by_lowered, by_title
                    )
                    if resolved is None or resolved == entry.path or resolved in matched:
                        continue
                    matched.add(resolved)
                    if resolved not in targets:
                        targets.append(resolved)
                    referrers.setdefault(resolved, {}).setdefault(entry.path, []).append(
                        line.strip()[:200]
                    )
            if targets:
                links_out[entry.path] = tuple(targets)

        texts_cached = total_bytes <= MAX_INDEX_TEXT_BYTES
        return _DocIndex(
            signature=signature,
            entries=tuple(entries),
            texts=texts if texts_cached else {},
            texts_cached=texts_cached,
            by_path=by_path,
            by_lowered=by_lowered,
            by_title=by_title,
            links_out=links_out,
            referrers=referrers,
        )

    def _index(self) -> _DocIndex:
        """The corpus as of *this* call: validity is recomputed, only the answer is cached."""
        scanned = self._scan()
        signature = tuple((item.relative, item.mtime_ns, item.size_bytes) for item in scanned)
        with self._lock:
            cached = self._cached
        if cached is not None and cached.signature == signature:
            return cached
        # Built outside the lock: it opens every file, and holding a lock across that would
        # serialise unrelated requests — and, since the docs routes are ``async def``, could
        # park the event loop behind another thread's IO. Two threads racing build the same
        # object from the same signature, so a duplicated build costs work, never correctness.
        built = self._build(scanned, signature)
        with self._lock:
            # Last writer wins, so a slow thread can publish an older index over a newer one.
            # Harmless: the next caller compares against the signature *it* just read, sees a
            # mismatch and rebuilds. The invariant is only ever "an index is served if and only
            # if its signature equals the tree as of this request" — never add a path that
            # returns ``self._cached`` without comparing.
            self._cached = built
        return built

    def _invalidate(self) -> None:
        """Drop the index because *we* just changed the tree.

        The signature would catch it anyway on the next call, but only if the write moved
        ``st_mtime_ns`` or the size — and ``write`` computes its own response's links from the
        index immediately afterwards, inside the same filesystem timestamp tick. Eager
        invalidation removes that dependency on timestamp granularity, and costs nothing.
        """
        with self._lock:
            self._cached = None

    # ------------------------------------------------------------------ reads

    def list_docs(self) -> list[DocEntry]:
        # A fresh list, so a caller cannot reorder the shared index.
        return list(self._index().entries)

    def read(self, relative: str) -> dict[str, object]:
        path = self.resolve(relative)
        if not path.is_file():
            raise FileNotFoundError(f"No document {self.relative(path)!r}")
        # ``newline=""`` — the bytes go to an editor that writes them back, so the file's own
        # line endings have to survive the trip. Python's default translates CRLF to LF on
        # read, and the block editor then saves an LF file: every line of a Windows-authored
        # document rewritten on the first save, which is what §7 and §8.2 forbid. Only this
        # reader needs it; ``search`` and ``backlinks`` compare lines and never write them —
        # which is also why ``content`` may never be served from ``index.texts``.
        text = path.open("r", encoding="utf-8", errors="replace", newline="").read()
        rel = self.relative(path)
        index = self._index()
        return {
            **self._entry(path).to_json(),
            "content": text,
            "links": self._outgoing(rel, text, index),
            "backlinks": self._backlinks(rel, index),
        }

    # ------------------------------------------------------------------ links

    def resolve_link(self, target: str) -> str | None:
        """Map a ``[[wiki link]]`` target onto a document path, or None if it is unresolved.

        Tried in order of how specific the reference is: the exact path, the path with a
        suffix added, then the bare stem anywhere in the tree, then the document's title.
        An unresolved link is not an error — Obsidian-style, it is an invitation to create
        the document, and the UI offers exactly that.
        """
        index = self._index()
        return _resolve_against(target, index.by_path, index.by_lowered, index.by_title)

    def _outgoing(self, source: str, text: str, index: _DocIndex) -> list[dict[str, object]]:
        """The links in *this* text — the caller's, never the index's copy of the body.

        Keyed and deduplicated by the raw target, so the first occurrence's label wins; a
        self-link is resolved with no path, an unresolved one is neither.
        """
        seen: dict[str, dict[str, object]] = {}
        for match in WIKI_LINK.finditer(text):
            target = match.group(1).strip()
            if not target or target in seen:
                continue
            resolved = _resolve_against(target, index.by_path, index.by_lowered, index.by_title)
            seen[target] = {
                "target": target,
                "label": (match.group(2) or "").strip() or None,
                "path": resolved if resolved != source else None,
                "resolved": resolved is not None,
            }
        return list(seen.values())

    def backlinks(self, relative: str) -> list[dict[str, object]]:
        """Documents pointing at this one, with the line each mention sits on.

        Served from the index (:meth:`_index`) rather than by rescanning. The files really do
        change behind the Studio's back — an agent's tool call, the user's own editor, a git
        checkout — so nothing here is *trusted*: every call recomputes the tree's signature
        with one ``rglob`` and one ``stat`` per file (1.9 ms over a 49-document corpus) and
        only the file *bodies* and the maps derived from them are reused. A write we make
        ourselves additionally invalidates eagerly, so staleness cannot ride a timestamp tick.

        Rescanning instead cost one ``list_docs()`` per link occurrence in the whole corpus,
        because ``resolve_link`` reopened the entire tree to answer each one: 27,931 file opens
        and 10.4 s to answer a single read of a sample project with 49 documents, on the event loop.
        """
        target_path = self.relative(self.resolve(relative))
        return self._backlinks(target_path, self._index())

    def _backlinks(self, target_path: str, index: _DocIndex) -> list[dict[str, object]]:
        by_source = index.referrers.get(target_path, {})
        if not by_source:
            return []
        found: list[dict[str, object]] = []
        # Walked in ``entries`` order rather than the map's, because the old result order was
        # ``list_docs()`` order and the panel lists them that way.
        for entry in index.entries:
            contexts = by_source.get(entry.path)
            if contexts:
                found.append(
                    {"path": entry.path, "title": entry.title, "contexts": list(contexts)}
                )
        return found

    # ------------------------------------------------------------------ search

    def search(self, query: str, *, limit: int = 50) -> list[dict[str, object]]:
        """Case-insensitive substring search over titles and bodies."""
        needle = (query or "").strip().lower()
        if not needle:
            return []

        index = self._index()
        results: list[dict[str, object]] = []
        for entry in index.entries:
            text = index.texts.get(entry.path)
            if text is None:
                if index.texts_cached:
                    continue  # unreadable — the old ``except OSError: continue``
                try:
                    text = (self.directory / entry.path).read_text(
                        encoding="utf-8", errors="replace"
                    )
                except OSError:
                    continue
            matches = [
                {"line": number, "text": line.strip()[:200]}
                for number, line in enumerate(text.splitlines(), start=1)
                if needle in line.lower()
            ]
            in_title = needle in entry.title.lower() or needle in entry.path.lower()
            if not matches and not in_title:
                continue
            results.append(
                {
                    "path": entry.path,
                    "title": entry.title,
                    "title_match": in_title,
                    "matches": matches[:5],
                    "match_count": len(matches),
                }
            )
        # A title hit is what the user usually meant; body hits rank by how often.
        results.sort(key=lambda item: (not item["title_match"], -int(item["match_count"])))
        return results[:limit]

    # ------------------------------------------------------------------ graph

    def graph(self) -> dict[str, object]:
        """Every document and every resolved link, for the Documents map view (§10.4).

        One index pass answers it: ``links_out`` already holds each document's resolved,
        deduplicated, non-self targets, so this is arithmetic over what a read would have
        built anyway — never a second scan. Self-links and unresolved links are excluded by
        construction, and every edge target is guaranteed to be a node, because resolution
        only ever returns a path that is in ``by_path``.
        """
        index = self._index()
        in_degree: Counter[str] = Counter()
        edges: list[dict[str, str]] = []
        for entry in index.entries:
            for target in index.links_out.get(entry.path, ()):
                edges.append({"source": entry.path, "target": target})
                in_degree[target] += 1
        nodes: list[dict[str, object]] = [
            {
                "path": entry.path,
                "title": entry.title,
                "in_degree": in_degree[entry.path],
                "out_degree": len(index.links_out.get(entry.path, ())),
            }
            for entry in index.entries
        ]
        # Sorted by the relative path rather than left in ``list_docs`` order: that order is
        # ``sorted(rglob)``, which on Windows folds case and puts a directory before a
        # same-prefixed file, so it is platform-dependent — and this is a wire contract a
        # test and a layout both key off.
        nodes.sort(key=lambda node: str(node["path"]))
        edges.sort(key=lambda edge: (edge["source"], edge["target"]))
        return {"nodes": nodes, "edges": edges}

    # ------------------------------------------------------------------ writes

    async def write(
        self,
        relative: str,
        content: str,
        *,
        author: str | None = None,
        expected_updated_at: str | None = None,
        exclusive: bool = False,
        expected_binding_generation: int | None = None,
    ) -> dict[str, object]:
        """Write a document, atomically.

        ``expected_updated_at`` is the ``updated_at`` the caller last read. Passing it makes
        the write conditional: if the file has changed since, it is refused rather than
        overwritten. Agents do not pass it — their writes are the authority on their own
        documents — but an editor holding a parsed copy must.

        ``exclusive`` refuses with :class:`FileExistsError` when anything is already at the path,
        which is what creating a document sends: a write with no expectation replaces whatever is
        there, and "New document" given a name already taken would otherwise empty that document
        down to a heading. Asked of the filesystem, so on Windows ``Notes.md`` is taken by
        ``notes.md`` as well.
        """
        admission = await self.transitions.admit(expected_binding_generation)
        try:
            async with admission.critical():
                if len(content.encode("utf-8")) > MAX_DOC_BYTES:
                    raise ValueError(f"Document exceeds {MAX_DOC_BYTES} bytes")

                path = self.resolve(relative)
                if exclusive and path.exists():
                    raise FileExistsError(f"{self.relative(path)!r} already exists")
                if expected_updated_at is not None and path.is_file():
                    # A live ``stat`` through ``_entry``, deliberately not the index: staleness is the
                    # one question that may never be answered from a cache, or a blind save clobbers
                    # the agent write it exists to refuse.
                    current = self._entry(path).updated_at
                    if current != expected_updated_at:
                        raise DocumentChangedOnDisk(
                            f"{self.relative(path)!r} changed on disk at {current}; "
                            "reload it before saving"
                        )
                path.parent.mkdir(parents=True, exist_ok=True)
                # Written beside the target and moved onto it, so the file is never half-written
                # (§7). The temporary file is cleaned up when that move fails — a read-only file or
                # one locked by another program otherwise leaves ``spec.md.tmp`` in the user's docs
                # folder, where it shows up in their next commit.
                tmp = path.with_suffix(path.suffix + ".tmp")
                try:
                    tmp.write_text(content, encoding="utf-8", newline="\n")
                    tmp.replace(path)
                except OSError:
                    tmp.unlink(missing_ok=True)
                    raise
                self._invalidate()

                entry = self._entry(path)
                # The same shape a read returns, links and backlinks included. An editor that saves
                # and then adopts this payload would otherwise lose both panels — and rebuilding them
                # is precisely what a save should do, since a save is when the links changed.
                index = self._index()
                result = {
                    **entry.to_json(),
                    "content": content,
                    "links": self._outgoing(entry.path, content, index),
                    "backlinks": self._backlinks(entry.path, index),
                }
        finally:
            admission.release()

        if self.bus is not None:
            await self.bus.publish(
                EventType.TEAM_DOC_CHANGED,
                {"path": entry.path, "title": entry.title, "author": author},
            )
        log.info("Document %s written by %s", entry.path, author or "the user")
        return result

    async def delete(
        self,
        relative: str,
        *,
        author: str | None = None,
        expected_binding_generation: int | None = None,
    ) -> str:
        admission = await self.transitions.admit(expected_binding_generation)
        try:
            async with admission.critical():
                path = self.resolve(relative)
                if not path.is_file():
                    raise FileNotFoundError(f"No document {self.relative(path)!r}")
                rel = self.relative(path)
                path.unlink()
                self._prune_empty_folders(path.parent)
                self._invalidate()
        finally:
            admission.release()

        if self.bus is not None:
            await self.bus.publish(
                EventType.TEAM_DOC_CHANGED,
                {"path": rel, "title": None, "author": author, "deleted": True},
            )
        log.info("Document %s deleted by %s", rel, author or "the user")
        return rel

    async def move(
        self,
        source: str,
        target: str,
        *,
        author: str | None = None,
        expected_binding_generation: int | None = None,
    ) -> dict[str, object]:
        """Rename or move a document.

        Wiki links address documents by name, so a rename can break the links pointing at
        it. Rewriting every referrer automatically would edit files the user did not ask
        to touch, so the referrers are *reported* instead and the UI offers the fix. A folder
        rename does mend its links, for a reason that does not carry over to a document — see
        :meth:`rename_folder`.
        """
        admission = await self.transitions.admit(expected_binding_generation)
        try:
            async with admission.critical():
                source_path = self.resolve(source)
                if not source_path.is_file():
                    raise FileNotFoundError(f"No document {self.relative(source_path)!r}")
                target_path = self.resolve(target)
                if target_path.exists():
                    raise FileExistsError(f"{self.relative(target_path)!r} already exists")

                # Taken from the pre-move index on purpose: these are the documents pointing at the
                # name that is about to stop existing.
                referrers = self.backlinks(self.relative(source_path))
                target_path.parent.mkdir(parents=True, exist_ok=True)
                source_path.replace(target_path)

                self._prune_empty_folders(source_path.parent)
                self._invalidate()

                entry = self._entry(target_path)
                result = {
                    **entry.to_json(),
                    "moved_from": self.relative(source_path),
                    "referrers": referrers,
                }
        finally:
            admission.release()

        if self.bus is not None:
            await self.bus.publish(
                EventType.TEAM_DOC_CHANGED,
                {"path": entry.path, "title": entry.title, "author": author, "moved_from": source},
            )
        log.info("Document %s moved to %s by %s", source, entry.path, author or "the user")
        return result

    # ------------------------------------------------------------------ folders

    def migrate_folder_styles(self) -> None:
        """Rename legacy folder styles when opening the project (block-editor spec §5.2)."""
        if any(p.is_symlink() or p.is_junction() for p in (self.paths.data, self.directory)):
            return
        for folder, dirs, files in self.directory.walk():
            # Path.walk skips symlinks by default. On Windows a junction can still be a dir.
            dirs[:] = [name for name in dirs if not (folder / name).is_junction()]
            if LEGACY_FOLDER_META_NAME not in files:
                continue
            legacy = folder / LEGACY_FOLDER_META_NAME
            current = folder / FOLDER_META_NAME
            try:
                if not S_ISREG(legacy.lstat().st_mode):
                    continue
                if os.name == "nt":
                    legacy.rename(current)
                else:
                    # POSIX rename would overwrite a style created by another opener or editor.
                    current.hardlink_to(legacy)
                    legacy.unlink()
            except FileExistsError:
                log.warning("Keeping legacy folder style %s because %s already exists", legacy, current)
            except FileNotFoundError:
                continue  # The working copy changed during the scan.
            except OSError as exc:
                log.warning("Could not migrate folder style %s: %s", legacy, exc)

    def resolve_folder(self, relative: str) -> Path:
        """Map a caller-supplied folder path to an existing directory inside ``.voxarium_studio/docs``.

        The boundary :meth:`resolve` draws for a document, drawn for a directory: traversal and
        absolute paths are refused, and so is the docs root itself — it has no row in the tree,
        so there is nothing for a colour or a new name to be about.
        """
        raw = (relative or "").strip().replace("\\", "/").strip("/")
        if not raw:
            raise ValueError("A folder path is required")
        pure = PurePosixPath(raw)
        if pure.is_absolute() or ".." in pure.parts:
            raise PathOutsideProject(f"Illegal folder path: {relative!r}")
        candidate = _plain((self.directory / pure).resolve())
        directory = _plain(self.directory.resolve())
        if candidate == directory:
            raise ValueError("The documents folder itself is not a folder of the tree")
        if directory not in candidate.parents:
            raise PathOutsideProject(f"Illegal folder path: {relative!r}")
        if not candidate.is_dir():
            raise FileNotFoundError(f"No folder {raw!r}")
        return candidate

    def folders(self) -> list[dict[str, object]]:
        """Every folder with a look of its own, read from its ``vxs-folder.json`` (§5.2).

        A walk of its own rather than a field of the index: a style is not part of the corpus,
        and folding these files into the index's signature would re-read every document because
        somebody picked a colour. It costs one ``rglob`` for a single name, and a read of a file a
        few dozen bytes long per folder that has one.

        A file that does not parse, or names nothing this build can draw, is left out rather
        than failing the listing — that folder is drawn plainly, which is how it looks without
        one. Only a write refuses such a file (:meth:`set_folder_style`).
        """
        self.directory.mkdir(parents=True, exist_ok=True)
        root = _plain(self.directory.resolve())
        found: list[dict[str, object]] = []
        for meta in self.directory.rglob(FOLDER_META_NAME):
            try:
                if not meta.is_file():
                    continue
                # `relative_to` raising is a symlink out of the tree, which the index refuses too.
                relative = _plain(meta.parent.resolve()).relative_to(root).as_posix()
                style = _folder_style(relative, _read_folder_meta(meta))
            except (OSError, ValueError, FolderStyleUnreadable):
                continue
            if relative != "." and (style["color"] is not None or style["icon"] is not None):
                found.append(style)
        found.sort(key=lambda style: str(style["path"]))
        return found

    async def set_folder_style(
        self,
        relative: str,
        changes: dict[str, str | None],
        *,
        author: str | None = None,
        expected_binding_generation: int | None = None,
    ) -> dict[str, object]:
        """Change a folder's colour or icon, or give either back to the default.

        ``changes`` carries only the keys the caller sent, as every patch here does
        (``exclude_unset``): ``None`` takes a key back to the default and a key left out is left
        alone. Keys this build does not know are kept — the file is the folder's, and a newer
        Studio or a person may have put something there.

        Clearing the last key removes the file unless a legacy style would take its place
        on the next project open (block-editor spec §5.2).
        """
        admission = await self.transitions.admit(expected_binding_generation)
        try:
            async with admission.critical():
                folder = self.resolve_folder(relative)
                meta = folder / FOLDER_META_NAME
                stored = _read_folder_meta(meta)
                for key, value in changes.items():
                    if key == "color":
                        checked = None if value is None else _checked_folder_color(value)
                    elif key == "icon":
                        checked = None if value is None else _checked_icon_name(value)
                    else:
                        raise ValueError(f"A folder has no property {key!r}")
                    if checked is None:
                        stored.pop(key, None)
                    else:
                        stored[key] = checked

                if stored or (folder / LEGACY_FOLDER_META_NAME).is_file():
                    text = json.dumps(stored, indent=2, ensure_ascii=False) + "\n"
                    _write_atomically(meta, text.encode("utf-8"))
                else:
                    meta.unlink(missing_ok=True)

                style = _folder_style(self.relative(folder), stored)
        finally:
            admission.release()

        if self.bus is not None:
            await self.bus.publish(
                EventType.TEAM_DOC_FOLDER_CHANGED,
                {"path": style["path"], "previous_path": None, "author": author},
            )
        log.info("Folder %s restyled by %s", style["path"], author or "the user")
        return style

    async def rename_folder(
        self,
        relative: str,
        name: str,
        *,
        author: str | None = None,
        expected_binding_generation: int | None = None,
    ) -> FolderRename:
        """Rename a folder on disk, then mend every ``[[link]]`` the new name broke (§5.2).

        **A folder rename mends links, and a document rename does not** (:meth:`move`). The
        difference is what a link says. A document's name is the word its authors wrote, so
        renaming one and rewriting the prose that points at it would be the Studio editing other
        people's sentences. A folder is in a link only when the link spells out a path — which
        the ``[[`` menu writes just to tell two documents of one name apart — and swapping the
        start of such a path changes nothing anybody chose.

        What counts as broken is decided by resolution, never by searching for the old name:
        every link that resolved before the rename resolves to the *same document* after it. A
        path through the old folder is the common case. The rule also catches the rare one — a
        bare ``[[notes]]`` whose tie between two ``notes.md`` the new name reorders — and pins
        that link to the document it meant. A link that resolved to nothing is left alone.

        The folder goes first and the links after, on purpose. A directory rename is one call
        that happens or does not; rewriting the referrers first would, on a folder Windows
        refuses to rename, leave every link in the project pointing at a name that never came to
        exist. This order can at worst leave the one document whose file could not be written
        with its old link — and the frame for that document says so.

        Returns once the folder is renamed, with the mending running as a task of its own, so a
        client that stops listening does not stop it half-way through the referrers. Renames are
        serialised: a second one plans against a tree the first has finished mending.
        """
        admission = await self.transitions.admit(expected_binding_generation)
        folder_locked = False

        def release_folder() -> None:
            nonlocal folder_locked
            if folder_locked:
                folder_locked = False
                self._folder_lock.release()

        try:
            new_name = _checked_folder_name(name)
            await self._folder_lock.acquire()
            folder_locked = True

            def rename() -> tuple[FolderRename, Callable[[str], str | None]]:
                source = self.resolve_folder(relative)
                previous_path = self.relative(source)
                if new_name == source.name:
                    raise ValueError(f"{previous_path!r} is already called {new_name!r}")
                target = source.parent / new_name
                # `samefile` is the case-only rename on a case-insensitive filesystem: `Features`
                # "exists" when renaming `features`, and it is the folder being renamed.
                if target.exists() and not _same_file(source, target):
                    raise FileExistsError(f"{self.relative(target)!r} already exists")

                # Taken before the rename on purpose: these are the answers every link gave while
                # the old name still existed, which is what the mending has to preserve.
                before = self._index()
                _rename_directory_once(source, target)
                self._invalidate()
                after = self._index()
                path = self.relative(target)

                mend = _link_mender(before, after, previous_path, path)
                operation = FolderRename(
                    path=path,
                    previous_path=previous_path,
                    moved=sum(
                        1
                        for entry in before.entries
                        if _moved(entry.path, previous_path, path) != entry.path
                    ),
                    plan=self._plan_relinks(after, mend, previous_path, path),
                )
                operation._put(
                    {
                        "type": "renamed",
                        **operation.summary(),
                        "documents": [item.to_json() for item in operation.plan],
                    }
                )
                return operation, mend

            operation, mend = await self._folder_mutation(admission, rename)
            task = asyncio.create_task(self._mend(operation, mend, author, admission, release_folder))
        except BaseException:
            release_folder()
            admission.release()
            raise

        def completed(done: asyncio.Task[None]) -> None:
            # Also handles cancellation before the continuation entered its try/finally.
            release_folder()
            admission.release()
            self._mending.discard(done)
            if done.cancelled():
                operation._put(None)

        # Referenced until it ends: the event loop keeps only a weak reference to a task.
        self._mending.add(task)
        task.add_done_callback(completed)
        log.info(
            "Folder %s renamed to %s by %s",
            operation.previous_path, operation.path, author or "the user",
        )
        return operation

    def _plan_relinks(
        self,
        after: _DocIndex,
        mend: Callable[[str], str | None],
        previous_path: str,
        path: str,
    ) -> list[_Relink]:
        """Which documents hold a link *mend* would rewrite, found in the index's bodies."""
        plan: list[_Relink] = []
        for entry in after.entries:
            text = after.texts.get(entry.path)
            if text is None:
                if after.texts_cached:
                    continue  # the index could not open it either; nor can Backlinks count it
                try:
                    text = (self.directory / entry.path).read_text(
                        encoding="utf-8", errors="replace"
                    )
                except OSError:
                    continue
            _, links = _relinked(text, mend)
            if links:
                was = _moved(entry.path, path, previous_path)
                plan.append(
                    _Relink(
                        path=entry.path,
                        previous_path=was if was != entry.path else None,
                        title=entry.title,
                        links=links,
                    )
                )
        return plan

    async def _mend(
        self,
        operation: FolderRename,
        mend: Callable[[str], str | None],
        author: str | None,
        admission: MutationAdmission,
        release_folder: Callable[[], None],
    ) -> None:
        """Finish the admitted rename independently of its streaming consumer.

        Serialize all mending with other folder operations, but defer notifications until
        that lock is released: an awaited subscriber may itself rename/delete a folder.
        The logical admission remains owned until this whole continuation is finished.
        """
        updated = failed = links = 0
        changed_items: list[_Relink] = []
        try:
            try:
                for item in operation.plan:
                    frame: dict[str, object] = {"type": "document", **item.to_json()}
                    try:
                        async with admission.critical():
                            changed = _mend_file(self.directory / item.path, mend)
                            if changed:
                                self._invalidate()
                    except OSError as exc:
                        failed += 1
                        frame.update(status="failed", error=_write_failure(exc))
                    else:
                        links += changed
                        frame.update(
                            status="updated" if changed else "unchanged", links=changed, error=None
                        )
                        if changed:
                            updated += 1
                            changed_items.append(item)
                    operation._put(frame)
                    # Keep per-file progress without holding the mutation critical lock.
                    await asyncio.sleep(0)
            finally:
                self._invalidate()
                release_folder()

            # Preserve notification order, never holding either filesystem lock over publish.
            if self.bus is not None:
                with admission.publishing():
                    await self.bus.publish(
                        EventType.TEAM_DOC_FOLDER_CHANGED,
                        {
                            "path": operation.path,
                            "previous_path": operation.previous_path,
                            "author": author,
                        },
                    )
                    for item in changed_items:
                        await self.bus.publish(
                            EventType.TEAM_DOC_CHANGED,
                            {"path": item.path, "title": item.title, "author": author},
                        )
            operation._put(
                {
                    "type": "done",
                    **operation.summary(),
                    "updated": updated,
                    "links": links,
                    "failed": failed,
                }
            )
            log.info(
                "Mended %d link(s) in %d document(s) after renaming %s to %s; %d failed",
                links, updated, operation.previous_path, operation.path, failed,
            )
        except Exception as exc:
            log.exception("Mending the links into %s failed", operation.path)
            operation._put({"type": "error", "error": str(exc) or type(exc).__name__})
        finally:
            release_folder()
            admission.release()
            operation._put(None)

    async def delete_folder(
        self,
        relative: str,
        *,
        author: str | None = None,
        expected_binding_generation: int | None = None,
    ) -> dict[str, object]:
        """Delete a folder of the tree with everything in it (§5.2).

        Everything, not only its documents: the pictures beside them, its ``vxs-folder.json`` and
        whatever else somebody put there go too — a folder left holding pictures once its
        documents went would be a directory nobody can see, the reason
        :meth:`_prune_empty_folders` gives. Read-only files included, as the Explorer deletes
        them. Links into the folder are left as they are and resolve to nothing, as a link to a
        deleted document does: there is no document left for them to mean.

        **Moved aside, then removed.** ``rmtree`` on a folder something holds deletes what it
        reaches first and then fails — measured on Windows 11: with a file open inside, three of
        five documents gone and the rest left; with a terminal's working directory inside, every
        file gone and the empty folder left. A directory rename is refused whole in both cases, so
        the folder is first renamed to a hidden sibling, retried for half a second as a rename is,
        and a folder the system will not let go of loses nothing. Should the removal still fail
        after that, what is left goes back under its name, where the tree shows it.

        Serialised with renames, so neither plans against a tree the other is halfway through.
        Other clients hear one ``team.doc.folder_changed`` with ``deleted`` set — also after a
        removal that stopped part-way, since something went.
        """
        admission = await self.transitions.admit(expected_binding_generation)
        changed = False
        path = ""
        documents: list[str] = []
        try:
            async with self._folder_lock:
                def delete() -> dict[str, object]:
                    nonlocal changed, path, documents
                    folder = self.resolve_folder(relative)
                    path = self.relative(folder)
                    documents = [
                        entry.path for entry in self._index().entries if entry.path.startswith(f"{path}/")
                    ]
                    aside = folder.with_name(f".{folder.name}.deleting-{secrets.token_hex(4)}")
                    _rename_directory_once(folder, aside, doing="delete")
                    changed = True
                    try:
                        remove_path(aside)
                    except OSError as exc:
                        where = "back where it was" if put_back(aside, folder) else f"in {aside.name!r}"
                        raise OSError(
                            f"Could not delete everything in {path!r}: {exc.strerror or exc}. "
                            f"What is left is {where}."
                        ) from exc
                    else:
                        self._prune_empty_folders(folder.parent)
                    finally:
                        self._invalidate()
                    return {"path": path, "documents": documents}

                result = await self._folder_mutation(admission, delete)
        finally:
            admission.release()
            if changed and self.bus is not None:
                await self.bus.publish(
                    EventType.TEAM_DOC_FOLDER_CHANGED,
                    {"path": path, "previous_path": None, "author": author, "deleted": True},
                )
        log.info(
            "Folder %s deleted with %d document(s) by %s", path, len(documents), author or "the user"
        )
        return result

    async def _folder_mutation[T](
        self, admission: MutationAdmission, change: Callable[[], T]
    ) -> T:
        """Retry busy directory renames without sleeping under the common critical lock."""
        for attempt in range(1, RENAME_ATTEMPTS + 1):
            try:
                async with admission.critical():
                    return change()
            except FolderInUse:
                if attempt == RENAME_ATTEMPTS:
                    raise
                await asyncio.sleep(RENAME_RETRY_SECONDS)
        raise AssertionError("No directory rename attempt was made")

    def _prune_empty_folders(self, folder: Path) -> None:
        """Remove *folder* and every parent it leaves empty, stopping at the docs root.

        A folder holding nothing but its ``vxs-folder.json`` counts as empty. The look belongs
        to a folder of the tree, and a folder with no documents has no row to wear it: kept, it
        would be a directory nobody can see holding a file that turns up in the next commit.
        """
        roots = {self.directory, _plain(self.directory.resolve())}
        while folder not in roots and folder.is_dir():
            entries = list(folder.iterdir())
            if any(entry.name != FOLDER_META_NAME or not entry.is_file() for entry in entries):
                return
            try:
                for entry in entries:
                    entry.unlink()
                folder.rmdir()
            except OSError:
                # The document is already gone, which is what was asked; a folder left behind
                # because Windows holds it open is untidy, and failing the delete over it is wrong.
                log.warning("Could not remove the emptied folder %s", folder, exc_info=True)
                return
            folder = folder.parent

    # ------------------------------------------------------------------ internals

    def _entry(self, path: Path) -> DocEntry:
        stat = path.stat()
        return DocEntry(
            path=self.relative(path),
            title=_title_of(path),
            size_bytes=stat.st_size,
            updated_at=datetime.fromtimestamp(stat.st_mtime, tz=timezone.utc).isoformat(),
        )


def _resolve_against(
    target: str,
    by_path: dict[str, DocEntry],
    by_lowered: dict[str, str],
    by_title: dict[str, str],
) -> str | None:
    """The four resolution stages, over one index's maps.

    A free function so :meth:`DocStore.resolve_link` and the index builder cannot drift: the
    graph's edges and the link the UI follows have to be the same answer.
    """
    wanted = (target or "").strip().strip("/")
    if not wanted:
        return None
    if wanted in by_path:
        return wanted
    for suffix in SUFFIX_ORDER:
        if f"{wanted}{suffix}" in by_path:
            return f"{wanted}{suffix}"
    lowered = wanted.lower()
    hit = by_lowered.get(lowered)
    if hit is not None:
        return hit
    return by_title.get(lowered)


def _title_from_text(text: str | None, path: Path) -> str:
    """First Markdown heading, falling back to a readable form of the filename."""
    # ``split("\n")``, not ``splitlines()``: this replaced a loop of 20 ``readline()`` calls on
    # a text-mode handle, which breaks on "\n" alone once universal newlines have folded
    # "\r\n" and "\r" into it. ``splitlines()`` also breaks on \v, \f and U+2028, which would
    # move which lines fall inside the first twenty.
    for line in (text or "").split("\n")[:20]:
        stripped = line.strip()
        if stripped.startswith("#"):
            return stripped.lstrip("#").strip() or path.stem
    return path.stem.replace("-", " ").replace("_", " ").strip() or path.name


def _asset_filename(raw: str) -> str:
    """A browser-supplied filename reduced to something safe to create in a folder.

    Only the last component survives, so a name carrying a path — ``../../evil.png``, or the
    full local path some browsers still send — cannot place the file anywhere but beside the
    document. A leading dot is dropped as well: a hidden file is not what someone dragging a
    picture in meant, and it would be invisible in the folder they expect to find it in.

    **The component is taken before the characters are cleaned, not after.** Cleaning first
    turns the separators into dashes, and ``../../escape.png`` becomes the single legal name
    ``..-..-escape.png`` — which lands in the right folder but under a name nobody dropped.

    May answer with an empty string, which is the caller's cue to refuse: a name that has no
    suffix left says nothing about what the bytes are, and inventing ``image.png`` for it
    would put a file in the folder claiming to be a picture on no evidence at all.
    """
    name = PurePosixPath((raw or "").replace("\\", "/")).name
    # Windows also refuses a name ending in a space or a dot, and quietly trims them.
    return _ILLEGAL_NAME_CHARS.sub("-", name).strip().strip(".").rstrip(" .")


def _free_name(folder: Path, name: str) -> Path:
    """``shot.png``, or ``shot-2.png`` when that is taken — never an overwrite.

    Bounded rather than a ``while True``: a folder holding a thousand ``shot-N.png`` is a
    loop somewhere else, and falling back to the counter's own limit is better than spinning
    on ``exists()`` forever.
    """
    candidate = folder / name
    if not candidate.exists():
        return candidate
    stem, suffix = PurePosixPath(name).stem, PurePosixPath(name).suffix
    for index in range(2, 1000):
        candidate = folder / f"{stem}-{index}{suffix}"
        if not candidate.exists():
            return candidate
    raise FileExistsError(f"Too many files named like {name!r} beside this document")


def _write_atomically(path: Path, data: bytes) -> None:
    """Write beside *path* and move onto it, so nobody ever reads half a file (§7).

    The temporary file goes when the move fails: a read-only target, or one another program
    holds, would otherwise leave ``name.tmp`` in the user's folder and in their next commit.
    """
    tmp = path.with_name(f"{path.name}.tmp")
    try:
        tmp.write_bytes(data)
        tmp.replace(path)
    except OSError:
        tmp.unlink(missing_ok=True)
        raise


# ------------------------------------------------------------------ folder look


def _read_folder_meta(meta: Path) -> dict[str, object]:
    """The object in a ``vxs-folder.json``, or ``{}`` when the folder has none.

    Anything else — bytes that are not text, JSON that does not parse, a list — raises
    :class:`FolderStyleUnreadable`, so no write ever replaces a file it could not read. A
    byte-order mark is tolerated, since Notepad writes one.
    """
    where = f"{FOLDER_META_NAME} in {meta.parent.name!r}"
    try:
        text = meta.read_text(encoding="utf-8-sig")
    except FileNotFoundError:
        return {}
    except UnicodeDecodeError as exc:
        raise FolderStyleUnreadable(f"{where} is not UTF-8 text; fix or delete it") from exc
    try:
        stored = json.loads(text)
    except json.JSONDecodeError as exc:
        raise FolderStyleUnreadable(
            f"{where} does not parse (line {exc.lineno}: {exc.msg}); fix or delete it"
        ) from exc
    if not isinstance(stored, dict):
        raise FolderStyleUnreadable(f"{where} is not a JSON object; fix or delete it")
    return stored


def _folder_style(path: str, stored: dict[str, object]) -> dict[str, object]:
    """A folder's look on the wire: what this build can draw of *stored*, ``None`` for the rest."""
    color = stored.get("color")
    icon = stored.get("icon")
    if color == "grey":
        color = "gray"
    return {
        "path": path,
        "color": color if isinstance(color, str) and color in FOLDER_COLORS else None,
        "icon": icon if isinstance(icon, str) and _is_icon_name(icon) else None,
    }


def _is_icon_name(value: str) -> bool:
    return len(value) <= MAX_ICON_NAME and _ICON_NAME.fullmatch(value) is not None


def _checked_folder_color(value: str) -> str:
    color = "gray" if value == "grey" else value
    if color not in FOLDER_COLORS:
        raise ValueError(f"{value!r} is not a folder colour — one of {', '.join(FOLDER_COLORS)}")
    return color


def _checked_icon_name(value: str) -> str:
    if not _is_icon_name(value):
        raise ValueError(f"{value!r} is not an icon name — lower-case words joined by hyphens")
    return value


def _checked_folder_name(raw: str) -> str:
    """The name a folder is renamed to — refused, never repaired, if the tree cannot hold it.

    Refused where a dropped picture's name is cleaned (:func:`_asset_filename`), because here a
    person typed the name into a field that stays open to be corrected: renaming the folder to
    a cleaned-up version of it would be renaming it to something nobody asked for.
    """
    name = (raw or "").strip()
    if not name:
        raise ValueError("A folder name is required")
    if "/" in name or "\\" in name:
        raise ValueError(f"{name!r} is a name, not a path — it may not contain a separator")
    if name in (".", ".."):
        raise ValueError(f"{name!r} is not a folder name")
    illegal = sorted(set(_ILLEGAL_FOLDER_CHARS.findall(name)))
    if illegal:
        shown = " ".join(repr(char)[1:-1] for char in illegal)
        raise ValueError(f"A folder name may not contain {shown}")
    if name.endswith("."):
        # Windows drops a trailing dot without saying so, and the folder would not be called
        # what the response claims.
        raise ValueError("A folder name may not end in a dot")
    if name.split(".")[0].lower() in _RESERVED_FOLDER_NAMES:
        raise ValueError(f"{name!r} is reserved by Windows for a device")
    return name


def put_back(aside: Path, folder: Path) -> bool:
    """Whether what a failed delete left of a folder could go back under its own name.

    Public, with :func:`rename_directory`, because a skill's folder is deleted the same way
    (``SkillStore.delete``).
    """
    try:
        aside.rename(folder)
    except OSError:
        log.warning("Could not put back what was left of %s from %s", folder, aside, exc_info=True)
        return False
    return True


def _same_file(first: Path, second: Path) -> bool:
    try:
        return first.samefile(second)
    except OSError:
        return False


def _rename_directory_once(source: Path, target: Path, *, doing: str = "rename") -> None:
    try:
        source.rename(target)
    except PermissionError as exc:
        raise FolderInUse(
            f"Could not {doing} {source.name!r}: {exc.strerror or exc}. Something has "
            "the folder or a file inside it open — an Explorer window, an editor, a "
            "terminal. Close it and try again."
        ) from exc


async def rename_directory(source: Path, target: Path, *, doing: str = "rename") -> None:
    """Retry a busy directory rename; retained for non-DocStore callers (SkillStore).

    DocStore retries its whole planning/FS critical section instead, so Windows retry
    sleeps do not block unrelated document mutations and each attempt uses a fresh tree.
    """
    for attempt in range(1, RENAME_ATTEMPTS + 1):
        try:
            _rename_directory_once(source, target, doing=doing)
            return
        except FolderInUse:
            if attempt == RENAME_ATTEMPTS:
                raise
            await asyncio.sleep(RENAME_RETRY_SECONDS)


# ------------------------------------------------------------------ mending links


def _moved(path: str, folder: str, renamed: str) -> str:
    """*path* as it reads once *folder* is called *renamed*; unchanged outside it."""
    if path == folder:
        return renamed
    if path.startswith(f"{folder}/"):
        return f"{renamed}{path[len(folder):]}"
    return path


def _link_mender(
    before: _DocIndex, after: _DocIndex, folder: str, renamed: str
) -> Callable[[str], str | None]:
    """What a link's target has to become after the rename, or ``None`` if it still means the same.

    The function it returns takes the text between the brackets with its whitespace stripped —
    exactly what :meth:`DocStore._build` resolves — and answers with the replacement for that
    text less its slashes, which is the part :func:`_relinked` swaps. Memoised, because a corpus
    says ``[[features/dash]]`` in a dozen places and the answer is the same each time.
    """
    answers: dict[str, str | None] = {}

    def resolves(written: str, index: _DocIndex) -> str | None:
        return _resolve_against(written, index.by_path, index.by_lowered, index.by_title)

    def mend(written: str) -> str | None:
        if written in answers:
            return answers[written]
        answer: str | None = None
        was = resolves(written, before)
        if was is not None:
            meant = _moved(was, folder, renamed)
            # `meant` missing from the new index is a document deleted in between — there is
            # nothing left for the link to mean, so it is not rewritten to a dangling path.
            if meant in after.by_path and resolves(written, after) != meant:
                answer = _retarget(
                    written.strip("/"), meant, folder, renamed, lambda text: resolves(text, after)
                )
        answers[written] = answer
        return answer

    return mend


def _retarget(
    target: str,
    meant: str,
    folder: str,
    renamed: str,
    resolves: Callable[[str], str | None],
) -> str:
    """The least a broken target can change and mean *meant* again.

    1. The same text with the old folder at its start swapped for the new one — the author's
       spelling of the rest kept, suffix or not.
    2. The document's path without its suffix — what the ``[[`` menu writes for a name two
       documents share, so a pinned link reads like one written by hand.
    3. The exact path, which is the first thing resolution tries and so always means itself.
    """
    parts = target.split("/")
    old = folder.split("/")
    if len(parts) > len(old) and [part.lower() for part in parts[: len(old)]] == [
        part.lower() for part in old
    ]:
        swapped = "/".join([*renamed.split("/"), *parts[len(old) :]])
        if resolves(swapped) == meant:
            return swapped
    suffix = PurePosixPath(meant).suffix
    bare = meant[: -len(suffix)] if suffix else meant
    if resolves(bare) == meant:
        return bare
    return meant


def _relinked(text: str, mend: Callable[[str], str | None]) -> tuple[str, int]:
    """*text* with every link *mend* has an answer for rewritten, and how many that was.

    Matched a line at a time, exactly as :meth:`DocStore._build` reads links, so what gets
    rewritten is what the Backlinks panel counted — including a link inside a code block, which
    the index counts too. Only the target changes: the brackets, a label, spaces around the
    target and a leading ``/`` stay as the author wrote them, and so does the rest of the line.
    """
    count = 0

    def replace(match: re.Match[str]) -> str:
        nonlocal count
        inner = match.group(1)
        # What `_build` resolves; `_resolve_against` then strips the slashes, and the rest of
        # that — `target` — is the part a replacement takes the place of.
        stripped = inner.strip()
        target = stripped.strip("/")
        replacement = mend(stripped) if target else None
        if replacement is None:
            return match.group(0)
        count += 1
        start = (len(inner) - len(inner.lstrip())) + (len(stripped) - len(stripped.lstrip("/")))
        rebuilt = inner[:start] + replacement + inner[start + len(target) :]
        whole = match.group(0)
        offset = match.start(1) - match.start(0)
        return whole[:offset] + rebuilt + whole[offset + len(inner) :]

    relinked = "".join(WIKI_LINK.sub(replace, line) for line in text.splitlines(keepends=True))
    return relinked, count


def _mend_file(path: Path, mend: Callable[[str], str | None]) -> int:
    """Rewrite the links in one file that *mend* has an answer for; how many that was.

    Read as bytes and decoded with ``surrogateescape``, and encoded back the same way, so the
    only bytes that can differ are between the brackets: line endings are never translated, a
    byte-order mark stays, and a byte that is not UTF-8 goes back exactly as it came off the
    disk. The index's copy of the body is not used — it was read with newlines translated and
    bad bytes replaced, which is fine for finding links and would rewrite every line of a
    Windows-authored file on the way back.
    """
    text = path.read_bytes().decode("utf-8", errors="surrogateescape")
    relinked, links = _relinked(text, mend)
    if links:
        _write_atomically(path, relinked.encode("utf-8", errors="surrogateescape"))
    return links


def _write_failure(exc: OSError) -> str:
    reason = exc.strerror or str(exc) or type(exc).__name__
    if isinstance(exc, PermissionError):
        return f"{reason} — the file is read-only, or another program has it open"
    return reason


def _plain(path: Path) -> Path:
    """Strip Windows' extended-length prefix, so two resolved paths can be compared.

    ``Path.resolve`` is not guaranteed to spell a path the same way twice on Windows.
    ``ntpath.realpath`` asks the filesystem for the canonical name, gets it back with the
    ``\\\\?\\`` prefix, and drops that prefix only if a *second* query verifies the stripped
    form. The prefix therefore survives whenever the name stops existing between those two
    adjacent calls — and then the file resolves prefixed while the docs root beside it does
    not, so every comparison between them reads as "outside the tree": ``relative_to`` raises
    ``ValueError`` out of the scan and takes the whole Documents section down with a 500, and
    :meth:`DocStore.resolve` answers 403 on a path that is perfectly legal.

    Which operations actually open that window is worth stating, because the obvious guesses
    are wrong. Measured on this machine over 40,000 resolves each
    (``.scratch/perf/probe_resolve_prefix.py``): another process merely **holding the file
    open** gives 0 — CPython opens its verification handle with no access rights and a
    permissive share mode, so a lock fails neither call. An **atomic write-then-replace**
    gives 0 as well, which is what ``write`` below does, so the Studio's own saves are never
    the trigger. What does it is the name vanishing, even for microseconds: **renaming away
    and back, 11.7%**, and **delete-and-recreate, 5.8%**. That is a Plastic update or a
    branch switch rewriting the working copy — the case §9.6 already expects — and it is far
    too common to leave to chance.

    Only the spelling is normalised. A symlink genuinely pointing out of ``.voxarium_studio/docs``
    still resolves to somewhere outside it and is still refused, which is what the per-file
    ``resolve()`` in :meth:`DocStore._scan` is there for.
    """
    text = str(path)
    if text.startswith("\\\\?\\UNC\\"):
        return Path("\\\\" + text[8:])
    if text.startswith("\\\\?\\"):
        return Path(text[4:])
    return path


def _title_of(path: Path) -> str:
    """The title of one file, read on its own — what ``_entry`` uses off the live stat.

    A wrapper rather than a second implementation, so a cached title and a freshly computed
    one can never disagree.
    """
    try:
        return _title_from_text(path.read_text(encoding="utf-8", errors="replace"), path)
    except OSError:
        return _title_from_text(None, path)
