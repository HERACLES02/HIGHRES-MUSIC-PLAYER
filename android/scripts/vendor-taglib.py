"""Fetch/verify the unmodified official TagLib source distribution; local build only."""
import hashlib, json, tarfile, urllib.request
from pathlib import Path

root = Path(__file__).resolve().parents[1]
archive = root / '.tools/downloads/taglib-2.3.2.tar.gz'
expected = '3ca2d8afaa7f1cf7f6ed10e511ebc368bfacd6dcaa3dbfa690b89e502e8963dc'
destination = root / '.tools/taglib-2.3.2'
if not archive.exists():
    archive.parent.mkdir(parents=True, exist_ok=True)
    archive.write_bytes(urllib.request.urlopen('https://taglib.org/releases/taglib-2.3.2.tar.gz', timeout=120).read())
assert hashlib.sha256(archive.read_bytes()).hexdigest() == expected, 'TagLib source archive checksum mismatch'
if not destination.exists():
    with tarfile.open(archive) as tar:
        assert all(n.name.startswith('taglib-2.3.2/') and not n.issym() and not n.islnk() for n in tar.getmembers())
        tar.extractall(root / '.tools', filter='data')
manifest = root / 'native-tags/source-lock.json'
if manifest.exists():
    lock = json.loads(manifest.read_text())
    for name, digest in lock['files'].items():
        assert hashlib.sha256((destination / name).read_bytes()).hexdigest() == digest, name + ' changed'
print('PASS: TagLib 2.3.2 official archive and pinned source tree verified.')
