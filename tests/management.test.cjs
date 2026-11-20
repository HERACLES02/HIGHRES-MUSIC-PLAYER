const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../static/management.js'), 'utf8');

function setup() {
  const elements = new Map();
  const element = (id) => {
    if (!elements.has(id)) elements.set(id, {
      value: '', textContent: '', innerHTML: '', disabled: false,
      classList: { add() {}, remove() {} },
    });
    return elements.get(id);
  };
  const c = vm.createContext({
    document: { getElementById: element },
    window: { scrollY: 450, scrollTo(x, y) { c.scroll = y; } },
    requestAnimationFrame(fn) { fn(); }, scheduleVirtualRender() {},
    currentView: 'library', currentPlaylistId: 'default', currentFolder: '',
    viewTitle: { textContent: 'All tracks' }, viewSubtitle: { textContent: 'Two songs' },
    browsePlayable: [{ id: 'a' }, { id: 'b' }], activePlayable: [], libraryPlayable: [],
    search: { value: 'artist' }, browseSort: { value: 'title' },
    browseFormat: { value: 'flac' }, browseStatus: { value: 'liked' },
    browseHistory: [], restoringBrowse: false,
    metadataBusy: false, metadataPreview: null,
    metadataTitle: { value: 'Title' }, metadataArtist: { value: 'Artist' },
    metadataAlbum: { value: 'Album' }, metadataError: element('error'),
    escapeHtml: (value) => String(value ?? '').replaceAll('<', '&lt;'),
    calls: [],
    showLibrary() { c.currentView = 'library'; c.calls.push('library'); },
  });
  vm.runInContext(source, c);
  return { c, element };
}

test('Back restores the previous view, query, filters, and scroll position', () => {
  const { c, element } = setup();
  c.rememberBrowse();
  c.currentView = 'album';
  c.search.value = '';
  c.browseSort.value = 'added';
  c.browseFormat.value = '';
  c.browseStatus.value = '';
  c.restoreBrowse();
  assert.equal(c.currentView, 'library');
  assert.equal(c.search.value, 'artist');
  assert.equal(c.browseSort.value, 'title');
  assert.equal(c.browseFormat.value, 'flac');
  assert.equal(c.browseStatus.value, 'liked');
  assert.equal(c.scroll, 450);
  assert.equal(element('browseBack').disabled, true);
});

test('editing form fields invalidates a preview and disables Apply', () => {
  const { c, element } = setup();
  c.metadataPreview = { preview_token: 'old' };
  c.invalidateMetadataPreview();
  assert.equal(c.metadataPreview, null);
  assert.equal(element('metadataApply').disabled, true);
});

test('a late preview response cannot approve fields changed during the request', async () => {
  const { c, element } = setup();
  let respond;
  c.apiFetch = () => new Promise((resolve) => { respond = resolve; });
  const request = c.previewMetadata({ track_ids: ['a'], album: 'Original input' });
  c.metadataAlbum.value = 'New input';
  respond({ ok: true, json: async () => ({ preview_token: 'old-preview', rows: [] }) });
  await request;
  assert.equal(c.metadataPreview, null);
  assert.equal(element('metadataApply').disabled, true);
  assert.equal(c.metadataBusy, false);
});

test('Apply without a preview does not send a write request', async () => {
  const { c } = setup();
  c.apiFetch = () => { throw new Error('Unexpected write'); };
  await c.confirmMetadata();
  assert.equal(c.metadataBusy, false);
});
