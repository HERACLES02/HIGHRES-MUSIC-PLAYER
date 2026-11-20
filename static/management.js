// Library maintenance and metadata review. These functions use the shared
// player state declared in app.js; they run only after that script is loaded.
function rememberBrowse() {
  if (restoringBrowse) return;
  browseHistory.push({ view: currentView, playlist: currentPlaylistId, folder: currentFolder,
    title: viewTitle.textContent, subtitle: viewSubtitle.textContent,
    tracks: browsePlayable.map((track) => track.id), query: search.value,
    sort: browseSort.value, format: browseFormat.value, status: browseStatus.value,
    scroll: window.scrollY });
  if (browseHistory.length > 20) browseHistory.shift();
  document.getElementById("browseBack").disabled = false;
  window.scrollTo(0, 0);
}

function restoreBrowse() {
  const saved = browseHistory.pop();
  if (!saved) return;
  restoringBrowse = true;
  search.value = saved.query;
  browseSort.value = saved.sort;
  browseFormat.value = saved.format;
  browseStatus.value = saved.status;
  try {
    if (saved.view === "folder") showFolder(saved.folder);
    else if (saved.view === "playlist") showPlaylist(saved.playlist);
    else if (saved.view === "liked") showLiked();
    else if (saved.view === "home") showHome();
    else if (saved.view === "artist") showArtist(saved.title);
    else if (saved.view === "album") showAlbum(saved.title);
    else if (saved.view === "artists") showAllArtists();
    else if (saved.view === "albums") showAllAlbums();
    else if (["smart", "smart_continue"].includes(saved.view)) {
      const byId = new Map(libraryPlayable.map((track) => [track.id, track]));
      showSmartTracks(saved.title, saved.subtitle, saved.tracks.map((id) => byId.get(id)).filter(Boolean));
      currentView = saved.view;
    } else showLibrary();
  } finally {
    restoringBrowse = false;
  }
  document.getElementById("browseBack").disabled = browseHistory.length === 0;
  requestAnimationFrame(() => { window.scrollTo(0, saved.scroll); scheduleVirtualRender(); });
}

function invalidateMetadataPreview() {
  metadataPreview = null;
  const review = document.getElementById("metadataReview");
  if (review) review.textContent = "Preview changes before applying them to your music files.";
  const button = document.getElementById("metadataApply");
  if (button) button.disabled = true;
}

async function managementRequest(url, payload) {
  const response = await apiFetch(url, payload === undefined ? {} : {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload),
  });
  const body = await response.json();
  if (!response.ok) throw new Error(body.error || "The request failed. Please try again.");
  return body;
}

async function previewMetadata(payload) {
  if (metadataBusy) return;
  metadataBusy = true;
  invalidateMetadataPreview();
  metadataError.classList.add("hidden");
  // Editing the form during a request invalidates the pending response too.
  const formValues = [metadataTitle.value, metadataArtist.value, metadataAlbum.value].join("\u0000");
  try {
    const data = await managementRequest("/api/tracks/metadata/batch", { ...payload, preview: true });
    if (formValues !== [metadataTitle.value, metadataArtist.value, metadataAlbum.value].join("\u0000")) return;
    metadataPreview = { ...payload, preview_token: data.preview_token };
    document.getElementById("metadataReview").innerHTML = data.rows.map((row) => {
      if (row.error) return `<p class="rounded-lg border border-red-900 p-3">${escapeHtml(row.path || row.id)}: ${escapeHtml(row.error)}</p>`;
      return `<div class="rounded-lg border border-zinc-700 p-3"><p class="break-all font-semibold">${escapeHtml(row.path)}</p>
        ${["title", "artist", "album"].map((key) => `<p class="mt-2 break-words"><strong>${key}:</strong> ${escapeHtml(row.before[key])} → ${escapeHtml(row.after[key])}</p>`).join("")}
        <p class="mt-2 text-xs text-zinc-400">${row.changed ? "Will change" : "Unchanged — will be skipped"}</p></div>`;
    }).join("");
    document.getElementById("metadataApply").disabled = !data.rows.some((row) => !row.error && row.changed);
  } catch (error) {
    metadataError.textContent = error.message;
    metadataError.classList.remove("hidden");
  } finally {
    metadataBusy = false;
  }
}

function metadataResultsHtml(data) {
  return (data.results || []).map((row) => `<p class="mb-2 break-all rounded-lg border border-zinc-700 p-3"><strong>${escapeHtml(row.status)}</strong>: ${escapeHtml(row.path)}${row.error ? ` — ${escapeHtml(row.error)}` : ""}</p>`).join("");
}

