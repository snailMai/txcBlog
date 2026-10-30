import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import vm from "node:vm";
import { withDeadline } from "../transfer/static/transfer-io.mjs";

const source = (await readFile(new URL("../transfer/static/app.js", import.meta.url), "utf8"))
  .replace(/^import .*\.mjs.*;\n/gm, "");
const records = ["first", "second", "third", "fourth", "fifth", "sixth", "seventh"].map((text, index) => ({
  id: String(index + 1).repeat(32), text, device: "Windows", created: 30 - index, files: [],
}));
const settle = async () => { await new Promise(resolve => setImmediate(resolve)); await new Promise(resolve => setImmediate(resolve)); };

async function harness(deleteReply = () => new Response('{"ok":true}'), initialRows = records) {
  const ids = new Map(), requests = [], intervals = [], timers = [];
  const copiedText = [];
  const settings = {};
  const positions = new Map(initialRows.map((row, index) => [row.id, index]));
  let newest = 0;
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
        const rows = serverRows.filter(row => (!params.get("q") || row.text.includes(params.get("q"))) &&
          (!params.get("kind") || params.get("kind") === "all" || (params.get("kind") === "text" ? !!row.text : row.files.some(file => params.get("kind") === "image" ? file.mime.startsWith("image/") : !file.mime.startsWith("image/")))));
        const visible = params.has("before") ? rows.filter(row => positions.get(row.id) > positions.get(params.get("before"))) : rows;
        const size = Number(params.get("page_size") || 30);
        const reply = new Response(JSON.stringify({ messages: visible.slice(0, size), next: visible.length > size ? visible[size - 1].id : null, total: rows.length, latest: rows[0]?.id || null }));
        return settings.historyReply ? settings.historyReply(reply, params) : reply;
      }
      if (url.endsWith("/api/stats")) return new Response(JSON.stringify({ count: serverRows.length, used: 0, quota: 1000, reserved: 0 }));
      throw new Error("Unexpected synthetic request");
    },
  });
  vm.runInContext(source + "\nglobalThis.testState = state;", context);
  await settle();
  return {
    node: element, boxes, requests, state: context.testState, copiedText, settings,
    cards: () => descendants(element("history-list")).filter(node => node.className === "record"),
    descendants,
    deletes: () => requests.filter(request => request.options.method === "DELETE"),
    async select(index, checked = true) { const box = boxes()[index]; box.checked = checked; await box.emit("change"); },
    async selectAll(checked) { const node = element("select-all-records"); node.checked = checked; await node.emit("change", { target: node }); },
    async search(text) { element("search").value = text; element("search").emit("input"); timers.findLast(timer => timer.delay === 250).fn(); await settle(); },
    async filter(kind) { filters.find(node => node.dataset.kind === kind).emit("click"); await settle(); },
    async poll() { for (const fn of intervals) fn(); await settle(); },
    async next() { await element("next-page").emit("click"); await settle(); },
    async previous() { await element("previous-page").emit("click"); await settle(); },
    async size(size) { element("page-size").value = String(size); await element("page-size").emit("change"); await settle(); },
    async refresh() { element("refresh-button").emit("click"); await settle(); },
    prepend(row) { positions.set(row.id, --newest); serverRows.unshift(row); },
    removeRows(ids) { serverRows = serverRows.filter(row => !ids.includes(row.id)); },
    singleDelete: () => descendants(element("history-list")).find(node => node.textContent === "删除记录").click(),
  };
}

test("selection is limited to the current page, survives polling and clears on page changes", async () => {
  const h = await harness();
  assert.equal(h.boxes().length, 5);
  assert.equal(h.node("batch-delete-button").disabled, true);
  await h.select(0);
  assert.equal(h.node("select-all-records").indeterminate, true);
  assert.equal(h.node("selected-count").textContent, "已选 1 条");
  await h.poll();
  assert.equal(h.boxes()[0].checked, true);
  await h.selectAll(true);
  assert.equal(h.node("selected-count").textContent, "已选 5 条");
  await h.next();
  assert.equal(h.boxes().length, 2);
  assert.equal(h.node("selected-count").textContent, "已选 0 条");
  assert.ok(h.boxes().every(box => !box.checked));
  assert.equal(h.node("next-page").disabled, true);
  await h.previous();
  assert.equal(h.boxes().length, 5);
  await h.selectAll(false);
  assert.ok(h.boxes().every(box => !box.checked));
});

