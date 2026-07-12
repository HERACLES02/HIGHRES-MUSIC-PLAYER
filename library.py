import os
import mimetypes
import threading
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional, Tuple

from flask import abort, request, Response
from mutagen import File as MutagenFile
from watchdog.events import FileSystemEventHandler
from watchdog.observers import Observer
from werkzeug.http import http_date, is_resource_modified
import hashlib
import re

MUSIC_DIR_ENV = os.environ.get("MUSIC_DIR")

if not MUSIC_DIR_ENV:
    raise RuntimeError("MUSIC_DIR must be set")

MUSIC_DIR = Path(MUSIC_DIR_ENV).expanduser().resolve()
if not MUSIC_DIR.exists() or not MUSIC_DIR.is_dir():
    raise RuntimeError(f"MUSIC_DIR is not set correctly. Current value: {MUSIC_DIR}")

AUDIO_EXTS = {".mp3", ".flac", ".m4a", ".aac", ".ogg", ".wav"}
mimetypes.add_type("audio/flac", ".flac")

@dataclass
class Track:
    id: str
    relpath: str
    title: str
    artist: str
    album: str
    folder: str
    cover_mime: Optional[str] = None
    url: Optional[str] = None
    cover_url: Optional[str] = None

TRACKS: list[Track] = []
COVERS: dict[str, bytes] = {}
TRACK_BY_ID: dict[str, Track] = {}

LYRIC_TAGS = (
    "lyrics",
    "LYRICS",
    "unsyncedlyrics",
    "UNSYNCEDLYRICS",
    "syncedlyrics",
    "SYNCEDLYRICS",
    "\xa9lyr",
)


def _first_text(value, default=""):
    if value is None:
        return default
    if isinstance(value, (list, tuple)):
        return str(value[0]) if value else default
    return str(value)

def stable_id_for_relpath(relpath: str) -> str:
    return hashlib.sha1(relpath.encode("utf-8")).hexdigest()

def _extract_cover(mut) -> Tuple[Optional[str], Optional[bytes]]:
    if mut is None:
        return None, None

    pics = getattr(mut, "pictures", None)
    if pics:
        pic = pics[0]
        mime = getattr(pic, "mime", None) or "image/jpeg"
        data = getattr(pic, "data", None)
        if data:
            return mime, data

    tags = getattr(mut, "tags", None)
    if tags and hasattr(tags, "getall"):
        apic = tags.getall("APIC")
        if apic:
            return apic[0].mime or "image/jpeg", apic[0].data

    return None, None

def _tag_value(tags, *keys, default=""):
    if not tags:
        return default

    for k in keys:
        try:
            v = tags.get(k) if hasattr(tags, "get") else None
        except Exception:
            v = None
        if v is not None:
            return _first_text(v, default)

    try:
        if isinstance(tags, dict):
            lower_map = {str(k).lower(): k for k in tags.keys()}
            for k in keys:
                real = lower_map.get(str(k).lower())
                if real is None:
                    continue
                v = tags.get(real)
                if v is not None:
                    return _first_text(v, default)
    except Exception:
        pass

    return default

def _all_tag_values(tags, *keys) -> list[str]:
    values: list[str] = []
    if not tags:
        return values

    for key in keys:
        try:
            raw = tags.get(key) if hasattr(tags, "get") else None
        except Exception:
            raw = None
        if raw is None:
            continue
        if isinstance(raw, (list, tuple)):
            values.extend(str(item) for item in raw if item)
        else:
            values.append(str(raw))

    try:
        if isinstance(tags, dict):
            lower_map = {str(k).lower(): k for k in tags.keys()}
            for key in keys:
                real = lower_map.get(str(key).lower())
                if real is None:
                    continue
                raw = tags.get(real)
                if isinstance(raw, (list, tuple)):
                    values.extend(str(item) for item in raw if item)
                elif raw:
                    values.append(str(raw))
    except Exception:
        pass

    return [v for v in values if v.strip()]

