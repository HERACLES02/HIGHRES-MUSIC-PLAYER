// Run with: node --test tests/playback.test.cjs
// Execute the real player functions with fake audio elements. No server,
// passwords, music files, browser packages, or network access are needed.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../static/app.js'), 'utf8');
const names = [
  'playTrackId', 'playTrackFromHome', 'startListening', 'playCurrentView', 'playIndex', 'playSelectedTrack', 'resumeTrack', 'trackTile', 'fmtTime', 'showPlaybackError', 'requestPlayback',
  'nextTrack', 'prevTrack', 'playRandomTrack', 'playTrackFromAnyQueue',
  'resolveUpcomingTrack', 'clampVolume', 'maybeStartCrossfade',
  'beginCrossfadeTo', 'cancelCrossfadeIfActive', 'addToQueue', 'playNext',
  'playQueuedIndex', 'removeQueuedIndex', 'clearQueue', 'moveQueuedTrack',
  'togglePlayPause', 'toggleShuffle', 'bindPlaybackListeners', 'seekBy',
];
const functions = names.map((name) => {
  const match = source.match(new RegExp(`^      (?:async )?function ${name}\\([^]*?^      }`, 'm'));
  assert.ok(match, `Player function ${name} must exist`);
  return match[0];
}).join('\n');

function fakeAudio() {
  const listeners = new Map();
  return {
    src: '', paused: true, currentTime: 0, duration: 100, volume: 1,
    play() { this.paused = false; return Promise.resolve(); },
    load() { this.paused = true; this.currentTime = 0; },
    pause() { this.paused = true; },
    addEventListener(name, fn) {
      if (!listeners.has(name)) listeners.set(name, new Set());
      listeners.get(name).add(fn);
    },
    removeEventListener(name, fn) { listeners.get(name)?.delete(fn); },
    emit(name) { for (const fn of [...(listeners.get(name) || [])]) fn(); },
  };
}

function setup() {
  const tracks = ['a', 'b', 'c'].map((id) => ({ id, title: id, url: `/music/${id}.mp3` }));
  const ctx = vm.createContext({
    audioA: fakeAudio(), audioB: fakeAudio(), libraryPlayable: tracks,
    activePlayable: tracks.slice(), currentIndex: 0, currentTrack: tracks[0],
    browsePlayable: tracks.slice(), filteredTracks: tracks.slice(), pendingResumePosition: 0,
    currentTrackId: 'a', manualQueue: [], crossfadeEnabled: true,
    crossfading: false, fadeAnimationFrame: null, pendingCrossfade: null,
    failedCrossfadeTrackId: null, playbackRequestId: 0, clearResumeSeek: null,
    playbackErrorShown: false, playRecorded: false, lastPositionSync: 0,
    isSeeking: false, shuffleEnabled: false, repeatMode: 'off',
    currentView: 'library', CROSSFADE_SECONDS: 4, vol: { value: '0.7' },
    historyData: { tracks: {} },
    performance: { now: () => 0 }, Math, Number, Promise,
    requestAnimationFrame(fn) { ctx.frame = fn; return 1; },
    cancelAnimationFrame() { ctx.frame = null; },
    records: [], positions: [], messages: [], icon: false,
    recordTrackPlay(id) { ctx.records.push(id); },
    syncTrackPosition() { ctx.positions.push([ctx.currentTrackId, ctx.audio.currentTime]); },
    showToast(message) { ctx.messages.push(message); },
    setPlayPauseIcon(value) { ctx.icon = value; },
    setNowPlaying() {}, renderQueue() {}, syncProgress() {},
    persistPlayerState() {}, updatePlaybackButtons() {},
    tileCover() { return ''; }, escapeHtml(value) { return String(value); },
  });
  ctx.audio = ctx.audioA;
  ctx.audio.src = tracks[0].url;
  ctx.audio.paused = false;
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../static/listening-queue.js'), 'utf8'), ctx);
  vm.runInContext("var listeningQueue = new ListeningQueue(); listeningQueue.start(['a', 'b', 'c'], 'a');", ctx);
  vm.runInContext(functions, ctx);
  return ctx;
}

