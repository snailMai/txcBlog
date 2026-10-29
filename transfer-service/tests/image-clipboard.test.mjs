import assert from "node:assert/strict";
import { afterEach, beforeEach, test } from "node:test";
import { copyImageToClipboard } from "../transfer/static/image-clipboard.mjs";

const globals = ["navigator", "isSecureContext", "ClipboardItem", "createImageBitmap", "document", "setTimeout", "clearTimeout"];
let saved, state;
beforeEach(() => {
  saved = Object.fromEntries(globals.map(key => [key, Object.getOwnPropertyDescriptor(globalThis, key)]));
  state = { width: 320, height: 240, closed: 0, drawn: 0, writeStarted: false };
  const define = (key, value) => Object.defineProperty(globalThis, key, { value, configurable: true, writable: true });
  define("isSecureContext", true);
  define("ClipboardItem", class {
    static supports(type) { return type === "image/png"; }
    constructor(data) { this.data = data; }
  });
  define("navigator", { clipboard: { async write(items) {
    state.writeStarted = true;
    assert.equal(items.length, 1);
    state.output = await items[0].data["image/png"];
  } } });
  define("createImageBitmap", async blob => {
    state.input = blob;
    return { width: state.width, height: state.height, close() { state.closed++; } };
  });
  define("document", { createElement(tag) {
    assert.equal(tag, "canvas");
    state.canvas = { width: 0, height: 0, getContext() {
      return { drawImage() { state.drawn++; state.drawSize = [state.canvas.width, state.canvas.height]; } };
    }, toBlob(callback, type) {
      assert.equal(type, "image/png"); callback(new Blob(["converted-image"], { type }));
    } };
    return state.canvas;
  } });
});
afterEach(() => {
  for (const key of globals) {
    if (saved[key]) Object.defineProperty(globalThis, key, saved[key]);
    else delete globalThis[key];
  }
});

test("clipboard write starts in the click gesture before async image loading", async () => {
  const png = new Blob(["synthetic-png"], { type: "image/png" });
  let loaded = false;
  const pending = copyImageToClipboard(async () => { loaded = true; return png; });
  assert.equal(state.writeStarted, true);
  assert.equal(loaded, false);
  await pending;
  assert.equal(state.output, png);
  assert.equal(state.drawn, 0);
  assert.equal(state.closed, 1);
});

test("JPEG and WebP are converted to PNG at the original dimensions; canvas is released", async () => {
  for (const type of ["image/jpeg", "image/webp", "image/gif"]) {
    await copyImageToClipboard(async () => new Blob(["synthetic-image"], { type }));
    assert.equal(state.output.type, "image/png");
    assert.deepEqual(state.drawSize, [320, 240]);
    assert.equal(state.canvas.width, 0);
    assert.equal(state.canvas.height, 0);
  }
  assert.equal(state.closed, 3);
});

test("unsupported environments fail before requesting any private image", async () => {
  const load = () => assert.fail("image should not be fetched");
  isSecureContext = false;
  await assert.rejects(copyImageToClipboard(load), /不支持复制图片/);
  isSecureContext = true;
  navigator.clipboard = undefined;
  await assert.rejects(copyImageToClipboard(load), /不支持复制图片/);
  navigator.clipboard = { write() {} };
  ClipboardItem.supports = () => false;
  await assert.rejects(copyImageToClipboard(load), /不支持复制图片/);
});

test("permission denial cancels an outstanding image request and reports no false success", async () => {
  let signal;
  navigator.clipboard.write = async () => { throw new DOMException("blocked", "NotAllowedError"); };
  await assert.rejects(copyImageToClipboard(async value => {
    signal = value;
    return new Promise((resolve, reject) => value.addEventListener("abort", () => reject(new Error("cancelled")), { once: true }));
  }), /未允许复制图片/);
  assert.equal(signal.aborted, true);
  assert.equal(state.output, undefined);
});

test("expired authentication and network errors remain actionable", async () => {
  for (const message of ["请先登录", "连接中断，请检查网络或确认 Mac mini 已开机"]) {
    await assert.rejects(copyImageToClipboard(async () => { throw new Error(message); }), error => error.message === message);
  }
  assert.equal(state.output, undefined);
});

test("empty or corrupt images reject instead of claiming to be copied", async () => {
  await assert.rejects(copyImageToClipboard(async () => new Blob([])), /图片内容为空/);
  createImageBitmap = async () => { throw new Error("bad encoding"); };
  await assert.rejects(copyImageToClipboard(async () => new Blob(["broken"])), /图片无法解码/);
});

test("very large files and pixel dimensions have bounded copy behavior", async () => {
  const large = new Blob(["not actually large"]);
  Object.defineProperty(large, "size", { value: 50 * 1024 * 1024 + 1 });
  await assert.rejects(copyImageToClipboard(async () => large), /超过 50 MiB/);
  assert.equal(state.closed, 0);
  state.width = 6000; state.height = 6000;
  await assert.rejects(copyImageToClipboard(async () => new Blob(["image"])), /超过 3000 万像素/);
  assert.equal(state.closed, 1);
});

test("unavailable canvas, failed conversion and OS clipboard failure are explicit", async () => {
  document.createElement = () => ({ getContext: () => null });
  await assert.rejects(copyImageToClipboard(async () => new Blob(["image"])), /浏览器无法处理/);
  document.createElement = () => ({ getContext: () => ({ drawImage() {} }), toBlob(callback) { callback(null); } });
  await assert.rejects(copyImageToClipboard(async () => new Blob(["image"])), /图片转换失败/);
  navigator.clipboard.write = async () => { throw new Error("OS clipboard unavailable"); };
  await assert.rejects(copyImageToClipboard(async () => new Blob(["image"])), /图片复制失败/);
});

test("stalled image loading or clipboard write times out and aborts the request", async () => {
  let expire, cleared = 0;
  setTimeout = callback => { expire = callback; return 1; };
  clearTimeout = () => { cleared++; };
  let signal;
  const loading = copyImageToClipboard(async value => { signal = value; return new Promise(() => {}); });
  await Promise.resolve();
  expire();
  await assert.rejects(loading, /图片复制超时/);
  assert.equal(signal.aborted, true);
  navigator.clipboard.write = () => new Promise(() => {});
  const writing = copyImageToClipboard(async () => new Blob(["png"], { type: "image/png" }));
  expire();
  await assert.rejects(writing, /图片复制超时/);
  assert.equal(cleared, 2);
});
