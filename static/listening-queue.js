// Listening state is independent of the page being browsed. IDs keep saved
// sessions small and let the UI resolve current metadata from the library.
class ListeningQueue {
  constructor(random = Math.random) {
    this.random = random;
    this.base = [];
    this.order = [];
    this.cursor = -1;
    this.currentId = null;
    this.history = [];
    this.historyCursor = -1;
    this.shuffle = false;
    this.nextCycle = null;
    this.heard = new Set();
  }

  shuffled(ids) {
    const result = [...ids];
    for (let i = result.length - 1; i > 0; i--) {
      const j = Math.floor(this.random() * (i + 1));
      [result[i], result[j]] = [result[j], result[i]];
    }
    return result;
  }

  start(ids, selectedId, shuffle = false) {
    this.base = [...new Set(ids)];
    if (!this.base.includes(selectedId)) return false;
    this.shuffle = shuffle;
    this.order = shuffle ? [selectedId, ...this.shuffled(this.base.filter((id) => id !== selectedId))] : [...this.base];
    this.cursor = this.order.indexOf(selectedId);
    this.currentId = selectedId;
    this.history = [selectedId];
    this.historyCursor = 0;
    this.nextCycle = null;
    this.heard = new Set([selectedId]);
    return true;
  }

  setShuffle(enabled) {
    if (enabled === this.shuffle) return;
    this.shuffle = enabled;
    // Keep the already-played prefix and randomize only the unheard suffix.
    if (enabled) {
      const frontier = this.order[this.cursor];
      const prefix = this.base.filter((id) => this.heard.has(id) && id !== frontier);
      if (this.base.includes(frontier)) prefix.push(frontier);
      this.order = [...prefix, ...this.shuffled(this.base.filter((id) => !this.heard.has(id)))];
      this.cursor = prefix.length - 1;
    } else {
      const frontier = this.order[this.cursor];
      this.order = [...this.base];
      this.cursor = this.order.indexOf(frontier);
    }
    this.nextCycle = null;
  }

  peek(manualIds = [], repeat = 'off') {
    if (this.historyCursor < this.history.length - 1) {
      return { id: this.history[this.historyCursor + 1], historyIndex: this.historyCursor + 1 };
    }
    if (manualIds.length) return { id: manualIds[0], fromQueue: true };
    if (this.cursor + 1 < this.order.length) return { id: this.order[this.cursor + 1], index: this.cursor + 1 };
    if (repeat !== 'all' || !this.order.length) return null;
    if (!this.nextCycle) {
      this.nextCycle = this.shuffle ? this.shuffled(this.base) : [...this.base];
    }
    if (this.shuffle && this.nextCycle.length > 1 && this.nextCycle[0] === this.currentId) this.nextCycle.push(this.nextCycle.shift());
    return { id: this.nextCycle[0], index: 0, newCycle: [...this.nextCycle] };
  }

  commit(candidate) {
    if (Number.isInteger(candidate.historyIndex)) {
      this.historyCursor = candidate.historyIndex;
      this.currentId = this.history[this.historyCursor];
      return;
    }
    if (candidate.newCycle) {
      this.order = [...candidate.newCycle];
      this.nextCycle = null;
      this.heard = new Set();
    }
    if (Number.isInteger(candidate.index)) this.cursor = candidate.index;
    // A manually queued song must not move the album/playlist cursor. If it
    // belongs to the unheard shuffle suffix, consume it there as well.
    if (this.shuffle && !Number.isInteger(candidate.index)) {
      const upcoming = this.order.indexOf(candidate.id, this.cursor + 1);
      if (upcoming !== -1) this.order.splice(upcoming, 1);
    }
    this.currentId = candidate.id;
    this.heard.add(candidate.id);
    this.history = this.history.slice(0, this.historyCursor + 1);
    this.history.push(candidate.id);
    if (this.history.length > 200) this.history.shift();
    this.historyCursor = this.history.length - 1;
  }

  previous(repeat = 'off') {
    if (this.historyCursor > 0) {
      this.currentId = this.history[--this.historyCursor];
      return this.currentId;
    }
    // Before the first explicitly selected album song, Previous can still
    // reach its preceding sibling. The forward frontier stays untouched.
    const index = this.order.indexOf(this.currentId);
    if (!this.shuffle && index > 0) {
      this.currentId = this.order[index - 1];
      this.history.unshift(this.currentId);
      return this.currentId;
    }
    if (!this.shuffle && index === 0 && repeat === 'all' && this.order.length > 1) {
      this.currentId = this.order[this.order.length - 1];
      this.history.unshift(this.currentId);
    }
    return this.currentId;
  }

  prioritizeManual() {
    // Explicit Play next takes priority over retracing a forward history.
    this.history = this.history.slice(0, this.historyCursor + 1);
  }

  upcoming(manualIds = [], repeat = 'off', limit = 20) {
    const result = [];
    const forward = this.history.slice(this.historyCursor + 1);
    result.push(...forward.map((id) => ({ id, source: 'history' })));
    result.push(...manualIds.map((id) => ({ id, source: 'manual' })));
    result.push(...this.order.slice(this.cursor + 1).map((id) => ({ id, source: 'list' })));
    if (repeat === 'all' && result.length < limit) {
      if (!this.nextCycle) {
        this.nextCycle = this.shuffle ? this.shuffled(this.base) : [...this.base];
      }
      const preceding = result.length ? result[result.length - 1].id : this.currentId;
      if (this.shuffle && this.nextCycle.length > 1 && this.nextCycle[0] === preceding) this.nextCycle.push(this.nextCycle.shift());
      result.push(...this.nextCycle.map((id) => ({ id, source: 'repeat' })));
    }
    return result.slice(0, limit);
  }

  snapshot() {
    return { base: this.base, order: this.order, cursor: this.cursor,
      currentId: this.currentId, history: this.history, historyCursor: this.historyCursor,
      shuffle: this.shuffle, nextCycle: this.nextCycle, heard: [...this.heard] };
  }

  restore(saved, validIds) {
    if (!saved || !Array.isArray(saved.base) || !Array.isArray(saved.order)) return false;
    const clean = (ids) => ids.filter((id) => typeof id === 'string' && validIds.has(id)).slice(0, 100000);
    this.base = [...new Set(clean(saved.base))];
    this.order = [...new Set(clean(saved.order))];
    const oldCursor = Number.isInteger(saved.cursor) ? saved.cursor : -1;
    this.cursor = clean(saved.order.slice(0, Math.max(0, oldCursor + 1))).length - 1;
    this.currentId = validIds.has(saved.currentId) ? saved.currentId : null;
    const oldHistory = Array.isArray(saved.history) ? saved.history : [];
    const oldHistoryCursor = Number.isInteger(saved.historyCursor) ? saved.historyCursor : oldHistory.length - 1;
    const before = clean(oldHistory.slice(0, Math.max(0, oldHistoryCursor + 1)));
    const after = clean(oldHistory.slice(Math.max(0, oldHistoryCursor + 1)));
    this.history = [...before, ...after];
    this.historyCursor = before.length - 1;
    this.shuffle = Boolean(saved.shuffle);
    this.heard = new Set(clean(Array.isArray(saved.heard) ? saved.heard : this.history));
    this.nextCycle = Array.isArray(saved.nextCycle) ? clean(saved.nextCycle) : null;
    if (this.nextCycle && !this.nextCycle.length) this.nextCycle = null;
    return Boolean(this.currentId);
  }
}
