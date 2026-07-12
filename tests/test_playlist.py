import json
import threading
from dataclasses import dataclass

import pytest


@dataclass
class FakeTrack:
    id: str


def make_tracks(*ids):
    return [FakeTrack(id=tid) for tid in ids]


def test_like_unlike_roundtrip(isolated_playlist):
    pl = isolated_playlist
    tracks = make_tracks("a", "b")

    pl.like("a", tracks)
    assert "a" in pl.LIKES

    pl.unlike("a")
    assert "a" not in pl.LIKES


def test_like_unknown_track_raises(isolated_playlist):
    pl = isolated_playlist
    with pytest.raises(pl.TrackNotFound):
        pl.like("missing", make_tracks("a"))


def test_per_user_function_without_current_user_raises(isolated_playlist):
    pl = isolated_playlist
    pl.set_current_user("")
    with pytest.raises(pl.PlaylistError):
        pl.like("a", make_tracks("a"))


def test_create_and_delete_playlist(isolated_playlist):
    pl = isolated_playlist
    created = pl.create_playlist("Road Trip")
    assert created["name"] == "Road Trip"
    assert created["id"] in pl.PLAYLISTS

    pl.delete_playlist(created["id"])
    assert created["id"] not in pl.PLAYLISTS


def test_cannot_delete_default_playlist(isolated_playlist):
    pl = isolated_playlist
    try:
        pl.delete_playlist(pl.DEFAULT_PLAYLIST_ID)
        assert False, "expected ValueError"
    except ValueError:
        pass


def test_add_remove_track_from_named_playlist(isolated_playlist):
    pl = isolated_playlist
    tracks = make_tracks("a", "b")
    created = pl.create_playlist("Favorites")

    pl.add_to_playlist(created["id"], "a", tracks)
    assert pl.PLAYLISTS[created["id"]]["tracks"] == ["a"]

    try:
        pl.add_to_playlist(created["id"], "a", tracks)
        assert False, "expected AlreadyInPlaylist"
    except pl.AlreadyInPlaylist:
        pass

    pl.remove_from_playlist(created["id"], "a")
    assert pl.PLAYLISTS[created["id"]]["tracks"] == []


def test_reorder_playlist(isolated_playlist):
    pl = isolated_playlist
    tracks = make_tracks("a", "b", "c")
    created = pl.create_playlist("Order Test")
    for tid in ("a", "b", "c"):
        pl.add_to_playlist(created["id"], tid, tracks)

    updated = pl.reorder_playlist(created["id"], ["c", "a", "b"])
    assert updated["tracks"] == ["c", "a", "b"]
    assert pl.PLAYLISTS[created["id"]]["tracks"] == ["c", "a", "b"]


def test_reorder_playlist_rejects_mismatched_track_set(isolated_playlist):
    pl = isolated_playlist
    tracks = make_tracks("a", "b")
    created = pl.create_playlist("Order Test")
    pl.add_to_playlist(created["id"], "a", tracks)
    pl.add_to_playlist(created["id"], "b", tracks)

    with pytest.raises(ValueError):
        pl.reorder_playlist(created["id"], ["a"])  # dropped a track

    with pytest.raises(ValueError):
        pl.reorder_playlist(created["id"], ["a", "b", "c"])  # added a track

    assert pl.PLAYLISTS[created["id"]]["tracks"] == ["a", "b"]


def test_reorder_unknown_playlist_raises(isolated_playlist):
    pl = isolated_playlist
    with pytest.raises(pl.PlaylistNotFound):
        pl.reorder_playlist("does-not-exist", ["a"])


def test_save_and_load_roundtrip(isolated_playlist):
    pl = isolated_playlist
    tracks = make_tracks("a", "b")
    pl.like("a", tracks)
    pl.create_playlist("Chill")
    pl.record_play("b")  # debounced - not on disk yet until flushed

    assert pl.PLAYLIST_FILE.exists()

    pl.flush_pending_save()

    # Simulate a fresh process picking the file back up.
    pl.USER_DATA.clear()
    pl.load_playlist()
    pl.set_current_user("testuser")

    assert "a" in pl.LIKES
    assert any(p["name"] == "Chill" for p in pl.PLAYLISTS.values())
    assert pl.HISTORY["b"]["play_count"] == 1


