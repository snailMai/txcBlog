import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import vm from "node:vm";
import { withDeadline } from "../transfer/static/transfer-io.mjs";

const source = (await readFile(new URL("../transfer/static/app.js", import.meta.url), "utf8"))
  .replace(/^import .*\.mjs.*;\n/gm, "");
const records = ["first", "second", "third"].map((text, index) => ({
  id: String(index + 1).repeat(32), text, device: "Windows", created: 30 - index, files: [],
}));
const settle = async () => { await new Promise(resolve => setImmediate(resolve)); await new Promise(resolve => setImmediate(resolve)); };

async function harness(deleteReply = () => new Response('{"ok":true}'), initialRows = records) {
  const ids = new Map(), requests = [], intervals = [], timers = [];
  const copiedText = [];
  let serverRows = [...initialRows];
  function element(id = "") {
    if (id && ids.has(id)) return ids.get(id);
    const listeners = new Map();
    const node = {
      children: [], dataset: {}, textContent: "", value: "", disabled: false, checked: false, open: false,
      classList: { toggle() {} },
      append(...items) { this.children.push(...items); },
      replaceChildren(...items) { this.children = items; },
      setAttribute(key, value) { this[key] = value; },
      addEventListener(type, callback) { listeners.set(type, callback); },
      emit(type, event = { preventDefault() {} }) { return listeners.get(type)?.(event); },
      click() { return this.emit("click"); },
      showModal() { this.open = true; }, close() { this.open = false; this.emit("close"); },
      reset() {}, focus() {},
    };
    if (id) ids.set(id, node);
    return node;
  }
  const filters = ["all", "text", "image", "file"].map(kind => {
    const node = element(); node.dataset.kind = kind; return node;
  });
  function descendants(node) { return [node, ...node.children.flatMap(descendants)]; }
  const boxes = () => descendants(element("history-list")).filter(node => node.className === "record-checkbox");
  const context = vm.createContext({
    withDeadline,
    document: { body: { dataset: { base: "/transfer" } }, hidden: false,
      getElementById: element, createElement: () => element(), createDocumentFragment: () => element(),
      createTextNode: text => ({ children: [], textContent: text }),
      querySelectorAll: selector => selector === "[data-kind]" ? filters : selector === ".record-checkbox" ? boxes() : [],
      querySelector: () => filters[0], addEventListener() {} },
    navigator: { userAgent: "Windows", clipboard: { async writeText(text) { copiedText.push(text); } } }, window: { addEventListener() {} },
    location: { search: "" }, URLSearchParams,
    localStorage: { getItem: () => "[]", setItem() {}, removeItem() {} },
    setTimeout(fn, delay) { timers.push({ fn, delay }); return timers.length; }, clearTimeout() {},
    setInterval(fn) { intervals.push(fn); },
    async fetch(url, options) {
      requests.push({ url, options });
      if (options.method === "DELETE") {
        const response = await deleteReply();
        if (response.ok) {
          const deleted = options.body ? JSON.parse(options.body).ids : [url.split("/").at(-1)];
          serverRows = serverRows.filter(row => !deleted.includes(row.id));
        }
        return response;
      }
      if (url.endsWith("/api/session")) return new Response('{"configured":true,"authenticated":true,"csrf":"synthetic-csrf"}');
      if (url.startsWith("/transfer/api/messages")) {
        const params = new URLSearchParams(url.split("?")[1]);
        const rows = serverRows.filter(row => !params.get("q") || row.text.includes(params.get("q")));
        return new Response(JSON.stringify({ messages: params.has("before") ? rows.slice(2) : rows.slice(0, 2), next: rows.length > 2 && !params.has("before") ? "cursor" : null }));
      }
      if (url.endsWith("/api/stats")) return new Response(JSON.stringify({ count: serverRows.length, used: 0, quota: 1000, reserved: 0 }));
      throw new Error("Unexpected synthetic request");
    },
  });
  vm.runInContext(source + "\nglobalThis.testState = state;", context);
  await settle();
  return {
    node: element, boxes, requests, state: context.testState, copiedText,
    cards: () => descendants(element("history-list")).filter(node => node.className === "record"),
    descendants,
    deletes: () => requests.filter(request => request.options.method === "DELETE"),
    async select(index, checked = true) { const box = boxes()[index]; box.checked = checked; await box.emit("change"); },
    async selectAll(checked) { const node = element("select-all-records"); node.checked = checked; await node.emit("change", { target: node }); },
    async search(text) { element("search").value = text; element("search").emit("input"); timers.findLast(timer => timer.delay === 250).fn(); await settle(); },
    async filter(kind) { filters.find(node => node.dataset.kind === kind).emit("click"); await settle(); },
    async poll() { for (const fn of intervals) fn(); await settle(); },
    async loadMore() { await element("load-more").emit("click"); await settle(); },
    singleDelete: () => descendants(element("history-list")).find(node => node.textContent === "删除记录").click(),
  };
}

