"""Offline FLAC/PCM-queue comparisons and optional long-source pipeline checks."""
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
parser.add_argument('--long', action='store_true')
args = parser.parse_args()
exe = args.exe.resolve()
root = Path(__file__).resolve().parents[1]
adb = root / '.tools/sdk/platform-tools/adb.exe'
work = root / '.tools/flac-queue-tests'
work.mkdir(parents=True, exist_ok=True)
remote = '/data/local/tmp/localmusic-flac-queue-' + uuid.uuid4().hex[:8]
assert re.fullmatch(r'/data/local/tmp/localmusic-flac-queue-[0-9a-f]{8}', remote)
evidence = work / (time.strftime('%Y%m%dT%H%M%SZ', time.gmtime()) + '-' + remote[-8:])
evidence.mkdir()
report = dict(platform=args.android or 'host', long=args.long,
              binarySha256=hashlib.sha256(exe.read_bytes()).hexdigest(), runs=[])


def bridge(*command, timeout=60):
    return subprocess.run([str(adb), '-s', args.android, *command], capture_output=True, check=True, timeout=timeout)


def reference_hash(path):
    digest = hashlib.sha256()
    with subprocess.Popen(['ffmpeg', '-v', 'error', '-i', str(path), '-f', 's24le', '-'], stdout=subprocess.PIPE) as process:
        while chunk := process.stdout.read(256 * 1024):
            digest.update(chunk)
        assert process.wait(timeout=30) == 0
    return digest.hexdigest()


try:
    if args.android:
        bridge('shell', 'mkdir', remote)
        bridge('push', str(exe), remote + '/pipeline')
        bridge('shell', 'chmod', '700', remote + '/pipeline')
    with tempfile.TemporaryDirectory(dir=work) as temporary:
        folder = Path(temporary)
        if args.long:
            for seconds in (60, 1200):
                path = folder / f'{seconds}.flac'
                subprocess.run(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i', 'anullsrc=r=96000:cl=stereo',
                                '-t', str(seconds), '-sample_fmt', 's32', '-c:a', 'flac', str(path)], check=True)
                expected = reference_hash(path)
                if args.android:
                    bridge('push', str(path), remote + '/source.flac')
                    # Hash on Android; never retain a complete long PCM file or
                    # pass binary PCM through Windows `adb shell` text output.
                    command = (f'{{ {remote}/pipeline {remote}/source.flac --pipe 2>{remote}/metrics; '
                               f'echo $? >{remote}/exit; }} | sha256sum >{remote}/hash')
                    bridge('shell', '-T', command, timeout=150)
                    assert bridge('shell', 'cat', remote + '/exit').stdout.strip() == b'0'
                    digest = bridge('shell', 'cat', remote + '/hash').stdout.split()[0].decode()
                    metrics = json.loads(bridge('shell', 'cat', remote + '/metrics').stdout)
                    assert 0 < metrics['residentHighWaterKiB'] < 64 * 1024
                else:
                    digest = hashlib.sha256()
                    with tempfile.TemporaryFile() as errors:
                        with subprocess.Popen([str(exe), str(path), '--pipe'], stdout=subprocess.PIPE, stderr=errors) as process:
                            while chunk := process.stdout.read(256 * 1024):
                                digest.update(chunk)
                            assert process.wait(timeout=30) == 0
                        errors.seek(0)
                        metrics = json.loads(errors.read())
                    digest = digest.hexdigest()
                assert digest == expected and metrics['status'] == 'End' and metrics['md5'] == 1
                assert metrics['bytes'] == seconds * 96000 * 6 and metrics['queueBytes'] == 24576
                report['runs'].append(dict(seconds=seconds, pcmSha256=digest, metrics=metrics))
                print(f'PASS: {seconds}s decoder-to-queue hash, bytes, EOF/MD5; process RSS high-water '
                      f'{metrics["residentHighWaterKiB"]} KiB ({args.android or "host: memory not measured"})', flush=True)
            if args.android:
                assert report['runs'][1]['metrics']['residentHighWaterKiB'] <= report['runs'][0]['metrics']['residentHighWaterKiB'] + 4096
        else:
            for bits in (16, 24):
                for rate in (44100, 48000, 88200, 96000):
                    tag = f'{bits}-{rate}'
                    source = folder / f'{tag}.wav'
                    pcm = random.Random(20261109).randbytes((rate + 137) * 2 * (bits // 8))
                    with wave.open(str(source), 'wb') as wav:
                        wav.setparams((2, bits // 8, rate, 0, 'NONE', ''))
                        wav.writeframes(pcm)
                    flac = source.with_suffix('.flac')
                    subprocess.run(['ffmpeg', '-v', 'error', '-i', str(source), '-c:a', 'flac', str(flac)], check=True)
                    reference = subprocess.run(['ffmpeg', '-v', 'error', '-i', str(flac), '-f', f's{bits}le', '-'],
                                               capture_output=True, check=True).stdout
                    assert reference == pcm
                    original = flac.read_bytes()
                    wrong = bytearray(original); wrong[26] ^= 1
                    missing = bytearray(original); missing[26:42] = bytes(16)
                    cases = [('valid', original, pcm, 13), ('error', wrong, pcm, 2),
                             ('truncated', original[:-1], pcm, 1), ('unverified', missing, pcm, 1),
                             ('partial', original, pcm[5001 * 2 * (bits // 8):], 1)]
                    for mode, encoded, expected, count in cases:
                        fixture, raw = folder / 'case.flac', folder / 'case.pcm'
                        fixture.write_bytes(encoded); raw.write_bytes(expected)
                        if args.android:
                            bridge('push', str(fixture), remote + '/source.flac')
                            bridge('push', str(raw), remote + '/reference.pcm')
                            command = [str(adb), '-s', args.android, 'shell', '-T', remote + '/pipeline',
                                       remote + '/source.flac', remote + '/reference.pcm', mode]
                        else:
                            command = [str(exe), str(fixture), str(raw), mode]
                        result = subprocess.run(command, capture_output=True, timeout=60)
                        (evidence / f'{tag}-{mode}.log').write_bytes(result.stdout + result.stderr)
                        assert result.returncode == 0 and b'PASS:' in result.stdout, (tag, mode, result.stdout, result.stderr)
                        if mode == 'valid':
                            negative = subprocess.run([*command[:-1], 'unsafe-token'], capture_output=True, timeout=30)
                            (evidence / f'{tag}-negative.log').write_bytes(negative.stdout + negative.stderr)
                            assert negative.returncode == 1 and b'retired producer must respect terminal state/generation' in negative.stderr
                        assert fixture.read_bytes() == encoded and raw.read_bytes() == expected
                        report['runs'].append(dict(bits=bits, rate=rate, mode=mode, cases=count,
                                                   pcmSha256=hashlib.sha256(expected).hexdigest()))
                    print(f'PASS: {tag}: 18 handoff cases + stale-token negative control ({args.android or "host"})', flush=True)
finally:
    if args.android:
        bridge('shell', 'rm', '-rf', remote)
        report['remoteCleaned'] = bridge('shell', 'test', '!', '-e', remote).returncode == 0
    (evidence / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
print(f'PASS: decoder-to-queue {"long-source" if args.long else "144 cases + 8 negative controls"}; evidence: {evidence}')
