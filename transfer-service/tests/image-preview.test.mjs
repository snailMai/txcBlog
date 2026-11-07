import assert from "node:assert/strict";
import test from "node:test";
import { createImagePreview } from "../transfer/static/image-preview.mjs";

function harness(t, load = async () => new Blob(["synthetic"], { type: "image/png" }), timeoutMs = 1000) {
  const ids = new Map(), urls = new Set(), classes = new Set(); let count = 0;
  function node(id) {
    if (ids.has(id)) return ids.get(id);
    const listeners = new Map();
    const value = { textContent: "", hidden: false, disabled: false, open: false, style: {}, clientWidth: 600, clientHeight: 400,
      naturalWidth: 1200, naturalHeight: 800, isConnected: true, focused: 0,
      classList: { toggle(name, on) { if (on) classes.add(name); else classes.delete(name); }, remove(name) { classes.delete(name); } },
      addEventListener(name, fn) { const entries = listeners.get(name) || []; entries.push(fn); listeners.set(name, entries); },
      emit(name, event = {}) { event.preventDefault ||= () => {}; for (const fn of listeners.get(name) || []) fn(event); },
      showModal() { this.open = true; }, close() { this.open = false; }, focus() { this.focused++; },
      removeAttribute(name) { if (name === "src") this._src = ""; }, setPointerCapture() {},
      getBoundingClientRect() { return { left: 0, top: 0, width: this.clientWidth, height: this.clientHeight }; },
      set src(value) { this._src = value; queueMicrotask(() => { if (this._src === value) this.onload?.(); }); }, get src() { return this._src; }
    };
    ids.set(id, value); return value;
  }
  const doc = { getElementById: node, querySelector: () => node("fallback"), documentElement: { style: { overflow: "auto" } } };
  const previousDoc = globalThis.document, previousWindow = globalThis.window;
  globalThis.document = doc; globalThis.window = node("window");
  t.mock.method(URL, "createObjectURL", () => { const url = `blob:synthetic-${++count}`; urls.add(url); return url; });
  t.mock.method(URL, "revokeObjectURL", url => urls.delete(url));
  const viewer = createImagePreview({ load, timeoutMs });
  t.after(() => { viewer.close(); globalThis.document = previousDoc; globalThis.window = previousWindow; });
  const file = { id: "a".repeat(32), name: "测试图片.png" }, opener = node("opener");
  const open = () => viewer.open(file, opener);
  const scale = () => Number(node("image-preview-ratio").textContent.replace("%", ""));
  const pointer = (type, id, x, y) => node("image-preview-viewport").emit(type, { pointerId: id, clientX: x, clientY: y, pointerType: "touch" });
  return { viewer, node, doc, urls, open, scale, pointer, opener, file, classes };
}

test("默认适应窗口，按钮缩放、100% 及适应窗口不改变原图资源", async t => {
  const h = harness(t); await h.open();
  assert.equal(h.node("image-preview").open, true); assert.equal(h.scale(), 47);
  assert.equal(h.doc.documentElement.style.overflow, "hidden");
  const url = h.node("image-preview-image").src;
  h.node("image-preview-in").emit("click"); assert(h.scale() > 47);
  h.node("image-preview-out").emit("click"); assert.equal(h.scale(), 47);
  h.node("image-preview-actual").emit("click"); assert.equal(h.scale(), 100);
  h.node("image-preview-fit").emit("click"); assert.equal(h.scale(), 47);
  assert.equal(h.node("image-preview-image").src, url); assert.equal(h.urls.size, 1);
});

