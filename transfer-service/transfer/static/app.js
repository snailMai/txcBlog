"use strict";

import { copyImageToClipboard, MAX_COPY_BYTES } from "./image-clipboard.mjs";

const $ = (id) => document.getElementById(id);
const BASE = document.body.dataset.base || "";
const endpoint = path => BASE + path;
const state = { csrf: "", authenticated: false, configured: false, localSetup: false,
  files: [], sending: false, changingPassword: false, deleting: false, kind: "all", next: null, rows: [], search: "", request: 0,
  snapshot: "", clientId: "", signature: "", toastTimer: null, loadedMore: false };
const DEVICE = /Android/i.test(navigator.userAgent) ? "Android" : /Windows/i.test(navigator.userAgent) ? "Windows" : /Mac/i.test(navigator.userAgent) ? "Mac" : "其他设备";
const QUEUE_KEY = "snail-transfer-draft-v1";
const CHUNK_SIZE = 8 * 1024 * 1024;
const FILE_LIMIT = 500000000;
const copyingImages = new Set();
const selectedRecords = new Set();

// Only product labels are normalized. User text and filenames remain unchanged.
function formatUI(value) {
  return String(value).replace(/—+/g, "--")
    .replace(/([\u4e00-\u9fff])([A-Za-z0-9])/g, "$1 $2")
    .replace(/([A-Za-z0-9])([\u4e00-\u9fff])/g, "$1 $2")
    .replace(/([\u4e00-\u9fff])[,]/g, "$1，")
    .replace(/([\u4e00-\u9fff])[:]/g, "$1：");
}
function el(tag, cls, text, user = false) {
  const node = document.createElement(tag);
  if (cls) node.className = cls;
  if (text !== undefined) node.textContent = user ? String(text) : formatUI(text);
  return node;
}
function label(id, value) { $(id).textContent = formatUI(value); }
function bytes(size) {
  if (size < 1000) return `${size} B`;
  if (size < 1000000) return `${(size / 1000).toFixed(1)} KB`;
  if (size < 1000000000) return `${(size / 1000000).toFixed(1)} MB`;
  return `${(size / 1000000000).toFixed(1)} GB`;
}
function toast(message, error = false) {
  label("toast", message); $("toast").classList.toggle("error", error); $("toast").hidden = false;
  clearTimeout(state.toastTimer); state.toastTimer = setTimeout(() => { $("toast").hidden = true; }, error ? 6500 : 3200);
}
async function api(url, options = {}) {
  const headers = { ...options.headers };
  if (options.body && typeof options.body === "string") headers["Content-Type"] = "application/json";
  if (options.method && options.method !== "GET") headers["X-CSRF-Token"] = state.csrf;
  let response;
  try { response = await fetch(endpoint(url), { ...options, headers, credentials: "same-origin", cache: "no-store" }); }
  catch { throw new Error("连接中断，请检查网络或确认 Mac mini 已开机"); }
  if (!response.ok) {
    const data = await response.json().catch(() => ({}));
    if (response.status === 401 && state.authenticated) {
      state.authenticated = false; showAuth();
      state.rows = []; $("history-list").replaceChildren();
    }
    const error = new Error(data.error || `请求未完成（${response.status}）`);
    error.status = response.status; throw error;
  }
  return response;
}
async function json(url, options = {}) { return (await api(url, options)).json(); }
function showAuth() {
  $("password-dialog").close(); $("password-form").reset();
  $("confirm-dialog").close(); clearSelection();
  $("workspace").hidden = true; $("auth-screen").hidden = false;
  const setup = !state.configured;
  $("login-form").hidden = setup && !state.localSetup;
  $("setup-hint").hidden = !setup;
  label("auth-description", setup ? (state.localSetup ? "先设置一个密码，开启你的私人传输空间。" : "请先在 Mac mini 上通过初始化命令设置访问密码。") : "输入密码，继续在你的设备之间传送。");
  label("login-button", setup ? "设置密码并进入 ↗" : "进入传输空间 ↗");
  $("password").autocomplete = setup ? "new-password" : "current-password";
  $("password").minLength = setup ? 10 : 1;
}
async function boot() {
  try {
    const info = await json("/api/session");
    Object.assign(state, { csrf: info.csrf, authenticated: info.authenticated, configured: info.configured, localSetup: info.local_setup });
    if (state.authenticated) await showWorkspace(); else showAuth();
  } catch (error) { label("auth-description", "暂时无法连接传输空间。"); label("auth-error", error.message); }
}
async function showWorkspace() {
  $("auth-screen").hidden = true; $("workspace").hidden = false; label("device-label", `来自 ${DEVICE}`);
  if (!state.files.length) restoreDraft();
  await Promise.all([refreshHistory(), refreshStats()]);
  await readSharedDraft();
}
$("login-form").addEventListener("submit", async (event) => {
  event.preventDefault(); $("login-button").disabled = true; label("auth-error", "");
  try {
    const password = $("password").value;
    if (!state.configured) { await json("/api/setup", { method: "POST", body: JSON.stringify({ password }) }); state.configured = true; }
    const info = await json("/api/login", { method: "POST", body: JSON.stringify({ password }) });
    state.csrf = info.csrf; state.authenticated = true; $("password").value = "";
    await showWorkspace();
  } catch (error) { label("auth-error", error.message); }
  finally { $("login-button").disabled = false; }
});
$("logout-button").addEventListener("click", async () => {
  if (state.sending) return toast("请等待当前发送完成", true);
  try {
    await json("/api/logout", { method: "POST" });
    state.authenticated = false; state.rows = []; state.signature = ""; state.files = [];
    $("message-text").value = ""; $("history-list").replaceChildren(); $("attachment-list").replaceChildren();
    localStorage.removeItem(QUEUE_KEY);
    await clearSharedDrafts(); await boot();
  } catch (error) { toast(error.message, true); }
});

