// Browser-local, account-specific player state. Restoration never starts audio.
function playerStorageKey() {
  return appState.username ? `music-player-session-v1:${appState.username}` : null;
}

function getCrossfadePreference() {
  try { return localStorage.getItem('crossfadeEnabled') !== '0'; }
  catch { return true; }
}

function persistPlayerState() {
  const key = playerStorageKey();
  if (!key) return;
  try {
    localStorage.setItem(key, JSON.stringify({
      version: 1, listening: listeningQueue.snapshot(),
      manual: manualQueue.map((track) => track.id),
      position: clearResumeSeek ? pendingResumePosition : (Number.isFinite(audio.currentTime) ? audio.currentTime : 0),
      volume: clampVolume(Number(vol.value)), shuffle: shuffleEnabled, repeat: repeatMode,
    }));
  } catch {
    // Private browsing, storage limits, or a blocked storage API should not
    // stop music. This session simply won't survive a reload.
  }
}

function restorePlayerState() {
  const key = playerStorageKey();
  if (!key) return;
  try {
    const saved = JSON.parse(localStorage.getItem(key));
    if (!saved || saved.version !== 1) return;
    const byId = new Map(libraryPlayable.map((track) => [track.id, track]));
    const restored = listeningQueue.restore(saved.listening, new Set(byId.keys()));
    activePlayable = listeningQueue.base.map((id) => byId.get(id)).filter(Boolean);
    manualQueue = (Array.isArray(saved.manual) ? saved.manual : []).slice(0, 10000).map((id) => byId.get(id)).filter(Boolean);
    shuffleEnabled = Boolean(saved.shuffle);
    listeningQueue.setShuffle(shuffleEnabled);
    repeatMode = ['off', 'all', 'one'].includes(saved.repeat) ? saved.repeat : 'off';
    const volume = Number.isFinite(saved.volume) ? clampVolume(saved.volume) : 1;
    vol.value = String(volume);
    audioA.volume = audioB.volume = volume;
    updatePlaybackButtons();
    if (restored) {
      const position = Number.isFinite(saved.position) && saved.position > 0 ? saved.position : 0;
      currentIndex = activePlayable.findIndex((track) => track.id === listeningQueue.currentId);
      playSelectedTrack(byId.get(listeningQueue.currentId), position, false);
    }
    renderQueue();
  } catch {
    // Ignore corrupt/older snapshots and start with the normal empty player.
  }
}