test("滚轮只缩放预览，双指缩放和单指拖动可组合；松手后不继续移动", async t => {
  const h = harness(t); await h.open(); let prevented = false;
  h.node("image-preview-viewport").emit("wheel", { deltaY: -100, clientX: 300, clientY: 200, preventDefault() { prevented = true; } });
  assert(prevented); assert(h.scale() > 47);
  h.node("image-preview-actual").emit("click");
  h.pointer("pointerdown", 1, 250, 200); h.pointer("pointerdown", 2, 350, 200);
  h.pointer("pointermove", 2, 450, 200); assert.equal(h.scale(), 200);
  h.pointer("pointerup", 2, 450, 200);
  const before = h.node("image-preview-image").style.transform;
  h.pointer("pointermove", 1, 280, 220); assert.notEqual(h.node("image-preview-image").style.transform, before);
  h.pointer("pointercancel", 1, 280, 220);
  const stopped = h.node("image-preview-image").style.transform;
  h.pointer("pointermove", 1, 300, 250); assert.equal(h.node("image-preview-image").style.transform, stopped);
});

test("关闭、遮罩和 Esc 释放资源、恢复滚动及焦点，图片拖动松手不误关闭", async t => {
  const h = harness(t); await h.open();
  h.node("image-preview").emit("pointerdown", { target: h.node("image-preview-viewport") });
  h.node("image-preview").emit("click", { target: h.node("image-preview") });
  assert(h.node("image-preview").open);
  h.node("image-preview").emit("cancel");
  assert.equal(h.urls.size, 0); assert.equal(h.doc.documentElement.style.overflow, "auto"); assert.equal(h.opener.focused, 1);
  await h.open();
  h.node("image-preview").emit("pointerdown", { target: h.node("image-preview") });
  h.node("image-preview").emit("click", { target: h.node("image-preview") });
  assert.equal(h.node("image-preview").open, false); assert.equal(h.urls.size, 0);
});

test("关闭取消未完成请求，迟到原图不能创建资源或覆盖下一张", async t => {
  let resolve, signal;
  const h = harness(t, (_file, incoming) => { signal = incoming; return new Promise(done => { resolve = done; }); });
  const waiting = h.open(); await new Promise(done => setImmediate(done));
  h.viewer.close(); assert(signal.aborted);
  await waiting;
  resolve(new Blob(["late"], { type: "image/png" })); await new Promise(done => setImmediate(done));
  assert.equal(h.urls.size, 0); assert.equal(h.node("image-preview-image").src, "");
});

test("快速换图时上一张迟到响应不能覆盖当前图片", async t => {
  let resolve;
  const h = harness(t, file => file.name === "first" ? new Promise(done => { resolve = done; }) : Promise.resolve(new Blob(["second"], { type: "image/png" })));
  const first = h.viewer.open({ ...h.file, name: "first" }, h.opener); await new Promise(done => setImmediate(done));
  await h.open(); const url = h.node("image-preview-image").src;
  resolve(new Blob(["first"], { type: "image/png" })); await first;
  assert.equal(h.node("image-preview-title").textContent, h.file.name); assert.equal(h.node("image-preview-image").src, url); assert.equal(h.urls.size, 1);
});

test("失败可重试，超时与无效内容不会留下原图资源", async t => {
  let fail = true;
  const h = harness(t, async () => { if (fail) throw new Error("合成连接失败"); return new Blob(["image"], { type: "image/png" }); });
  await h.open(); assert.equal(h.node("image-preview-retry").hidden, false); assert.match(h.node("image-preview-status").textContent, /连接失败/);
  fail = false; await h.open(); assert.equal(h.node("image-preview-retry").hidden, true); assert.equal(h.scale(), 47);
});

test("原图请求永久等待时可超时恢复并关闭", async t => {
  const h = harness(t, () => new Promise(() => {}), 10); await h.open();
  assert.match(h.node("image-preview-status").textContent, /超时/); assert.equal(h.urls.size, 0); assert.equal(h.node("image-preview-in").disabled, true);
});

test("非图片或空内容明确失败，不作为成功图片显示", async t => {
  const h = harness(t, async () => new Blob(["html"], { type: "text/html" })); await h.open();
  assert.match(h.node("image-preview-status").textContent, /无效/); assert.equal(h.urls.size, 0); assert.equal(h.node("image-preview-image").hidden, true);
});
