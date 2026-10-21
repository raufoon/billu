self.addEventListener('install', (e) => self.skipWaiting());
self.addEventListener('activate', (e) => e.waitUntil(self.clients.claim()));

self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url);

  if (event.request.method === 'POST' && url.pathname.endsWith('/share-receive')) {
    event.respondWith((async () => {
      const formData = await event.request.formData();
      const files = formData.getAll('file').filter(f => f && f.size > 0);
      const sharedText = formData.get('text') || '';
      const sharedTitle = formData.get('title') || '';
      const cache = await caches.open('shared-files');

      const keys = await cache.keys();
      for (const k of keys) await cache.delete(k);

      const isFpAuto =
        sharedTitle.includes('FP_AUTO') ||
        sharedText.includes('===FP_AUTO===') ||
        (files.length > 0 && files.some(f => f.name && f.name.startsWith('FP_')));

      if (!isFpAuto && files.length === 1) {
        const file = files[0];
        const headers = new Headers({
          'Content-Type': file.type || 'application/pdf',
          'X-File-Name': file.name || 'shared.pdf'
        });
        await cache.put('/shared-file', new Response(file, { headers }));
        return Response.redirect('./index.html?shared=1', 303);
      }

      await cache.put('/shared-meta', new Response(JSON.stringify({
        title: sharedTitle,
        text: sharedText,
        fileCount: files.length,
        timestamp: Date.now()
      }), { headers: { 'Content-Type': 'application/json' } }));

      for (let i = 0; i < files.length; i++) {
        const f = files[i];
        const headers = new Headers({
          'Content-Type': f.type || 'application/octet-stream',
          'X-File-Name': f.name || `shared_${i}`
        });
        await cache.put(`/shared-file-${i}`, new Response(f, { headers }));
      }

      return Response.redirect(`./index.html?shared=1${isFpAuto ? '&fp_auto=1' : ''}`, 303);
    })());
  }
});
