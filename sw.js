const SW_VERSION = 'billu-sw-v3.1.0';

self.addEventListener('install', (e) => self.skipWaiting());
self.addEventListener('activate', (e) => {
  e.waitUntil((async () => {
    const keys = await caches.keys();
    for (const k of keys) {
      if (k !== 'shared-files' && k !== SW_VERSION) await caches.delete(k);
    }
    await self.clients.claim();
  })());
});

self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url);

  // 1. Handle Share Target POSTs
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
    return;
  }

  // 2. Network-First for HTML/JS so GitHub Pages updates apply immediately to installed PWA
  if (event.request.method === 'GET' && url.origin === self.location.origin) {
    event.respondWith((async () => {
      try {
        const netResp = await fetch(event.request, { cache: 'no-store' });
        const cache = await caches.open(SW_VERSION);
        cache.put(event.request, netResp.clone());
        return netResp;
      } catch (err) {
        const cached = await caches.match(event.request);
        return cached || Response.error();
      }
    })());
  }
});
