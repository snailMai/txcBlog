// A clipboard item is created during the click gesture; image loading stays async.
export const MAX_COPY_BYTES = 50 * 1024 * 1024;
const MAX_COPY_PIXELS = 30_000_000;

async function asPng(blob) {
  if (!(blob instanceof Blob) || !blob.size) throw new Error("图片内容为空，无法复制");
  if (blob.size > MAX_COPY_BYTES) throw new Error("图片超过 50 MiB，请下载原图后使用");
  let bitmap;
  try { bitmap = await createImageBitmap(blob); }
  catch { throw new Error("图片无法解码，请下载原图后检查"); }
  let canvas;
  try {
    if (!bitmap.width || !bitmap.height || bitmap.width * bitmap.height > MAX_COPY_PIXELS) {
      throw new Error("图片超过 3000 万像素或尺寸无效，请下载原图后使用");
    }
    if (blob.type === "image/png") return blob;
    canvas = document.createElement("canvas");
    canvas.width = bitmap.width;
    canvas.height = bitmap.height;
    const context = canvas.getContext("2d");
    if (!context) throw new Error("浏览器无法处理这张图片，请下载原图后使用");
    context.drawImage(bitmap, 0, 0);
    const png = await new Promise(resolve => canvas.toBlob(resolve, "image/png"));
    if (!png) throw new Error("图片转换失败，请下载原图后使用");
    return png;
  } finally {
    bitmap.close();
    if (canvas) { canvas.width = 0; canvas.height = 0; }
  }
}

export async function copyImageToClipboard(loadBlob) {
  if (!globalThis.isSecureContext || !navigator.clipboard?.write || typeof ClipboardItem === "undefined" ||
      (typeof ClipboardItem.supports === "function" && !ClipboardItem.supports("image/png"))) {
    throw new Error("当前浏览器不支持复制图片，请使用 HTTPS 访问或下载原图");
  }
  const controller = new AbortController();
  let expired = false;
  let timeout;
  const timeoutError = new Error("图片复制超时，请重试或下载原图");
  const deadline = new Promise((resolve, reject) => {
    timeout = setTimeout(() => {
      expired = true;
      controller.abort();
      reject(timeoutError);
    }, 30_000);
  });
  let loadingError;
  const image = Promise.race([Promise.resolve().then(() => loadBlob(controller.signal)).then(asPng), deadline]).catch(error => {
    loadingError = expired ? timeoutError : error;
    throw loadingError;
  });
  // Some browsers deny write before consuming the item's promise.
  image.catch(() => {});
  try {
    await Promise.race([navigator.clipboard.write([new ClipboardItem({ "image/png": image })]), deadline]);
  } catch (error) {
    if (expired) throw timeoutError;
    if (loadingError) throw loadingError;
    if (error.name === "NotAllowedError") {
      throw new Error("浏览器未允许复制图片，请保持页面在前台并检查剪贴板权限，或下载原图");
    }
    throw new Error("图片复制失败，请重试或下载原图");
  } finally {
    clearTimeout(timeout);
    controller.abort();
  }
}
