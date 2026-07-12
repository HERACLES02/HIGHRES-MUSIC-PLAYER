import functools
import hashlib
import json
import os
import mimetypes
import re
import secrets
import tempfile
import time
import zipfile
from pathlib import Path

from flask import (
    Flask,
    Response,
    abort,
    jsonify,
    redirect,
    render_template,
    request,
    send_file,
    session,
    url_for,
)
from werkzeug.http import is_resource_modified

import playlist
import library
import artist_info

IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".webp", ".gif"}
BACKGROUND_EXTS = IMAGE_EXTS | {".mp4", ".webm", ".mov"}
ARTWORK_CACHE_MAX_AGE_SECONDS = int(os.environ.get("ARTWORK_CACHE_MAX_AGE_SECONDS", "3600"))


def _looks_like_jpeg(head: bytes) -> bool:
    return head[:3] == b"\xff\xd8\xff"


def _looks_like_png(head: bytes) -> bool:
    return head[:8] == b"\x89PNG\r\n\x1a\n"


def _looks_like_gif(head: bytes) -> bool:
    return head[:6] in (b"GIF87a", b"GIF89a")


def _looks_like_webp(head: bytes) -> bool:
    return head[:4] == b"RIFF" and head[8:12] == b"WEBP"


def _looks_like_mp4_or_mov(head: bytes) -> bool:
    # ISO base media file format: a 4-byte box size followed by a box type.
    # Modern files lead with "ftyp"; some older .mov files lead straight into
    # "moov"/"mdat"/"free"/"skip"/"wide" instead.
    return head[4:8] in (b"ftyp", b"moov", b"mdat", b"free", b"skip", b"wide")


def _looks_like_webm(head: bytes) -> bool:
    return head[:4] == b"\x1a\x45\xdf\xa3"


_SIGNATURE_CHECKS = {
    ".jpg": _looks_like_jpeg,
    ".jpeg": _looks_like_jpeg,
    ".png": _looks_like_png,
    ".gif": _looks_like_gif,
    ".webp": _looks_like_webp,
    ".mp4": _looks_like_mp4_or_mov,
    ".mov": _looks_like_mp4_or_mov,
    ".webm": _looks_like_webm,
}


def _matches_declared_type(path: Path, suffix: str) -> bool:
    # The extension allow-list alone only checks the filename a client sent -
    # trivial to fake. This confirms the actual file bytes match a real
    # signature for that format before we keep the upload.
    checker = _SIGNATURE_CHECKS.get(suffix)
    if checker is None:
        return True
    try:
        with open(path, "rb") as f:
            head = f.read(64)
    except OSError:
        return False
    return checker(head)


BACKUP_NAMES = {"playlist.json", "artwork/"}
MUTATING_METHODS = {"POST", "PUT", "PATCH", "DELETE"}
MAX_FAILED_LOGINS = 5
LOGIN_WINDOW_SECONDS = 300
FAILED_LOGINS: dict[str, list[float]] = {}
MAX_RESTORE_ENTRIES = int(os.environ.get("MAX_RESTORE_ENTRIES", "20000"))
MAX_RESTORE_TOTAL_BYTES = int(os.environ.get("MAX_RESTORE_BYTES", str(2 * 1024 * 1024 * 1024)))
TRUST_PROXY_HEADERS = os.environ.get("TRUST_PROXY_HEADERS", "0") == "1"


app = Flask(__name__)
app.secret_key = os.environ.get("SECRET_KEY")
app.config.update(
    SESSION_COOKIE_HTTPONLY=True,
    SESSION_COOKIE_SAMESITE="Lax",
    SESSION_COOKIE_SECURE=os.environ.get("SESSION_COOKIE_SECURE", "0") == "1",
    MAX_CONTENT_LENGTH=int(os.environ.get("MAX_UPLOAD_BYTES", str(300 * 1024 * 1024))),
)

ADMIN_USERNAME = os.environ.get("ADMIN_USERNAME")
ADMIN_PASSWORD_HASH = os.environ.get("ADMIN_PASSWORD_HASH")

if not app.secret_key:
    raise RuntimeError("SECRET_KEY must be set")
