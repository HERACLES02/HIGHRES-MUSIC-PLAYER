"""Read-only library health reports; all duplicate/move matches are suggestions."""
from collections import defaultdict

import library
import playlist


def identity(track):
    values = tuple(str(track.get(key, "")).strip().casefold() for key in ("title", "artist", "album"))
    if not all(values) or values[1] == "unknown artist" or values[2] == "unknown album":
        return None
    return values + (track.get("file_size", 0),)


def report():
    existing = dict(library.TRACK_BY_ID)
    groups = defaultdict(list)
    for track in existing.values():
        key = identity(track.__dict__)
        if key:
            groups[key].append({"id": track.id, "path": track.relpath})
    references = set(playlist.LIKES) | set(playlist.HISTORY)
    for item in playlist.PLAYLISTS.values():
        references.update(item["tracks"])
    missing = []
    for tid in sorted(references - existing.keys()):
        old = library.CATALOG.get(tid, {})
        key = identity(old)
        missing.append({"id": tid, "path": old.get("relpath", "Unknown previous path"),
                        "title": old.get("title", tid), "candidates": groups.get(key, []) if key else []})
    return {"scan": dict(library.SCAN_STATUS), "missing": missing,
            "duplicates": [items for items in groups.values() if len(items) > 1]}
