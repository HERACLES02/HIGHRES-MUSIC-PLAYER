"""Offline real-source/packet bridge checks. All audio fixtures are disposable."""
import argparse
import hashlib
import json
import random
import re
import shlex
import subprocess
import tempfile
import threading
import time
import wave
import uuid
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('exe', type=Path)
parser.add_argument('work', type=Path)
parser.add_argument('--memory', action='store_true')
parser.add_argument('--race-repeats', action='store_true')
parser.add_argument('--android', help='Explicit ADB serial; native silent tests only')
parser.add_argument('--adb', type=Path)
parser.add_argument('--adb-port', type=int, default=5037)
parser.add_argument('--fd', action='store_true', help='Adapter descriptor handoff cases')
parser.add_argument('--source-session', action='store_true', help='Ordinary-source coordinator control cases')
parser.add_argument('--service-gap', action='store_true', help='Adapter coverage of recorded worker-service gaps')
args = parser.parse_args()
exe, work = args.exe.resolve(), args.work.resolve()
work.mkdir(parents=True, exist_ok=True)
evidence = work / (time.strftime('%Y%m%dT%H%M%S') + '-' + uuid.uuid4().hex[:8])
evidence.mkdir()
def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


report = dict(binarySha256=digest(exe), platform=args.android or 'host', completed=False, runs=[], memory=[])
remote = '/data/local/tmp/localmusic-packet-bridge-' + uuid.uuid4().hex
remote_created = False
pushed = {}
metric = 'nativeHeap' if args.android else 'private'


def adb(*command):
    assert args.adb and args.android, 'Android tests require explicit adb path and serial'
    return subprocess.run([str(args.adb.resolve()), '-P', str(args.adb_port), '-s', args.android, *command],
                          capture_output=True, check=True, timeout=180)


def push(path):
    checksum = digest(path)
    if pushed.get(path) != checksum:
        adb('push', str(path), remote + '/' + path.name)
        pushed[path] = checksum
    return remote + '/' + path.name


def call(command):
    return subprocess.run(command, capture_output=True, check=True, timeout=120)


