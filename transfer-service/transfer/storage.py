"""Durable storage. One process owns a data directory; writes share one lock."""

import hashlib
import json
import math
import os
import re
import secrets
import shutil
import sqlite3
import threading
import time
import uuid
from contextlib import contextmanager
from pathlib import Path

from PIL import Image, ImageOps, UnidentifiedImageError
from werkzeug.security import check_password_hash, generate_password_hash

FILE_LIMIT = 500_000_000
CHUNK_SIZE = 8 * 1024 * 1024
TEXT_LIMIT = 100_000
UPLOAD_TTL = 48 * 3600
ID_PATTERN = re.compile(r"^[a-f0-9]{32}$")
HASH_PATTERN = re.compile(r"^[a-f0-9]{64}$")
Image.MAX_IMAGE_PIXELS = 30_000_000


class Problem(Exception):
    def __init__(self, message, status=400):
        self.message, self.status = message, status


def new_id():
    return uuid.uuid4().hex


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


class Store:
    def __init__(self, directory, quota=20_000_000_000):
        self.root = Path(directory).resolve()
        self.root.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.lock = threading.RLock()
        self.quota = quota
        self._owner = open(self.root / "app.lock", "a+b")
        try:
            if os.name == "nt":
                import msvcrt
                self._owner.seek(0)
                self._owner.write(b"0")
                self._owner.flush()
                self._owner.seek(0)
                msvcrt.locking(self._owner.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(self._owner.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            self._owner.close()
            raise RuntimeError("该数据目录已有运行中的实例，请勿重复启动") from None
        for name in ("files", "thumbs", "uploads", "work"):
            (self.root / name).mkdir(exist_ok=True, mode=0o700)
        key = self.root / "session.key"
        if not key.exists():
            with key.open("xb") as target:
                os.chmod(key, 0o600)
                target.write(secrets.token_bytes(32))
                target.flush()
                os.fsync(target.fileno())
        self.secret = key.read_bytes()
        with self.db() as db:
            db.executescript("""
                PRAGMA journal_mode=WAL;
                CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS sessions (
                    id TEXT PRIMARY KEY, expires REAL NOT NULL
                );
                CREATE TABLE IF NOT EXISTS login_attempts (
                    id INTEGER PRIMARY KEY, created REAL NOT NULL
                );
                CREATE TABLE IF NOT EXISTS messages (
                    id TEXT PRIMARY KEY, client_id TEXT UNIQUE NOT NULL,
                    text TEXT NOT NULL, device TEXT NOT NULL, created REAL NOT NULL,
                    deleted INTEGER NOT NULL DEFAULT 0
                );
                CREATE TABLE IF NOT EXISTS uploads (
                    id TEXT PRIMARY KEY, name TEXT NOT NULL, size INTEGER NOT NULL,
                    fingerprint TEXT NOT NULL, state TEXT NOT NULL DEFAULT 'pending',
                    sha256 TEXT, mime TEXT NOT NULL DEFAULT 'application/octet-stream',
                    thumb_size INTEGER NOT NULL DEFAULT 0, created REAL NOT NULL,
                    message_id TEXT REFERENCES messages(id)
                );
                CREATE TABLE IF NOT EXISTS chunks (
                    upload_id TEXT REFERENCES uploads(id) ON DELETE CASCADE,
                    idx INTEGER NOT NULL, hash TEXT NOT NULL,
                    PRIMARY KEY(upload_id, idx)
                );
                CREATE INDEX IF NOT EXISTS messages_created ON messages(created DESC, id DESC);
                CREATE INDEX IF NOT EXISTS uploads_message ON uploads(message_id);
            """)
        self.cleanup()

    def close(self):
        self._owner.close()

    @contextmanager
    def db(self):
        db = sqlite3.connect(self.root / "history.sqlite3", timeout=30)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA foreign_keys=ON")
        try:
            with db:
                yield db
        finally:
            db.close()

    def configure(self, password):
        if not isinstance(password, str) or not 10 <= len(password) <= 256:
            raise Problem("密码需要 10～256 个字符")
        with self.lock, self.db() as db:
            if db.execute("SELECT 1 FROM settings WHERE key='password'").fetchone():
                raise Problem("已经初始化，请直接登录", 409)
            db.execute("INSERT INTO settings VALUES ('password', ?)",
                       (generate_password_hash(password),))

    def change_password(self, sid, current_password, new_password):
        if not isinstance(current_password, str) or not 1 <= len(current_password) <= 256:
            raise Problem("请输入有效的旧密码")
        if not isinstance(new_password, str) or not 10 <= len(new_password) <= 256:
            raise Problem("新密码需要 10～256 个字符")
        with self.lock, self.db() as db:
            now = time.time()
            # A previous change may have revoked this request after its auth check.
            if not db.execute("SELECT 1 FROM sessions WHERE id=? AND expires>?", (sid, now)).fetchone():
                raise Problem("请先登录", 401)
            db.execute("DELETE FROM login_attempts WHERE created<?", (now - 900,))
            if db.execute("SELECT COUNT(*) FROM login_attempts").fetchone()[0] >= 10:
                raise Problem("尝试次数过多，请在 15 分钟后重试", 429)
            row = db.execute("SELECT value FROM settings WHERE key='password'").fetchone()
            valid = row is not None and check_password_hash(row[0], current_password)
            if valid:
                db.execute("UPDATE settings SET value=? WHERE key='password'", (generate_password_hash(new_password),))
                db.execute("DELETE FROM sessions")
                db.execute("DELETE FROM login_attempts")
            else:
                db.execute("INSERT INTO login_attempts(created) VALUES(?)", (now,))
        if not valid:
            raise Problem("旧密码不正确")

    def configured(self):
        with self.db() as db:
            return db.execute("SELECT 1 FROM settings WHERE key='password'").fetchone() is not None

    @staticmethod
    def used(db):
        attachments = db.execute("SELECT COALESCE(SUM(size + thumb_size), 0) FROM uploads").fetchone()[0]
        texts = db.execute("SELECT COALESCE(SUM(length(CAST(text AS BLOB))),0) FROM messages WHERE deleted=0").fetchone()[0]
        return attachments + texts

    def stats(self):
        with self.db() as db:
            reserved = db.execute("SELECT COALESCE(SUM(size),0) FROM uploads WHERE message_id IS NULL").fetchone()[0]
            return {"used": self.used(db), "reserved": reserved, "quota": self.quota,
                    "count": db.execute("SELECT COUNT(*) FROM messages WHERE deleted=0").fetchone()[0],
                    "file_limit": FILE_LIMIT, "chunk_size": CHUNK_SIZE}

    def get_upload(self, upload_id, db=None):
        if not isinstance(upload_id, str) or not ID_PATTERN.fullmatch(upload_id):
            raise Problem("文件不存在", 404)
        if db is None:
            with self.db() as connection:
                return self.get_upload(upload_id, connection)
        row = db.execute("SELECT * FROM uploads WHERE id=?", (upload_id,)).fetchone()
        if row is None:
            raise Problem("上传任务不存在或已过期", 404)
        result = dict(row)
        result["chunks"] = {str(r["idx"]): r["hash"] for r in db.execute(
            "SELECT idx, hash FROM chunks WHERE upload_id=?", (upload_id,))}
        return result

    def begin(self, name, size, fingerprint):
        if not isinstance(name, str) or not name or len(name) > 240 or any(ord(c) < 32 for c in name):
            raise Problem("文件名无效或过长")
        name = name.replace("\\", "/").rsplit("/", 1)[-1]
        if name in ("", ".", ".."):
            raise Problem("文件名无效")
        if type(size) is not int or not 0 <= size <= FILE_LIMIT:
            raise Problem("单个文件不能超过 500 MB", 413)
        if not isinstance(fingerprint, str) or not HASH_PATTERN.fullmatch(fingerprint):
            raise Problem("文件指纹无效")
        with self.lock:
            self.cleanup()
            with self.db() as db:
                if self.used(db) + size > self.quota:
                    raise Problem("存储空间不足，请先删除不需要的内容", 507)
                reserved = db.execute("SELECT COALESCE(SUM(size),0) FROM uploads WHERE state='pending'").fetchone()[0]
                if shutil.disk_usage(self.root).free < reserved + size + 128 * 1024 * 1024:
                    raise Problem("电脑磁盘可用空间不足", 507)
                if db.execute("SELECT COUNT(*) FROM uploads WHERE message_id IS NULL").fetchone()[0] >= 100:
                    raise Problem("待发送文件过多，请先发送或移除已有文件", 429)
                upload_id = new_id()
                (self.root / "uploads" / upload_id).touch(mode=0o600)
                db.execute("INSERT INTO uploads(id,name,size,fingerprint,created) VALUES(?,?,?,?,?)",
                           (upload_id, name, size, fingerprint, time.time()))
                return self.get_upload(upload_id, db)

    def put_chunk(self, upload_id, index, data, digest):
        if not isinstance(digest, str) or not HASH_PATTERN.fullmatch(digest):
            raise Problem("分片校验值无效")
        if hashlib.sha256(data).hexdigest() != digest:
            raise Problem("分片校验失败，请重试", 422)
        with self.lock, self.db() as db:
            upload = self.get_upload(upload_id, db)
            if upload["state"] != "pending":
                raise Problem("上传已完成，请刷新任务状态", 409)
            count = math.ceil(upload["size"] / CHUNK_SIZE)
            if not 0 <= index < count:
                raise Problem("分片序号超出范围")
            expected = min(CHUNK_SIZE, upload["size"] - index * CHUNK_SIZE)
            if len(data) != expected:
                raise Problem("分片大小不正确")
            previous = upload["chunks"].get(str(index))
            if previous:
                if previous != digest:
                    raise Problem("重新选择的文件与已上传内容不一致", 409)
                return
            with (self.root / "uploads" / upload_id).open("r+b") as target:
                target.seek(index * CHUNK_SIZE)
                target.write(data)
                target.flush()
                os.fsync(target.fileno())
            db.execute("INSERT INTO chunks VALUES(?,?,?)", (upload_id, index, digest))

    def finish(self, upload_id):
        with self.lock, self.db() as db:
            upload = self.get_upload(upload_id, db)
            if upload["state"] == "ready":
                return upload
            if len(upload["chunks"]) != math.ceil(upload["size"] / CHUNK_SIZE):
                raise Problem("文件尚未上传完整", 409)
            temporary = self.root / "uploads" / upload_id
            final = self.root / "files" / upload_id
            source = temporary if temporary.exists() else final
            if not source.exists() or source.stat().st_size != upload["size"]:
                raise Problem("文件大小校验失败，请重新上传", 422)
            with source.open("rb") as target:
                for index in range(math.ceil(upload["size"] / CHUNK_SIZE)):
                    if hashlib.sha256(target.read(CHUNK_SIZE)).hexdigest() != upload["chunks"][str(index)]:
                        raise Problem("文件完整性校验失败，请重新上传", 422)
            file_hash = sha256_file(source)
            if source != final:
                os.replace(source, final)
            mime, thumb_size = self.thumbnail(upload_id)
            if self.used(db) + thumb_size > self.quota:
                (self.root / "thumbs" / upload_id).unlink(missing_ok=True)
                thumb_size = 0
            db.execute("UPDATE uploads SET state='ready', sha256=?, mime=?, thumb_size=? WHERE id=?",
                       (file_hash, mime, thumb_size, upload_id))
            return self.get_upload(upload_id, db)

    def thumbnail(self, upload_id):
        try:
            with Image.open(self.root / "files" / upload_id) as image:
                if image.format not in ("JPEG", "PNG", "GIF", "WEBP", "BMP"):
                    return "application/octet-stream", 0
                mime = Image.MIME.get(image.format, "application/octet-stream")
                image.seek(0)
                preview = ImageOps.exif_transpose(image)
                preview.thumbnail((640, 640))
                preview.convert("RGB").save(self.root / "thumbs" / upload_id, "JPEG", quality=80)
                return mime, (self.root / "thumbs" / upload_id).stat().st_size
        except (UnidentifiedImageError, OSError, ValueError, Image.DecompressionBombError):
            return "application/octet-stream", 0

    def cancel(self, upload_id):
        with self.lock, self.db() as db:
            upload = self.get_upload(upload_id, db)
            if upload["message_id"] is not None:
                raise Problem("该文件已经发送，请从历史记录删除", 409)
            self._remove_files(upload_id)
            db.execute("DELETE FROM uploads WHERE id=?", (upload_id,))

    def _remove_files(self, upload_id):
        for directory in ("files", "thumbs", "uploads"):
            (self.root / directory / upload_id).unlink(missing_ok=True)

    def cleanup(self):
        with self.lock, self.db() as db:
            expired = db.execute("SELECT id FROM uploads WHERE message_id IS NULL AND created<?",
                                 (time.time() - UPLOAD_TTL,)).fetchall()
            deleted = db.execute("SELECT u.id FROM uploads u JOIN messages m ON m.id=u.message_id WHERE m.deleted=1").fetchall()
            for row in [*expired, *deleted]:
                self._remove_files(row["id"])
                db.execute("DELETE FROM uploads WHERE id=?", (row["id"],))
            known = {r[0] for r in db.execute("SELECT id FROM uploads")}
            for directory in ("files", "thumbs", "uploads"):
                for item in (self.root / directory).iterdir():
                    if ID_PATTERN.fullmatch(item.name) and item.name not in known:
                        item.unlink()
            db.execute("DELETE FROM sessions WHERE expires<?", (time.time(),))
            db.execute("DELETE FROM login_attempts WHERE created<?", (time.time() - 900,))

    def send(self, client_id, text, device, ids):
        if not isinstance(client_id, str) or not ID_PATTERN.fullmatch(client_id):
            raise Problem("发送标识无效")
        if not isinstance(text, str) or len(text) > TEXT_LIMIT:
            raise Problem("文字不能超过 100000 个字符")
        if not isinstance(device, str) or device not in ("Windows", "Android", "Mac", "其他设备"):
            raise Problem("来源设备无效")
        if not isinstance(ids, list) or len(ids) > 20 or any(not isinstance(i, str) for i in ids) or len(set(ids)) != len(ids):
            raise Problem("一次最多发送 20 个不同附件")
        if not text.strip() and not ids:
            raise Problem("请输入文字或选择文件")
        with self.lock, self.db() as db:
            previous = db.execute("SELECT * FROM messages WHERE client_id=?", (client_id,)).fetchone()
            if previous:
                actual = sorted(r[0] for r in db.execute("SELECT id FROM uploads WHERE message_id=?", (previous["id"],)))
                if previous["deleted"] or previous["text"] != text or actual != sorted(ids):
                    raise Problem("发送标识已被使用，请重新发送", 409)
                return previous["id"]
            for upload_id in ids:
                upload = self.get_upload(upload_id, db)
                if upload["state"] != "ready" or upload["message_id"] is not None:
                    raise Problem("附件未上传完成或已经发送", 409)
            if len(text.encode("utf-8")) > self.quota - self.used(db):
                raise Problem("存储空间不足", 507)
            message_id = new_id()
            db.execute("INSERT INTO messages(id,client_id,text,device,created) VALUES(?,?,?,?,?)",
                       (message_id, client_id, text, device, time.time()))
            for upload_id in ids:
                db.execute("UPDATE uploads SET message_id=? WHERE id=?", (message_id, upload_id))
            return message_id

    def history(self, search="", kind="all", before=None):
        if kind not in ("all", "text", "image", "file"):
            raise Problem("筛选类型无效")
        if len(search) > 200:
            raise Problem("搜索内容过长")
        conditions, params = ["m.deleted=0"], []
        if search:
            conditions.append("(instr(lower(m.text),lower(?))>0 OR EXISTS (SELECT 1 FROM uploads u WHERE u.message_id=m.id AND instr(lower(u.name),lower(?))>0))")
            params.extend([search, search])
        if kind == "text":
            conditions.append("length(m.text)>0")
        elif kind in ("image", "file"):
            comparator = "LIKE" if kind == "image" else "NOT LIKE"
            conditions.append(f"EXISTS (SELECT 1 FROM uploads u WHERE u.message_id=m.id AND u.mime {comparator} 'image/%')")
        if before:
            if not ID_PATTERN.fullmatch(before):
                raise Problem("分页标识无效")
            conditions.append("m.rowid < COALESCE((SELECT rowid FROM messages WHERE id=?),0)")
            params.append(before)
        with self.db() as db:
            rows = db.execute("SELECT m.* FROM messages m WHERE " + " AND ".join(conditions) + " ORDER BY m.rowid DESC LIMIT 31", params).fetchall()
            messages = []
            for row in rows[:30]:
                attachments = [dict(r) for r in db.execute("SELECT id,name,size,mime,sha256,thumb_size FROM uploads WHERE message_id=? ORDER BY rowid", (row["id"],))]
                messages.append({"id": row["id"], "text": row["text"], "device": row["device"],
                                 "created": row["created"], "files": attachments})
            return {"messages": messages, "next": rows[29]["id"] if len(rows) > 30 else None}

    def remove_message(self, message_id):
        if not ID_PATTERN.fullmatch(message_id):
            raise Problem("记录不存在", 404)
        with self.lock:
            with self.db() as db:
                if not db.execute("SELECT 1 FROM messages WHERE id=? AND deleted=0", (message_id,)).fetchone():
                    raise Problem("记录不存在", 404)
                db.execute("UPDATE messages SET deleted=1, text='' WHERE id=?", (message_id,))
            self.cleanup()

    def remove_messages(self, ids):
        if (not isinstance(ids, list) or not 1 <= len(ids) <= 1000
                or any(not isinstance(value, str) or not ID_PATTERN.fullmatch(value) for value in ids)
                or len(set(ids)) != len(ids)):
            raise Problem("请选择 1～1000 条不同的记录")
        placeholders = ",".join("?" for _ in ids)
        with self.lock:
            with self.db() as db:
                deleted = db.execute(f"UPDATE messages SET deleted=1, text='' WHERE deleted=0 AND id IN ({placeholders})", ids).rowcount
            # Missing/already-deleted IDs are harmless on retries or another device.
            self.cleanup()
        return deleted

    def attachment(self, upload_id):
        with self.db() as db:
            upload = self.get_upload(upload_id, db)
            if upload["message_id"] is None or not db.execute("SELECT 1 FROM messages WHERE id=? AND deleted=0", (upload["message_id"],)).fetchone():
                raise Problem("文件尚未发送或已删除", 404)
            return upload
