import atexit
import contextvars
import functools
import json
import os
import re
import threading
import uuid
import hashlib
import shutil
from datetime import datetime, timezone
from pathlib import Path

from werkzeug.security import check_password_hash, generate_password_hash

PLAYLIST_FILE = Path(
    os.environ.get("PLAYLIST_FILE", Path(__file__).with_name("playlist.json"))
).expanduser().resolve()
ARTWORK_DIR = Path(
    os.environ.get("ARTWORK_DIR", PLAYLIST_FILE.with_name("artwork"))
).expanduser().resolve()

DEFAULT_PLAYLIST_ID = "default"
USERNAME_PATTERN = re.compile(r"^[a-zA-Z0-9_.-]{3,32}$")
MIN_PASSWORD_LENGTH = 8

# Users and their per-user data (likes/playlists/history). Library-level
# enrichment (folder/track cover overrides, custom background, cached artist
# bios) is intentionally NOT per-user - it describes the shared music
# library itself, not any one person's taste.
USERS: dict[str, dict] = {}
USER_DATA: dict[str, dict] = {}
FOLDER_COVERS: dict[str, str] = {}
TRACK_COVERS: dict[str, str] = {}
BACKGROUND_MEDIA: str | None = None
ARTIST_INFO: dict[str, dict] = {}

# Flask's dev/prod server runs multi-threaded, and all state above is plain
# module globals mutated directly by request handlers. Without this lock,
# two concurrent requests (e.g. two browser tabs liking a track and updating
# playback position at the same time) can interleave their read-modify-write
# of these dicts/sets and silently drop one of the updates on save.
_STATE_LOCK = threading.RLock()

# Which user "playlist.LIKES" / "PLAYLISTS" / etc. resolve to for the
# duration of the current request - set once per request from the session
# username (see app.py's before_request hook), read implicitly by every
# per-user function below via _bucket()/__getattr__.
_current_username: contextvars.ContextVar[str] = contextvars.ContextVar("current_username", default="")


def _locked(fn):
    @functools.wraps(fn)
    def wrapper(*args, **kwargs):
        with _STATE_LOCK:
            return fn(*args, **kwargs)

    return wrapper


class PlaylistError(Exception):
    pass


class TrackNotFound(PlaylistError):
    pass


class AlreadyInPlaylist(PlaylistError):
    pass


class NotInPlaylist(PlaylistError):
    pass


class PlaylistNotFound(PlaylistError):
    pass


class DuplicatePlaylist(PlaylistError):
    pass


class UserError(PlaylistError):
    pass


def set_current_user(username: str) -> None:
    _current_username.set(username or "")


def get_current_user() -> str:
    return _current_username.get()


def _track_exists(tracks, track_id: str) -> bool:
    return any(t.id == track_id for t in tracks)


def _slug(value: str) -> str:
    slug = re.sub(r"[^a-zA-Z0-9_-]+", "-", value.strip().lower()).strip("-")
    return slug or uuid.uuid4().hex[:12]


def _default_playlist() -> dict:
    return {"id": DEFAULT_PLAYLIST_ID, "name": "My Playlist", "tracks": [], "cover": None}


def _new_user_bucket() -> dict:
    return {"likes": set(), "playlists": {DEFAULT_PLAYLIST_ID: _default_playlist()}, "history": {}}


def _bucket(username: str | None = None) -> dict:
    username = username or get_current_user()
    if not username:
        raise PlaylistError("No authenticated user in context")
    bucket = USER_DATA.setdefault(username, _new_user_bucket())
    bucket["playlists"].setdefault(DEFAULT_PLAYLIST_ID, _default_playlist())
    return bucket


def __getattr__(name):
    # Lets existing/external code read `playlist.LIKES` / `.PLAYLISTS` /
    # `.HISTORY` as if they were plain globals, transparently resolved to
    # whichever user is current in this request (PEP 562 module __getattr__).
    if name == "LIKES":
        return _bucket()["likes"]
    if name == "PLAYLISTS":
        return _bucket()["playlists"]
    if name == "HISTORY":
        return _bucket()["history"]
    raise AttributeError(f"module {__name__!r} has no attribute {name!r}")


