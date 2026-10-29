"""Batch deletion is tested only against disposable synthetic data."""
import io
import unittest
from unittest.mock import patch

from PIL import Image

import test_transfer as fixtures
from transfer.storage import new_id


class BatchDeleteTests(unittest.TestCase):
    setUp = fixtures.TransferTests.setUp
    tearDown = fixtures.TransferTests.tearDown
    get = fixtures.TransferTests.get
    request = fixtures.TransferTests.request
    post = fixtures.TransferTests.post
    upload = fixtures.TransferTests.upload
    send = fixtures.TransferTests.send

    def delete(self, ids):
        return self.request("DELETE", "/api/messages", {"ids": ids})

    def test_selected_records_originals_and_thumbnails_deleted_others_unchanged(self):
        output = io.BytesIO()
        Image.new("RGB", (40, 30), "green").save(output, "PNG")
        image = self.upload(output.getvalue(), "synthetic.png")
        file = self.upload(b"selected file", "selected.bin")
        keep_file = self.upload(b"keep these bytes", "keep.bin")
        first = self.send("选中的图片记录", [image])
        second = self.send("选中的文件记录", [file])
        keep = self.send("未选中的记录", [keep_file])
        before = self.get("/api/stats").json["used"]
        response = self.delete([first, second])
        self.assertEqual(response.status_code, 200, response.json)
        self.assertEqual(response.json["deleted"], 2)
        self.assertEqual([row["id"] for row in self.get("/api/messages").json["messages"]], [keep])
        self.assertLess(self.get("/api/stats").json["used"], before)
        for uid in (image, file):
            self.assertEqual(self.get(f"/api/files/{uid}/download").status_code, 404)
            self.assertFalse((self.root / "files" / uid).exists())
            self.assertFalse((self.root / "thumbs" / uid).exists())
        self.assertEqual((self.root / "files" / keep_file).read_bytes(), b"keep these bytes")

    def test_retry_missing_and_already_deleted_ids_do_not_expand_scope(self):
        first, second, keep = self.send("first"), self.send("second"), self.send("keep")
        self.assertEqual(self.request("DELETE", f"/api/messages/{first}").status_code, 200)
        result = self.delete([first, second, new_id()])
        self.assertEqual(result.json["deleted"], 1)
        self.assertEqual(self.delete([first, second]).json["deleted"], 0)
        self.assertEqual(self.get("/api/messages").json["messages"][0]["id"], keep)
        self.assertEqual(self.request("DELETE", f"/api/messages/{first}").status_code, 404)

    def test_invalid_input_is_rejected_before_any_record_changes(self):
        keep = self.send("不得删除")
        invalid = (None, [], "all", [keep, keep], [keep, "bad"], [True], [{}], [new_id() for _ in range(1001)])
        for ids in invalid:
            with self.subTest(input_type=type(ids).__name__, size=len(ids) if isinstance(ids, list) else None):
                self.assertEqual(self.delete(ids).status_code, 400)
                self.assertEqual(self.get("/api/messages").json["messages"][0]["id"], keep)
        self.assertEqual(self.delete([new_id() for _ in range(1000)]).status_code, 200)
        self.assertEqual(self.get("/api/stats").json["count"], 1)

    def test_authentication_csrf_and_origin_remain_required(self):
        keep = self.send("protected")
        outsider = self.app.test_client()
        token = outsider.get("/api/session", base_url=fixtures.ORIGIN).json["csrf"]
        self.assertEqual(outsider.delete("/api/messages", base_url=fixtures.ORIGIN, json={"ids": [keep]},
                                        headers={"Origin": fixtures.ORIGIN, "X-CSRF-Token": token}).status_code, 401)
        self.assertEqual(self.client.delete("/api/messages", base_url=fixtures.ORIGIN, json={"ids": [keep]}).status_code, 403)
        self.assertEqual(self.request("DELETE", "/api/messages", {"ids": [keep]},
                                      headers={"Origin": "https://other.example"}).status_code, 403)
        self.assertEqual(self.get("/api/stats").json["count"], 1)

    def test_database_failure_rolls_back_entire_batch_before_file_cleanup(self):
        first, second = self.send("first"), self.send("second")
        with self.store.db() as db:
            db.execute("CREATE TRIGGER fail_batch BEFORE UPDATE ON messages WHEN OLD.id='" + second + "' BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
        with self.assertLogs(self.app.logger, level="ERROR"):
            result = self.delete([first, second])
        self.assertEqual(result.status_code, 503)
        self.assertEqual({row["text"] for row in self.get("/api/messages").json["messages"]}, {"first", "second"})

    def test_file_cleanup_failure_is_retryable_and_deleted_file_is_inaccessible(self):
        uid = self.upload(b"remove after retry")
        message = self.send("selected", [uid])
        with patch.object(self.store, "_remove_files", side_effect=OSError("synthetic cleanup error")):
            with self.assertLogs(self.app.logger, level="ERROR"):
                response = self.delete([message])
        self.assertEqual(response.status_code, 503)
        self.assertEqual(self.get(f"/api/files/{uid}/download").status_code, 404)
        self.assertTrue((self.root / "files" / uid).exists())
        self.assertEqual(self.delete([message]).status_code, 200)
        self.assertFalse((self.root / "files" / uid).exists())

    def test_mounted_batch_route_uses_same_authentication_boundary(self):
        from transfer.app import create_app
        app = create_app(self.root.parent / "mounted", origin=fixtures.ORIGIN, prefix="/transfer", testing=True)
        try:
            store = app.extensions["store"]
            store.configure("synthetic-mounted-password")
            client = app.test_client()
            token = client.get("/transfer/api/session", base_url=fixtures.ORIGIN).json["csrf"]
            headers = {"Origin": fixtures.ORIGIN, "X-CSRF-Token": token}
            login = client.post("/transfer/api/login", base_url=fixtures.ORIGIN, headers=headers,
                                json={"password": "synthetic-mounted-password"})
            headers["X-CSRF-Token"] = login.json["csrf"]
            uid = store.send(new_id(), "mounted batch", "Android", [])
            result = client.delete("/transfer/api/messages", base_url=fixtures.ORIGIN, headers=headers, json={"ids": [uid]})
            self.assertEqual(result.json, {"ok": True, "deleted": 1})
            self.assertEqual(client.delete("/api/messages", base_url=fixtures.ORIGIN, headers=headers, json={"ids": [uid]}).status_code, 404)
        finally:
            app.extensions["store"].close()


if __name__ == "__main__":
    unittest.main()
