self.addEventListener('install', (e) => self.skipWaiting());
self.addEventListener('activate', (e) => e.waitUntil(self.clients.claim()));

self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url);

  if (event.request.method === 'POST' && url.pathname.endsWith('/share-receive')) {
    event.respondWith((async () => {
      const formData = await event.request.formData();
      const file = formData.get('file');
      if (file) {
        const cache = await caches.open('shared-files');
        const headers = new Headers({
          'Content-Type': file.type || 'application/pdf',
          'X-File-Name': file.name || 'shared.pdf'
        });
        await cache.put('/shared-file', new Response(file, { headers }));
      }
      return Response.redirect('./index.html?shared=1', 303);
    })());
  }
});