if not ADMIN_USERNAME or not ADMIN_PASSWORD_HASH:
    raise RuntimeError("ADMIN_USERNAME and ADMIN_PASSWORD_HASH must be set")


def is_logged_in() -> bool:
    return bool(session.get("username"))


def current_username() -> str:
    return session.get("username") or ""


def current_user_is_admin() -> bool:
    return bool(current_username()) and playlist.is_admin(current_username())


def admin_required(view):
    @functools.wraps(view)
    def wrapper(*args, **kwargs):
        if not current_user_is_admin():
            if request.path.startswith("/api/"):
                return {"ok": False, "error": "Admin privileges required"}, 403
            abort(403, "Admin privileges required")
        return view(*args, **kwargs)

    return wrapper


def safe_next_url(default: str = "index") -> str:
    next_url = request.args.get("next")
    if next_url and next_url.startswith("/") and not next_url.startswith("//"):
        return next_url
    return url_for(default)


def csrf_token() -> str:
    token = session.get("csrf_token")
    if not token:
        token = secrets.token_urlsafe(32)
        session["csrf_token"] = token
    return token


def csrf_is_valid() -> bool:
    expected = session.get("csrf_token")
    provided = request.headers.get("X-CSRF-Token") or request.form.get("csrf_token")
    return bool(expected and provided and secrets.compare_digest(expected, provided))


def client_key() -> str:
    # X-Forwarded-For is client-supplied and trivially spoofable - honoring it
    # unconditionally would let an attacker pick a fresh fake value on every
    # login attempt and bypass the failed-login rate limit entirely. Only
    # trust it when explicitly running behind a proxy that overwrites/sets it.
    if TRUST_PROXY_HEADERS:
        forwarded = request.headers.get("X-Forwarded-For")
        if forwarded:
            return forwarded.split(",")[0].strip()
    return request.remote_addr or "local"


def login_is_limited(key: str) -> bool:
    now = time.time()
    recent = [item for item in FAILED_LOGINS.get(key, []) if now - item < LOGIN_WINDOW_SECONDS]
    FAILED_LOGINS[key] = recent
    return len(recent) >= MAX_FAILED_LOGINS


def record_failed_login(key: str) -> None:
    FAILED_LOGINS.setdefault(key, []).append(time.time())


@app.context_processor
def inject_security_context():
    return {"csrf_token": csrf_token}


