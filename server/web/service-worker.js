const CACHE_NAME = 'fredplayer-web-shell-v39';
const SHELL = [
  './',
  './assets/app.css?v=33',
  './assets/app.js?v=37',
  './assets/fredplayer-icon-192.png',
  './assets/fredplayer-icon-512.png',
  './assets/no-album-art.png',
  './manifest.webmanifest',
];

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(CACHE_NAME).then((cache) => cache.addAll(SHELL)));
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(caches.keys().then((names) => Promise.all(
    names.filter((name) => name !== CACHE_NAME).map((name) => caches.delete(name)),
  )));
  self.clients.claim();
});

self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url);
  if (event.request.method !== 'GET' || url.origin !== self.location.origin
      || url.pathname.includes('/api/') || url.pathname.includes('/stream/')) return;
  if (event.request.mode === 'navigate') {
    event.respondWith(fetch(event.request).then((response) => {
      const copy = response.clone();
      caches.open(CACHE_NAME).then((cache) => cache.put('./', copy));
      return response;
    }).catch(() => caches.match('./')));
    return;
  }
  event.respondWith(caches.match(event.request).then((cached) => cached || fetch(event.request)));
});
