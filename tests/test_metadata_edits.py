import struct

import pytest
from mutagen.flac import FLAC

import library
import metadata_edits
import playlist


def make_flac(path, title="Old title"):
    """A tiny valid FLAC metadata container, with no real music needed."""
    stream = struct.pack(">HH", 4096, 4096) + b"\0" * 6
    stream += ((44100 << 44) | (1 << 41) | (15 << 36) | 44100).to_bytes(8, "big")
    stream += b"\0" * 16
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(b"fLaC\x80\x00\x00\x22" + stream)
    audio = FLAC(path)
    audio["title"] = [title]
    audio["artist"] = ["Original artist", "Second artist"]
    audio["album"] = ["Original album"]
    audio.save()


@pytest.fixture
def editable_library(music_dir, isolated_playlist):
    for name in ("one", "two"):
        make_flac(music_dir / f"{name}.flac", name)
    library.refresh_library()
    return [track.id for track in library.TRACKS]


def test_preview_does_not_change_files_and_keeps_original_multivalue_tags(editable_library, music_dir):
    before = (music_dir / "one.flac").read_bytes()
    rows = metadata_edits.preview({"track_ids": editable_library, "artist": "New artist"})
    assert len(rows) == 2
    assert rows[0]["after"]["artist"] == "New artist"
    assert rows[0]["original_tags"]["artist"] == ["Original artist", "Second artist"]
    assert (music_dir / "one.flac").read_bytes() == before


def test_apply_then_undo_restores_exact_tag_values(editable_library):
    rows = metadata_edits.preview({"track_ids": editable_library, "artist": "Changed"})
    result = metadata_edits.execute(rows, "testuser")
    assert result["updated"] == 2
    assert FLAC(library._track_path(editable_library[0]))["artist"] == ["Changed"]
    undone = metadata_edits.undo(result["job_id"], "testuser")
    assert all(row["status"] == "restored" for row in undone["results"])
    assert FLAC(library._track_path(editable_library[0]))["artist"] == ["Original artist", "Second artist"]


def test_partial_failure_does_not_stop_other_files(editable_library, monkeypatch):
    rows = metadata_edits.preview({"track_ids": editable_library, "album": "Changed"})
    original = library.update_metadata_fields

    def save(tid, fields):
        if tid == editable_library[0]:
            raise PermissionError("File is read-only")
        return original(tid, fields)

    monkeypatch.setattr(library, "update_metadata_fields", save)
    result = metadata_edits.execute(rows, "testuser")
    assert [row["status"] for row in result["results"]] == ["failed", "updated"]
    assert result["updated"] == 1


def test_undo_skips_newer_changes_and_is_account_scoped(editable_library):
    rows = metadata_edits.preview({"track_ids": editable_library, "album": "Changed"})
    result = metadata_edits.execute(rows, "testuser")
    library.update_metadata_fields(editable_library[0], {"title": "Newer edit"})
    with pytest.raises(ValueError):
        metadata_edits.undo(result["job_id"], "someone-else")
    undone = metadata_edits.undo(result["job_id"], "testuser")
    assert [row["status"] for row in undone["results"]] == ["failed", "restored"]
    assert FLAC(library._track_path(editable_library[0]))["title"] == ["Newer edit"]


def test_file_changed_after_preview_is_not_written(editable_library):
    rows = metadata_edits.preview({"track_ids": editable_library, "artist": "Changed"})
    library.update_metadata_fields(editable_library[0], {"title": "External edit"})
    result = metadata_edits.execute(rows, "testuser")
    assert result["results"][0]["status"] == "failed"
    assert FLAC(library._track_path(editable_library[0]))["artist"] == ["Original artist", "Second artist"]


def test_undo_removes_tags_that_were_originally_absent(editable_library):
    path = library._track_path(editable_library[0])
    audio = FLAC(path)
    del audio["album"]
    audio.save()
    library.refresh_library()
    rows = metadata_edits.preview({"track_ids": [editable_library[0]], "album": "Added album"})
    result = metadata_edits.execute(rows, "testuser")
    metadata_edits.undo(result["job_id"], "testuser")
    assert "album" not in FLAC(path)


def test_preview_reports_missing_files_instead_of_silently_dropping_ids(editable_library):
    rows = metadata_edits.preview({"track_ids": [editable_library[0], "missing"], "artist": "Changed"})
    assert len(rows) == 2
    assert rows[1]["error"]


def test_preview_tokens_bind_account_file_version_and_changes(editable_library):
    rows = metadata_edits.preview({"track_ids": editable_library, "artist": "Changed"})
    token = metadata_edits.token(rows, "secret", "testuser")
    assert token != metadata_edits.token(rows, "secret", "someone-else")
    rows[0]["after"]["artist"] = "Different"
    assert token != metadata_edits.token(rows, "secret", "testuser")


def test_unreadable_undo_history_prevents_edits(editable_library):
    rows = metadata_edits.preview({"track_ids": editable_library, "artist": "Changed"})
    metadata_edits.history_path().parent.mkdir(parents=True, exist_ok=True)
    metadata_edits.history_path().write_text("broken", encoding="utf-8")
    with pytest.raises(ValueError):
        metadata_edits.execute(rows, "testuser")
    assert FLAC(library._track_path(editable_library[0]))["artist"] == ["Original artist", "Second artist"]
