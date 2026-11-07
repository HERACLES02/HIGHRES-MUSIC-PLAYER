"""Exercise actual compiled queue via bounded OS pipes; no playback or music writes."""
from pathlib import Path
import subprocess
import sys

exe=Path(sys.argv[1]).resolve()
tests=0
for bits in (16,24):
    for rate in (44100,48000,88200,96000):
        width=bits//8
        # Non-integral duration and signed low bits, different L/R samples.
        pcm=b''.join((((i*137+ch*8191)%(1<<bits))-(1<<(bits-1))).to_bytes(width,'little',signed=True)
                     for i in range(10003) for ch in (0,1))
        run=subprocess.run([str(exe),str(rate),str(bits)],input=pcm,capture_output=True,timeout=15)
        assert run.returncode==0,run.stderr
        assert run.stdout==pcm
        assert f'frames=10003 queueBytes={4096*2*width}'.encode() in run.stderr
        bad=subprocess.run([str(exe),str(rate),str(bits)],input=pcm+b'\x01',capture_output=True,timeout=15)
        assert bad.returncode==1 and len(bad.stdout)%(2*width)==0
        empty=subprocess.run([str(exe),str(rate),str(bits)],input=b'',capture_output=True,timeout=15)
        assert empty.returncode==0 and not empty.stdout
        tests+=3
print(f'PASS: {tests} raw-pipe checks: exact finite bytes, incomplete-frame error and empty EOF across eight formats')
