import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { createHash, webcrypto } from "node:crypto";
import { File } from "node:buffer";
import vm from "node:vm";
import test from "node:test";
import { withDeadline, readHashed, uploadBuffer } from "../transfer/static/transfer-io.mjs";

const source = (await readFile(new URL("../transfer/static/app.js", import.meta.url), "utf8"))
  .replace(/^import .*\.mjs.*;\n/gm, "");
const tick = () => new Promise(resolve => setImmediate(resolve));
const hash = bytes => createHash("sha256").update(bytes).digest("hex");

async function harness(t, options = {}) {
  const nodes = new Map(), calls = [], sends = [], readers = [];
  const job = { id: "a".repeat(32), state: "pending", chunks: { ...options.chunks }, message_id: null };
  const records = new Map();
  const settings = { ...options };
  const windowListeners = new Map();
  const deadline = options.deadline || 50;
  function node(id = "") {
    if (id && nodes.has(id)) return nodes.get(id);
    const listeners = new Map();
    const value = { value: "", textContent: "", disabled: false, hidden: false, open: false, dataset: {}, style: {}, scrollTop: 0,
      classList: { toggle() {} }, append() {}, replaceChildren() {}, setAttribute() {}, reset() {}, focus() {},
      addEventListener(type, callback) { listeners.set(type, callback); },
      emit(type, event = { preventDefault() {} }) { return listeners.get(type)?.(event); },
      close() { this.open = false; }, showModal() { this.open = true; } };
    if (id) nodes.set(id, value);
    if (id === "message-text" && options.composer) {
      value.clientWidth = 320;
      Object.defineProperty(value, "scrollHeight", { get() { return this.value ? (settings.contentHeight || 114) : 42; } });
    }
    return value;
  }
  class Reader {
    constructor() { readers.push(this); }
    readAsArrayBuffer(blob) {
      if (settings.stall === "read") return;
      blob.arrayBuffer().then(buffer => { this.result = buffer; this.onload?.(); }, () => this.onerror?.());
    }
    abort() { this.aborted = true; this.onabort?.(); }
  }
  class XHR {
    constructor() { this.upload = {}; this.headers = {}; }
    open(method, url) { this.url = url; this.method = method; }
    setRequestHeader(key, value) { this.headers[key] = value; }
    abort() { this.aborted = true; this.onabort?.(); }
    send(buffer) {
      sends.push(this); this.buffer = buffer;
      if (settings.stall === "chunk") return;
      const index = this.url.split("/").at(-1);
      assert.equal(this.headers["X-Chunk-SHA256"], hash(Buffer.from(buffer)));
      job.chunks[index] = hash(Buffer.from(buffer));
      this.upload.onprogress?.({ loaded: buffer.byteLength });
      if (settings.loseChunkReply) { settings.loseChunkReply = false; return; }
      this.status = 200; this.responseText = '{"ok":true}'; queueMicrotask(() => this.onload?.());
    }
  }
  for (const [key, value] of Object.entries({ FileReader: Reader, XMLHttpRequest: XHR, crypto: webcrypto })) {
    const original = Object.getOwnPropertyDescriptor(globalThis, key);
    Object.defineProperty(globalThis, key, { configurable: true, value });
    t.after(() => { if (original) Object.defineProperty(globalThis, key, original); else delete globalThis[key]; });
  }
  const context = vm.createContext({
    withDeadline: (work, opts) => withDeadline(work, { ...opts, timeoutMs: deadline }),
    readHashed: (blob, opts) => readHashed(blob, { ...opts, timeoutMs: deadline }),
    uploadBuffer: (url, buffer, opts) => uploadBuffer(url, buffer, { ...opts, timeoutMs: deadline }),
    crypto: webcrypto, AbortController, Blob, URLSearchParams,
    document: { body: { dataset: { base: "/transfer" } }, hidden: false,
      getElementById: node, createElement: () => node(), createDocumentFragment: () => node(),
      querySelectorAll: () => [], addEventListener() {} },
    window: { addEventListener(type, callback) { windowListeners.set(type, callback); } }, navigator: { userAgent: "Android" },
    location: { search: options.shareText ? "?share=synthetic-share" : "" }, history: { replaceState() {} },
    getComputedStyle: () => ({ lineHeight: "24px", paddingTop: "12px", paddingBottom: "6px" }),
    indexedDB: { open() {
      const request = {};
      const db = { close() {}, transaction() {
        const tx = { objectStore: () => ({
          get() { const result = { result: { text: settings.shareText, files: [], created: Date.now() } }; queueMicrotask(() => result.onsuccess?.()); return result; },
          delete() {}, clear() {},
        }) };
        queueMicrotask(() => tx.oncomplete?.());
        return tx;
      } };
      queueMicrotask(() => { request.result = db; request.onsuccess?.(); });
      return request;
    } },
    localStorage: { getItem: () => "[]", setItem() {}, removeItem() {} },
    setInterval() {}, setTimeout: () => 1, clearTimeout() {},
    async fetch(url, request) {
      calls.push({ url, request });
      if (url.endsWith("/api/session")) return new Response('{"configured":true,"authenticated":true,"csrf":"synthetic-csrf"}');
      if (url === "/transfer/api/uploads") {
        if (settings.stall === "create") return new Promise(() => {});
        if (settings.stall === "create-body") return { ok: true, json: () => new Promise(() => {}) };
        return new Response(JSON.stringify(job));
      }
      if (url === "/transfer/api/uploads/" + job.id) return new Response(JSON.stringify(job));
      if (url.endsWith("/complete")) {
        job.state = "ready";
        if (settings.stall === "complete") { settings.stall = ""; return new Promise(() => {}); }
        return new Response(JSON.stringify(job));
      }
      if (url === "/transfer/api/messages" && request.method === "POST") {
        const payload = JSON.parse(request.body);
        if (!records.has(payload.client_id)) records.set(payload.client_id, payload);
        job.message_id = "saved-record";
        if (settings.loseCommitReply) { settings.loseCommitReply = false; return new Promise(() => {}); }
        return new Response('{"id":"saved-record"}');
      }
      if (url.startsWith("/transfer/api/messages?")) {
        if (settings.stall === "refresh" && records.size) return new Promise(() => {});
        return new Response('{"messages":[],"next":null}');
      }
      if (url.endsWith("/api/stats")) return new Response(JSON.stringify({ used: settings.used || 0, quota: 1000, count: 0, reserved: 0 }));
      throw new Error("Unexpected synthetic request: " + url);
    },
  });
  vm.runInContext(source + "\nglobalThis.testState=state; globalThis.testRefreshStats=refreshStats;", context);
  await tick(); await tick();
  const bytes = options.bytes || new Uint8Array([0, 1, 2, 255]);
  const file = new File([bytes], "synthetic.jpg");
  const entry = { id: options.existing ? job.id : null, file, name: file.name, size: file.size, fingerprint: "b".repeat(64), status: "待发送", progress: 0 };
  context.testState.files = [entry];
  return { node, state: context.testState, entry, job, records, calls, sends, readers, settings,
    send: () => node("send-button").emit("click"), cancel: () => node("cancel-send").emit("click"),
    resize: () => windowListeners.get("resize")(), refreshStats: context.testRefreshStats };
}

