import pytest

import app as webapp
import library
import playlist
from test_metadata_edits import editable_library


@pytest.fixture
def client(editable_library):
    if not playlist.user_exists("testuser"):
        playlist.create_user("testuser", "test-password")
    webapp.app.config["TESTING"] = True
    client = webapp.app.test_client()
    with client.session_transaction() as session:
        session["username"] = "testuser"
        session["csrf_token"] = "test-csrf"
    return client


def post(client, path, payload):
    return client.post(path, json=payload, headers={"X-CSRF-Token": "test-csrf"})


def test_edit_requires_preview_then_returns_per_file_results(client, editable_library):
    payload = {"track_ids": editable_library, "artist": "Reviewed artist"}
    assert post(client, "/api/tracks/metadata/batch", payload).status_code == 409
    preview = post(client, "/api/tracks/metadata/batch", {**payload, "preview": True})
    assert preview.status_code == 200
    assert library.TRACKS[0].artist == "Original artist"
    saved = post(client, "/api/tracks/metadata/batch", {**payload, "preview_token": preview.json["preview_token"]})
    assert saved.status_code == 200
    assert saved.json["updated"] == 2
    history = client.get("/api/metadata/history")
    assert history.json[0]["count"] == 2
    undone = post(client, f'/api/metadata/history/{saved.json["job_id"]}/undo', {})
    assert all(row["status"] == "restored" for row in undone.json["results"])


def test_changed_payload_cannot_reuse_preview_token(client, editable_library):
    payload = {"track_ids": editable_library, "album": "Reviewed"}
    preview = post(client, "/api/tracks/metadata/batch", {**payload, "preview": True}).json
    changed = post(client, "/api/tracks/metadata/batch", {**payload, "album": "Unreviewed", "preview_token": preview["preview_token"]})
    assert changed.status_code == 409
    assert library.TRACKS[0].album == "Original album"


def test_changed_file_cannot_reuse_preview_token(client, editable_library):
    payload = {"track_ids": editable_library, "artist": "Reviewed"}
    preview = post(client, "/api/tracks/metadata/batch", {**payload, "preview": True}).json
    library.update_metadata_fields(editable_library[0], {"title": "New title"})
    saved = post(client, "/api/tracks/metadata/batch", {**payload, "preview_token": preview["preview_token"]})
    assert saved.status_code == 409


def test_management_routes_require_login_and_csrf(client):
    assert client.post("/api/tracks/metadata/batch", json={}).status_code == 403
    with client.session_transaction() as session:
        session.clear()
    assert client.get("/api/library-health").status_code == 401
    assert client.get("/api/metadata/history").status_code == 401


def test_health_relink_and_scan_routes(client, editable_library, monkeypatch):
    playlist.set_current_user("testuser")
    playlist.LIKES.add("old-song")
    assert client.get("/api/library-health").json["missing"][0]["id"] == "old-song"
    response = post(client, "/api/library/relink", {"old_id": "old-song", "new_id": editable_library[0]})
    assert response.status_code == 200
    assert client.get("/api/library-health").json["missing"] == []
    monkeypatch.setattr(library, "request_refresh", lambda: True)
    assert post(client, "/api/refresh", {}).status_code == 202


def test_main_page_includes_management_controls_and_scripts(client):
    page = client.get("/").get_data(as_text=True)
    for text in ('id="browseSort"', 'id="metadataReview"', 'id="metadataApply"', 'id="queueUpcoming"', 'Select all results', '/static/management.js', '/static/library-tools.js', '/static/listening-queue.js', '/static/player-session.js'):
        assert text in page
