"""Offline disposable FLAC fixtures, independent PCM reference; no playback."""
from pathlib import Path
import json
import random
import shlex
import subprocess
import sys
import tempfile
import wave

exe = Path(sys.argv[1]).resolve()
root = Path(__file__).resolve().parents[1]
serial = sys.argv[3] if len(sys.argv) == 4 and sys.argv[2] == '--android' else None
adb = root / '.tools/sdk/platform-tools/adb.exe'
remote = '/data/local/tmp/localmusic-flac-stream'
pushed = set()
tests = 0
failures = []


def fixture_pcm(frames, bits):
    # Non-periodic source distinguishes nearby seeks. Include signed extrema
    # explicitly, with independent left/right values and meaningful low bits.
    width = bits // 8
    data = bytearray(random.Random(20261109).randbytes(frames * 2 * width))
    values = [-(1 << (bits - 1)), -257, -1, 0, 1, 255, (1 << (bits - 1)) - 1]
    for i in range(7):
        for ch in (0, 1):
            start = (i * 2 + ch) * width
            data[start:start + width] = values[(i * 3 + ch) % 7].to_bytes(width, 'little', signed=True)
    return bytes(data)


def run_reader(path, commands=()):
    if serial:
        # Every file here is a disposable generated fixture, never user music.
        if path not in pushed:
            subprocess.run([str(adb), '-s', serial, 'push', str(path), remote + '/fixture-' + path.name],
                           capture_output=True, check=True, timeout=20)
            pushed.add(path)
        arguments = [remote + '/flac_stream_cli', remote + '/fixture-' + path.name, *commands]
        # Windows `adb shell -T` still expands LF bytes to CRLF on stdout.
        # Redirect on Android, then pull the binary file without text translation.
        command = ' '.join(shlex.quote(arg) for arg in arguments) + ' > ' + remote + '/result.pcm'
        result = subprocess.run([str(adb), '-s', serial, 'shell', '-T', command],
                                capture_output=True, timeout=20)
        with tempfile.TemporaryDirectory(dir=root / '.tools') as output_folder:
            output = Path(output_folder) / 'result.pcm'
            subprocess.run([str(adb), '-s', serial, 'pull', remote + '/result.pcm', str(output)],
                           capture_output=True, check=True, timeout=20)
            result.stdout = output.read_bytes()
        return result
    else:
        command = [str(exe), str(path), *commands]
    return subprocess.run(command, capture_output=True, timeout=20)


def check(label, path, commands, pcm, statuses, md5, code=0, seek_ok=1):
    global tests
    run = run_reader(path, commands)
    events = [json.loads(line) for line in run.stderr.splitlines()]
    states = [event for event in events if 'status' in event]
    try:
        assert run.returncode == code, (run.returncode, events)
        assert run.stdout == pcm, (len(run.stdout), len(pcm), events)
        assert [event['status'] for event in states] == statuses, events
        assert [event['md5'] for event in states] == md5, events
        assert all(event['ok'] == seek_ok and event['md5'] == 0 for event in events if 'seek' in event), events
    except AssertionError as error:
        failures.append(f'{label}: {error}')
    tests += 1


baseline = root / '.tools/fixtures/stereo-16-44100.flac'
with wave.open(str(baseline.with_suffix('.wav')), 'rb') as wav:
    expected = wav.readframes(wav.getnframes())
check('six-second baseline', baseline, [], expected, ['End'], [1])