def _clean_track_list(raw) -> list[str]:
    if not isinstance(raw, list):
        return []
    cleaned = []
    seen = set()
    for item in raw:
        tid = str(item)
        if tid not in seen:
            cleaned.append(tid)
            seen.add(tid)
    return cleaned


def _clean_playlists(raw_playlists) -> dict:
    if isinstance(raw_playlists, dict):
        raw_playlists = raw_playlists.values()
    playlists = {}
    if isinstance(raw_playlists, list):
        for item in raw_playlists:
            if not isinstance(item, dict):
                continue
            name = str(item.get("name") or "Playlist").strip() or "Playlist"
            pid = str(item.get("id") or _slug(name))
            playlists[pid] = {
                "id": pid,
                "name": name,
                "tracks": _clean_track_list(item.get("tracks", [])),
                "cover": item.get("cover"),
            }
    playlists.setdefault(DEFAULT_PLAYLIST_ID, _default_playlist())
    return playlists


def _legacy_to_v2(data: dict) -> dict:
    return {
        "version": 2,
        "likes": [],
        "playlists": [
            {
                "id": DEFAULT_PLAYLIST_ID,
                "name": "My Playlist",
                "tracks": _clean_track_list(data.get("tracks", [])),
                "cover": None,
            }
        ],
        "folder_covers": {},
        "track_covers": {},
        "background_media": None,
        "history": {},
        "artist_info": {},
    }


def _migrate_v2_to_v3(data: dict) -> dict:
    admin_username = os.environ.get("ADMIN_USERNAME", "")
    admin_password_hash = os.environ.get("ADMIN_PASSWORD_HASH", "")
    users = {}
    user_data = {}
    if admin_username and admin_password_hash:
        users[admin_username] = {
            "password_hash": admin_password_hash,
            "is_admin": True,
            "created_at": utc_now(),
        }
        user_data[admin_username] = {
            "likes": _clean_track_list(data.get("likes", [])),
            "playlists": list(_clean_playlists(data.get("playlists", [])).values()),
            "history": {
                str(k): v for k, v in (data.get("history", {}) or {}).items() if isinstance(v, dict)
            },
        }
    return {
        "version": 3,
        "users": users,
        "user_data": user_data,
        "folder_covers": data.get("folder_covers", {}) or {},
        "track_covers": data.get("track_covers", {}) or {},
        "background_media": data.get("background_media"),
        "artist_info": data.get("artist_info", {}) or {},
    }


def _ensure_bootstrap_admin() -> None:
    """Guarantee ADMIN_USERNAME always exists and is an admin, seeded from
    ADMIN_PASSWORD_HASH if not already present. This is the break-glass
    account: it's recreated if missing (never permanently lockable by
    deleting/mismanaging users) but its password_hash is only ever *set*
    here, never overwritten, so changing it through the app afterward
    sticks across restarts."""
    admin_username = os.environ.get("ADMIN_USERNAME")
    admin_password_hash = os.environ.get("ADMIN_PASSWORD_HASH")
    if not admin_username or not admin_password_hash:
        return
    if admin_username not in USERS:
        USERS[admin_username] = {
            "password_hash": admin_password_hash,
            "is_admin": True,
            "created_at": utc_now(),
        }
    else:
        USERS[admin_username]["is_admin"] = True


def _load_v3(data: dict) -> None:
    global USERS, USER_DATA, FOLDER_COVERS, TRACK_COVERS, BACKGROUND_MEDIA, ARTIST_INFO

    users = {}
    for username, info in (data.get("users", {}) or {}).items():
        if not isinstance(info, dict) or not info.get("password_hash"):
            continue
        users[str(username)] = {
            "password_hash": str(info["password_hash"]),
            "is_admin": bool(info.get("is_admin", False)),
            "created_at": str(info.get("created_at") or utc_now()),
        }
    USERS = users

    user_data = {}
    for username, bucket in (data.get("user_data", {}) or {}).items():
        if not isinstance(bucket, dict):
            continue
        user_data[str(username)] = {
            "likes": set(_clean_track_list(bucket.get("likes", []))),
            "playlists": _clean_playlists(bucket.get("playlists", [])),
            "history": {
                str(k): v for k, v in (bucket.get("history", {}) or {}).items() if isinstance(v, dict)
            },
        }
    USER_DATA = user_data

    FOLDER_COVERS = {str(k): str(v) for k, v in (data.get("folder_covers", {}) or {}).items() if v}
    TRACK_COVERS = {str(k): str(v) for k, v in (data.get("track_covers", {}) or {}).items() if v}
    BACKGROUND_MEDIA = str(data.get("background_media")) if data.get("background_media") else None
    ARTIST_INFO = {
        str(k): v for k, v in (data.get("artist_info", {}) or {}).items() if isinstance(v, dict)
    }

    _ensure_bootstrap_admin()


