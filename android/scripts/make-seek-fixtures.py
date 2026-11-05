"""Generate original 180-second FLACs with absent, 10s and 60s seek tables.

Requires ffmpeg and ffprobe on PATH. Only writes ignored development fixtures.
"""
import json
from pathlib import Path
import struct
import subprocess

directory = Path(__file__).resolve().parents[1] / ".tools" / "fixtures"
directory.mkdir(parents=True, exist_ok=True)
source = directory / "seek-no-table.flac"
subprocess.run([
    "ffmpeg", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i",
    "anoisesrc=color=pink:sample_rate=44100:amplitude=0.08:seed=4567",
    "-t", "180", "-ac", "2", "-c:a", "flac", "-compression_level", "0",
    "-sample_fmt", "s16", "-y", str(source),
], check=True)
frames = json.loads(subprocess.check_output([
    "ffprobe", "-v", "error", "-select_streams", "a:0", "-show_frames",
    "-show_entries", "frame=pts,pkt_pos,nb_samples", "-of", "json", str(source),
]))["frames"]
data = source.read_bytes()
assert data[:4] == b"fLaC"
position = 4
while True:
    last = position
    header = data[position]
    assert header & 127 != 3, "Expected generated input without a seek table"
    position += 4 + int.from_bytes(data[position + 1:position + 4], "big")
    if header & 128:
        break
head = bytearray(data[:position])
head[last] &= 127
for name, spacing in [("sparse", 10), ("very-sparse", 60)]:
    points = []
    next_second = 0
    for frame in frames:
        if int(frame["pts"]) >= next_second * 44100:
            points.append(struct.pack(">QQH", int(frame["pts"]),
                int(frame["pkt_pos"]) - position, int(frame["nb_samples"])))
            next_second += spacing
    table = b"".join(points)
    target = source.with_name("seek-" + name + ".flac")
    target.write_bytes(head + bytes([128 | 3]) + len(table).to_bytes(3, "big") + table + data[position:])
    print(f"{target}: {len(points)} seek points, {target.stat().st_size} bytes")
