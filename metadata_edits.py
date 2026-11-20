"""Previewed metadata edits with durable, conflict-checked tag undo records."""
import hashlib
import hmac
import json
import threading
import time
import uuid

import library
import playlist

LOCK = threading.RLock()
FIELDS = ("title", "artist", "album")


def history_path():
    return playlist.ARTWORK_DIR / "metadata-history.json"


def read_history():
    path = history_path()
    if not path.exists():
        return []
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, list):
        raise ValueError("Metadata history is invalid. Restore it from a backup before editing.")
    return data


def write_history(jobs):
    path = history_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(jobs, ensure_ascii=False), encoding="utf-8")
    temporary.replace(path)


def read_tags(path):
    audio = library.MutagenFile(path, easy=True)
    if audio is None:
        raise ValueError("This file cannot be read for metadata editing")
    tags = {field: list(audio.get(field, [])) for field in FIELDS}
    return audio, tags


def preview(data):
    raw_ids = data.get("track_ids")
    if not isinstance(raw_ids, list) or not 1 <= len(raw_ids) <= 200:
        raise ValueError("Select between 1 and 200 tracks per edit.")
    ids = list(dict.fromkeys(str(value) for value in raw_ids))
    mode = data.get("mode", "set")
    if mode not in ("set", "derive"):
        raise ValueError("Choose set or derive metadata mode.")
    fields = {key: str(data[key]).strip() for key in FIELDS if key in data}
    if not data.get("single"):
        fields = {key: value for key, value in fields.items() if value}
    if mode == "set" and (not fields or ("title" in fields and len(ids) > 1)):
        raise ValueError("Enter an artist or album. Title can only be changed for one track.")
    rows = []
    for tid in ids:
        row = {"id": tid, "error": None}
        try:
            current = library.metadata_for_track(tid)
            path = library.safe_resolve(current["relpath"])
            stat = path.stat()
            _, original_tags = read_tags(path)
            for key in FIELDS:
                if original_tags[key]:
                    current[key] = str(original_tags[key][0])
            proposed = library.derived_metadata_for_track(tid) if mode == "derive" else fields
            after = {key: str(proposed.get(key, current[key])).strip() for key in FIELDS}
            after["title"] = after["title"] or path.stem
            after["artist"] = after["artist"] or "Unknown Artist"
            after["album"] = after["album"] or "Unknown Album"
            row.update(path=current["relpath"], before={key: current[key] for key in FIELDS},
                       after=after, original_tags=original_tags,
                       stamp=[stat.st_size, stat.st_mtime_ns], changed=any(current[k] != after[k] for k in FIELDS))
            row["after_tags"] = {key: [after[key]] if current[key] != after[key] else original_tags[key] for key in FIELDS}
        except Exception as exc:
            row["error"] = str(exc)
        rows.append(row)
    return rows


def token(rows, secret, username):
    message = json.dumps([username, rows], sort_keys=True).encode()
    return hmac.new(str(secret).encode(), message, hashlib.sha256).hexdigest()


def execute(rows, username):
    jobs = read_history()  # Refuse writes if the existing undo journal is unreadable.
    job = {"id": uuid.uuid4().hex, "username": username, "created_at": time.time(), "entries": []}
    jobs.append(job)
    results = []
    for row in rows:
        result = {"id": row["id"], "path": row.get("path", row["id"])}
        if row["error"]:
            results.append({**result, "status": "failed", "error": row["error"]})
            continue
        if not row["changed"]:
            results.append({**result, "status": "unchanged"})
            continue
        entry = {**row, "status": "prepared"}
        job["entries"].append(entry)
        # Keep original tag values on disk BEFORE changing the song. Pending
        # records can also be undone after an interrupted save/restart.
        write_history(jobs)
        try:
            path = library.safe_resolve(row["path"])
            stat = path.stat()
            if [stat.st_size, stat.st_mtime_ns] != row["stamp"]:
                raise ValueError("File changed after preview. Preview it again.")
            changes = {key: value for key, value in row["after"].items() if row["before"][key] != value}
            track = library.update_metadata_fields(row["id"], changes)
            saved = path.stat()
            entry["saved_stamp"] = [saved.st_size, saved.st_mtime_ns]
            entry["status"] = "applied"
            results.append({**result, "status": "updated", "track": track})
        except Exception as exc:
            entry["status"] = "failed"
            results.append({**result, "status": "failed", "error": str(exc)})
        write_history(jobs)
    return {"ok": True, "job_id": job["id"], "results": results,
            "updated": sum(row["status"] == "updated" for row in results)}


def undo(job_id, username):
    jobs = read_history()
    job = next((item for item in jobs if item["id"] == job_id and item["username"] == username), None)
    if job is None:
        raise ValueError("Metadata edit was not found for this account.")
    results = []
    for entry in job["entries"]:
        if entry["status"] not in ("applied", "prepared", "failed"):
            continue
        result = {"id": entry["id"], "path": entry["path"]}
        try:
            path = library.safe_resolve(entry["path"])
            audio, tags = read_tags(path)
            expected = entry["after_tags"]
            if tags == entry["original_tags"]:
                entry["status"] = "undone"
                results.append({**result, "status": "unchanged"})
                continue
            if tags != expected:
                raise ValueError("Tags changed since this edit. Undo skipped this file to preserve newer changes.")
            if entry.get("saved_stamp"):
                stat = path.stat()
                if [stat.st_size, stat.st_mtime_ns] != entry["saved_stamp"]:
                    raise ValueError("File changed since this edit. Undo skipped it to preserve newer changes.")
            for key, values in entry["original_tags"].items():
                if values:
                    audio[key] = values
                elif key in audio:
                    del audio[key]
            audio.save()
            entry["status"] = "undone"
            results.append({**result, "status": "restored"})
        except Exception as exc:
            results.append({**result, "status": "failed", "error": str(exc)})
        finally:
            write_history(jobs)
    library.refresh_library()
    return {"ok": True, "results": results}


def history(username):
    return [{"id": job["id"], "created_at": job["created_at"],
             "count": sum(row["status"] in ("applied", "prepared", "failed") for row in job["entries"])}
            for job in reversed(read_history()) if job["username"] == username and job["entries"]][:20]
