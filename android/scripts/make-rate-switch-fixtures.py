"""Original quiet stereo PCM24 WAVs for rate switching; never reads user music."""
import argparse
import hashlib
import json
import math
from pathlib import Path
import struct
import wave


def id3_text(kind, value):
    payload = b'\x03' + value.encode('utf-8')
    size = len(payload)
    syncsafe = bytes((size >> shift) & 127 for shift in (21, 14, 7, 0))
    return kind.encode('ascii') + syncsafe + b'\0\0' + payload


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--silence', action='store_true', help='Generate a separate digital-zero control album')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1] / 'releases' / ('rate-switch-silence-v1' if args.silence else 'rate-switch-check-v1')
    root.mkdir(parents=True, exist_ok=True)
    records = []
    for index, rate in enumerate((44100, 48000, 96000, 44100), 1):
        title = f'{index:02d} - {rate // 1000 if rate % 1000 == 0 else "44.1"} kHz'
        path = root / f'{index:02d}-{rate}Hz.wav'
        # Each integer-second block joins at the same waveform phase. Both
        # channels are identical; original tones, approximately -32 dBFS peak.
        second = bytearray()
        if args.silence:
            second.extend(bytes(rate * 6))
        else:
            for frame in range(rate):
                sample = round(.025 * 8388607 * sum(math.sin(2 * math.pi * frequency * frame / rate)
                                                   for frequency in (220, 330, 440)) / 3)
                second.extend(sample.to_bytes(3, 'little', signed=True) * 2)
        pcm = bytes(second) * 30
        with wave.open(str(path), 'wb') as output:
            output.setnchannels(2)
            output.setsampwidth(3)
            output.setframerate(rate)
            output.writeframes(pcm)
        frames = b''.join(id3_text(kind, value) for kind, value in (
            ('TIT2', title), ('TPE1', 'Local Music test audio'),
            ('TALB', 'Local Music Silent Rate Check' if args.silence else 'Local Music Rate Check'), ('TRCK', f'{index}/4')))
        size = len(frames)
        tag = b'ID3\x04\0\0' + bytes((size >> shift) & 127 for shift in (21, 14, 7, 0)) + frames
        with path.open('ab') as output:
            output.write(b'id3 ' + struct.pack('<I', len(tag)) + tag + (b'\0' if len(tag) % 2 else b''))
        with path.open('r+b') as output:
            output.seek(4)
            output.write(struct.pack('<I', path.stat().st_size - 8))
        with wave.open(str(path), 'rb') as source:
            assert (source.getnchannels(), source.getsampwidth(), source.getframerate(), source.getnframes()) == (2, 3, rate, rate * 30)
            assert source.readframes(source.getnframes()) == pcm
        if args.silence:
            assert not any(pcm), 'Silent control must have an entirely zero PCM payload'
        records.append({'file': path.name, 'title': title, 'sampleRateHz': rate,
                        'bits': 24, 'channels': 2, 'durationSeconds': 30, 'digitalSilence': args.silence,
                        'pcmSha256': hashlib.sha256(pcm).hexdigest(),
                        'fileSha256': hashlib.sha256(path.read_bytes()).hexdigest()})
    assert records[0]['pcmSha256'] == records[3]['pcmSha256']
    (root / 'evidence.json').write_text(json.dumps(records, indent=2) + '\n', encoding='utf-8')
    print(f'PASS: four WAV headers, complete PCM payloads, and identical return-to-44.1 kHz PCM verified. {root}')


if __name__ == '__main__':
    main()
