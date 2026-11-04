"""Generate disposable music and artwork for a hosted Android UI review.

Uses only original synthetic audio/images. Never reads the user's music.
FFmpeg and the existing native TagLib checker must already be installed.
"""
from pathlib import Path
import hashlib
import json
import math
import shutil
import struct
import subprocess
import tempfile
import wave
import zipfile
import zlib


PROJECT = Path(__file__).resolve().parents[1]
DEST = PROJECT / "releases" / "cloud-preview"
FILES = DEST / "files"


def run(*args):
    return subprocess.run(list(map(str, args)), check=True, capture_output=True).stdout


def cover(path, palette):
    """An original geometric PNG, without external artwork or image libraries."""
    rows = bytearray()
    for y in range(512):
        rows.append(0)
        for x in range(512):
            t = (x + y) / 1022
            pixel = tuple(round(a * (1 - t) + b * t) for a, b in zip(*palette))
            if (x - 256) ** 2 + (y - 220) ** 2 < 100 ** 2:
                pixel = tuple(min(255, v + 65) for v in pixel)
            if y > 360 + 30 * math.sin(x / 65):
                pixel = tuple(round(v * .55) for v in pixel)
            rows.extend(pixel)
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    path.write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 512, 512, 8, 2, 0, 0, 0))
                     + chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b""))