@_locked
def load_playlist():
    if not PLAYLIST_FILE.exists():
        _load_v3({})
        return

    try:
        with open(PLAYLIST_FILE, "r", encoding="utf-8") as f:
            data = json.load(f)
    except Exception:
        _load_v3({})
        return

    if not isinstance(data, dict):
        _load_v3({})
        return

    if data.get("version") == 3 or "users" in data:
        _load_v3(data)
    elif "playlists" in data or "likes" in data:
        _load_v3(_migrate_v2_to_v3(data))
    else:
        _load_v3(_migrate_v2_to_v3(_legacy_to_v2(data)))


@_locked
def save_playlist():
    for bucket in USER_DATA.values():
        bucket["playlists"].setdefault(DEFAULT_PLAYLIST_ID, _default_playlist())

    data = {
        "version": 3,
        "users": USERS,
        "user_data": {
            username: {
                "likes": sorted(bucket["likes"]),
                "playlists": list(bucket["playlists"].values()),
                "history": bucket["history"],
            }
            for username, bucket in USER_DATA.items()
        },
        "folder_covers": FOLDER_COVERS,
        "track_covers": TRACK_COVERS,
        "background_media": BACKGROUND_MEDIA,
        "artist_info": ARTIST_INFO,
    }
    PLAYLIST_FILE.parent.mkdir(parents=True, exist_ok=True)
    tmp = PLAYLIST_FILE.with_suffix(".json.tmp")
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2)
        f.flush()
        os.fsync(f.fileno())
    tmp.replace(PLAYLIST_FILE)


SAVE_DEBOUNCE_SECONDS = float(os.environ.get("SAVE_DEBOUNCE_SECONDS", "5"))
_pending_save_timer: threading.Timer | None = None


def _schedule_debounced_save() -> None:
    """Playback position/play-count updates fire every few seconds during
    normal listening. Writing + fsyncing the *entire* state file on every one
    of those is unnecessary I/O for data that's just resume bookkeeping -
    coalesce many rapid calls into a single flush a few seconds later.
    Correctness-critical mutations (likes, playlists, artist info, covers)
    are unaffected and still call save_playlist() directly and immediately.
    """
    global _pending_save_timer
    with _STATE_LOCK:
        if _pending_save_timer is not None:
            return
        timer = threading.Timer(SAVE_DEBOUNCE_SECONDS, _flush_debounced_save)
        timer.daemon = True
        _pending_save_timer = timer
        timer.start()


def _flush_debounced_save() -> None:
    global _pending_save_timer
    with _STATE_LOCK:
        _pending_save_timer = None
    save_playlist()


def flush_pending_save() -> None:
    """Force any pending debounced save to happen immediately - used on
    clean process shutdown so the last few seconds of playback position
    aren't lost, and available for tests that need synchronous behavior."""
    global _pending_save_timer
    with _STATE_LOCK:
        timer = _pending_save_timer
        _pending_save_timer = None
    if timer is not None:
        timer.cancel()
        save_playlist()


atexit.register(flush_pending_save)


# ---------------------------------------------------------------------------
# Users
# ---------------------------------------------------------------------------

def verify_user(username: str, password: str) -> bool:
    user = USERS.get(username)
    if user and check_password_hash(user["password_hash"], password):
        return True
    # Break-glass: the bootstrap admin can always also authenticate with
    # whatever ADMIN_PASSWORD_HASH is currently configured in the
    # environment, even after changing their password through the app -
    # this prevents ever being permanently locked out of your own data.
    if username == os.environ.get("ADMIN_USERNAME") and os.environ.get("ADMIN_PASSWORD_HASH"):
        if check_password_hash(os.environ["ADMIN_PASSWORD_HASH"], password):
            return True
    return False


