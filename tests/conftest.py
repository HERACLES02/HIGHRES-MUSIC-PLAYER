import os
import sys
import tempfile
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parent.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

# library.py and app.py validate these at import time, so they must exist
# before any test module (or conftest fixture) imports those modules for the
# first time. Individual tests that need specific files/state override the
# relevant module attributes via monkeypatch instead of relying on this.
#
# These are force-set (not setdefault) because the developer's own shell
# profile may already export MUSIC_DIR/PLAYLIST_FILE for running the real
# app (e.g. MUSIC_DIR=F:\music) - tests must never depend on, or touch,
# that real data regardless of what's already in the ambient environment.
_DEFAULT_MUSIC_DIR = Path(tempfile.mkdtemp(prefix="music_player_tests_music_"))
os.environ["MUSIC_DIR"] = str(_DEFAULT_MUSIC_DIR)
os.environ["PLAYLIST_FILE"] = str(Path(tempfile.mkdtemp(prefix="music_player_tests_state_")) / "playlist.json")
os.environ["SECRET_KEY"] = "test-only-secret"
os.environ["ADMIN_USERNAME"] = "test-admin"
# A real werkzeug hash for the literal password "test-admin-password", so
# tests exercising login/verify_user against the bootstrap account work
# exactly like production rather than special-casing a fake hash string.
os.environ["ADMIN_PASSWORD_HASH"] = (
    "scrypt:32768:8:1$wrfxBKMp15FHRmUz$e1cf0a6b96db8f1578502c6edc05980379464a183858c3f266c01265bc3f1cc5fc33243b033f1ed257b05e2bb9271b8afc806212db60d1d5601341a7018442d5"
)
os.environ["WATCH_LIBRARY"] = "0"


@pytest.fixture(autouse=True)
def _cancel_pending_playlist_saves():
    """record_play()/update_position() schedule a real background
    threading.Timer (SAVE_DEBOUNCE_SECONDS). Cancel/flush it after every
    test regardless of which fixtures that test used, so a timer never
    fires later against a path monkeypatch has already reverted."""
    yield
    import playlist

    playlist.flush_pending_save()


@pytest.fixture
def isolated_playlist(tmp_path, monkeypatch):
    """A playlist.py module state fully isolated to this test's tmp_path.

    Resets every in-memory global back to defaults (via load_playlist()
    against a not-yet-existing file) so tests never see leftover state from
    a previous test in the same session. The current user is set to a plain
    non-admin "testuser" so per-user functions (like/create_playlist/etc.)
    work without every test needing to set one explicitly; tests specifically
    about multi-user behavior can call set_current_user()/use ADMIN_USERNAME
    themselves.
    """
    import playlist

    monkeypatch.setattr(playlist, "PLAYLIST_FILE", tmp_path / "playlist.json")
    monkeypatch.setattr(playlist, "ARTWORK_DIR", tmp_path / "artwork")
    playlist.load_playlist()
    playlist.set_current_user("testuser")
    yield playlist
    playlist.set_current_user("")


@pytest.fixture
def music_dir(tmp_path, monkeypatch):
    """An isolated MUSIC_DIR for library.py tests, with library state reset."""
    import library

    monkeypatch.setattr(library, "MUSIC_DIR", tmp_path)
    monkeypatch.setattr(library, "TRACKS", [])
    monkeypatch.setattr(library, "COVERS", {})
    monkeypatch.setattr(library, "TRACK_BY_ID", {})
    yield tmp_path
