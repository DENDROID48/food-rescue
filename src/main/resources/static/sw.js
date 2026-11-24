/*
 * Food Rescue service worker. It only handles push notifications: nothing is cached, so the app
 * is always the latest version and works exactly as before when offline support is not needed.
 */
'use strict';

self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (event) => event.waitUntil(self.clients.claim()));

self.addEventListener('push', (event) => {
  let data = {};
  try { data = event.data ? event.data.json() : {}; } catch { data = { body: event.data && event.data.text() }; }
  const title = data.title || 'Food Rescue';
  event.waitUntil((async () => {
    const windows = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    // Let open tabs refresh their inbox and the live drops straight away.
    windows.forEach((w) => w.postMessage({ type: 'alert-received' }));
    // When the app is on screen it shows the alert itself; otherwise show a system notification.
    if (windows.some((w) => w.focused && w.visibilityState === 'visible')) return;
    await self.registration.showNotification(title, {
      body: data.body || '',
      tag: data.tag || undefined,
      icon: 'icons/icon-192.png',
      badge: 'icons/badge-96.png',
      data: { url: data.url || '/' },
    });
  })());
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const url = new URL(event.notification.data?.url || '/', self.location.origin).href;
  event.waitUntil((async () => {
    const windows = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    const open = windows.find((w) => new URL(w.url).origin === self.location.origin);
    if (open) {
      await open.focus();
      open.postMessage({ type: 'open-url', url });
    } else {
      await self.clients.openWindow(url);
    }
  })());
});

// If the browser renews the subscription, tell the server (the page re-syncs on next open, too).
self.addEventListener('pushsubscriptionchange', (event) => {
  event.waitUntil(self.clients.matchAll({ type: 'window' }).then((ws) => ws.forEach((w) => w.postMessage({ type: 'resubscribe' }))));
});