function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

// VM and host promises can take more than one microtask turn to settle.
const settlePlayback = () => new Promise((resolve) => setImmediate(resolve));

test('a failed crossfade keeps the old song, volume, queue, and history', async () => {
  const c = setup();
  c.manualQueue = [c.libraryPlayable[1]];
  c.audioB.play = () => Promise.reject(new Error('unsupported file'));
  await c.beginCrossfadeTo(c.resolveUpcomingTrack());
  assert.equal(c.audio, c.audioA);
  assert.equal(c.currentTrackId, 'a');
  assert.equal(c.audioA.paused, false);
  assert.equal(c.audioA.volume, 1);
  assert.equal(c.manualQueue.length, 1);
  assert.deepEqual(c.records, []);
  assert.equal(c.failedCrossfadeTrackId, 'a');
});

test('the queue is consumed only after the next song starts', async () => {
  const c = setup();
  const pending = deferred();
  c.manualQueue = [c.libraryPlayable[2]];
  c.activePlayable = [c.libraryPlayable[0]];
  c.listeningQueue.start(['a'], 'a');
  c.audioB.play = () => pending.promise;
  const transition = c.beginCrossfadeTo(c.resolveUpcomingTrack());
  assert.equal(c.currentTrackId, 'a');
  assert.equal(c.manualQueue.length, 1);
  pending.resolve();
  await transition;
  assert.equal(c.currentTrackId, 'c');
  assert.equal(c.manualQueue.length, 0);
  assert.equal(c.listeningQueue.currentId, 'c');
  assert.deepEqual(Array.from(c.activePlayable, (track) => track.id), ['a']);
  assert.deepEqual(c.records, ['c']);
  c.frame(4000);
  assert.equal(c.audioA.paused, true);
  assert.equal(c.audioB.volume, 0.7);
});

test('pausing during a pending crossfade prevents a late song switch', async () => {
  const c = setup();
  const pending = deferred();
  c.manualQueue = [c.libraryPlayable[1]];
  c.audioB.play = () => pending.promise;
  const transition = c.beginCrossfadeTo(c.resolveUpcomingTrack());
  c.togglePlayPause();
  pending.resolve();
  await transition;
  assert.equal(c.currentTrackId, 'a');
  assert.equal(c.audioA.paused, true);
  assert.equal(c.manualQueue.length, 1);
  assert.equal(c.frame, undefined);
});

test('changing the queue cancels its pending crossfade selection', async () => {
  const c = setup();
  const pending = deferred();
  c.manualQueue = [c.libraryPlayable[1]];
  c.audioB.play = () => pending.promise;
  const transition = c.beginCrossfadeTo(c.resolveUpcomingTrack());
  c.playNext('c');
  pending.resolve();
  await transition;
  assert.equal(c.currentTrackId, 'a');
  assert.deepEqual(Array.from(c.manualQueue, (track) => track.id), ['c', 'b']);
});

test('Next plays queued songs even when the page being browsed is empty', async () => {
  const c = setup();
  c.browsePlayable = [];
  c.filteredTracks = [];
  c.manualQueue = [c.libraryPlayable[1]];
  c.nextTrack();
  await settlePlayback();
  assert.equal(c.currentTrackId, 'b');
  assert.equal(c.manualQueue.length, 0);
});

test('a rejected play shows an error without recording a successful play', async () => {
  const c = setup();
  c.audioA.play = () => Promise.reject({ name: 'NotAllowedError' });
  c.playIndex(1);
  await settlePlayback();
  assert.equal(c.icon, false);
  assert.match(c.messages[0], /blocked playback/);
  assert.deepEqual(c.records, []);
});

test('a rejection from an older song cannot overwrite the new player state', async () => {
  const c = setup();
  const pending = deferred();
  c.audioA.play = () => pending.promise;
  c.playIndex(1);
  c.audioA.play = () => { c.audioA.paused = false; return Promise.resolve(); };
  c.playIndex(2);
  await settlePlayback();
  pending.reject(new Error('old failure'));
  await settlePlayback();
  assert.equal(c.currentTrackId, 'c');
  assert.equal(c.icon, true);
  assert.deepEqual(c.messages, []);
  assert.deepEqual(c.records, ['c']);
});

