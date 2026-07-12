import time

import pytest
from flask import Flask

import library


def test_stable_id_is_deterministic_and_unique():
    id1 = library.stable_id_for_relpath("Artist/Album/track.mp3")
    id2 = library.stable_id_for_relpath("Artist/Album/track.mp3")
    id3 = library.stable_id_for_relpath("Artist/Album/other.mp3")
    assert id1 == id2
    assert id1 != id3


def test_safe_resolve_allows_paths_inside_music_dir(music_dir):
    target = music_dir / "Artist" / "song.mp3"
    target.parent.mkdir(parents=True)
    target.touch()

    resolved = library.safe_resolve("Artist/song.mp3")
    assert resolved == target.resolve()


def test_safe_resolve_blocks_path_traversal(music_dir):
    with pytest.raises(Exception):
        library.safe_resolve("../../outside.mp3")


def test_safe_resolve_blocks_drive_absolute_escape(music_dir):
    # On Windows, Path(base) / "C:/other" discards the base entirely
    # (pathlib joins an absolute path by replacing, not nesting) - safe_resolve
    # must still catch this via its final containment check.
    drive = music_dir.drive or "C:"
    with pytest.raises(Exception):
        library.safe_resolve(f"{drive}/Windows/win.ini")


def test_refresh_library_groups_by_folder_and_generates_ids(music_dir):
    (music_dir / "Artist A").mkdir()
    (music_dir / "Artist A" / "track1.mp3").touch()
    (music_dir / "Artist A" / "track2.mp3").touch()
    (music_dir / "track_root.mp3").touch()
    (music_dir / "not_audio.txt").touch()

    library.refresh_library()

    assert len(library.TRACKS) == 3
    relpaths = {t.relpath for t in library.TRACKS}
    assert "Artist A/track1.mp3" in relpaths
    assert "track_root.mp3" in relpaths
    assert "not_audio.txt" not in relpaths

    folders = library.folders()
    folder_names = {f["name"] for f in folders}
    assert "Artist A" in folder_names
    assert "(root)" in folder_names

    artist_a_folder = next(f for f in folders if f["name"] == "Artist A")
    assert artist_a_folder["count"] == 2


def test_refresh_library_falls_back_to_filename_for_unparseable_tags(music_dir):
    fake_track = music_dir / "My Cool Song.mp3"
    fake_track.touch()

    library.refresh_library()

    assert len(library.TRACKS) == 1
    track = library.TRACKS[0]
    assert track.title == "My Cool Song"
    assert track.artist == "Unknown Artist"
    assert track.album == "Unknown Album"


def test_derived_metadata_uses_parent_folders_as_artist_album(music_dir):
    nested = music_dir / "Some Artist" / "Some Album"
    nested.mkdir(parents=True)
    (nested / "01 - Track.mp3").touch()

    library.refresh_library()
    track_id = library.TRACKS[0].id

    derived = library.derived_metadata_for_track(track_id)
    assert derived["artist"] == "Some Artist"
    assert derived["album"] == "Some Album"
    assert derived["title"] == "Track"


def test_watcher_detects_new_and_deleted_files(music_dir, monkeypatch):
    monkeypatch.setattr(library, "WATCH_LIBRARY", True)
    monkeypatch.setattr(library, "WATCH_DEBOUNCE_SECONDS", 0.2)

    assert library.start_watching() is True
    assert library.is_watching() is True
    try:
        new_file = music_dir / "Watched Song.mp3"
        new_file.write_bytes(b"\x00" * 32)

        deadline = time.monotonic() + 5
        while time.monotonic() < deadline and not any(t.relpath == "Watched Song.mp3" for t in library.TRACKS):
            time.sleep(0.1)
        assert any(t.relpath == "Watched Song.mp3" for t in library.TRACKS)

        new_file.unlink()
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline and library.TRACKS:
            time.sleep(0.1)
        assert library.TRACKS == []
    finally:
        library.stop_watching()

    assert library.is_watching() is False


