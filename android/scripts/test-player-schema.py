"""Executable v1 schema contracts, before Room integration. No third-party packages."""
from pathlib import Path
import sqlite3
import unittest

SCHEMA = Path(__file__).resolve().parents[1] / 'player-core/schema/v1.sql'

class SchemaChecks(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.db.executescript(SCHEMA.read_text(encoding='utf-8'))
        self.db.execute("INSERT INTO track(id,title,duration_ms,mime_type,availability,added_at_ms) VALUES ('t','Title',100000,'audio/flac','AVAILABLE',1)")
        self.db.execute("INSERT INTO playlist VALUES ('p','Favorites')")

    def tearDown(self):
        self.db.close()

    def test_duplicate_playlist_entries_and_missing_media(self):
        self.db.executemany("INSERT INTO playlist_entry VALUES (?, 'p','t',?)", [('e1', 0), ('e2', 1)])
        self.db.execute("INSERT INTO favorite VALUES ('t')")
        self.db.execute("INSERT INTO playback_history VALUES ('h','t',1,20000,0)")
        self.db.execute("UPDATE track SET availability='VOLUME_UNMOUNTED'")
        self.assertEqual(self.db.execute('SELECT count(*) FROM playlist_entry').fetchone()[0], 2)
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("DELETE FROM track WHERE id='t'")
        self.assertEqual(self.db.execute('SELECT count(*) FROM playback_history').fetchone()[0], 1)

    def test_uri_dedup_and_lost_folder_grant(self):
        self.db.execute("INSERT INTO folder_grant VALUES ('g','content://folder',3,1)")
        self.db.execute("INSERT INTO track_source VALUES ('s','t','content://song','DOCUMENT','g',2,NULL,NULL,NULL)")
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO track_source VALUES ('s2','t','content://song','MEDIA_STORE',NULL,2,NULL,NULL,NULL)")
        self.db.execute("DELETE FROM folder_grant WHERE id='g'")
        self.assertEqual(self.db.execute('SELECT track_id,folder_grant_id FROM track_source').fetchone(), ('t', None))

    def test_lyrics_versions_preserved_and_selection_cannot_cross_tracks(self):
        self.db.execute("INSERT INTO track(id,title,duration_ms,mime_type,availability,added_at_ms) VALUES ('t2','Other',1,'audio/mpeg','MISSING',1)")
        self.db.executemany("INSERT INTO lyrics_version(id,track_id,origin,original_text) VALUES (?,'t','USER_SELECTED',?)", [('l1','one'), ('l2','two')])
        self.db.execute("INSERT INTO lyrics_selection VALUES ('t','l1')")
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO lyrics_selection VALUES ('t2','l2')")
        self.assertEqual(self.db.execute('SELECT count(*) FROM lyrics_version').fetchone()[0], 2)

    def test_journal_survives_unavailable_media(self):
        self.db.execute("INSERT INTO edit_journal(id,track_id,source_uri,preview_stamp_json,state,original_backup_file,created_at_ms) VALUES ('j','t','content://song','{}','RECOVERY_REQUIRED','backup.bin',1)")
        self.db.execute("UPDATE track SET availability='PERMISSION_LOST'")
        self.assertEqual(self.db.execute('SELECT state,original_backup_file,retain_until_ms FROM edit_journal').fetchone(), ('RECOVERY_REQUIRED','backup.bin',None))
        self.assertEqual(self.db.execute('PRAGMA foreign_key_check').fetchall(), [])
        self.assertEqual(self.db.execute('PRAGMA user_version').fetchone()[0], 1)

if __name__ == '__main__':
    unittest.main()