test('quick song changes remove the previous resume seek callback', () => {
  const c = setup();
  c.playIndex(1, 65);
  c.playIndex(2);
  c.audioA.currentTime = 0;
  c.audioA.emit('loadedmetadata');
  assert.equal(c.audioA.currentTime, 0);
});

test('a saved position beyond the loaded song duration restarts the song', () => {
  const c = setup();
  c.playIndex(1, 200);
  c.audioA.emit('loadedmetadata');
  assert.equal(c.audioA.currentTime, 0);
});

test('reordering preserves duplicate entries and rejects invalid indices', () => {
  const c = setup();
  c.manualQueue = [c.libraryPlayable[0], c.libraryPlayable[1], c.libraryPlayable[0]];
  c.moveQueuedTrack(1, -1);
  assert.deepEqual(Array.from(c.manualQueue, (track) => track.id), ['b', 'a', 'a']);
  c.moveQueuedTrack(0, -1);
  c.removeQueuedIndex(-1);
  c.playQueuedIndex(NaN);
  assert.equal(c.manualQueue.length, 3);
});

test('a missing song produces a message instead of recursive retries', () => {
  const c = setup();
  c.playTrackId('missing');
  assert.match(c.messages[0], /no longer in the library/);
  assert.equal(c.currentTrackId, 'a');
});

test('crossfade does not start while paused, seeking, or after a failed attempt', () => {
  const c = setup();
  c.audioA.currentTime = 98;
  c.audioA.paused = true;
  c.maybeStartCrossfade();
  assert.equal(c.pendingCrossfade, null);
  c.audioA.paused = false;
  c.isSeeking = true;
  c.maybeStartCrossfade();
  assert.equal(c.pendingCrossfade, null);
  c.isSeeking = false;
  c.failedCrossfadeTrackId = 'a';
  c.maybeStartCrossfade();
  assert.equal(c.pendingCrossfade, null);
});

test('media errors stop a fade and report the failure once', () => {
  const c = setup();
  c.bindPlaybackListeners(c.audioA);
  c.audioA.error = { code: 4 };
  c.audioA.emit('error');
  c.audioA.emit('error');
  assert.equal(c.icon, false);
  assert.equal(c.messages.length, 1);
});

test('seeking cancels a pending crossfade without consuming the queue', async () => {
  const c = setup();
  const pending = deferred();
  c.manualQueue = [c.libraryPlayable[1]];
  c.audioB.play = () => pending.promise;
  const transition = c.beginCrossfadeTo(c.resolveUpcomingTrack());
  c.seekBy(20);
  pending.resolve();
  await transition;
  assert.equal(c.audio, c.audioA);
  assert.equal(c.audio.currentTime, 20);
  assert.equal(c.manualQueue.length, 1);
});

test('browsing another list while crossfade loads leaves the listening list intact', async () => {
  const c = setup();
  const pending = deferred();
  c.audioB.play = () => pending.promise;
  const transition = c.beginCrossfadeTo(c.resolveUpcomingTrack());
  c.browsePlayable = [c.libraryPlayable[2]];
  pending.resolve();
  await transition;
  assert.equal(c.currentTrackId, 'b');
  assert.equal(c.activePlayable[c.currentIndex].id, 'b');
  c.nextTrack();
  assert.equal(c.currentTrackId, 'c');
});

test('pausing an active crossfade silences both elements and restores volume', async () => {
  const c = setup();
  await c.beginCrossfadeTo(c.resolveUpcomingTrack());
  c.togglePlayPause();
  assert.equal(c.audioA.paused, true);
  assert.equal(c.audioB.paused, true);
  assert.equal(c.audioB.volume, 0.7);
  assert.equal(c.frame, null);
});

