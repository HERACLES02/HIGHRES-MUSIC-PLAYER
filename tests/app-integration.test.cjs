// Run the complete script set together against a small DOM/audio simulator.
// This checks script wiring and state integration; it is not visual browser QA.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.join(__dirname, '..');
const settle = () => new Promise((resolve) => setImmediate(resolve));

async function boot(storage = new Map()) {
  const elements = new Map();
  function element() {
    const listeners = new Map();
    const classes = new Set();
    let html = '';
    return {
      value: '', textContent: '', dataset: {}, children: [], clientWidth: 800,
      style: { setProperty(key, value) { this[key] = value; } },
      classList: {
        add(...names) { names.forEach((name) => classes.add(name)); },
        remove(...names) { names.forEach((name) => classes.delete(name)); },
        contains(name) { return classes.has(name); },
        toggle(name, force) { const enabled = force ?? !classes.has(name); enabled ? classes.add(name) : classes.delete(name); },
      },
      get innerHTML() { return html; },
      set innerHTML(value) { html = value; register(value); },
      setAttribute() {}, removeAttribute() {}, remove() {},
      appendChild(child) { this.children.push(child); },
      querySelectorAll() { return []; }, querySelector() { return null; },
      getBoundingClientRect() { return { top: 0, height: 168 }; },
      addEventListener(name, fn) {
        if (!listeners.has(name)) listeners.set(name, new Set());
        listeners.get(name).add(fn);
      },
      removeEventListener(name, fn) { listeners.get(name)?.delete(fn); },
      emit(name) { for (const fn of [...(listeners.get(name) || [])]) fn({ target: this }); },
      src: '', currentTime: 0, duration: 100, volume: 1, paused: true, playCalls: 0,
      play() { this.playCalls++; this.paused = false; return Promise.resolve(); },
      pause() { this.paused = true; },
      load() { this.paused = true; this.currentTime = 0; },
    };
  }
  function register(html) {
    for (const match of html.matchAll(/<[^>]*\bid="([^"]+)"[^>]*>/g)) {
      if (elements.has(match[1])) continue;
      const node = element();
      if (/\bclass="[^"]*\bhidden\b/.test(match[0])) node.classList.add('hidden');
      elements.set(match[1], node);
    }
  }
  register(fs.readFileSync(path.join(root, 'templates/index.html'), 'utf8'));
  const tracks = [
    { id: 'a', title: 'Third', artist: 'Artist A', album: 'Album A', relpath: 'a.flac', folder: '' },
    { id: 'b', title: 'First', artist: 'Artist A', album: 'Album A', relpath: 'b.flac', folder: '' },
    { id: 'c', title: 'Second', artist: 'Artist B', album: 'Album B', relpath: 'c.mp3', folder: '' },
  ];
  elements.get('tracks-data').textContent = JSON.stringify(tracks);
  elements.get('browseSort').value = 'default';
  elements.get('vol').value = '1';
  const state = { username: 'alice', likes: [], playlists: [{ id: 'default', name: 'My Playlist', tracks: [], count: 0 }], history: { tracks: {}, recent: [], most_played: [] } };
  const errors = [];
  const c = vm.createContext({
    document: {
      getElementById: (id) => elements.get(id) || null,
      createElement: element,
      querySelector: () => null, querySelectorAll: () => [],
      addEventListener() {}, documentElement: { scrollTop: 0 },
    },
    Audio: function () { return element(); },
    localStorage: { getItem: (key) => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, value) },
    navigator: {}, Headers, URLSearchParams,
    console: { warn: (...args) => errors.push(args) },
    performance: { now: () => 0 }, CSS: { escape: String },
    setTimeout: () => 1, clearTimeout() {},
    requestAnimationFrame(fn) { fn(0); return 1; }, cancelAnimationFrame() {},
    getComputedStyle: () => ({ columnGap: '0', rowGap: '0' }),
    innerHeight: 800, scrollY: 0, addEventListener() {}, scrollTo() {},
    fetch: async (url) => {
      let body = {};
      if (url === '/api/library-state') body = state;
      else if (url === '/api/folders') body = [];
      else if (url === '/api/tracks') body = tracks;
      else if (url.startsWith('/api/artist-info')) body = { info: {} };
      else if (url.includes('/lyrics')) body = { lyrics: '' };
      else if (url.startsWith('/api/history/play')) body = { history: { play_count: 1, last_played: 'today' } };
      else if (url === '/api/settings') body = { music_dir: 'test', playlist_file: 'test', artwork_dir: 'test', track_count: 3, folder_count: 1, playlist_count: 1, user_count: 1 };
      return { ok: true, json: async () => body };
    },
  });
  c.window = c;
  for (const name of ['library-tools.js', 'management.js', 'listening-queue.js', 'player-session.js', 'app.js']) {
    vm.runInContext(fs.readFileSync(path.join(root, 'static', name), 'utf8'), c, { filename: name });
  }
  await settle();
  return { c, elements, storage, errors, run: (code) => vm.runInContext(code, c) };
}

test('complete scripts keep playback tied to displayed results across album and Settings browsing', async () => {
  const app = await boot();
  assert.equal(app.run('currentView'), 'home');
  app.run("showLibrary(); browseSort.value = 'title'; browseFormat.value = 'flac'; applyFilter(); playTrackId('b');");
  await settle();
  assert.deepEqual(Array.from(app.run('listeningQueue.base')), ['b', 'a']);
  app.run("showAlbum('Album B');");
  await app.run('showSettings()');
  app.run('nextTrack()');
  await settle();
  assert.equal(app.run('currentTrackId'), 'a');
  assert.match(app.elements.get('queueUpcoming').innerHTML, /Next up/);
  assert.equal(app.errors.length, 0);
});

test('complete scripts restore player state after refresh and wait for Play', async () => {
  const first = await boot();
  first.run("showLibrary(); playTrackId('a'); addToQueue('c'); cycleRepeat(); toggleShuffle();");
  await settle();
  first.run("audio.currentTime = 43; vol.value = '0.4'; audio.volume = 0.4; audio.pause(); syncTrackPosition(true); persistPlayerState();");
  const second = await boot(first.storage);
  assert.equal(second.run('currentTrackId'), 'a');
  assert.equal(second.run('audio.paused'), true);
  assert.equal(second.run('audio.playCalls'), 0);
  assert.equal(second.run('repeatMode'), 'all');
  assert.equal(second.run('shuffleEnabled'), true);
  assert.equal(second.run('audio.volume'), 0.4);
  assert.deepEqual(Array.from(second.run('manualQueue.map(track => track.id)')), ['c']);
  second.elements.get('audio').emit('loadedmetadata');
  assert.equal(second.run('audio.currentTime'), 43);
  assert.equal(second.run('audio.paused'), true);
  second.run('togglePlayPause()');
  await settle();
  assert.equal(second.run('audio.paused'), false);
  assert.equal(second.run('audio.currentTime'), 43);
  assert.equal(second.errors.length, 0);
});
