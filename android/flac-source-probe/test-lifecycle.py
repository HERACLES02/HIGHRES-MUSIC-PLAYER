"""Run only the installed silent probe; export evidence before its removal.

No install, broad deletion, music access or audio output. Requires the generated
fixtures and mounted test SD used by LifecycleActivity. Device/ADB are explicit.
"""
import argparse
import json
from pathlib import Path
import subprocess
import time
import re

parser = argparse.ArgumentParser()
parser.add_argument('--adb', required=True)
parser.add_argument('--serial', required=True)
parser.add_argument('--port', default='5038')
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=False)
adb = [args.adb, '-P', args.port, '-s', args.serial]
package = 'com.localmusic.flacsourceprobe'
activity = package + '/.LifecycleActivity'
system_samples = []
last_system_sample = 0


def call(*command):
    return subprocess.check_output(adb + list(command), timeout=20)


def report():
    result = json.loads(call('exec-out', 'run-as', package, 'cat', 'files/lifecycle-report.json'))
    assert result['state'] != 'failed', result
    return result


def wait(predicate, timeout=120):
    global last_system_sample
    limit = time.monotonic() + timeout
    while time.monotonic() < limit:
        current = report()
        if time.monotonic() - last_system_sample >= 10:
            raw = call('shell', 'dumpsys', 'meminfo', package).decode('utf-8')
            match = re.search(r'TOTAL PSS:\s+(\d+)\s+TOTAL RSS:\s+(\d+)', raw)
            assert match, 'system memory report unavailable'
            system_samples.append(dict(state=current['state'], frames=current['frames'], pid=current['pid'],
                                       pssKiB=int(match[1]), rssKiB=int(match[2])))
            (args.output / ('dumpsys-%02d.txt' % len(system_samples))).write_text(raw)
            last_system_sample = time.monotonic()
        if predicate(current):
            return current
        time.sleep(1)
    raise AssertionError(('phase timeout', current))


def export(name, current):
    (args.output / (name + '.json')).write_text(json.dumps(current, indent=2) + '\n')
    for point in current['samples']:
        if 'allocator' in point:
            path = args.output / point['allocator']
            path.write_bytes(call('exec-out', 'run-as', package, 'cat', 'files/' + path.name))
    print(name, current['state'], 'pid', current['pid'], 'frames', current['frames'], flush=True)


def launch(command=None):
    extra = ['--es', 'command', command] if command else []
    call('shell', 'am', 'start', '-W', '-n', activity, *extra)


try:
    full = wait(lambda r: r['state'] == 'waiting_background' and r['frames'] >= 96000)
    assert full['cycles'] == 110
    assert '20-minute-nonzero-source-exact-pattern-md5-bounded-stream' in full['checks']
    export('01-full-source', full)
    call('shell', 'input', 'keyevent', 'KEYCODE_HOME')
    stopped = wait(lambda r: r['state'] == 'background_stopped')
    assert stopped['sourceStopped']
    time.sleep(3)
    assert report()['frames'] == stopped['frames'], 'background consumer must stop'
    export('02-background', report())
    launch()
    returned = wait(lambda r: r['state'] == 'resumed_still_stopped')
    time.sleep(2)
    assert report()['sourceStopped'] and report()['frames'] == returned['frames']
    export('03-foreground-stopped', report())
    launch('resume')
    paced = wait(lambda r: r['state'] == 'paced_resumed' and r['frames'] >= 96000 * 120, timeout=150)
    export('04-paced-120s', paced)
    launch('recreate')
    recreated = wait(lambda r: r['state'] == 'recovered_stopped' and r['session'] != paced['session'])
    assert recreated['pid'] == paced['pid'], 'activity recreation in same process'
    assert recreated['recoveredJournal'] and recreated['cleanupRowsDeleted'] == 3
    assert recreated['sourceStopped'] and recreated['frames'] == 0 and recreated['generatedFolderAbsent']
    export('05-recreated', recreated)
    launch('prepare_kill')
    killed = wait(lambda r: r['state'] == 'waiting_process_death' and r['frames'] >= 96000)
    export('06-before-process-death', killed)
    call('shell', 'am', 'force-stop', package)
    launch('recover')
    recovered = wait(lambda r: r['state'] == 'recovered_stopped' and r['session'] != killed['session'])
    assert recovered['pid'] != killed['pid'], 'new process required'
    assert recovered['recoveredJournal'] and recovered['cleanupRowsDeleted'] == 3
    assert recovered['sourceStopped'] and recovered['frames'] == 0 and recovered['generatedFolderAbsent']
    export('07-process-recovered', recovered)

    subprocess.run([__import__('sys').executable, str(Path(__file__).with_name('analyze-lifecycle.py')), str(args.output)], check=True)
except BaseException:
    # Export the latest scoped report; do not hide failure or erase the journal.
    try:
        export('failure', report())
    except Exception:
        pass
    raise
