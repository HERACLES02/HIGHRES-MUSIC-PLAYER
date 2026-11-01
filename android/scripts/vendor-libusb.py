"""Extract a checksum-pinned official archive, or verify the vendored source tree."""
import hashlib
import json
from pathlib import Path
import sys
import tarfile

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / 'native-usb' / 'vendor' / 'libusb'
LOCK = ROOT / 'native-usb' / 'sources.json'
ARCHIVE_SHA = '7c2dd39c0b2589236e48c93247c986ae272e27570942b4163cb00a060fcf1b74'

if '--extract' in sys.argv:
    archive = ROOT / '.tools/downloads/libusb-1.0.29.tar.gz'
    if hashlib.sha256(archive.read_bytes()).hexdigest() != ARCHIVE_SHA:
        raise SystemExit('Archive checksum mismatch')
    files = {}
    with tarfile.open(archive) as tar:
        for member in tar:
            relative = member.name.removeprefix('libusb-1.0.29/')
            wanted = (relative.startswith('libusb/') and relative.endswith(('.c', '.h'))) or relative in ('android/config.h', 'COPYING', 'AUTHORS')
            if not wanted or not member.isfile():
                continue
            target = (DEST / relative).resolve()
            if not target.is_relative_to(DEST.resolve()):
                raise SystemExit('Archive path escapes vendor directory')
            data = tar.extractfile(member).read()
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            files[relative] = hashlib.sha256(data).hexdigest()
    LOCK.write_text(json.dumps(dict(version='1.0.29', url='https://codeload.github.com/libusb/libusb/tar.gz/refs/tags/v1.0.29',
                                    archiveSha256=ARCHIVE_SHA, files=files), indent=2) + '\n', encoding='utf-8')

lock = json.loads(LOCK.read_text(encoding='utf-8'))
for name, expected in lock['files'].items():
    if hashlib.sha256((DEST / name).read_bytes()).hexdigest() != expected:
        raise SystemExit(f'Source checksum mismatch: {name}')
print(f"PASS: {len(lock['files'])} pinned libusb source/license files verified.")
