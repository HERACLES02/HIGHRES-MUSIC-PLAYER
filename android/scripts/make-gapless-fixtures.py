"""Disposable, quiet continuous-tone splits for listening checks; never touches real music."""
import hashlib
import json
import math
from pathlib import Path
import wave
import zipfile

root = Path(__file__).resolve().parents[1] / "releases" / "gapless-check"
root.mkdir(parents=True, exist_ok=True)
records = []
for rate, bits in ((44100, 16), (96000, 24)):
    width = bits // 8
    frames = bytearray()
    for i in range(rate * 12):
        value = round(0.08 * ((1 << (bits - 1)) - 1) * math.sin(2 * math.pi * 443 * i / rate + 0.3))
        frames.extend(value.to_bytes(width, "little", signed=True) * 2)
    split = rate * 6 * width * 2
    prefix = f"gapless-{rate}-{bits}"
    paths = []
    for suffix, pcm in (("01", frames[:split]), ("02", frames[split:]), ("reference", frames)):
        path = root / f"{prefix}-{suffix}.wav"
        with wave.open(str(path), "wb") as out:
            out.setnchannels(2)
            out.setsampwidth(width)
            out.setframerate(rate)
            out.writeframes(pcm)
        paths.append(path)
    def read(path):
        with wave.open(str(path), "rb") as source:
            return source.getparams(), source.readframes(source.getnframes())
    a, b, ref = map(read, paths)
    assert a[0][:3] == b[0][:3] == ref[0][:3]
    assert a[1] + b[1] == ref[1] == frames
    playlist = root / f"{prefix}.m3u8"
    playlist.write_text("#EXTM3U\n" + "\n".join(p.name for p in paths[:2]) + "\n", encoding="utf-8")
    records.append({"rate": rate, "bits": bits, "channels": 2, "split_seconds": 6,
                    "joined_pcm_sha256": hashlib.sha256(frames).hexdigest(), "pcm_join_verified": True})
(root / "evidence.json").write_text(json.dumps(records, indent=2) + "\n", encoding="utf-8")
(root / "LISTENING.txt").write_text(
    "Set a comfortable volume. Copy this folder to the phone and scan it.\n"
    "Play 01 followed by 02 at the SAME rate and depth; compare with reference.\n"
    "The split at six seconds should have no added silence, restart, click or pop.\n"
    "Repeat with screen locked, manual queue and repeat modes.\n"
    "Different output rates and compressed codecs need separate checks.\n"
    "PCM equality here proves the fixtures, not physical output or audible gaplessness.\n", encoding="utf-8")
archive = root.parent / "local-music-gapless-check.zip"
with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED) as out:
    for path in sorted(root.iterdir()):
        if path.is_file():
            out.write(path, "gapless-check/" + path.name)
print(f"PASS: both stereo PCM joins equal their continuous reference. {archive}")
