"""Link and device-free real-libusb lifetime evidence. No APK or device handles."""
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
parser.add_argument('tools', type=Path)
parser.add_argument('--serial')
parser.add_argument('--adb-port', type=int, default=5037)
args = parser.parse_args()
tools = args.tools.resolve()
work = tools / 'usb-backend-lifetime'
work.mkdir(exist_ok=True)
evidence = work / (time.strftime('%Y%m%dT%H%M%S') + '-' + uuid.uuid4().hex[:8])
evidence.mkdir()
readelf = tools / 'sdk/ndk/27.2.12479018/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe'
adb = tools / 'sdk/platform-tools/adb.exe'
report = dict(completed=False, compile=[], runtime=None)
remote = '/data/local/tmp/localmusic-usb-backend-' + uuid.uuid4().hex
created = False


def call(command, timeout=60):
    return subprocess.run([str(arg) for arg in command], capture_output=True, check=True, timeout=timeout)


def bridge(*command):
    return call([adb, '-P', args.adb_port, '-s', args.serial, *command])


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


try:
    for abi in ('arm64-v8a', 'armeabi-v7a', 'x86_64'):
        folder = tools / ('usb-backend-lifetime-' + abi)
        binary, library = folder / 'usb_backend_lifetime_test', folder / 'libusb-1.0.so'
        dynamic = call([readelf, '-d', binary]).stdout
        symbols = call([readelf, '--dyn-syms', binary]).stdout
        (evidence / (abi + '-dynamic.txt')).write_bytes(dynamic)
        (evidence / (abi + '-symbols.txt')).write_bytes(symbols)
        assert b'libusb-1.0.so' in dynamic
        for symbol in (b'libusb_alloc_transfer', b'libusb_free_transfer', b'libusb_init_context', b'libusb_exit',
                       b'libusb_submit_transfer', b'libusb_cancel_transfer', b'libusb_handle_events_timeout_completed'):
            assert any(b'UND' in line and symbol in line for line in symbols.splitlines()), symbol
        report['compile'].append(dict(abi=abi, binarySha256=digest(binary), librarySha256=digest(library)))
    if args.serial:
        abi = bridge('shell', 'getprop', 'ro.product.cpu.abi').stdout.decode().strip()
        assert abi in ('arm64-v8a', 'armeabi-v7a', 'x86_64')
        record = next(entry for entry in report['compile'] if entry['abi'] == abi)
        report['target'] = dict(serial=args.serial, abi=abi, api=bridge('shell', 'getprop', 'ro.build.version.sdk').stdout.decode().strip(), adbPort=args.adb_port)
        bridge('shell', 'mkdir', remote)
        created = True
        for name in ('usb_backend_lifetime_test', 'libusb-1.0.so'):
            path = tools / ('usb-backend-lifetime-' + abi) / name
            bridge('push', path, remote + '/' + name)
            assert bridge('shell', 'sha256sum', remote + '/' + name).stdout.decode().split()[0] == digest(path)
        bridge('shell', 'chmod', '700', remote + '/usb_backend_lifetime_test')
        with tempfile.TemporaryDirectory(dir=work) as temporary:
            for tag, bits, rate in [('a', 16, 44100), ('b', 24, 96000)]:
                wav = Path(temporary) / (tag + '.wav')
                pcm = random.Random(rate).randbytes((rate + 137) * (bits // 8) * 2)
                with wave.open(str(wav), 'wb') as stream:
                    stream.setparams((2, bits // 8, rate, 0, 'NONE', '')); stream.writeframes(pcm)
                flac = wav.with_suffix('.flac')
                call(['ffmpeg', '-v', 'error', '-i', wav, '-c:a', 'flac', flac])
                assert call(['ffmpeg', '-v', 'error', '-i', flac, '-f', f's{bits}le', '-']).stdout == pcm
                bridge('push', flac, remote + '/' + tag + '.flac')
            command = [str(adb), '-P', str(args.adb_port), '-s', args.serial, 'shell', '-T',
                       'LD_LIBRARY_PATH=' + remote, remote + '/usb_backend_lifetime_test', remote + '/a.flac', remote + '/b.flac']
            for mode, expected in [('omit-discovery', b'NO_DEVICE_DISCOVERY required'), ('early-exit', b'adapter transfers and source must retire')]:
                result = subprocess.run([*command, mode], capture_output=True, timeout=30)
                (evidence / (mode + '.log')).write_bytes(result.stdout + result.stderr)
                assert result.returncode == 1 and expected in result.stderr, (mode, result.stdout, result.stderr)
                print('PASS: device-free guard control detected:', mode, flush=True)
            result = call([*command, 'normal'])
            (evidence / 'runtime.log').write_bytes(result.stdout + result.stderr)
            report['runtime'] = json.loads(result.stdout)
            assert report['runtime']['status'] == 'PASS' and report['runtime']['contexts'] == 110
            assert report['runtime']['transfersAllocated'] == report['runtime']['transfersFreed'] == 880
            assert report['runtime']['deviceSubmissions'] == 0
            print(result.stdout.decode().strip(), flush=True)
    report['completed'] = True
finally:
    try:
        if created:
            assert re.fullmatch('/data/local/tmp/localmusic-usb-backend-[0-9a-f]{32}', remote)
            report['remoteDirectory'] = remote
            report['remoteCleaned'] = False
            bridge('shell', 'rm', '-rf', remote)
            report['remoteCleaned'] = bridge('shell', 'test', '!', '-e', remote).returncode == 0
    finally:
        (evidence / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
print('PASS: real backend linkage/lifetime evidence:', evidence)
