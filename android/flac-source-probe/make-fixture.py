"""Disposable short nonperiodic and bounded-memory long PCM oracles."""
import hashlib
import json
from pathlib import Path
import random
import subprocess
import sys
import wave

folder=Path(sys.argv[1]);folder.mkdir(parents=True,exist_ok=True)
pcm=random.Random(20261109).randbytes((96000+137)*6)
wav=folder/'source.wav'
with wave.open(str(wav),'wb') as out:
    out.setparams((2,3,96000,0,'NONE',''));out.writeframes(pcm)
flac=folder/'24-96000.flac'
subprocess.run(['ffmpeg','-nostdin','-y','-v','error','-i',str(wav),'-c:a','flac',str(flac)],check=True)
reference=subprocess.run(['ffmpeg','-v','error','-i',str(flac),'-f','s24le','-'],capture_output=True,check=True).stdout
assert reference==pcm
(folder/'24-96000.pcm').write_bytes(reference)
(folder/'fixture.json').write_text(json.dumps(dict(frames=96000+137,pcmSha256=hashlib.sha256(pcm).hexdigest(),
    flacSha256=hashlib.sha256(flac.read_bytes()).hexdigest()),indent=2)+'\n')

pcm16=random.Random(137).randbytes((44100+137)*4)
wav16=folder/'source16.wav'
with wave.open(str(wav16),'wb') as out:
    out.setparams((2,2,44100,0,'NONE',''));out.writeframes(pcm16)
flac16=folder/'16-44100.flac'
subprocess.run(['ffmpeg','-nostdin','-y','-v','error','-i',str(wav16),'-c:a','flac',str(flac16)],check=True)
ref16=subprocess.run(['ffmpeg','-v','error','-i',str(flac16),'-f','s16le','-'],capture_output=True,check=True).stdout
assert ref16==pcm16
(folder/'16-44100.pcm').write_bytes(ref16)
pattern=b''.join(value.to_bytes(3,'little',signed=True) for i in range(4)
                 for value in ((32767 if i%2==0 else -257),i-2))
(folder/'long.pattern').write_bytes(pattern)
long_file=folder/'long-24-96000.flac'
process=subprocess.Popen(['ffmpeg','-nostdin','-y','-v','error','-f','s24le','-ar','96000','-ac','2',
                          '-i','pipe:0','-c:a','flac',str(long_file)],stdin=subprocess.PIPE)
remaining=96000*1200//4
chunk=pattern*16384
source_hash=hashlib.sha256()
while remaining:
    units=min(remaining,16384);data=chunk if units==16384 else pattern*units
    process.stdin.write(data);source_hash.update(data);remaining-=units
process.stdin.close();assert process.wait(timeout=120)==0
reference_hash=hashlib.sha256();total=0
with subprocess.Popen(['ffmpeg','-v','error','-i',str(long_file),'-f','s24le','-'],stdout=subprocess.PIPE) as reader:
    while data:=reader.stdout.read(256*1024):reference_hash.update(data);total+=len(data)
    assert reader.wait(timeout=30)==0
assert total==96000*1200*6 and reference_hash.digest()==source_hash.digest()
(folder/'lifecycle-fixtures.json').write_text(json.dumps(dict(longFrames=96000*1200,longPcmBytes=total,
    longPcmSha256=reference_hash.hexdigest(),longFlacBytes=long_file.stat().st_size,
    pcm16Sha256=hashlib.sha256(pcm16).hexdigest(),pcm24Sha256=hashlib.sha256(pcm).hexdigest()),indent=2)+'\n')
