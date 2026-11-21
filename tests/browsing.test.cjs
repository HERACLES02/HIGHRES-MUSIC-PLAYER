const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const context = vm.createContext({ window: {} });
vm.runInContext(fs.readFileSync(path.join(__dirname, '../static/library-tools.js'), 'utf8'), context);
const filter = context.window.LibraryTools.filterAndSort;
const tracks = [
  { id: 'a', title: 'Song 10', artist: 'B', album: 'Z', relpath: 'B/song.flac', folder: 'Live', addedAt: 10, hasCover: true },
  { id: 'b', title: 'Song 2', artist: 'A', album: 'Y', relpath: 'A/song.mp3', folder: '', addedAt: 30, hasCover: false },
  { id: 'c', title: 'Other', artist: 'Unknown Artist', album: 'Y', relpath: 'other.mp3', folder: '', addedAt: 20, hasCover: false },
];
const ids = (items) => Array.from(items, (track) => track.id);

test('search combines with format and liked filters', () => {
  assert.deepEqual(ids(filter(tracks, { query: 'song', format: 'mp3', status: 'liked', likedIds: new Set(['b']) })), ['b']);
  assert.deepEqual(ids(filter(tracks, { query: 'live', format: 'flac' })), ['a']);
});
test('title sorting is numeric and does not mutate the library', () => {
  assert.deepEqual(ids(filter(tracks, { sort: 'title' })), ['c', 'b', 'a']);
  assert.deepEqual(ids(tracks), ['a', 'b', 'c']);
});
test('default preserves custom playlist order; added sorts by first seen', () => {
  assert.deepEqual(ids(filter([tracks[2], tracks[0]], {})), ['c', 'a']);
  assert.deepEqual(ids(filter(tracks, { sort: 'added' })), ['b', 'c', 'a']);
});
test('cover and missing-tag filters identify the intended songs', () => {
  assert.deepEqual(ids(filter(tracks, { status: 'missing-cover' })), ['b', 'c']);
  assert.deepEqual(ids(filter(tracks, { status: 'weak-tags' })), ['c']);
});
