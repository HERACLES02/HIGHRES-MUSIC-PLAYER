"""Offline generated-fixture check for bounded FLAC decoding; no playback."""
from pathlib import Path
import subprocess
import sys
import wave

exe=Path(sys.argv[1]).resolve()
fixture=Path('android/.tools/fixtures/stereo-16-44100.flac')
with wave.open(str(fixture.with_suffix('.wav')),'rb') as wav:
    expected=wav.readframes(wav.getnframes())
r=subprocess.run([str(exe),str(fixture.resolve())],capture_output=True,timeout=20)
assert r.returncode==0 and r.stdout==expected, 'streaming reader must decode complete PCM exactly'
print('PASS: streaming FLAC PCM round-trip')
