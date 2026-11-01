"""Compare the actual native WAV/libFLAC decoder against independent PCM hashes."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import wave

root=Path(__file__).resolve().parents[1]
exe=Path(sys.argv[1]).resolve()
work=root/'.tools/decode-tests'
work.mkdir(parents=True,exist_ok=True)
output=work/'decoded.pcm'
manifest=json.loads((root/'fixtures-manifest.json').read_text())
manifest['fixtures']+=json.loads((root/'channel-fixtures-manifest.json').read_text())['fixtures']
for fixture in manifest['fixtures']:
    run=subprocess.run([str(exe),str(root/'.tools/fixtures'/fixture['file']),str(output)],capture_output=True,check=True)
    assert run.stdout.decode().strip()==f"{fixture['sampleRateHz']} {fixture['meaningfulBits']}"
    assert hashlib.sha256(output.read_bytes()).hexdigest()==fixture['pcmSha256'],fixture['file']
# Signed extrema, low bits and distinct channels, independent of tone generator.
for bits in (16,24):
    rate=44100
    samples=[-(1<<(bits-1)), -1, -257, 0, 1, 255, (1<<(bits-1))-1]
    pcm=b''.join(samples[(i*2+ch)%len(samples)].to_bytes(bits//8,'little',signed=True) for i in range(rate*6) for ch in (0,1))
    path=work/f'extrema-{bits}.wav'
    with wave.open(str(path),'wb') as wav:
        wav.setparams((2,bits//8,rate,0,'NONE','')); wav.writeframes(pcm)
    subprocess.run([str(exe),str(path),str(output)],capture_output=True,check=True)
    assert output.read_bytes()==pcm
    raw=path.read_bytes()
    for size in (0,3,11,35,43,len(raw)-1):
        broken=work/'broken.wav'; broken.write_bytes(raw[:size])
        assert subprocess.run([str(exe),str(broken),str(output)],capture_output=True).returncode!=0
    mono=bytearray(raw); mono[22:24]=(1).to_bytes(2,'little'); broken.write_bytes(mono)
    assert subprocess.run([str(exe),str(broken),str(output)],capture_output=True).returncode!=0
flac=(root/'.tools/fixtures/stereo-24-96000.flac').read_bytes()
for size in (4,16,len(flac)//2,len(flac)-1):
    broken=work/'broken.flac'; broken.write_bytes(flac[:size])
    assert subprocess.run([str(exe),str(broken),str(output)],capture_output=True).returncode!=0
print('PASS: 24 native WAV/FLAC hashes, 16/24-bit signed extrema/channel order, 18 corrupt/unsupported cases.')