async function confirmMetadata() {
  if (!metadataPreview || metadataBusy) return;
  metadataBusy = true;
  const payload = metadataPreview;
  metadataPreview = null;
  document.getElementById("metadataApply").disabled = true;
  metadataError.classList.add("hidden");
  try {
    const data = await managementRequest("/api/tracks/metadata/batch", payload);
    document.getElementById("metadataReview").innerHTML = `<p class="font-semibold">${data.updated} songs updated. Undo is available in Library health.</p>${metadataResultsHtml(data)}`;
    await reloadLibraryData();
  } catch (error) {
    metadataError.textContent = error.message;
    metadataError.classList.remove("hidden");
  } finally {
    metadataBusy = false;
  }
}

async function reloadLibraryData() {
  const data = await fetchJson("/api/tracks");
  const browseIds = browsePlayable.map((track) => track.id);
  const activeIds = activePlayable.map((track) => track.id);
  libraryPlayable.splice(0, libraryPlayable.length, ...data.map(normalizeTrack));
  const byId = new Map(libraryPlayable.map((track) => [track.id, track]));
  browsePlayable = currentView === "library" || currentView === "home"
    ? libraryPlayable : browseIds.map((id) => byId.get(id)).filter(Boolean);
  activePlayable = activeIds.map((id) => byId.get(id)).filter(Boolean);
  manualQueue = manualQueue.map((track) => byId.get(track.id)).filter(Boolean);
  listeningQueue.restore(listeningQueue.snapshot(), new Set(byId.keys()));
  currentIndex = activePlayable.findIndex((track) => track.id === currentTrackId);
  if (byId.has(currentTrackId)) {
    currentTrack = byId.get(currentTrackId);
    setNowPlaying(currentTrack);
  }
  if (libraryCount) libraryCount.textContent = `(${libraryPlayable.length})`;
  await refreshState();
}

async function showMaintenance() {
  currentView = "maintenance";
  viewTitle.textContent = "Library health";
  viewSubtitle.textContent = "Scan progress, missing songs, possible duplicates, and metadata undo";
  homeContent.classList.add("hidden");
  hideTrackGrid();
  emptyState?.classList.add("hidden");
  [deletePlaylistButton, coverUploadButton, exportPlaylistButton].forEach((button) => button.classList.add("hidden"));
  detailContent.classList.remove("hidden");
  detailContent.innerHTML = '<p role="status">Loading library health…</p>';
  await renderMaintenance();
}

