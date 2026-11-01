"""Verify pinned native FLAC source; explicit --extract uses the official tag archive."""
import hashlib
import json
from pathlib import Path
import sys
import tarfile

root = Path(__file__).resolve().parents[1]
dest = root / 'native-usb/vendor/flac'
lock = root / 'native-usb/flac-sources.json'
archive_hash = '0a4bb82a30609b606650d538a804a7b40205366ce8fc98871b0ecf3fbb0611ee'
if '--extract' in sys.argv:
    archive = root / '.tools/downloads/flac-1.4.3.tar.gz'
    if hashlib.sha256(archive.read_bytes()).hexdigest() != archive_hash:
        raise SystemExit('FLAC archive checksum mismatch')
    files = {}
    with tarfile.open(archive) as tar:
        for entry in tar:
            name = entry.name.removeprefix('flac-1.4.3/')
            if not entry.isfile() or name.startswith(('test/', 'doc/', '.git')):
                continue
            path = (dest / name).resolve()
            if not path.is_relative_to(dest.resolve()):
                raise SystemExit('Unsafe FLAC archive path')
            data = tar.extractfile(entry).read()
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
            files[name] = hashlib.sha256(data).hexdigest()
    lock.write_text(json.dumps(dict(version='1.4.3', url='https://codeload.github.com/xiph/flac/tar.gz/refs/tags/1.4.3',
                                    archiveSha256=archive_hash, files=files), indent=2) + '\n', encoding='utf-8')
for name, expected in json.loads(lock.read_text(encoding='utf-8'))['files'].items():
    if hashlib.sha256((dest / name).read_bytes()).hexdigest() != expected:
        raise SystemExit(f'FLAC source checksum mismatch: {name}')
print('PASS: pinned FLAC source and license hashes verified.')