test("creating a task or reading its response cannot leave sending permanently active", async t => {
  const h = await harness(t, { deadline: 10 });
  for (const stall of ["create", "create-body"]) {
    h.settings.stall = stall;
    await h.send();
    assert.equal(h.state.sending, false);
    assert.equal(h.node("send-button").disabled, false);
    assert.match(h.entry.status, /创建上传任务超时/);
    assert.equal(h.state.files.length, 1);
  }
});

test("a stuck file provider reports its phase and restores controls without sending bytes", async t => {
  const h = await harness(t, { stall: "read", deadline: 10 });
  await h.send();
  assert.match(h.entry.status, /读取文件超时/);
  assert.equal(h.sends.length, 0);
  assert.equal(h.readers[0].aborted, true);
  assert.equal(h.state.sending, false);
});

test("cancelling a stalled chunk aborts it and retains the task for continuation", async t => {
  const h = await harness(t, { stall: "chunk", deadline: 500 });
  const pending = h.send();
  while (!h.sends.length) await tick();
  assert.equal(h.node("send-label").textContent, "正在发送…");
  assert.equal(h.node("send-button").textContent, "");
  h.cancel(); await pending;
  assert.equal(h.sends[0].aborted, true);
  assert.equal(h.entry.id, h.job.id);
  assert.equal(h.state.files.length, 1);
  assert.equal(h.node("cancel-send").hidden, true);
  assert.equal(h.node("send-button").disabled, false);
  assert.equal(h.node("send-label").textContent, "发送");
  assert.equal(h.node("send-button").textContent, "");
});

test("lost chunk acknowledgement is reconciled without uploading the same chunk again", async t => {
  const h = await harness(t, { loseChunkReply: true });
  await h.send();
  assert.equal(h.sends.length, 1);
  assert.ok(h.calls.some(call => call.url === "/transfer/api/uploads/" + h.job.id));
  assert.equal(h.records.size, 1);
  assert.equal(h.state.files.length, 0);
});