$("change-password-button").addEventListener("click", () => {
  if (state.sending) return toast("请等待当前发送完成后再修改密码", true);
  $("password-form").reset(); label("password-error", "");
  $("password-dialog").showModal(); $("current-password").focus();
});
$("password-cancel").addEventListener("click", () => $("password-dialog").close());
$("password-dialog").addEventListener("close", () => $("password-form").reset());
$("password-dialog").addEventListener("cancel", event => { if (state.changingPassword) event.preventDefault(); });
$("password-form").addEventListener("submit", async event => {
  event.preventDefault();
  if (state.changingPassword) return;
  const newPassword = $("new-password").value;
  if (Array.from(newPassword).length < 10 || Array.from(newPassword).length > 256) return label("password-error", "新密码需要 10～256 个字符");
  if (newPassword !== $("confirm-password").value) return label("password-error", "两次新密码不一致");
  state.changingPassword = true; label("password-error", "");
  $("password-submit").disabled = true; $("password-cancel").disabled = true;
  label("password-submit", "正在保存…");
  try {
    await json("/api/password", { method: "POST", body: JSON.stringify({ current_password: $("current-password").value, new_password: newPassword, confirm_password: $("confirm-password").value }) });
    state.authenticated = false; state.rows = []; state.signature = "";
    $("history-list").replaceChildren(); $("password").value = "";
    showAuth(); await boot();
    toast("密码已修改，所有设备需使用新密码重新登录");
  } catch (error) {
    label("password-error", error.message); $("current-password").value = "";
    if (!state.authenticated) toast("登录已失效，请重新登录后再修改密码", true);
  } finally {
    state.changingPassword = false;
    $("password-submit").disabled = false; $("password-cancel").disabled = false;
    label("password-submit", "保存新密码");
  }
});