@app.after_request
def set_security_headers(response):
    response.headers.setdefault("X-Content-Type-Options", "nosniff")
    response.headers.setdefault("X-Frame-Options", "DENY")
    response.headers.setdefault("Referrer-Policy", "same-origin")
    response.headers.setdefault("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
    return response


@app.before_request
def require_login():
    public_endpoints = {"login", "login_post", "static", "service_worker"}
    if request.method in MUTATING_METHODS and not csrf_is_valid():
        if request.path.startswith("/api/"):
            return {"ok": False, "error": "Invalid CSRF token"}, 403
        abort(403, "Invalid CSRF token")

    if request.endpoint in public_endpoints or is_logged_in():
        if is_logged_in():
            playlist.set_current_user(current_username())
        return None

    if request.path.startswith("/api/"):
        return {"ok": False, "error": "Authentication required"}, 401

    return redirect(url_for("login", next=request.full_path))


@app.get("/login")
def login():
    if is_logged_in():
        return redirect(url_for("index"))
    return render_template("login.html", title="Sign in", error=None)


@app.post("/login")
def login_post():
    key = client_key()
    if login_is_limited(key):
        return render_template("login.html", title="Sign in", error="Too many failed attempts. Wait a few minutes and try again."), 429

    username = request.form.get("username", "")
    password = request.form.get("password", "")

    if playlist.verify_user(username, password):
        FAILED_LOGINS.pop(key, None)
        session.clear()
        session["username"] = username
        return redirect(safe_next_url())

    record_failed_login(key)
    return render_template("login.html", title="Sign in", error="Invalid username or password"), 401


@app.post("/logout")
def logout():
    token = session.get("csrf_token")
    session.clear()
    if token:
        session["csrf_token"] = token
    return redirect(url_for("login"))

@app.get("/")
def index():
    return render_template("index.html", title="My Music Player", tracks=tracks_payload())

@app.get("/sw.js")
def service_worker():
    # Served from the root (not /static/) so its default scope covers the
    # whole origin - a service worker's scope is capped to its own path and
    # below, and /static/sw.js could only ever control /static/*.
    response = send_file(Path(app.static_folder) / "sw.js", mimetype="application/javascript")
    response.headers["Cache-Control"] = "no-cache"
    return response

@app.get("/api/tracks")
def api_tracks():
    return jsonify(tracks_payload())

def tracks_payload():
    items = []
    for track in library.TRACKS:
        data = track.__dict__.copy()
        data["cover_url"] = playlist.track_cover_url(track.id) or data.get("cover_url")
        items.append(data)
    return items

@app.get("/api/folders")
def api_folders():
    folders = []
    for folder in library.folders():
        name = "" if folder["name"] == "(root)" else folder["name"]
        folders.append({**folder, "cover_url": playlist.folder_cover_url(name)})
    return jsonify(folders)

@app.get("/api/tracks/<string:track_id>/lyrics")
def api_track_lyrics(track_id: str):
    return jsonify(library.lyrics_for_track(track_id))

@app.put("/api/tracks/<string:track_id>/lyrics")
def api_save_track_lyrics(track_id: str):
    data = request.get_json(silent=True) or {}
    text = str(data.get("lyrics", ""))
    return jsonify(library.save_lrc_for_track(track_id, text))

@app.get("/api/tracks/<string:track_id>/metadata")
def api_track_metadata(track_id: str):
    return jsonify(library.metadata_for_track(track_id))

@app.get("/api/tracks/<string:track_id>/metadata/derive")
def api_derive_track_metadata(track_id: str):
    return jsonify(library.derived_metadata_for_track(track_id))

@app.patch("/api/tracks/<string:track_id>/metadata")
def api_update_track_metadata(track_id: str):
    data = request.get_json(silent=True) or {}
    try:
        updated = library.update_metadata(
            track_id,
            str(data.get("title", "")),
            str(data.get("artist", "")),
            str(data.get("album", "")),
        )
    except ValueError as exc:
        return {"ok": False, "error": str(exc)}, 400
    return {"ok": True, "track": updated}

@app.post("/api/tracks/metadata/batch")
def api_batch_update_metadata():
    data = request.get_json(silent=True) or {}
    track_ids = [str(item) for item in data.get("track_ids", []) if str(item) in library.TRACK_BY_ID]
    if not track_ids:
        return {"ok": False, "error": "Select at least one track"}, 400

    mode = str(data.get("mode", "set"))
    updated = []
    try:
        if mode == "derive":
            for track_id in track_ids:
                fields = library.derived_metadata_for_track(track_id)
                updated.append(library.update_metadata_fields(track_id, fields))
        else:
            fields = {
                key: str(data.get(key, "")).strip()
                for key in ("title", "artist", "album")
                if str(data.get(key, "")).strip()
            }
            if "title" in fields and len(track_ids) > 1:
                return {"ok": False, "error": "Title can only be batch-set for one track"}, 400
            if not fields:
                return {"ok": False, "error": "Enter at least one metadata field"}, 400
            for track_id in track_ids:
                updated.append(library.update_metadata_fields(track_id, fields))
    except ValueError as exc:
        return {"ok": False, "error": str(exc)}, 400

    return {"ok": True, "updated": len(updated), "tracks": updated}

def _uploaded_image_temp():
    uploaded = request.files.get("cover")
    if uploaded is None or not uploaded.filename:
        return None, None, ("Cover image is required", 400)

    suffix = Path(uploaded.filename).suffix.lower()
    if suffix not in IMAGE_EXTS:
        return None, None, ("Cover must be a JPG, PNG, WEBP, or GIF image", 400)

    tmp = tempfile.NamedTemporaryFile(delete=False, suffix=suffix)
    tmp_path = Path(tmp.name)
    tmp.close()
    uploaded.save(tmp_path)

    if not _matches_declared_type(tmp_path, suffix):
        tmp_path.unlink(missing_ok=True)
        return None, None, ("File content does not match a valid image format", 400)

    return tmp_path, suffix, None

@app.post("/api/playlists/<string:playlist_id>/cover")
def api_set_playlist_cover(playlist_id: str):
    tmp_path, suffix, error = _uploaded_image_temp()
    if error:
        message, status = error
        return {"ok": False, "error": message}, status

    try:
        updated = playlist.set_playlist_cover(playlist_id, tmp_path, suffix)
        return {"ok": True, "playlist": updated}
    except playlist.PlaylistNotFound:
        tmp_path.unlink(missing_ok=True)
        abort(404, "Playlist not found")

@app.post("/api/folders/cover")
def api_set_folder_cover():
    folder_name = str(request.form.get("folder", ""))
    if folder_name == "(root)":
        folder_name = ""

    known_folders = {"" if f["name"] == "(root)" else f["name"] for f in library.folders()}
    if folder_name not in known_folders:
        return {"ok": False, "error": "Folder not found"}, 404

    tmp_path, suffix, error = _uploaded_image_temp()
    if error:
        message, status = error
        return {"ok": False, "error": message}, status

    playlist.set_folder_cover(folder_name, tmp_path, suffix)
    return {"ok": True, "cover_url": playlist.folder_cover_url(folder_name)}

@app.post("/api/tracks/<string:track_id>/cover")
def api_set_track_cover(track_id: str):
    if track_id not in library.TRACK_BY_ID:
        abort(404, "Track not found")

    tmp_path, suffix, error = _uploaded_image_temp()
    if error:
        message, status = error
        return {"ok": False, "error": message}, status

    playlist.set_track_cover(track_id, tmp_path, suffix)
    return {"ok": True, "cover_url": playlist.track_cover_url(track_id)}

@app.post("/api/background")
def api_set_background():
    uploaded = request.files.get("background")
    if uploaded is None or not uploaded.filename:
        return {"ok": False, "error": "Background file is required"}, 400

    suffix = Path(uploaded.filename).suffix.lower()
    if suffix not in BACKGROUND_EXTS:
        return {"ok": False, "error": "Background must be an image, GIF, MP4, WEBM, or MOV file"}, 400

    tmp = tempfile.NamedTemporaryFile(delete=False, suffix=suffix)
    tmp_path = Path(tmp.name)
    tmp.close()
    uploaded.save(tmp_path)

    if not _matches_declared_type(tmp_path, suffix):
        tmp_path.unlink(missing_ok=True)
        return {"ok": False, "error": "File content does not match a valid media format"}, 400

    playlist.set_background_media(tmp_path, suffix)
    return {"ok": True, "background_url": playlist.background_url()}

@app.get("/api/backup")
def api_backup():
    tmp = tempfile.NamedTemporaryFile(delete=False, suffix=".zip")
    backup_path = Path(tmp.name)
    tmp.close()

    with zipfile.ZipFile(backup_path, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        if playlist.PLAYLIST_FILE.exists():
            zf.write(playlist.PLAYLIST_FILE, "playlist.json")
        if playlist.ARTWORK_DIR.exists():
            for file_path in playlist.ARTWORK_DIR.rglob("*"):
                if file_path.is_file():
                    zf.write(file_path, Path("artwork") / file_path.relative_to(playlist.ARTWORK_DIR))

    return send_file(
        backup_path,
        mimetype="application/zip",
        as_attachment=True,
        download_name="music-player-backup.zip",
        max_age=0,
    )

@app.post("/api/restore")
def api_restore():
    uploaded = request.files.get("backup")
    if uploaded is None or not uploaded.filename:
        return {"ok": False, "error": "Backup zip is required"}, 400

    suffix = Path(uploaded.filename).suffix.lower()
    if suffix != ".zip":
        return {"ok": False, "error": "Backup must be a .zip file"}, 400

    tmp = tempfile.NamedTemporaryFile(delete=False, suffix=".zip")
    tmp_path = Path(tmp.name)
    tmp.close()
    uploaded.save(tmp_path)

    try:
        with zipfile.ZipFile(tmp_path, "r") as zf:
            infolist = zf.infolist()

            if len(infolist) > MAX_RESTORE_ENTRIES:
                return {"ok": False, "error": "Backup contains too many files"}, 400

            total_uncompressed = sum(member.file_size for member in infolist)
            if total_uncompressed > MAX_RESTORE_TOTAL_BYTES:
                return {"ok": False, "error": "Backup is too large once decompressed"}, 400

            # Validate playlist.json fully in memory before touching any live
            # file - a malformed/corrupt entry must not partially clobber the
            # working state (e.g. after some artwork has already been written).
            playlist_member = next(
                (m for m in infolist if not m.is_dir() and Path(m.filename).parts == ("playlist.json",)),
                None,
            )
            playlist_raw = None
            if playlist_member is not None:
                with zf.open(playlist_member) as src:
                    playlist_raw = src.read()
                try:
                    parsed = json.loads(playlist_raw)
                except (json.JSONDecodeError, UnicodeDecodeError) as exc:
                    return {"ok": False, "error": f"Backup's playlist.json is not valid JSON: {exc}"}, 400
                if not isinstance(parsed, dict):
                    return {"ok": False, "error": "Backup's playlist.json has an unexpected format"}, 400

            for member in infolist:
                if member.is_dir():
                    continue
                parts = Path(member.filename).parts
                if parts == ("playlist.json",):
                    continue
                if len(parts) >= 2 and parts[0] == "artwork":
                    target = (playlist.ARTWORK_DIR / Path(*parts[1:])).resolve()
                    if playlist.ARTWORK_DIR not in target.parents:
                        continue
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with zf.open(member) as src, open(target, "wb") as dst:
                        dst.write(src.read())

            if playlist_raw is not None:
                playlist.PLAYLIST_FILE.parent.mkdir(parents=True, exist_ok=True)
                tmp_playlist = playlist.PLAYLIST_FILE.with_suffix(".json.tmp")
                tmp_playlist.write_bytes(playlist_raw)
                tmp_playlist.replace(playlist.PLAYLIST_FILE)
    except zipfile.BadZipFile:
        return {"ok": False, "error": "Invalid backup zip"}, 400
    finally:
        tmp_path.unlink(missing_ok=True)

    playlist.load_playlist()
    playlist.migrate_legacy_int_ids(library.TRACKS)
    return {"ok": True}

@app.get("/artwork/playlist/<string:playlist_id>")
def playlist_artwork(playlist_id: str):
    try:
        path = playlist.playlist_cover_path(playlist_id)
    except playlist.PlaylistNotFound:
        abort(404)
    if not path or not path.exists():
        abort(404)
    return send_file(path, max_age=ARTWORK_CACHE_MAX_AGE_SECONDS)

@app.get("/artwork/folder/<string:folder_key>")
def folder_artwork(folder_key: str):
    for folder_name in playlist.FOLDER_COVERS:
        if playlist.artwork_key(folder_name) == folder_key:
            path = playlist.folder_cover_path(folder_name)
            if path and path.exists():
                return send_file(path, max_age=ARTWORK_CACHE_MAX_AGE_SECONDS)
    abort(404)

@app.get("/artwork/track/<string:track_id>")
def track_artwork(track_id: str):
    path = playlist.track_cover_path(track_id)
    if not path or not path.exists():
        abort(404)
    return send_file(path, max_age=ARTWORK_CACHE_MAX_AGE_SECONDS)

@app.get("/background/media")
def background_media():
    path = playlist.background_path()
    if not path or not path.exists():
        abort(404)
    return send_file(path, max_age=ARTWORK_CACHE_MAX_AGE_SECONDS)

@app.post("/api/refresh")
def api_refresh():
    library.refresh_library()
    return {"ok": True, "count": len(library.TRACKS)}

@app.get("/music/<path:filename>")
def music(filename: str):
    target = library.safe_resolve(filename)
    if not target.exists() or not target.is_file():
        abort(404)

    mime, _ = mimetypes.guess_type(str(target))
    mime = mime or "application/octet-stream"

    return library.range_response(target, mime)

@app.get("/cover/<string:track_id>")
def cover(track_id: str):
    if track_id not in library.COVERS:
        abort(404)

    t = library.TRACK_BY_ID.get(track_id)
    if not t:
        abort(404)

    mime = t.cover_mime or "image/jpeg"
    data = library.COVERS[track_id]
    # Content-derived, not file-derived (this comes from an in-memory dict
    # populated at scan time, not a file on disk) - changes automatically
    # if a rescan picks up different embedded art for this track.
    etag = hashlib.sha1(data).hexdigest()

    if not is_resource_modified(request.environ, etag=etag):
        resp = Response(status=304)
        resp.headers["ETag"] = etag
        resp.headers["Cache-Control"] = f"private, max-age={ARTWORK_CACHE_MAX_AGE_SECONDS}"
        return resp

    resp = Response(data, mimetype=mime)
    resp.headers["ETag"] = etag
    resp.headers["Cache-Control"] = f"private, max-age={ARTWORK_CACHE_MAX_AGE_SECONDS}"
    return resp

@app.get("/api/playlist")
def api_get_playlist():
    return jsonify(playlist.snapshot())

@app.get("/api/library-state")
def api_library_state():
    return jsonify(playlist.snapshot())

@app.get("/api/settings")
def api_settings():
    using_dev_secret = app.secret_key == "dev-only-change-this-secret"
    return {
        "music_dir": str(library.MUSIC_DIR),
        "playlist_file": str(playlist.PLAYLIST_FILE),
        "artwork_dir": str(playlist.ARTWORK_DIR),
        "track_count": len(library.TRACKS),
        "folder_count": len(library.folders()),
        "library_auto_watch": library.is_watching(),
        "playlist_count": len(playlist.PLAYLISTS),
        "artist_info_cache_count": len(playlist.ARTIST_INFO),
        "max_upload_mb": round(app.config["MAX_CONTENT_LENGTH"] / 1024 / 1024),
        "flask_host": os.environ.get("FLASK_HOST", "127.0.0.1"),
        "https_cookie_required": app.config["SESSION_COOKIE_SECURE"],
        "using_dev_secret": using_dev_secret,
        "using_default_admin": ADMIN_USERNAME == "admin",
        "username": current_username(),
        "is_admin": current_user_is_admin(),
        "user_count": len(playlist.USERS),
    }

@app.get("/api/users")
@admin_required
def api_list_users():
    return jsonify(playlist.list_users())

@app.post("/api/users")
@admin_required
def api_create_user():
    data = request.get_json(silent=True) or {}
    try:
        created = playlist.create_user(
            str(data.get("username", "")),
            str(data.get("password", "")),
            make_admin=bool(data.get("is_admin", False)),
        )
    except playlist.UserError as exc:
        return {"ok": False, "error": str(exc)}, 400
    return {"ok": True, "user": created}, 201

@app.delete("/api/users/<string:username>")
@admin_required
def api_delete_user(username: str):
    if username == current_username():
        return {"ok": False, "error": "You cannot delete your own account"}, 400
    try:
        playlist.delete_user(username)
    except playlist.UserError as exc:
        return {"ok": False, "error": str(exc)}, 400
    return {"ok": True}

@app.post("/api/users/me/password")
def api_change_own_password():
    data = request.get_json(silent=True) or {}
    current_password = str(data.get("current_password", ""))
    new_password = str(data.get("new_password", ""))
    if not playlist.verify_user(current_username(), current_password):
        return {"ok": False, "error": "Current password is incorrect"}, 403
    try:
        playlist.set_password(current_username(), new_password)
    except playlist.UserError as exc:
        return {"ok": False, "error": str(exc)}, 400
    return {"ok": True}

@app.post("/api/settings/artist-info/clear")
def api_clear_artist_info():
    count = playlist.clear_artist_info()
    return {"ok": True, "cleared": count}

@app.get("/api/artist-info")
def api_artist_info():
    artist_name = str(request.args.get("artist", "")).strip()
    if not artist_name:
        return {"ok": False, "error": "Artist name is required"}, 400

    cached = playlist.cached_artist_info(artist_name)
    if cached and request.args.get("refresh") != "1":
        return {"ok": True, "cached": True, "info": cached}

    if request.args.get("cached") == "1":
        return {"ok": True, "cached": bool(cached), "info": cached}

    try:
        info = artist_info.fetch_artist_info(artist_name)
    except ValueError as exc:
        return {"ok": False, "error": str(exc)}, 400

    playlist.set_artist_info(artist_name, info)
    return {"ok": True, "cached": False, "info": info}

@app.get("/api/artist-info/matches")
def api_artist_info_matches():
    artist_name = str(request.args.get("artist", "")).strip()
    if not artist_name:
        return {"ok": False, "error": "Artist name is required"}, 400
    try:
        return {"ok": True, "matches": artist_info.search_artist_matches(artist_name)}
    except ValueError as exc:
        return {"ok": False, "error": str(exc)}, 400

@app.post("/api/artist-info/choose")
def api_choose_artist_info():
    data = request.get_json(silent=True) or {}
    artist_name = str(data.get("artist", "")).strip()
    if not artist_name:
        return {"ok": False, "error": "Artist name is required"}, 400
    try:
        info = artist_info.fetch_artist_info(
            artist_name,
            musicbrainz_id=str(data.get("musicbrainz_id", "")).strip(),
            wikipedia_title=str(data.get("wikipedia_title", "")).strip(),
        )
    except ValueError as exc:
        return {"ok": False, "error": str(exc)}, 400
    playlist.set_artist_info(artist_name, info)
    return {"ok": True, "info": info}

@app.post("/api/history/play/<string:track_id>")
def api_record_play(track_id: str):
    if track_id not in library.TRACK_BY_ID:
        abort(404, "Track not found")
    return {"ok": True, "history": playlist.record_play(track_id)}

@app.post("/api/history/position/<string:track_id>")
def api_update_position(track_id: str):
    if track_id not in library.TRACK_BY_ID:
        abort(404, "Track not found")
    data = request.get_json(silent=True) or {}
    return {
        "ok": True,
        "history": playlist.update_position(
            track_id,
            float(data.get("position") or 0),
            float(data.get("duration") or 0),
        ),
    }

@app.post("/api/likes/<string:track_id>")
def api_like_track(track_id: str):
    try:
        playlist.like(track_id, library.TRACKS)
        return {"ok": True}
    except playlist.TrackNotFound:
        abort(404, "Track not found")

@app.delete("/api/likes/<string:track_id>")
def api_unlike_track(track_id: str):
    playlist.unlike(track_id)
    return {"ok": True}

@app.get("/api/likes/tracks")
def api_liked_tracks():
    return jsonify(playlist.liked_tracks(library.TRACKS))

@app.get("/api/playlists")
def api_get_playlists():
    return jsonify(playlist.snapshot()["playlists"])

@app.post("/api/playlists")
def api_create_playlist():
    data = request.get_json(silent=True) or {}
    try:
        created = playlist.create_playlist(str(data.get("name", "")))
    except ValueError as exc:
        return {"ok": False, "error": str(exc)}, 400
    return {"ok": True, "playlist": created}, 201

@app.delete("/api/playlists/<string:playlist_id>")
def api_delete_playlist(playlist_id: str):
    try:
        playlist.delete_playlist(playlist_id)
        return {"ok": True}
    except ValueError as exc:
        return {"ok": False, "error": str(exc)}, 400
    except playlist.PlaylistNotFound:
        abort(404, "Playlist not found")

@app.get("/api/playlists/<string:playlist_id>/tracks")
def api_playlist_id_tracks(playlist_id: str):
    try:
        return jsonify(playlist.playlist_tracks(playlist_id, library.TRACKS))
    except playlist.PlaylistNotFound:
        abort(404, "Playlist not found")

@app.get("/api/playlists/<string:playlist_id>/export.m3u8")
def api_export_playlist(playlist_id: str):
    playlists = playlist.PLAYLISTS
    if playlist_id not in playlists:
        abort(404, "Playlist not found")
    name = playlists[playlist_id]["name"]

    try:
        tracks = playlist.playlist_tracks(playlist_id, library.TRACKS)
    except playlist.PlaylistNotFound:
        abort(404, "Playlist not found")

    lines = ["#EXTM3U"]
    for track in tracks:
        title = str(track.get("title") or "").replace("\n", " ").replace("\r", "")
        artist_name = str(track.get("artist") or "").replace("\n", " ").replace("\r", "")
        display = f"{artist_name} - {title}" if artist_name else title
        # Duration isn't tracked server-side (only read on playback in the
        # browser), so -1 ("unknown length") is used per the M3U convention.
        lines.append(f"#EXTINF:-1,{display}")
        lines.append(str(library.MUSIC_DIR / track["relpath"]))
    content = "\n".join(lines) + "\n"

    safe_name = re.sub(r"[^a-zA-Z0-9_-]+", "-", name.strip().lower()).strip("-") or "playlist"
    response = Response(content, mimetype="audio/x-mpegurl")
    response.headers["Content-Disposition"] = f'attachment; filename="{safe_name}.m3u8"'
    return response

@app.put("/api/playlists/<string:playlist_id>/order")
def api_reorder_playlist(playlist_id: str):
    data = request.get_json(silent=True) or {}
    track_ids = [str(item) for item in data.get("track_ids", [])]
    try:
        updated = playlist.reorder_playlist(playlist_id, track_ids)
    except playlist.PlaylistNotFound:
        abort(404, "Playlist not found")
    except ValueError as exc:
        return {"ok": False, "error": str(exc)}, 400
    return {"ok": True, "playlist": updated}

@app.post("/api/playlists/<string:playlist_id>/tracks/<string:track_id>")
def api_add_to_named_playlist(playlist_id: str, track_id: str):
    try:
        playlist.add_to_playlist(playlist_id, track_id, library.TRACKS)
        return {"ok": True}
    except playlist.PlaylistNotFound:
        abort(404, "Playlist not found")
    except playlist.TrackNotFound:
        abort(404, "Track not found")
    except playlist.AlreadyInPlaylist:
        return {"ok": False, "error": "Track already in playlist"}, 409

@app.delete("/api/playlists/<string:playlist_id>/tracks/<string:track_id>")
def api_remove_from_named_playlist(playlist_id: str, track_id: str):
    try:
        playlist.remove_from_playlist(playlist_id, track_id)
        return {"ok": True}
    except playlist.PlaylistNotFound:
        abort(404, "Playlist not found")
    except playlist.NotInPlaylist:
        abort(404, "Track not in playlist")

@app.post("/api/playlist/add/<string:track_id>")
def api_add_to_playlist(track_id):
    try:
        playlist.add(track_id, library.TRACKS)
        return {"ok": True, "playlist_size": playlist.snapshot()["count"]}
    except playlist.TrackNotFound:
        abort(404, "Track not found")
    except playlist.AlreadyInPlaylist:
        return {"ok": False, "error": "Track already in playlist"}, 409

@app.delete("/api/playlist/remove/<string:track_id>")
def api_remove_from_playlist(track_id):
    try:
        playlist.remove(track_id)
        return {"ok": True, "playlist_size": playlist.snapshot()["count"]}
    except playlist.NotInPlaylist:
        abort(404, "Track not in playlist")

@app.post("/api/playlist/clear")
def api_clear_playlist():
    playlist.clear()
    return {"ok": True, "playlist_size": playlist.snapshot()["count"]}

@app.get("/api/playlist/tracks")
def api_playlist_tracks():
    return jsonify(playlist.tracks(library.TRACKS))


def initialize_state():
    library.refresh_library()
    playlist.load_playlist()
    playlist.migrate_legacy_int_ids(library.TRACKS)
    library.start_watching()


initialize_state()


if __name__ == "__main__":
    app.run(
        host=os.environ.get("FLASK_HOST", "127.0.0.1"),
        port=int(os.environ.get("FLASK_PORT", "5000")),
        debug=os.environ.get("FLASK_DEBUG", "0") == "1",
        threaded=True,
        use_reloader=False,
    )



