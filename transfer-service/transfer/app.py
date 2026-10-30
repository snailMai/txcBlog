import hmac
import ipaddress
import os
import re
import secrets
import sqlite3
import time
from datetime import timedelta
from pathlib import Path
from urllib.parse import urlsplit

from flask import Flask, jsonify, render_template, request, send_file, session, url_for
from werkzeug.exceptions import HTTPException, NotFound
from werkzeug.middleware.dispatcher import DispatcherMiddleware
from werkzeug.security import check_password_hash

from .storage import CHUNK_SIZE, Problem, Store


def create_app(directory=None, origin=None, quota=20_000_000_000, testing=False, prefix=""):
    # 固定服务端挂载点；不能接受任意客户端路径，也不信任转发前缀头。
    if not isinstance(prefix, str) or (prefix and not re.fullmatch(r"(?:/[A-Za-z0-9_-]+)+", prefix)):
        raise ValueError("挂载路径须为 /transfer 形式，不含尾斜杠、查询或相对路径")
    app = Flask(__name__)
    origin = (origin or os.environ.get("TRANSFER_ORIGIN", "http://127.0.0.1:8765")).rstrip("/")
    parsed = urlsplit(origin)
    if parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.path or parsed.query or parsed.fragment or parsed.username:
        raise ValueError("访问地址必须是完整的 HTTP／HTTPS 源地址，不包含路径或凭据")
    local = parsed.hostname in ("127.0.0.1", "localhost", "::1")
    if not local and parsed.scheme != "https":
        raise ValueError("外部访问必须使用 HTTPS")
    store = Store(directory or os.environ.get("TRANSFER_DATA", "data"), quota)
    app.extensions["store"] = store
    app.config.update(
        TESTING=testing, SECRET_KEY=store.secret, PUBLIC_ORIGIN=origin,
        MAX_CONTENT_LENGTH=CHUNK_SIZE + 1024 * 1024,
        MAX_FORM_MEMORY_SIZE=1024 * 1024,
        SESSION_COOKIE_NAME="snail_session", SESSION_COOKIE_HTTPONLY=True,
        SESSION_COOKIE_SECURE=parsed.scheme == "https", SESSION_COOKIE_SAMESITE="Strict",
        SESSION_COOKIE_PATH=(prefix + "/") if prefix else "/",
        PERMANENT_SESSION_LIFETIME=timedelta(days=30),
        SESSION_REFRESH_EACH_REQUEST=False,
    )

    def authorized():
        sid = session.get("sid")
        if not sid:
            return False
        with store.db() as db:
            return db.execute("SELECT 1 FROM sessions WHERE id=? AND expires>?", (sid, time.time())).fetchone() is not None

    @app.before_request
    def protect():
        if request.host.lower() != parsed.netloc.lower():
            raise Problem("访问地址不匹配，请使用配置的入口地址", 400)
        if request.path == "/share-target":
            return None  # Only the service worker receives share data; no server mutation.
        if request.method not in ("GET", "HEAD", "OPTIONS"):
            if request.headers.get("Origin") != origin:
                raise Problem("请求来源校验失败，请重新打开页面", 403)
            expected = session.get("csrf", "")
            token = request.headers.get("X-CSRF-Token", "")
            if request.path == "/api/backup" and request.mimetype == "application/x-www-form-urlencoded":
                token = request.form.get("csrf", "")
            if not expected or not hmac.compare_digest(expected, token):
                raise Problem("页面凭证已过期，请刷新后重试", 403)
        public = request.path in ("/", "/api/session", "/api/login", "/api/setup", "/health", "/manifest.webmanifest", "/sw.js") or request.path.startswith("/static/")
        if not public and not authorized():
            raise Problem("请先登录", 401)

    @app.after_request
    def headers(response):
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["Referrer-Policy"] = "no-referrer"
        response.headers["X-Frame-Options"] = "SAMEORIGIN" if prefix else "DENY"
        ancestors = "'self'" if prefix else "'none'"
        response.headers["Content-Security-Policy"] = f"default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' blob:; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors {ancestors}; form-action 'self'"
        response.headers["Permissions-Policy"] = "camera=(), microphone=(), geolocation=()"
        response.headers["Cache-Control"] = "no-store"
        if parsed.scheme == "https":
            response.headers["Strict-Transport-Security"] = "max-age=31536000"
        return response

    @app.errorhandler(Problem)
    def problem(error):
        return jsonify(error=error.message), error.status

    @app.errorhandler(HTTPException)
    def http_error(error):
        labels = {400: "请求内容无效", 404: "内容不存在", 405: "请求方式不支持", 413: "本次请求内容过大", 416: "下载范围无效"}
        return jsonify(error=labels.get(error.code, "请求未完成")), error.code

    @app.errorhandler(OSError)
    @app.errorhandler(sqlite3.Error)
    def storage_error(error):
        app.logger.error("Storage error: %s", type(error).__name__)
        return jsonify(error="磁盘或数据库暂时不可用，请检查电脑存储后重试"), 503

    def body():
        value = request.get_json(silent=True)
        if not isinstance(value, dict):
            raise Problem("请求需要 JSON 对象")
        return value

    def csrf():
        if "csrf" not in session:
            session["csrf"] = secrets.token_hex(32)
        return session["csrf"]

    @app.get("/")
    def index():
        return render_template("index.html", embedded=bool(prefix) and request.args.get("embedded") == "1")

    @app.get("/health")
    def health():
        return jsonify(status="ok")

    @app.get("/api/session")
    def session_info():
        return jsonify(authenticated=authorized(), configured=store.configured(), csrf=csrf(), local_setup=local)

    @app.post("/api/setup")
    def setup():
        try:
            loopback = ipaddress.ip_address(request.remote_addr).is_loopback
        except ValueError:
            loopback = False
        if not local or not loopback:
            raise Problem("首次设置只能在本机完成，请使用初始化命令", 403)
        store.configure(body().get("password"))
        return jsonify(ok=True)

    @app.post("/api/login")
    def login():
        password = body().get("password")
        if not isinstance(password, str) or len(password) > 256:
            raise Problem("密码格式无效")
        with store.lock, store.db() as db:
            db.execute("DELETE FROM login_attempts WHERE created<?", (time.time() - 900,))
            if db.execute("SELECT COUNT(*) FROM login_attempts").fetchone()[0] >= 10:
                raise Problem("尝试次数过多，请在 15 分钟后重试", 429)
            row = db.execute("SELECT value FROM settings WHERE key='password'").fetchone()
            valid = row is not None and check_password_hash(row[0], password)
            if not valid:
                db.execute("INSERT INTO login_attempts(created) VALUES(?)", (time.time(),))
            else:
                db.execute("DELETE FROM login_attempts")
                sid = secrets.token_hex(32)
                db.execute("INSERT INTO sessions VALUES(?,?)", (sid, time.time() + 30 * 86400))
        if not valid:
            raise Problem("密码不正确", 401)
        session.clear()
        session.permanent = True
        session["sid"] = sid
        return jsonify(ok=True, csrf=csrf())

    @app.post("/api/logout")
    def logout():
        with store.db() as db:
            db.execute("DELETE FROM sessions WHERE id=?", (session.get("sid", ""),))
        session.clear()
        return jsonify(ok=True)

    @app.post("/api/password")
    def change_password():
        data = body()
        new_password = data.get("new_password")
        if not isinstance(new_password, str) or new_password != data.get("confirm_password"):
            raise Problem("两次新密码不一致")
        store.change_password(session.get("sid", ""), data.get("current_password"), new_password)
        session.clear()
        return jsonify(ok=True)

    @app.get("/api/stats")
    def stats():
        return jsonify(store.stats())

    @app.get("/api/messages")
    def history():
        size = request.args.get("page_size")
        if size is not None and size not in ("5", "10", "20"):
            raise Problem("每页条数只能为 5、10 或 20")
        return jsonify(store.history(request.args.get("q", ""), request.args.get("kind", "all"), request.args.get("before"),
                                     int(size) if size is not None else None))

    @app.post("/api/messages")
    def send():
        data = body()
        message_id = store.send(data.get("client_id"), data.get("text", ""), data.get("device", "其他设备"), data.get("files", []))
        return jsonify(id=message_id)

    @app.delete("/api/messages/<message_id>")
    def delete_message(message_id):
        store.remove_message(message_id)
        return jsonify(ok=True)

    @app.delete("/api/messages")
    def delete_messages():
        return jsonify(ok=True, deleted=store.remove_messages(body().get("ids")))

    @app.post("/api/uploads")
    def begin_upload():
        data = body()
        return jsonify(store.begin(data.get("name"), data.get("size"), data.get("fingerprint"))), 201

    @app.get("/api/uploads/<upload_id>")
    def upload_status(upload_id):
        return jsonify(store.get_upload(upload_id))

    @app.put("/api/uploads/<upload_id>/chunks/<int:index>")
    def chunk(upload_id, index):
        store.put_chunk(upload_id, index, request.get_data(), request.headers.get("X-Chunk-SHA256", ""))
        return jsonify(ok=True)

    @app.post("/api/uploads/<upload_id>/complete")
    def complete(upload_id):
        return jsonify(store.finish(upload_id))

    @app.delete("/api/uploads/<upload_id>")
    def cancel_upload(upload_id):
        store.cancel(upload_id)
        return jsonify(ok=True)

    @app.get("/api/files/<upload_id>/<action>")
    def download(upload_id, action):
        if action not in ("download", "preview", "thumbnail"):
            raise Problem("文件操作无效", 404)
        with store.lock:
            upload = store.attachment(upload_id)
            if action == "thumbnail":
                if not upload["thumb_size"]:
                    raise Problem("没有可用缩略图", 404)
                target, mime = store.root / "thumbs" / upload_id, "image/jpeg"
            else:
                target, mime = store.root / "files" / upload_id, upload["mime"]
            if action == "preview" and not mime.startswith("image/"):
                raise Problem("该文件只支持下载", 400)
            response = send_file(target, mimetype=mime, as_attachment=action == "download",
                                 download_name=upload["name"], conditional=True, etag=upload["sha256"])
            response.headers["Accept-Ranges"] = "bytes"
            return response

    @app.post("/api/backup")
    def backup():
        from .backup import create_backup
        archive = create_backup(store)
        response = send_file(archive, mimetype="application/zip", as_attachment=True,
                             download_name=time.strftime("snail-transfer-%Y%m%d-%H%M%S.zip"))
        response.call_on_close(archive.close)
        return response

    @app.get("/manifest.webmanifest")
    def manifest():
        response = jsonify({
            "id": url_for("index"), "name": "蜗牛传送", "short_name": "蜗牛传送", "lang": "zh-CN",
            "start_url": url_for("index"), "scope": url_for("index"), "display": "standalone",
            "background_color": "#f5f6f8", "theme_color": "#24745b",
            "icons": [{"src": url_for("static", filename="icon-192.png"), "sizes": "192x192", "type": "image/png", "purpose": "any maskable"},
                      {"src": url_for("static", filename="icon-512.png"), "sizes": "512x512", "type": "image/png", "purpose": "any maskable"}],
            "share_target": {"action": url_for("share_fallback"), "method": "POST", "enctype": "multipart/form-data",
                             "params": {"title": "title", "text": "text", "url": "url",
                                        "files": [{"name": "files", "accept": ["*/*"]}]}}
        })
        response.mimetype = "application/manifest+json"
        return response

    @app.get("/sw.js")
    def worker():
        response = app.send_static_file("sw.js")
        response.headers["Service-Worker-Allowed"] = prefix + "/"
        return response

    @app.route("/share-target", methods=["GET", "POST"])
    def share_fallback():
        return "请先打开并安装蜗牛传送，再从其他应用分享。", 409

    if prefix:
        app.wsgi_app = DispatcherMiddleware(NotFound(), {prefix: app.wsgi_app})
    return app