test('a normal song click starts from zero even in Continue Listening', () => {
  const c = setup();
  c.currentView = 'smart_continue';
  c.historyData.tracks.b = { last_position: 65, duration: 100 };
  c.playTrackId('b');
  c.audioA.emit('loadedmetadata');
  assert.equal(c.currentTrackId, 'b');
  assert.equal(c.audioA.currentTime, 0);
});

test('clicking the selected song again restarts it instead of unpausing', () => {
  const c = setup();
  c.audioA.currentTime = 45;
  c.audioA.paused = true;
  c.playTrackId('a');
  assert.equal(c.audioA.currentTime, 0);
});

test('the transport Play button keeps the paused position', async () => {
  const c = setup();
  c.audioA.currentTime = 45;
  c.audioA.paused = true;
  c.togglePlayPause();
  await settlePlayback();
  assert.equal(c.audioA.currentTime, 45);
  assert.equal(c.audioA.paused, false);
});

test('an explicit Resume action seeks to the saved position', () => {
  const c = setup();
  c.historyData.tracks.b = { last_position: 65, duration: 100, completed: false };
  c.resumeTrack('b');
  c.audioA.emit('loadedmetadata');
  assert.equal(c.audioA.currentTime, 65);
});

test('Resume on the currently paused song keeps its live position', () => {
  const c = setup();
  c.historyData.tracks.a = { last_position: 10, duration: 100 };
  c.playRecorded = true;
  c.audioA.currentTime = 47;
  c.audioA.paused = true;
  c.resumeTrack('a');
  assert.equal(c.audioA.currentTime, 47);
});

test('explicit Resume restarts completed songs rather than playing the final seconds', () => {
  const c = setup();
  c.historyData.tracks.b = { last_position: 98, duration: 100, completed: true };
  c.resumeTrack('b');
  c.audioA.emit('loadedmetadata');
  assert.equal(c.audioA.currentTime, 0);
});

test('saving a position updates local history immediately', () => {
  const c = setup();
  const saves = [];
  c.apiFetch = (url, options) => { saves.push(JSON.parse(options.body)); return Promise.resolve(); };
  c.playRecorded = true;
  c.audioA.currentTime = 47;
  const actual = source.match(/^      function syncTrackPosition\([^]*?^      }/m)[0];
  vm.runInContext(actual, c);
  c.syncTrackPosition(true);
  assert.equal(c.historyData.tracks.a.last_position, 47);
  assert.equal(saves[0].position, 47);
});

test('loading a saved position cannot overwrite it with a temporary zero', () => {
  const c = setup();
  const saves = [];
  c.apiFetch = (...args) => { saves.push(args); return Promise.resolve(); };
  c.historyData.tracks.b = { last_position: 65, duration: 100 };
  c.resumeTrack('b');
  const actual = source.match(/^      function syncTrackPosition\([^]*?^      }/m)[0];
  vm.runInContext(actual, c);
  c.syncTrackPosition(true);
  assert.equal(saves.length, 0);
  assert.equal(c.historyData.tracks.b.last_position, 65);
});

test('Continue Listening tiles separate starting fresh from explicit Resume', () => {
  const c = setup();
  c.historyData.tracks.a = { last_position: 47 };
  const html = c.trackTile(c.libraryPlayable[0], true);
  assert.match(html, /data-action="play-home-track"/);
  assert.match(html, /title="Play from beginning"/);
  assert.match(html, /data-action="resume-track"/);
  assert.match(html, /Resume<br>0:47/);
  assert.equal((html.match(/<button\b/g) || []).length, 2);
});

test('a delayed play-count response cannot restore an older saved position', async () => {
  const c = setup();
  const pending = deferred();
  c.apiFetch = () => pending.promise;
  const actual = source.match(/^      async function recordTrackPlay\([^]*?^      }/m)[0];
  vm.runInContext(actual, c);
  const recording = c.recordTrackPlay('a');
  c.historyData.tracks.a = { last_position: 47, completed: false };
  pending.resolve({ ok: true, json: async () => ({ history: { last_position: 10, completed: true, play_count: 3, last_played: 'today' } }) });
  await recording;
  assert.equal(c.historyData.tracks.a.last_position, 47);
  assert.equal(c.historyData.tracks.a.completed, false);
  assert.equal(c.historyData.tracks.a.play_count, 3);
});