def fixture(folder, bits, rate):
    pcm = bytearray(random.Random(rate + bits).randbytes((rate + 137) * 2 * (bits // 8)))
    # Explicit signed extrema, separate channels and meaningful low bits.
    width = bits // 8
    for i, value in enumerate((-(1 << (bits-1)), -257, -1, 0, 1, 255, (1 << (bits-1))-1)):
        pcm[i*width:(i+1)*width] = value.to_bytes(width, 'little', signed=True)
    wav = folder / f'{bits}-{rate}.wav'
    with wave.open(str(wav), 'wb') as out:
        out.setparams((2, width, rate, 0, 'NONE', ''))
        out.writeframes(pcm)
    flac, raw = wav.with_suffix('.flac'), wav.with_suffix('.pcm')
    call(['ffmpeg', '-v', 'error', '-i', str(wav), '-c:a', 'flac', str(flac)])
    reference = call(['ffmpeg', '-v', 'error', '-i', str(flac), '-f', f's{bits}le', '-']).stdout
    assert reference == pcm
    raw.write_bytes(reference)
    return flac, raw


def run(a, raw, bits, rate, mode, replacement, label, repeat=1):
    b, raw_b, bits_b, rate_b = replacement
    before = {p: digest(p) for p in (a, raw, b, raw_b)}
    command = [str(exe), str(a), str(raw), str(rate), str(bits), mode, str(b), str(raw_b), str(rate_b), str(bits_b)]
    if args.android:
        command = [str(args.adb.resolve()), '-P', str(args.adb_port), '-s', args.android, 'shell', '-T',
                   remote + '/bridge', push(a), push(raw), str(rate), str(bits), mode, push(b), push(raw_b), str(rate_b), str(bits_b)]
    for index in range(repeat):
        result = subprocess.run(command, capture_output=True, timeout=35)
        log = result.stdout + result.stderr
        (evidence / f'{bits}-{rate}-{label}.log').write_bytes(log)
        if result.returncode:
            raise AssertionError((bits, rate, mode, index, log.decode(errors='replace')))
        assert b'PASS:' in result.stdout
    assert all(digest(p) == checksum for p, checksum in before.items())
    if mode == 'stress':
        values = list(map(int, re.search(rb'MEMORY (\d+) (\d+) (\d+) (\d+)', result.stdout).groups()))
        assert all(values), 'native allocation/process samples must be available'
        assert max(values) < 64*1024*1024 and values[2]-values[0] < 8*1024*1024 and values[3]-values[1] < 8*1024*1024
        report['memory'].append(dict(mode=label, bits=bits, rate=rate, **{metric+'10': values[0], metric+'110': values[2]}, resident10=values[1], resident110=values[3]))
    report['runs'].append(dict(bits=bits, rate=rate, mode=label, repeats=repeat, output=result.stdout.decode().strip()))
    print(f'PASS: {bits}/{rate} {label} ({repeat} runs)', flush=True)


def stream_hash(command):
    digest, count = hashlib.sha256(), 0
    with subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE) as process:
        watchdog = threading.Timer(120, process.kill)
        watchdog.start()
        try:
            while data := process.stdout.read(65536):
                digest.update(data)
                count += len(data)
            error = process.stderr.read()
            assert process.wait(timeout=20) == 0, error
        finally:
            watchdog.cancel()
    return digest.hexdigest(), count, error.decode()


try:
    if args.android:
        assert args.adb and args.adb.exists(), 'Use the existing pinned SDK adb executable'
        report['abi'] = adb('shell', 'getprop', 'ro.product.cpu.abi').stdout.decode().strip()
        report['api'] = adb('shell', 'getprop', 'ro.build.version.sdk').stdout.decode().strip()
        adb('shell', 'mkdir', remote)
        remote_created = True
        adb('push', str(exe), remote + '/bridge')
        assert adb('shell', 'sha256sum', remote + '/bridge').stdout.decode().split()[0] == report['binarySha256']
        adb('shell', 'chmod', '700', remote + '/bridge')
    with tempfile.TemporaryDirectory(dir=work) as directory:
        folder = Path(directory)
        fixtures = {(bits, rate): fixture(folder, bits, rate) for bits in (16, 24) for rate in (44100, 48000, 88200, 96000)}
        for (bits, rate), (a, raw) in fixtures.items():
            bits_b, rate_b = (24 if bits == 16 else 16), (44100 if rate == 96000 else 96000)
            b, raw_b = fixtures[bits_b, rate_b]
            replacement = b, raw_b, bits_b, rate_b
            encoded = a.read_bytes()
            wrong = bytearray(encoded); wrong[26] ^= 1
            missing = bytearray(encoded); missing[26:42] = bytes(16)
            for label, mode, content in [('valid', 'valid', encoded), ('starve', 'starve', encoded),
                                         ('lifecycle', 'lifecycle', encoded), ('bad-md5', 'error', wrong),
                                         ('truncated', 'error', encoded[:-1]), ('missing-md5', 'unverified', missing)]:
                a.write_bytes(content)
                repeats = 100 if args.race_repeats and mode == 'error' else 1
                run(a, raw, bits, rate, mode, replacement, label, repeats)
            a.write_bytes(encoded)
            if args.service_gap:
                run(a, raw, bits, rate, 'service-gap', replacement, 'service-gap')
            run(a, raw, bits, rate, 'stress', replacement, 'stress')
            if args.source_session:
                for mode in ('stop', 'stop-before-start', 'configure-stop', 'configure-fail', 'configure-throw',
                             'retiring-error', 'invalid', 'seek-fail', 'destroy', 'metadata-mismatch', 'source-allocation',
                             'pause-tail', 'pause', 'pause-stop', 'pause-stop-inflight', 'pause-error', 'pause-destroy', 'pause-toggle'):
                    run(a, raw, bits, rate, mode, replacement, mode)
            if args.fd:
                run(a, raw, bits, rate, 'fd', replacement, 'fd')
        if args.memory:
            # Repeated generated nonzero 24-bit ramp. No whole long PCM cache.
            seed = folder / 'ramp.wav'
            second = b''.join((((i*7919 + ch*104729) % (1 << 24)) - (1 << 23)).to_bytes(3, 'little', signed=True)
                              for i in range(96000) for ch in (0, 1))
            with wave.open(str(seed), 'wb') as wav:
                wav.setparams((2, 3, 96000, 0, 'NONE', '')); wav.writeframes(second)
            measurements = []
            for seconds in (60, 1200):
                source = folder / f'long-{seconds}.flac'
                call(['ffmpeg', '-v', 'error', '-stream_loop', '-1', '-i', str(seed), '-t', str(seconds), '-c:a', 'flac', str(source)])
                reference = stream_hash(['ffmpeg', '-v', 'error', '-i', str(source), '-f', 's24le', '-'])
                if args.android:
                    arguments = [remote + '/bridge', push(source), '-', '96000', '24', 'pipe']
                    result = adb('shell', '-T', 'set -o pipefail; ' + ' '.join(shlex.quote(arg) for arg in arguments) + ' | sha256sum')
                    status = result.stderr.decode()
                    frames = int(re.search(r'PASS: pipe (\d+) MEMORY', status).group(1))
                    actual = result.stdout.decode().split()[0], frames*6, status
                else:
                    actual = stream_hash([str(exe), str(source), '-', '96000', '24', 'pipe'])
                assert actual[:2] == reference[:2] and actual[1] == seconds*96000*6
                values = list(map(int, re.search(r'MEMORY (\d+) (\d+) MAX_ALLOC (\d+)', actual[2]).groups()))
                assert all(values) and max(values[:2]) < 64*1024*1024 and values[2] <= 1024*1024
                entry = dict(seconds=seconds, sha256=actual[0], bytes=actual[1], **{metric+'Peak': values[0]}, peakResident=values[1], largestAllocation=values[2])
                measurements.append(entry);report['memory'].append(entry)
                print(f'PASS: nonzero 24/96 {seconds}s {actual[1]} exact PCM bytes; {metric}/resident peaks {values[:2]}', flush=True)
            assert abs(measurements[1][metric+'Peak']-measurements[0][metric+'Peak']) < 8*1024*1024
            assert abs(measurements[1]['peakResident']-measurements[0]['peakResident']) < 8*1024*1024
    report['completed'] = True
finally:
    try:
        if remote_created:
            # Unique generated-fixture directory only; no APK, shared storage or USB.
            assert re.fullmatch(r'/data/local/tmp/localmusic-packet-bridge-[0-9a-f]{32}', remote)
            report['remoteDirectory'] = remote
            report['remoteCleaned'] = False
            adb('shell', 'rm', '-rf', remote)
            report['remoteCleaned'] = adb('shell', 'test', '!', '-e', remote).returncode == 0
    finally:
        (evidence / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
print(f'PASS: {len(report["runs"])} bridge batches; report: {evidence}')
