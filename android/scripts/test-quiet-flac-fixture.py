"""Offline quiet-fixture preflight plus actual fd bridge/fake-libusb checks."""
import argparse
import hashlib
import json
import random
import re
import subprocess
import tempfile
import time
import uuid
import wave
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('exe', type=Path)
parser.add_argument('work', type=Path)
parser.add_argument('--probe', action='store_true', help='Exercise the actual finite Probe worker')
parser.add_argument('--session-jni', action='store_true', help='Exercise ordinary-source JNI handoff')
parser.add_argument('--ordinary-backend', action='store_true', help='Exercise production ordinary-source USB scope against fake transport')
parser.add_argument('--direct-owner', action='store_true', help='Exercise production generational source owner')
parser.add_argument('--android', help='Explicit ADB serial; silent native executable only')
parser.add_argument('--adb', type=Path)
parser.add_argument('--adb-port', type=int, default=5037)
args = parser.parse_args()
if args.android and (not args.adb or not args.adb.is_file()):
    parser.error('Android requires an existing explicit ADB executable')
if not 1 <= args.adb_port <= 65535:
    parser.error('ADB port must be in 1..65535')
args.work.mkdir(parents=True, exist_ok=True)
evidence = args.work / (time.strftime('%Y%m%dT%H%M%S') + '-' + uuid.uuid4().hex[:8])
evidence.mkdir()
report = dict(completed=False, platform=args.android or 'host', binarySha256=hashlib.sha256(args.exe.read_bytes()).hexdigest(), runs=[])
remote = '/data/local/tmp/localmusic-finite-fixture-' + uuid.uuid4().hex
remote_created = False
pushed = {}
metric = 'nativeHeap' if args.android else 'private'


def adb(*command):
    return subprocess.run([str(args.adb.resolve()), '-P', str(args.adb_port), '-s', args.android, *command],
                          capture_output=True, check=True, timeout=180)


def push(path):
    checksum = hashlib.sha256(path.read_bytes()).hexdigest()
    if pushed.get(path) != checksum:
        adb('push', str(path), remote + '/' + path.name)
        pushed[path] = checksum
    return remote + '/' + path.name


def call(command):
    return subprocess.run(command, capture_output=True, check=True, timeout=120)


def run(source, raw, rate, bits, mode, label):
    command = [str(args.exe.resolve()), str(source), str(raw), str(rate), str(bits), mode]
    if args.android:
        command = [str(args.adb.resolve()), '-P', str(args.adb_port), '-s', args.android, 'shell', '-T',
                   remote + '/runner', push(source), push(raw), str(rate), str(bits), mode]
    result = subprocess.run(command, capture_output=True, timeout=120)
    (evidence / f'{bits}-{rate}-{label}.log').write_bytes(result.stdout + result.stderr)
    assert result.returncode == 0, (label, result.stdout, result.stderr)
    assert b'PASS:' in result.stdout
    entry = dict(rate=rate, bits=bits, mode=label, output=result.stdout.decode().strip())
    if mode.startswith('ordinary-'):
        values=list(map(int,re.search(rb'MEMORY (\d+) (\d+) MAX_ALLOC (\d+)',result.stdout).groups()))
        assert max(values[:2])<64*1024*1024 and values[2]<=1024*1024
        entry['memory']={metric:values[0],'residentHighWater':values[1],'largestAllocation':values[2]}
    if mode in ('quiet-stress', 'probe-stress'):
        values = list(map(int, re.search(rb'MEMORY (\d+) (\d+) (\d+) (\d+) MAX_ALLOC (\d+)', result.stdout).groups()))
        assert all(values) and max(values[:4]) < 64*1024*1024
        assert values[2]-values[0] < 8*1024*1024 and values[3]-values[1] < 8*1024*1024
        assert values[4] <= 1024*1024
        entry['memory'] = {metric+'10': values[0], 'resident10': values[1], metric+'110': values[2], 'resident110': values[3], 'largestAllocation': values[4]}
    if mode == 'probe-unlinked':
        # This mode deliberately removes only the pushed disposable input.
        pushed.pop(source, None)
    report['runs'].append(entry)
    print(f'PASS: {bits}/{rate} {label}', flush=True)


