"""Measure isolated Android native decoding using disposable silence fixtures.

No APK, playback or user-music access. malloc_info snapshots measure allocator
state; /proc/self/status and getrusage measure the native process, not Java heap.
"""
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tempfile
import time
import uuid
import wave
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
serial = sys.argv[1]
adb = str(root / '.tools/sdk/platform-tools/adb.exe')
work = root / '.tools/flac-stream-tests'
work.mkdir(parents=True, exist_ok=True)
remote = '/data/local/tmp/localmusic-flac-memory-' + uuid.uuid4().hex[:8]
assert re.fullmatch(r'/data/local/tmp/localmusic-flac-memory-[0-9a-f]{8}', remote)


def bridge(*args, timeout=30):
    return subprocess.run([adb, '-s', serial, *args], capture_output=True, check=True, timeout=timeout)


def shell(command, timeout=30):
    return bridge('shell', '-T', command, timeout=timeout).stdout.decode().strip()


def reference(path, seconds):
    digest = hashlib.sha256()
    zeros = hashlib.sha256()
    total = 0
    with subprocess.Popen(['ffmpeg', '-v', 'error', '-i', str(path), '-f', 's24le', '-'],
                          stdout=subprocess.PIPE) as process:
        while chunk := process.stdout.read(256 * 1024):
            assert not any(chunk), 'reference fixture must be silent'
            digest.update(chunk)
            zeros.update(bytes(len(chunk)))
            total += len(chunk)
        assert process.wait(timeout=30) == 0
    assert total == seconds * 96000 * 6 and digest.digest() == zeros.digest()
    return digest.hexdigest()


def allocator_snapshots(path):
    # Android's documented malloc_info XML reports allocated-large/huge/bins
    # per arena. Preserve the complete XML as evidence as well as these totals.
    raw = path.read_text()
    tree = ET.fromstring('<snapshots>' + re.sub(r'<\?xml[^>]*\?>', '', raw) + '</snapshots>')
    result = []
    for snapshot in tree:
        allocator = snapshot.find('malloc')
        assert allocator is not None and allocator.attrib['version'] == 'jemalloc-1', raw[:1000]
        heaps = allocator.findall('heap')
        assert heaps
        allocated = sum(int(heap.findtext(tag, '0')) for heap in heaps
                        for tag in ('allocated-large', 'allocated-huge', 'allocated-bins'))
        assert allocated > 0
        result.append(dict(phase=snapshot.attrib['phase'], allocatedBytes=allocated))
    assert [item['phase'] for item in result] == ['before', 'warm', 'middle', 'end', 'after-destroy', 'cycles-end']
    return result


stamp = time.strftime('%Y%m%dT%H%M%SZ', time.gmtime())
evidence = work / f'phone-memory-{stamp}'
evidence.mkdir()
abi = shell('getprop ro.product.cpu.abi')
assert abi in ('arm64-v8a', 'armeabi-v7a', 'x86_64'), abi
binary = root / f'.tools/flac-stream-android-{abi}/flac_stream_memory_cli'
report = dict(utc=stamp, serial=serial, abi=abi, model=shell('getprop ro.product.model'),
              sdk=shell('getprop ro.build.version.sdk'), binarySha256=hashlib.sha256(binary.read_bytes()).hexdigest(), runs={})