def main():
    ffmpeg = shutil.which("ffmpeg")
    checker = PROJECT / ".tools" / "tag-host" / "tag-check.exe"
    if not ffmpeg or not checker.is_file():
        raise SystemExit("Existing FFmpeg and android/.tools/tag-host/tag-check.exe are required.")
    FILES.mkdir(parents=True, exist_ok=True)
    records = []
    albums = [
        ("Harbor Lights", "Preview Artist One", ((24, 69, 90), (55, 161, 162)),
         [("Morning Tide", 105, 220), ("Still Water", 14, 330)]),
        ("Evening Signals", "Preview Artist Two", ((94, 51, 48), (218, 145, 69)),
         [("Amber Lines", 16, 275), ("Quiet Circuit", 18, 440)]),
        ("Paper Satellites", "Preview Artist Three", ((67, 53, 109), (161, 136, 188)),
         [("Paper Sky", 20, 330), ("Afterglow", 22, 495)]),
    ]
    with tempfile.TemporaryDirectory(prefix="cloud-preview-", dir=DEST) as scratch:
        for album_index, (album, artist, palette, tracks) in enumerate(albums, 1):
            image = FILES / f"cover-{album_index}.png"
            cover(image, palette)
            for track_number, (title, seconds, frequency) in enumerate(tracks, 1):
                name = f"{album_index}{track_number}-{title.lower().replace(' ', '-')}.flac"
                output = FILES / name
                unit = bytearray()
                for i in range(44100):
                    value = round(32767 * (.025 * math.sin(2 * math.pi * frequency * i / 44100)
                                          + .01 * math.sin(2 * math.pi * frequency * 2 * i / 44100)))
                    unit.extend(struct.pack("<hh", value, value))
                pcm = bytes(unit) * seconds
                wav = Path(scratch) / "original.wav"
                with wave.open(str(wav), "wb") as stream:
                    stream.setnchannels(2); stream.setsampwidth(2); stream.setframerate(44100)
                    stream.writeframes(pcm)
                run(ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error", "-y", "-i", wav, "-i", image,
                    "-map", "0:a", "-map", "1:v", "-c:a", "flac", "-c:v", "copy", "-disposition:v", "attached_pic",
                    "-metadata", f"title={title}", "-metadata", f"artist={artist}", "-metadata", f"album={album}",
                    "-metadata", f"album_artist={artist}", "-metadata", f"track={track_number}",
                    "-metadata:s:v", "title=Album cover", "-metadata:s:v", "comment=Cover (front)", output)
                extracted = Path(scratch) / "embedded-cover.png"
                tags = json.loads(run(checker, "read", output, extracted))
                for key, value in {"TITLE": title, "ARTIST": artist, "ALBUM": album}.items():
                    assert tags["properties"][key] == [value], (name, key)
                assert tags["pictureCount"] == 1, name
                assert extracted.read_bytes() == image.read_bytes(), f"Embedded cover differs: {name}"
                decoded = run(ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error", "-i", output,
                              "-map", "0:a:0", "-f", "s16le", "-acodec", "pcm_s16le", "-")
                assert decoded == pcm, f"FLAC PCM round-trip differs: {name}"
                lyrics = f"[ti:{title}]\n[ar:{artist}]\n[00:01.00]Original preview lyrics\n[00:04.00]A quiet tone, a colored sky\n[00:08.00]These lines are only for testing\n"
                if seconds > 60:
                    lyrics += "[01:05.00]Seek ahead to this line\n[01:30.00]The preview continues here\n"
                output.with_suffix(".lrc").write_text(lyrics, encoding="utf-8")
                records.append({"file": name, "title": title, "artist": artist, "album": album,
                                "seconds": seconds, "sampleRateHz": 44100, "channels": 2, "bits": 16,
                                "sha256": hashlib.sha256(output.read_bytes()).hexdigest(),
                                "pcmSha256": hashlib.sha256(pcm).hexdigest(), "tagsAndCoverVerified": True})
    playlist = "#EXTM3U\n" + "".join(f"#EXTINF:{r['seconds']},{r['artist']} - {r['title']}\n{r['file']}\n" for r in records)
    (FILES / "Preview Mix.m3u8").write_text(playlist, encoding="utf-8")
    sample = PROJECT.parent / "design" / "listening-tools" / "warm-headphones.json"
    shutil.copyfile(sample, FILES / "warm-headphones.json")
    (DEST / "manifest.json").write_text(json.dumps(records, indent=2) + "\n", encoding="utf-8")
    (DEST / "START-HERE.txt").write_text(
        "Local Music cloud visual review: original disposable samples only.\n"
        "Use a comfortable volume; the audio consists of quiet synthetic tones, not songs.\n"
        "Upload the current Local Music APK to your Appetize Free account.\n"
        "Select Android 11 or newer for this ARM APK. Upload individual files from files/ to the device.\n"
        "Allow music access and scan; if needed, grant the folder containing the uploaded files.\n"
        "Expected library: six tracks, three artists and three albums, each with embedded cover art.\n"
        "Add a favorite, play/pause a track, and reopen Home to populate its listening blocks.\n"
        "Review Home grid/list, album/artist pages, playlist cover, queue and Now Playing.\n"
        "Import Preview Mix.m3u8 after the songs are scanned; it uses relative file names.\n"
        "The LRC sidecars provide original timed lyrics. The JSON is an optional test EQ preset.\n"
        "Morning Tide lasts 105 seconds for long-seek UI checks, not SD-card performance claims.\n"
        "Check light/dark themes and large system text. Capture screenshots rather than recording long sessions.\n"
        "Stay within the Free allowance. No personal music, app backup, credentials or signing keys are included.\n",
        encoding="utf-8")
    archive = DEST.parent / "local-music-cloud-preview.zip"
    # Include only files generated above, even if other files were later placed in this folder.
    selected = [FILES / r["file"] for r in records]
    selected += [p.with_suffix(".lrc") for p in selected]
    selected += [FILES / f"cover-{i}.png" for i in range(1, 4)]
    selected += [FILES / "Preview Mix.m3u8", FILES / "warm-headphones.json"]
    selected += [DEST / "manifest.json", DEST / "START-HERE.txt"]
    assert all(p.stat().st_size < 50_000_000 for p in selected)
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as output:
        for path in sorted(selected):
            output.write(path, path.relative_to(DEST))
    assert len(records) == 6 and len({r["album"] for r in records}) == 3
    print(f"PASS: six original FLAC PCM round-trips, native tags and embedded covers verified. {archive}")
    print(f"Archive size: {archive.stat().st_size:,} bytes; individual files are below the 50 MB media limit.")


if __name__ == "__main__":
    main()
