"""Read-only memory sampler for the installed Local Music process.

The operator sets phase.txt in the new output directory and creates stop.txt to
finish. This script never installs, controls playback, edits music or settings.
Raw memory reports and bounded JSON stay in the specified local output folder.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time

parser = argparse.ArgumentParser()
parser.add_argument('--serial', required=True)
parser.add_argument('--port', default='5038')
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=False)
adb = Path(__file__).resolve().parents[1] / '.tools/sdk/platform-tools/adb.exe'
command = [str(adb), '-P', args.port, '-s', args.serial, 'shell']
package = 'com.localmusic.player'
points = []
started = time.monotonic()
pid = None


def query(*words):
    return subprocess.check_output(command + list(words), timeout=20).decode('utf-8')


def allocated(text, name):
    row = re.search(r'^\s*' + name + r' Heap\s+([0-9 ]+)\s*$', text, re.M)
    assert row, ('missing heap measurement', name)
    values = [int(value) for value in row[1].split()]
    assert len(values) == 8, (name, values)
    return values[6]


try:
    while len(points) < 91 and not (args.output / 'stop.txt').exists():
        phase_file = args.output / 'phase.txt'
        phase = phase_file.read_text().strip() if phase_file.exists() else 'baseline'
        memory = query('dumpsys', 'meminfo', package)
        process = re.search(r'MEMINFO in pid (\d+) \[' + re.escape(package) + r'\]', memory)
        totals = re.search(r'TOTAL PSS:\s+(\d+)\s+TOTAL RSS:\s+(\d+)', memory)
        assert process and totals, 'target process memory unavailable'
        measured_pid = int(process[1])
        if pid is None:
            pid = measured_pid
        assert pid == measured_pid, 'target process restarted during measurement'
        session = query('dumpsys', 'media_session')
        marker = 'package=' + package
        offset = session.find(marker)
        assert offset >= 0, 'Local Music session unavailable'
        # Only inspect this session. Do not retain other applications' metadata.
        section = session[offset:]
        end = re.search(r'\n    \S', section)
        if end:
            section = section[:end.start()]
        state = re.search(r'state=PlaybackState \{state=(\d+), position=(\d+)', section)
        assert state, 'target playback state unavailable'
        point = dict(elapsedSeconds=round(time.monotonic()-started, 3), phase=phase,
                     pid=pid, pssKiB=int(totals[1]), rssKiB=int(totals[2]),
                     dalvikAllocatedKiB=allocated(memory, 'Dalvik'),
                     nativeAllocatedKiB=allocated(memory, 'Native'),
                     playbackState=int(state[1]), positionMs=int(state[2]),
                     generatedTrackInMetadata='ZZ Memory Test 192k 20261109' in section)
        (args.output / ('meminfo-%03d.txt' % len(points))).write_text(memory)
        points.append(point)
        (args.output / 'samples.json').write_text(json.dumps(points, indent=2)+'\n')
        print(json.dumps(point), flush=True)
        for _ in range(10):
            if (args.output / 'stop.txt').exists():
                break
            time.sleep(1)
    assert (args.output / 'stop.txt').exists(), 'measurement duration cap reached'
finally:
    (args.output / 'samples.json').write_text(json.dumps(points, indent=2)+'\n')