async function digest(blob) {
  const hash = await crypto.subtle.digest("SHA-256", await blob.arrayBuffer());
  return Array.from(new Uint8Array(hash), (byte) => byte.toString(16).padStart(2, "0")).join("");
}
async function fingerprint(file) {
  return digest(new Blob([`${file.name}:${file.size}:`, file.slice(0, 65536), file.slice(Math.max(0, file.size - 65536))]));
}
function rememberDraft() {
  try { localStorage.setItem(QUEUE_KEY, JSON.stringify(state.files.filter(f => f.id).map(({ id, name, size, fingerprint: fp }) => ({ id, name, size, fingerprint: fp })))); }
  catch { toast("浏览器无法保存续传信息，请保持当前页面打开", true); }
}
function restoreDraft() {
  try {
    const items = JSON.parse(localStorage.getItem(QUEUE_KEY) || "[]");
    if (Array.isArray(items)) state.files = items.filter(f => /^[a-f0-9]{32}$/.test(f.id) && typeof f.name === "string" && typeof f.size === "number").slice(0, 20).map(f => ({ ...f, status: "请重新选择原文件以继续", progress: 0, file: null }));
  } catch { state.files = []; }
  renderQueue();
}
async function addFiles(files) {
  if (state.sending) return toast("正在发送，请稍后添加文件", true);
  for (const file of files) {
    if (file.size > FILE_LIMIT) { toast(`“${file.name}”超过单文件 500 MB 上限`, true); continue; }
    const fp = await fingerprint(file);
    const previous = state.files.find(f => f.fingerprint === fp && !f.file);
    if (previous) { previous.file = file; previous.status = "已重新选择，发送时继续上传"; previous.error = false; }
    else {
      if (state.files.length >= 20) { toast("一次最多发送 20 个附件", true); break; }
      state.files.push({ file, name: file.name, size: file.size, fingerprint: fp, progress: 0, status: "待发送", id: null });
    }
  }
  renderQueue();
}
function renderQueue() {
  const fragment = document.createDocumentFragment();
  for (const file of state.files) {
    const card = el("div", "attachment");
    const info = el("div"); info.append(el("span", "attachment-name", file.name, true), el("span", `attachment-status${file.error ? " error" : ""}`, `${bytes(file.size)} · ${file.status}`));
    const remove = el("button", "", "×"); remove.type = "button"; remove.setAttribute("aria-label", `移除 ${file.name}`); remove.disabled = state.sending;
    remove.addEventListener("click", async () => {
      remove.disabled = true;
      try { if (file.id) await json(`/api/uploads/${file.id}`, { method: "DELETE" }); }
      catch (error) { if (![404, 409].includes(error.status)) { toast(error.message, true); remove.disabled = false; return; } }
      state.files = state.files.filter(item => item !== file); rememberDraft(); renderQueue(); refreshStats().catch(() => {});
    });
    card.append(info, remove);
    if (file.progress > 0) { const progress = el("progress"); progress.max = 100; progress.value = file.progress; progress.setAttribute("aria-label", "上传进度"); card.append(progress); }
    fragment.append(card);
  }
  $("attachment-list").replaceChildren(fragment);
}
async function upload(file) {
  if (!file.file) throw new Error(`请重新选择“${file.name}”后再发送`);
  let status;
  if (file.id) {
    try { status = await json(`/api/uploads/${file.id}`); }
    catch (error) { if (error.status !== 404) throw error; file.id = null; }
  }
  if (!file.id) {
    status = await json("/api/uploads", { method: "POST", body: JSON.stringify({ name: file.name, size: file.size, fingerprint: file.fingerprint }) });
    file.id = status.id; rememberDraft();
  }
  if (status.message_id) throw new Error("该附件可能已经发送成功，请先查看传输记录，再移除待发送附件");
  const chunks = Math.ceil(file.size / CHUNK_SIZE);
  for (let index = 0; index < chunks; index++) {
    const block = file.file.slice(index * CHUNK_SIZE, Math.min(file.size, (index + 1) * CHUNK_SIZE));
    const hash = await digest(block);
    if (status.chunks[String(index)] && status.chunks[String(index)] !== hash) throw new Error("选择的文件与已上传内容不一致，请移除后重新添加");
    if (status.state !== "ready" && !status.chunks[String(index)]) {
      for (let attempt = 0; ; attempt++) {
        try {
          await json(`/api/uploads/${file.id}/chunks/${index}`, { method: "PUT", headers: { "Content-Type": "application/octet-stream", "X-Chunk-SHA256": hash }, body: block });
          break;
        } catch (error) {
          if (attempt >= 2 || (error.status && error.status < 500 && error.status !== 422)) throw error;
          file.status = "网络不稳定，正在重试"; renderQueue();
          await new Promise(resolve => setTimeout(resolve, 600 * (attempt + 1)));
        }
      }
    }
    file.progress = Math.round(((index + 1) / Math.max(1, chunks)) * 100);
    file.status = `上传中 ${file.progress}%`; renderQueue();
  }
  file.status = "正在校验并保存"; renderQueue();
  await json(`/api/uploads/${file.id}/complete`, { method: "POST" });
  file.progress = 100; file.status = "上传完成，正在提交记录"; renderQueue();
}
$("choose-files").addEventListener("click", () => $("file-picker").click());
$("file-picker").addEventListener("change", async event => {
  try { await addFiles(Array.from(event.target.files)); } catch (error) { toast(error.message, true); }
  event.target.value = "";
});
$("message-text").addEventListener("paste", async event => {
  const files = Array.from(event.clipboardData?.files || []);
  if (files.length) { event.preventDefault(); try { await addFiles(files); } catch (error) { toast(error.message, true); } }
});
$("message-text").addEventListener("keydown", event => {
  if ((event.ctrlKey || event.metaKey) && event.key === "Enter") { event.preventDefault(); $("send-button").click(); }
});
let dragDepth = 0;
window.addEventListener("dragenter", event => { if (state.authenticated && event.dataTransfer.types.includes("Files")) { event.preventDefault(); dragDepth++; $("drop-overlay").hidden = false; } });
window.addEventListener("dragleave", () => { dragDepth = Math.max(0, dragDepth - 1); if (!dragDepth) $("drop-overlay").hidden = true; });
window.addEventListener("dragover", event => { if (state.authenticated) event.preventDefault(); });
window.addEventListener("drop", async event => {
  if (!state.authenticated) return;
  event.preventDefault(); dragDepth = 0; $("drop-overlay").hidden = true;
  try { await addFiles(Array.from(event.dataTransfer.files)); } catch (error) { toast(error.message, true); }
});
$("send-button").addEventListener("click", async () => {
  if (state.sending) return;
  const text = $("message-text").value;
  if (!text.trim() && !state.files.length) return toast("请输入文字或添加文件", true);
  state.sending = true; $("send-button").disabled = true; $("choose-files").disabled = true; $("message-text").disabled = true;
  label("send-button", "正在发送…");
  try {
    for (const file of state.files) {
      try { file.error = false; await upload(file); }
      catch (error) { file.status = error.message; file.error = true; renderQueue(); throw error; }
    }
    const ids = state.files.map(file => file.id);
    const snapshot = JSON.stringify({ text, ids });
    if (state.snapshot !== snapshot) { state.snapshot = snapshot; state.clientId = crypto.randomUUID().replaceAll("-", ""); }
    await json("/api/messages", { method: "POST", body: JSON.stringify({ client_id: state.clientId, text, device: DEVICE, files: ids }) });
    state.files = []; state.snapshot = ""; state.clientId = ""; $("message-text").value = "";
    rememberDraft(); renderQueue(); toast("已保存，其他设备打开后即可查看");
    state.loadedMore = false; await Promise.all([refreshHistory(), refreshStats()]);
  } catch (error) { toast(error.message, true); }
  finally { state.sending = false; $("send-button").disabled = false; $("choose-files").disabled = false; $("message-text").disabled = false; label("send-button", "发送 ↗"); renderQueue(); }
});
window.addEventListener("beforeunload", event => { if (state.sending) { event.preventDefault(); event.returnValue = ""; } });

