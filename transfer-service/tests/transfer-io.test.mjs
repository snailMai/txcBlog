import assert from "node:assert/strict";
import { webcrypto } from "node:crypto";
import test from "node:test";
import { withDeadline, readHashed, uploadBuffer } from "../transfer/static/transfer-io.mjs";

function install(t, values) {
  for (const [key, value] of Object.entries(values)) {
    const original = Object.getOwnPropertyDescriptor(globalThis, key);
    Object.defineProperty(globalThis, key, { configurable: true, value });
    t.after(() => { if (original) Object.defineProperty(globalThis, key, original); else delete globalThis[key]; });
  }
}

class Reader {
  async readAsArrayBuffer(blob) {
    try { this.result = await blob.arrayBuffer(); this.onload?.(); }
    catch { this.onerror?.(); }
  }
  abort() { this.aborted = true; this.onabort?.(); }
}

test("a never-settling request has a deadline and aborts its underlying operation", async () => {
  let signal;
  await assert.rejects(withDeadline(value => { signal = value; return new Promise(() => {}); },
    { timeoutMs: 10, stage: "创建上传任务" }), error => error.timeout && /创建上传任务超时/.test(error.message));
  assert.equal(signal.aborted, true);
});

test("cancellation works before starting and while an operation is pending", async () => {
  const controller = new AbortController(); controller.abort();
  let started = false;
  await assert.rejects(withDeadline(() => { started = true; }, { signal: controller.signal }), error => error.cancelled);
  assert.equal(started, false);
  const active = new AbortController();
  const promise = withDeadline(() => new Promise(() => {}), { signal: active.signal });
  active.abort();
  await assert.rejects(promise, error => error.cancelled);
});

test("file bytes are read once, hashed unchanged, and exposed as an ArrayBuffer", async t => {
  install(t, { FileReader: Reader, crypto: webcrypto });
  const bytes = new Uint8Array([0, 255, 1, 7, 3]);
  const stages = [];
  const result = await readHashed(new Blob([bytes]), { onStage: stage => stages.push(stage) });
  assert.deepEqual(new Uint8Array(result.buffer), bytes);
  assert.deepEqual(stages, ["读取文件", "校验分片"]);
  const digest = await webcrypto.subtle.digest("SHA-256", bytes);
  assert.equal(result.hash, Buffer.from(digest).toString("hex"));
});

test("a stuck file provider is aborted, and a stuck hash also times out", async t => {
  let reader;
  install(t, { FileReader: class extends Reader { constructor() { super(); reader = this; } readAsArrayBuffer() {} }, crypto: webcrypto });
  await assert.rejects(readHashed(new Blob(["bytes"]), { timeoutMs: 10 }), /读取文件超时/);
  assert.equal(reader.aborted, true);
  assert.equal(reader.onload, null);
  Object.defineProperty(globalThis, "FileReader", { configurable: true, value: Reader });
  Object.defineProperty(globalThis, "crypto", { configurable: true, value: { subtle: { digest: () => new Promise(() => {}) } } });
  await assert.rejects(readHashed(new Blob(["bytes"]), { timeoutMs: 10 }), /校验分片超时/);
});

test("unreadable, truncated and zero-byte files have defined behavior", async t => {
  install(t, { FileReader: Reader, crypto: webcrypto });
  await assert.rejects(readHashed({ size: 5, arrayBuffer: async () => new ArrayBuffer(1) }), /读取不完整/);
  await assert.rejects(readHashed({ size: 5, arrayBuffer: async () => { throw new Error("provider"); } }), /读取文件失败/);
  const empty = await readHashed(new Blob([]));
  assert.equal(empty.buffer.byteLength, 0);
  assert.equal(empty.hash, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
});

function xhrMock(t, scenario) {
  const instances = [];
  class XHR {
    constructor() { this.upload = {}; this.headers = {}; instances.push(this); }
    open(method, url) { this.method = method; this.url = url; }
    setRequestHeader(key, value) { this.headers[key] = value; }
    send(buffer) { this.buffer = buffer; scenario(this); }
    abort() { this.aborted = true; this.onabort?.(); }
  }
  install(t, { XMLHttpRequest: XHR });
  return instances;
}

test("binary upload sends the exact ArrayBuffer, credentials and checksums with progress", async t => {
  const instances = xhrMock(t, xhr => queueMicrotask(() => {
    xhr.upload.onprogress({ loaded: 2 });
    xhr.upload.onprogress({ loaded: 4 });
    xhr.status = 200; xhr.responseText = '{"ok":true}'; xhr.onload();
  }));
  const buffer = new Uint8Array([1, 2, 3, 4]).buffer;
  const progress = [];
  const result = await uploadBuffer("/transfer/api/uploads/test/chunks/0", buffer,
    { csrf: "synthetic", hash: "a".repeat(64), onProgress: (loaded, total) => progress.push([loaded, total]) });
  const xhr = instances[0];
  assert.equal(xhr.buffer, buffer);
  assert.equal(xhr.withCredentials, true);
  assert.equal(xhr.headers["X-CSRF-Token"], "synthetic");
  assert.equal(xhr.headers["X-Chunk-SHA256"], "a".repeat(64));
  assert.equal(xhr.headers["Content-Type"], "application/octet-stream");
  assert.deepEqual(progress, [[2, 4], [4, 4]]);
  assert.deepEqual(result, { ok: true });
  assert.equal(xhr.upload.onprogress, null);
});

test("a stalled upload is aborted even when native timeout events never arrive", async t => {
  const instances = xhrMock(t, () => {});
  await assert.rejects(uploadBuffer("/upload", new ArrayBuffer(1), { timeoutMs: 10 }), /上传分片超时/);
  assert.equal(instances[0].aborted, true);
  assert.equal(instances[0].onload, null);
});

test("cancelling an upload clears handlers and stops the network request", async t => {
  const instances = xhrMock(t, () => {});
  const controller = new AbortController();
  const pending = uploadBuffer("/upload", new ArrayBuffer(1), { signal: controller.signal });
  await Promise.resolve(); controller.abort();
  await assert.rejects(pending, error => error.cancelled);
  assert.equal(instances[0].aborted, true);
  assert.equal(instances[0].upload.onprogress, null);
});

test("HTTP errors preserve status; malformed successful replies can be reconciled", async t => {
  let code = 401, body = '{"error":"请先登录"}';
  xhrMock(t, xhr => queueMicrotask(() => { xhr.status = code; xhr.responseText = body; xhr.onload(); }));
  await assert.rejects(uploadBuffer("/upload", new ArrayBuffer(1)), error => error.status === 401 && /请先登录/.test(error.message));
  code = 503; body = 'null';
  await assert.rejects(uploadBuffer("/upload", new ArrayBuffer(1)), error => error.status === 503);
  code = 200; body = '<html>not json</html>';
  await assert.rejects(uploadBuffer("/upload", new ArrayBuffer(1)), error => error.retryable === true);
  body = '{}';
  await assert.rejects(uploadBuffer("/upload", new ArrayBuffer(1)), error => error.retryable === true);
});
