"""Fail the build if the tested tag writer/payload parser changed without preservation tests."""
import hashlib,json
from pathlib import Path
root=Path(__file__).resolve().parents[1]
report=json.loads((root/'native-tags/preservation-evidence.json').read_text())
assert report['taglibVersion']=='2.3.2'
assert {r['format'] for r in report['results'] if r['passed']}=={'mp3','flac','m4a','ogg','opus'}
assert report['malformedRejected']>=5
assert any(r['format']=='flac' and r.get('sampleRateHz')==96000 and r.get('bits')==24 for r in report['results'])
assert any(r['format']=='m4a' and r['codec']=='alac' for r in report['results'])
for source,digest in report['verifiedSources'].items():
    assert hashlib.sha256((root/source).read_bytes()).hexdigest()==digest, source+' changed; rerun preservation tests'
print('PASS: all five tag containers have matching source/payload-parser preservation evidence.')