try:
    shell(f'mkdir {remote}')
    bridge('push', str(binary), remote + '/reader')
    shell(f'chmod 700 {remote}/reader')
    with tempfile.TemporaryDirectory(dir=work) as temporary:
        for seconds in (60, 1200):
            path = Path(temporary) / f'silence-24-96000-{seconds}.flac'
            subprocess.run(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i', 'anullsrc=r=96000:cl=stereo',
                            '-t', str(seconds), '-sample_fmt', 's32', '-c:a', 'flac', str(path)], check=True)
            expected_hash = reference(path, seconds)
            bridge('push', str(path), remote + '/source.flac')
            start = time.monotonic()
            shell(f'{remote}/reader {remote}/source.flac >{remote}/samples.jsonl 2>{remote}/allocator.xml', timeout=180)
            elapsed = time.monotonic() - start
            samples_path = evidence / f'{seconds}-samples.jsonl'
            allocator_path = evidence / f'{seconds}-allocator.xml'
            bridge('pull', remote + '/samples.jsonl', str(samples_path))
            bridge('pull', remote + '/allocator.xml', str(allocator_path))
            events = [json.loads(line) for line in samples_path.read_text().splitlines()]
            summary = events.pop()
            assert summary == dict(status='End', md5=1, frames=seconds * 96000,
                                   bytes=seconds * 96000 * 6, silenceChecked=True, cycles=40), summary
            assert sum(event['phase'] == 'cycle-destroyed' for event in events) == 40
            heap = allocator_snapshots(allocator_path)
            heap_by_phase = {item['phase']: item['allocatedBytes'] for item in heap}
            assert heap_by_phase['cycles-end'] <= heap_by_phase['after-destroy'] + 64 * 1024
            max_rss = max(max(event['hwmKiB'], event['maxRssKiB']) for event in events)
            max_heap = max(snapshot['allocatedBytes'] for snapshot in heap)
            assert 0 < max_rss < 64 * 1024 and 0 < max_heap < 64 * 1024 * 1024
            run = dict(seconds=seconds, elapsedSeconds=elapsed, summary=summary,
                       referencePcmSha256=expected_hash, fixtureSha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                       maxResidentKiB=max_rss, maxSampledAllocatedBytes=max_heap, allocator=heap,
                       samples=len(events))
            report['runs'][str(seconds)] = run
            print(f'PASS: phone {seconds}s 24/96: {summary["bytes"]} exact zero PCM bytes, MD5, 40 seek/cancel/destroy cycles; '
                  f'RSS high-water {max_rss} KiB, sampled native allocated {max_heap} bytes', flush=True)
        # A valid FLAC with a nonzero sample must fail the silence checker.
        # This checks the measurement harness, not a decoder error condition.
        nonzero_wav = Path(temporary) / 'nonzero.wav'
        with wave.open(str(nonzero_wav), 'wb') as wav:
            wav.setparams((2, 3, 96000, 0, 'NONE', ''))
            wav.writeframes(b'\x01' + bytes(383))
        nonzero = nonzero_wav.with_suffix('.flac')
        subprocess.run(['ffmpeg', '-v', 'error', '-i', str(nonzero_wav), '-c:a', 'flac', str(nonzero)], check=True)
        bridge('push', str(nonzero), remote + '/source.flac')
        rejected = subprocess.run([adb, '-s', serial, 'shell', '-T',
                                   f'{remote}/reader {remote}/source.flac >{remote}/negative.jsonl 2>{remote}/negative.xml'],
                                  capture_output=True, timeout=30)
        assert rejected.returncode == 1, rejected
        bridge('pull', remote + '/negative.jsonl', str(evidence / 'negative.jsonl'))
        assert not any('status' in json.loads(line) for line in (evidence / 'negative.jsonl').read_text().splitlines())
        report['nonzeroControlRejected'] = True
    short, long = report['runs']['60'], report['runs']['1200']
    assert long['maxResidentKiB'] <= short['maxResidentKiB'] + 4096
    assert long['maxSampledAllocatedBytes'] <= short['maxSampledAllocatedBytes'] + 4 * 1024 * 1024
finally:
    # Only this run's validated, uniquely named generated-fixture directory.
    shell('rm -rf ' + shlex.quote(remote))
    report['remoteCleaned'] = shell(f'test ! -e {remote} && echo yes') == 'yes'
    (evidence / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
assert report['remoteCleaned']
print(f'PASS: Android duration/memory comparison; raw snapshots and report: {evidence}')
