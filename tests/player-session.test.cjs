const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const read = (name) => fs.readFileSync(path.join(__dirname, '../static', name), 'utf8');

function setup() {
  const saved = new Map();
  const listeners = new Map();
  const audio = {
    currentTime: 0, duration: 100, paused: true, src: '', volume: 1,
    load() { this.currentTime = 0; this.paused = true; },
    addEventListener(name, fn) { listeners.set(name, fn); },
    removeEventListener(name, fn) { if (listeners.get(name) === fn) listeners.delete(name); },
    emit(name) { listeners.get(name)?.(); },
  };
  const tracks = ['a', 'b', 'c'].map((id) => ({ id, url: `/music/${id}.mp3` }));
  const c = vm.createContext({
    appState: { username: 'alice' }, libraryPlayable: tracks, activePlayable: [],
    manualQueue: [], currentTrackId: null, currentTrack: null, currentIndex: -1,
    audio, audioA: audio, audioB: { volume: 1 }, vol: { value: '1' },
    shuffleEnabled: false, repeatMode: 'off', clearResumeSeek: null,
    pendingResumePosition: 0, playbackRequestId: 0, playbackErrorShown: false,
    playRecorded: false, lastPositionSync: 0, failedCrossfadeTrackId: null,
    localStorage: { getItem: (key) => saved.get(key) ?? null, setItem: (key, value) => saved.set(key, value) },
    plays: 0, requestPlayback() { c.plays++; },
    updatePlaybackButtons() {}, renderQueue() {}, syncTrackPosition() {},
    cancelCrossfadeIfActive() {}, setPlayPauseIcon() {}, setNowPlaying() {}, syncProgress() {},
    clampVolume: (value) => Math.min(1, Math.max(0, value)),
  });
  vm.runInContext(read('listening-queue.js'), c);
  vm.runInContext('var listeningQueue = new ListeningQueue(() => 0.25);', c);
  vm.runInContext(read('player-session.js'), c);
  const actual = read('app.js').match(/^      function playSelectedTrack\([^]*?^      }/m)[0];
  vm.runInContext(actual, c);
  return { c, saved };
}

function example(c) {
  c.listeningQueue.start(['a', 'b', 'c'], 'b', true);
  return { version: 1, listening: c.listeningQueue.snapshot(), manual: ['c', 'c', 'deleted'],
    position: 43, volume: 0.4, shuffle: true, repeat: 'all' };
}

test('refresh restores the selected song, order, manual duplicates, volume and modes without autoplay', () => {
  const { c, saved } = setup();
  const original = example(c);
  saved.set(c.playerStorageKey(), JSON.stringify(original));
  c.restorePlayerState();
  assert.equal(c.currentTrackId, 'b');
  assert.equal(c.audio.paused, true);
  assert.equal(c.plays, 0);
  assert.equal(c.audio.volume, 0.4);
  assert.equal(c.audioB.volume, 0.4);
  assert.equal(c.shuffleEnabled, true);
  assert.equal(c.repeatMode, 'all');
  assert.deepEqual(Array.from(c.manualQueue, (track) => track.id), ['c', 'c']);
  assert.deepEqual(Array.from(c.listeningQueue.order), Array.from(original.listening.order));
  c.audio.emit('loadedmetadata');
  assert.equal(c.audio.currentTime, 43);
  assert.equal(c.audio.paused, true);
});

test('saved sessions are isolated by account', () => {
  const { c, saved } = setup();
  saved.set(c.playerStorageKey(), JSON.stringify(example(c)));
  c.appState.username = 'bob';
  c.restorePlayerState();
  assert.equal(c.currentTrackId, null);
  assert.equal(c.plays, 0);
});

test('saving during metadata loading keeps the requested resume time', () => {
  const { c, saved } = setup();
  c.listeningQueue.start(['a', 'b'], 'a');
  c.pendingResumePosition = 65;
  c.clearResumeSeek = () => {};
  c.persistPlayerState();
  assert.equal(JSON.parse(saved.get(c.playerStorageKey())).position, 65);
});

test('blocked storage and corrupt snapshots leave the player usable', () => {
  const { c, saved } = setup();
  saved.set(c.playerStorageKey(), 'broken JSON');
  assert.doesNotThrow(() => c.restorePlayerState());
  c.localStorage.getItem = () => { throw new Error('Storage blocked'); };
  c.localStorage.setItem = () => { throw new Error('Storage blocked'); };
  assert.doesNotThrow(() => c.restorePlayerState());
  assert.doesNotThrow(() => c.persistPlayerState());
  assert.equal(c.getCrossfadePreference(), true);
  assert.equal(c.plays, 0);
});

test('a deleted selected song is not loaded or played after refresh', () => {
  const { c, saved } = setup();
  const original = example(c);
  c.libraryPlayable = c.libraryPlayable.filter((track) => track.id !== 'b');
  saved.set(c.playerStorageKey(), JSON.stringify(original));
  c.restorePlayerState();
  assert.equal(c.currentTrackId, null);
  assert.equal(c.plays, 0);
  assert.equal(c.activePlayable.some((track) => track.id === 'b'), false);
});
