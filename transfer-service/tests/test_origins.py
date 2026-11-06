"""公网及局域网入口共用合成数据，禁止读取真实传输数据。"""
import tempfile
import unittest
from pathlib import Path

from transfer.app import create_app
from transfer.storage import new_id

PUBLIC = "https://tools.example.test"
LAN = "https://192.168.3.195:8443"
SECOND = "https://192.168.3.159:8443"
TMP = Path(__file__).resolve().parents[1] / ".tmp" / "tests"


class MultipleOriginTests(unittest.TestCase):
    def setUp(self):
        TMP.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=TMP)
        self.root = Path(self.temp.name)
        self.app = create_app(self.root / "data", origin=PUBLIC, testing=True,
                              prefix="/transfer", additional_origins=[LAN, SECOND])
        self.store = self.app.extensions["store"]
        self.store.configure("synthetic-lan-password")

    def tearDown(self):
        self.store.close()
        self.temp.cleanup()

    def login(self, origin):
        client = self.app.test_client()
        response = client.get("/transfer/api/session", base_url=origin)
        cookie = response.headers["Set-Cookie"]
        for part in ("Secure", "HttpOnly", "SameSite=Strict", "Path=/transfer/"):
            self.assertIn(part, cookie)
        headers = {"Origin": origin, "X-CSRF-Token": response.json["csrf"]}
        response = client.post("/transfer/api/login", base_url=origin, headers=headers,
                               json={"password": "synthetic-lan-password"})
        self.assertEqual(response.status_code, 200)
        return client, {"Origin": origin, "X-CSRF-Token": response.json["csrf"]}

    def test_entries_share_records_but_require_separate_login(self):
        public, public_headers = self.login(PUBLIC)
        for origin in (LAN, SECOND):
            self.assertEqual(public.get("/transfer/api/messages", base_url=origin).status_code, 401)
            client, headers = self.login(origin)
            response = client.post("/transfer/api/messages", base_url=origin, headers=headers,
                                   json={"text": "合成局域网记录", "device": "Windows", "files": [], "client_id": new_id()})
            self.assertEqual(response.status_code, 200)
        self.assertEqual(len(public.get("/transfer/api/messages", base_url=PUBLIC).json["messages"]), 2)

    def test_host_origin_pairs_and_csrf_are_still_enforced(self):
        for origin in (PUBLIC, LAN):
            client, headers = self.login(origin)
            other = LAN if origin == PUBLIC else PUBLIC
            for wrong in (other, "https://evil.example", "null", origin + ".evil.example", origin + "/"):
                response = client.post("/transfer/api/messages", base_url=origin,
                                       headers={**headers, "Origin": wrong}, json={})
                self.assertEqual(response.status_code, 403)
            self.assertEqual(client.post("/transfer/api/messages", base_url=origin,
                                        headers={"Origin": origin}, json={}).status_code, 403)
            self.assertEqual(client.get("/transfer/health", base_url="https://192.168.3.196:8443").status_code, 400)
            self.assertEqual(client.get("/transfer/health", base_url="https://192.168.3.195:8444").status_code, 400)
            self.assertEqual(client.get("/transfer/health", base_url=origin,
                                       headers={"Host": "evil.example", "X-Forwarded-Host": origin.split("//")[1]}).status_code, 400)

    def test_invalid_configuration_fails_before_storage_is_created(self):
        invalid = ["http://192.168.3.195:8443", "https://*.example", "https://user:secret@example.test",
                   "https://example.test/path", "https://example.test?x=1", "https://example.test#x",
                   "https://example.test:0", "https://example.test:70000", PUBLIC]
        for extra in invalid:
            path = self.root / "invalid"
            with self.subTest(origin=extra), self.assertRaises(ValueError):
                create_app(path, origin=PUBLIC, additional_origins=[extra])
            self.assertFalse(path.exists())
        with self.assertRaises(ValueError):
            create_app(self.root / "invalid", origin="http://127.0.0.1:8765", additional_origins=[LAN])


if __name__ == "__main__":
    unittest.main()