test("checkbox selection and select-all are limited to loaded rows and survive polling/pagination", async () => {
  const h = await harness();
  assert.equal(h.boxes().length, 2);
  assert.equal(h.node("batch-delete-button").disabled, true);
  await h.select(0);
  assert.equal(h.node("select-all-records").indeterminate, true);
  assert.equal(h.node("selected-count").textContent, "已选 1 条");
  await h.poll();
  assert.equal(h.boxes()[0].checked, true);
  await h.selectAll(true);
  assert.equal(h.node("selected-count").textContent, "已选 2 条");
  await h.loadMore();
  assert.equal(h.boxes().length, 3);
  assert.equal(h.boxes()[2].checked, false);
  assert.equal(h.node("select-all-records").indeterminate, true);
  await h.selectAll(false);
  assert.ok(h.boxes().every(box => !box.checked));
});

test("batch deletion requires confirmation and sends only the frozen selected IDs with CSRF", async () => {
  const h = await harness();
  await h.selectAll(true);
  await h.node("batch-delete-button").click();
  assert.equal(h.deletes().length, 0);
  assert.match(h.node("confirm-title").textContent, /2 条/);
  const calls = h.requests.length;
  await h.poll();
  assert.equal(h.requests.length, calls);
  await h.selectAll(false); // A changed selection cannot alter the already-confirmed target set.
  await h.node("confirm-ok").click();
  const request = h.deletes()[0];
  assert.equal(request.url, "/transfer/api/messages");
  assert.deepEqual(JSON.parse(request.options.body).ids, records.slice(0, 2).map(row => row.id));
  assert.equal(request.options.headers["X-CSRF-Token"], "synthetic-csrf");
  assert.equal(h.node("confirm-dialog").open, false);
  assert.equal(h.node("selected-count").textContent, "已选 0 条");
  assert.equal(h.boxes().length, 1);
});

test("cancel and filter/search changes never delete hidden selections", async () => {
  const h = await harness();
  await h.select(0); await h.node("batch-delete-button").click();
  await h.node("confirm-cancel").click();
  assert.equal(h.deletes().length, 0);
  assert.equal(h.boxes()[0].checked, true);
  await h.filter("text");
  assert.equal(h.node("selected-count").textContent, "已选 0 条");
  await h.select(0); await h.search("second");
  assert.equal(h.node("selected-count").textContent, "已选 0 条");
  assert.equal(h.boxes().length, 1);
  assert.equal(h.boxes()[0].checked, false);
});

test("failed deletion keeps the selected rows and dialog ready for retry", async () => {
  let fail = true;
  const h = await harness(() => fail ? new Response('{"error":"暂时无法删除"}', { status: 503 }) : new Response('{"ok":true}'));
  await h.select(0); await h.node("batch-delete-button").click();
  await h.node("confirm-ok").click();
  assert.equal(h.node("confirm-dialog").open, true);
  assert.equal(h.node("confirm-ok").disabled, false);
  assert.equal(h.boxes()[0].checked, true);
  assert.match(h.node("toast").textContent, /暂时无法删除/);
  fail = false;
  await h.node("confirm-ok").click();
  assert.equal(h.node("confirm-dialog").open, false);
  assert.equal(h.boxes()[0].dataset.recordId, records[1].id);
});