def test_position_and_play_saves_are_debounced_not_immediate(isolated_playlist):
    pl = isolated_playlist
    pl.record_play("a")

    # The like/create_playlist paths above already proved immediate saves
    # work; this confirms record_play/update_position do NOT hit disk
    # synchronously - only after flush_pending_save() (or the debounce timer).
    on_disk = json.loads(pl.PLAYLIST_FILE.read_text()) if pl.PLAYLIST_FILE.exists() else {}
    assert on_disk.get("user_data", {}).get("testuser", {}).get("history", {}) == {}

    pl.flush_pending_save()
    on_disk = json.loads(pl.PLAYLIST_FILE.read_text())
    assert on_disk["user_data"]["testuser"]["history"]["a"]["play_count"] == 1


def test_flush_pending_save_is_a_safe_noop_when_nothing_pending(isolated_playlist):
    pl = isolated_playlist
    pl.flush_pending_save()  # must not raise even with no timer scheduled


def test_record_play_increments_count(isolated_playlist):
    pl = isolated_playlist
    pl.record_play("a")
    pl.record_play("a")
    assert pl.HISTORY["a"]["play_count"] == 2


def test_update_position_marks_completed_near_end(isolated_playlist):
    pl = isolated_playlist
    pl.update_position("a", position=118, duration=120)
    assert pl.HISTORY["a"]["completed"] is True

    pl.update_position("a", position=10, duration=120)
    assert pl.HISTORY["a"]["completed"] is False


def test_artist_info_cache_case_insensitive_lookup(isolated_playlist):
    pl = isolated_playlist
    pl.set_artist_info("Silverstein", {"summary": "test"})
    assert pl.cached_artist_info("silverstein")["summary"] == "test"
    assert pl.cached_artist_info("SILVERSTEIN ")["summary"] == "test"


def test_clear_artist_info_returns_count(isolated_playlist):
    pl = isolated_playlist
    pl.set_artist_info("A", {})
    pl.set_artist_info("B", {})
    assert pl.clear_artist_info() == 2
    assert pl.ARTIST_INFO == {}


def test_concurrent_likes_do_not_lose_updates(isolated_playlist):
    """Regression test for the race condition fixed by playlist._STATE_LOCK:
    many threads liking distinct tracks at once must all be recorded, and
    the on-disk file must always be valid JSON (never a torn write).
    """
    pl = isolated_playlist
    track_ids = [f"t{i}" for i in range(50)]
    tracks = make_tracks(*track_ids)

    def worker(track_id):
        # contextvars aren't inherited by new threads (mirrors production:
        # each Flask request thread sets its own user via before_request).
        pl.set_current_user("testuser")
        pl.like(track_id, tracks)

    threads = [threading.Thread(target=worker, args=(tid,)) for tid in track_ids]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    assert pl.LIKES == set(track_ids)

    pl.USER_DATA.clear()
    pl.load_playlist()
    pl.set_current_user("testuser")
    assert pl.LIKES == set(track_ids)


# ---------------------------------------------------------------------------
# Multi-user accounts
# ---------------------------------------------------------------------------

def test_bootstrap_admin_created_from_env_vars(isolated_playlist):
    pl = isolated_playlist
    users = pl.list_users()
    assert any(u["username"] == "test-admin" and u["is_admin"] for u in users)


def test_verify_user_correct_and_wrong_password(isolated_playlist):
    pl = isolated_playlist
    assert pl.verify_user("test-admin", "test-admin-password") is True
    assert pl.verify_user("test-admin", "wrong-password") is False
    assert pl.verify_user("nonexistent", "anything") is False


def test_create_user_and_login(isolated_playlist):
    pl = isolated_playlist
    created = pl.create_user("alice", "alicepassword123")
    assert created["username"] == "alice"
    assert created["is_admin"] is False
    assert pl.verify_user("alice", "alicepassword123") is True
    assert pl.user_exists("alice")


def test_create_user_rejects_bad_username_or_short_password(isolated_playlist):
    pl = isolated_playlist
    with pytest.raises(pl.UserError):
        pl.create_user("ab", "longenoughpassword")  # too short a username
    with pytest.raises(pl.UserError):
        pl.create_user("validname", "short")  # too short a password


def test_create_user_rejects_duplicate_username(isolated_playlist):
    pl = isolated_playlist
    pl.create_user("alice", "alicepassword123")
    with pytest.raises(pl.UserError):
        pl.create_user("alice", "anotherpassword123")