def test_start_watching_is_idempotent(music_dir, monkeypatch):
    monkeypatch.setattr(library, "WATCH_LIBRARY", True)
    try:
        assert library.start_watching() is True
        assert library.start_watching() is True  # second call is a no-op, not a second observer
    finally:
        library.stop_watching()


def test_watching_disabled_via_env_flag(music_dir, monkeypatch):
    monkeypatch.setattr(library, "WATCH_LIBRARY", False)
    assert library.start_watching() is False
    assert library.is_watching() is False


class _FakeAudio:
    """Stands in for mutagen's MutagenFile(..., easy=True) return value, so
    this test doesn't depend on crafting a byte-perfect real MP3 fixture."""

    def __init__(self):
        self.tags = {}
        self.saved = False

    def add_tags(self):
        pass

    def __setitem__(self, key, value):
        self.tags[key] = value

    def save(self):
        self.saved = True


def test_update_metadata_patches_in_place_without_full_rescan(music_dir, monkeypatch):
    (music_dir / "song.mp3").touch()
    library.refresh_library()
    track_id = library.TRACKS[0].id

    fake_audio = _FakeAudio()
    monkeypatch.setattr(library, "MutagenFile", lambda path, easy=False: fake_audio)
    rescan_calls = []
    monkeypatch.setattr(library, "refresh_library", lambda: rescan_calls.append(1))

    result = library.update_metadata(track_id, "New Title", "New Artist", "New Album")

    assert fake_audio.saved is True
    assert result["title"] == "New Title"
    assert result["artist"] == "New Artist"
    assert result["album"] == "New Album"
    assert library.TRACK_BY_ID[track_id].title == "New Title"
    assert library.TRACK_BY_ID[track_id].artist == "New Artist"
    assert library.TRACK_BY_ID[track_id].album == "New Album"
    assert rescan_calls == []  # no full MUSIC_DIR rescan was triggered


def test_range_response_sets_cache_headers_and_supports_full_download(music_dir):
    file_path = music_dir / "song.mp3"
    content = b"abc123" * 1000
    file_path.write_bytes(content)

    probe_app = Flask(__name__)
    with probe_app.test_request_context("/music/song.mp3"):
        resp = library.range_response(file_path, "audio/mpeg")

    assert resp.status_code == 200
    assert resp.headers["Content-Length"] == str(len(content))
    assert resp.headers["Accept-Ranges"] == "bytes"
    assert resp.headers["Cache-Control"] == "private, max-age=86400"
    assert resp.headers["Last-Modified"]
    etag = resp.headers["ETag"]
    assert etag


def test_range_response_returns_304_when_etag_matches(music_dir):
    file_path = music_dir / "song.mp3"
    file_path.write_bytes(b"abc123" * 1000)

    probe_app = Flask(__name__)
    with probe_app.test_request_context("/music/song.mp3"):
        first = library.range_response(file_path, "audio/mpeg")
        etag = first.headers["ETag"]

    with probe_app.test_request_context("/music/song.mp3", headers={"If-None-Match": etag}):
        revalidated = library.range_response(file_path, "audio/mpeg")

    assert revalidated.status_code == 304
    assert revalidated.headers["ETag"] == etag


def test_range_response_still_serves_206_for_range_requests(music_dir):
    file_path = music_dir / "song.mp3"
    content = b"0123456789" * 100
    file_path.write_bytes(content)

    probe_app = Flask(__name__)
    with probe_app.test_request_context("/music/song.mp3", headers={"Range": "bytes=10-19"}):
        resp = library.range_response(file_path, "audio/mpeg")

    assert resp.status_code == 206
    assert resp.headers["Content-Range"] == f"bytes 10-19/{len(content)}"
    assert resp.headers["Content-Length"] == "10"
    assert resp.headers["ETag"]
    assert resp.headers["Cache-Control"] == "private, max-age=86400"


def test_range_response_rejects_out_of_bounds_range(music_dir):
    file_path = music_dir / "song.mp3"
    file_path.write_bytes(b"short")

    probe_app = Flask(__name__)
    with probe_app.test_request_context("/music/song.mp3", headers={"Range": "bytes=0-999"}):
        with pytest.raises(Exception):
            library.range_response(file_path, "audio/mpeg")
