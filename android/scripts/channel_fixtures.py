"""Deterministic equal-frequency, non-overlapping WAV channel comparison signals."""
import argparse
import hashlib
import json
import math
from pathlib import Path
import wave

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'.tools/fixtures'
MANIFEST=ROOT/'channel-fixtures-manifest.json'


def reference(rate,bits):
    scale=int(((1<<(bits-1))-1)*10**(-42/20))
    tone=[round(scale*min(1.0,(i/rate)/.02,(2-i/rate)/.02)*math.sin(2*math.pi*440*i/rate)) for i in range(rate*2)]
    pcm=bytearray()
    for frame in range(rate*6):
        left=tone[frame] if frame<rate*2 else 0
        right=tone[frame-rate*3] if rate*3<=frame<rate*5 else 0
        pcm.extend(left.to_bytes(bits//8,'little',signed=True))
        pcm.extend(right.to_bytes(bits//8,'little',signed=True))
    return bytes(pcm)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--generate',action='store_true')
    args=parser.parse_args()
    OUT.mkdir(parents=True,exist_ok=True)
    records=[]
    for bits in (16,24):
        for rate in (44100,48000,88200,96000):
            name=f'channels-{bits}-{rate}.wav'
            path=OUT/name
            pcm=reference(rate,bits)
            if args.generate or not path.exists():
                with wave.open(str(path),'wb') as output:
                    output.setparams((2,bits//8,rate,0,'NONE','not compressed'))
                    output.writeframes(pcm)
            with wave.open(str(path),'rb') as source:
                if source.getparams()[:3]!=(2,bits//8,rate) or source.readframes(source.getnframes())!=pcm:
                    raise SystemExit(f'Channel PCM mismatch: {name}')
            records.append(dict(file=name,sampleRateHz=rate,meaningfulBits=bits,channels=2,durationMs=6000,
                                peakDbfs=-42,pattern='equal-tone-separated',frequencyHz=440,
                                pcmSha256=hashlib.sha256(pcm).hexdigest(),fileSha256=hashlib.sha256(path.read_bytes()).hexdigest()))
    data=dict(schemaVersion=1,signal='0-2s left 440Hz; 2-3s silence; 3-5s right 440Hz; 5-6s silence; 20ms fades; no both-channel section',fixtures=records)
    if args.generate:
        MANIFEST.write_text(json.dumps(data,indent=2)+'\n',encoding='utf-8')
    elif json.loads(MANIFEST.read_text(encoding='utf-8'))!=data:
        raise SystemExit('Channel manifest mismatch; do not silently update trusted hashes.')
    (OUT/'channel-fixtures-manifest.json').write_text(MANIFEST.read_text(encoding='utf-8'),encoding='utf-8')
    print('PASS: 8 channel-comparison WAV files match the pinned PCM and file hashes.')


if __name__=='__main__':
    main()
