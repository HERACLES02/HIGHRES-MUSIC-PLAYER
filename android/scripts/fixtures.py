"""Generate quiet local test signals; verify every FLAC against exact reference PCM.

Requires Python 3 and local ffmpeg. No network, recordings or third-party music.
Generated audio stays in .tools; the reference manifest belongs in source control.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
import shutil
import subprocess
import wave

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / '.tools' / 'fixtures'
MANIFEST = ROOT / 'fixtures-manifest.json'


def sha(data):
    return hashlib.sha256(data).hexdigest()


def reference(rate, bits):
    # Six seconds: left only, right only, then both. Gentle edges every two seconds.
    # -42 dBFS peak. 24-bit values retain meaningful low bits and both signs.
    scale = int(((1 << (bits - 1)) - 1) * 10 ** (-42 / 20))
    pcm = bytearray()
    for frame in range(rate * 6):
        phase = (frame % (rate * 2)) / rate
        fade = min(1.0, phase / .02, (2 - phase) / .02)
        section = frame // (rate * 2)
        for channel, frequency in enumerate((440, 660)):
            value = round(scale * fade * math.sin(2 * math.pi * frequency * frame / rate))
            if (section == 0 and channel == 1) or (section == 1 and channel == 0):
                value = 0
            pcm.extend(value.to_bytes(bits // 8, 'little', signed=True))
    return bytes(pcm)


def run(*args):
    return subprocess.run(args, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--generate', action='store_true', help='Explicitly regenerate fixtures and reference manifest')
    parser.add_argument('--ffmpeg', help='Full path to an existing local ffmpeg binary')
    parser.add_argument('--decode', action='store_true', help='Also repeat exact FLAC/WAV decode comparisons using ffmpeg')
    args = parser.parse_args()
    if not args.generate and MANIFEST.exists() and not args.decode:
        expected = json.loads(MANIFEST.read_text(encoding='utf-8'))
        if all((OUT / item['file']).exists() for item in expected['fixtures']):
            for item in expected['fixtures']:
                if sha((OUT / item['file']).read_bytes()) != item['fileSha256']:
                    raise SystemExit(f"Fixture checksum mismatch: {item['file']}")
            (OUT / 'fixtures-manifest.json').write_text(MANIFEST.read_text(encoding='utf-8'), encoding='utf-8')
            print('PASS: 16 fixture checksums match the PCM-verified reference manifest.')
            return
    ffmpeg = args.ffmpeg or shutil.which('ffmpeg')
    if not ffmpeg:
        raise SystemExit('Install ffmpeg locally to generate/verify the diagnostic fixtures.')
    OUT.mkdir(parents=True, exist_ok=True)
    records = []
    for bits in (16, 24):
        for rate in (44100, 48000, 88200, 96000):
            pcm = reference(rate, bits)
            base = f'stereo-{bits}-{rate}'
            wav = OUT / (base + '.wav')
            flac = OUT / (base + '.flac')
            if args.generate or not wav.exists() or not flac.exists():
                with wave.open(str(wav), 'wb') as output:
                    output.setparams((2, bits // 8, rate, 0, 'NONE', 'not compressed'))
                    output.writeframes(pcm)
                run(ffmpeg, '-v', 'error', '-y', '-i', str(wav), '-map_metadata', '-1', '-c:a', 'flac', str(flac))
            for path in (wav, flac):
                decoded = run(ffmpeg, '-v', 'error', '-i', str(path), '-f', f's{bits}le', '-acodec', f'pcm_s{bits}le', '-')
                if decoded != pcm:
                    raise SystemExit(f'Exact PCM mismatch: {path.name}')
                records.append(dict(file=path.name, sampleRateHz=rate, meaningfulBits=bits,
                                    channels=2, durationMs=6000, peakDbfs=-42,
                                    pcmSha256=sha(pcm), fileSha256=sha(path.read_bytes())))
    manifest = dict(schemaVersion=1, signal='0-2s left 440Hz; 2-4s right 660Hz; 4-6s both; 20ms fades',
                    encoder=run(ffmpeg, '-version').decode().splitlines()[0], fixtures=records)
    if args.generate:
        MANIFEST.write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    elif json.loads(MANIFEST.read_text(encoding='utf-8'))['fixtures'] != records:
        raise SystemExit('Fixture hashes differ from the reference manifest. Do not silently replace it; inspect the encoder/version change.')
    (OUT / 'fixtures-manifest.json').write_text(MANIFEST.read_text(encoding='utf-8'), encoding='utf-8')
    print(f'PASS: {len(records)} WAV/FLAC files, exact signed stereo PCM including low 24-bit values.')


if __name__ == '__main__':
    main()