test("lost commit response reuses the same idempotency key and creates one record", async t => {
  const h = await harness(t, { loseCommitReply: true });
  await h.send();
  const key = h.state.clientId;
  assert.ok(key);
  assert.equal(h.records.size, 1);
  assert.match(h.entry.status, /结果待确认/);
  await h.send();
  assert.equal(h.records.size, 1);
  const posts = h.calls.filter(call => call.url.endsWith("/api/messages") && call.request.method === "POST");
  assert.deepEqual(posts.map(call => JSON.parse(call.request.body).client_id), [key, key]);
  assert.equal(h.sends.length, 1);
  assert.equal(h.state.files.length, 0);
});

test("an acknowledged upload can finish after a lost complete response without the local file", async t => {
  const h = await harness(t, { stall: "complete" });
  await h.send();
  assert.match(h.entry.status, /服务器校验并保存超时/);
  h.entry.file = null;
  await h.send();
  assert.equal(h.records.size, 1);
  assert.equal(h.sends.length, 1);
});

test("history refresh cannot keep the button busy after the server confirmed the record", async t => {
  const h = await harness(t, { stall: "refresh", deadline: 20 });
  await h.send();
  assert.equal(h.records.size, 1);
  assert.equal(h.state.sending, false);
  assert.equal(h.node("send-button").disabled, false);
  assert.match(h.node("toast").textContent, /已保存/);
});

test("resume keeps the 8 MiB protocol and uploads only missing bytes", async t => {
  const bytes = new Uint8Array(8 * 1024 * 1024 + 3);
  bytes.set([17, 0, 255], bytes.length - 3);
  const h = await harness(t, { bytes, existing: true, chunks: { "0": hash(bytes.subarray(0, -3)) }, deadline: 1000 });
  await h.send();
  assert.equal(h.sends.length, 1);
  assert.match(h.sends[0].url, /chunks\/1$/);
  assert.deepEqual(new Uint8Array(h.sends[0].buffer), new Uint8Array([17, 0, 255]));
  assert.equal(h.records.size, 1);
});

test("composer grows, caps scrolling, and shrinks on input, composition and width changes without editing text", async t => {
  const h = await harness(t, { composer: true });
  const input = h.node("message-text");
  assert.equal(input.style.height, "42px");
  input.value = "中文输入\n第二行\n第三行\n第四行";
  await input.emit("input");
  assert.equal(input.style.height, "114px");
  assert.equal(input.style.overflowY, "hidden");
  h.settings.contentHeight = 500; input.scrollTop = 123;
  await input.emit("compositionend");
  assert.equal(input.style.height, "210px");
  assert.equal(input.style.overflowY, "auto");
  assert.equal(input.scrollTop, 123);
  input.selectionStart = input.selectionEnd = input.value.length;
  await input.emit("input", { type: "input" });
  assert.equal(input.scrollTop, 500);
  h.settings.contentHeight = 66; h.resize();
  assert.equal(input.style.height, "66px");
  assert.equal(input.scrollTop, 0);
  assert.equal(input.value, "中文输入\n第二行\n第三行\n第四行");
  input.value = ""; await input.emit("input");
  assert.equal(input.style.height, "42px");
});

test("uncertain sending keeps composer content and height; confirmed retry shrinks it", async t => {
  const h = await harness(t, { composer: true, loseCommitReply: true });
  h.state.files = [];
  const input = h.node("message-text");
  input.value = "保留未确认内容\n第二行"; await input.emit("input");
  await h.send();
  assert.equal(input.value, "保留未确认内容\n第二行");
  assert.equal(input.style.height, "114px");
  assert.equal(input.disabled, false);
  await h.send();
  assert.equal(h.records.size, 1);
  assert.equal(input.value, "");
  assert.equal(input.style.height, "42px");
});

test("shared text is sized after programmatic insertion without a native input event", async t => {
  const text = "分享填入\n第二行\n第三行";
  const h = await harness(t, { composer: true, shareText: text });
  assert.equal(h.node("message-text").value, text);
  assert.equal(h.node("message-text").style.height, "114px");
  assert.match(h.node("toast").textContent, /已接收分享内容/);
});

test("capacity warning occupies no default row and appears only at the existing 90 percent threshold", async t => {
  const h = await harness(t, { used: 899 });
  assert.equal(h.node("composer-warning").hidden, true);
  assert.equal(h.node("composer-warning").textContent, "");
  h.settings.used = 900; await h.refreshStats();
  assert.equal(h.node("composer-warning").hidden, false);
  assert.match(h.node("composer-warning").textContent, /存储空间即将用满/);
  h.settings.used = 100; await h.refreshStats();
  assert.equal(h.node("composer-warning").hidden, true);
  assert.equal(h.node("composer-warning").textContent, "");
});
