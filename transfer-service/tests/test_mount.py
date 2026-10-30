"""工具站挂载回归：全部使用项目临时目录，不能读写生产传输数据。"""
import hashlib
import tempfile
import unittest
from pathlib import Path

from transfer.app import create_app
from transfer.storage import new_id

ORIGIN = "http://127.0.0.1:8765"
PREFIX = "/transfer"
TMP = Path(__file__).resolve().parents[1] / ".tmp" / "tests"


class MountedTransferTests(unittest.TestCase):
    def setUp(self):
        TMP.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(dir=TMP)
        self.app = create_app(Path(self.temporary.name) / "data", origin=ORIGIN,
                              testing=True, prefix=PREFIX)
        self.client = self.app.test_client()

    def tearDown(self):
        self.app.extensions["store"].close()
        self.temporary.cleanup()

    def request(self, path, method="GET", **kwargs):
        return self.client.open(PREFIX + path, method=method, base_url=ORIGIN, **kwargs)

    def login(self):
        token = self.request("/api/session").json["csrf"]
        headers = {"Origin": ORIGIN, "X-CSRF-Token": token}
        password = {"password": "synthetic-mounted-password"}
        self.assertEqual(self.request("/api/setup", "POST", json=password, headers=headers).status_code, 200)
        login = self.request("/api/login", "POST", json=password, headers=headers)
        self.assertEqual(login.status_code, 200)
        return {"Origin": ORIGIN, "X-CSRF-Token": login.json["csrf"]}

    def test_pages_assets_cookie_and_worker_stay_in_namespace(self):
        page = self.request("/?embedded=1")
        text = page.get_data(as_text=True)
        self.assertEqual(page.status_code, 200)
        self.assertIn('data-base="/transfer"', text)
        self.assertIn('class="embedded"', text)
        self.assertIn('/transfer/static/app.js', text)
        self.assertIn('/transfer/manifest.webmanifest', text)
        self.assertEqual(page.headers["X-Frame-Options"], "SAMEORIGIN")
        self.assertIn("frame-ancestors 'self'", page.headers["Content-Security-Policy"])
        self.assertIn('type="module"', text)
        for path in ("/static/app.js", "/static/image-clipboard.mjs", "/static/transfer-io.mjs", "/static/style.css", "/static/icon.svg", "/health"):
            response = self.request(path)
            self.assertEqual(response.status_code, 200)
            if path.endswith(".mjs"):
                self.assertIn(response.mimetype, ("text/javascript", "application/javascript"))
            response.close()
        manifest = self.request("/manifest.webmanifest").json
        for key in ("id", "scope", "start_url"):
            self.assertEqual(manifest[key], "/transfer/")
        self.assertEqual(manifest["share_target"]["action"], "/transfer/share-target")
        self.assertTrue(all(icon["src"].startswith("/transfer/static/") for icon in manifest["icons"]))
        worker = self.request("/sw.js")
        self.assertEqual(worker.headers["Service-Worker-Allowed"], "/transfer/")
        worker.close()
        session = self.request("/api/session")
        self.assertIn("Path=/transfer/", session.headers["Set-Cookie"])
        for path in ("/", "/Todoist", "/api/messages", "/static/app.js", "/sw.js"):
            self.assertEqual(self.client.get(path, base_url=ORIGIN).status_code, 404)

    def test_mounted_text_file_download_and_auth_roundtrip(self):
        self.assertEqual(self.request("/api/messages").status_code, 401)
        headers = self.login()
        data = b"synthetic mounted file\x00\xff"
        digest = hashlib.sha256(data).hexdigest()
        upload = self.request("/api/uploads", "POST", headers=headers,
                              json={"name": "same-host.bin", "size": len(data), "fingerprint": digest})
        self.assertEqual(upload.status_code, 201)
        uid = upload.json["id"]
        chunk_headers = {**headers, "X-Chunk-SHA256": digest, "Content-Type": "application/octet-stream"}
        self.assertEqual(self.request(f"/api/uploads/{uid}/chunks/0", "PUT", headers=chunk_headers, data=data).status_code, 200)
        self.assertEqual(self.request(f"/api/uploads/{uid}/complete", "POST", headers=headers).status_code, 200)
        payload = {"text": "合成跨设备文字", "device": "Windows", "files": [uid], "client_id": new_id()}
        self.assertEqual(self.request("/api/messages", "POST", headers=headers, json=payload).status_code, 200)
        self.assertEqual(self.request("/api/messages").json["messages"][0]["text"], payload["text"])
        download = self.request(f"/api/files/{uid}/download")
        self.assertEqual(download.data, data)
        download.close()
        outsider = self.app.test_client()
        self.assertEqual(outsider.get(f"/transfer/api/files/{uid}/download", base_url=ORIGIN).status_code, 401)
        self.assertEqual(self.request("/api/messages", "POST", json=payload,
                                     headers={**headers, "Origin": "https://other.example"}).status_code, 403)
        self.assertEqual(self.request("/api/logout", "POST", headers=headers).status_code, 200)
        self.assertEqual(self.request("/api/messages").status_code, 401)

    def test_rejects_invalid_prefix_before_creating_storage(self):
        for prefix in ("/", "/../x", "//transfer", "transfer", "/transfer/", "/transfer?x"):
            with self.assertRaises(ValueError):
                create_app(Path(self.temporary.name) / "invalid", prefix=prefix)
        self.assertFalse((Path(self.temporary.name) / "invalid").exists())


if __name__ == "__main__":
    unittest.main()
