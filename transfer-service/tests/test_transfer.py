import hashlib
import io
import json
import os
import tempfile
import unittest
import zipfile
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from PIL import Image

from transfer.app import create_app
from transfer.backup import create_backup, restore_backup
from transfer.storage import CHUNK_SIZE, FILE_LIMIT, Problem, Store, new_id, sha256_file

ORIGIN = "http://127.0.0.1:8765"
PASSWORD = "test-only-password-2026"
TMP = Path(__file__).resolve().parents[1] / ".tmp" / "tests"
TMP.mkdir(parents=True, exist_ok=True)


class TransferTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(dir=TMP)
        self.root = Path(self.temporary.name) / "data"
        self.app = create_app(self.root, testing=True)
        self.store = self.app.extensions["store"]
        self.client = self.app.test_client()
        self.csrf = self.get("/api/session").json["csrf"]
        self.assertEqual(self.post("/api/setup", {"password": PASSWORD}).status_code, 200)
        self.csrf = self.post("/api/login", {"password": PASSWORD}).json["csrf"]

    def tearDown(self):
        self.store.close()
        self.temporary.cleanup()

    def get(self, path, **kwargs):
        return self.client.get(path, base_url=ORIGIN, **kwargs)

    def request(self, method, path, data=None, **kwargs):
        headers = {"Origin": ORIGIN, "X-CSRF-Token": self.csrf}
        headers.update(kwargs.pop("headers", {}))
        return self.client.open(path, method=method, base_url=ORIGIN, json=data, headers=headers, **kwargs)

    def post(self, path, data=None, **kwargs):
        return self.request("POST", path, data, **kwargs)

    def upload(self, data=b"hello", name="example.txt"):
        fingerprint = hashlib.sha256(data).hexdigest()
        response = self.post("/api/uploads", {"name": name, "size": len(data), "fingerprint": fingerprint})
        self.assertEqual(response.status_code, 201, response.json)
        upload_id = response.json["id"]
        for index in range((len(data) + CHUNK_SIZE - 1) // CHUNK_SIZE):
            chunk = data[index * CHUNK_SIZE:(index + 1) * CHUNK_SIZE]
            result = self.client.put(f"/api/uploads/{upload_id}/chunks/{index}", base_url=ORIGIN, data=chunk,
                                     headers={"Origin": ORIGIN, "X-CSRF-Token": self.csrf,
                                              "X-Chunk-SHA256": hashlib.sha256(chunk).hexdigest()})
            self.assertEqual(result.status_code, 200, result.json)
        result = self.post(f"/api/uploads/{upload_id}/complete")
        self.assertEqual(result.status_code, 200, result.json)
        self.assertEqual(result.json["sha256"], fingerprint)
        return upload_id

    def send(self, text="测试文字", files=None, client_id=None):
        response = self.post("/api/messages", {"text": text, "files": files or [], "device": "Windows", "client_id": client_id or new_id()})
        self.assertEqual(response.status_code, 200, response.json)
        return response.json["id"]

    def test_text_original_search_filter_and_idempotence(self):
        text = "原文GPT5，标点保留--<script>alert(1)</script>\nhttps://example.com?a=1"
        key = new_id()
        first = self.send(text, client_id=key)
        self.assertEqual(self.send(text, client_id=key), first)
        history = self.get("/api/messages?q=GPT5&kind=text").json
        self.assertEqual(history["messages"][0]["text"], text)
        self.assertEqual(self.get("/api/stats").json["count"], 1)
        conflict = self.post("/api/messages", {"text": "不同内容", "files": [], "device": "Windows", "client_id": key})
        self.assertEqual(conflict.status_code, 409)
        self.assertEqual(self.get("/api/messages?kind=image").json["messages"], [])

    def test_binary_roundtrip_range_same_name_and_zero_byte(self):
        data = bytes(range(256)) * 256
        first = self.upload(data, "同名文件.bin")
        second = self.upload(b"different", "同名文件.bin")
        empty = self.upload(b"", "empty.txt")
        self.assertNotEqual(first, second)
        self.send(files=[first, second, empty])
        response = self.get(f"/api/files/{first}/download")
        self.assertEqual(response.data, data)
        self.assertIn("attachment;", response.headers["Content-Disposition"])
        response.close()
        response = self.get(f"/api/files/{first}/download", headers={"Range": "bytes=256-511"})
        self.assertEqual(response.status_code, 206)
        self.assertEqual(response.data, bytes(range(256)))
        response.close()
        self.assertEqual(self.get(f"/api/files/{first}/preview").status_code, 400)

    def test_image_original_thumbnail_and_search_filename(self):
        output = io.BytesIO()
        Image.new("RGB", (900, 700), "#24745b").save(output, "PNG")
        data = output.getvalue()
        upload_id = self.upload(data, "截图.png")
        self.send(text="", files=[upload_id])
        result = self.get("/api/messages?q=截图&kind=image").json["messages"]
        self.assertEqual(len(result), 1)
        self.assertGreater(result[0]["files"][0]["thumb_size"], 0)
        response = self.get(f"/api/files/{upload_id}/preview")
        self.assertEqual(response.data, data)
        self.assertEqual(response.mimetype, "image/png")
        response.close()
        thumbnail = self.get(f"/api/files/{upload_id}/thumbnail")
        with Image.open(io.BytesIO(thumbnail.data)) as image:
            self.assertLessEqual(max(image.size), 640)
        thumbnail.close()

    def test_auth_csrf_origin_and_host_protection(self):
        outsider = self.app.test_client()
        for route in ("/api/messages", "/api/stats", "/api/files/" + new_id() + "/download"):
            self.assertEqual(outsider.get(route, base_url=ORIGIN).status_code, 401)
        self.assertEqual(self.client.post("/api/messages", base_url=ORIGIN, json={}).status_code, 403)
        self.assertEqual(self.post("/api/messages", {}, headers={"Origin": "https://attacker.example"}).status_code, 403)
        self.assertEqual(self.client.get("/api/session", base_url="http://attacker.example").status_code, 400)
        response = self.get("/api/session")
        self.assertEqual(response.headers["Cache-Control"], "no-store")
        self.assertIn("object-src 'none'", response.headers["Content-Security-Policy"])
        self.assertEqual(self.post("/api/logout").status_code, 200)
        self.assertEqual(self.get("/api/messages").status_code, 401)

    def test_initialization_and_public_origin(self):
        self.assertEqual(self.post("/api/setup", {"password": PASSWORD}).status_code, 409)
        with self.assertRaises(ValueError):
            create_app(self.root / "invalid", "http://public.example")
        other = create_app(self.root / "external", "https://transfer.example.com", testing=True)
        try:
            client = other.test_client()
            csrf = client.get("/api/session", base_url="https://transfer.example.com").json["csrf"]
            response = client.post("/api/setup", base_url="https://transfer.example.com", json={"password": PASSWORD}, headers={"Origin": "https://transfer.example.com", "X-CSRF-Token": csrf})
            self.assertEqual(response.status_code, 403)
            self.assertIn("Secure", client.get("/api/session", base_url="https://transfer.example.com").headers.get("Set-Cookie", "") or other.config["SESSION_COOKIE_SECURE"] and "Secure")
        finally:
            other.extensions["store"].close()

    def test_chunk_integrity_retry_and_resume(self):
        data = b"a" * CHUNK_SIZE + b"last"
        upload = self.store.begin("resume.bin", len(data), hashlib.sha256(data).hexdigest())
        upload_id = upload["id"]
        first_hash = hashlib.sha256(data[:CHUNK_SIZE]).hexdigest()
        with self.assertRaises(Problem):
            self.store.put_chunk(upload_id, 0, data[:CHUNK_SIZE], "0" * 64)
        self.store.put_chunk(upload_id, 0, data[:CHUNK_SIZE], first_hash)
        self.store.put_chunk(upload_id, 0, data[:CHUNK_SIZE], first_hash)
        self.assertEqual(self.get(f"/api/uploads/{upload_id}").json["chunks"], {"0": first_hash})
        self.assertEqual(self.post(f"/api/uploads/{upload_id}/complete").status_code, 409)
        with self.assertRaises(Problem):
            self.store.put_chunk(upload_id, 0, b"b" * CHUNK_SIZE, hashlib.sha256(b"b" * CHUNK_SIZE).hexdigest())
        self.store.put_chunk(upload_id, 1, b"last", hashlib.sha256(b"last").hexdigest())
        self.assertEqual(self.store.finish(upload_id)["sha256"], hashlib.sha256(data).hexdigest())
        self.assertEqual(self.store.finish(upload_id)["state"], "ready")

    def test_limits_invalid_inputs_and_pending_not_visible(self):
        for size in (-1, FILE_LIMIT + 1, True, "1"):
            self.assertEqual(self.post("/api/uploads", {"name": "test", "size": size, "fingerprint": "a" * 64}).status_code, 413)
        upload = self.store.begin("../../safe.txt", FILE_LIMIT, "a" * 64)
        self.assertEqual(upload["name"], "safe.txt")
        self.assertEqual(self.get(f"/api/files/{upload['id']}/download").status_code, 404)
        self.store.cancel(upload["id"])
        for text in ("", " " * 2, "a" * 100001, None):
            self.assertEqual(self.post("/api/messages", {"text": text, "files": [], "client_id": new_id()}).status_code, 400)
        self.assertEqual(self.get("/api/messages?kind=wrong").status_code, 400)

    def test_quota_reservation_text_and_concurrent_sends(self):
        self.store.quota = 10
        upload = self.store.begin("fits", 7, "a" * 64)
        self.assertEqual(self.get("/api/stats").json["used"], 7)
        with self.assertRaises(Problem):
            self.store.begin("does-not-fit", 4, "a" * 64)
        self.store.cancel(upload["id"])
        self.send("中文")
        self.assertEqual(self.get("/api/stats").json["used"], 6)
        with self.assertRaises(Problem):
            self.store.send(new_id(), "五字节以上", "Windows", [])
        self.store.quota = 1000
        key = new_id()
        with ThreadPoolExecutor(max_workers=6) as pool:
            ids = list(pool.map(lambda _: self.store.send(key, "并发", "Windows", []), range(12)))
        self.assertEqual(len(set(ids)), 1)

    def test_deleted_files_unavailable_and_space_released(self):
        upload_id = self.upload(b"delete me")
        message_id = self.send(files=[upload_id])
        response = self.request("DELETE", f"/api/messages/{message_id}")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(self.get("/api/messages").json["messages"], [])
        self.assertEqual(self.get(f"/api/files/{upload_id}/download").status_code, 404)
        self.assertEqual(self.get("/api/stats").json["used"], 0)
        self.assertFalse((self.root / "files" / upload_id).exists())

    def test_restart_persistence_single_instance_and_backup_restore(self):
        upload_id = self.upload(b"persistent")
        self.send("长期记录", [upload_id])
        with self.assertRaises(RuntimeError):
            Store(self.root)
        with create_backup(self.store) as archive:
            output = Path(self.temporary.name) / "backup.zip"
            output.write_bytes(archive.read())
        target = Path(self.temporary.name) / "restored"
        restore_backup(output, target)
        restored = Store(target)
        try:
            self.assertTrue(restored.configured())
            self.assertEqual(restored.history()["messages"][0]["text"], "长期记录")
            self.assertEqual((target / "files" / upload_id).read_bytes(), b"persistent")
            with restored.db() as db:
                self.assertEqual(db.execute("SELECT COUNT(*) FROM sessions").fetchone()[0], 0)
        finally:
            restored.close()
        with self.assertRaises(Problem):
            restore_backup(output, self.root)
        self.store.close()
        self.store = Store(self.root)
        self.assertEqual(self.store.history()["messages"][0]["text"], "长期记录")

    def test_backup_endpoint_and_tampered_archive(self):
        upload_id = self.upload(b"backup check")
        self.send(files=[upload_id])
        response = self.post("/api/backup")
        self.assertEqual(response.status_code, 200)
        output = io.BytesIO(response.data)
        response.close()
        with zipfile.ZipFile(output) as original:
            tampered = io.BytesIO()
            with zipfile.ZipFile(tampered, "w") as target:
                for name in original.namelist():
                    target.writestr(name, b"changed" if name.startswith("files/") else original.read(name))
        tampered.seek(0)
        target = Path(self.temporary.name) / "bad-restore"
        with self.assertRaises(Problem):
            restore_backup(tampered, target)
        self.assertFalse(target.exists())

    def test_backup_excludes_unsubmitted_uploads(self):
        self.upload(b"not submitted")
        self.send("只有文字")
        with create_backup(self.store) as archive:
            with zipfile.ZipFile(archive) as result:
                manifest = json.loads(result.read("manifest.json"))
                self.assertEqual(manifest["files"], {})

    def test_login_throttling(self):
        for _ in range(10):
            self.assertEqual(self.post("/api/login", {"password": "bad-password"}).status_code, 401)
        self.assertEqual(self.post("/api/login", {"password": PASSWORD}).status_code, 429)

    def test_pagination_does_not_repeat(self):
        for index in range(35):
            self.store.send(new_id(), str(index), "Windows", [])
        first = self.get("/api/messages").json
        second = self.get("/api/messages?before=" + first["next"]).json
        self.assertEqual(len(first["messages"]), 30)
        self.assertEqual(len(second["messages"]), 5)
        self.assertFalse({r["id"] for r in first["messages"]} & {r["id"] for r in second["messages"]})

    @unittest.skipUnless(os.environ.get("TRANSFER_LARGE_TEST") == "1", "Set TRANSFER_LARGE_TEST=1 for a real 500 MB transfer")
    def test_full_500mb_upload_and_hash(self):
        upload = self.store.begin("limit-500MB.bin", FILE_LIMIT, "a" * 64)
        upload_id = upload["id"]
        expected = hashlib.sha256()
        block = b"z" * CHUNK_SIZE
        for index in range((FILE_LIMIT + CHUNK_SIZE - 1) // CHUNK_SIZE):
            data = block[:min(CHUNK_SIZE, FILE_LIMIT - index * CHUNK_SIZE)]
            expected.update(data)
            response = self.client.put(f"/api/uploads/{upload_id}/chunks/{index}", base_url=ORIGIN, data=data,
                                       headers={"Origin": ORIGIN, "X-CSRF-Token": self.csrf, "X-Chunk-SHA256": hashlib.sha256(data).hexdigest()})
            self.assertEqual(response.status_code, 200, response.json)
        response = self.post(f"/api/uploads/{upload_id}/complete")
        self.assertEqual(response.status_code, 200, response.json)
        self.assertEqual(response.json["sha256"], expected.hexdigest())
        self.send(files=[upload_id])
        self.assertEqual(sha256_file(self.root / "files" / upload_id), expected.hexdigest())


if __name__ == "__main__":
    unittest.main()
