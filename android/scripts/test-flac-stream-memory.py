"""Windows host process memory and long PCM hashes; generated fixtures only.

Private bytes and OS peak working set measure the whole native test process,
not just its PCM staging array. These are not per-allocation/Android measurements.
"""
import ctypes
from ctypes import wintypes
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import time

root = Path(__file__).resolve().parents[1]
exe = Path(sys.argv[1]).resolve()
work = root / '.tools/flac-stream-tests'
work.mkdir(parents=True, exist_ok=True)


class Counters(ctypes.Structure):
    _fields_ = [('cb', wintypes.DWORD), ('PageFaultCount', wintypes.DWORD)] + [
        (name, ctypes.c_size_t) for name in (
            'PeakWorkingSetSize', 'WorkingSetSize', 'QuotaPeakPagedPoolUsage',
            'QuotaPagedPoolUsage', 'QuotaPeakNonPagedPoolUsage', 'QuotaNonPagedPoolUsage',
            'PagefileUsage', 'PeakPagefileUsage', 'PrivateUsage')]


get_memory = ctypes.WinDLL('psapi', use_last_error=True).GetProcessMemoryInfo
get_memory.argtypes = [wintypes.HANDLE, ctypes.POINTER(Counters), wintypes.DWORD]
get_memory.restype = wintypes.BOOL


def measure(path, commands=()):
    digest = hashlib.sha256()
    total = 0
    private = peak_ws = samples = 0
    checkpoints = []
    with tempfile.TemporaryFile() as errors:
        with subprocess.Popen([str(exe), str(path), *commands], stdout=subprocess.PIPE, stderr=errors) as process:
            while True:
                counters = Counters()
                counters.cb = ctypes.sizeof(counters)
                if get_memory(int(process._handle), ctypes.byref(counters), counters.cb):
                    private = max(private, counters.PrivateUsage)
                    peak_ws = max(peak_ws, counters.PeakWorkingSetSize)
                    if samples % 256 == 0:
                        checkpoints.append([total, counters.PrivateUsage, counters.WorkingSetSize])
                    samples += 1
                chunk = process.stdout.read(256 * 1024)
                if not chunk:
                    break
                digest.update(chunk)
                total += len(chunk)
            code = process.wait(timeout=20)
        errors.seek(0)
        events = [json.loads(line) for line in errors.read().splitlines()]
    assert samples > 0
    assert private < 64 * 1024 * 1024 and peak_ws < 64 * 1024 * 1024, (private, peak_ws)
    return dict(bytes=total, sha256=digest.hexdigest(), privateBytes=private,
                peakWorkingSetBytes=peak_ws, samples=samples, checkpoints=checkpoints,
                code=code, events=events)


def reference(path):
    digest = hashlib.sha256()
    total = 0
    with subprocess.Popen(['ffmpeg', '-v', 'error', '-i', str(path), '-f', 's24le', '-'],
                          stdout=subprocess.PIPE) as process:
        while chunk := process.stdout.read(256 * 1024):
            digest.update(chunk)
            total += len(chunk)
        assert process.wait(timeout=20) == 0
    return total, digest.hexdigest()


results = dict(utc=time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()), runs={})
ffmpeg = Path(subprocess.check_output(['where.exe', 'ffmpeg'], text=True).splitlines()[0])
results['ffmpegSha256'] = hashlib.sha256(ffmpeg.read_bytes()).hexdigest()
results['ffmpegVersion'] = subprocess.check_output(['ffmpeg', '-version'], text=True).splitlines()[0]
with tempfile.TemporaryDirectory(dir=work) as temporary:
    for seconds in (60, 1200):
        path = Path(temporary) / f'silence-24-96000-{seconds}.flac'
        subprocess.run(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i', 'anullsrc=r=96000:cl=stereo',
                        '-t', str(seconds), '-sample_fmt', 's32', '-c:a', 'flac', str(path)], check=True)
        result = measure(path)
        assert result['code'] == 0 and result['events'][-1]['status'] == 'End' and result['events'][-1]['md5'] == 1
        expected_bytes, expected_hash = reference(path)
        assert result['bytes'] == expected_bytes == seconds * 96000 * 6
        assert result['sha256'] == expected_hash
        results['runs'][str(seconds)] = result
        print(f'PASS: {seconds}s 24/96 PCM hash; private={result["privateBytes"]}, peakWS={result["peakWorkingSetBytes"]}', flush=True)
    assert results['runs']['1200']['privateBytes'] <= results['runs']['60']['privateBytes'] + 4 * 1024 * 1024
    # Reinitialize the same reader repeatedly; decode only short tails/prefixes.
    commands = [command for _ in range(40) for command in
                (f'seek={1200 * 96000 - 96000}', 'drain', 'seek=0', 'read=1')]
    commands += ['cancel', 'drain']
    result = measure(path, commands)
    assert result['code'] == 3 and result['events'][-1]['status'] == 'Cancelled'
    assert result['privateBytes'] <= results['runs']['1200']['privateBytes'] + 4 * 1024 * 1024
    assert all(event.get('ok', 1) == 1 for event in result['events'])
    results['runs']['40-seek-cycles'] = result
    unsupported = Path(temporary) / 'silence-24-192000-1200.flac'
    subprocess.run(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i', 'anullsrc=r=192000:cl=stereo',
                    '-t', '1200', '-sample_fmt', 's32', '-c:a', 'flac', str(unsupported)], check=True)
    result = measure(unsupported)
    assert result['code'] == 1 and result['bytes'] == 0 and result['events'][-1]['status'] == 'Error'
    results['runs']['192k-rejected'] = result

(work / 'last-memory.json').write_text(json.dumps(results, indent=2) + '\n')
print('PASS: 20-minute memory plateau, 40 seek cycles, explicit 192k rejection; evidence: android/.tools/flac-stream-tests/last-memory.json')
