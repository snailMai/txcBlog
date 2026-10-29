"""Password changes use only isolated synthetic data, never production credentials."""
import hashlib
import tempfile
import time
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from unittest.mock import patch

from werkzeug.security import check_password_hash

from transfer.app import create_app
from transfer.storage import Problem, Store, new_id

ORIGIN = "http://127.0.0.1:8765"
OLD = "synthetic-old-password"
NEW = "abcdefghij"
TMP = Path(__file__).resolve().parents[1] / ".tmp" / "tests"


class PasswordTests(unittest.TestCase):
    def setUp(self):
        TMP.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(dir=TMP)
        self.root = Path(self.temporary.name) / "data"
        self.app = create_app(self.root, origin=ORIGIN, prefix="/transfer", testing=True)
        self.store = self.app.extensions["store"]
        self.store.configure(OLD)
        self.client, self.headers = self.login(OLD)

    def tearDown(self):
        self.store.close()
        self.temporary.cleanup()

    def login(self, password):
        client = self.app.test_client()
        csrf = client.get("/transfer/api/session", base_url=ORIGIN).json["csrf"]
        result = client.post("/transfer/api/login", base_url=ORIGIN,
                             headers={"Origin": ORIGIN, "X-CSRF-Token": csrf},
                             json={"password": password})
        self.assertEqual(result.status_code, 200, result.json)
        return client, {"Origin": ORIGIN, "X-CSRF-Token": result.json["csrf"]}

    def change(self, **values):
        payload = {"current_password": OLD, "new_password": NEW, "confirm_password": NEW}
        payload.update(values)
        return self.client.post("/transfer/api/password", base_url=ORIGIN,
                                headers=self.headers, json=payload)

    def saved_hash(self):
        with self.store.db() as db:
            return db.execute("SELECT value FROM settings WHERE key='password'").fetchone()[0]

    def test_ten_character_change_revokes_every_session_and_preserves_content(self):
        second, _ = self.login(OLD)
        data = b"preserve original bytes\x00\xff"
        digest = hashlib.sha256(data).hexdigest()
        uid = self.store.begin("original.bin", len(data), digest)["id"]
        self.store.put_chunk(uid, 0, data, digest)
        self.store.finish(uid)
        message = self.store.send(new_id(), "保留文字与附件", "Windows", [uid])
        before_hash = self.saved_hash()
        result = self.change()
        self.assertEqual(result.status_code, 200, result.json)
        self.assertIn("Path=/transfer/", result.headers["Set-Cookie"])
        self.assertNotEqual(self.saved_hash(), before_hash)
        self.assertTrue(check_password_hash(self.saved_hash(), NEW))
        self.assertNotIn(NEW, self.saved_hash())
        with self.store.db() as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM sessions").fetchone()[0], 0)
        for client in (self.client, second):
            self.assertEqual(client.get("/transfer/api/messages", base_url=ORIGIN).status_code, 401)
        client, headers = self.login(NEW)
        history = client.get("/transfer/api/messages", base_url=ORIGIN).json
        self.assertEqual(history["messages"][0]["id"], message)
        response = client.get(f"/transfer/api/files/{uid}/download", base_url=ORIGIN)
        self.assertEqual(response.data, data)
        response.close()
        self.assertEqual(client.post("/transfer/api/login", base_url=ORIGIN, headers=headers,
                                     json={"password": OLD}).status_code, 401)

    def test_invalid_input_and_wrong_old_password_keep_existing_login(self):
        before = self.saved_hash()
        for values in ({"current_password": "wrong"}, {"current_password": None},
                       {"current_password": ""}, {"current_password": "x" * 257},
                       {"new_password": "a" * 9, "confirm_password": "a" * 9},
                       {"new_password": "a" * 257, "confirm_password": "a" * 257},
                       {"new_password": None}, {"confirm_password": "mismatch"}):
            with self.subTest(values=list(values)):
                self.assertEqual(self.change(**values).status_code, 400)
                self.assertEqual(self.saved_hash(), before)
                self.assertEqual(self.client.get("/transfer/api/messages", base_url=ORIGIN).status_code, 200)

    def test_authentication_origin_and_csrf_are_required(self):
        outsider = self.app.test_client()
        token = outsider.get("/transfer/api/session", base_url=ORIGIN).json["csrf"]
        payload = {"current_password": OLD, "new_password": NEW, "confirm_password": NEW}
        response = outsider.post("/transfer/api/password", base_url=ORIGIN, json=payload,
                                 headers={"Origin": ORIGIN, "X-CSRF-Token": token})
        self.assertEqual(response.status_code, 401)
        for headers in ({"Origin": ORIGIN}, {**self.headers, "Origin": "https://other.example"}):
            self.assertEqual(self.client.post("/transfer/api/password", base_url=ORIGIN,
                                             json=payload, headers=headers).status_code, 403)
        self.assertTrue(check_password_hash(self.saved_hash(), OLD))

    def test_wrong_old_password_is_rate_limited_without_revoking_sessions(self):
        for _ in range(10):
            self.assertEqual(self.change(current_password="wrong").status_code, 400)
        self.assertEqual(self.change().status_code, 429)
        self.assertTrue(check_password_hash(self.saved_hash(), OLD))
        self.assertEqual(self.client.get("/transfer/api/messages", base_url=ORIGIN).status_code, 200)
        with patch("transfer.storage.time.time", return_value=time.time() + 901):
            self.assertEqual(self.change().status_code, 200)

    def test_concurrent_changes_cannot_use_an_already_revoked_session(self):
        second, _ = self.login(OLD)
        with self.client.session_transaction(base_url=ORIGIN, path="/transfer/") as session:
            first_sid = session["sid"]
        with second.session_transaction(base_url=ORIGIN, path="/transfer/") as session:
            second_sid = session["sid"]

        def change(sid):
            try:
                self.store.change_password(sid, OLD, NEW)
                return 200
            except Problem as error:
                return error.status

        with ThreadPoolExecutor(max_workers=2) as pool:
            self.assertEqual(sorted(pool.map(change, [first_sid, second_sid])), [200, 401])

    def test_session_revocation_failure_rolls_back_password_change(self):
        before = self.saved_hash()
        with self.store.db() as db:
            db.execute("CREATE TRIGGER fail_revoke BEFORE DELETE ON sessions BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
        with self.assertLogs(self.app.logger, level="ERROR") as logs:
            result = self.change()
        self.assertEqual(result.status_code, 503)
        self.assertEqual(self.saved_hash(), before)
        self.assertEqual(self.client.get("/transfer/api/messages", base_url=ORIGIN).status_code, 200)
        for secret in (OLD, NEW):
            self.assertNotIn(secret, " ".join(logs.output))
            self.assertNotIn(secret, result.get_data(as_text=True))

    def test_setup_bounds_allow_simple_ten_character_and_maximum_passwords(self):
        for index, password in enumerate(("0123456789", "中" * 10, "a" * 256)):
            store = Store(Path(self.temporary.name) / f"setup-{index}")
            try:
                for invalid in ("a" * 9, "a" * 257, None):
                    with self.assertRaises(Problem):
                        store.configure(invalid)
                store.configure(password)
                self.assertTrue(store.configured())
            finally:
                store.close()

    def test_maximum_length_password_and_mounted_form(self):
        result = self.change(new_password="a" * 256, confirm_password="a" * 256)
        self.assertEqual(result.status_code, 200)
        self.login("a" * 256)
        page = self.client.get("/transfer/?embedded=1", base_url=ORIGIN).get_data(as_text=True)
        for text in ('id="change-password-button"', 'id="password-form"', 'minlength="10"', 'autocomplete="new-password"'):
            self.assertIn(text, page)


if __name__ == "__main__":
    unittest.main()