def user_exists(username: str) -> bool:
    return username in USERS


def is_admin(username: str) -> bool:
    user = USERS.get(username)
    return bool(user and user["is_admin"])


def list_users() -> list[dict]:
    return [
        {"username": name, "is_admin": info["is_admin"], "created_at": info["created_at"]}
        for name, info in sorted(USERS.items())
    ]


def _validate_password(password: str) -> None:
    if not password or len(password) < MIN_PASSWORD_LENGTH:
        raise UserError(f"Password must be at least {MIN_PASSWORD_LENGTH} characters")


@_locked
def create_user(username: str, password: str, make_admin: bool = False) -> dict:
    username = (username or "").strip()
    if not USERNAME_PATTERN.match(username):
        raise UserError("Username must be 3-32 characters (letters, numbers, _ . -)")
    if username in USERS:
        raise UserError("Username already exists")
    _validate_password(password)

    USERS[username] = {
        "password_hash": generate_password_hash(password),
        "is_admin": bool(make_admin),
        "created_at": utc_now(),
    }
    save_playlist()
    return {"username": username, "is_admin": bool(make_admin), "created_at": USERS[username]["created_at"]}


@_locked
def delete_user(username: str) -> None:
    if username not in USERS:
        raise UserError("User not found")
    if username == os.environ.get("ADMIN_USERNAME"):
        raise UserError("The bootstrap admin account cannot be deleted")
    remaining_admins = [u for u, info in USERS.items() if info["is_admin"] and u != username]
    if USERS[username]["is_admin"] and not remaining_admins:
        raise UserError("Cannot delete the last remaining admin")
    del USERS[username]
    USER_DATA.pop(username, None)
    save_playlist()


@_locked
def set_password(username: str, password: str) -> None:
    if username not in USERS:
        raise UserError("User not found")
    _validate_password(password)
    USERS[username]["password_hash"] = generate_password_hash(password)
    save_playlist()


# ---------------------------------------------------------------------------
# Per-user playlists / likes / history
# ---------------------------------------------------------------------------

@_locked
def snapshot():
    bucket = _bucket()
    username = get_current_user()
    return {
        "username": username,
        "is_admin": is_admin(username),
        "likes": sorted(bucket["likes"]),
        "background_url": background_url(),
        "history": history_snapshot(bucket["history"]),
        "artist_info": ARTIST_INFO,
        "playlists": [
            {
                "id": p["id"],
                "name": p["name"],
                "count": len(p["tracks"]),
                "tracks": list(p["tracks"]),
                "cover_url": artwork_url("playlist", p["id"], p.get("cover")),
            }
            for p in bucket["playlists"].values()
        ],
        "count": len(bucket["playlists"][DEFAULT_PLAYLIST_ID]["tracks"]),
        "tracks": list(bucket["playlists"][DEFAULT_PLAYLIST_ID]["tracks"]),
    }


@_locked
def like(track_id: str, tracks):
    if not _track_exists(tracks, track_id):
        raise TrackNotFound()
    _bucket()["likes"].add(track_id)
    save_playlist()


@_locked
def unlike(track_id: str):
    _bucket()["likes"].discard(track_id)
    save_playlist()


@_locked
def create_playlist(name: str):
    name = (name or "").strip()
    if not name:
        raise ValueError("Playlist name is required")

    playlists = _bucket()["playlists"]
    base = _slug(name)
    pid = base
    if pid in playlists:
        pid = f"{base}-{uuid.uuid4().hex[:6]}"

    playlists[pid] = {"id": pid, "name": name, "tracks": [], "cover": None}
    save_playlist()
    return playlists[pid]


@_locked
def delete_playlist(playlist_id: str):
    playlists = _bucket()["playlists"]
    if playlist_id == DEFAULT_PLAYLIST_ID:
        raise ValueError("Default playlist cannot be deleted")
    if playlist_id not in playlists:
        raise PlaylistNotFound()
    del playlists[playlist_id]
    save_playlist()


