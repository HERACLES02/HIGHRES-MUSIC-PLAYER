import json
import time

import library
import maintenance
import playlist


def test_scan_reports_unreadable_tags_and_keeps_filename_fallback(music_dir):
    (music_dir / "broken.mp3").write_bytes(b"bad audio")
    library.refresh_library()
    assert library.SCAN_STATUS["scanned"] == library.SCAN_STATUS["total"] == 1
    assert not library.SCAN_STATUS["running"]
    assert library.SCAN_STATUS["issues"][0]["path"] == "broken.mp3"
    assert library.TRACKS[0].title == "broken"


def test_catalog_preserves_first_seen_and_missing_paths_across_scans(music_dir, monkeypatch):
    index = music_dir / "index.json"
    monkeypatch.setattr(library, "CATALOG_FILE", index)
    song = music_dir / "original.mp3"
    song.touch()
    library.refresh_library()
    tid = library.TRACKS[0].id
    added = library.TRACKS[0].added_at
    library.refresh_library()
    assert library.TRACKS[0].added_at == added
    song.unlink()
    library.refresh_library()
    assert tid not in library.TRACK_BY_ID
    assert json.loads(index.read_text())[tid]["relpath"] == "original.mp3"


def test_possible_duplicates_and_move_suggestions_are_read_only(music_dir, isolated_playlist):
    def item(tid, relpath):
        return library.Track(tid, relpath, "Song", "Artist", "Album", "", file_size=10)

    library.TRACK_BY_ID = {"new": item("new", "new.mp3"), "copy": item("copy", "copy.mp3")}
    library.CATALOG["old"] = item("old", "old.mp3").__dict__
    playlist.LIKES.add("old")
    result = maintenance.report()
    assert len(result["duplicates"]) == 1
    assert len(result["missing"][0]["candidates"]) == 2
    assert playlist.LIKES == {"old"}


def test_relink_preserves_playlist_order_deduplicates_and_isolates_users(isolated_playlist):
    bucket = playlist._bucket()
    bucket["likes"] = {"old"}
    bucket["playlists"]["default"]["tracks"] = ["a", "old", "b", "new"]
    bucket["history"]["old"] = {"play_count": 2, "last_position": 12}
    playlist.USER_DATA["other"] = playlist._new_user_bucket()
    playlist.USER_DATA["other"]["likes"].add("old")
    playlist.relink_track("old", "new")
    assert bucket["likes"] == {"new"}
    assert bucket["playlists"]["default"]["tracks"] == ["a", "new", "b"]
    assert bucket["history"]["new"]["last_position"] == 12
    assert playlist.USER_DATA["other"]["likes"] == {"old"}


def test_request_scan_returns_promptly_and_coalesces_requests(music_dir, monkeypatch):
    calls = []
    class FakeThread:
        def __init__(self, **kwargs):
            calls.append(kwargs)
        def start(self):
            pass
    monkeypatch.setattr(library.threading, "Thread", FakeThread)
    monkeypatch.setitem(library.SCAN_STATUS, "running", False)
    start = time.monotonic()
    assert library.request_refresh()
    assert not library.request_refresh()
    assert time.monotonic() - start < 0.1
    assert len(calls) == 1
