const tracks = JSON.parse(document.getElementById("tracks-data").textContent);
      const csrfToken = document.querySelector('meta[name="csrf-token"]')?.content || "";
      const libraryPlayable = tracks.map((track) => normalizeTrack(track));
      const audioA = document.getElementById("audio");
      const audioB = new Audio();
      audioB.preload = "metadata";
      let audio = audioA; // always the currently-active player; reassigned when a crossfade hands off to the other element
      const bgVideo = document.getElementById("bgVideo");
      const bg = document.getElementById("bg");
      const backgroundInput = document.getElementById("backgroundInput");
      const restoreInput = document.getElementById("restoreInput");
      const search = document.getElementById("search");
      const grid = document.getElementById("grid");
      const gridScroller = document.getElementById("gridScroller");
      const gridControls = document.getElementById("gridControls");
      const gridSummary = document.getElementById("gridSummary");
      const homeContent = document.getElementById("homeContent");
      const homeTiles = document.getElementById("homeTiles");
      const homePlaylistTiles = document.getElementById("homePlaylistTiles");
      const artistTiles = document.getElementById("artistTiles");
      const albumTiles = document.getElementById("albumTiles");
      const smartTiles = document.getElementById("smartTiles");
      const continueSection = document.getElementById("continueSection");
      const continueTiles = document.getElementById("continueTiles");
      const recentSection = document.getElementById("recentSection");
      const recentTiles = document.getElementById("recentTiles");
      const mostPlayedSection = document.getElementById("mostPlayedSection");
      const mostPlayedTiles = document.getElementById("mostPlayedTiles");
      const emptyState = document.getElementById("emptyState");
      const detailContent = document.getElementById("detailContent");
      const toastHost = document.getElementById("toastHost");
      const batchBar = document.getElementById("batchBar");
      const batchCount = document.getElementById("batchCount");
      const viewTitle = document.getElementById("viewTitle");
      const viewSubtitle = document.getElementById("viewSubtitle");
      const libraryCount = document.getElementById("libraryCount");
      const likedCount = document.getElementById("likedCount");
      const folderList = document.getElementById("folderList");
      const playlistList = document.getElementById("playlistList");
      const playlistTarget = document.getElementById("playlistTarget");
      const deletePlaylistButton = document.getElementById("deletePlaylistButton");
      const coverUploadButton = document.getElementById("coverUploadButton");
      const exportPlaylistButton = document.getElementById("exportPlaylistButton");
      const coverUploadInput = document.getElementById("coverUploadInput");
      const playPauseBtn = document.getElementById("playPauseBtn");
      const shuffleBtn = document.getElementById("shuffleBtn");
      const repeatBtn = document.getElementById("repeatBtn");

      const ICON_PLAY = '<svg viewBox="0 0 24 24" fill="currentColor" stroke="none"><path d="M5 5a2 2 0 0 1 3.008-1.728l11.997 6.998a2 2 0 0 1 .003 3.458l-12 7A2 2 0 0 1 5 19z" /></svg>';
      const ICON_PAUSE = '<svg viewBox="0 0 24 24" fill="currentColor" stroke="none"><rect x="14" y="3" width="5" height="18" rx="1" /><rect x="5" y="3" width="5" height="18" rx="1" /></svg>';
      const ICON_REPEAT = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="m17 2 4 4-4 4" /><path d="M3 11v-1a4 4 0 0 1 4-4h14" /><path d="m7 22-4-4 4-4" /><path d="M21 13v1a4 4 0 0 1-4 4H3" /></svg>';
      const ICON_REPEAT_ONE = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="m17 2 4 4-4 4" /><path d="M3 11v-1a4 4 0 0 1 4-4h14" /><path d="m7 22-4-4 4-4" /><path d="M21 13v1a4 4 0 0 1-4 4H3" /><path d="M11 10h1v4" /></svg>';

      function setPlayPauseIcon(isPlaying) {
        playPauseBtn.innerHTML = isPlaying ? ICON_PAUSE : ICON_PLAY;
        playPauseBtn.title = isPlaying ? "Pause" : "Play";
      }
      const seek = document.getElementById("seek");
      const vol = document.getElementById("vol");
      const tCur = document.getElementById("tCur");
      const tDur = document.getElementById("tDur");
      const nowPlaying = document.getElementById("nowPlaying");
      const nowMeta = document.getElementById("nowMeta");
      const nowCover = document.getElementById("nowCover");
      const nowCoverFallback = document.getElementById("nowCoverFallback");
      const lyricsPanel = document.getElementById("lyricsPanel");
      const lyricsSource = document.getElementById("lyricsSource");
      const lyricsLines = document.getElementById("lyricsLines");
      const metadataModal = document.getElementById("metadataModal");
      const metadataTrackId = document.getElementById("metadataTrackId");
      const metadataMode = document.getElementById("metadataMode");
      const metadataScope = document.getElementById("metadataScope");
      const trackCoverInput = document.getElementById("trackCoverInput");
      const metadataTitle = document.getElementById("metadataTitle");
      const metadataArtist = document.getElementById("metadataArtist");
      const metadataAlbum = document.getElementById("metadataAlbum");
      const metadataPath = document.getElementById("metadataPath");
      const metadataError = document.getElementById("metadataError");
      const lyricsEditorModal = document.getElementById("lyricsEditorModal");
      const lyricsEditorTrackId = document.getElementById("lyricsEditorTrackId");
      const lyricsEditorText = document.getElementById("lyricsEditorText");
      const lyricsEditorSource = document.getElementById("lyricsEditorSource");
      const lyricsEditorError = document.getElementById("lyricsEditorError");
      const lyricsFileInput = document.getElementById("lyricsFileInput");
      const sideAlbum = document.getElementById("sideAlbum");
      const sideCover = document.getElementById("sideCover");
      const sideCoverFallback = document.getElementById("sideCoverFallback");
      const sideTitle = document.getElementById("sideTitle");
      const sideArtist = document.getElementById("sideArtist");
      const sideLiked = document.getElementById("sideLiked");
      const artistAvatar = document.getElementById("artistAvatar");
      const artistAvatarFallback = document.getElementById("artistAvatarFallback");
      const artistName = document.getElementById("artistName");
      const artistStats = document.getElementById("artistStats");
      const artistInfo = document.getElementById("artistInfo");
      const lyricsView = document.getElementById("lyricsView");
      const lyricsViewBg = document.getElementById("lyricsViewBg");
      const lyricsViewTitle = document.getElementById("lyricsViewTitle");
      const lyricsViewLines = document.getElementById("lyricsViewLines");
      const lyricsViewAlbum = document.getElementById("lyricsViewAlbum");
      const lyricsViewCover = document.getElementById("lyricsViewCover");
      const lyricsViewCoverFallback = document.getElementById("lyricsViewCoverFallback");
      const lyricsViewSong = document.getElementById("lyricsViewSong");
      const lyricsViewArtist = document.getElementById("lyricsViewArtist");
      const lyricsArtistAvatar = document.getElementById("lyricsArtistAvatar");
      const lyricsArtistAvatarFallback = document.getElementById("lyricsArtistAvatarFallback");
      const lyricsArtistName = document.getElementById("lyricsArtistName");
      const lyricsArtistStats = document.getElementById("lyricsArtistStats");
      const lyricsArtistInfo = document.getElementById("lyricsArtistInfo");
      const queuePanel = document.getElementById("queuePanel");
      const queueItems = document.getElementById("queueItems");
      const queueCount = document.getElementById("queueCount");
      const nowFullscreen = document.getElementById("nowFullscreen");
      const nowFullscreenBg = document.getElementById("nowFullscreenBg");
      const fullCover = document.getElementById("fullCover");
      const fullCoverFallback = document.getElementById("fullCoverFallback");
      const fullAlbum = document.getElementById("fullAlbum");
      const fullTitle = document.getElementById("fullTitle");
      const fullArtist = document.getElementById("fullArtist");
      const fullInfo = document.getElementById("fullInfo");
      const artistMatchModal = document.getElementById("artistMatchModal");
      const artistMatchSubtitle = document.getElementById("artistMatchSubtitle");
      const artistMatchStatus = document.getElementById("artistMatchStatus");
      const musicBrainzMatches = document.getElementById("musicBrainzMatches");
      const wikipediaMatches = document.getElementById("wikipediaMatches");
      const artistMatchChoice = document.getElementById("artistMatchChoice");

      let appState = { likes: [], playlists: [] };
      let likedIdSet = new Set();
      let playlistMap = new Map();
      let historyData = { tracks: {}, recent: [], most_played: [] };
      let folders = [];
      let currentView = "library";
      let currentPlaylistId = "default";
      let currentFolder = "";
      let activePlayable = libraryPlayable;
      let currentIndex = -1;
      let currentTrackId = null;
      let isSeeking = false;
      let lyricEntries = [];
      let plainLyrics = "";
      let activeLyricIndex = -1;
      let customBackgroundUrl = "";
      let pendingTrackCoverId = "";
      let shuffleEnabled = false;
      let repeatMode = "off";
      let manualQueue = [];
      let currentTrack = null;
      let lastPositionSync = 0;

      // Crossfade: two <audio> elements alternate as "active" so the next
      // track can start (and fade in) on the idle one while the current
      // track fades out on its own, rather than a hard cut at track end.
      const CROSSFADE_SECONDS = 4;
      let crossfadeEnabled = localStorage.getItem("crossfadeEnabled") !== "0";
      let crossfading = false;
      let fadeAnimationFrame = null;
      let selectedTrackIds = new Set();
      let metadataBatchIds = [];
      let filteredTracks = [];
      let filterTimer = null;
      let artistMatchArtist = "";
      let selectedMusicBrainzId = "";
      let selectedWikipediaTitle = "";

      // Track-grid virtualization state: only the rows currently scrolled
      // into view (plus an overscan buffer) are ever mounted as real DOM
      // nodes, regardless of how many tracks match the current filter.
      const GRID_MIN_CARD_WIDTH = 260;
      const GRID_OVERSCAN_ROWS = 3;
      const GRID_FALLBACK_ROW_HEIGHT = 168;
      let gridColumns = 1;
      let gridRowHeight = 0;
      let gridStartIndex = -1;
      let gridEndIndex = -1;
      let gridRenderScheduled = false;
      const artistInfoMemory = new Map();
      const artistInfoLoading = new Set();

      if (libraryCount) libraryCount.textContent = `(${libraryPlayable.length})`;

      function normalizeTrack(track) {
        return {
          id: String(track.id),
          title: track.title || "Untitled",
          artist: track.artist || "Unknown Artist",
          album: track.album || "Unknown Album",
          folder: track.folder || "",
          relpath: track.relpath,
          url: track.url || `/music/${encodePath(track.relpath)}`,
          coverUrl: track.cover_url || `/cover/${track.id}`,
        };
      }

      function encodePath(path) {
        return String(path).split("/").map((part) => encodeURIComponent(part)).join("/");
      }

      function withCsrf(options = {}) {
        const method = String(options.method || "GET").toUpperCase();
        const headers = new Headers(options.headers || {});
        if (!["GET", "HEAD", "OPTIONS"].includes(method)) {
          headers.set("X-CSRF-Token", csrfToken);
        }
        return { ...options, headers };
      }

      function apiFetch(url, options = {}) {
        return fetch(url, withCsrf(options));
      }

      async function fetchJson(url, options = {}) {
        const res = await apiFetch(url, options);
        if (!res.ok) throw new Error(await res.text());
        return res.json();
      }

      async function refreshState() {
        const [state, folderData] = await Promise.all([
          fetchJson("/api/library-state"),
          fetchJson("/api/folders"),
        ]);
        appState = state;
        folders = folderData;
        likedIdSet = new Set((appState.likes || []).map(String));
        playlistMap = new Map((appState.playlists || []).map((p) => [String(p.id), p]));
        historyData = appState.history || { tracks: {}, recent: [], most_played: [] };
        Object.entries(appState.artist_info || {}).forEach(([artist, info]) => {
          artistInfoMemory.set(String(artist).toLowerCase(), info);
        });

        if (!playlistMap.has(currentPlaylistId)) currentPlaylistId = "default";
        if (likedCount) likedCount.textContent = `(${likedIdSet.size})`;
        const usernameEl = document.getElementById("currentUsername");
        if (usernameEl && appState.username) {
          usernameEl.textContent = appState.username;
          usernameEl.classList.remove("hidden");
        }

        renderPlaylists();
        renderFolders();
        renderHome();
        setAppBackground(appState.background_url || "");
        updateTrackButtons();
        applyFilter();
      }

      function setAppBackground(url) {
        customBackgroundUrl = url || "";
        bgVideo.classList.add("hidden");
        bgVideo.removeAttribute("src");

        if (!customBackgroundUrl) {
          bg.style.setProperty("--cover-url", "linear-gradient(135deg, rgba(255,255,255,0.08), rgba(255,255,255,0))");
          return;
        }

        const cleanUrl = customBackgroundUrl.split("?")[0].toLowerCase();
        if (cleanUrl.endsWith(".mp4") || cleanUrl.endsWith(".webm") || cleanUrl.endsWith(".mov")) {
          bgVideo.src = customBackgroundUrl;
          bgVideo.classList.remove("hidden");
          bg.style.setProperty("--cover-url", "linear-gradient(135deg, rgba(0,0,0,0.12), rgba(0,0,0,0))");
          return;
        }

        bg.style.setProperty("--cover-url", `url("${customBackgroundUrl}")`);
      }

      function tileCover(url, fallback = "") {
        if (!url) {
          return `<span class="flex h-16 w-16 shrink-0 items-center justify-center rounded-l-lg bg-zinc-900 text-xs text-zinc-500">${escapeHtml(fallback)}</span>`;
        }
        return `<img src="${escapeHtml(url)}" class="h-16 w-16 shrink-0 rounded-l-lg object-cover" alt="" />`;
      }

      function renderHome() {
        if (!homeTiles || !homePlaylistTiles) return;
        renderHistorySections();
        const folderTiles = folders.map((folder) => `
          <button type="button" data-action="show-folder" data-folder="${escapeHtml(folder.name)}" class="flex min-w-0 items-center gap-4 overflow-hidden rounded-lg bg-zinc-800/80 text-left font-semibold hover:bg-zinc-700">
            ${tileCover(folder.cover_url)}
            <span class="min-w-0">
              <span class="block truncate">${escapeHtml(folder.name)}</span>
              <span class="block text-sm font-normal text-zinc-400">${folder.count} tracks</span>
            </span>
          </button>
        `);

        homeTiles.innerHTML = [
          `<button type="button" data-action="show-liked" class="flex min-w-0 items-center gap-4 overflow-hidden rounded-lg bg-zinc-800/80 text-left font-semibold hover:bg-zinc-700">
            <span class="flex h-16 w-16 shrink-0 items-center justify-center rounded-l-lg bg-gradient-to-br from-violet-600 via-blue-300 to-emerald-200 text-2xl text-white">&hearts;</span>
            <span class="min-w-0">
              <span class="block truncate">Liked Songs</span>
              <span class="block text-sm font-normal text-zinc-400">${likedIdSet.size} tracks</span>
            </span>
          </button>`,
          ...folderTiles,
        ].join("");

        homePlaylistTiles.innerHTML = Array.from(playlistMap.values()).map((playlist) => `
          <button type="button" data-action="show-playlist" data-playlist-id="${escapeHtml(playlist.id)}" class="flex min-w-0 items-center gap-4 overflow-hidden rounded-lg bg-zinc-800/80 text-left font-semibold hover:bg-zinc-700">
            ${tileCover(playlist.cover_url)}
            <span class="min-w-0">
              <span class="block truncate">${escapeHtml(playlist.name)}</span>
              <span class="block text-sm font-normal text-zinc-400">${playlist.count} tracks</span>
            </span>
          </button>
        `).join("");

        if (artistTiles) {
          artistTiles.innerHTML = artistSummaries().slice(0, 12).map(artistCard).join("");
        }

        if (albumTiles) {
          albumTiles.innerHTML = albumSummaries().slice(0, 12).map(albumCard).join("");
        }

        if (smartTiles) {
          const noCoverCount = libraryPlayable.filter((track) => track.coverUrl.includes("/cover/")).length;
          const rootCount = libraryPlayable.filter((track) => !track.folder).length;
          const longNames = libraryPlayable.filter((track) => track.title.length > 40);
          const weakMetadata = metadataCleanupCandidates();
          const continueItems = continueCandidates();
          const recentItems = tracksFromHistory(historyData.recent).slice(0, 30);
          const mostPlayedItems = tracksFromHistory(historyData.most_played).slice(0, 30);
          smartTiles.innerHTML = [
            smartTile("Continue Listening", `${continueItems.length} unfinished tracks`, "show-smart-continue"),
            smartTile("Recently Played", `${recentItems.length} tracks`, "show-smart-recently"),
            smartTile("Most Played", `${mostPlayedItems.length} tracks`, "show-smart-most-played"),
            smartTile("Random 25", "A shuffled quick mix", "show-smart-random"),
            smartTile("Missing Covers", `${noCoverCount} tracks using embedded/default art`, "show-smart-missing-covers"),
            smartTile("Metadata Cleanup", `${weakMetadata.length} tracks with weak tags`, "show-smart-metadata-cleanup"),
            smartTile("Root Folder", `${rootCount} tracks outside subfolders`, "show-smart-root"),
            smartTile("Long Titles", `${longNames.length} tracks`, "show-smart-long-titles"),
          ].join("");
        }
      }

      function renderHistorySections() {
        const continueItems = continueCandidates().slice(0, 6);
        const recentItems = tracksFromHistory(historyData.recent).slice(0, 6);
        const mostPlayedItems = tracksFromHistory(historyData.most_played).slice(0, 6);

        renderTrackTileSection(continueSection, continueTiles, continueItems, true);
        renderTrackTileSection(recentSection, recentTiles, recentItems, false);
        renderTrackTileSection(mostPlayedSection, mostPlayedTiles, mostPlayedItems, false);
      }

      function renderTrackTileSection(section, container, items, resume = false) {
        if (!section || !container) return;
        section.classList.toggle("hidden", !items.length);
        container.innerHTML = items.map((track) => trackTile(track, resume)).join("");
      }

      function trackTile(track, resume = false) {
        const history = historyData.tracks?.[track.id] || {};
        const subtitle = resume && history.last_position
          ? `Resume at ${fmtTime(Number(history.last_position || 0))}`
          : `${track.artist} - ${track.album}`;
        const action = resume ? `resumeTrack('${jsString(track.id)}')` : `playTrackFromHome('${jsString(track.id)}')`;
        return `<button type="button" data-action="${resume ? "resume-track" : "play-home-track"}" data-track-id="${escapeHtml(track.id)}" class="flex min-w-0 items-center gap-4 overflow-hidden rounded-lg bg-zinc-800/80 text-left font-semibold hover:bg-zinc-700">
          ${tileCover(track.coverUrl)}
          <span class="min-w-0">
            <span class="block truncate">${escapeHtml(track.title)}</span>
            <span class="block truncate text-sm font-normal text-zinc-400">${escapeHtml(subtitle)}</span>
          </span>
        </button>`;
      }

      function smartTile(title, subtitle, action) {
        return `<button type="button" data-action="${escapeHtml(action)}" class="flex min-w-0 items-center gap-4 overflow-hidden rounded-lg bg-zinc-800/80 p-4 text-left font-semibold hover:bg-zinc-700">
          <span class="flex h-12 w-12 shrink-0 items-center justify-center rounded-lg bg-gradient-to-br from-zinc-700 to-zinc-900 text-xs text-zinc-300">Mix</span>
          <span class="min-w-0">
            <span class="block truncate">${escapeHtml(title)}</span>
            <span class="block text-sm font-normal text-zinc-400">${escapeHtml(subtitle)}</span>
          </span>
        </button>`;
      }

      function artistSummaries() {
        const map = new Map();
        for (const track of libraryPlayable) {
          const key = track.artist || "Unknown Artist";
          if (!map.has(key)) {
            map.set(key, { name: key, tracks: [], albums: new Set(), coverUrl: track.coverUrl });
          }
          const item = map.get(key);
          item.tracks.push(track);
          item.albums.add(track.album);
          if (!item.coverUrl || item.coverUrl.includes("/cover/")) item.coverUrl = track.coverUrl;
        }
        return Array.from(map.values())
          .map((item) => ({ ...item, albumCount: item.albums.size, trackCount: item.tracks.length }))
          .sort((a, b) => b.trackCount - a.trackCount || a.name.localeCompare(b.name));
      }

      function albumSummaries() {
        const map = new Map();
        for (const track of libraryPlayable) {
          const key = track.album || "Unknown Album";
          if (!map.has(key)) {
            map.set(key, { name: key, tracks: [], artists: new Set(), coverUrl: track.coverUrl });
          }
          const item = map.get(key);
          item.tracks.push(track);
          item.artists.add(track.artist);
          if (!item.coverUrl || item.coverUrl.includes("/cover/")) item.coverUrl = track.coverUrl;
        }
        return Array.from(map.values())
          .map((item) => ({ ...item, artistNames: Array.from(item.artists).sort(), trackCount: item.tracks.length }))
          .sort((a, b) => b.trackCount - a.trackCount || a.name.localeCompare(b.name));
      }

      function artistCard(artist) {
        return `<button type="button" data-action="show-artist" data-artist="${escapeHtml(artist.name)}" class="min-w-0 rounded-xl bg-zinc-900/80 p-3 text-left hover:bg-zinc-800">
          <img src="${escapeHtml(artist.coverUrl)}" class="aspect-square w-full rounded-lg object-cover" alt="" onerror="this.style.display='none'" />
          <span class="mt-3 block truncate font-semibold">${escapeHtml(artist.name)}</span>
          <span class="mt-1 block truncate text-sm text-zinc-400">${artist.trackCount} tracks, ${artist.albumCount} albums</span>
        </button>`;
      }

      function albumCard(album) {
        return `<button type="button" data-action="show-album" data-album="${escapeHtml(album.name)}" class="min-w-0 rounded-xl bg-zinc-900/80 p-3 text-left hover:bg-zinc-800">
          <img src="${escapeHtml(album.coverUrl)}" class="aspect-square w-full rounded-lg object-cover" alt="" onerror="this.style.display='none'" />
          <span class="mt-3 block truncate font-semibold">${escapeHtml(album.name)}</span>
          <span class="mt-1 block truncate text-sm text-zinc-400">${escapeHtml(album.artistNames.join(", "))}</span>
          <span class="mt-1 block text-xs text-zinc-500">${album.trackCount} tracks</span>
        </button>`;
      }

      function jsString(value) {
        return String(value).replace(/\\/g, "\\\\").replace(/'/g, "\\'");
      }

      function renderFolders() {
        if (!folderList) return;
        folderList.innerHTML = "";
        for (const folder of folders) {
          const row = document.createElement("button");
          row.type = "button";
          row.className = "folder-button flex w-full items-center gap-3 rounded-lg px-3 py-2 text-left text-sm hover:bg-zinc-900";
          row.dataset.folder = folder.name;
          row.onclick = () => showFolder(folder.name);
          row.innerHTML = `${smallCover(folder.cover_url)}<span class="min-w-0"><span class="block truncate">${escapeHtml(folder.name)}</span><span class="text-xs text-zinc-500">${folder.count} tracks</span></span>`;
          folderList.appendChild(row);
        }
      }

      function renderPlaylists() {
        if (!playlistList) return;
        playlistList.innerHTML = "";
        if (playlistTarget) playlistTarget.innerHTML = "";

        for (const playlist of playlistMap.values()) {
          if (playlistTarget) {
            const option = document.createElement("option");
            option.value = playlist.id;
            option.textContent = playlist.name;
            option.selected = playlist.id === currentPlaylistId;
            playlistTarget.appendChild(option);
          }

          const row = document.createElement("button");
          row.type = "button";
          row.className = "playlist-button flex w-full items-center gap-3 rounded-lg px-3 py-2 text-left text-sm hover:bg-zinc-900";
          row.dataset.playlistId = playlist.id;
          row.onclick = () => showPlaylist(playlist.id);
          row.innerHTML = `${smallCover(playlist.cover_url)}<span class="min-w-0"><span class="block truncate">${escapeHtml(playlist.name)}</span><span class="text-xs text-zinc-500">${playlist.count} tracks</span></span>`;
          playlistList.appendChild(row);
        }

        setActiveNavigation();
      }

      function smallCover(url) {
        if (!url) {
          return `<span class="h-10 w-10 shrink-0 rounded-md border border-zinc-800 bg-zinc-950"></span>`;
        }
        return `<img src="${escapeHtml(url)}" class="h-10 w-10 shrink-0 rounded-md border border-zinc-800 object-cover" alt="" />`;
      }

      function setPlaylistTarget(playlistId) {
        if (!playlistMap.has(String(playlistId))) return;
        currentPlaylistId = String(playlistId);
        setActiveNavigation();
        if (currentView === "library") {
          const selected = playlistMap.get(currentPlaylistId);
          viewSubtitle.textContent = `${libraryPlayable.length} tracks from your local folder. Add target: ${selected.name}`;
        }
      }

      function escapeHtml(value) {
        return String(value).replace(/[&<>"']/g, (char) => ({
          "&": "&amp;",
          "<": "&lt;",
          ">": "&gt;",
          "\"": "&quot;",
          "'": "&#39;",
        }[char]));
      }

      function closeCardMenus() {
        document.querySelectorAll(".track-menu").forEach((menu) => {
          menu.classList.add("hidden");
        });
      }

      function showToast(message, kind = "ok") {
        if (!toastHost) return;
        const toast = document.createElement("div");
        toast.className = `rounded-lg border px-4 py-3 text-sm shadow-xl ${
          kind === "error"
            ? "border-red-900 bg-red-950 text-red-100"
            : "border-zinc-700 bg-zinc-950 text-zinc-100"
        }`;
        toast.textContent = message;
        toastHost.appendChild(toast);
        setTimeout(() => toast.remove(), 2600);
      }

      function localArtistSummary(artist) {
        const sameArtistTracks = libraryPlayable.filter((item) => item.artist === artist);
        const artistAlbums = new Set(sameArtistTracks.map((item) => item.album));
        return {
          tracks: sameArtistTracks,
          albums: artistAlbums,
          text: `${artist} appears on ${sameArtistTracks.length} track${sameArtistTracks.length === 1 ? "" : "s"} across ${artistAlbums.size} album${artistAlbums.size === 1 ? "" : "s"} in your local library.`,
        };
      }

      function applyArtistInfoToSidePanel(artist, info) {
        if (!info || currentTrack?.artist !== artist) return;
        const text = info.summary || "";
        if (text) {
          artistInfo.textContent = text;
          lyricsArtistInfo.textContent = text;
          fullInfo.textContent = text;
        }
        if (info.image_url) {
          setImage(artistAvatar, artistAvatarFallback, info.image_url);
          setImage(lyricsArtistAvatar, lyricsArtistAvatarFallback, info.image_url);
        }
      }

      function artistInfoDetails(info) {
        const mb = info?.musicbrainz || {};
        const parts = [
          mb.type,
          mb.area || mb.country,
          mb.begin ? `Since ${mb.begin}` : "",
          mb.genres?.length ? mb.genres.slice(0, 5).join(", ") : mb.tags?.length ? mb.tags.slice(0, 5).join(", ") : "",
        ].filter(Boolean);
        return parts.length ? parts.join(" / ") : "No structured internet metadata found yet.";
      }

      const CONFIDENCE_LABELS = {
        verified: { text: "Verified match", class: "text-emerald-400" },
        high: { text: "High-confidence match", class: "text-emerald-400" },
        low: { text: "Best guess - please confirm", class: "text-amber-400" },
        none: { text: "", class: "" },
      };

      function renderArtistInfoPanel(artist, info, loading = false) {
        const panel = document.getElementById("artistInfoPanel");
        if (!panel) return;
        const summary = info?.summary || (loading ? "Loading internet artist info..." : "No internet artist info found yet. Try refresh.");
        const sourceText = info?.sources?.length ? `Sources: ${info.sources.join(", ")}` : "Sources: local library only";
        const confidence = CONFIDENCE_LABELS[info?.match_confidence] || CONFIDENCE_LABELS.none;
        panel.innerHTML = `
          <div class="flex flex-col gap-4 rounded-xl border border-zinc-800 bg-zinc-950/70 p-4 sm:flex-row">
            <div class="h-24 w-24 shrink-0 overflow-hidden rounded-xl bg-zinc-900">
              ${info?.image_url ? `<img src="${escapeHtml(info.image_url)}" class="h-full w-full object-cover" alt="" />` : `<div class="flex h-full w-full items-center justify-center text-xs text-zinc-500">Artist</div>`}
            </div>
            <div class="min-w-0 flex-1">
              <div class="flex items-start justify-between gap-3">
                <div>
                  <div class="text-xs uppercase tracking-widest text-zinc-500">Internet info</div>
                  <div class="mt-1 text-sm text-zinc-400">${escapeHtml(artistInfoDetails(info))}</div>
                </div>
                <div class="flex flex-wrap gap-2">
                  <button type="button" data-action="choose-artist-match" data-artist="${escapeHtml(artist)}" class="rounded-lg border border-zinc-700 px-3 py-2 text-sm hover:bg-zinc-900">Choose match</button>
                  <button type="button" data-action="refresh-artist-info" data-artist="${escapeHtml(artist)}" class="rounded-lg border border-zinc-700 px-3 py-2 text-sm hover:bg-zinc-900">${loading ? "Loading" : "Refresh"}</button>
                </div>
              </div>
              <p class="mt-3 text-sm leading-6 text-zinc-300">${escapeHtml(summary)}</p>
              <div class="mt-3 flex flex-wrap items-center gap-3 text-xs text-zinc-500">
                <span>${escapeHtml(sourceText)}</span>
                ${confidence.text ? `<span class="${confidence.class}">${escapeHtml(confidence.text)}</span>` : ""}
                ${info?.external_url ? `<a href="${escapeHtml(info.external_url)}" target="_blank" rel="noreferrer" class="text-zinc-300 underline hover:text-white">Open source page</a>` : ""}
              </div>
            </div>
          </div>
        `;
      }

      async function loadArtistInfo(artist, options = {}) {
        artist = String(artist || "").trim();
        if (!artist) return null;
        const key = artist.toLowerCase();
        if (!options.refresh && artistInfoMemory.has(key)) {
          const info = artistInfoMemory.get(key);
          if (options.target === "artist-page") renderArtistInfoPanel(artist, info);
          applyArtistInfoToSidePanel(artist, info);
          return info;
        }
        if (artistInfoLoading.has(key)) return null;

        artistInfoLoading.add(key);
        if (options.target === "artist-page") renderArtistInfoPanel(artist, null, true);
        try {
          const params = new URLSearchParams({ artist });
          if (options.refresh) params.set("refresh", "1");
          const data = await fetchJson(`/api/artist-info?${params.toString()}`);
          const info = data.info || {};
          artistInfoMemory.set(key, info);
          applyArtistInfoToSidePanel(artist, info);
          if (options.target === "artist-page") renderArtistInfoPanel(artist, info);
          if (options.refresh) showToast(info.summary ? "Artist info refreshed" : "No artist info found");
          return info;
        } catch (error) {
          console.warn("Could not load artist info", error);
          if (options.target === "artist-page") renderArtistInfoPanel(artist, null);
          if (options.refresh) showToast("Could not refresh artist info", "error");
          return null;
        } finally {
          artistInfoLoading.delete(key);
        }
      }

      function musicBrainzMatchHtml(item) {
        const details = [item.type, item.area || item.country, item.disambiguation, item.begin ? `Since ${item.begin}` : ""].filter(Boolean).join(" / ");
        const tags = item.tags?.length ? item.tags.slice(0, 4).join(", ") : "";
        return `
          <button type="button" data-action="select-musicbrainz-match" data-id="${escapeHtml(item.id)}" class="artist-match-option w-full rounded-lg border border-zinc-800 bg-zinc-950 p-3 text-left hover:bg-zinc-900">
            <span class="block font-semibold">${escapeHtml(item.name || "Unknown")}</span>
            <span class="mt-1 block text-sm text-zinc-400">${escapeHtml(details || "No extra details")}</span>
            ${tags ? `<span class="mt-1 block text-xs text-zinc-500">${escapeHtml(tags)}</span>` : ""}
          </button>
        `;
      }

      function wikipediaMatchHtml(item) {
        return `
          <button type="button" data-action="select-wikipedia-match" data-title="${escapeHtml(item.title)}" class="artist-match-option w-full rounded-lg border border-zinc-800 bg-zinc-950 p-3 text-left hover:bg-zinc-900">
            <span class="block font-semibold">${escapeHtml(item.title || "Unknown")}</span>
            <span class="mt-1 block text-sm text-zinc-400">${escapeHtml(item.description || "No description")}</span>
            ${item.url ? `<span class="mt-1 block truncate text-xs text-zinc-500">${escapeHtml(item.url)}</span>` : ""}
          </button>
        `;
      }

      async function openArtistMatchPicker(artist) {
        artistMatchArtist = String(artist || "").trim();
        selectedMusicBrainzId = "";
        selectedWikipediaTitle = "";
        if (!artistMatchArtist) return;
        artistMatchSubtitle.textContent = artistMatchArtist;
        artistMatchStatus.textContent = "Loading matches...";
        musicBrainzMatches.innerHTML = "";
        wikipediaMatches.innerHTML = "";
        artistMatchChoice.textContent = "Select one MusicBrainz match and/or one Wikipedia match.";
        artistMatchModal.classList.remove("hidden");
        artistMatchModal.classList.add("flex");
        try {
          const params = new URLSearchParams({ artist: artistMatchArtist });
          const data = await fetchJson(`/api/artist-info/matches?${params.toString()}`);
          const matches = data.matches || {};
          const mb = matches.musicbrainz || [];
          const wiki = matches.wikipedia || [];
          musicBrainzMatches.innerHTML = mb.length
            ? mb.map(musicBrainzMatchHtml).join("")
            : `<div class="rounded-lg border border-zinc-800 p-3 text-sm text-zinc-500">No MusicBrainz matches found.</div>`;
          wikipediaMatches.innerHTML = wiki.length
            ? wiki.map(wikipediaMatchHtml).join("")
            : `<div class="rounded-lg border border-zinc-800 p-3 text-sm text-zinc-500">No Wikipedia matches found.</div>`;
          artistMatchStatus.textContent = matches.errors?.length ? matches.errors.join(" / ") : "Choose the correct source result, then save.";
        } catch (error) {
          console.warn("Could not load artist matches", error);
          artistMatchStatus.textContent = "Could not load matches.";
          showToast("Could not load artist matches", "error");
        }
      }

      function closeArtistMatchPicker() {
        artistMatchModal.classList.add("hidden");
        artistMatchModal.classList.remove("flex");
      }

      function selectArtistMatch(kind, value) {
        if (kind === "musicbrainz") {
          selectedMusicBrainzId = value || "";
          musicBrainzMatches.querySelectorAll(".artist-match-option").forEach((button) => {
            button.classList.toggle("border-zinc-100", button.dataset.id === selectedMusicBrainzId);
            button.classList.toggle("bg-zinc-800", button.dataset.id === selectedMusicBrainzId);
          });
        } else {
          selectedWikipediaTitle = value || "";
          wikipediaMatches.querySelectorAll(".artist-match-option").forEach((button) => {
            button.classList.toggle("border-zinc-100", button.dataset.title === selectedWikipediaTitle);
            button.classList.toggle("bg-zinc-800", button.dataset.title === selectedWikipediaTitle);
          });
        }
        const mbText = selectedMusicBrainzId ? "MusicBrainz selected" : "No MusicBrainz match";
        const wikiText = selectedWikipediaTitle ? `Wikipedia: ${selectedWikipediaTitle}` : "No Wikipedia match";
        artistMatchChoice.textContent = `${mbText}. ${wikiText}.`;
      }

      async function saveArtistMatch() {
        if (!artistMatchArtist) return;
        if (!selectedMusicBrainzId && !selectedWikipediaTitle && !window.confirm("Save an automatic lookup without choosing a source match?")) return;
        artistMatchStatus.textContent = "Saving selected match...";
        try {
          const data = await fetchJson("/api/artist-info/choose", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
              artist: artistMatchArtist,
              musicbrainz_id: selectedMusicBrainzId,
              wikipedia_title: selectedWikipediaTitle,
            }),
          });
          const info = data.info || {};
          artistInfoMemory.set(artistMatchArtist.toLowerCase(), info);
          renderArtistInfoPanel(artistMatchArtist, info);
          applyArtistInfoToSidePanel(artistMatchArtist, info);
          closeArtistMatchPicker();
          showToast("Artist match saved");
        } catch (error) {
          console.warn("Could not save artist match", error);
          artistMatchStatus.textContent = "Could not save selected match.";
          showToast("Could not save artist match", "error");
        }
      }

      const actionHandlers = {
        "refresh-library": () => refreshLibrary(),
        "choose-background": () => chooseBackground(),
        "export-backup": () => exportBackup(),
        "choose-restore": () => chooseRestore(),
        "show-home": () => showHome(),
        "show-settings": () => showSettings(),
        "show-library": () => showLibrary(),
        "show-liked": () => showLiked(),
        "create-playlist": () => createPlaylist(),
        "delete-current-playlist": () => deleteCurrentPlaylist(),
        "export-playlist": () => exportCurrentPlaylist(),
        "choose-view-cover": () => chooseViewCover(),
        "select-visible": () => selectVisibleTracks(),
        "clear-selection": () => clearSelection(),
        "batch-like": () => batchLike(true),
        "batch-unlike": () => batchLike(false),
        "batch-add-playlist": () => batchAddToPlaylist(),
        "batch-play-next": () => batchPlayNext(),
        "batch-queue": () => batchQueue(),
        "batch-edit-metadata": () => openBatchMetadataEditor(),
        "batch-derive-metadata": () => applyDerivedMetadata(Array.from(selectedTrackIds)),
        "delete-user": (el) => handleDeleteUser(el.dataset.username),
        "show-smart-continue": () => showSmartContinue(),
        "show-smart-recently": () => showSmartRecently(),
        "show-smart-most-played": () => showSmartMostPlayed(),
        "show-smart-random": () => showSmartRandom(),
        "show-smart-missing-covers": () => showSmartMissingCovers(),
        "show-smart-metadata-cleanup": () => showSmartMetadataCleanup(),
        "show-smart-root": () => showSmartRoot(),
        "show-smart-long-titles": () => showSmartLongTitles(),
        "show-folder": (el) => showFolder(el.dataset.folder),
        "show-playlist": (el) => showPlaylist(el.dataset.playlistId),
        "show-artist": (el) => showArtist(el.dataset.artist),
        "show-album": (el) => showAlbum(el.dataset.album),
        "show-all-artists": () => showAllArtists(),
        "show-all-albums": () => showAllAlbums(),
        "refresh-artist-info": (el) => loadArtistInfo(el.dataset.artist, { refresh: true, target: "artist-page" }),
        "choose-artist-match": (el) => openArtistMatchPicker(el.dataset.artist),
        "select-musicbrainz-match": (el) => selectArtistMatch("musicbrainz", el.dataset.id),
        "select-wikipedia-match": (el) => selectArtistMatch("wikipedia", el.dataset.title),
        "save-artist-match": () => saveArtistMatch(),
        "close-artist-match": () => closeArtistMatchPicker(),
        "play-home-track": (el) => playTrackFromHome(el.dataset.trackId),
        "resume-track": (el) => resumeTrack(el.dataset.trackId),
        "play-any-track": (el) => playTrackFromAnyQueue(el.dataset.trackId),
        "shuffle-active": (el) => showSmartTracks(el.dataset.title || "Shuffle", el.dataset.subtitle || "", [...activePlayable].sort(() => Math.random() - 0.5)),
        "queue-active": () => queueAlbum(),
        "play-track": (el) => playTrackId(el.dataset.trackId),
        "toggle-card-menu": (el, event) => {
          event.stopPropagation();
          toggleCardMenu(el.dataset.trackId);
        },
        "toggle-like": (el) => {
          toggleLike(el.dataset.trackId);
          closeCardMenus();
        },
        "add-playlist": (el) => {
          addTrackToActivePlaylist(el.dataset.trackId);
          closeCardMenus();
        },
        "play-next": (el) => {
          playNext(el.dataset.trackId);
          closeCardMenus();
        },
        "add-queue": (el) => {
          addToQueue(el.dataset.trackId);
          closeCardMenus();
        },
        "remove-playlist": (el) => {
          removeTrackFromCurrentPlaylist(el.dataset.trackId);
          closeCardMenus();
        },
        "edit-metadata": (el) => {
          openMetadataEditor(el.dataset.trackId);
          closeCardMenus();
        },
        "derive-metadata": (el) => {
          applyDerivedMetadata([el.dataset.trackId]);
          closeCardMenus();
        },
        "set-track-cover": (el) => {
          chooseTrackCover(el.dataset.trackId);
          closeCardMenus();
        },
        "edit-lrc": (el) => {
          openLyricsEditor(el.dataset.trackId);
          closeCardMenus();
        },
        "prev-track": () => prevTrack(),
        "toggle-shuffle": () => toggleShuffle(),
        "toggle-play": () => togglePlayPause(),
        "next-track": () => nextTrack(),
        "cycle-repeat": () => cycleRepeat(),
        "open-queue": () => openQueuePanel(),
        "open-fullscreen": () => openNowFullscreen(),
        "open-lyrics": () => openLyricsView(),
        "close-lyrics": () => closeLyricsView(),
        "close-fullscreen": () => closeNowFullscreen(),
        "clear-queue": () => clearQueue(),
        "close-queue": () => closeQueuePanel(),
        "play-queued-index": (el) => playQueuedIndex(Number(el.dataset.index)),
        "remove-queued-index": (el) => removeQueuedIndex(Number(el.dataset.index)),
        "close-metadata": () => closeMetadataEditor(),
        "fill-derived-metadata": () => fillDerivedMetadata(),
        "apply-derived-metadata": () => applyDerivedMetadata(metadataTargetIds()),
        "close-lyrics-editor": () => closeLyricsEditor(),
        "insert-lyrics-timestamp": () => insertCurrentTimestamp(),
        "clear-artist-cache": () => clearArtistCache(),
      };

      function toggleCardMenu(trackId) {
        const target = document.querySelector(`[data-menu-id="${CSS.escape(String(trackId))}"]`);
        if (!target) return;
        const willOpen = target.classList.contains("hidden");
        closeCardMenus();
        target.classList.toggle("hidden", !willOpen);
      }

      function trackCardHtml(track) {
        const reorderable = currentView === "playlist";
        return `
          <article
            class="track-card group rounded-xl border border-zinc-800 bg-black/55 p-3 transition hover:bg-zinc-950${reorderable ? " cursor-grab active:cursor-grabbing" : ""}"
            data-id="${escapeHtml(track.id)}"
            data-title="${escapeHtml(track.title)}"
            data-artist="${escapeHtml(track.artist)}"
            data-album="${escapeHtml(track.album)}"
            data-folder="${escapeHtml(track.folder)}"
            ${reorderable ? 'draggable="true"' : ""}
          >
            <div class="mb-3 flex items-center justify-between gap-3">
              <label class="flex items-center gap-2 text-xs text-zinc-400">
                <input type="checkbox" class="track-select accent-zinc-100" data-track-id="${escapeHtml(track.id)}" />
                Select
              </label>
            </div>
            <div class="flex gap-3">
              <button type="button" data-action="play-track" data-track-id="${escapeHtml(track.id)}" class="h-20 w-20 shrink-0 overflow-hidden rounded-lg border border-zinc-800 bg-zinc-950">
                <img
                  src="${escapeHtml(track.coverUrl)}"
                  onerror="this.style.display='none'; this.nextElementSibling.style.display='flex';"
                  class="h-full w-full object-cover"
                  alt=""
                />
                <span class="hidden h-full w-full items-center justify-center text-xs text-zinc-500">No art</span>
              </button>

              <div class="min-w-0 flex-1">
                <button type="button" data-action="play-track" data-track-id="${escapeHtml(track.id)}" class="block w-full text-left">
                  <div class="truncate font-semibold">${escapeHtml(track.title)}</div>
                  <div class="mt-1 truncate text-sm text-zinc-300">${escapeHtml(track.artist)}</div>
                  <div class="truncate text-xs text-zinc-500">${escapeHtml(track.album)}</div>
                </button>

                <div class="relative mt-3 flex justify-end">
                  <button
                    type="button"
                    class="rounded-lg border border-zinc-700 px-3 py-1 text-sm leading-none hover:bg-zinc-900"
                    data-action="toggle-card-menu"
                    data-track-id="${escapeHtml(track.id)}"
                    title="Track options"
                  >
                    ...
                  </button>
                  <div
                    class="track-menu absolute right-0 top-8 z-30 hidden min-w-36 rounded-lg border border-zinc-700 bg-black/95 p-1 shadow-xl"
                    data-menu-id="${escapeHtml(track.id)}"
                  >
                    <button type="button" class="like-button block w-full rounded-md px-3 py-2 text-left text-sm hover:bg-zinc-900" data-action="toggle-like" data-track-id="${escapeHtml(track.id)}">Like</button>
                    <button type="button" class="add-button block w-full rounded-md px-3 py-2 text-left text-sm hover:bg-zinc-900" data-action="add-playlist" data-track-id="${escapeHtml(track.id)}">Add</button>
                    <button type="button" class="block w-full rounded-md px-3 py-2 text-left text-sm hover:bg-zinc-900" data-action="play-next" data-track-id="${escapeHtml(track.id)}">Play next</button>
                    <button type="button" class="block w-full rounded-md px-3 py-2 text-left text-sm hover:bg-zinc-900" data-action="add-queue" data-track-id="${escapeHtml(track.id)}">Queue</button>
                    <button type="button" class="remove-button hidden w-full rounded-md px-3 py-2 text-left text-sm hover:bg-zinc-900" data-action="remove-playlist" data-track-id="${escapeHtml(track.id)}">Remove</button>
                    <button type="button" class="block w-full rounded-md px-3 py-2 text-left text-sm hover:bg-zinc-900" data-action="edit-metadata" data-track-id="${escapeHtml(track.id)}">Edit metadata</button>
                    <button type="button" class="block w-full rounded-md px-3 py-2 text-left text-sm hover:bg-zinc-900" data-action="derive-metadata" data-track-id="${escapeHtml(track.id)}">Auto-fill tags</button>
                    <button type="button" class="block w-full rounded-md px-3 py-2 text-left text-sm hover:bg-zinc-900" data-action="set-track-cover" data-track-id="${escapeHtml(track.id)}">Set cover</button>
                    <button type="button" class="block w-full rounded-md px-3 py-2 text-left text-sm hover:bg-zinc-900" data-action="edit-lrc" data-track-id="${escapeHtml(track.id)}">Edit LRC</button>
                  </div>
                </div>
              </div>
            </div>
          </article>
        `;
      }

      function setActiveNavigation() {
        document.querySelectorAll(".view-button, .playlist-button, .folder-button").forEach((button) => {
          button.classList.remove("bg-zinc-100", "text-black");
        });

        if (currentView === "library") {
          document.getElementById("view-home")?.classList.remove("bg-zinc-100", "text-black");
          document.getElementById("view-library")?.classList.add("bg-zinc-100", "text-black");
        } else if (currentView === "home") {
          document.getElementById("view-home")?.classList.add("bg-zinc-100", "text-black");
        } else if (currentView === "settings") {
          document.getElementById("view-settings")?.classList.add("bg-zinc-100", "text-black");
        } else if (currentView === "liked") {
          document.getElementById("view-liked")?.classList.add("bg-zinc-100", "text-black");
        } else if (currentView === "folder") {
          document.querySelector(`[data-folder="${CSS.escape(currentFolder)}"]`)?.classList.add("bg-zinc-100", "text-black");
        } else {
          document.querySelector(`[data-playlist-id="${CSS.escape(currentPlaylistId)}"]`)?.classList.add("bg-zinc-100", "text-black");
        }
      }

      function updateTrackButtons() {
        document.querySelectorAll(".track-card").forEach((card) => {
          const id = card.dataset.id;
          const liked = likedIdSet.has(id);
          const likeButton = card.querySelector(".like-button");
          const addButton = card.querySelector(".add-button");
          const removeButton = card.querySelector(".remove-button");
          const checkbox = card.querySelector(".track-select");

          likeButton.textContent = liked ? "Liked" : "Like";
          likeButton.classList.toggle("bg-zinc-100", liked);
          likeButton.classList.toggle("text-black", liked);
          addButton.classList.toggle("hidden", currentView === "playlist");
          removeButton.classList.toggle("hidden", currentView !== "playlist");
          if (checkbox) checkbox.checked = selectedTrackIds.has(id);
          card.classList.toggle("ring-2", selectedTrackIds.has(id) || currentTrackId === id);
          card.classList.toggle("ring-zinc-100/30", selectedTrackIds.has(id) || currentTrackId === id);
        });
        updateBatchBar();
      }

      function updateBatchBar() {
        if (!batchBar || !batchCount) return;
        batchBar.classList.toggle("hidden", selectedTrackIds.size === 0);
        batchCount.textContent = `${selectedTrackIds.size} selected`;
      }

      function selectedTracks() {
        return Array.from(selectedTrackIds)
          .map((id) => libraryPlayable.find((track) => track.id === id))
          .filter(Boolean);
      }

      function selectVisibleTracks() {
        filteredTracks.forEach((track) => selectedTrackIds.add(track.id));
        updateTrackButtons();
        showToast(`${selectedTrackIds.size} tracks selected`);
      }

      function clearSelection() {
        selectedTrackIds.clear();
        updateTrackButtons();
      }

      function setTrackSelected(trackId, selected) {
        if (selected) selectedTrackIds.add(String(trackId));
        else selectedTrackIds.delete(String(trackId));
        updateTrackButtons();
      }

      async function batchLike(shouldLike) {
        const ids = Array.from(selectedTrackIds);
        await Promise.all(ids.map((id) => apiFetch(`/api/likes/${id}`, { method: shouldLike ? "POST" : "DELETE" })));
        await refreshState();
        showToast(shouldLike ? "Selected tracks liked" : "Selected tracks unliked");
      }

      async function batchAddToPlaylist() {
        const ids = Array.from(selectedTrackIds);
        await Promise.all(ids.map((id) => apiFetch(`/api/playlists/${currentPlaylistId}/tracks/${id}`, { method: "POST" })));
        await refreshState();
        showToast("Selected tracks added to playlist");
      }

      function batchPlayNext() {
        const tracks = selectedTracks();
        manualQueue = [...tracks, ...manualQueue.filter((track) => !selectedTrackIds.has(track.id))];
        renderQueue();
        showToast("Selected tracks will play next");
      }

      function batchQueue() {
        manualQueue.push(...selectedTracks());
        renderQueue();
        showToast("Selected tracks queued");
      }

      function metadataTargetIds() {
        return metadataMode.value === "batch" ? metadataBatchIds : [metadataTrackId.value].filter(Boolean);
      }

      function openBatchMetadataEditor() {
        const ids = Array.from(selectedTrackIds);
        if (!ids.length) {
          showToast("Select tracks first", "error");
          return;
        }
        metadataBatchIds = ids;
        metadataMode.value = "batch";
        metadataTrackId.value = "";
        metadataTitle.value = "";
        metadataTitle.disabled = ids.length > 1;
        metadataTitle.placeholder = ids.length > 1 ? "Title is per-track; use auto-fill instead" : "";
        metadataArtist.value = "";
        metadataAlbum.value = "";
        metadataScope.textContent = `${ids.length} selected tracks. Blank fields will not be changed.`;
        metadataPath.textContent = "Batch editing writes tags directly to the selected audio files.";
        metadataError.classList.add("hidden");
        metadataModal.classList.remove("hidden");
        metadataModal.classList.add("flex");
      }

      function setActivePlayable(ids) {
        const allowed = new Set(ids.map(String));
        activePlayable = libraryPlayable.filter((track) => allowed.has(track.id));
        currentIndex = activePlayable.findIndex((track) => track.id === currentTrackId);
      }

      function showHome() {
        currentView = "home";
        activePlayable = libraryPlayable;
        currentIndex = activePlayable.findIndex((track) => track.id === currentTrackId);
        viewTitle.textContent = "Home";
        viewSubtitle.textContent = `${folders.length} folders, ${playlistMap.size} playlists, ${libraryPlayable.length} tracks`;
        deletePlaylistButton.classList.add("hidden");
        coverUploadButton.classList.add("hidden");
        exportPlaylistButton.classList.add("hidden");
        homeContent.classList.remove("hidden");
        detailContent.classList.add("hidden");
        hideTrackGrid();
        emptyState?.classList.add("hidden");
        setActiveNavigation();
        renderHome();
      }

      async function showSettings() {
        currentView = "settings";
        activePlayable = libraryPlayable;
        currentIndex = activePlayable.findIndex((track) => track.id === currentTrackId);
        viewTitle.textContent = "Settings";
        viewSubtitle.textContent = "Storage, backup, cache, and local security";
        deletePlaylistButton.classList.add("hidden");
        coverUploadButton.classList.add("hidden");
        exportPlaylistButton.classList.add("hidden");
        homeContent.classList.add("hidden");
        hideTrackGrid();
        emptyState?.classList.add("hidden");
        detailContent.classList.remove("hidden");
        detailContent.innerHTML = `
          <div class="grid gap-4 xl:grid-cols-2">
            <section class="rounded-xl border border-zinc-800 bg-zinc-950/70 p-5">
              <div class="text-xs font-semibold uppercase tracking-widest text-zinc-500">Playback</div>
              <h3 class="mt-2 text-xl font-semibold">Transitions</h3>
              <label class="mt-4 flex items-center gap-2 text-sm text-zinc-300">
                <input id="crossfadeToggle" type="checkbox" class="accent-zinc-100" ${crossfadeEnabled ? "checked" : ""} />
                Crossfade between tracks (${CROSSFADE_SECONDS}s)
              </label>
              <p class="mt-2 text-sm text-zinc-500">Fades the current track out while the next one fades in, instead of a hard cut. Applies to automatic track changes, not manual skip/previous.</p>
              <div class="mt-5 border-t border-zinc-800 pt-4">
                <h4 class="text-sm font-semibold text-zinc-300">Keyboard shortcuts</h4>
                <div class="mt-2 grid grid-cols-2 gap-x-4 gap-y-1 text-xs text-zinc-500">
                  <div><kbd class="rounded bg-zinc-800 px-1.5 py-0.5 text-zinc-300">Space</kbd> Play / pause</div>
                  <div><kbd class="rounded bg-zinc-800 px-1.5 py-0.5 text-zinc-300">&larr; / &rarr;</kbd> Seek 5s</div>
                  <div><kbd class="rounded bg-zinc-800 px-1.5 py-0.5 text-zinc-300">Shift+&larr;/&rarr;</kbd> Prev / next track</div>
                  <div><kbd class="rounded bg-zinc-800 px-1.5 py-0.5 text-zinc-300">&uarr; / &darr;</kbd> Volume</div>
                  <div><kbd class="rounded bg-zinc-800 px-1.5 py-0.5 text-zinc-300">M</kbd> Mute</div>
                  <div><kbd class="rounded bg-zinc-800 px-1.5 py-0.5 text-zinc-300">L</kbd> Like current track</div>
                  <div><kbd class="rounded bg-zinc-800 px-1.5 py-0.5 text-zinc-300">S</kbd> Shuffle</div>
                  <div><kbd class="rounded bg-zinc-800 px-1.5 py-0.5 text-zinc-300">R</kbd> Repeat mode</div>
                </div>
                <p class="mt-2 text-xs text-zinc-600">Disabled while typing in a text field or with a dialog open.</p>
              </div>
            </section>

            <section class="rounded-xl border border-zinc-800 bg-zinc-950/70 p-5">
              <div class="text-xs font-semibold uppercase tracking-widest text-zinc-500">Library</div>
              <h3 class="mt-2 text-xl font-semibold">Local storage</h3>
              <div id="settingsStorage" class="mt-4 space-y-3 text-sm text-zinc-300">
                <div class="text-zinc-500">Loading settings...</div>
              </div>
              <div class="mt-5 flex flex-wrap gap-2">
                <button type="button" data-action="refresh-library" class="rounded-lg border border-zinc-700 px-3 py-2 text-sm hover:bg-zinc-900">Rescan library</button>
                <button type="button" data-action="choose-background" class="rounded-lg border border-zinc-700 px-3 py-2 text-sm hover:bg-zinc-900">Set background</button>
              </div>
            </section>

            <section class="rounded-xl border border-zinc-800 bg-zinc-950/70 p-5">
              <div class="text-xs font-semibold uppercase tracking-widest text-zinc-500">Backups</div>
              <h3 class="mt-2 text-xl font-semibold">Export and restore</h3>
              <p class="mt-3 text-sm leading-6 text-zinc-400">Backups include playlists, likes, listening history, internet artist cache, and uploaded artwork. Your music files stay in your music folder.</p>
              <div class="mt-5 flex flex-wrap gap-2">
                <button type="button" data-action="export-backup" class="rounded-lg border border-zinc-700 px-3 py-2 text-sm hover:bg-zinc-900">Export backup</button>
                <button type="button" data-action="choose-restore" class="rounded-lg border border-zinc-700 px-3 py-2 text-sm hover:bg-zinc-900">Restore backup</button>
              </div>
            </section>

            <section class="rounded-xl border border-zinc-800 bg-zinc-950/70 p-5">
              <div class="text-xs font-semibold uppercase tracking-widest text-zinc-500">Internet metadata</div>
              <h3 class="mt-2 text-xl font-semibold">Artist info cache</h3>
              <p id="settingsArtistCache" class="mt-3 text-sm leading-6 text-zinc-400">Loading cache details...</p>
              <button type="button" data-action="clear-artist-cache" class="mt-5 rounded-lg border border-zinc-700 px-3 py-2 text-sm hover:bg-zinc-900">Clear artist info cache</button>
            </section>

            <section class="rounded-xl border border-zinc-800 bg-zinc-950/70 p-5">
              <div class="text-xs font-semibold uppercase tracking-widest text-zinc-500">Security</div>
              <h3 class="mt-2 text-xl font-semibold">Local app hardening</h3>
              <div id="settingsSecurity" class="mt-4 space-y-3 text-sm text-zinc-300">
                <div class="text-zinc-500">Checking current setup...</div>
              </div>
            </section>

            <section class="rounded-xl border border-zinc-800 bg-zinc-950/70 p-5">
              <div class="text-xs font-semibold uppercase tracking-widest text-zinc-500">Account</div>
              <h3 class="mt-2 text-xl font-semibold">Change your password</h3>
              <div id="settingsAccountInfo" class="mt-3 text-sm text-zinc-400"></div>
              <form id="changePasswordForm" class="mt-4 space-y-3">
                <input type="password" name="current_password" placeholder="Current password" autocomplete="current-password" required class="w-full rounded-lg border border-zinc-700 bg-black px-3 py-2 text-sm outline-none focus:border-zinc-300" />
                <input type="password" name="new_password" placeholder="New password (min 8 characters)" autocomplete="new-password" required minlength="8" class="w-full rounded-lg border border-zinc-700 bg-black px-3 py-2 text-sm outline-none focus:border-zinc-300" />
                <button type="submit" class="rounded-lg border border-zinc-700 px-3 py-2 text-sm hover:bg-zinc-900">Update password</button>
              </form>
            </section>

            <section id="settingsUsersSection" class="hidden rounded-xl border border-zinc-800 bg-zinc-950/70 p-5 xl:col-span-2">
              <div class="text-xs font-semibold uppercase tracking-widest text-zinc-500">Admin</div>
              <h3 class="mt-2 text-xl font-semibold">Users</h3>
              <div id="settingsUsersList" class="mt-4 space-y-2 text-sm"></div>
              <form id="createUserForm" class="mt-5 flex flex-wrap items-end gap-2">
                <div>
                  <label class="mb-1 block text-xs text-zinc-500">Username</label>
                  <input type="text" name="username" required minlength="3" maxlength="32" pattern="[a-zA-Z0-9_.\-]+" class="rounded-lg border border-zinc-700 bg-black px-3 py-2 text-sm outline-none focus:border-zinc-300" />
                </div>
                <div>
                  <label class="mb-1 block text-xs text-zinc-500">Password</label>
                  <input type="password" name="password" required minlength="8" autocomplete="new-password" class="rounded-lg border border-zinc-700 bg-black px-3 py-2 text-sm outline-none focus:border-zinc-300" />
                </div>
                <label class="mb-2 flex items-center gap-2 text-sm text-zinc-400">
                  <input type="checkbox" name="is_admin" class="accent-zinc-100" />
                  Admin
                </label>
                <button type="submit" class="mb-0 rounded-lg border border-zinc-700 px-3 py-2 text-sm hover:bg-zinc-900">Add user</button>
              </form>
            </section>
          </div>
        `;
        setActiveNavigation();
        await renderSettingsDetails();
        document.getElementById("changePasswordForm")?.addEventListener("submit", handleChangePasswordSubmit);
        document.getElementById("createUserForm")?.addEventListener("submit", handleCreateUserSubmit);
        document.getElementById("crossfadeToggle")?.addEventListener("change", (event) => {
          crossfadeEnabled = event.target.checked;
          localStorage.setItem("crossfadeEnabled", crossfadeEnabled ? "1" : "0");
          if (!crossfadeEnabled) cancelCrossfadeIfActive();
        });
      }

      function settingRow(label, value) {
        return `
          <div>
            <div class="text-xs uppercase tracking-widest text-zinc-500">${escapeHtml(label)}</div>
            <div class="mt-1 break-all rounded-lg border border-zinc-800 bg-black/60 px-3 py-2">${escapeHtml(value || "-")}</div>
          </div>
        `;
      }

      function securityPill(ok, text) {
        return `<div class="rounded-lg border ${ok ? "border-emerald-900 bg-emerald-950/30 text-emerald-100" : "border-amber-900 bg-amber-950/30 text-amber-100"} px-3 py-2">${escapeHtml(text)}</div>`;
      }

      async function renderSettingsDetails() {
        try {
          const settings = await fetchJson("/api/settings");
          document.getElementById("settingsStorage").innerHTML = [
            settingRow("Music folder", settings.music_dir),
            settingRow("Playlist state", settings.playlist_file),
            settingRow("Artwork folder", settings.artwork_dir),
            settingRow("Library size", `${settings.track_count} tracks, ${settings.folder_count} folders, ${settings.playlist_count} playlists`),
            settingRow("Upload limit", `${settings.max_upload_mb} MB`),
          ].join("");
          document.getElementById("settingsArtistCache").textContent = `${settings.artist_info_cache_count} artist info entr${settings.artist_info_cache_count === 1 ? "y is" : "ies are"} cached locally. Clearing it does not affect your music files or playlists.`;
          document.getElementById("settingsSecurity").innerHTML = [
            securityPill(!settings.using_dev_secret, settings.using_dev_secret ? "Development SECRET_KEY is still in use. Change it in run.ps1 before sharing the app on a network." : "SECRET_KEY is not the default development value."),
            securityPill(!settings.using_default_admin, settings.using_default_admin ? "Admin username is still the default. Use a private username for network use." : "Admin username is customized."),
            securityPill(settings.flask_host === "127.0.0.1", settings.flask_host === "127.0.0.1" ? "Server binds to localhost by default." : `Server host is ${settings.flask_host}; make sure your network is trusted.`),
            securityPill(settings.https_cookie_required, settings.https_cookie_required ? "Secure cookies are required for HTTPS deployments." : "SESSION_COOKIE_SECURE is off, which is acceptable for local HTTP but not for HTTPS/network exposure."),
            securityPill(true, "CSRF protection is enabled for POST, PUT, PATCH, and DELETE requests."),
            securityPill(true, "Browser hardening headers are enabled: frame blocking, nosniff, same-origin referrers, and limited permissions."),
          ].join("");

          document.getElementById("settingsAccountInfo").textContent = `Signed in as ${settings.username}${settings.is_admin ? " (admin)" : ""}.`;

          const usersSection = document.getElementById("settingsUsersSection");
          if (settings.is_admin) {
            usersSection.classList.remove("hidden");
            await renderUsersList(settings.username);
          } else {
            usersSection.classList.add("hidden");
          }
        } catch (error) {
          showToast("Could not load settings", "error");
        }
      }

      async function renderUsersList(myUsername) {
        const container = document.getElementById("settingsUsersList");
        if (!container) return;
        try {
          const users = await fetchJson("/api/users");
          container.innerHTML = users
            .map((u) => {
              const isSelf = u.username === myUsername;
              return `
                <div class="flex items-center justify-between gap-3 rounded-lg border border-zinc-800 bg-black/50 px-3 py-2">
                  <div class="min-w-0">
                    <span class="font-semibold">${escapeHtml(u.username)}</span>
                    ${u.is_admin ? '<span class="ml-2 rounded bg-zinc-800 px-1.5 py-0.5 text-xs text-zinc-300">admin</span>' : ""}
                    ${isSelf ? '<span class="ml-2 text-xs text-zinc-500">(you)</span>' : ""}
                  </div>
                  ${isSelf ? "" : `<button type="button" data-action="delete-user" data-username="${escapeHtml(u.username)}" class="shrink-0 rounded-lg border border-red-900/70 bg-red-950/40 px-2 py-1 text-xs text-red-100 hover:bg-red-950/70">Delete</button>`}
                </div>
              `;
            })
            .join("");
        } catch (error) {
          container.innerHTML = `<div class="text-zinc-500">Could not load users.</div>`;
        }
      }

      async function handleChangePasswordSubmit(event) {
        event.preventDefault();
        const form = event.target;
        const currentPassword = form.current_password.value;
        const newPassword = form.new_password.value;
        try {
          await fetchJson("/api/users/me/password", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ current_password: currentPassword, new_password: newPassword }),
          });
          form.reset();
          showToast("Password updated");
        } catch (error) {
          showToast("Could not update password - check your current password", "error");
        }
      }

      async function handleCreateUserSubmit(event) {
        event.preventDefault();
        const form = event.target;
        const username = form.username.value.trim();
        const password = form.password.value;
        const isAdmin = form.is_admin.checked;
        try {
          const data = await fetchJson("/api/users", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ username, password, is_admin: isAdmin }),
          });
          form.reset();
          showToast(`User "${data.user.username}" created`);
          const settings = await fetchJson("/api/settings");
          await renderUsersList(settings.username);
        } catch (error) {
          showToast("Could not create user", "error");
        }
      }

      async function handleDeleteUser(username) {
        if (!window.confirm(`Delete user "${username}"? Their likes, playlists, and history will be permanently removed.`)) return;
        try {
          await fetchJson(`/api/users/${encodeURIComponent(username)}`, { method: "DELETE" });
          showToast("User deleted");
          const settings = await fetchJson("/api/settings");
          await renderUsersList(settings.username);
        } catch (error) {
          showToast("Could not delete user", "error");
        }
      }

      function showLibrary() {
        currentView = "library";
        activePlayable = libraryPlayable;
        currentIndex = activePlayable.findIndex((track) => track.id === currentTrackId);
        const selected = playlistMap.get(currentPlaylistId);
        viewTitle.textContent = "All tracks";
        viewSubtitle.textContent = `${libraryPlayable.length} tracks from your local folder. Add target: ${selected?.name || "My Playlist"}`;
        deletePlaylistButton.classList.add("hidden");
        coverUploadButton.classList.add("hidden");
        exportPlaylistButton.classList.add("hidden");
        homeContent.classList.add("hidden");
        detailContent.classList.add("hidden");
        showTrackGrid();
        setActiveNavigation();
        updateTrackButtons();
        applyFilter();
      }

      function showFolder(folderName) {
        currentView = "folder";
        currentFolder = String(folderName);
        const realFolder = currentFolder === "(root)" ? "" : currentFolder;
        activePlayable = libraryPlayable.filter((track) => track.folder === realFolder);
        currentIndex = activePlayable.findIndex((track) => track.id === currentTrackId);
        viewTitle.textContent = currentFolder;
        viewSubtitle.textContent = `${activePlayable.length} tracks in this folder`;
        deletePlaylistButton.classList.add("hidden");
        coverUploadButton.classList.remove("hidden");
        exportPlaylistButton.classList.add("hidden");
        homeContent.classList.add("hidden");
        detailContent.classList.add("hidden");
        showTrackGrid();
        setActiveNavigation();
        updateTrackButtons();
        applyFilter();
      }

      function showLiked() {
        currentView = "liked";
        setActivePlayable(Array.from(likedIdSet));
        viewTitle.textContent = "Liked songs";
        viewSubtitle.textContent = `${likedIdSet.size} liked tracks`;
        deletePlaylistButton.classList.add("hidden");
        coverUploadButton.classList.add("hidden");
        exportPlaylistButton.classList.add("hidden");
        homeContent.classList.add("hidden");
        detailContent.classList.add("hidden");
        showTrackGrid();
        setActiveNavigation();
        updateTrackButtons();
        applyFilter();
      }

      function showPlaylist(playlistId) {
        const selected = playlistMap.get(String(playlistId));
        if (!selected) return;
        currentView = "playlist";
        currentPlaylistId = String(selected.id);
        setActivePlayable(selected.tracks || []);
        viewTitle.textContent = selected.name;
        viewSubtitle.textContent = `${selected.count} tracks`;
        deletePlaylistButton.classList.toggle("hidden", currentPlaylistId === "default");
        coverUploadButton.classList.remove("hidden");
        exportPlaylistButton.classList.remove("hidden");
        homeContent.classList.add("hidden");
        detailContent.classList.add("hidden");
        showTrackGrid();
        setActiveNavigation();
        updateTrackButtons();
        applyFilter();
      }

      function showSmartTracks(title, subtitle, items) {
        currentView = "smart";
        activePlayable = items;
        currentIndex = activePlayable.findIndex((track) => track.id === currentTrackId);
        viewTitle.textContent = title;
        viewSubtitle.textContent = subtitle;
        deletePlaylistButton.classList.add("hidden");
        coverUploadButton.classList.add("hidden");
        exportPlaylistButton.classList.add("hidden");
        homeContent.classList.add("hidden");
        detailContent.classList.add("hidden");
        showTrackGrid();
        setActiveNavigation();
        updateTrackButtons();
        applyFilter();
      }

      function showSmartRandom() {
        const shuffled = [...libraryPlayable].sort(() => Math.random() - 0.5).slice(0, 25);
        showSmartTracks("Random 25", "A shuffled quick mix from your library", shuffled);
      }

      function showSmartContinue() {
        const items = continueCandidates();
        showSmartTracks("Continue Listening", `${items.length} unfinished tracks`, items);
        currentView = "smart_continue";
      }

      function showSmartRecently() {
        const items = tracksFromHistory(historyData.recent).slice(0, 50);
        showSmartTracks("Recently Played", `${items.length} recently played tracks`, items);
      }

      function showSmartMostPlayed() {
        const items = tracksFromHistory(historyData.most_played).slice(0, 50);
        showSmartTracks("Most Played", `${items.length} played tracks`, items);
      }

      function showSmartMissingCovers() {
        const items = libraryPlayable.filter((track) => track.coverUrl.includes("/cover/"));
        showSmartTracks("Missing Covers", `${items.length} tracks using embedded/default art`, items);
      }

      function hasWeakMetadata(track) {
        const weakValues = new Set(["", "unknown artist", "unknown album", "untitled"]);
        return weakValues.has(String(track.artist || "").trim().toLowerCase())
          || weakValues.has(String(track.album || "").trim().toLowerCase())
          || weakValues.has(String(track.title || "").trim().toLowerCase())
          || track.title === track.relpath?.split("/").pop()?.replace(/\.[^.]+$/, "");
      }

      function metadataCleanupCandidates() {
        return libraryPlayable.filter(hasWeakMetadata);
      }

      function showSmartMetadataCleanup() {
        const items = metadataCleanupCandidates();
        showSmartTracks("Metadata Cleanup", `${items.length} tracks with weak or file-name-like tags`, items);
      }

      function showSmartRoot() {
        const items = libraryPlayable.filter((track) => !track.folder);
        showSmartTracks("Root Folder", `${items.length} tracks outside subfolders`, items);
      }

      function showSmartLongTitles() {
        const items = libraryPlayable.filter((track) => track.title.length > 40);
        showSmartTracks("Long Titles", `${items.length} tracks`, items);
      }

      function showArtist(artist) {
        const items = libraryPlayable.filter((track) => track.artist === artist);
        const albums = Array.from(new Set(items.map((track) => track.album))).sort();
        currentView = "artist";
        activePlayable = items;
        currentIndex = activePlayable.findIndex((track) => track.id === currentTrackId);
        viewTitle.textContent = artist;
        viewSubtitle.textContent = `${items.length} tracks, ${albums.length} albums`;
        deletePlaylistButton.classList.add("hidden");
        coverUploadButton.classList.add("hidden");
        exportPlaylistButton.classList.add("hidden");
        homeContent.classList.add("hidden");
        showTrackGrid();
        detailContent.classList.remove("hidden");
        detailContent.innerHTML = `
          <div class="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
            <div>
              <div class="text-sm uppercase tracking-widest text-zinc-500">Artist</div>
              <h3 class="mt-1 text-3xl font-bold">${escapeHtml(artist)}</h3>
              <p class="mt-2 text-zinc-400">${items.length} tracks across ${albums.length} albums in your local library.</p>
            </div>
            <div class="flex gap-2">
              <button type="button" data-action="play-any-track" data-track-id="${escapeHtml(items[0]?.id || "")}" class="rounded-lg bg-zinc-100 px-4 py-2 font-semibold text-black hover:bg-white">Play</button>
              <button type="button" data-action="shuffle-active" data-title="Artist Shuffle" data-subtitle="${escapeHtml(artist)}" class="rounded-lg border border-zinc-700 px-4 py-2 hover:bg-zinc-900">Shuffle</button>
            </div>
          </div>
          <div class="mt-5 flex flex-wrap gap-2">
            ${albums.map((album) => `<button type="button" data-action="show-album" data-album="${escapeHtml(album)}" class="rounded-full border border-zinc-700 px-3 py-1 text-sm hover:bg-zinc-900">${escapeHtml(album)}</button>`).join("")}
          </div>
          <div id="artistInfoPanel" class="mt-5"></div>
        `;
        const cachedInfo = artistInfoMemory.get(String(artist).toLowerCase());
        renderArtistInfoPanel(artist, cachedInfo, !cachedInfo);
        loadArtistInfo(artist, { target: "artist-page" });
        setActiveNavigation();
        updateTrackButtons();
        applyFilter();
      }

      function showAllArtists() {
        currentView = "artists";
        activePlayable = libraryPlayable;
        viewTitle.textContent = "Artists";
        const artists = artistSummaries();
        viewSubtitle.textContent = `${artists.length} artists`;
        deletePlaylistButton.classList.add("hidden");
        coverUploadButton.classList.add("hidden");
        exportPlaylistButton.classList.add("hidden");
        homeContent.classList.add("hidden");
        hideTrackGrid();
        emptyState?.classList.add("hidden");
        detailContent.classList.remove("hidden");
        detailContent.innerHTML = `
          <div class="mb-5 flex items-end justify-between gap-3">
            <div>
              <div class="text-sm uppercase tracking-widest text-zinc-500">Library</div>
              <h3 class="mt-1 text-3xl font-bold">Artists</h3>
            </div>
          </div>
          <div class="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5">
            ${artists.map(artistCard).join("")}
          </div>
        `;
        setActiveNavigation();
      }

      function showAlbum(album) {
        const items = libraryPlayable.filter((track) => track.album === album);
        const artists = Array.from(new Set(items.map((track) => track.artist))).sort();
        const cover = items[0]?.coverUrl || "";
        currentView = "album";
        activePlayable = items;
        currentIndex = activePlayable.findIndex((track) => track.id === currentTrackId);
        viewTitle.textContent = album;
        viewSubtitle.textContent = `${items.length} tracks`;
        deletePlaylistButton.classList.add("hidden");
        coverUploadButton.classList.add("hidden");
        exportPlaylistButton.classList.add("hidden");
        homeContent.classList.add("hidden");
        showTrackGrid();
        detailContent.classList.remove("hidden");
        detailContent.innerHTML = `
          <div class="flex flex-col gap-5 sm:flex-row">
            <img src="${escapeHtml(cover)}" class="h-40 w-40 rounded-xl border border-zinc-800 object-cover" alt="" onerror="this.style.display='none'" />
            <div class="min-w-0 flex-1">
              <div class="text-sm uppercase tracking-widest text-zinc-500">Album</div>
              <h3 class="mt-1 text-3xl font-bold">${escapeHtml(album)}</h3>
              <p class="mt-2 text-zinc-400">${escapeHtml(artists.join(", "))} - ${items.length} tracks</p>
              <div class="mt-4 flex flex-wrap gap-2">
                <button type="button" data-action="play-any-track" data-track-id="${escapeHtml(items[0]?.id || "")}" class="rounded-lg bg-zinc-100 px-4 py-2 font-semibold text-black hover:bg-white">Play album</button>
                <button type="button" data-action="shuffle-active" data-title="Album Shuffle" data-subtitle="${escapeHtml(album)}" class="rounded-lg border border-zinc-700 px-4 py-2 hover:bg-zinc-900">Shuffle</button>
                <button type="button" data-action="queue-active" class="rounded-lg border border-zinc-700 px-4 py-2 hover:bg-zinc-900">Queue album</button>
              </div>
            </div>
          </div>
        `;
        setActiveNavigation();
        updateTrackButtons();
        applyFilter();
      }

      function showAllAlbums() {
        currentView = "albums";
        activePlayable = libraryPlayable;
        viewTitle.textContent = "Albums";
        const albums = albumSummaries();
        viewSubtitle.textContent = `${albums.length} albums`;
        deletePlaylistButton.classList.add("hidden");
        coverUploadButton.classList.add("hidden");
        exportPlaylistButton.classList.add("hidden");
        homeContent.classList.add("hidden");
        hideTrackGrid();
        emptyState?.classList.add("hidden");
        detailContent.classList.remove("hidden");
        detailContent.innerHTML = `
          <div class="mb-5 flex items-end justify-between gap-3">
            <div>
              <div class="text-sm uppercase tracking-widest text-zinc-500">Library</div>
              <h3 class="mt-1 text-3xl font-bold">Albums</h3>
            </div>
          </div>
          <div class="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5">
            ${albums.map(albumCard).join("")}
          </div>
        `;
        setActiveNavigation();
      }

      function queueAlbum() {
        manualQueue.push(...activePlayable);
        renderQueue();
        showToast("Album queued");
      }

      function tracksFromHistory(items) {
        return (items || [])
          .map((item) => libraryPlayable.find((track) => track.id === String(item.id)))
          .filter(Boolean);
      }

      function continueCandidates() {
        return Object.entries(historyData.tracks || {})
          .filter(([, item]) => Number(item.last_position || 0) > 15 && !item.completed)
          .sort((a, b) => String(b[1].position_updated_at || "").localeCompare(String(a[1].position_updated_at || "")))
          .map(([trackId]) => libraryPlayable.find((track) => track.id === String(trackId)))
          .filter(Boolean);
      }

      function visibleIdsForCurrentView() {
        if (currentView === "home" || currentView === "library") return new Set(libraryPlayable.map((track) => track.id));
        if (currentView === "liked") return likedIdSet;
        if (currentView === "folder") {
          const realFolder = currentFolder === "(root)" ? "" : currentFolder;
          return new Set(libraryPlayable.filter((track) => track.folder === realFolder).map((track) => track.id));
        }
        if (["smart", "smart_continue", "artist", "album"].includes(currentView)) return new Set(activePlayable.map((track) => track.id));
        const selected = playlistMap.get(currentPlaylistId);
        return new Set((selected?.tracks || []).map(String));
      }

      function applyFilter() {
        if (!grid || !gridScroller) return;
        if (gridScroller.classList.contains("hidden")) {
          filteredTracks = [];
          gridStartIndex = -1;
          gridEndIndex = -1;
          grid.innerHTML = "";
          gridScroller.style.height = "0px";
          emptyState?.classList.add("hidden");
          updateGridControls();
          return;
        }
        const query = (search?.value || "").trim().toLowerCase();
        const matchesQuery = (track) => !query || `${track.title} ${track.artist} ${track.album}`.toLowerCase().includes(query);

        if (currentView === "playlist") {
          // Playlists have a meaningful custom order (drag-to-reorder) - show
          // them in that order, not library order like every other view.
          const selected = playlistMap.get(currentPlaylistId);
          const byId = new Map(libraryPlayable.map((track) => [track.id, track]));
          filteredTracks = (selected?.tracks || [])
            .map((id) => byId.get(String(id)))
            .filter(Boolean)
            .filter(matchesQuery);
        } else {
          const visibleIds = visibleIdsForCurrentView();
          filteredTracks = libraryPlayable.filter((track) => visibleIds.has(track.id) && matchesQuery(track));
        }
        gridStartIndex = -1;
        gridEndIndex = -1;
        renderVirtualizedGrid();
        emptyState?.classList.toggle("hidden", filteredTracks.length !== 0);
      }

      function computeGridColumns() {
        const style = getComputedStyle(grid);
        const gap = parseFloat(style.columnGap) || 0;
        const containerWidth = gridScroller.clientWidth || grid.clientWidth || GRID_MIN_CARD_WIDTH;
        return Math.max(1, Math.floor((containerWidth + gap) / (GRID_MIN_CARD_WIDTH + gap)));
      }

      function renderVirtualizedGrid() {
        if (!grid || !gridScroller) return;
        const total = filteredTracks.length;
        if (total === 0) {
          gridStartIndex = -1;
          gridEndIndex = -1;
          grid.innerHTML = "";
          grid.style.transform = "translateY(0px)";
          gridScroller.style.height = "0px";
          updateGridControls();
          return;
        }

        gridColumns = computeGridColumns();
        const rowHeight = gridRowHeight || GRID_FALLBACK_ROW_HEIGHT;
        const totalRows = Math.ceil(total / gridColumns);
        gridScroller.style.height = `${totalRows * rowHeight}px`;

        const scrollTop = window.scrollY || document.documentElement.scrollTop || 0;
        const containerTop = gridScroller.getBoundingClientRect().top + scrollTop;
        const visibleTop = Math.max(0, scrollTop - containerTop);

        const startRow = Math.max(0, Math.floor(visibleTop / rowHeight) - GRID_OVERSCAN_ROWS);
        const endRow = Math.min(totalRows, Math.ceil((visibleTop + window.innerHeight) / rowHeight) + GRID_OVERSCAN_ROWS);
        const startIndex = startRow * gridColumns;
        const endIndex = Math.min(total, endRow * gridColumns);

        if (startIndex !== gridStartIndex || endIndex !== gridEndIndex) {
          gridStartIndex = startIndex;
          gridEndIndex = endIndex;
          grid.innerHTML = filteredTracks.slice(startIndex, endIndex).map(trackCardHtml).join("");
          grid.style.transform = `translateY(${startRow * rowHeight}px)`;
          updateTrackButtons();

          if (!gridRowHeight) {
            requestAnimationFrame(() => {
              const sample = grid.querySelector(".track-card");
              if (!sample) return;
              const rowGap = parseFloat(getComputedStyle(grid).rowGap) || 0;
              gridRowHeight = sample.getBoundingClientRect().height + rowGap;
              gridStartIndex = -1;
              gridEndIndex = -1;
              renderVirtualizedGrid();
            });
          }
        }
        updateGridControls();
      }

      function scheduleVirtualRender() {
        if (gridRenderScheduled) return;
        gridRenderScheduled = true;
        requestAnimationFrame(() => {
          gridRenderScheduled = false;
          if (!gridScroller.classList.contains("hidden")) renderVirtualizedGrid();
        });
      }

      window.addEventListener("scroll", scheduleVirtualRender, { passive: true });
      window.addEventListener("resize", scheduleVirtualRender);

      let dragSourceTrackId = null;

      function setupPlaylistDragReorder() {
        if (!grid) return;

        grid.addEventListener("dragstart", (event) => {
          const card = event.target.closest(".track-card[draggable='true']");
          if (!card) return;
          dragSourceTrackId = card.dataset.id;
          event.dataTransfer.effectAllowed = "move";
          event.dataTransfer.setData("text/plain", card.dataset.id);
        });

        grid.addEventListener("dragover", (event) => {
          if (!dragSourceTrackId) return;
          const card = event.target.closest(".track-card[draggable='true']");
          if (!card) return;
          event.preventDefault();
          event.dataTransfer.dropEffect = "move";
          if (card.dataset.id !== dragSourceTrackId) card.classList.add("ring-2", "ring-emerald-400/60");
        });

        grid.addEventListener("dragleave", (event) => {
          const card = event.target.closest(".track-card[draggable='true']");
          card?.classList.remove("ring-2", "ring-emerald-400/60");
        });

        grid.addEventListener("drop", async (event) => {
          const card = event.target.closest(".track-card[draggable='true']");
          if (!card || !dragSourceTrackId) return;
          event.preventDefault();
          card.classList.remove("ring-2", "ring-emerald-400/60");
          const targetTrackId = card.dataset.id;
          const sourceTrackId = dragSourceTrackId;
          dragSourceTrackId = null;
          if (targetTrackId === sourceTrackId) return;
          await reorderPlaylistTracks(sourceTrackId, targetTrackId);
        });

        grid.addEventListener("dragend", () => {
          dragSourceTrackId = null;
          grid.querySelectorAll(".track-card").forEach((el) => el.classList.remove("ring-2", "ring-emerald-400/60"));
        });
      }

      async function reorderPlaylistTracks(sourceTrackId, targetTrackId) {
        const selected = playlistMap.get(currentPlaylistId);
        if (!selected) return;
        const order = (selected.tracks || []).map(String);
        const fromIndex = order.indexOf(sourceTrackId);
        if (fromIndex === -1) return;
        order.splice(fromIndex, 1);
        const insertAt = order.indexOf(targetTrackId);
        order.splice(insertAt === -1 ? order.length : insertAt, 0, sourceTrackId);

        try {
          const data = await fetchJson(`/api/playlists/${currentPlaylistId}/order`, {
            method: "PUT",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ track_ids: order }),
          });
          selected.tracks = data.playlist.tracks;
          playlistMap.set(currentPlaylistId, selected);
          applyFilter();
        } catch (error) {
          showToast("Could not reorder playlist", "error");
        }
      }

      setupPlaylistDragReorder();

      function updateGridControls() {
        if (!gridControls || !gridSummary) return;
        const hasVisibleGrid = !gridScroller.classList.contains("hidden") && filteredTracks.length > 0;
        gridControls.classList.toggle("hidden", !hasVisibleGrid);
        gridControls.classList.toggle("flex", hasVisibleGrid);
        if (!hasVisibleGrid) return;
        gridSummary.textContent = `${filteredTracks.length} track${filteredTracks.length === 1 ? "" : "s"}`;
      }

      function hideTrackGrid() {
        gridScroller?.classList.add("hidden");
        if (grid) grid.innerHTML = "";
        if (gridScroller) gridScroller.style.height = "0px";
        filteredTracks = [];
        gridStartIndex = -1;
        gridEndIndex = -1;
        updateGridControls();
      }

      function showTrackGrid() {
        gridScroller?.classList.remove("hidden");
      }

      function scheduleFilter() {
        window.clearTimeout(filterTimer);
        filterTimer = window.setTimeout(applyFilter, 120);
      }

      async function toggleLike(trackId) {
        const liked = likedIdSet.has(String(trackId));
        await fetchJson(`/api/likes/${trackId}`, { method: liked ? "DELETE" : "POST" });
        await refreshState();
        if (currentView === "liked") showLiked();
        showToast(liked ? "Removed from liked songs" : "Added to liked songs");
      }

      async function createPlaylist() {
        const name = window.prompt("Playlist name");
        if (!name || !name.trim()) return;
        const result = await fetchJson("/api/playlists", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ name }),
        });
        await refreshState();
        showPlaylist(result.playlist.id);
        showToast("Playlist created");
      }

      async function deleteCurrentPlaylist() {
        if (currentPlaylistId === "default") return;
        const selected = playlistMap.get(currentPlaylistId);
        if (!selected || !window.confirm(`Delete "${selected.name}"?`)) return;
        await fetchJson(`/api/playlists/${currentPlaylistId}`, { method: "DELETE" });
        await refreshState();
        showLibrary();
        showToast("Playlist deleted");
      }

      function exportCurrentPlaylist() {
        if (currentView !== "playlist") return;
        const a = document.createElement("a");
        a.href = `/api/playlists/${currentPlaylistId}/export.m3u8`;
        a.rel = "noopener";
        document.body.appendChild(a);
        a.click();
        a.remove();
      }

      async function addTrackToActivePlaylist(trackId) {
        const res = await apiFetch(`/api/playlists/${currentPlaylistId}/tracks/${trackId}`, { method: "POST" });
        if (!res.ok && res.status !== 409) throw new Error(await res.text());
        await refreshState();
        showToast(res.status === 409 ? "Already in playlist" : "Added to playlist");
      }

      async function removeTrackFromCurrentPlaylist(trackId) {
        if (currentView !== "playlist") return;
        await fetchJson(`/api/playlists/${currentPlaylistId}/tracks/${trackId}`, { method: "DELETE" });
        await refreshState();
        showPlaylist(currentPlaylistId);
        showToast("Removed from playlist");
      }

      async function refreshLibrary() {
        await fetchJson("/api/refresh", { method: "POST" });
        window.location.reload();
      }

      function playTrackId(trackId) {
        const id = String(trackId);
        if (currentView === "smart_continue") return resumeTrack(id);
        const idx = activePlayable.findIndex((track) => track.id === id);
        if (idx === -1) {
          activePlayable = libraryPlayable;
          currentView = "library";
          showLibrary();
          return playTrackId(id);
        }
        playIndex(idx);
      }

      function playTrackFromHome(trackId) {
        activePlayable = libraryPlayable;
        const idx = activePlayable.findIndex((track) => track.id === String(trackId));
        if (idx !== -1) playIndex(idx);
      }

      function playIndex(index, resumeAt = 0) {
        if (index < 0 || index >= activePlayable.length) return;
        cancelCrossfadeIfActive();
        currentIndex = index;
        const track = activePlayable[index];
        currentTrack = track;
        currentTrackId = track.id;

        audio.src = track.url;
        if (resumeAt > 0) {
          const seekWhenReady = () => {
            audio.currentTime = Math.min(resumeAt, audio.duration || resumeAt);
            audio.removeEventListener("loadedmetadata", seekWhenReady);
          };
          audio.addEventListener("loadedmetadata", seekWhenReady);
        }
        audio.play();
        setPlayPauseIcon(true);
        setNowPlaying(track);
        recordTrackPlay(track.id);
      }

      function setNowPlaying(track) {
        nowPlaying.textContent = track.title;
        nowMeta.textContent = `${track.artist} - ${track.album}`;
        setCover(track.coverUrl);
        setDetailPanel(track);
        if (!customBackgroundUrl) bg.style.setProperty("--cover-url", `url("${track.coverUrl}")`);
        loadLyrics(track.id);
        updateMediaSession(track);
        renderQueue();

        document.querySelectorAll(".track-card").forEach((card) => {
          const isCurrent = card.dataset.id === track.id;
          card.classList.toggle("ring-2", isCurrent || selectedTrackIds.has(card.dataset.id));
          card.classList.toggle("ring-zinc-100/30", isCurrent || selectedTrackIds.has(card.dataset.id));
        });
      }

      function setCover(url) {
        nowCover.onload = () => {
          nowCover.classList.remove("hidden");
          nowCoverFallback.classList.add("hidden");
        };
        nowCover.onerror = () => {
          nowCover.classList.add("hidden");
          nowCoverFallback.classList.remove("hidden");
        };
        nowCover.src = url;
      }

      function setImage(imgEl, fallbackEl, url) {
        imgEl.onload = () => {
          imgEl.classList.remove("hidden");
          fallbackEl.classList.add("hidden");
        };
        imgEl.onerror = () => {
          imgEl.classList.add("hidden");
          fallbackEl.classList.remove("hidden");
        };
        imgEl.src = url;
      }

      function setDetailPanel(track) {
        const localSummary = localArtistSummary(track.artist);
        const sameArtistTracks = localSummary.tracks;
        const summary = `${localSummary.text} Current folder: ${track.folder || "(root)"}.`;

        sideAlbum.textContent = track.album;
        sideTitle.textContent = track.title;
        sideArtist.textContent = track.artist;
        sideAlbum.onclick = () => showAlbum(track.album);
        sideArtist.onclick = () => showArtist(track.artist);
        sideAlbum.classList.add("cursor-pointer", "hover:text-white");
        sideArtist.classList.add("cursor-pointer", "hover:text-white");
        sideLiked.classList.toggle("hidden", !likedIdSet.has(track.id));
        artistName.textContent = track.artist;
        artistStats.textContent = `${sameArtistTracks.length} tracks in library`;
        artistInfo.textContent = summary;
        setImage(sideCover, sideCoverFallback, track.coverUrl);
        setImage(artistAvatar, artistAvatarFallback, track.coverUrl);

        lyricsViewBg.style.setProperty("--lyrics-cover", `url("${track.coverUrl}")`);
        lyricsViewTitle.textContent = track.title;
        lyricsViewAlbum.textContent = track.album;
        lyricsViewSong.textContent = track.title;
        lyricsViewArtist.textContent = track.artist;
        lyricsArtistName.textContent = track.artist;
        lyricsArtistStats.textContent = `${sameArtistTracks.length} tracks in library`;
        lyricsArtistInfo.textContent = summary;
        setImage(lyricsViewCover, lyricsViewCoverFallback, track.coverUrl);
        setImage(lyricsArtistAvatar, lyricsArtistAvatarFallback, track.coverUrl);
        nowFullscreenBg.style.backgroundImage = `url("${track.coverUrl}")`;
        fullAlbum.textContent = track.album;
        fullTitle.textContent = track.title;
        fullArtist.textContent = track.artist;
        fullInfo.textContent = summary;
        setImage(fullCover, fullCoverFallback, track.coverUrl);
        loadArtistInfo(track.artist);
      }

      function nextTrack() {
        if (!activePlayable.length) return;
        if (manualQueue.length) {
          const nextQueued = manualQueue.shift();
          renderQueue();
          return playTrackFromAnyQueue(nextQueued.id);
        }
        if (shuffleEnabled) return playRandomTrack();
        if (currentIndex === -1) return playIndex(0);
        const nextIndex = currentIndex + 1;
        if (nextIndex < activePlayable.length) return playIndex(nextIndex);
        if (repeatMode === "all") return playIndex(0);
        audio.pause();
        audio.currentTime = 0;
        setPlayPauseIcon(false);
      }

      function prevTrack() {
        if (!activePlayable.length) return;
        if (shuffleEnabled) return playRandomTrack();
        if (currentIndex === -1) return playIndex(0);
        const prevIndex = currentIndex - 1;
        if (prevIndex >= 0) return playIndex(prevIndex);
        if (repeatMode === "all") return playIndex(activePlayable.length - 1);
        playIndex(0);
      }

      function playRandomTrack() {
        if (!activePlayable.length) return;
        if (activePlayable.length === 1) return playIndex(0);
        let nextIndex = currentIndex;
        while (nextIndex === currentIndex) {
          nextIndex = Math.floor(Math.random() * activePlayable.length);
        }
        playIndex(nextIndex);
      }

      function playTrackFromAnyQueue(trackId) {
        const existingIndex = activePlayable.findIndex((track) => track.id === trackId);
        if (existingIndex !== -1) return playIndex(existingIndex);
        const track = libraryPlayable.find((item) => item.id === trackId);
        if (!track) return;
        activePlayable = [track, ...activePlayable.filter((item) => item.id !== track.id)];
        playIndex(0);
      }

      // --- Crossfade -----------------------------------------------------
      // Mirrors nextTrack()'s selection order (manual queue -> shuffle ->
      // sequential -> repeat-all) but only *reads* state, so it's safe to
      // call speculatively a few seconds before the current track actually
      // ends without disturbing anything if it turns out not to be used.
      function resolveUpcomingTrack() {
        if (manualQueue.length) return { track: manualQueue[0], fromQueue: true };
        if (shuffleEnabled) {
          if (!activePlayable.length) return null;
          if (activePlayable.length === 1) return { track: activePlayable[0], index: 0 };
          let idx = currentIndex;
          while (idx === currentIndex) idx = Math.floor(Math.random() * activePlayable.length);
          return { track: activePlayable[idx], index: idx };
        }
        if (currentIndex === -1) return activePlayable[0] ? { track: activePlayable[0], index: 0 } : null;
        const nextIndex = currentIndex + 1;
        if (nextIndex < activePlayable.length) return { track: activePlayable[nextIndex], index: nextIndex };
        if (repeatMode === "all") return activePlayable[0] ? { track: activePlayable[0], index: 0 } : null;
        return null;
      }

      function clampVolume(value) {
        return Math.min(1, Math.max(0, value));
      }

      function maybeStartCrossfade() {
        if (!crossfadeEnabled || crossfading || repeatMode === "one") return;
        const duration = audio.duration;
        if (!Number.isFinite(duration) || duration <= 0) return;
        if (duration - audio.currentTime > CROSSFADE_SECONDS) return;
        const upcoming = resolveUpcomingTrack();
        if (!upcoming) return;
        beginCrossfadeTo(upcoming);
      }

      function beginCrossfadeTo(upcoming) {
        crossfading = true;
        const outgoing = audio;
        const incoming = outgoing === audioA ? audioB : audioA;

        // Record the outgoing track's final position/history entry with its
        // own id/element before any state points at the new track.
        syncTrackPosition(true);

        incoming.pause();
        incoming.currentTime = 0;
        incoming.src = upcoming.track.url;
        incoming.volume = 0;
        incoming.play().catch(() => {});

        const outgoingStartVolume = outgoing.volume;
        const startTime = performance.now();

        function tick(now) {
          const targetVolume = clampVolume(vol ? Number(vol.value) : 1);
          const t = clampVolume((now - startTime) / 1000 / CROSSFADE_SECONDS);
          outgoing.volume = clampVolume(outgoingStartVolume * (1 - t));
          incoming.volume = clampVolume(targetVolume * t);
          if (t < 1 && crossfading) {
            fadeAnimationFrame = requestAnimationFrame(tick);
          } else {
            outgoing.pause();
            outgoing.currentTime = 0;
            outgoing.volume = targetVolume;
            crossfading = false;
            fadeAnimationFrame = null;
          }
        }
        fadeAnimationFrame = requestAnimationFrame(tick);

        audio = incoming;
        if (upcoming.fromQueue) {
          manualQueue.shift();
          renderQueue();
          currentIndex = activePlayable.findIndex((track) => track.id === upcoming.track.id);
        } else {
          currentIndex = upcoming.index;
        }
        currentTrack = upcoming.track;
        currentTrackId = upcoming.track.id;
        setNowPlaying(upcoming.track);
        recordTrackPlay(upcoming.track.id);
      }

      function cancelCrossfadeIfActive() {
        if (!crossfading) return;
        if (fadeAnimationFrame) cancelAnimationFrame(fadeAnimationFrame);
        fadeAnimationFrame = null;
        crossfading = false;
        const idle = audio === audioA ? audioB : audioA;
        idle.pause();
        idle.currentTime = 0;
        const targetVolume = clampVolume(vol ? Number(vol.value) : 1);
        idle.volume = targetVolume;
        audio.volume = targetVolume;
      }

      function resumeTrack(trackId) {
        const history = historyData.tracks?.[trackId] || {};
        const resumeAt = Number(history.last_position || 0);
        const existingIndex = activePlayable.findIndex((track) => track.id === trackId);
        if (existingIndex !== -1) return playIndex(existingIndex, resumeAt);
        const track = libraryPlayable.find((item) => item.id === trackId);
        if (!track) return;
        activePlayable = [track, ...activePlayable.filter((item) => item.id !== track.id)];
        playIndex(0, resumeAt);
      }

      async function recordTrackPlay(trackId) {
        try {
          const res = await apiFetch(`/api/history/play/${trackId}`, { method: "POST" });
          if (res.ok) {
            const data = await res.json();
            historyData.tracks = historyData.tracks || {};
            historyData.tracks[trackId] = { ...(historyData.tracks[trackId] || {}), ...(data.history || {}) };
          }
        } catch (error) {
          console.warn("Could not record play", error);
        }
      }

      function syncTrackPosition(force = false) {
        if (!currentTrackId || !audio.src || !Number.isFinite(audio.currentTime)) return;
        const now = Date.now();
        if (!force && now - lastPositionSync < 10000) return;
        lastPositionSync = now;
        const payload = JSON.stringify({
          position: audio.currentTime || 0,
          duration: audio.duration || 0,
        });
        apiFetch(`/api/history/position/${currentTrackId}`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: payload,
          keepalive: true,
        }).catch((error) => console.warn("Could not save position", error));
      }

      function addToQueue(trackId) {
        const track = libraryPlayable.find((item) => item.id === String(trackId));
        if (!track) return;
        manualQueue.push(track);
        renderQueue();
        showToast("Added to queue");
      }

      function playNext(trackId) {
        const track = libraryPlayable.find((item) => item.id === String(trackId));
        if (!track) return;
        manualQueue = [track, ...manualQueue.filter((item) => item.id !== track.id)];
        renderQueue();
        showToast("Will play next");
      }

      function renderQueue() {
        if (!queueItems || !queueCount) return;
        queueCount.textContent = `${manualQueue.length} queued track${manualQueue.length === 1 ? "" : "s"}`;
        if (!manualQueue.length) {
          queueItems.innerHTML = `<div class="rounded-lg border border-zinc-800 p-4 text-sm text-zinc-500">No queued tracks. Use Play Next or Queue on a track.</div>`;
          return;
        }
        queueItems.innerHTML = manualQueue.map((track, index) => `
          <div class="mb-2 flex items-center gap-3 rounded-lg bg-zinc-950 p-2">
            <img src="${escapeHtml(track.coverUrl)}" class="h-12 w-12 rounded-md object-cover" alt="" onerror="this.style.display='none'" />
            <button type="button" data-action="play-queued-index" data-index="${index}" class="min-w-0 flex-1 text-left">
              <span class="block truncate text-sm font-semibold">${escapeHtml(track.title)}</span>
              <span class="block truncate text-xs text-zinc-500">${escapeHtml(track.artist)}</span>
            </button>
            <button type="button" data-action="remove-queued-index" data-index="${index}" class="rounded-md border border-zinc-700 px-2 py-1 text-xs hover:bg-zinc-900">Remove</button>
          </div>
        `).join("");
      }

      function playQueuedIndex(index) {
        const track = manualQueue.splice(index, 1)[0];
        renderQueue();
        if (track) playTrackFromAnyQueue(track.id);
      }

      function removeQueuedIndex(index) {
        manualQueue.splice(index, 1);
        renderQueue();
      }

      function clearQueue() {
        manualQueue = [];
        renderQueue();
        showToast("Queue cleared");
      }

      function openQueuePanel() {
        renderQueue();
        queuePanel.classList.remove("hidden");
      }

      function closeQueuePanel() {
        queuePanel.classList.add("hidden");
      }

      function openNowFullscreen() {
        if (currentTrack) setDetailPanel(currentTrack);
        nowFullscreen.classList.remove("hidden");
      }

      function closeNowFullscreen() {
        nowFullscreen.classList.add("hidden");
      }

      function updateMediaSession(track) {
        if (!("mediaSession" in navigator)) return;
        navigator.mediaSession.metadata = new MediaMetadata({
          title: track.title,
          artist: track.artist,
          album: track.album,
          artwork: [{ src: track.coverUrl, sizes: "512x512", type: "image/jpeg" }],
        });
        navigator.mediaSession.setActionHandler("play", () => {
          if (audio.src) audio.play();
        });
        navigator.mediaSession.setActionHandler("pause", () => audio.pause());
        navigator.mediaSession.setActionHandler("previoustrack", prevTrack);
        navigator.mediaSession.setActionHandler("nexttrack", nextTrack);
      }

      function toggleShuffle() {
        shuffleEnabled = !shuffleEnabled;
        shuffleBtn.classList.toggle("bg-zinc-100", shuffleEnabled);
        shuffleBtn.classList.toggle("text-black", shuffleEnabled);
      }

      function cycleRepeat() {
        repeatMode = repeatMode === "off" ? "all" : repeatMode === "all" ? "one" : "off";
        repeatBtn.innerHTML = repeatMode === "one" ? ICON_REPEAT_ONE : ICON_REPEAT;
        repeatBtn.title = repeatMode === "off" ? "Repeat off" : repeatMode === "all" ? "Repeat all" : "Repeat one";
        repeatBtn.classList.toggle("bg-zinc-100", repeatMode !== "off");
        repeatBtn.classList.toggle("text-black", repeatMode !== "off");
      }

      function togglePlayPause() {
        if (!audio.src) return;
        if (audio.paused) {
          audio.play();
          setPlayPauseIcon(true);
        } else {
          cancelCrossfadeIfActive();
          audio.pause();
          setPlayPauseIcon(false);
        }
      }

      function fmtTime(seconds) {
        if (!Number.isFinite(seconds) || seconds < 0) return "0:00";
        const minutes = Math.floor(seconds / 60);
        const rest = Math.floor(seconds % 60);
        return `${minutes}:${String(rest).padStart(2, "0")}`;
      }

      function syncProgress() {
        const duration = audio.duration || 0;
        const current = audio.currentTime || 0;
        tCur.textContent = fmtTime(current);
        tDur.textContent = fmtTime(duration);
        if (!isSeeking && duration > 0) seek.value = String(Math.floor((current / duration) * 100));
        updateLyrics(current);
        syncTrackPosition();
      }

      async function loadLyrics(trackId) {
        lyricEntries = [];
        plainLyrics = "";
        activeLyricIndex = -1;
        lyricsPanel?.classList.add("hidden");
        if (lyricsLines) lyricsLines.innerHTML = "";
        if (lyricsViewLines) lyricsViewLines.innerHTML = "";

        const data = await fetchJson(`/api/tracks/${trackId}/lyrics`);
        if (!data.lyrics) return;

        lyricsPanel?.classList.remove("hidden");
        if (lyricsSource) lyricsSource.textContent = data.source === "lrc" ? "LRC file" : "Embedded";

        lyricEntries = parseLrc(data.lyrics);
        if (lyricEntries.length) {
          if (lyricsLines) lyricsLines.innerHTML = lyricEntries.map((entry, index) => (
            `<div class="lyric-line transition ${index === 0 ? "text-zinc-100 text-base font-semibold" : ""}" data-lyric-index="${index}">${escapeHtml(entry.text || "...")}</div>`
          )).join("");
          lyricsViewLines.innerHTML = lyricEntries.map((entry, index) => (
            `<div class="lyric-view-line transition ${index === 0 ? "text-white" : ""}" data-lyric-index="${index}">${escapeHtml(entry.text || "...")}</div>`
          )).join("");
          return;
        }

        plainLyrics = data.lyrics;
        if (lyricsLines) lyricsLines.innerHTML = escapeHtml(plainLyrics).split("\n").map((line) => `<div>${line || "&nbsp;"}</div>`).join("");
        lyricsViewLines.innerHTML = escapeHtml(plainLyrics).split("\n").map((line) => `<div>${line || "&nbsp;"}</div>`).join("");
      }

      function parseLrc(text) {
        const entries = [];
        const linePattern = /\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?\]/g;
        for (const rawLine of text.split(/\r?\n/)) {
          const matches = Array.from(rawLine.matchAll(linePattern));
          if (!matches.length) continue;
          const lyricText = rawLine.replace(linePattern, "").trim();
          for (const match of matches) {
            const minutes = Number(match[1]);
            const seconds = Number(match[2]);
            const fraction = match[3] ? Number(`0.${match[3].padEnd(3, "0").slice(0, 3)}`) : 0;
            entries.push({ time: minutes * 60 + seconds + fraction, text: lyricText });
          }
        }
        return entries.sort((a, b) => a.time - b.time);
      }

      function updateLyrics(currentTime) {
        if (!lyricEntries.length) return;
        let nextIndex = lyricEntries.findIndex((entry, index) => {
          const next = lyricEntries[index + 1];
          return currentTime >= entry.time && (!next || currentTime < next.time);
        });
        if (nextIndex === -1 || nextIndex === activeLyricIndex) return;
        activeLyricIndex = nextIndex;

        lyricsLines?.querySelectorAll(".lyric-line").forEach((line) => {
          const active = Number(line.dataset.lyricIndex) === activeLyricIndex;
          line.classList.toggle("text-zinc-100", active);
          line.classList.toggle("text-base", active);
          line.classList.toggle("font-semibold", active);
          line.classList.toggle("text-zinc-400", !active);
          if (active) line.scrollIntoView({ block: "center", behavior: "smooth" });
        });
        lyricsViewLines?.querySelectorAll(".lyric-view-line").forEach((line) => {
          const active = Number(line.dataset.lyricIndex) === activeLyricIndex;
          line.classList.toggle("text-white", active);
          line.classList.toggle("text-blue-200/60", !active);
          if (active && !lyricsView.classList.contains("hidden")) line.scrollIntoView({ block: "center", behavior: "smooth" });
        });
      }

      function openLyricsView() {
        lyricsView.classList.remove("hidden");
      }

      function closeLyricsView() {
        lyricsView.classList.add("hidden");
      }

      function chooseViewCover() {
        coverUploadInput.value = "";
        coverUploadInput.click();
      }

      coverUploadInput?.addEventListener("change", async () => {
        const file = coverUploadInput.files?.[0];
        if (!file) return;
        const form = new FormData();
        form.append("cover", file);

        let url = "";
        if (currentView === "playlist") {
          url = `/api/playlists/${currentPlaylistId}/cover`;
        } else if (currentView === "folder") {
          const folderName = currentFolder === "(root)" ? "" : currentFolder;
          form.append("folder", folderName);
          url = "/api/folders/cover";
        } else {
          return;
        }

        const res = await apiFetch(url, { method: "POST", body: form });
        if (!res.ok) {
          showToast("Could not save cover image", "error");
          return;
        }
        await refreshState();
        if (currentView === "playlist") showPlaylist(currentPlaylistId);
        if (currentView === "folder") showFolder(currentFolder);
        showToast("Cover saved");
      });

      function chooseTrackCover(trackId) {
        pendingTrackCoverId = String(trackId);
        trackCoverInput.value = "";
        trackCoverInput.click();
      }

      trackCoverInput?.addEventListener("change", async () => {
        const file = trackCoverInput.files?.[0];
        if (!file || !pendingTrackCoverId) return;
        const form = new FormData();
        form.append("cover", file);
        const res = await apiFetch(`/api/tracks/${pendingTrackCoverId}/cover`, { method: "POST", body: form });
        if (!res.ok) {
          showToast("Could not save track cover", "error");
          return;
        }
        window.location.reload();
      });

      function chooseBackground() {
        backgroundInput.value = "";
        backgroundInput.click();
      }

      backgroundInput?.addEventListener("change", async () => {
        const file = backgroundInput.files?.[0];
        if (!file) return;
        const form = new FormData();
        form.append("background", file);
        const res = await apiFetch("/api/background", { method: "POST", body: form });
        if (!res.ok) {
          showToast("Could not save background", "error");
          return;
        }
        const data = await res.json();
        setAppBackground(data.background_url || "");
        await refreshState();
        showToast("Background saved");
      });

      function exportBackup() {
        window.location.href = "/api/backup";
      }

      function chooseRestore() {
        restoreInput.value = "";
        restoreInput.click();
      }

      async function clearArtistCache() {
        if (!window.confirm("Clear cached internet artist info? It can be fetched again later.")) return;
        const result = await fetchJson("/api/settings/artist-info/clear", { method: "POST" });
        artistInfoMemory.clear();
        showToast(`Cleared ${result.cleared || 0} cached artist entr${result.cleared === 1 ? "y" : "ies"}`);
        if (currentView === "settings") renderSettingsDetails();
        if (currentTrack) setDetailPanel(currentTrack);
      }

      restoreInput?.addEventListener("change", async () => {
        const file = restoreInput.files?.[0];
        if (!file) return;
        if (!window.confirm("Restore this backup? Current playlists, likes, history, and artwork may be replaced.")) return;

        const form = new FormData();
        form.append("backup", file);
        const res = await apiFetch("/api/restore", { method: "POST", body: form });
        if (!res.ok) {
          const body = await res.json().catch(() => ({ error: "Could not restore backup" }));
          showToast(body.error || "Could not restore backup", "error");
          return;
        }
        showToast("Backup restored");
        window.location.reload();
      });

      async function openMetadataEditor(trackId) {
        const data = await fetchJson(`/api/tracks/${trackId}/metadata`);
        metadataMode.value = "single";
        metadataBatchIds = [];
        metadataTrackId.value = data.id;
        metadataScope.textContent = `Editing one track: ${data.title}`;
        metadataTitle.value = data.title;
        metadataTitle.disabled = false;
        metadataTitle.placeholder = "";
        metadataArtist.value = data.artist;
        metadataAlbum.value = data.album;
        metadataPath.textContent = data.relpath;
        metadataError.classList.add("hidden");
        metadataModal.classList.remove("hidden");
        metadataModal.classList.add("flex");
      }

      function closeMetadataEditor() {
        metadataModal.classList.add("hidden");
        metadataModal.classList.remove("flex");
      }

      async function saveMetadata(event) {
        event.preventDefault();
        metadataError.classList.add("hidden");
        const ids = metadataTargetIds();
        const payload = {
          track_ids: ids,
          title: metadataTitle.disabled ? "" : metadataTitle.value,
          artist: metadataArtist.value,
          album: metadataAlbum.value,
        };
        const res = metadataMode.value === "batch"
          ? await apiFetch("/api/tracks/metadata/batch", {
              method: "POST",
              headers: { "Content-Type": "application/json" },
              body: JSON.stringify(payload),
            })
          : await apiFetch(`/api/tracks/${metadataTrackId.value}/metadata`, {
              method: "PATCH",
              headers: { "Content-Type": "application/json" },
              body: JSON.stringify({
                title: metadataTitle.value,
                artist: metadataArtist.value,
                album: metadataAlbum.value,
              }),
            });
        if (!res.ok) {
          const body = await res.json().catch(() => ({ error: "Could not save metadata" }));
          metadataError.textContent = body.error || "Could not save metadata";
          metadataError.classList.remove("hidden");
          return;
        }
        window.location.reload();
      }

      async function fillDerivedMetadata() {
        const ids = metadataTargetIds();
        if (!ids.length) return;
        if (ids.length > 1) {
          metadataError.textContent = "Preview is only available for one track. Use Apply auto-fill for batches.";
          metadataError.classList.remove("hidden");
          return;
        }
        const data = await fetchJson(`/api/tracks/${ids[0]}/metadata/derive`);
        metadataTitle.disabled = false;
        metadataTitle.value = data.title || "";
        metadataArtist.value = data.artist || "";
        metadataAlbum.value = data.album || "";
        metadataError.classList.add("hidden");
      }

      async function applyDerivedMetadata(ids) {
        ids = (ids || []).filter(Boolean);
        if (!ids.length) {
          showToast("Select tracks first", "error");
          return;
        }
        if (!window.confirm(`Auto-fill metadata for ${ids.length} track${ids.length === 1 ? "" : "s"} from filename and folders?`)) return;
        const res = await apiFetch("/api/tracks/metadata/batch", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ track_ids: ids, mode: "derive" }),
        });
        if (!res.ok) {
          const body = await res.json().catch(() => ({ error: "Could not auto-fill metadata" }));
          showToast(body.error || "Could not auto-fill metadata", "error");
          return;
        }
        showToast("Metadata auto-filled");
        window.location.reload();
      }

      async function openLyricsEditor(trackId) {
        const data = await fetchJson(`/api/tracks/${trackId}/lyrics`);
        lyricsEditorTrackId.value = trackId;
        lyricsEditorText.value = data.lyrics || "";
        lyricsEditorSource.textContent = data.source ? `Current source: ${data.source}` : "No lyrics saved yet";
        lyricsEditorError.classList.add("hidden");
        lyricsEditorModal.classList.remove("hidden");
        lyricsEditorModal.classList.add("flex");
      }

      function closeLyricsEditor() {
        lyricsEditorModal.classList.add("hidden");
        lyricsEditorModal.classList.remove("flex");
      }

      async function saveLyricsEditor(event) {
        event.preventDefault();
        lyricsEditorError.classList.add("hidden");
        const trackId = lyricsEditorTrackId.value;
        const res = await apiFetch(`/api/tracks/${trackId}/lyrics`, {
          method: "PUT",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ lyrics: lyricsEditorText.value }),
        });
        if (!res.ok) {
          lyricsEditorError.textContent = "Could not save lyrics.";
          lyricsEditorError.classList.remove("hidden");
          return;
        }
        closeLyricsEditor();
        if (currentTrackId === trackId) loadLyrics(trackId);
        showToast("Lyrics saved");
      }

      function insertCurrentTimestamp() {
        const current = audio.currentTime || 0;
        const minutes = Math.floor(current / 60);
        const seconds = Math.floor(current % 60);
        const hundredths = Math.floor((current % 1) * 100);
        const stamp = `[${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}.${String(hundredths).padStart(2, "0")}] `;
        const start = lyricsEditorText.selectionStart || 0;
        const end = lyricsEditorText.selectionEnd || start;
        lyricsEditorText.value = lyricsEditorText.value.slice(0, start) + stamp + lyricsEditorText.value.slice(end);
        lyricsEditorText.focus();
        lyricsEditorText.selectionStart = lyricsEditorText.selectionEnd = start + stamp.length;
      }

      lyricsFileInput?.addEventListener("change", async () => {
        const file = lyricsFileInput.files?.[0];
        if (!file) return;
        lyricsEditorText.value = await file.text();
        lyricsEditorSource.textContent = `Loaded file: ${file.name}`;
      });

      search?.addEventListener("input", scheduleFilter);

      // Both audio elements get the same listeners since either can be the
      // active player at any time (crossfade alternates which is which);
      // each guards on "am I currently the active one" before acting, so
      // the element quietly fading out in the background doesn't drive
      // the seek bar, media session, or trigger a second track transition.
      function bindPlaybackListeners(el) {
        el.addEventListener("timeupdate", () => {
          if (el !== audio) return;
          syncProgress();
          maybeStartCrossfade();
        });
        el.addEventListener("loadedmetadata", () => {
          if (el === audio) syncProgress();
        });
        el.addEventListener("ended", () => {
          if (el !== audio) return;
          syncTrackPosition(true);
          if (repeatMode === "one") return playIndex(currentIndex);
          nextTrack();
        });
        el.addEventListener("pause", () => {
          if (el !== audio) return;
          syncTrackPosition(true);
          if (el.currentTime !== el.duration) setPlayPauseIcon(false);
        });
        el.addEventListener("play", () => {
          if (el === audio) setPlayPauseIcon(true);
        });
      }
      bindPlaybackListeners(audioA);
      bindPlaybackListeners(audioB);

      seek.addEventListener("input", () => {
        isSeeking = true;
      });
      seek.addEventListener("change", () => {
        const duration = audio.duration || 0;
        if (duration > 0) audio.currentTime = (Number(seek.value) / 100) * duration;
        isSeeking = false;
      });
      vol?.addEventListener("input", () => {
        audio.volume = clampVolume(Number(vol.value));
      });
      document.addEventListener("click", (event) => {
        const actionEl = event.target.closest("[data-action]");
        if (actionEl) {
          const handler = actionHandlers[actionEl.dataset.action];
          if (handler) {
            event.preventDefault();
            handler(actionEl, event);
          }
        }
        if (!event.target.closest(".track-menu") && actionEl?.dataset.action !== "toggle-card-menu") closeCardMenus();
      });
      document.addEventListener("submit", (event) => {
        const form = event.target.closest("form[data-action]");
        if (!form) return;
        event.preventDefault();
        if (form.dataset.action === "save-metadata") saveMetadata(event);
        if (form.dataset.action === "save-lyrics") saveLyricsEditor(event);
      });
      document.addEventListener("change", (event) => {
        const checkbox = event.target.closest(".track-select");
        if (checkbox) {
          setTrackSelected(checkbox.dataset.trackId, checkbox.checked);
          return;
        }
        const target = event.target.closest("[data-action]");
        if (!target) return;
        if (target.dataset.action === "playlist-target") setPlaylistTarget(target.value);
      });
      window.addEventListener("beforeunload", () => syncTrackPosition(true));

      // --- Keyboard shortcuts ---------------------------------------------
      let volumeBeforeMute = null;

      function isTypingTarget(el) {
        if (!el) return false;
        const tag = el.tagName;
        return tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT" || el.isContentEditable;
      }

      function isModalOpen() {
        return [metadataModal, lyricsEditorModal, artistMatchModal].some((el) => el && !el.classList.contains("hidden"));
      }

      function seekBy(deltaSeconds) {
        if (!Number.isFinite(audio.duration)) return;
        audio.currentTime = Math.min(Math.max(0, audio.currentTime + deltaSeconds), audio.duration);
      }

      function adjustVolume(delta) {
        const next = clampVolume((vol ? Number(vol.value) : audio.volume) + delta);
        audio.volume = next;
        if (vol) vol.value = String(next);
      }

      function toggleMute() {
        if (audio.volume > 0) {
          volumeBeforeMute = audio.volume;
          audio.volume = 0;
          if (vol) vol.value = "0";
        } else {
          const restore = volumeBeforeMute ?? 1;
          audio.volume = clampVolume(restore);
          if (vol) vol.value = String(clampVolume(restore));
          volumeBeforeMute = null;
        }
      }

      document.addEventListener("keydown", (event) => {
        if (isTypingTarget(event.target) || isModalOpen() || event.metaKey || event.ctrlKey || event.altKey) return;

        switch (event.key) {
          case " ":
            event.preventDefault();
            togglePlayPause();
            break;
          case "ArrowRight":
            event.preventDefault();
            if (event.shiftKey) nextTrack();
            else seekBy(5);
            break;
          case "ArrowLeft":
            event.preventDefault();
            if (event.shiftKey) prevTrack();
            else seekBy(-5);
            break;
          case "ArrowUp":
            event.preventDefault();
            adjustVolume(0.05);
            break;
          case "ArrowDown":
            event.preventDefault();
            adjustVolume(-0.05);
            break;
          case "l":
          case "L":
            if (currentTrackId) toggleLike(currentTrackId);
            break;
          case "m":
          case "M":
            toggleMute();
            break;
          case "s":
          case "S":
            toggleShuffle();
            break;
          case "r":
          case "R":
            cycleRepeat();
            break;
          default:
            break;
        }
      });

      if ("serviceWorker" in navigator) {
        window.addEventListener("load", () => {
          navigator.serviceWorker.register("/sw.js").catch((error) => console.warn("Service worker registration failed", error));
        });
      }

      refreshState().then(showHome);