@_locked
def add_to_playlist(playlist_id: str, track_id: str, tracks):
    playlists = _bucket()["playlists"]
    if playlist_id not in playlists:
        raise PlaylistNotFound()
    if not _track_exists(tracks, track_id):
        raise TrackNotFound()
    if track_id in playlists[playlist_id]["tracks"]:
        raise AlreadyInPlaylist()

    playlists[playlist_id]["tracks"].append(track_id)
    save_playlist()


@_locked
def remove_from_playlist(playlist_id: str, track_id: str):
    playlists = _bucket()["playlists"]
    if playlist_id not in playlists:
        raise PlaylistNotFound()
    if track_id not in playlists[playlist_id]["tracks"]:
        raise NotInPlaylist()

    playlists[playlist_id]["tracks"].remove(track_id)
    save_playlist()


@_locked
def reorder_playlist(playlist_id: str, track_ids: list[str]):
    playlists = _bucket()["playlists"]
    if playlist_id not in playlists:
        raise PlaylistNotFound()

    current = playlists[playlist_id]["tracks"]
    reordered = _clean_track_list(track_ids)
    if sorted(reordered) != sorted(current):
        raise ValueError("New order must contain exactly the same tracks")

    playlists[playlist_id]["tracks"] = reordered
    save_playlist()
    return playlists[playlist_id]


@_locked
def playlist_tracks(playlist_id: str, tracks):
    playlists = _bucket()["playlists"]
    if playlist_id not in playlists:
        raise PlaylistNotFound()
    by_id = {t.id: t for t in tracks}
    return [by_id[tid].__dict__ for tid in playlists[playlist_id]["tracks"] if tid in by_id]


@_locked
def liked_tracks(tracks):
    likes = _bucket()["likes"]
    by_id = {t.id: t for t in tracks}
    return [by_id[tid].__dict__ for tid in sorted(likes) if tid in by_id]


@_locked
def clear_playlist(playlist_id: str = DEFAULT_PLAYLIST_ID):
    playlists = _bucket()["playlists"]
    if playlist_id not in playlists:
        raise PlaylistNotFound()
    playlists[playlist_id]["tracks"] = []
    save_playlist()


@_locked
def migrate_legacy_int_ids(tracks):
    """One-time fixup for playlists that still reference tracks by their old
    integer list-index instead of a stable id. Runs at startup across every
    user's data, outside of any per-request user context."""
    changed = False

    for bucket in USER_DATA.values():
        for playlist_data in bucket["playlists"].values():
            raw = playlist_data["tracks"]
            if not raw:
                continue
            migrated = []
            for item in raw:
                try:
                    idx = int(item)
                except Exception:
                    migrated = []
                    break
                if 0 <= idx < len(tracks):
                    migrated.append(tracks[idx].id)
            if migrated:
                playlist_data["tracks"] = migrated
                changed = True

    if changed:
        save_playlist()

    return changed


@_locked
def add(track_id: str, tracks):
    add_to_playlist(DEFAULT_PLAYLIST_ID, track_id, tracks)


@_locked
def remove(track_id: str):
    remove_from_playlist(DEFAULT_PLAYLIST_ID, track_id)


@_locked
def tracks(tracks):
    return playlist_tracks(DEFAULT_PLAYLIST_ID, tracks)


@_locked
def clear():
    clear_playlist(DEFAULT_PLAYLIST_ID)


def artwork_key(value: str) -> str:
    return hashlib.sha1(value.encode("utf-8")).hexdigest()


def artwork_url(kind: str, key: str, filename: str | None = None) -> str | None:
    if not filename:
        return None
    return f"/artwork/{kind}/{key}"


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def history_snapshot(history: dict | None = None) -> dict:
    history = history if history is not None else _bucket()["history"]
    return {
        "tracks": history,
        "recent": [
            {"id": track_id, **item}
            for track_id, item in sorted(
                history.items(),
                key=lambda pair: str(pair[1].get("last_played", "")),
                reverse=True,
            )
            if item.get("last_played")
        ],
        "most_played": [
            {"id": track_id, **item}
            for track_id, item in sorted(
                history.items(),
                key=lambda pair: int(pair[1].get("play_count", 0)),
                reverse=True,
            )
            if int(item.get("play_count", 0)) > 0
        ],
    }


