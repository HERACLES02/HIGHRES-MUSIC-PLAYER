const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const context = vm.createContext({});
vm.runInContext(fs.readFileSync(path.join(__dirname, '../static/listening-queue.js'), 'utf8'), context);
const Queue = vm.runInContext('ListeningQueue', context);
const ids = ['a', 'b', 'c', 'd'];
const array = (value) => Array.from(value);
function queue(selected = 'a', shuffle = false) {
  const q = new Queue(() => 0.25);
  q.start(ids, selected, shuffle);
  return q;
}

test('a listening list preserves the supplied display order', () => {
  const q = new Queue();
  q.start(['d', 'b', 'a'], 'd');
  q.commit(q.peek());
  assert.equal(q.currentId, 'b');
  q.commit(q.peek());
  assert.equal(q.currentId, 'a');
  assert.equal(q.peek(), null);
});

test('manual songs do not steal the album position', () => {
  const q = queue();
  q.commit(q.peek(['outside']));
  assert.equal(q.currentId, 'outside');
  assert.equal(q.peek().id, 'b');
  assert.deepEqual(array(q.base), ids);
});

test('shuffle plays each song once before stopping with Repeat off', () => {
  const q = queue('b', true);
  const played = [q.currentId];
  while (q.peek()) { q.commit(q.peek()); played.push(q.currentId); }
  assert.equal(played.length, ids.length);
  assert.deepEqual([...new Set(played)].sort(), ids);
});

test('Previous and Next retrace actual shuffled listening history', () => {
  const q = queue('b', true);
  q.commit(q.peek());
  const second = q.currentId;
  q.commit(q.peek());
  const third = q.currentId;
  assert.equal(q.previous(), second);
  assert.equal(q.previous(), 'b');
  q.commit(q.peek());
  assert.equal(q.currentId, second);
  q.commit(q.peek());
  assert.equal(q.currentId, third);
});

test('Previous includes manual songs and leaves unconsumed manual entries alone', () => {
  const q = queue();
  q.commit(q.peek(['outside']));
  q.commit(q.peek());
  assert.equal(q.previous(), 'outside');
  assert.equal(q.peek(['waiting']).id, 'b');
  q.commit(q.peek(['waiting']));
  assert.equal(q.peek(['waiting']).id, 'waiting');
});

test('enabling shuffle includes songs before the first selected song that were never heard', () => {
  const q = queue('c');
  q.setShuffle(true);
  const remaining = [];
  while (q.peek()) { q.commit(q.peek()); remaining.push(q.currentId); }
  assert.deepEqual(remaining.sort(), ['a', 'b', 'd']);
});

test('Next up and crossfade previews agree and do not consume entries', () => {
  const q = queue('b', true);
  const cursor = q.cursor;
  assert.equal(q.upcoming(['outside'])[0].id, q.peek(['outside']).id);
  assert.equal(q.peek(['outside']).id, 'outside');
  assert.equal(q.cursor, cursor);
  assert.equal(q.history.length, 1);
});

test('Repeat all creates a new shuffle cycle without immediate boundary repetition', () => {
  const q = queue('b', true);
  while (q.peek()) q.commit(q.peek());
  const last = q.currentId;
  const candidate = q.peek([], 'all');
  assert.notEqual(candidate.id, last);
  assert.equal(q.upcoming([], 'all')[0].id, candidate.id);
  q.commit(candidate);
  const played = [q.currentId];
  while (q.peek()) { q.commit(q.peek()); played.push(q.currentId); }
  assert.deepEqual(played.sort(), ids);
});

test('a manually queued shuffle song is not repeated in the unheard suffix', () => {
  const q = queue('a', true);
  q.commit(q.peek(['c']));
  const rest = [];
  while (q.peek()) { q.commit(q.peek()); rest.push(q.currentId); }
  assert.equal(rest.includes('c'), false);
  assert.deepEqual(rest.sort(), ['b', 'd']);
});

test('restoration preserves order and history while removing deleted songs', () => {
  const q = queue('a', true);
  q.commit(q.peek());
  const saved = JSON.parse(JSON.stringify(q.snapshot()));
  const restored = new Queue();
  assert.ok(restored.restore(saved, new Set(ids)));
  assert.deepEqual(array(restored.order), array(q.order));
  assert.equal(restored.peek().id, q.peek().id);
  assert.equal(restored.previous(), 'a');
  const missing = new Queue();
  missing.restore(saved, new Set(ids.filter((id) => id !== q.currentId)));
  assert.equal(missing.order.includes(q.currentId), false);
});

test('Previous can reach the preceding album song without losing the forward frontier', () => {
  const q = queue('c');
  assert.equal(q.previous(), 'b');
  assert.equal(q.previous(), 'a');
  q.commit(q.peek());
  assert.equal(q.currentId, 'b');
  q.commit(q.peek());
  assert.equal(q.currentId, 'c');
  q.commit(q.peek());
  assert.equal(q.currentId, 'd');
});

test('a repeat-cycle preview made early still avoids repetition at the actual boundary', () => {
  const q = queue('b', true);
  q.upcoming([], 'all');
  while (q.peek()) q.commit(q.peek());
  assert.notEqual(q.peek([], 'all').id, q.currentId);
});

test('explicit Play next takes priority after using Previous', () => {
  const q = queue();
  q.commit(q.peek());
  q.commit(q.peek());
  q.previous();
  q.prioritizeManual();
  assert.equal(q.peek(['chosen']).id, 'chosen');
});