try:
    if args.android:
        report['abi'] = adb('shell', 'getprop', 'ro.product.cpu.abi').stdout.decode().strip()
        report['api'] = adb('shell', 'getprop', 'ro.build.version.sdk').stdout.decode().strip()
        report['model'] = adb('shell', 'getprop', 'ro.product.model').stdout.decode().strip()
        adb('shell', 'mkdir', remote)
        remote_created = True
        adb('push', str(args.exe.resolve()), remote + '/runner')
        assert adb('shell', 'sha256sum', remote + '/runner').stdout.decode().split()[0] == report['binarySha256']
        adb('shell', 'chmod', '700', remote + '/runner')
    with tempfile.TemporaryDirectory(dir=args.work) as temporary:
        folder = Path(temporary)
        for bits in (16, 24):
            for rate in (44100, 48000, 88200, 96000):
                width = bits // 8
                ceiling = ((1 << (bits-1))-1)*(750 if args.ordinary_backend else 8)//1000
                rng = random.Random(rate + bits)
                pcm = bytearray(b''.join(rng.randint(-ceiling, ceiling).to_bytes(width, 'little', signed=True)
                                        for _ in range(((7*rate+137) if args.ordinary_backend else 6*rate)*2)))
                pcm[:width*4] = b''.join(value.to_bytes(width, 'little', signed=True) for value in (-ceiling, ceiling, -1, 1))
                wav = folder / f'{bits}-{rate}.wav'
                source, raw = wav.with_suffix('.flac'), wav.with_suffix('.pcm')

                def encode(data):
                    with wave.open(str(wav), 'wb') as out:
                        out.setparams((2, width, rate, 0, 'NONE', '')); out.writeframes(data)
                    call(['ffmpeg', '-y', '-v', 'error', '-i', str(wav), '-c:a', 'flac', str(source)])

                encode(pcm)
                reference = call(['ffmpeg', '-v', 'error', '-i', str(source), '-f', f's{bits}le', '-']).stdout
                assert reference == pcm
                raw.write_bytes(reference)
                encoded = source.read_bytes()
                if args.direct_owner:
                    for mode in ('direct-owner','direct-owner-race','direct-owner-quarantine','direct-owner-packet-failure'):
                        run(source, raw, rate, bits, mode, mode)
                    continue
                if args.ordinary_backend:
                    for mode in ('desc-feedback','starve','backend','pause','stop','submit-stop','prestop','invalid','rate','claim','descriptor','desc-length','desc-width','desc-rate','metadata','config','packet'):
                        run(source, raw, rate, bits, 'ordinary-'+mode, 'ordinary-'+mode)
                    wrong=bytearray(encoded);wrong[26]^=1
                    source.write_bytes(wrong)
                    run(source, raw, rate, bits, 'ordinary-corrupt', 'ordinary-corrupt')
                    source.write_bytes(encoded[:-1])
                    run(source, raw, rate, bits, 'ordinary-truncated', 'ordinary-truncated')
                    missing=bytearray(encoded);missing[26:42]=bytes(16);source.write_bytes(missing)
                    run(source, raw, rate, bits, 'ordinary-unverified', 'ordinary-unverified')
                    continue
                if args.session_jni:
                    run(source, raw, rate, bits, 'probe-session', 'probe-session')
                    continue
                if args.probe:
                    for mode in ('valid', 'prestop', 'stop', 'late-cancel', 'config-stop', 'config-stop-false', 'submit-stop', 'config-error', 'submit-error', 'packet-error', 'event-throw', 'stress'):
                        run(source, raw, rate, bits, 'probe-'+mode, 'probe-'+mode)
                    if args.android:
                        run(source, raw, rate, bits, 'probe-unlinked', 'probe-unlinked')
                    continue
                run(source, raw, rate, bits, 'quiet-valid', 'valid-bridge')
                run(source, raw, rate, bits, 'quiet-prestop', 'stop-before-start')
                run(source, raw, rate, bits, 'quiet-stop', 'stop-during-read')
                run(source, raw, rate, bits, 'quiet-stress', '110-preflights')
                wrong = bytearray(encoded); wrong[26] ^= 1
                missing = bytearray(encoded); missing[26:42] = bytes(16)
                for mode, data, label in [('quiet-corrupt', wrong, 'wrong-md5'),
                                          ('quiet-corrupt', encoded[:-1], 'truncated'),
                                          ('quiet-unverified', missing, 'missing-md5')]:
                    source.write_bytes(data); run(source, raw, rate, bits, mode, label)
                source.write_bytes(encoded)
                run(source, raw, rate, 24 if bits == 16 else 16, 'quiet-corrupt', f'metadata-width-{bits}')
                run(source, raw, 48000 if rate == 44100 else 44100, bits, 'quiet-corrupt', f'metadata-rate-{rate}')
                for data, label in [(b'', 'empty'), (bytes(8*1024*1024+1), 'encoded-cap')]:
                    source.write_bytes(data); run(source, raw, rate, bits, 'quiet-input', label)
                for frame in (0, 6*rate-1):
                    for value in (-ceiling-1, ceiling+1):
                        loud = bytearray(pcm)
                        loud[frame*2*width:frame*2*width+width] = value.to_bytes(width, 'little', signed=True)
                        encode(loud); run(source, raw, rate, bits, 'quiet-loud', f'loud-frame-{frame}-{value}')
                for count in (5*rate, 6*rate+137):
                    data = (pcm + pcm[:137*2*width])[:count*2*width]
                    encode(data); run(source, raw, rate, bits, 'quiet-length', f'length-{count}')
        report['completed'] = True
finally:
    try:
        if remote_created:
            assert re.fullmatch(r'/data/local/tmp/localmusic-finite-fixture-[0-9a-f]{32}', remote)
            report['remoteDirectory'] = remote
            report['remoteCleaned'] = False
            adb('shell', 'rm', '-rf', remote)
            report['remoteCleaned'] = adb('shell', 'test', '!', '-e', remote).returncode == 0
    finally:
        (evidence / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
print(f'PASS: {len(report["runs"])} quiet fixture checks; report: {evidence}')