def _read_tags(file_path: Path) -> Tuple[str, str, str, Optional[str], Optional[bytes]]:
    try:
        mut = MutagenFile(file_path)
    except Exception:
        # A single corrupt/truncated/zero-byte audio file must not take down
        # the whole library scan - fall back to filename-derived metadata.
        mut = None
    title = file_path.stem
    artist = "Unknown Artist"
    album = "Unknown Album"

    tags = getattr(mut, "tags", None) if mut is not None else None

    title = _tag_value(tags, "TIT2", default=title)
    artist = _tag_value(tags, "TPE1", default=artist)
    album = _tag_value(tags, "TALB", default=album)

    title = _tag_value(tags, "title", "TITLE", default=title)
    artist = _tag_value(tags, "artist", "ARTIST", default=artist)
    album = _tag_value(tags, "album", "ALBUM", default=album)

    cover_mime, cover_bytes = _extract_cover(mut)
    return title, artist, album, cover_mime, cover_bytes


_REFRESH_LOCK = threading.Lock()


def refresh_library() -> None:
    # Guards against two scans racing each other - previously only possible
    # if a user double-clicked "Refresh library", now also possible because
    # the background file-watcher can trigger a refresh at any time,
    # including while a manual refresh or metadata-edit refresh is running.
    with _REFRESH_LOCK:
        _refresh_library_unlocked()


def _refresh_library_unlocked() -> None:
    global TRACKS, COVERS, TRACK_BY_ID

    tracks: list[Track] = []
    covers: dict[str, bytes] = {}
    track_by_id: dict[str, Track] = {}

    audio_files = sorted(
        [p for p in MUSIC_DIR.rglob("*") if p.is_file() and p.suffix.lower() in AUDIO_EXTS]
    )

    for  p in audio_files:
        title, artist, album, cover_mime, cover_bytes = _read_tags(p)
        rel = str(p.relative_to(MUSIC_DIR)).replace("\\", "/")
        folder = str(p.parent.relative_to(MUSIC_DIR)).replace("\\", "/")
        if folder == ".":
            folder = ""
        tid = stable_id_for_relpath(rel)

        
        t = Track(
                id=tid,
                relpath=rel,
                title=title,
                artist=artist,
                album=album,
                folder=folder,
                cover_mime=cover_mime,
            )
        tracks.append(t)
        track_by_id[tid] = t

        if cover_bytes and cover_mime:
            covers[tid] = cover_bytes

    TRACKS = tracks
    COVERS = covers
    TRACK_BY_ID = track_by_id


WATCH_LIBRARY = os.environ.get("WATCH_LIBRARY", "1") == "1"
WATCH_DEBOUNCE_SECONDS = float(os.environ.get("WATCH_DEBOUNCE_SECONDS", "2"))

_watch_observer: Optional[Observer] = None
_watch_debounce_timer: Optional[threading.Timer] = None
_watch_debounce_lock = threading.Lock()


class _LibraryChangeHandler(FileSystemEventHandler):
    """Coalesces filesystem activity under MUSIC_DIR into a single debounced
    refresh_library() call, so copying a whole album (many rapid-fire
    create events) triggers one rescan instead of dozens."""

    def _is_relevant(self, event) -> bool:
        if event.is_directory:
            return True
        paths = [event.src_path]
        dest_path = getattr(event, "dest_path", None)
        if dest_path:
            paths.append(dest_path)
        return any(Path(p).suffix.lower() in AUDIO_EXTS for p in paths)

    def on_any_event(self, event):
        if event.event_type == "opened" or event.event_type == "closed":
            return
        if self._is_relevant(event):
            _schedule_debounced_refresh()


def _schedule_debounced_refresh() -> None:
    global _watch_debounce_timer
    with _watch_debounce_lock:
        if _watch_debounce_timer is not None:
            _watch_debounce_timer.cancel()
        _watch_debounce_timer = threading.Timer(WATCH_DEBOUNCE_SECONDS, refresh_library)
        _watch_debounce_timer.daemon = True
        _watch_debounce_timer.start()


def start_watching() -> bool:
    """Best-effort: watching new files is a convenience, not core
    functionality, so a platform/filesystem that can't be watched (e.g. some
    network shares) should log and continue rather than crash the app."""
    global _watch_observer
    if not WATCH_LIBRARY or _watch_observer is not None:
        return _watch_observer is not None

    try:
        observer = Observer()
        observer.schedule(_LibraryChangeHandler(), str(MUSIC_DIR), recursive=True)
        observer.daemon = True
        observer.start()
    except Exception as exc:
        print(f"Library file-watcher could not start ({exc}); auto-detection of new files is disabled.")
        return False

    _watch_observer = observer
    return True