function connected(ok) {
  $("connection-dot").className = `status-dot ${ok ? "online" : "offline"}`;
  label("connection-label", ok ? "已连接" : "连接中断");
}
async function refreshStats() {
  const info = await json("/api/stats");
  label("total-count", String(info.count)); label("storage-percent", `${Math.min(100, Math.round(info.used / info.quota * 100))}%`);
  $("storage-progress").value = info.used / info.quota * 100;
  label("storage-label", `${bytes(info.used)} / ${bytes(info.quota)}`);
  $("reserved-label").hidden = !info.reserved; label("reserved-label", `含待发送文件预留 ${bytes(info.reserved)}`);
  label("records-caption", `共 ${info.count} 条`);
  if (info.used / info.quota >= 0.9) label("composer-hint", "存储空间即将用满，请先备份并删除不需要的内容");
  else label("composer-hint", "图片保留原图 · 上传时请保持页面打开");
}
async function refreshHistory(append = false, quiet = false) {
  const query = new URLSearchParams({ q: state.search, kind: state.kind });
  if (append && state.next) query.set("before", state.next);
  const ticket = ++state.request;
  try {
    const data = await json(`/api/messages?${query}`);
    if (ticket !== state.request) return;
    if (append) { state.rows.push(...data.messages); state.loadedMore = true; state.next = data.next; }
    else if (quiet && state.loadedMore && data.messages.length) {
      const oldest = data.messages[data.messages.length - 1].created;
      const seen = new Set(data.messages.map(m => m.id));
      state.rows = [...data.messages, ...state.rows.filter(m => !seen.has(m.id) && m.created <= oldest)];
    } else { state.rows = data.messages; state.next = data.next; }
    const signature = JSON.stringify(state.rows);
    if (signature !== state.signature) { state.signature = signature; renderHistory(); }
    $("load-more").hidden = !state.next; connected(true);
  } catch (error) { connected(false); if (!quiet) toast(error.message, true); }
}
function textWithLinks(text) {
  const node = el("div", "record-text");
  let offset = 0;
  for (const match of text.matchAll(/https?:\/\/[^\s<>"'，。！？；）]+/g)) {
    node.append(document.createTextNode(text.slice(offset, match.index)));
    const link = el("a", "", match[0], true); link.href = match[0]; link.target = "_blank"; link.rel = "noreferrer noopener"; node.append(link);
    offset = match.index + match[0].length;
  }
  node.append(document.createTextNode(text.slice(offset))); return node;
}
function imageCopyButton(file) {
  const button = el("button", "copy-image-button", copyingImages.has(file.id) ? "复制中…" : "复制图片");
  button.type = "button";
  button.dataset.fileId = file.id;
  button.disabled = copyingImages.has(file.id);
  button.setAttribute("aria-busy", String(button.disabled));
  button.setAttribute("aria-label", `复制图片：${file.name}`);
  button.addEventListener("click", async () => {
    if (copyingImages.has(file.id)) return;
    copyingImages.add(file.id);
    button.disabled = true;
    button.setAttribute("aria-busy", "true");
    button.textContent = formatUI("复制中…");
    try {
      await copyImageToClipboard(async signal => {
        if (file.size > MAX_COPY_BYTES) throw new Error("图片超过 50 MiB，请下载原图后使用");
        const response = await api(`/api/files/${file.id}/preview`, { signal });
        return response.blob();
      });
      toast(file.mime === "image/gif" ? "图片首帧已复制，可在支持图片的输入框粘贴" : "图片已复制，可在支持图片的输入框粘贴");
    } catch (error) { toast(error.message, true); }
    finally {
      copyingImages.delete(file.id);
      // Polling may replace the card while the original image is being loaded.
      for (const current of document.querySelectorAll(`.copy-image-button[data-file-id="${file.id}"]`)) {
        current.disabled = false; current.setAttribute("aria-busy", "false"); current.textContent = formatUI("复制图片");
      }
    }
  });
  return button;
}
function renderHistory() {
  const visibleIds = new Set(state.rows.map(record => record.id));
  for (const id of selectedRecords) if (!visibleIds.has(id)) selectedRecords.delete(id);
  const fragment = document.createDocumentFragment();
  if (!state.rows.length) {
    const empty = el("div", "empty"); empty.append(el("div", "empty-symbol", "↔"), el("strong", "", state.search || state.kind !== "all" ? "没有找到符合条件的记录" : "第一段内容，从这里出发"), el("p", "", state.search || state.kind !== "all" ? "换个关键词或筛选条件试试。" : "发送一段文字或一张图片，另一台设备就能看见。")); fragment.append(empty);
  }
  for (const record of state.rows) {
    const card = el("article", "record");
    const top = el("div", "record-top");
    const select = el("input", "record-checkbox"); select.type = "checkbox";
    select.dataset.recordId = record.id; select.checked = selectedRecords.has(record.id); select.disabled = state.deleting;
    const summary = record.text || record.files.map(file => file.name).join("、");
    select.setAttribute("aria-label", `选择记录：${summary.slice(0, 60)}`);
    select.addEventListener("change", () => {
      if (select.checked && selectedRecords.size >= 1000) { select.checked = false; return toast("一次最多选择 1000 条记录", true); }
      if (select.checked) selectedRecords.add(record.id); else selectedRecords.delete(record.id);
      updateSelection();
    });
    top.append(select);
    const time = new Date(record.created * 1000);
    top.append(el("span", "record-device", `来自 ${record.device}`), el("span", "", "已保存"), el("time", "record-time", new Intl.DateTimeFormat("zh-CN", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hour12: false }).format(time)));
    card.append(top);
    if (record.text) card.append(textWithLinks(record.text));
    if (record.files.length) {
      const grid = el("div", "file-grid");
      for (const file of record.files) {
        const item = el("div", "file-card");
        if (file.thumb_size) {
          const preview = el("a", "image-link"); preview.href = endpoint(`/api/files/${file.id}/preview`); preview.target = "_blank"; preview.rel = "noopener"; preview.setAttribute("aria-label", `查看原图：${file.name}`);
          const image = el("img"); image.src = endpoint(`/api/files/${file.id}/thumbnail`); image.alt = file.name; image.loading = "lazy"; preview.append(image); item.append(preview);
        }
        const info = el("div", "file-info");
        const ext = file.name.includes(".") ? file.name.split(".").pop().slice(0, 4).toUpperCase() : "FILE";
        info.append(el("span", "file-type", ext, true));
        const detail = el("div", "file-detail"); const name = el("span", "file-name", file.name, true); name.title = file.name;
        detail.append(name, el("span", "file-size", bytes(file.size))); info.append(detail);
        const download = el("a", "download", "↓"); download.href = endpoint(`/api/files/${file.id}/download`); download.setAttribute("aria-label", `下载 ${file.name}`); info.append(download);
        item.append(info);
        if (file.mime.startsWith("image/")) {
          const actions = el("div", "file-actions");
          actions.append(imageCopyButton(file)); item.append(actions);
        }
        grid.append(item);
      }
      card.append(grid);
    }
    const actions = el("div", "record-actions");
    if (record.text) {
      const copy = el("button", "", "复制文字"); copy.addEventListener("click", async () => { try { await navigator.clipboard.writeText(record.text); toast("已复制"); } catch { toast("复制受浏览器限制，请选中文字手动复制", true); } }); actions.append(copy);
    }
    const remove = el("button", "", "删除记录"); remove.addEventListener("click", () => confirmDelete([record.id])); actions.append(remove); card.append(actions); fragment.append(card);
  }
  $("history-list").replaceChildren(fragment);
  updateSelection();
}
function updateSelection() {
  $("batch-actions").hidden = !state.rows.length;
  const count = selectedRecords.size;
  label("selected-count", `已选 ${count} 条`);
  $("batch-delete-button").disabled = !count || state.deleting;
  const all = $("select-all-records");
  all.checked = !!state.rows.length && state.rows.every(record => selectedRecords.has(record.id));
  all.indeterminate = count > 0 && !all.checked; all.disabled = state.deleting;
  for (const box of document.querySelectorAll(".record-checkbox")) {
    box.checked = selectedRecords.has(box.dataset.recordId); box.disabled = state.deleting;
  }
}
function clearSelection() { selectedRecords.clear(); updateSelection(); }
$("select-all-records").addEventListener("change", event => {
  if (event.target.checked && state.rows.length > 1000) { updateSelection(); return toast("一次最多选择 1000 条记录，请减少已加载记录后重试", true); }
  selectedRecords.clear();
  if (event.target.checked) for (const record of state.rows) selectedRecords.add(record.id);
  updateSelection();
});
$("batch-delete-button").addEventListener("click", () => {
  if (selectedRecords.size) confirmDelete([...selectedRecords], true);
});
let deletingIds = [], deletingBatch = false;
function confirmDelete(ids, batch = false) {
  if (state.deleting) return;
  deletingIds = [...ids]; deletingBatch = batch;
  label("confirm-title", batch ? `删除选中的 ${ids.length} 条记录？` : "删除这条记录？");
  $("confirm-dialog").showModal();
}
$("confirm-cancel").addEventListener("click", () => $("confirm-dialog").close());
$("confirm-dialog").addEventListener("cancel", event => { if (state.deleting) event.preventDefault(); });
$("confirm-ok").addEventListener("click", async () => {
  if (state.deleting || !deletingIds.length) return;
  const ids = [...deletingIds];
  state.deleting = true; $("confirm-ok").disabled = true; $("confirm-cancel").disabled = true;
  label("confirm-ok", "正在删除…"); updateSelection();
  try {
    if (deletingBatch) await json("/api/messages", { method: "DELETE", body: JSON.stringify({ ids }) });
    else await json(`/api/messages/${ids[0]}`, { method: "DELETE" });
    ++state.request;
    for (const id of ids) selectedRecords.delete(id);
    state.rows = state.rows.filter(record => !ids.includes(record.id));
    state.signature = JSON.stringify(state.rows); renderHistory();
    $("confirm-dialog").close(); state.loadedMore = false;
    toast("所选记录及服务器附件已删除");
    await Promise.all([refreshHistory(), refreshStats()]);
  }
  catch (error) { toast(error.message, true); }
  finally { state.deleting = false; $("confirm-ok").disabled = false; $("confirm-cancel").disabled = false; label("confirm-ok", "删除"); updateSelection(); }
});
document.querySelectorAll("[data-kind]").forEach(button => button.addEventListener("click", () => {
  state.kind = button.dataset.kind; state.loadedMore = false; clearSelection();
  document.querySelectorAll("[data-kind]").forEach(b => { b.classList.toggle("active", b === button); b.setAttribute("aria-pressed", String(b === button)); }); refreshHistory();
}));
let searchTimer;
$("search").addEventListener("input", () => { clearSelection(); clearTimeout(searchTimer); searchTimer = setTimeout(() => { state.search = $("search").value; state.loadedMore = false; refreshHistory(); }, 250); });
$("refresh-button").addEventListener("click", () => { state.loadedMore = false; refreshHistory(); refreshStats().catch(error => toast(error.message, true)); });
$("all-records").addEventListener("click", () => { $("search").value = ""; state.search = ""; document.querySelector('[data-kind="all"]').click(); });
$("load-more").addEventListener("click", () => refreshHistory(true));
$("backup-button").addEventListener("click", () => {
  // Let the browser stream the archive to disk instead of buffering a large Blob.
  const form = el("form"); form.method = "POST"; form.action = endpoint("/api/backup"); form.target = "_blank"; form.rel = "noopener";
  const token = el("input"); token.type = "hidden"; token.name = "csrf"; token.value = state.csrf;
  form.append(token); document.body.append(form); form.submit(); form.remove();
  toast("已请求导出完整备份，生成后由浏览器下载；导出期间请稍候再发送");
});

function shareDB() {
  return new Promise((resolve, reject) => { const request = indexedDB.open("snail-transfer-shares", 1); request.onupgradeneeded = () => request.result.createObjectStore("drafts", { keyPath: "id" }); request.onsuccess = () => resolve(request.result); request.onerror = () => reject(request.error); });
}
async function clearSharedDrafts() {
  const db = await shareDB();
  await new Promise((resolve, reject) => { const tx = db.transaction("drafts", "readwrite"); tx.objectStore("drafts").clear(); tx.oncomplete = resolve; tx.onerror = () => reject(tx.error); }); db.close();
}
async function readSharedDraft() {
  const params = new URLSearchParams(location.search);
  if (params.has("share_error")) { toast("分享内容暂存失败，请打开应用后手动选择文件", true); history.replaceState(null, "", endpoint("/")); }
  const id = params.get("share"); if (!id) return;
  try {
    const db = await shareDB();
    const draft = await new Promise((resolve, reject) => { const req = db.transaction("drafts").objectStore("drafts").get(id); req.onsuccess = () => resolve(req.result); req.onerror = () => reject(req.error); });
    if (!draft) { db.close(); throw new Error("分享草稿不存在，请重新分享"); }
    if (Date.now() - draft.created > 48 * 3600000) { db.close(); throw new Error("分享草稿已过期，请重新分享"); }
    if (draft.text) $("message-text").value = [$("message-text").value, draft.text].filter(Boolean).join("\n");
    await addFiles(draft.files || []);
    await new Promise((resolve, reject) => { const tx = db.transaction("drafts", "readwrite"); tx.objectStore("drafts").delete(id); tx.oncomplete = resolve; tx.onerror = () => reject(tx.error); }); db.close();
    toast("已接收分享内容，确认后点击发送");
  } catch (error) { toast(error.message, true); }
  history.replaceState(null, "", endpoint("/"));
}
if ("serviceWorker" in navigator) navigator.serviceWorker.register(endpoint("/sw.js"), { scope: endpoint("/") }).catch(() => { /* Browser upload remains available without installation. */ });
setInterval(() => { if (state.authenticated && !document.hidden && !state.sending && !state.deleting && !$("confirm-dialog").open) { refreshHistory(false, true); refreshStats().catch(() => connected(false)); } }, 5000);
document.addEventListener("visibilitychange", () => { if (!document.hidden && state.authenticated) refreshHistory(false, true); });
boot();
