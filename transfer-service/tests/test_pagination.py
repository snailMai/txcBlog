import hashlib
import io
import sqlite3
import tempfile
import unittest
from contextlib import contextmanager
from pathlib import Path
from unittest.mock import patch

from PIL import Image

from transfer.app import create_app
from transfer.storage import Problem, new_id

ORIGIN = "http://127.0.0.1:18780"
TMP = Path(__file__).resolve().parents[1] / ".tmp" / "tests"


class HistoryPaginationTests(unittest.TestCase):
    def setUp(self):
        TMP.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(dir=TMP)
        self.app = create_app(Path(self.temporary.name) / "data", origin=ORIGIN, prefix="/transfer", testing=True)
        self.store = self.app.extensions["store"]
        self.store.configure("synthetic-pagination-password")
        self.client = self.app.test_client()
        token = self.get("/api/session").json["csrf"]
        response = self.client.post("/transfer/api/login", base_url=ORIGIN,
                                    headers={"Origin": ORIGIN, "X-CSRF-Token": token},
                                    json={"password": "synthetic-pagination-password"})
        self.assertEqual(response.status_code, 200)

    def tearDown(self):
        self.store.close()
        self.temporary.cleanup()

    def get(self, path):
        return self.client.get("/transfer" + path, base_url=ORIGIN)

    def send(self, text="", files=None):
        return self.store.send(new_id(), text, "Windows", files or [])

    def file(self, name, content):
        digest = hashlib.sha256(content).hexdigest()
        item = self.store.begin(name, len(content), digest)
        self.store.put_chunk(item["id"], 0, content, digest)
        self.store.finish(item["id"])
        return item["id"]

    def test_page_sizes_and_legacy_default_have_no_duplicates(self):
        expected = [self.send(f"记录 {i}") for i in range(43)][::-1]
        for size in (5, 10, 20):
            seen, before = [], None
            while True:
                response = self.get(f"/api/messages?page_size={size}" + (f"&before={before}" if before else ""))
                self.assertEqual(response.status_code, 200)
                data = response.json
                self.assertLessEqual(len(data["messages"]), size)
                self.assertEqual(data["total"], 43)
                self.assertEqual(data["latest"], expected[0])
                seen.extend(row["id"] for row in data["messages"])
                before = data["next"]
                if not before:
                    break
            self.assertEqual(seen, expected)
        legacy = self.get("/api/messages").json
        self.assertEqual(len(legacy["messages"]), 30)
        self.assertEqual(len(self.get("/api/messages?before=" + legacy["next"]).json["messages"]), 13)

    def test_matching_totals_include_filename_filters_and_exclude_deleted(self):
        picture = io.BytesIO()
        Image.new("RGB", (8, 8), "green").save(picture, "PNG")
        image = self.file("needle.png", picture.getvalue())
        document = self.file("needle.txt", b"synthetic-document")
        text_id = self.send("needle text")
        image_id = self.send(files=[image])
        file_id = self.send(files=[document])
        removed = self.send("needle removed")
        self.store.remove_message(removed)
        for kind, expected in (("all", [file_id, image_id, text_id]), ("text", [text_id]), ("image", [image_id]), ("file", [file_id])):
            data = self.get(f"/api/messages?q=NEEDLE&kind={kind}&page_size=5").json
            self.assertEqual(data["total"], len(expected))
            self.assertEqual(data["latest"], expected[0])
            self.assertEqual([row["id"] for row in data["messages"]], expected)
        empty = self.get("/api/messages?q=absent&page_size=5").json
        self.assertEqual(empty, {"messages": [], "next": None, "latest": None, "total": 0})

    def test_invalid_sizes_cursors_and_anonymous_requests(self):
        for size in ("", "0", "1", "30", "21", "-5", "5.0", "05", "abc", "100000"):
            self.assertEqual(self.get("/api/messages?page_size=" + size).status_code, 400, size)
        for size in (True, False, 0, 30, "5", 5.0):
            with self.assertRaisesRegex(Problem, "每页条数"):
                self.store.history(page_size=size)
        self.assertEqual(self.get("/api/messages?page_size=5&before=oops").status_code, 400)
        self.assertEqual(self.get("/api/messages?page_size=5&kind=wrong").status_code, 400)
        anonymous = self.app.test_client()
        self.assertEqual(anonymous.get("/transfer/api/messages?page_size=5", base_url=ORIGIN).status_code, 401)

    def test_new_records_and_deleted_boundary_do_not_repeat_forward_pages(self):
        expected = [self.send(str(i)) for i in range(12)][::-1]
        first = self.get("/api/messages?page_size=5").json
        newest = self.send("arrived later")
        self.store.remove_message(first["next"])
        second = self.get("/api/messages?page_size=5&before=" + first["next"]).json
        self.assertEqual([row["id"] for row in second["messages"]], expected[5:10])
        self.assertEqual(second["total"], 12)
        self.assertEqual(second["latest"], newest)
        self.assertFalse({row["id"] for row in first["messages"]} & {row["id"] for row in second["messages"]})

    def test_total_rows_and_latest_share_one_read_snapshot(self):
        ids = [self.send(str(i)) for i in range(5)]
        original_db = self.store.db
        removed = False

        class Connection:
            def __init__(self, db):
                self.db = db

            def execute(inner, sql, *args):
                nonlocal removed
                cursor = inner.db.execute(sql, *args)
                if sql.startswith("SELECT COUNT(*) AS total") and not removed:
                    removed = True
                    with sqlite3.connect(self.store.root / "history.sqlite3") as writer:
                        writer.execute("UPDATE messages SET deleted=1,text='' WHERE id=?", (ids[0],))
                return cursor

        @contextmanager
        def database():
            with original_db() as db:
                yield Connection(db)

        with patch.object(self.store, "db", database):
            snapshot = self.store.history(page_size=5)
        self.assertEqual(snapshot["total"], 5)
        self.assertEqual(len(snapshot["messages"]), 5)
        self.assertEqual(snapshot["latest"], ids[-1])
        self.assertEqual(self.store.history(page_size=5)["total"], 4)