def stop_watching() -> None:
    global _watch_observer
    if _watch_observer is None:
        return
    _watch_observer.stop()
    _watch_observer.join(timeout=5)
    _watch_observer = None


def is_watching() -> bool:
    return _watch_observer is not None


def folders() -> list[dict]:
    grouped: dict[str, int] = {}
    for track in TRACKS:
        folder = track.folder or "(root)"
        grouped[folder] = grouped.get(folder, 0) + 1
    return [
        {"name": folder, "count": count}
        for folder, count in sorted(grouped.items(), key=lambda item: item[0].lower())
    ]


def _track_path(track_id: str) -> Path:
    track = TRACK_BY_ID.get(track_id)
    if not track:
        abort(404, "Track not found")
    return safe_resolve(track.relpath)


def _external_lrc_path(file_path: Path) -> Optional[Path]:
    candidate = file_path.with_suffix(".lrc")
    if candidate.exists() and candidate.is_file():
        return candidate
    return None


def lyrics_for_track(track_id: str) -> dict:
    file_path = _track_path(track_id)
    lrc_path = _external_lrc_path(file_path)

    if lrc_path:
        text = lrc_path.read_text(encoding="utf-8-sig", errors="replace")
        return {
            "ok": True,
            "source": "lrc",
            "synced": bool(re.search(r"\[\d{1,2}:\d{2}(?:\.\d{1,3})?\]", text)),
            "lyrics": text,
        }

    try:
        mut = MutagenFile(file_path)
    except Exception:
        mut = None
    tags = getattr(mut, "tags", None) if mut is not None else None

    if tags and hasattr(tags, "getall"):
        uslt = tags.getall("USLT")
        if uslt:
            text = "\n".join(str(getattr(item, "text", "")).strip() for item in uslt if getattr(item, "text", ""))
            if text.strip():
                return {"ok": True, "source": "embedded", "synced": False, "lyrics": text}

    for value in _all_tag_values(tags, *LYRIC_TAGS):
        return {
            "ok": True,
            "source": "embedded",
            "synced": bool(re.search(r"\[\d{1,2}:\d{2}(?:\.\d{1,3})?\]", value)),
            "lyrics": value,
        }

    return {"ok": True, "source": None, "synced": False, "lyrics": ""}


def save_lrc_for_track(track_id: str, text: str) -> dict:
    file_path = _track_path(track_id)
    lrc_path = file_path.with_suffix(".lrc")
    lrc_path.write_text(text.replace("\r\n", "\n").replace("\r", "\n"), encoding="utf-8")
    return lyrics_for_track(track_id)


def metadata_for_track(track_id: str) -> dict:
    track = TRACK_BY_ID.get(track_id)
    if not track:
        abort(404, "Track not found")
    return {
        "id": track.id,
        "relpath": track.relpath,
        "folder": track.folder,
        "title": track.title,
        "artist": track.artist,
        "album": track.album,
    }


def derived_metadata_for_track(track_id: str) -> dict:
    track = TRACK_BY_ID.get(track_id)
    if not track:
        abort(404, "Track not found")
    file_path = _track_path(track_id)
    relative_parent = file_path.parent.relative_to(MUSIC_DIR)
    parts = list(relative_parent.parts)
    title = re.sub(r"^\s*\d+[\s._-]+", "", file_path.stem).strip() or file_path.stem
    album = parts[-1] if parts else track.album
    artist = parts[-2] if len(parts) >= 2 else track.artist
    return {
        "title": title,
        "artist": artist or "Unknown Artist",
        "album": album or "Unknown Album",
    }