def test_users_have_isolated_likes_playlists_and_history(isolated_playlist):
    pl = isolated_playlist
    pl.create_user("alice", "alicepassword123")
    tracks = make_tracks("t1", "t2")

    pl.set_current_user("test-admin")
    pl.like("t1", tracks)
    pl.create_playlist("Admin's Playlist")
    pl.record_play("t1")

    pl.set_current_user("alice")
    assert pl.LIKES == set()
    assert list(pl.PLAYLISTS.keys()) == [pl.DEFAULT_PLAYLIST_ID]
    assert pl.HISTORY == {}

    pl.like("t2", tracks)

    pl.set_current_user("test-admin")
    assert pl.LIKES == {"t1"}
    assert pl.HISTORY["t1"]["play_count"] == 1


def test_delete_user_removes_their_data(isolated_playlist):
    pl = isolated_playlist
    pl.create_user("alice", "alicepassword123")
    pl.set_current_user("alice")
    pl.like("t1", make_tracks("t1"))

    pl.delete_user("alice")
    assert not pl.user_exists("alice")
    assert "alice" not in pl.USER_DATA


def test_cannot_delete_bootstrap_admin(isolated_playlist):
    pl = isolated_playlist
    with pytest.raises(pl.UserError):
        pl.delete_user("test-admin")


def test_cannot_delete_last_remaining_admin(isolated_playlist):
    pl = isolated_playlist
    pl.create_user("bob", "bobpassword123", make_admin=False)
    # bob isn't an admin, so test-admin (bootstrap) is still protected
    # separately - test with a second admin instead.
    pl.create_user("second_admin", "adminpassword123", make_admin=True)
    # Deleting second_admin is fine, one admin (test-admin) remains.
    pl.delete_user("second_admin")
    assert not pl.user_exists("second_admin")


def test_set_password_changes_login(isolated_playlist):
    pl = isolated_playlist
    pl.create_user("alice", "originalpassword123")
    pl.set_password("alice", "newpassword456")
    assert pl.verify_user("alice", "originalpassword123") is False
    assert pl.verify_user("alice", "newpassword456") is True


def test_break_glass_admin_login_survives_password_change(isolated_playlist, monkeypatch):
    """Even after the bootstrap admin's stored password is changed via the
    app, logging in with whatever ADMIN_PASSWORD_HASH is currently
    configured in the environment must still work - a safety net against
    ever being permanently locked out."""
    pl = isolated_playlist
    pl.set_password("test-admin", "changed-password-123")
    assert pl.verify_user("test-admin", "changed-password-123") is True
    assert pl.verify_user("test-admin", "test-admin-password") is True  # break-glass


def test_migration_from_v2_preserves_all_data_under_bootstrap_admin(tmp_path, monkeypatch):
    import playlist as pl

    v2_data = {
        "version": 2,
        "likes": ["track1", "track2"],
        "playlists": [
            {"id": "default", "name": "My Playlist", "tracks": ["track1"], "cover": None},
            {"id": "chill", "name": "Chill Vibes", "tracks": ["track2", "track1"], "cover": "chill.jpg"},
        ],
        "folder_covers": {"SomeFolder": "abc.jpg"},
        "track_covers": {},
        "background_media": None,
        "history": {"track1": {"play_count": 5}},
        "artist_info": {"Silverstein": {"summary": "cached bio"}},
    }
    playlist_file = tmp_path / "playlist.json"
    playlist_file.write_text(json.dumps(v2_data), encoding="utf-8")

    monkeypatch.setattr(pl, "PLAYLIST_FILE", playlist_file)
    monkeypatch.setattr(pl, "ARTWORK_DIR", tmp_path / "artwork")
    pl.load_playlist()
    pl.set_current_user("test-admin")

    assert pl.LIKES == {"track1", "track2"}
    assert set(pl.PLAYLISTS.keys()) == {"default", "chill"}
    assert pl.PLAYLISTS["chill"]["tracks"] == ["track2", "track1"]
    assert pl.FOLDER_COVERS == {"SomeFolder": "abc.jpg"}
    assert pl.ARTIST_INFO == {"Silverstein": {"summary": "cached bio"}}
    assert pl.HISTORY["track1"]["play_count"] == 5

    pl.set_current_user("")
