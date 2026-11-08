"""Controlled real-reader worker/IO tests; generated fixtures and test-only gates."""
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
args = parser.parse_args()
exe = args.exe.resolve()
root = Path(__file__).resolve().parents[1]
adb = root / '.tools/sdk/platform-tools/adb.exe'
work = root / '.tools/flac-worker-tests'
work.mkdir(parents=True, exist_ok=True)
remote = '/data/local/tmp/localmusic-flac-worker-' + uuid.uuid4().hex[:8]
assert re.fullmatch(r'/data/local/tmp/localmusic-flac-worker-[0-9a-f]{8}', remote)
stamp = time.strftime('%Y%m%dT%H%M%SZ', time.gmtime())
evidence = work / f'{stamp}-{remote[-8:]}'
evidence.mkdir()
report = dict(platform=args.android or 'host', binarySha256=hashlib.sha256(exe.read_bytes()).hexdigest(), runs=[])


def bridge(*command):
    return subprocess.run([str(adb), '-s', args.android, *command], capture_output=True, check=True, timeout=30)


try:
    if args.android:
        bridge('shell', 'mkdir', remote)
        bridge('push', str(exe), remote + '/worker')
        bridge('shell', 'chmod', '700', remote + '/worker')
    with tempfile.TemporaryDirectory(dir=work) as temporary:
        for bits, rate in ((16, 44100), (24, 96000)):
            folder = Path(temporary)
            tag = f'{bits}-{rate}'
            source = folder / f'{tag}.wav'
            frames = rate * 3 + 137
            pcm = random.Random(20261109).randbytes(frames * 2 * (bits // 8))
            with wave.open(str(source), 'wb') as wav:
                wav.setparams((2, bits // 8, rate, 0, 'NONE', ''))
                wav.writeframes(pcm)
            flac = source.with_suffix('.flac')
            subprocess.run(['ffmpeg', '-v', 'error', '-i', str(source), '-c:a', 'flac', str(flac)], check=True)
            reference = subprocess.run(['ffmpeg', '-v', 'error', '-i', str(flac), '-f', f's{bits}le', '-'],
                                       capture_output=True, check=True).stdout
            assert reference == pcm
            raw = source.with_suffix('.pcm')
            raw.write_bytes(reference)
            original_hash = hashlib.sha256(flac.read_bytes()).hexdigest()
            if args.android:
                bridge('push', str(flac), remote + '/source.flac')
                bridge('push', str(raw), remote + '/reference.pcm')
                command = [str(adb), '-s', args.android, 'shell', '-T', remote + '/worker',
                           remote + '/source.flac', remote + '/reference.pcm']
            else:
                command = [str(exe), str(flac), str(raw)]
            result = subprocess.run(command, capture_output=True, timeout=60)
            output = result.stdout.decode()
            error = result.stderr.decode()
            (evidence / f'{tag}.log').write_text(output + error)
            assert result.returncode == 0 and 'PASS: 23 worker cases' in output, (result.returncode, output, error)
            # Deliberately omit the test owner's pre-publication cancellation
            # check. The real decoder is unchanged in this negative control.
            negative = subprocess.run([*command, '--unsafe-publish'], capture_output=True, timeout=30)
            negative_error = negative.stderr.decode()
            (evidence / f'{tag}-negative.log').write_text(negative.stdout.decode() + negative_error)
            assert negative.returncode == 1 and 'owner must discard borrowed block after cancel' in negative_error
            assert hashlib.sha256(flac.read_bytes()).hexdigest() == original_hash
            report['runs'].append(dict(bits=bits, rate=rate, cases=23, negativeControlDetected=True,
                                       fixtureSha256=original_hash, pcmSha256=hashlib.sha256(pcm).hexdigest()))
            print(f'PASS: {tag}: 23 worker cases and unsafe-publication negative control ({args.android or "host"})', flush=True)
finally:
    if args.android:
        bridge('shell', 'rm', '-rf', remote)
        report['remoteCleaned'] = bridge('shell', 'test', '!', '-e', remote).returncode == 0
    (evidence / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
print(f'PASS: 46 worker cases + 2 expected negative-control failures; evidence: {evidence}')