test("pending deletion blocks duplicate requests, cancellation and checkbox edits", async () => {
  let complete;
  const h = await harness(() => new Promise(resolve => { complete = resolve; }));
  await h.select(0); await h.node("batch-delete-button").click();
  const request = h.node("confirm-ok").click();
  await h.node("confirm-ok").click();
  assert.equal(h.deletes().length, 1);
  assert.equal(h.node("confirm-cancel").disabled, true);
  assert.ok(h.boxes().every(box => box.disabled));
  let prevented = false;
  h.node("confirm-dialog").emit("cancel", { preventDefault() { prevented = true; } });
  assert.equal(prevented, true);
  complete(new Response('{"ok":true}'));
  await request;
  assert.equal(h.state.deleting, false);
});

test("original single-record deletion still uses its existing endpoint", async () => {
  const h = await harness();
  await h.singleDelete(); await h.node("confirm-ok").click();
  assert.equal(h.deletes()[0].url, "/transfer/api/messages/" + records[0].id);
  assert.equal(h.deletes()[0].options.body, undefined);
  assert.equal(h.boxes()[0].dataset.recordId, records[1].id);
});

test("text actions in the header copy original content and still confirm deletion", async () => {
  const text = "保留原文\nhttps://example.com/?a=1&b=2";
  const h = await harness(undefined, [{ ...records[0], text }]);
  const card = h.cards()[0], top = card.children[0];
  const actions = top.children.find(node => node.className === "record-actions");
  assert.deepEqual(actions.children.map(node => node.textContent), ["复制文字", "删除记录"]);
  assert.equal(h.descendants(card).filter(node => node.className === "record-actions").length, 1);
  await actions.children[0].click();
  assert.equal(h.copiedText[0], text);
  await actions.children[1].click();
  assert.equal(h.deletes().length, 0);
  await h.node("confirm-ok").click();
  assert.equal(h.deletes()[0].url, "/transfer/api/messages/" + records[0].id);
});

const imageFile = { id: "a".repeat(32), name: "image.png", size: 30, mime: "image/png", thumb_size: 12 };
const plainFile = { id: "b".repeat(32), name: "示例表格.xlsx", size: 40, mime: "application/octet-stream", thumb_size: 0 };
const controls = (h, card) => h.descendants(card.children[0]).filter(node => node.textContent === "下载" || /^(复制图片|复制文字|删除记录)$/.test(node.textContent));

test("image and file actions retain exact order and authenticated file targets without duplicate controls", async () => {
  const h = await harness(undefined, [
    { ...records[0], text: "", files: [imageFile] },
    { ...records[1], text: "", files: [plainFile] },
  ]);
  const [image, file] = h.cards();
  assert.deepEqual(controls(h, image).map(node => node.textContent), ["复制图片", "下载", "删除记录"]);
  assert.deepEqual(controls(h, file).map(node => node.textContent), ["下载", "删除记录"]);
  assert.equal(controls(h, image)[0]["aria-label"], "复制图片：image.png");
  assert.equal(controls(h, image)[1].href, `/transfer/api/files/${imageFile.id}/download`);
  assert.equal(controls(h, file)[0].href, `/transfer/api/files/${plainFile.id}/download`);
  assert.ok(h.descendants(image).some(node => node.href === `/transfer/api/files/${imageFile.id}/preview`));
  assert.equal(h.descendants(image).filter(node => node.className === "download").length, 1);
  assert.ok(!h.descendants(image).some(node => node.textContent === "下载原图" || node.textContent === "↓" || node.className === "file-actions"));
});

test("mixed multi-attachment records keep each file identifiable and deletion last after polling", async () => {
  const h = await harness(undefined, [{ ...records[0], files: [imageFile, plainFile] }]);
  for (let attempt = 0; attempt < 2; attempt++) {
    const card = h.cards()[0];
    const groups = h.descendants(card.children[0]).filter(node => node.className === "record-file-actions");
    assert.deepEqual(groups.map(group => group.children[0].textContent), [imageFile.name, plainFile.name]);
    assert.deepEqual(controls(h, card).map(node => node.textContent), ["复制文字", "复制图片", "下载", "下载", "删除记录"]);
    assert.deepEqual(controls(h, card).filter(node => node.textContent === "下载").map(node => node.href), [imageFile, plainFile].map(file => `/transfer/api/files/${file.id}/download`));
    await h.poll();
  }
});
