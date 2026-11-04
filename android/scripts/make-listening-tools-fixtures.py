"""Quiet, original tones and tagged disposable WAVs for the 0.9 phone checks."""
from pathlib import Path
import hashlib
import json
import math
import struct
import subprocess
import wave
import zipfile

project = Path(__file__).resolve().parents[1]
root = project / 'releases' / 'listening-tools-check'
root.mkdir(parents=True, exist_ok=True)

def syncsafe(n):
    return bytes(((n >> 21) & 127, (n >> 14) & 127, (n >> 7) & 127, n & 127))

def frame(kind, text):
    data = b'\x03' + text.encode('utf-8')
    return kind.encode('ascii') + syncsafe(len(data)) + b'\0\0' + data

def txxx(key, value):
    return frame('TXXX', key + '\0' + value)

records = []
for name, amplitude, frequency, track_gain in (
    ('01-louder', .08, 440, '-12.0412 dB'),
    ('02-quieter', .02, 440, '0 dB'),
    ('03-crossfade-first', .05, 440, '0 dB'),
    ('04-crossfade-next', .05, 660, '0 dB'),
):
    path = root / (name + '.wav')
    pcm = bytearray()
    for i in range(44100 * 12):
        value = round(amplitude * 32767 * math.sin(2 * math.pi * frequency * i / 44100))
        pcm.extend(struct.pack('<hh', value, value))
    with wave.open(str(path), 'wb') as output:
        output.setnchannels(2); output.setsampwidth(2); output.setframerate(44100); output.writeframes(pcm)
    tags = frame('TIT2', name) + frame('TPE1', 'Local Music fixtures') + frame('TALB', 'Listening tools check')
    expected = {'REPLAYGAIN_TRACK_GAIN': track_gain, 'REPLAYGAIN_ALBUM_GAIN': '-6.0206 dB',
                'REPLAYGAIN_TRACK_PEAK': str(amplitude), 'REPLAYGAIN_ALBUM_PEAK': '.08'}
    tags += b''.join(txxx(key, value) for key, value in expected.items())
    tag = b'ID3\x04\0\0' + syncsafe(len(tags)) + tags
    with path.open('ab') as output:
        output.write(b'id3 ' + struct.pack('<I', len(tag)) + tag + (b'\0' if len(tag) % 2 else b''))
    with path.open('r+b') as output:
        output.seek(4); output.write(struct.pack('<I', path.stat().st_size - 8))
    with wave.open(str(path), 'rb') as source:
        assert source.readframes(source.getnframes()) == pcm
    # The same native tag source is used by the app's scanner. No user file is read or changed.
    result = subprocess.run([str(project / '.tools' / 'tag-host' / 'tag-check.exe'), 'read', str(path)], check=True, capture_output=True)
    parsed = json.loads(result.stdout)
    for key, value in expected.items():
        assert parsed['properties'].get(key) == [value], (name, key, parsed['properties'])
    records.append({'file': path.name, 'pcm_sha256': hashlib.sha256(pcm).hexdigest(), 'replaygain': expected, 'native_tags_verified': True})

sample = project.parent / 'design' / 'listening-tools' / 'warm-headphones.json'
(root / sample.name).write_bytes(sample.read_bytes())
(root / 'evidence.json').write_text(json.dumps(records, indent=2) + '\n', encoding='utf-8')
(root / 'LISTENING.txt').write_text(
    'Original quiet tones; use a comfortable volume. Copy this folder to the phone and scan it.\n'
    'ReplayGain OFF: 01-louder is louder than 02-quieter.\n'
    'ReplayGain TRACK: both should be close in level after the short gain ramp.\n'
    'ReplayGain ALBUM: the relative difference remains, with lower overall level.\n'
    'Crossfade: play 03 then 04 with crossfade 4s; listen for a blend of two pitches.\n'
    'Turn crossfade off afterward and verify ordinary/gapless album playback.\n'
    'EQ: import warm-headphones.json, review, apply, then compare EQ on/off.\n'
    'Native tag and PCM checks establish fixture integrity only, not phone playback or DAC output.\n', encoding='utf-8')
archive = project / 'releases' / 'local-music-listening-tools-check.zip'
with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as output:
    for path in sorted(root.iterdir()):
        if path.is_file(): output.write(path, 'listening-tools-check/' + path.name)
print(f'PASS: 4 WAV PCM payloads and all ReplayGain tags independently read by native TagLib. {archive}')
