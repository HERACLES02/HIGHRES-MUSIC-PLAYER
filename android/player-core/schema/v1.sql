-- Proposed Room v1 storage contract. Executable SQLite fixture; not wired into an APK yet.
PRAGMA foreign_keys = ON;
CREATE TABLE track (
    id TEXT PRIMARY KEY NOT NULL,
    title TEXT NOT NULL,
    album TEXT,
    album_artist TEXT,
    track_number INTEGER,
    disc_number INTEGER,
    date_text TEXT,
    genre TEXT,
    comment TEXT,
    duration_ms INTEGER NOT NULL CHECK(duration_ms >= 0),
    mime_type TEXT NOT NULL,
    sample_rate_hz INTEGER,
    channels INTEGER,
    meaningful_bits INTEGER,
    availability TEXT NOT NULL CHECK(availability IN ('AVAILABLE','MISSING','PERMISSION_LOST','VOLUME_UNMOUNTED','CORRUPT')),
    added_at_ms INTEGER NOT NULL
);
CREATE TABLE track_artist (
    track_id TEXT NOT NULL REFERENCES track(id) ON DELETE CASCADE,
    ordinal INTEGER NOT NULL CHECK(ordinal >= 0),
    name TEXT NOT NULL,
    PRIMARY KEY(track_id, ordinal)
);
CREATE TABLE folder_grant (
    id TEXT PRIMARY KEY NOT NULL,
    tree_uri TEXT UNIQUE NOT NULL,
    persisted_flags INTEGER NOT NULL,
    granted_at_ms INTEGER NOT NULL
);
CREATE TABLE track_source (
    id TEXT PRIMARY KEY NOT NULL,
    track_id TEXT NOT NULL REFERENCES track(id) ON DELETE RESTRICT,
    uri TEXT UNIQUE NOT NULL,
    source_kind TEXT NOT NULL CHECK(source_kind IN ('MEDIA_STORE','DOCUMENT')),
    folder_grant_id TEXT REFERENCES folder_grant(id) ON DELETE SET NULL,
    size_bytes INTEGER NOT NULL CHECK(size_bytes >= 0),
    modified_ms INTEGER,
    sha256 TEXT,
    last_seen_scan_id TEXT
);
CREATE INDEX source_track ON track_source(track_id);
CREATE TABLE source_alias (
    uri TEXT PRIMARY KEY NOT NULL,
    source_id TEXT NOT NULL REFERENCES track_source(id) ON DELETE CASCADE
);
CREATE TABLE playlist (
    id TEXT PRIMARY KEY NOT NULL,
    name TEXT NOT NULL
);
CREATE TABLE playlist_entry (
    id TEXT PRIMARY KEY NOT NULL,
    playlist_id TEXT NOT NULL REFERENCES playlist(id) ON DELETE CASCADE,
    track_id TEXT NOT NULL REFERENCES track(id) ON DELETE RESTRICT,
    rank INTEGER NOT NULL,
    UNIQUE(playlist_id, rank)
);
CREATE INDEX entry_track ON playlist_entry(track_id);
CREATE TABLE favorite (
    track_id TEXT PRIMARY KEY NOT NULL REFERENCES track(id) ON DELETE RESTRICT
);
CREATE TABLE playback_history (
    id TEXT PRIMARY KEY NOT NULL,
    track_id TEXT NOT NULL REFERENCES track(id) ON DELETE RESTRICT,
    played_at_ms INTEGER NOT NULL,
    position_ms INTEGER NOT NULL CHECK(position_ms >= 0),
    completed INTEGER NOT NULL CHECK(completed IN (0,1))
);
CREATE TABLE resume_state (
    track_id TEXT PRIMARY KEY NOT NULL REFERENCES track(id) ON DELETE RESTRICT,
    position_ms INTEGER NOT NULL CHECK(position_ms >= 0),
    completed INTEGER NOT NULL CHECK(completed IN (0,1)),
    updated_at_ms INTEGER NOT NULL
);
CREATE TABLE player_session (
    singleton INTEGER PRIMARY KEY CHECK(singleton = 1),
    snapshot_version INTEGER NOT NULL CHECK(snapshot_version = 1),
    snapshot_json TEXT NOT NULL,
    saved_at_ms INTEGER NOT NULL
    -- Playback restoration is always paused; no persisted autoplay flag.
);
CREATE TABLE artwork_override (
    track_id TEXT PRIMARY KEY NOT NULL REFERENCES track(id) ON DELETE RESTRICT,
    private_file TEXT NOT NULL,
    source_url TEXT,
    attribution TEXT
);
CREATE TABLE lyrics_version (
    id TEXT PRIMARY KEY NOT NULL,
    track_id TEXT NOT NULL REFERENCES track(id) ON DELETE RESTRICT,
    origin TEXT NOT NULL CHECK(origin IN ('USER_SELECTED','NEIGHBORING_LRC','EMBEDDED')),
    original_text TEXT NOT NULL,
    timing_adjustment_ms INTEGER NOT NULL DEFAULT 0 CHECK(timing_adjustment_ms BETWEEN -86400000 AND 86400000),
    instrumental INTEGER NOT NULL DEFAULT 0 CHECK(instrumental IN (0,1)),
    provider_id TEXT,
    source_url TEXT,
    UNIQUE(id, track_id)
);
CREATE TABLE lyrics_selection (
    track_id TEXT PRIMARY KEY NOT NULL REFERENCES track(id) ON DELETE RESTRICT,
    version_id TEXT NOT NULL,
    FOREIGN KEY(version_id, track_id) REFERENCES lyrics_version(id, track_id) ON DELETE RESTRICT
);
CREATE TABLE provider_identity (
    id TEXT PRIMARY KEY NOT NULL,
    local_kind TEXT NOT NULL CHECK(local_kind IN ('ARTIST','RELEASE')),
    local_id TEXT NOT NULL,
    provider TEXT NOT NULL,
    provider_id TEXT NOT NULL,
    source_url TEXT NOT NULL,
    selected_at_ms INTEGER NOT NULL,
    UNIQUE(local_kind, local_id, provider)
);
CREATE TABLE edit_journal (
    id TEXT PRIMARY KEY NOT NULL,
    track_id TEXT NOT NULL REFERENCES track(id) ON DELETE RESTRICT,
    source_uri TEXT NOT NULL,
    preview_stamp_json TEXT NOT NULL,
    state TEXT NOT NULL CHECK(state IN ('PREVIEW','BACKED_UP','STAGED_VERIFIED','WRITING','SAVED_VERIFIED','RECOVERY_REQUIRED','UNDONE')),
    original_backup_file TEXT,
    staged_file TEXT,
    original_payload_sha256 TEXT,
    saved_stamp_json TEXT,
    error TEXT,
    created_at_ms INTEGER NOT NULL,
    verified_at_ms INTEGER,
    retain_until_ms INTEGER
);
PRAGMA user_version = 1;