@_locked
def record_play(track_id: str) -> dict:
    history = _bucket()["history"]
    item = history.setdefault(track_id, {})
    item["play_count"] = int(item.get("play_count", 0)) + 1
    item["last_played"] = utc_now()
    _schedule_debounced_save()
    return item


@_locked
def update_position(track_id: str, position: float, duration: float = 0.0) -> dict:
    history = _bucket()["history"]
    item = history.setdefault(track_id, {})
    item["last_position"] = max(0.0, float(position or 0))
    if duration and duration > 0:
        item["duration"] = float(duration)
        item["completed"] = position >= max(0.0, duration - 5)
    item["position_updated_at"] = utc_now()
    _schedule_debounced_save()
    return item


@_locked
def cached_artist_info(artist_name: str) -> dict | None:
    if artist_name in ARTIST_INFO:
        return ARTIST_INFO[artist_name]
    wanted = artist_name.strip().casefold()
    for key, value in ARTIST_INFO.items():
        if key.strip().casefold() == wanted:
            return value
    return None


@_locked
def set_artist_info(artist_name: str, info: dict) -> dict:
    ARTIST_INFO[artist_name] = info
    save_playlist()
    return info


@_locked
def clear_artist_info() -> int:
    count = len(ARTIST_INFO)
    ARTIST_INFO.clear()
    save_playlist()
    return count


def artwork_path(kind: str, key: str, filename: str | None = None) -> Path | None:
    if not filename:
        return None
    return ARTWORK_DIR / kind / filename


def playlist_cover_path(playlist_id: str) -> Path | None:
    playlists = _bucket()["playlists"]
    playlist_data = playlists.get(playlist_id)
    if not playlist_data:
        raise PlaylistNotFound()
    return artwork_path("playlist", playlist_id, playlist_data.get("cover"))


def folder_cover_path(folder_name: str) -> Path | None:
    return artwork_path("folder", artwork_key(folder_name), FOLDER_COVERS.get(folder_name))


def folder_cover_url(folder_name: str) -> str | None:
    filename = FOLDER_COVERS.get(folder_name)
    if not filename:
        return None
    return artwork_url("folder", artwork_key(folder_name), filename)


def track_cover_path(track_id: str) -> Path | None:
    return artwork_path("track", track_id, TRACK_COVERS.get(track_id))


def track_cover_url(track_id: str) -> str | None:
    filename = TRACK_COVERS.get(track_id)
    if not filename:
        return None
    return artwork_url("track", track_id, filename)


def background_path() -> Path | None:
    if not BACKGROUND_MEDIA:
        return None
    return ARTWORK_DIR / "background" / BACKGROUND_MEDIA


def background_url() -> str | None:
    if not BACKGROUND_MEDIA:
        return None
    return "/background/media"


@_locked
def set_playlist_cover(playlist_id: str, source_path: Path, suffix: str) -> dict:
    playlists = _bucket()["playlists"]
    if playlist_id not in playlists:
        raise PlaylistNotFound()
    # Prefixed with username: playlist ids like "default" are per-user and
    # would otherwise collide on disk between two different users' covers.
    filename = f"{get_current_user()}__{playlist_id}{suffix}"
    target = ARTWORK_DIR / "playlist" / filename
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.move(str(source_path), target)
    playlists[playlist_id]["cover"] = filename
    save_playlist()
    return playlists[playlist_id]


@_locked
def set_folder_cover(folder_name: str, source_path: Path, suffix: str) -> str:
    key = artwork_key(folder_name)
    filename = f"{key}{suffix}"
    target = ARTWORK_DIR / "folder" / filename
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.move(str(source_path), target)
    FOLDER_COVERS[folder_name] = filename
    save_playlist()
    return filename


@_locked
def set_track_cover(track_id: str, source_path: Path, suffix: str) -> str:
    filename = f"{track_id}{suffix}"
    target = ARTWORK_DIR / "track" / filename
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.move(str(source_path), target)
    TRACK_COVERS[track_id] = filename
    save_playlist()
    return filename


@_locked
def set_background_media(source_path: Path, suffix: str) -> str:
    global BACKGROUND_MEDIA

    filename = f"background{suffix}"
    target = ARTWORK_DIR / "background" / filename
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.move(str(source_path), target)
    BACKGROUND_MEDIA = filename
    save_playlist()
    return filename
