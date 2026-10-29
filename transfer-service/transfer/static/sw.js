"use strict";
const appURL = path => new URL(path, self.registration.scope).href;
self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", event => event.waitUntil(self.clients.claim()));

function database() {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open("snail-transfer-shares", 1);
    request.onupgradeneeded = () => request.result.createObjectStore("drafts", { keyPath: "id" });
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}
async function receiveShare(request) {
  try {
    const form = await request.formData();
    const files = form.getAll("files").filter(value => value instanceof File);
    if (files.length > 20 || files.some(file => file.size > 500000000)) throw new Error("Share too large");
    const text = [form.get("title"), form.get("text"), form.get("url")].filter(value => typeof value === "string" && value.trim()).join("\n");
    if (text.length > 100000) throw new Error("Text too large");
    const id = crypto.randomUUID();
    const db = await database();
    await new Promise((resolve, reject) => {
      const transaction = db.transaction("drafts", "readwrite");
      const store = transaction.objectStore("drafts");
      // Browser-local, unsubmitted share drafts expire after 48 hours.
      const cursor = store.openCursor();
      cursor.onsuccess = () => { const item = cursor.result; if (item) { if (Date.now() - item.value.created > 48 * 3600000) item.delete(); item.continue(); } };
      store.put({ id, text, files, created: Date.now() });
      transaction.oncomplete = resolve;
      transaction.onerror = () => reject(transaction.error);
    });
    db.close();
    return Response.redirect(appURL(`?share=${id}`), 303);
  } catch { return Response.redirect(appURL("?share_error=1"), 303); }
}
self.addEventListener("fetch", event => {
  const url = new URL(event.request.url);
  if (url.href === appURL("share-target") && event.request.method === "POST") event.respondWith(receiveShare(event.request));
  // Private messages, attachments and API responses are never cached here.
});
