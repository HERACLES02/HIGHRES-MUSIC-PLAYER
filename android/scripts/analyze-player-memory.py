"""Check the exported finite Normal-player memory run; no phone operations."""
import json
from pathlib import Path
import statistics
import sys

folder = Path(sys.argv[1])
load = lambda path: json.loads((folder / path).read_text())
points = load('measurements/samples.json')
budgets = load('budgets.json')
assert points and len({point['pid'] for point in points}) == 1, 'one uninterrupted process required'
foreground = [point for point in points if point['phase'] == 'foreground']
assert len(foreground) >= 25
span = foreground[-1]['elapsedSeconds'] - foreground[0]['elapsedSeconds']
advance = foreground[-1]['positionMs'] - foreground[0]['positionMs']
assert span >= budgets['foregroundTargetSeconds'] and advance >= 300000
assert 0.9 <= advance / (span * 1000) <= 1.1, 'source position must advance with elapsed playback'
assert all(p['playbackState'] == 3 and p['generatedTrackInMetadata'] for p in foreground)
assert all(b['positionMs'] > a['positionMs'] for a, b in zip(foreground, foreground[1:]))
boundary = load('background-boundary.json')['lastVerifiedBackgroundSampleSeconds']
background = [point for point in points if point['phase'] == 'background' and point['elapsedSeconds'] <= boundary]
assert len(background) >= 3 and background[-1]['elapsedSeconds']-background[0]['elapsedSeconds'] >= 20
assert all(p['playbackState'] == 3 and p['generatedTrackInMetadata'] for p in background)
assert background[-1]['positionMs']-background[0]['positionMs'] >= 20000
paused = [point for point in points if point['phase'] == 'paused']
assert len(paused) >= 2 and all(p['playbackState'] == 2 for p in paused)
assert paused[-1]['elapsedSeconds']-paused[0]['elapsedSeconds'] >= 10
assert abs(paused[-1]['positionMs']-paused[0]['positionMs']) < 1000
stopped = [point for point in points if point['phase'] == 'stopped']
assert len(stopped) >= 2 and all(p['playbackState'] in (0, 1, 2) for p in stopped)
assert abs(stopped[-1]['positionMs']-stopped[0]['positionMs']) < 1000
seeks = load('seek-checks.json')
assert len(seeks) == budgets['seekTargetCount']
assert all(p['confirmed'] and p['state'] == 3 and p['generatedTrack'] for p in seeks)
keys = ('pssKiB', 'rssKiB', 'dalvikAllocatedKiB', 'nativeAllocatedKiB')
maximum = {key: max(point[key] for point in points) for key in keys}
for key, budget in zip(keys, ('maxPssKiB', 'maxRssKiB', 'maxDalvikAllocatedKiB', 'maxNativeAllocatedKiB')):
    assert maximum[key] < budgets[budget], ('sampled memory limit', key, maximum[key], budgets[budget])
early = [p for p in foreground if 60000 <= p['positionMs'] <= 120000]
late = [p for p in foreground if p['positionMs'] >= foreground[-1]['positionMs'] - 60000]
assert len(early) >= 4 and len(late) >= 4
median = lambda rows, key: statistics.median(point[key] for point in rows)
growth = {key: median(late, key)-median(early, key) for key in keys}
growth_limits = dict(pssKiB=budgets['warmedGrowthPssKiB'], rssKiB=budgets['warmedGrowthRssKiB'],
                     dalvikAllocatedKiB=budgets['warmedGrowthDalvikKiB'], nativeAllocatedKiB=budgets['warmedGrowthNativeKiB'])
assert all(growth[key] <= growth_limits[key] for key in keys), ('warmed playback growth', growth)
seek_points = [p for p in points if p['phase'] == 'seeks']
assert len(seek_points) >= 4
seek_growth = {key: median(seek_points[-3:], key)-median(seek_points[:3], key) for key in keys}
assert all(seek_growth[key] <= growth_limits[key] for key in keys), ('repeated seek growth', seek_growth)
fixture = load('fixture.json')
assert fixture['sample_rate'] == '192000' and fixture['bits_per_raw_sample'] == '24' and fixture['pcmBytes'] == 1382400000
summary = dict(result='PASS', pid=points[0]['pid'], samples=len(points), sourceSeconds=1200,
               foregroundElapsedSeconds=span, foregroundPositionAdvanceMs=advance,
               confirmedSeeks=len(seeks), backgroundSamples=len(background),
               sampledMaximumKiB=maximum, warmedMedianGrowthKiB=growth, seekMedianGrowthKiB=seek_growth,
               note='Installed Normal player; generated source. Ten-second samples may miss short peaks. No DAC clock, bit-perfect, 20-minute real-time endurance or dual-pipeline claim.')
(folder/'memory-summary.json').write_text(json.dumps(summary, indent=2)+'\n')
print(json.dumps(summary, indent=2))
