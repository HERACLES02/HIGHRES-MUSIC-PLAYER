import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parents[1]
lock = json.loads((root / 'native-usb/toolchain.json').read_text(encoding='utf-8'))
for name, expected in lock['files'].items():
    actual = hashlib.sha256((root / '.tools/sdk' / name).read_bytes()).hexdigest()
    if actual != expected:
        raise SystemExit(f'Native tool checksum mismatch: {name}')
print('PASS: pinned native compiler and CMake checksums verified.')
