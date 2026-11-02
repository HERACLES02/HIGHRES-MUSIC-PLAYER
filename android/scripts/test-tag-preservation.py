"""Generate original quiet fixtures and compare encoded packets/metadata before and after edits."""
import hashlib, json, os, struct, subprocess, zlib
from pathlib import Path

root = Path(__file__).resolve().parents[1]
work = root / '.tools/tag-preservation'
work.mkdir(parents=True, exist_ok=True)
ffmpeg = Path(os.environ.get('MUSIC_TEST_FFMPEG', r'C:\Users\User\AppData\Local\Microsoft\WinGet\Packages\Gyan.FFmpeg.Essentials_Microsoft.Winget.Source_8wekyb3d8bbwe\ffmpeg-8.1.1-essentials_build\bin\ffmpeg.exe'))
ffprobe = ffmpeg.with_name('ffprobe.exe')
cli = root / '.tools/tag-host/tag-check.exe'
classes = root / '.tools/payload-digest-classes'
classes.mkdir(exist_ok=True)
def run(*args):
    p = subprocess.run([str(a) for a in args], check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    return p.stdout
run('javac', '--release', '17', '-d', classes, root / 'player-core/src/main/java/com/localmusic/core/AudioPayloadDigest.java')
def digest(path): return run('java', '-cp', classes, 'com.localmusic.core.AudioPayloadDigest', path).decode().strip()
def packets(path):
    data = json.loads(run(ffprobe, '-v','error','-select_streams','a','-show_packets','-show_entries','packet=data_hash','-show_data_hash','sha256','-of','json',path))
    return [p['data_hash'] for p in data['packets']]
def read(path): return json.loads(run(cli,'read',path))
def chunk(name,data): return struct.pack('>I',len(data))+name+data+struct.pack('>I',zlib.crc32(name+data)&0xffffffff)
cover = work / 'cover.png'
cover.write_bytes(b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',16,16,8,2,0,0,0))+chunk(b'IDAT',zlib.compress((b'\0'+b'\x20\x80\x60'*16)*16))+chunk(b'IEND',b''))
fixtures = [('flac','flac','stereo-16-44100.wav'),('mp3','libmp3lame','stereo-16-44100.wav'),('m4a','aac','stereo-16-44100.wav'),
    ('ogg','libvorbis','stereo-16-44100.wav'),('opus','libopus','stereo-16-44100.wav'),('flac','flac','stereo-24-96000.wav'),('m4a','alac','stereo-24-96000.wav')]
results=[]
for ext,codec,source in fixtures:
    path = work / ('edit.'+ext)
    extra = ['-write_id3v1','1'] if ext=='mp3' else []
    run(ffmpeg,'-v','error','-y','-i',root/'.tools/fixtures'/source,'-c:a',codec,*extra,
        '-metadata','title=Original','-metadata','artist=Original Artist','-metadata','album=Keep Album','-metadata','comment=Keep Comment',
        '-metadata','genre=Keep Genre','-metadata','lyrics=[00:01.000]Keep lyrics',path)
    before=read(path); payload=digest(path); encoded=packets(path)
    descriptor = work/('descriptor_'+ext); descriptor.write_bytes(path.read_bytes())
    assert read(descriptor)==before,(ext,'extension-free descriptor sniffing failed')
    run(cli,'edit',path,'TITLE','Changed','ARTIST','New Artist','TRACKNUMBER','3')
    after=read(path)
    assert after['properties']['TITLE']==['Changed'] and after['properties']['ARTIST']==['New Artist'],ext
    for key in ['ALBUM','COMMENT','GENRE','LYRICS']:
        if after['properties'].get(key)!=before['properties'].get(key): print('Property mismatch',ext,key,before['properties'],after['properties'])
        assert after['properties'].get(key)==before['properties'].get(key),(ext,key)
    assert digest(path)==payload and packets(path)==encoded,(ext,'encoded audio changed')
    run(cli,'cover',path,'1',cover)
    assert read(path)['pictureCount']==1,ext
    assert digest(path)==payload,(ext,'cover changed audio fingerprint')
    assert packets(path)==encoded,(ext,'cover changed encoded audio packets')
    run(cli,'cover',path,'1',cover)
    assert read(path)['pictureCount']==1,(ext,'cover replacement duplicated artwork')
    run(cli,'cover',path,'2')
    assert read(path)['pictureCount']==0,(ext,'cover removal failed')
    run(cli,'edit',path,'COMMENT','')
    assert not read(path)['properties'].get('COMMENT'),(ext,'explicit clearing failed')
    assert digest(path)==payload and packets(path)==encoded,(ext,'clear changed audio')
    unicode = work/'unicode.txt'; unicode.write_text('বাংলা 🎵',encoding='utf-8')
    run(cli,'edit',path,'TITLE','@'+str(unicode),'ALBUMARTIST','Album Artist','ALBUM','New Album','DISCNUMBER','2','DATE','2024-01-02','GENRE','New Genre','COMMENT','New comment')
    all_fields=read(path)['properties']
    for key,value in [('TITLE','বাংলা 🎵'),('ALBUMARTIST','Album Artist'),('ALBUM','New Album'),('DISCNUMBER','2'),('DATE','2024-01-02'),('GENRE','New Genre'),('COMMENT','New comment')]:
        assert all_fields.get(key)==[value],(ext,key,all_fields)
    assert digest(path)==payload and packets(path)==encoded,(ext,'other fields changed audio')
    run(cli,'edit',path,'ARTIST','Singer A\nSinger B','COMMENT','Line one\nLine two')
    credits=read(path)['properties']
    assert credits['ARTIST']==['Singer A','Singer B'] and credits['COMMENT']==['Line one\nLine two'],(ext,'multi-artist or multiline comment failed',credits)
    assert digest(path)==payload and packets(path)==encoded,(ext,'credits/comment changed audio')
    run(cli,'cover',path,'1',cover);run(cli,'back',path,cover)
    attached=read(path)['pictureFingerprints'];assert len(attached)==2,ext
    run(cli,'edit',path,'TITLE','Artwork retained')
    assert read(path)['pictureFingerprints']==attached,(ext,'field edit changed attached artwork')
    run(cli,'cover',path,'1',cover)
    replacement=read(path)['pictureFingerprints'];assert len(replacement)==2 and replacement[1:]==attached[1:],(ext,'replacement changed other artwork')
    run(cli,'cover',path,'2')
    assert read(path)['pictureFingerprints']==attached[1:],(ext,'removal changed other artwork')
    assert digest(path)==payload and packets(path)==encoded,(ext,'retained artwork changed audio')
    changed = bytearray(path.read_bytes()); changed[len(changed)//2] ^= 1
    corrupt = work/('changed.'+ext); corrupt.write_bytes(changed)
    assert digest(corrupt)!=payload,(ext,'audio-change detector missed a change')
    results.append({'format':ext,'codec':codec,'fixture':source,'sampleRateHz':before['sampleRateHz'],'bits':before['bits'],'passed':True,'encodedPacketCount':len(encoded),'payloadSha256':payload})
    print('PASS',ext,': fields, unrelated tags, cover add/replace/remove, explicit clear, exact encoded packets and change detection.')
for bad in [b'',b'OggS\0',b'fLaC\xff\xff\xff\xff',b'ID3\x04\0\0\xff\xff\xff\xff',b'\0\0\0\x10ftyp']:
    path=work/'malformed.bin';path.write_bytes(bad)
    p=subprocess.run(['java','-cp',str(classes),'com.localmusic.core.AudioPayloadDigest',str(path)],capture_output=True)
    assert p.returncode!=0,'Malformed container accepted'
report={'taglibVersion':'2.3.2','scope':'Host-generated fixtures; exact encoded packet and Java payload fingerprints, not Android provider acceptance','results':results,'malformedRejected':5,
    'verifiedSources':{name:hashlib.sha256((root/name).read_bytes()).hexdigest() for name in ['native-tags/tags.cpp','native-tags/tags.hpp','native-tags/jni.cpp','player-core/src/main/java/com/localmusic/core/AudioPayloadDigest.java']}}
(root/'native-tags/preservation-evidence.json').write_text(json.dumps(report,indent=2)+'\n')
print('PASS: all five editable containers; 5 malformed-container rejections.')