test("batch deletion requires confirmation and sends only the frozen selected IDs with CSRF", async () => {
  const h = await harness();
  await h.selectAll(true);
  await h.node("batch-delete-button").click();
  assert.equal(h.deletes().length, 0);
  assert.match(h.node("confirm-title").textContent, /5 条/);
  const calls = h.requests.length;
  await h.poll();
  assert.equal(h.requests.length, calls);
  await h.selectAll(false); // A changed selection cannot alter the already-confirmed target set.
  await h.node("confirm-ok").click();
  const request = h.deletes()[0];
  assert.equal(request.url, "/transfer/api/messages");
  assert.deepEqual(JSON.parse(request.options.body).ids, records.slice(0, 5).map(row => row.id));
  assert.equal(request.options.headers["X-CSRF-Token"], "synthetic-csrf");
  assert.equal(h.node("confirm-dialog").open, false);
  assert.equal(h.node("selected-count").textContent, "已选 0 条");
  assert.equal(h.boxes().length, 2);
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
  assert.equal(h.node("next-page").disabled, true);
  assert.equal(h.node("page-size").disabled, true);
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

const manyRecords = count => Array.from({ length: count }, (_, index) => ({
  ...records[0], id: (index + 1).toString(16).padStart(32, "0"), text: `item ${index}`, created: 100 - index,
}));

test("5/10/20 choices replace pages, reset to the first page and reject invalid values", async () => {
  const h = await harness(undefined, manyRecords(43));
  assert.equal(h.boxes().length, 5);
  assert.equal(h.node("page-status").textContent, "第 1 页");
  assert.equal(h.node("previous-page").disabled, true);
  assert.equal(h.node("pagination-total").textContent, "共 43 条");
  await h.next();
  assert.equal(h.boxes().length, 5);
  assert.equal(h.state.page, 1);
  for (const size of [10, 20, 5]) {
    await h.select(0); await h.size(size);
    assert.equal(h.boxes().length, size);
    assert.equal(h.state.page, 0);
    assert.equal(h.node("selected-count").textContent, "已选 0 条");
    const request = h.requests.filter(r => r.url.startsWith("/transfer/api/messages?")).at(-1);
    assert.equal(new URLSearchParams(request.url.split("?")[1]).get("page_size"), String(size));
  }
  const calls = h.requests.length;
  await h.size(7);
  assert.equal(h.requests.length, calls);
  assert.equal(h.node("page-size").value, "5");
  await h.size(20); await h.next(); await h.next();
  assert.equal(h.boxes().length, 3);
  assert.equal(h.node("next-page").disabled, true);
  const terminalCalls = h.requests.length;
  await h.next(); assert.equal(h.requests.length, terminalCalls);
});

test("search and type changes reset the page and keep matching totals independent of global storage stats", async () => {
  const rows = manyRecords(12).map((row, index) => ({ ...row, files: index % 2 ? [imageFile] : [] }));
  const h = await harness(undefined, rows);
  await h.next(); await h.select(0); await h.filter("image");
  assert.equal(h.state.page, 0);
  assert.equal(h.boxes().length, 5);
  assert.equal(h.node("pagination-total").textContent, "匹配 6 条");
  await h.size(10);
  assert.equal(h.boxes().length, 6);
  assert.equal(h.node("records-caption").textContent, "匹配 6 条");
  await h.search("item 11");
  assert.equal(h.boxes().length, 1);
  assert.equal(h.node("pagination-total").textContent, "匹配 1 条");
  await h.poll();
  assert.equal(h.node("records-caption").textContent, "匹配 1 条");
});

test("polling old pages or a selected first page only signals updates until explicit navigation", async () => {
  const h = await harness();
  await h.next();
  const older = h.boxes().map(box => box.dataset.recordId);
  h.prepend({ ...records[0], id: "e".repeat(32), text: "new arrival" });
  await h.poll();
  assert.deepEqual(h.boxes().map(box => box.dataset.recordId), older);
  assert.equal(h.state.page, 1);
  assert.equal(h.node("history-updated").hidden, false);
  await h.refresh();
  assert.equal(h.state.page, 0);
  assert.equal(h.boxes()[0].dataset.recordId, "e".repeat(32));
  assert.equal(h.node("history-updated").hidden, true);
  await h.select(4);
  const selectedPage = h.boxes().map(box => box.dataset.recordId);
  h.prepend({ ...records[0], id: "f".repeat(32), text: "second new arrival" });
  await h.poll();
  assert.deepEqual(h.boxes().map(box => box.dataset.recordId), selectedPage);
  assert.equal(h.boxes()[4].checked, true);
  assert.equal(h.node("history-updated").hidden, false);
});

test("deleting the last page returns to a valid previous page and deleting everything leaves an empty first page", async () => {
  const h = await harness();
  await h.next(); await h.selectAll(true); await h.node("batch-delete-button").click(); await h.node("confirm-ok").click();
  assert.deepEqual(JSON.parse(h.deletes()[0].options.body).ids, records.slice(5).map(row => row.id));
  assert.equal(h.state.page, 0);
  assert.equal(h.boxes().length, 5);
  assert.equal(h.node("pagination-total").textContent, "共 5 条");
  await h.selectAll(true); await h.node("batch-delete-button").click(); await h.node("confirm-ok").click();
  assert.equal(h.boxes().length, 0);
  assert.equal(h.state.page, 0);
  assert.equal(h.node("next-page").disabled, true);
  assert.equal(h.node("previous-page").disabled, true);
  assert.equal(h.node("pagination-total").textContent, "共 0 条");
});

test("failed page navigation retains the current page and restores controls for retry", async () => {
  const h = await harness();
  h.settings.historyReply = () => new Response('{"error":"synthetic failure"}', { status: 503 });
  const first = h.boxes().map(box => box.dataset.recordId);
  await h.select(0); await h.next();
  assert.equal(h.state.pageLoading, false);
  assert.equal(h.state.page, 0);
  assert.deepEqual(h.boxes().map(box => box.dataset.recordId), first);
  assert.equal(h.node("next-page").disabled, false);
  assert.equal(h.node("selected-count").textContent, "已选 0 条");
  delete h.settings.historyReply; await h.next();
  assert.equal(h.state.page, 1);
});

test("a late search response cannot replace the latest search results or pagination status", async () => {
  const h = await harness();
  let finish;
  h.settings.historyReply = (reply, params) => params.get("q") === "first" ? new Promise(resolve => { finish = () => resolve(reply); }) : reply;
  await h.search("first");
  assert.equal(h.state.pageLoading, true);
  await h.search("second");
  assert.equal(h.boxes()[0].dataset.recordId, records[1].id);
  finish(); await settle();
  assert.equal(h.boxes()[0].dataset.recordId, records[1].id);
  assert.equal(h.node("pagination-total").textContent, "匹配 1 条");
  assert.equal(h.node("page-status").textContent, "第 1 页");
});

test("a failed reset never leaves previous filter results selectable under the new query", async () => {
  const h = await harness();
  await h.next();
  h.settings.historyReply = () => new Response('{"error":"synthetic failure"}', { status: 503 });
  await h.search("second");
  assert.equal(h.boxes().length, 0);
  assert.equal(h.state.pageLoading, false);
  assert.equal(h.node("page-size").disabled, false);
  delete h.settings.historyReply; await h.refresh();
  assert.equal(h.boxes().length, 1);
  assert.equal(h.boxes()[0].dataset.recordId, records[1].id);
});

test("records removed by another device can empty a page without trapping navigation", async () => {
  const rows = manyRecords(12), h = await harness(undefined, rows);
  await h.next();
  h.removeRows(rows.map(row => row.id));
  await h.next();
  assert.equal(h.state.page, 0);
  assert.equal(h.boxes().length, 0);
  assert.equal(h.node("page-status").textContent, "第 1 页");
  assert.equal(h.node("next-page").disabled, true);
});
