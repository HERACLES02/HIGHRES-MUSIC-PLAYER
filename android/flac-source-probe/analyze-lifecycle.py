"""Verify exported real-phone lifecycle/memory evidence without changing it."""
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

folder = Path(sys.argv[1])
load = lambda name: json.loads((folder / (name + '.json')).read_text())
full, background, foreground, paced, recreated, killed, recovered = [load(name) for name in (
    '01-full-source', '02-background', '03-foreground-stopped', '04-paced-120s',
    '05-recreated', '06-before-process-death', '07-process-recovered')]
assert full['cycles'] == 110 and '20-minute-nonzero-source-exact-pattern-md5-bounded-stream' in full['checks']
assert background['sourceStopped'] and foreground['sourceStopped']
assert background['frames'] == foreground['frames']
assert paced['frames'] >= 96000*120
assert recreated['pid'] == paced['pid'] and recreated['session'] != paced['session']
assert recovered['pid'] != killed['pid'] and recovered['session'] != killed['session']
for result in (recreated, recovered):
    assert result['state'] == 'recovered_stopped' and result['recoveredJournal']
    assert result['sourceStopped'] and result['frames'] == 0
    assert result['cleanupRowsDeleted'] == 3 and result['generatedFolderAbsent']

points = paced['samples']
warm = next(p for p in points if p['label'] == 'warm10')
after = next(p for p in points if p['label'] == 'after110')
limits = dict(javaUsedBytes=8*1024**2, nativeDebugAllocatedBytes=8*1024**2,
              rssKiB=16*1024, pssKiB=16*1024, threadCount=2)
growth = {key: after[key] - warm[key] for key in limits}
assert all(growth[key] <= limits[key] for key in limits), ('100-cycle growth', growth)

def graphics(target):
    return target.startswith('/dmabuf:') or target == 'anon_inode:sync_file'


def other_descriptors(point):
    return sum(not graphics(d['target']) for d in point['descriptors'])


# Graphics buffer/fence descriptors warm independently of file-source ownership.
# Never exclude ordinary files, sockets, pipes or unrecognized descriptor types.
non_graphics_growth = other_descriptors(after) - other_descriptors(warm)
assert non_graphics_growth <= 2, ('non-graphics descriptor growth', non_graphics_growth)
for point in points:
    if point['stopped'] and 'descriptors' in point:
        assert not any('FlacLifecycle-' in d['target'] for d in point['descriptors']), ('open source at stopped checkpoint', point)
maximum = {key: max(p[key] for p in points) for key in (*limits, 'fdCount', 'rssHighWaterKiB')}
assert maximum['javaUsedBytes'] < 64*1024**2 and maximum['nativeDebugAllocatedBytes'] < 64*1024**2
assert maximum['rssKiB'] < 256*1024 and maximum['rssHighWaterKiB'] < 256*1024
start = next(p for p in points if p['label'] == 'long_start_paced_resumed')
window = [p for p in points if p['elapsedMs'] >= start['elapsedMs'] + 10000]
assert window and window[-1]['frames'] >= 96000*119
paced_growth = {key: window[-1][key] - window[0][key] for key in limits}
assert all(paced_growth[key] <= limits[key] for key in limits), ('paced growth', paced_growth)
assert window[-1]['fdCount'] - window[0]['fdCount'] <= 2

system_samples = []
for path in sorted(folder.glob('dumpsys-[0-9][0-9].txt')):
    text = path.read_text()
    stamp = re.search(r'Realtime:\s+(\d+)', text)
    memory = re.search(r'TOTAL PSS:\s+(\d+)\s+TOTAL RSS:\s+(\d+)', text)
    pid = re.search(r'MEMINFO in pid (\d+)', text)
    assert stamp and memory and pid
    system_samples.append(dict(elapsedMs=int(stamp[1]), pid=int(pid[1]),
                               pssKiB=int(memory[1]), rssKiB=int(memory[2])))
assert len(system_samples) >= 10, 'independent system memory samples required'
assert max(p['pssKiB'] for p in system_samples) < 192*1024
assert max(p['rssKiB'] for p in system_samples) < 256*1024
system_paced = [p for p in system_samples if p['pid'] == paced['pid'] and
                start['elapsedMs']+10000 <= p['elapsedMs'] <= window[-1]['elapsedMs']+2000]
assert len(system_paced) >= 8
assert system_paced[-1]['pssKiB'] - system_paced[0]['pssKiB'] <= 16*1024
assert system_paced[-1]['rssKiB'] - system_paced[0]['rssKiB'] <= 16*1024

allocators = {}
for point in points:
    if 'allocator' in point:
        root = ET.parse(folder / point['allocator']).getroot()
        assert root.tag == 'malloc' and root.attrib.get('version') == 'jemalloc-1'
        # Sum actual bin values plus large/huge once; do not double-count totals.
        allocators[point['label']] = sum(int(e.text) for e in root.iter()
                                        if e.tag in ('allocated-large', 'allocated-huge', 'allocated'))
assert allocators['after110'] - allocators['warm10'] <= 8*1024**2
summary = dict(result='PASS', cycles=110, fullSourceFrames=115200000,
               fullPcmBytes=691200000, pacedFrames=paced['frames'], sampleCount=len(points),
               maximum=maximum, growthAfter100Cycles=growth, pacedWindowGrowth=paced_growth,
               rawDescriptorGrowth=after['fdCount']-warm['fdCount'], nonGraphicsDescriptorGrowth=non_graphics_growth,
               allocatorAllocatedBytes=allocators, systemMemorySamples=system_samples,
               notes='Per-process GC at checkpoints; periodic samples without forced GC. App PSS may be cached; use independent system figures. Silent debug probe only.')
(folder/'summary.json').write_text(json.dumps(summary, indent=2)+'\n')
print(json.dumps(summary, indent=2))
