"""Check frozen Room schema bytes and v1-to-v2 scope. Does not regenerate fixtures."""
import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parents[1] / 'player-data'
lock = json.loads((root / 'schema-lock.json').read_text(encoding='utf-8'))
for relative, expected in lock['schemas'].items():
    assert hashlib.sha256((root / relative).read_bytes()).hexdigest() == expected, relative + ' changed; add a versioned migration'
old = json.loads((root / 'schemas/com.localmusic.data.LegacyDatabaseV1/1.json').read_text())['database']
new = json.loads((root / 'schemas/com.localmusic.data.PlayerDatabase/2.json').read_text())['database']
old_tables = {t['tableName']: t for t in old['entities']}
new_tables = {t['tableName']: t for t in new['entities']}
assert old['version'] == 1 and new['version'] == 2
assert new_tables.keys() - old_tables.keys() == {'session_problem'}
assert all(new_tables[name] == table for name, table in old_tables.items()), 'Migration unexpectedly modifies an existing table'
latest = json.loads((root / 'schemas/com.localmusic.data.PlayerDatabase/3.json').read_text())['database']
latest_tables = {t['tableName']: t for t in latest['entities']}
assert latest['version'] == 3
assert latest_tables.keys() - new_tables.keys() == {'library_extra', 'file_edit'}
assert all(latest_tables[name] == table for name, table in new_tables.items()), 'v3 changed an installed v2 table'
fixture = json.loads((root / 'schemas/com.localmusic.data.LegacyDatabaseV2/2.json').read_text())['database']
assert fixture == new, 'Installed-v2 migration fixture drifted'
print('PASS: 3 frozen schema hashes; v1/v2 tables unchanged; v3 adds only library_extra and file_edit; installed-v2 fixture matches.')
