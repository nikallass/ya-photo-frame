// Служебный работник: держит оболочку страницы, чтобы она открывалась и когда
// телевизор выключен. Данные не кэшируются никогда — состояние рамки всегда
// должно быть свежим, лучше честная ошибка, чем вчерашние цифры.
const SHELL = 'frame-shell-v1';
const FILES = ['/', '/icon-192.png', '/icon-512.png'];

self.addEventListener('install', event => {
  event.waitUntil(caches.open(SHELL).then(cache => cache.addAll(FILES)));
  self.skipWaiting();
});

self.addEventListener('activate', event => {
  event.waitUntil(
    caches.keys().then(names =>
      Promise.all(names.filter(n => n !== SHELL).map(n => caches.delete(n)))
    )
  );
  self.clients.claim();
});

self.addEventListener('fetch', event => {
  const url = new URL(event.request.url);
  if (url.pathname.startsWith('/api/')) return; // состояние — только из сети
  event.respondWith(
    fetch(event.request).catch(() => caches.match(event.request))
  );
});
