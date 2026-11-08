"""Generated-fixture owner-managed seeks/replacement; no playback or user music."""
import argparse
import hashlib
import json
from pathlib import Path
import random
import re
import subprocess
import tempfile
import time
import uuid
import wave

parser = argparse.ArgumentParser()
parser.add_argument('exe', type=Path)
parser.add_argument('--android')
parser.add_argument('--stress', action='store_true')
args = parser.parse_args()
exe = args.exe.resolve()
root = Path(__file__).resolve().parents[1]
adb = root / '.tools/sdk/platform-tools/adb.exe'
work = root / '.tools/flac-owner-tests'
work.mkdir(parents=True, exist_ok=True)
remote = '/data/local/tmp/localmusic-flac-owner-' + uuid.uuid4().hex[:8]
assert re.fullmatch(r'/data/local/tmp/localmusic-flac-owner-[0-9a-f]{8}', remote)
evidence = work / (time.strftime('%Y%m%dT%H%M%SZ', time.gmtime()) + '-' + remote[-8:])
evidence.mkdir()
report = dict(platform=args.android or 'host', stress=args.stress,
              binarySha256=hashlib.sha256(exe.read_bytes()).hexdigest(), runs=[])


def bridge(*command):
    return subprocess.run([str(adb), '-s', args.android, *command], capture_output=True, check=True, timeout=30)


def fixture(folder, tag, bits, rate, seed):
    wav_path = folder / f'{tag}.wav'
    pcm = random.Random(seed).randbytes((rate + 137) * (bits // 8) * 2)
    with wave.open(str(wav_path), 'wb') as wav:
        wav.setparams((2, bits // 8, rate, 0, 'NONE', ''))
        wav.writeframes(pcm)
    flac = wav_path.with_suffix('.flac')
    subprocess.run(['ffmpeg', '-v', 'error', '-i', str(wav_path), '-c:a', 'flac', str(flac)], check=True)
    reference = subprocess.run(['ffmpeg', '-v', 'error', '-i', str(flac), '-f', f's{bits}le', '-'], capture_output=True, check=True).stdout
    assert reference == pcm
    raw = wav_path.with_suffix('.pcm')
    raw.write_bytes(reference)
    return flac, raw


try:
    if args.android:
        bridge('shell', 'mkdir', remote)
        bridge('push', str(exe), remote + '/owner')
        bridge('shell', 'chmod', '700', remote + '/owner')
    with tempfile.TemporaryDirectory(dir=work) as temporary:
        formats = [(16, 44100), (24, 96000)] if args.stress else [(b, r) for b in (16, 24) for r in (44100, 48000, 88200, 96000)]
        for bits, rate in formats:
            folder = Path(temporary) / f'{bits}-{rate}'
            folder.mkdir()
            bits_b, rate_b = (24 if bits == 16 else 16), (96000 if rate != 96000 else 44100)
            a, raw_a = fixture(folder, 'a', bits, rate, 20261109)
            b, raw_b = fixture(folder, 'b', bits_b, rate_b, 137)
            preserved = {path: hashlib.sha256(path.read_bytes()).hexdigest() for path in (b, raw_a, raw_b)}
            encoded = a.read_bytes()
            wrong = bytearray(encoded); wrong[26] ^= 1
            missing = bytearray(encoded); missing[26:42] = bytes(16)
            modes = [('stress', encoded, 'stress', 100)] if args.stress else [
                ('valid', encoded, 'valid', 19), ('bad-md5', wrong, 'error', 2),
                ('truncated', encoded[:-1], 'error', 2), ('missing-md5', missing, 'unverified', 1), ('fd', encoded, 'fd', 8)]
            for label, content, mode, count in modes:
                a.write_bytes(content)
                if args.android:
                    for path, name in ((a, 'a.flac'), (raw_a, 'a.pcm'), (b, 'b.flac'), (raw_b, 'b.pcm')):
                        bridge('push', str(path), remote + '/' + name)
                    command = [str(adb), '-s', args.android, 'shell', '-T', remote + '/owner',
                               remote + '/a.flac', remote + '/a.pcm', str(rate), str(bits),
                               remote + '/b.flac', remote + '/b.pcm', str(rate_b), str(bits_b), mode]
                else:
                    command = [str(exe), str(a), str(raw_a), str(rate), str(bits), str(b), str(raw_b), str(rate_b), str(bits_b), mode]
                result = subprocess.run(command, capture_output=True, timeout=35)
                (evidence / f'{bits}-{rate}-{label}.log').write_bytes(result.stdout + result.stderr)
                assert result.returncode == 0 and f'PASS: {count} source-owner cases'.encode() in result.stdout, (bits, rate, label, result.stdout, result.stderr)
                entry = dict(bits=bits, rate=rate, replacementBits=bits_b, replacementRate=rate_b, mode=label, cases=count)
                if args.stress:
                    match = re.search(rb'MEMORY: (\d+) (\d+) KiB', result.stdout)
                    assert match
                    entry['residentAfter10KiB'], entry['residentAfter100KiB'] = map(int, match.groups())
                    if args.android:
                        assert entry['residentAfter10KiB'] > 0 and entry['residentAfter100KiB'] > 0
                if label == 'valid':
                    negative = subprocess.run([*command[:-1], 'unsafe-deliver'], capture_output=True, timeout=35)
                    (evidence / f'{bits}-{rate}-negative.log').write_bytes(negative.stdout + negative.stderr)
                    assert negative.returncode == 1 and b'stale consumer block must never reach copy callback' in negative.stderr
                    entry['negativeControlDetected'] = True
                assert a.read_bytes() == content and all(hashlib.sha256(path.read_bytes()).hexdigest() == digest for path, digest in preserved.items())
                report['runs'].append(entry)
            print(f'PASS: {bits}/{rate}: {"100 repeated seek/format-switch cycles" if args.stress else "32 owner/fd cases + stale-delivery control"} ({args.android or "host"})', flush=True)
finally:
    if args.android:
        bridge('shell', 'rm', '-rf', remote)
        report['remoteCleaned'] = bridge('shell', 'test', '!', '-e', remote).returncode == 0
    (evidence / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
print(f'PASS: source-owner {sum(entry["cases"] for entry in report["runs"])} cases; evidence: {evidence}')
