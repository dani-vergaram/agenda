// Guarda la app para que abra sin conexión. Sube VERSION cuando cambies archivos.
const VERSION = "agenda-19";
const BASE = ["./", "index.html", "firebase-config.js", "extras.js", "manifest.webmanifest", "icons/icon-192.png", "icons/icon-512.png"];
self.addEventListener("install", e => e.waitUntil(caches.open(VERSION).then(c => c.addAll(BASE)).then(() => self.skipWaiting())));
self.addEventListener("activate", e => e.waitUntil(
  caches.keys().then(ks => Promise.all(ks.filter(k => k !== VERSION).map(k => caches.delete(k)))).then(() => self.clients.claim())));
self.addEventListener("fetch", e => {
  const u = new URL(e.request.url);
  if (e.request.method !== "GET") return;
  const propio = u.origin === location.origin, lib = u.hostname === "www.gstatic.com" || u.hostname.startsWith("fonts.");
  if (!propio && !lib) return; // Firebase maneja su propia conexión
  e.respondWith(fetch(e.request).then(r => {
    if (r.ok) { const copia = r.clone(); caches.open(VERSION).then(c => c.put(e.request, copia)); }
    return r;
  }).catch(() => caches.match(e.request).then(r => r || caches.match("index.html"))));
});
