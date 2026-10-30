// Bound every async stage, including response bodies and providers that never settle.
export function withDeadline(work, { signal, timeoutMs = 30_000, stage = "请求服务器" } = {}) {
  const controller = new AbortController();
  return new Promise((resolve, reject) => {
    let settled = false;
    const finish = (error, value) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      signal?.removeEventListener("abort", cancel);
      if (error) reject(error); else resolve(value);
      controller.abort();
    };
    const cancel = () => {
      const error = new Error("已停止发送，可点击发送继续");
      error.cancelled = true;
      finish(error);
    };
    const timer = setTimeout(() => {
      const error = new Error(`${stage}超时，请重试`);
      error.timeout = true;
      finish(error);
    }, timeoutMs);
    if (signal?.aborted) { cancel(); return; }
    signal?.addEventListener("abort", cancel, { once: true });
    Promise.resolve().then(() => {
      if (settled) return;
      return work(controller.signal);
    }).then(value => finish(null, value), error => finish(error));
  });
}

export async function readHashed(blob, { signal, timeoutMs = 30_000, onStage = () => {} } = {}) {
  onStage("读取文件");
  const buffer = await withDeadline(localSignal => new Promise((resolve, reject) => {
    const reader = new FileReader();
    const abort = () => { reader.abort(); reject(new Error("读取已停止")); };
    const finish = (error, value) => {
      localSignal.removeEventListener("abort", abort);
      reader.onload = reader.onerror = reader.onabort = null;
      if (error) reject(error); else resolve(value);
    };
    reader.onload = () => finish(null, reader.result);
    reader.onerror = () => finish(new Error("读取文件失败，请重新选择本机原文件"));
    reader.onabort = () => finish(new Error("读取文件已停止"));
    localSignal.addEventListener("abort", abort, { once: true });
    try { reader.readAsArrayBuffer(blob); }
    catch { finish(new Error("读取文件失败，请重新选择本机原文件")); }
  }), { signal, timeoutMs, stage: "读取文件" });
  if (!(buffer instanceof ArrayBuffer) || buffer.byteLength !== blob.size) throw new Error("文件读取不完整，请重新选择本机原文件");
  onStage("校验分片");
  const digest = await withDeadline(() => crypto.subtle.digest("SHA-256", buffer), { signal, timeoutMs, stage: "校验分片" });
  const hash = Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, "0")).join("");
  return { buffer, hash };
}

export function uploadBuffer(url, buffer, { signal, csrf, hash, timeoutMs = 60_000, onProgress = () => {} } = {}) {
  return withDeadline(localSignal => new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    const abort = () => xhr.abort();
    const finish = (error, value) => {
      localSignal.removeEventListener("abort", abort);
      xhr.onload = xhr.onerror = xhr.ontimeout = xhr.onabort = null;
      xhr.upload.onprogress = null;
      if (error) reject(error); else resolve(value);
    };
    xhr.open("PUT", url);
    xhr.withCredentials = true;
    xhr.timeout = timeoutMs;
    xhr.setRequestHeader("Content-Type", "application/octet-stream");
    xhr.setRequestHeader("X-CSRF-Token", csrf);
    xhr.setRequestHeader("X-Chunk-SHA256", hash);
    xhr.upload.onprogress = event => { if (!localSignal.aborted) onProgress(event.loaded, buffer.byteLength); };
    xhr.onload = () => {
      let data;
      try { data = JSON.parse(xhr.responseText); }
      catch {
        const error = new Error(`服务器响应异常（${xhr.status}），请重试`);
        error.status = xhr.status;
        error.retryable = xhr.status >= 200 && xhr.status < 300;
        finish(error); return;
      }
      if (xhr.status < 200 || xhr.status >= 300) {
        const error = new Error(data?.error || `请求未完成（${xhr.status}）`);
        error.status = xhr.status;
        finish(error);
      } else if (data?.ok === true) finish(null, data);
      else { const error = new Error("服务器未确认分片，请重试"); error.retryable = true; finish(error); }
    };
    xhr.onerror = () => finish(new Error("上传连接中断，请检查网络后重试"));
    xhr.ontimeout = () => { const error = new Error("上传分片超时，请重试"); error.timeout = true; finish(error); };
    xhr.onabort = () => finish(new Error("上传已停止"));
    localSignal.addEventListener("abort", abort, { once: true });
    try { xhr.send(buffer); }
    catch { finish(new Error("浏览器无法发送文件，请重试")); }
  }), { signal, timeoutMs, stage: "上传分片" });
}