test('starting a filtered/sorted result builds playback in that exact order', () => {
  const c = setup();
  c.filteredTracks = [c.libraryPlayable[2], c.libraryPlayable[1]];
  c.playTrackId('c');
  assert.deepEqual(Array.from(c.listeningQueue.base), ['c', 'b']);
  c.browsePlayable = [c.libraryPlayable[0]];
  c.filteredTracks = [c.libraryPlayable[0]];
  c.nextTrack();
  assert.equal(c.currentTrackId, 'b');
});

test('Previous restarts after three seconds, then goes back when pressed again', () => {
  const c = setup();
  c.nextTrack();
  assert.equal(c.currentTrackId, 'b');
  c.audio.currentTime = 8;
  c.prevTrack();
  assert.equal(c.currentTrackId, 'b');
  assert.equal(c.audio.currentTime, 0);
  c.prevTrack();
  assert.equal(c.currentTrackId, 'a');
  c.nextTrack();
  assert.equal(c.currentTrackId, 'b');
});

test('the album Shuffle action starts shuffled playback immediately', async () => {
  const c = setup();
  c.filteredTracks = [c.libraryPlayable[1], c.libraryPlayable[2]];
  c.audio.pause();
  c.playCurrentView(true);
  await settlePlayback();
  assert.equal(c.shuffleEnabled, true);
  assert.equal(c.audio.paused, false);
  assert.deepEqual(Array.from(c.listeningQueue.base).sort(), ['b', 'c']);
  assert.equal(c.records.length, 1);
});

test('Next up shows automatic songs even without manual queue entries', () => {
  const c = setup();
  const container = { innerHTML: '' };
  c.document = { getElementById: () => container };
  const actual = source.match(/^      function renderUpcomingQueue\([^]*?^      }/m)[0];
  vm.runInContext(actual, c);
  c.renderUpcomingQueue();
  assert.match(container.innerHTML, /Next up/);
  assert.match(container.innerHTML, /Listening list/);
  assert.match(container.innerHTML, />b —/);
});

test('Select all results reports the complete filtered selection', () => {
  const c = setup();
  c.selectedTrackIds = new Set(['a']);
  c.filteredTracks = [c.libraryPlayable[1], c.libraryPlayable[2]];
  c.updateTrackButtons = () => {};
  const actual = source.match(/^      function selectVisibleTracks\([^]*?^      }/m)[0];
  vm.runInContext(actual, c);
  c.selectVisibleTracks();
  assert.equal(c.selectedTrackIds.size, 3);
  assert.match(c.messages[0], /all 2 matching results/);
  assert.match(c.messages[0], /3 songs selected in total/);
});

test('opening an album changes browsing without touching the current listening list', () => {
  const c = setup();
  c.libraryPlayable[0].album = 'Album A';
  c.libraryPlayable[1].album = c.libraryPlayable[2].album = 'Album B';
  const element = () => ({ textContent: '', innerHTML: '', classList: { add() {}, remove() {} } });
  for (const key of ['viewTitle', 'viewSubtitle', 'deletePlaylistButton', 'coverUploadButton', 'exportPlaylistButton', 'homeContent', 'detailContent']) c[key] = element();
  for (const key of ['rememberBrowse', 'showTrackGrid', 'setActiveNavigation', 'updateTrackButtons', 'applyFilter']) c[key] = () => {};
  const actual = source.match(/^      function showAlbum\([^]*?^      }/m)[0];
  vm.runInContext(actual, c);
  c.showAlbum('Album B');
  assert.deepEqual(Array.from(c.browsePlayable, (track) => track.id), ['b', 'c']);
  assert.equal(c.listeningQueue.currentId, 'a');
  c.nextTrack();
  assert.equal(c.currentTrackId, 'b');
});