def update_metadata(track_id: str, title: str, artist: str, album: str) -> dict:
    file_path = _track_path(track_id)
    try:
        audio = MutagenFile(file_path, easy=True)
    except Exception as exc:
        raise ValueError(f"This file could not be read for metadata editing: {exc}") from exc
    if audio is None:
        raise ValueError("This file type is not supported for metadata editing")

    if audio.tags is None:
        try:
            audio.add_tags()
        except Exception:
            pass

    next_title = title.strip() or file_path.stem
    next_artist = artist.strip() or "Unknown Artist"
    next_album = album.strip() or "Unknown Album"
    audio["title"] = [next_title]
    audio["artist"] = [next_artist]
    audio["album"] = [next_album]
    audio.save()

    # Title/artist/album are the only fields this touches, and they don't
    # affect folder grouping, relpath, or cover art - so the in-memory Track
    # can be patched directly instead of paying for a full MUSIC_DIR rescan
    # (previously every single-track edit re-walked and re-tagged the whole
    # library just to pick up the one field we already know we just wrote).
    track = TRACK_BY_ID.get(track_id)
    if track is not None:
        track.title = next_title
        track.artist = next_artist
        track.album = next_album

    return metadata_for_track(track_id)


def update_metadata_fields(track_id: str, fields: dict) -> dict:
    current = metadata_for_track(track_id)
    return update_metadata(
        track_id,
        str(fields.get("title", current["title"])),
        str(fields.get("artist", current["artist"])),
        str(fields.get("album", current["album"])),
    )


def safe_resolve(relpath: str) -> Path:
    if MUSIC_DIR is None:
        abort(404)

    relpath = relpath.lstrip("/").replace("\\", "/")
    target = (MUSIC_DIR / relpath).resolve()
    if MUSIC_DIR not in target.parents and target != MUSIC_DIR:
        abort(403)
    return target

AUDIO_CACHE_MAX_AGE_SECONDS = int(os.environ.get("AUDIO_CACHE_MAX_AGE_SECONDS", str(24 * 3600)))


def _set_cache_headers(resp: Response, etag: str, mtime: float) -> None:
    resp.headers["ETag"] = etag
    resp.headers["Last-Modified"] = http_date(mtime)
    resp.headers["Cache-Control"] = f"private, max-age={AUDIO_CACHE_MAX_AGE_SECONDS}"


def range_response(file_path: Path, mime: str) -> Response:
    stat = file_path.stat()
    file_size = stat.st_size
    # A weak identity for the file's current content, cheap to compute (no
    # hashing/reading needed) and stable as long as the file is untouched -
    # lets the browser skip re-downloading/re-streaming audio it already has.
    etag = f"{int(stat.st_mtime_ns):x}-{file_size:x}"
    last_modified_dt = datetime.fromtimestamp(stat.st_mtime, tz=timezone.utc)
    range_header = request.headers.get("Range", None)

    if not range_header:
        if not is_resource_modified(request.environ, etag=etag, last_modified=last_modified_dt):
            resp = Response(status=304)
            _set_cache_headers(resp, etag, stat.st_mtime)
            return resp

        def generate():
            with open(file_path, "rb") as f:
                while True:
                    chunk = f.read(1024 * 1024)
                    if not chunk:
                        break
                    yield chunk

        resp = Response(generate(), mimetype=mime, direct_passthrough=True)
        resp.headers["Content-Length"] = str(file_size)
        resp.headers["Accept-Ranges"] = "bytes"
        _set_cache_headers(resp, etag, stat.st_mtime)
        return resp

    try:
        units, rng = range_header.split("=", 1)
        if units.strip() != "bytes":
            abort(416)
        start_s, end_s = (rng.split("-", 1) + [""])[:2]
        start = int(start_s) if start_s else 0
        end = int(end_s) if end_s else file_size - 1
    except Exception:
        abort(416)

    if start < 0 or end < start or end >= file_size:
        abort(416)

    length = end - start + 1

    def generate():
        with open(file_path, "rb") as f:
            f.seek(start)
            remaining = length
            while remaining > 0:
                chunk = f.read(min(1024 * 1024, remaining))
                if not chunk:
                    break
                remaining -= len(chunk)
                yield chunk

    resp = Response(generate(), status=206, mimetype=mime, direct_passthrough=True)
    resp.headers["Content-Range"] = f"bytes {start}-{end}/{file_size}"
    resp.headers["Content-Length"] = str(length)
    resp.headers["Accept-Ranges"] = "bytes"
    _set_cache_headers(resp, etag, stat.st_mtime)
    return resp
