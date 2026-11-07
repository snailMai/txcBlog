import { withDeadline } from "./transfer-io.mjs";

// 仅保留当前原图；关闭、切换或鉴权失效后取消加载，迟到响应不得重新显示。
export function createImagePreview({ load, timeoutMs = 60_000 }) {
  const node = id => document.getElementById(id);
  const dialog = node("image-preview"), viewport = node("image-preview-viewport"), image = node("image-preview-image");
  const status = node("image-preview-status"), retry = node("image-preview-retry"), ratio = node("image-preview-ratio");
  const controls = ["image-preview-out", "image-preview-in", "image-preview-fit", "image-preview-actual"].map(node);
  const points = new Map();
  let ticket = 0, controller = null, objectURL = "", file = null, opener = null, overflow = "";
  let width = 0, height = 0, scale = 1, x = 0, y = 0, fitted = true;

  function fitScale() { return Math.min(1, (viewport.clientWidth - 24) / width, (viewport.clientHeight - 24) / height); }
  function paint() {
    const limitX = Math.max(0, (width * scale - viewport.clientWidth) / 2);
    const limitY = Math.max(0, (height * scale - viewport.clientHeight) / 2);
    x = Math.max(-limitX, Math.min(limitX, x)); y = Math.max(-limitY, Math.min(limitY, y));
    image.style.transform = `translate(-50%, -50%) translate(${x}px, ${y}px) scale(${scale})`;
    ratio.textContent = `${Math.round(scale * 100)}%`;
    viewport.classList.toggle("is-zoomed", limitX > 0 || limitY > 0);
  }
  function zoom(value, anchor = { x: 0, y: 0 }) {
    if (!width) return;
    const next = Math.max(fitScale() / 4, Math.min(4, value));
    x = anchor.x - (anchor.x - x) * next / scale;
    y = anchor.y - (anchor.y - y) * next / scale;
    scale = next; fitted = false; paint();
  }
  function fit() { if (width) { scale = fitScale(); x = y = 0; fitted = true; paint(); } }
  function reset() {
    controller?.abort(); controller = null; points.clear();
    image.onload = image.onerror = null; image.removeAttribute("src"); image.hidden = true;
    if (objectURL) URL.revokeObjectURL(objectURL);
    objectURL = ""; width = height = 0; x = y = 0;
    viewport.classList.remove("is-zoomed");
    for (const button of controls) button.disabled = true;
    ratio.textContent = "";
  }
  function close() {
    ticket++; reset();
    if (!dialog.open) return;
    dialog.close(); document.documentElement.style.overflow = overflow;
    const target = opener?.isConnected ? opener : document.querySelector(".image-link");
    target?.focus({ preventScroll: true });
    opener = file = null;
  }
  async function open(nextFile, trigger) {
    const current = ++ticket;
    reset(); file = nextFile; opener = trigger;
    node("image-preview-title").textContent = file.name;
    image.alt = file.name; status.textContent = "正在加载原图…"; retry.hidden = true;
    if (!dialog.open) {
      overflow = document.documentElement.style.overflow;
      document.documentElement.style.overflow = "hidden";
      dialog.showModal(); node("image-preview-close").focus();
    }
    const active = () => current === ticket && dialog.open;
    controller = new AbortController();
    try {
      await withDeadline(async signal => {
        const blob = await load(nextFile, signal);
        if (!active() || signal.aborted) return;
        if (!blob.size || !blob.type.startsWith("image/")) throw new Error("图片内容无效，请下载原文件查看");
        objectURL = URL.createObjectURL(blob);
        await new Promise((resolve, reject) => {
          const finish = error => {
            image.onload = image.onerror = null;
            signal.removeEventListener("abort", abort);
            if (error) reject(error); else resolve();
          };
          const abort = () => finish(new Error("图片加载已取消"));
          image.onload = () => finish();
          image.onerror = () => finish(new Error("图片无法显示，请重试或下载原文件查看"));
          signal.addEventListener("abort", abort, { once: true });
          image.src = objectURL;
        });
      }, { signal: controller.signal, timeoutMs, stage: "加载原图" });
      if (!active()) return;
      width = image.naturalWidth; height = image.naturalHeight;
      if (!width || !height) throw new Error("图片无法显示，请下载原文件查看");
      image.style.width = `${width}px`; image.style.height = `${height}px`;
      image.hidden = false; status.textContent = "";
      for (const button of controls) button.disabled = false;
      fit();
    } catch (error) {
      if (!active()) return;
      reset(); status.textContent = error.message; retry.hidden = false;
    }
  }
  node("image-preview-close").addEventListener("click", close);
  dialog.addEventListener("cancel", event => { event.preventDefault(); close(); });
  // 只把真正的遮罩点击视为关闭，拖动图片松手不会意外关窗。
  let backdrop = false;
  dialog.addEventListener("pointerdown", event => { backdrop = event.target === dialog; });
  dialog.addEventListener("click", event => { if (backdrop && event.target === dialog) close(); backdrop = false; });
  controls[0].addEventListener("click", () => zoom(scale / 1.25));
  controls[1].addEventListener("click", () => zoom(scale * 1.25));
  controls[2].addEventListener("click", fit);
  controls[3].addEventListener("click", () => { zoom(1); x = y = 0; paint(); });
  retry.addEventListener("click", () => open(file, opener));
  function position(event) {
    const rect = viewport.getBoundingClientRect();
    return { x: event.clientX - rect.left - rect.width / 2, y: event.clientY - rect.top - rect.height / 2 };
  }
  viewport.addEventListener("wheel", event => {
    if (!width) return;
    event.preventDefault(); zoom(scale * Math.exp(-Math.max(-100, Math.min(100, event.deltaY)) * .003), position(event));
  }, { passive: false });
  viewport.addEventListener("pointerdown", event => {
    if (!width || (event.pointerType === "mouse" && event.button !== 0)) return;
    event.preventDefault(); points.set(event.pointerId, position(event)); viewport.setPointerCapture(event.pointerId);
  });
  viewport.addEventListener("pointermove", event => {
    if (!points.has(event.pointerId)) return;
    event.preventDefault();
    const before = [...points.values()]; const previous = points.get(event.pointerId);
    const point = position(event); points.set(event.pointerId, point);
    if (points.size === 1) { x += point.x - previous.x; y += point.y - previous.y; paint(); }
    else if (points.size === 2) {
      const after = [...points.values()];
      const middle = pair => ({ x: (pair[0].x + pair[1].x) / 2, y: (pair[0].y + pair[1].y) / 2 });
      const distance = pair => Math.hypot(pair[0].x - pair[1].x, pair[0].y - pair[1].y);
      const start = middle(before), end = middle(after), initial = distance(before);
      if (initial > 0) zoom(scale * distance(after) / initial, start);
      x += end.x - start.x; y += end.y - start.y; paint();
    }
  });
  for (const event of ["pointerup", "pointercancel", "lostpointercapture"]) viewport.addEventListener(event, event => points.delete(event.pointerId));
  window.addEventListener("resize", () => { if (dialog.open && width) { if (fitted) fit(); else paint(); } });
  window.addEventListener("pagehide", close);
  return { open, close };
}
