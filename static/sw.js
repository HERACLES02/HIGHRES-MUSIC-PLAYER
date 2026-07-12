// Minimal offline-shell service worker. Scope is deliberately narrow: it
// only caches the static app shell (CSS/JS/manifest/icon) so the app can be
// installed and survives brief connectivity blips. It does NOT cache the
// authenticated page (which embeds per-request track data), API responses,
// or audio/artwork - those are personal, dynamic, and/or large, and require
// a live server regardless.
const CACHE_NAME = "music-player-shell-v1";
const SHELL_ASSETS = [
  "/static/styles.css",
  "/static/app.js",
  "/static/theme.js",
  "/static/manifest.json",
  "/static/icon.svg",
];

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => cache.addAll(SHELL_ASSETS)).then(() => self.skipWaiting())
  );
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((key) => key !== CACHE_NAME).map((key) => caches.delete(key))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener("fetch", (event) => {
  const url = new URL(event.request.url);
  if (event.request.method !== "GET" || !SHELL_ASSETS.includes(url.pathname)) return;

  // Network-first so an online user always gets current app code; only
  // fall back to the cached shell asset when the network is unreachable.
  event.respondWith(
    fetch(event.request)
      .then((response) => {
        const copy = response.clone();
        caches.open(CACHE_NAME).then((cache) => cache.put(event.request, copy));
        return response;
      })
      .catch(() => caches.match(event.request))
  );
});
