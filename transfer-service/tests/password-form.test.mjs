import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import vm from "node:vm";
import { withDeadline } from "../transfer/static/transfer-io.mjs";

const source = (await readFile(new URL("../transfer/static/app.js", import.meta.url), "utf8"))
  .replace(/^import .*\.mjs.*;\n/gm, "");

async function harness(reply = () => new Response('{"ok":true}')) {
  const nodes = new Map();
  const calls = [];
  let authenticated = true;
  function node(id = "") {
    if (id && nodes.has(id)) return nodes.get(id);
    const listeners = new Map();
    const value = {
      value: "", textContent: "", disabled: false, hidden: false, open: false, dataset: {},
      classList: { toggle() {} },
      addEventListener(type, listener) { listeners.set(type, listener); },
      async emit(type, event = { preventDefault() {} }) { return listeners.get(type)?.(event); },
      append() {}, replaceChildren() {}, setAttribute() {}, focus() {},
      showModal() { this.open = true; },
      close() { this.open = false; void this.emit("close"); },
      reset() { for (const id of ["current-password", "new-password", "confirm-password"]) node(id).value = ""; },
    };
    if (id) nodes.set(id, value);
    return value;
  }
  const storage = new Map([["snail-transfer-draft-v1", "[]"]]);
  const context = vm.createContext({
    withDeadline,
    document: { body: { dataset: { base: "/transfer" } }, hidden: false,
      getElementById: node, createElement: () => node(), createDocumentFragment: () => node(),
      querySelectorAll: () => [], addEventListener() {} },
    navigator: { userAgent: "Windows" }, window: { addEventListener() {} },
    location: { search: "" }, URLSearchParams,
    localStorage: { getItem: key => storage.get(key), setItem: (key, value) => storage.set(key, value), removeItem: key => storage.delete(key) },
    setTimeout: () => 1, clearTimeout() {}, setInterval() {},
    async fetch(url, options) {
      calls.push({ url, options });
      if (url === "/transfer/api/session") return new Response(JSON.stringify({ authenticated, configured: true, csrf: "synthetic-csrf" }));
      if (url.startsWith("/transfer/api/messages")) return new Response('{"messages":[],"next":null,"total":0,"latest":null}');
      if (url === "/transfer/api/stats") return new Response('{"count":0,"used":0,"quota":20000000000,"reserved":0}');
      if (url === "/transfer/api/password") {
        const response = await reply();
        if (response.ok || response.status === 401) authenticated = false;
        return response;
      }
      throw new Error("Unexpected synthetic request: " + url);
    },
  });
  vm.runInContext(source + "\nglobalThis.inspectState = () => state;", context);
  await new Promise(resolve => setImmediate(resolve));
  await new Promise(resolve => setImmediate(resolve));
  await node("change-password-button").emit("click");
  function fill(password = "0123456789", confirmation = password) {
    node("current-password").value = "synthetic-old-password";
    node("new-password").value = password;
    node("confirm-password").value = confirmation;
  }
  return { node, fill, calls, state: context.inspectState, storage,
    submit: () => node("password-form").emit("submit"),
    changes: () => calls.filter(call => call.url === "/transfer/api/password") };
}

test("ten-character password submits once with CSRF and returns to login without losing drafts", async () => {
  const h = await harness();
  h.node("message-text").value = "未发送草稿";
  h.fill(" 12345678 ");
  await h.submit();
  assert.equal(h.changes().length, 1);
  const request = h.changes()[0].options;
  assert.equal(request.headers["X-CSRF-Token"], "synthetic-csrf");
  assert.equal(JSON.parse(request.body).new_password, " 12345678 ");
  assert.equal(h.state().authenticated, false);
  assert.equal(h.node("password-dialog").open, false);
  assert.equal(h.node("current-password").value, "");
  assert.equal(h.node("new-password").value, "");
  assert.equal(h.node("message-text").value, "未发送草稿");
  assert.equal(h.storage.get("snail-transfer-draft-v1"), "[]");
  assert.match(h.node("toast").textContent, /密码已修改/);
});

test("invalid lengths and mismatched confirmation never submit", async () => {
  const h = await harness();
  for (const [password, confirmation] of [["a".repeat(9), undefined], ["a".repeat(257), undefined], ["0123456789", "mismatch"]]) {
    h.fill(password, confirmation);
    await h.submit();
    assert.equal(h.changes().length, 0);
    assert.equal(h.node("password-dialog").open, true);
    assert.ok(h.node("password-error").textContent);
  }
});

test("wrong old password leaves the dialog usable and clears the old password", async () => {
  const h = await harness(() => new Response('{"error":"旧密码不正确"}', { status: 400 }));
  h.fill(); await h.submit();
  assert.equal(h.node("password-dialog").open, true);
  assert.equal(h.node("current-password").value, "");
  assert.equal(h.node("password-error").textContent, "旧密码不正确");
  assert.equal(h.node("password-submit").disabled, false);
  assert.equal(h.state().authenticated, true);
});

test("pending submit blocks duplicates and Escape; cancellation clears sensitive fields", async () => {
  let complete;
  const h = await harness(() => new Promise(resolve => { complete = resolve; }));
  h.fill(); const first = h.submit();
  await h.submit();
  assert.equal(h.changes().length, 1);
  assert.equal(h.node("password-cancel").disabled, true);
  let prevented = false;
  await h.node("password-dialog").emit("cancel", { preventDefault() { prevented = true; } });
  assert.equal(prevented, true);
  complete(new Response('{"error":"稍后重试"}', { status: 429 }));
  await first;
  await h.node("password-cancel").emit("click");
  assert.equal(h.node("new-password").value, "");
  assert.equal(h.node("password-dialog").open, false);
});

test("expired login closes the password form and reports a visible login error", async () => {
  const h = await harness(() => new Response('{"error":"请先登录"}', { status: 401 }));
  h.fill(); await h.submit();
  assert.equal(h.node("password-dialog").open, false);
  assert.equal(h.node("auth-screen").hidden, false);
  assert.match(h.node("toast").textContent, /登录已失效/);
});
