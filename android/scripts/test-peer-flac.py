"""Exercise the Peer Test verifier with disposable FLAC files."""
from pathlib import Path
import subprocess
import sys
from tempfile import TemporaryDirectory


def main(verifier):
    with TemporaryDirectory() as folder:
        root = Path(folder)
        valid = root / "valid.flac"
        subprocess.run([
            "ffmpeg", "-v", "error", "-f", "lavfi", "-i",
            "sine=frequency=440:sample_rate=48000:duration=1", "-c:a", "flac", str(valid),
        ], check=True)
        source = valid.read_bytes()
        assert source[:5] == b"fLaC\x00" and any(source[26:42])
        cases = [
            ("valid", source, len(source), True),
            ("wrong byte count", source, len(source) + 1, False),
            ("wrong audio checksum", source[:26] + bytes([source[26] ^ 1]) + source[27:], len(source), False),
            ("no audio checksum", source[:26] + bytes(16) + source[42:], len(source), True),
            ("corrupt frame", source[:-1] + bytes([source[-1] ^ 1]), len(source), False),
            ("truncated frame without checksum", source[:26] + bytes(16) + source[42:-1], len(source) - 1, False),
            ("trailing data", source + b"bad", len(source) + 3, False),
        ]
        for name, data, size, accepted in cases:
            candidate = root / "candidate.flac"
            candidate.write_bytes(data)
            actual = subprocess.run([verifier, str(candidate), str(size)], check=False).returncode
            assert actual == (0 if accepted else 1), (name, actual)
            print(f"PASS: {name}")


if __name__ == "__main__":
    main(sys.argv[1])