work = root / '.tools/flac-stream-tests'
work.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory(dir=work) as temporary:
    folder = Path(temporary)
    frames = 44100 * 3 + 137
    pcm = fixture_pcm(frames, 16)
    source = folder / 'source.wav'
    with wave.open(str(source), 'wb') as wav:
        wav.setparams((2, 2, 44100, 0, 'NONE', ''))
        wav.writeframes(pcm)
    flac = folder / 'source.flac'
    subprocess.run(['ffmpeg', '-v', 'error', '-i', str(source), '-c:a', 'flac', str(flac)], check=True)
    reference = subprocess.run(['ffmpeg', '-v', 'error', '-i', str(flac), '-f', 's16le', '-'],
                               capture_output=True, check=True).stdout
    assert reference == pcm, 'independent FFmpeg decoder must match generated source'
    check('clean EOF repeated', flac, ['drain', 'drain'], pcm, ['End', 'End'], [1, 1])
    check('End remains End after late cancel', flac, ['drain', 'cancel', 'drain'],
          pcm, ['End', 'End'], [1, 1])
    for frame in (0, 1, 4607, 4608, 4609, frames // 2, frames - 1, frames):
        check(f'seek {frame}', flac, [f'seek={frame}', 'drain', 'drain'],
              reference[frame * 4:], ['End', 'End'], [int(frame == 0)] * 2)
    check('seek backward after EOF re-verifies', flac,
          ['drain', 'seek=5001', 'drain', 'seek=0', 'drain'],
          pcm + pcm[5001 * 4:] + pcm, ['End'] * 3, [1, 0, 1])
    check('seek with undelivered pending block', flac,
          ['seek=100', 'seek=7001', 'drain'], pcm[7001 * 4:], ['End'], [0])
    check('cancel before decoding is sticky', flac, ['cancel', 'drain', 'clear', 'drain'],
          b'', ['Cancelled'] * 2, [0, 0], 3)
    check('cancel pending seek block', flac, ['seek=100', 'cancel', 'drain', 'clear', 'drain'],
          b'', ['Cancelled'] * 2, [0, 0], 3)
    first = run_reader(flac, ['read=1'])
    assert first.returncode == 0
    assert first.stdout and pcm.startswith(first.stdout)
    check('cancel after delivered PCM', flac, ['read=1', 'cancel', 'drain', 'clear', 'drain'],
          first.stdout, ['Block', 'Cancelled', 'Cancelled'], [0, 0, 0], 3)
    check('invalid seek leaves stream intact', flac, [f'seek={frames + 1}', 'drain'],
          pcm, ['End'], [1], seek_ok=0)
    raw = flac.read_bytes()
    offset = 4
    while True:
        last = raw[offset] & 128
        offset += 4 + int.from_bytes(raw[offset + 1:offset + 4], 'big')
        if last:
            break
    variants = {}
    for size in (0, 3, 16, 41, offset, len(raw) // 2, len(raw) - 1):
        variants[f'truncated-{size}'] = raw[:size]
    digest = bytearray(raw)
    digest[26] ^= 1
    variants['wrong-md5'] = digest
    corrupt = bytearray(raw)
    corrupt[offset + (len(raw) - offset) // 2] ^= 64
    variants['corrupt-audio'] = corrupt
    for label, data in variants.items():
        broken = folder / f'{label}.flac'
        broken.write_bytes(data)
        run = run_reader(broken, ['drain', 'cancel', 'drain'])
        events = [json.loads(line) for line in run.stderr.splitlines()]
        if (run.returncode != 1 or [e['status'] for e in events] != ['Error', 'Error']
                or any(e['md5'] for e in events) or run.stdout != pcm[:len(run.stdout)]):
            failures.append(f'{label}: code={run.returncode}, events={events}')
        tests += 1
    missing = bytearray(raw)
    missing[26:42] = bytes(16)
    no_digest = folder / 'missing-md5.flac'
    no_digest.write_bytes(missing)
    check('absent MD5 is clean but unverified', no_digest, ['drain', 'drain'], pcm,
          ['End', 'End'], [0, 0])
    partial = folder / 'wrong-md5.flac'
    check('partial seek cannot check whole-stream MD5', partial, ['seek=5001', 'drain'],
          pcm[5001 * 4:], ['End'], [0])
    unknown = bytearray(raw)
    # The low 36 STREAMINFO bits are the optional total-sample count.
    unknown[21] &= 0xf0
    unknown[22:26] = bytes(4)
    unknown_path = folder / 'unknown-length.flac'
    unknown_path.write_bytes(unknown)
    check('unknown length complete MD5', unknown_path, ['drain'], pcm, ['End'], [1])
    check('unknown length partial seek', unknown_path, ['seek=5001', 'drain'],
          pcm[5001 * 4:], ['End'], [0])
    # libFLAC always parses SEEKTABLE, even when metadata callbacks ignore it.
    # Bound this allocation independently of a track's optional index length.
    for points in (3640, 3641):
        table = (b'\xff' * 8 + bytes(10)) * points
        indexed = folder / f'seektable-{points}.flac'
        indexed.write_bytes(raw[:4] + b'\x00' + raw[5:42] + b'\x83' +
                            len(table).to_bytes(3, 'big') + table + raw[offset:])
        if points == 3640:
            check('bounded seektable accepted', indexed, ['drain'], pcm, ['End'], [1])
        else:
            check('oversized seektable rejected before decode', indexed, ['drain'],
                  b'', ['Error'], [0], 1)

    # Separately verify all eight allowed formats, signed extrema and low bits.
    for bits in (16, 24):
        for rate in (44100, 48000, 88200, 96000):
            width = bits // 8
            count = rate + 137
            data = fixture_pcm(count, bits)
            wav_path = folder / f'{bits}-{rate}.wav'
            path = wav_path.with_suffix('.flac')
            with wave.open(str(wav_path), 'wb') as wav:
                wav.setparams((2, width, rate, 0, 'NONE', ''))
                wav.writeframes(data)
            subprocess.run(['ffmpeg', '-v', 'error', '-i', str(wav_path), '-c:a', 'flac', str(path)], check=True)
            ref = subprocess.run(['ffmpeg', '-v', 'error', '-i', str(path), '-f', f's{bits}le', '-'],
                                 capture_output=True, check=True).stdout
            assert ref == data
            check(f'{bits}/{rate} full', path, ['drain'], ref, ['End'], [1])
            check(f'{bits}/{rate} seek', path, ['seek=5001', 'drain'],
                  ref[5001 * 2 * width:], ['End'], [0])

if failures:
    raise AssertionError('\n'.join(failures))
platform = f'Android {serial}' if serial else 'host'
print(f'PASS: {tests} streaming FLAC {platform} cases (PCM reference, seek, cancellation, EOF, corruption/truncation and MD5 scope)')