async function renderMaintenance() {
  clearTimeout(scanPollTimer);
  try {
    const report = await fetchJson("/api/library-health");
    const jobs = await fetchJson("/api/metadata/history").catch(() => null);
    if (currentView !== "maintenance") return;
    const scan = report.scan;
    if (!scan.running && scan.finished_at && scan.finished_at !== lastLibraryScan) {
      await reloadLibraryData();
      lastLibraryScan = scan.finished_at;
    }
    if (currentView !== "maintenance") return;
    detailContent.innerHTML = `
      <section class="mb-6"><h3 class="text-xl font-semibold">Library scan</h3>
        <p class="mt-2" role="status">${scan.running ? `Scanning: ${scan.scanned} of ${scan.total} files (${escapeHtml(scan.phase)})` : `Last scan: ${scan.scanned} of ${scan.total} files checked.`}</p>
        ${scan.error ? `<p class="text-red-300">${escapeHtml(scan.error)}</p>` : ""}
        <button type="button" data-action="refresh-library" ${scan.running ? "disabled" : ""} class="mt-3 rounded-lg border border-zinc-700 p-2 disabled:opacity-30">Rescan library</button>
        <details class="mt-3"><summary>${scan.issues.length} file/index warnings</summary>${scan.issues.slice(0, 200).map((row) => `<p class="mt-2 break-all text-sm">${escapeHtml(row.path)} — ${escapeHtml(row.error)}</p>`).join("")}${scan.issues.length > 200 ? "<p>Showing the first 200 warnings.</p>" : ""}</details>
      </section>
      <section class="mb-6"><h3 class="text-xl font-semibold">Missing songs (${report.missing.length})</h3>
        <p class="mt-2 text-sm text-zinc-400">These songs are still in your likes, playlists, or history but were absent from the last scan. Reconnecting changes only your account's references. Suggestions use tags and file size; check the paths before choosing.</p>
        ${report.missing.slice(0, 100).map((row) => `<div class="mt-3 rounded-lg border border-zinc-700 p-3"><p class="break-all">${escapeHtml(row.title)} — ${escapeHtml(row.path)}</p>${row.candidates.slice(0, 10).map((candidate) => `<button type="button" data-action="relink-track" data-old-id="${escapeHtml(row.id)}" data-new-id="${escapeHtml(candidate.id)}" class="mt-2 block max-w-full break-all rounded-lg border border-zinc-700 p-2 text-left text-sm">Reconnect to ${escapeHtml(candidate.path)}</button>`).join("")}</div>`).join("")}
        ${report.missing.length ? `<div class="mt-3 space-y-2"><p class="text-sm">Or select a replacement yourself:</p><label class="block text-sm">Missing song<select id="relinkOld" class="block w-full rounded-lg border border-zinc-700 bg-zinc-950 p-2">${report.missing.map((row) => `<option value="${escapeHtml(row.id)}">${escapeHtml(row.title)} — ${escapeHtml(row.path)}</option>`).join("")}</select></label><label class="block text-sm">Replacement<select id="relinkNew" class="block w-full rounded-lg border border-zinc-700 bg-zinc-950 p-2">${libraryPlayable.map((track) => `<option value="${escapeHtml(track.id)}">${escapeHtml(track.title)} — ${escapeHtml(track.relpath)}</option>`).join("")}</select></label><button type="button" data-action="relink-selected" class="rounded-lg border border-zinc-700 p-2">Reconnect selected songs</button></div>` : ""}
      </section>
      <section class="mb-6"><h3 class="text-xl font-semibold">Possible duplicates (${report.duplicates.length} groups)</h3>
        <p class="mt-2 text-sm text-zinc-400">These files share title, artist, album, and file size. They may be different versions; no files are deleted.</p>
        ${report.duplicates.slice(0, 100).map((group) => `<ul class="mt-3 rounded-lg border border-zinc-700 p-3">${group.map((row) => `<li class="break-all">${escapeHtml(row.path)}</li>`).join("")}</ul>`).join("")}
      </section>
      <section><h3 class="text-xl font-semibold">Metadata edit history</h3>
        <p class="mt-2 text-sm text-zinc-400">Undo restores the original title, artist, and album tags. Files with newer changes are skipped. Your latest 20 edit sessions are listed.</p>
        ${jobs === null ? "<p>History could not be loaded. Check the saved metadata history before editing.</p>" : jobs.map((job) => `<div class="mt-3 flex flex-wrap items-center gap-3 rounded-lg border border-zinc-700 p-3"><span>${escapeHtml(new Date(job.created_at * 1000).toLocaleString())} — ${job.count} files available to undo</span><button type="button" data-action="undo-metadata" data-job-id="${escapeHtml(job.id)}" ${job.count ? "" : "disabled"} class="rounded-lg border border-zinc-700 p-2 disabled:opacity-30">Undo metadata edit</button></div>`).join("")}
        <div id="maintenanceResults" class="mt-3" aria-live="polite"></div>
      </section>`;
    if (scan.running) {
      scanPollTimer = setTimeout(async () => {
        try {
          if (currentView === "maintenance") await renderMaintenance();
        } catch (error) { showToast("Could not check scan progress. Open Library health to retry.", "error"); }
      }, 700);
    }
  } catch (error) {
    if (currentView === "maintenance") detailContent.textContent = "Could not load library health. Use Library health to retry.";
  }
}

async function relinkMissingTrack(oldId, newId) {
  const replacement = libraryPlayable.find((track) => track.id === newId);
  if (!replacement || !window.confirm(`Reconnect this missing song to ${replacement.relpath}? This updates your likes, playlists, and history.`)) return;
  try {
    await managementRequest("/api/library/relink", { old_id: oldId, new_id: newId });
    await refreshState();
    await renderMaintenance();
    showToast("Missing song reconnected");
  } catch (error) { showToast(error.message, "error"); }
}

async function undoMetadata(jobId) {
  if (!window.confirm("Restore the original title, artist, and album tags for this edit? Files with newer changes will be skipped.")) return;
  try {
    const data = await managementRequest(`/api/metadata/history/${encodeURIComponent(jobId)}/undo`, {});
    await reloadLibraryData();
    await renderMaintenance();
    const results = document.getElementById("maintenanceResults");
    if (results) results.innerHTML = metadataResultsHtml(data);
  } catch (error) { showToast(error.message, "error"); }
}
