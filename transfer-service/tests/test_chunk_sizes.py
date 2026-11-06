"""小分片与历史 8 MiB 上传兼容；只操作独立合成文件。"""
import hashlib
from pathlib import Path
import tempfile
import unittest

from transfer.storage import Store, Problem

MIB = 1024 * 1024
TMP = Path(__file__).resolve().parents[1] / '.tmp' / 'tests'


class ChunkSizeTests(unittest.TestCase):
    def setUp(self):
        TMP.mkdir(parents=True, exist_ok=True)
        self.tmp = tempfile.TemporaryDirectory(dir=TMP)
        self.root = Path(self.tmp.name) / 'data'
        self.store = Store(self.root)

    def tearDown(self):
        self.store.close()
        self.tmp.cleanup()

    def put(self, uid, index, data):
        self.store.put_chunk(uid, index, data, hashlib.sha256(data).hexdigest())

    def test_10613531_byte_file_uses_1mib_and_preserves_original_bytes(self):
        data = (bytes(range(256)) * ((10613531 // 256) + 1))[:10613531]
        uid = self.store.begin('synthetic.mp4', len(data), 'a' * 64)['id']
        self.assertEqual(self.store.get_upload(uid)['chunk_size'], MIB)
        for index, start in enumerate(range(0, len(data), MIB)):
            self.put(uid, index, data[start:start + MIB])
        self.assertEqual(len(self.store.get_upload(uid)['chunks']), 11)
        self.assertEqual(self.store.finish(uid)['sha256'], hashlib.sha256(data).hexdigest())
        self.assertEqual((self.root / 'files' / uid).read_bytes(), data)

    def test_old_client_8mib_first_chunk_and_resume_after_restart(self):
        data = b'legacy' * ((9 * MIB + 17) // 6 + 1)
        uid = self.store.begin('legacy.bin', len(data), 'b' * 64)['id']
        self.put(uid, 0, data[:8 * MIB])
        self.store.close()
        self.store = Store(self.root)
        self.assertEqual(self.store.get_upload(uid)['chunk_size'], 8 * MIB)
        self.put(uid, 0, data[:8 * MIB])
        self.put(uid, 1, data[8 * MIB:])
        self.assertEqual(self.store.finish(uid)['sha256'], hashlib.sha256(data).hexdigest())

    def test_out_of_order_and_mixed_chunk_sizes_do_not_change_confirmed_bytes(self):
        uid = self.store.begin('mixed.bin', 12 * MIB, 'c' * 64)['id']
        with self.assertRaises(Problem):
            self.put(uid, 1, b'a' * MIB)
        with self.assertRaises(Problem):
            self.put(uid, 0, b'a' * (MIB + 1))
        self.assertEqual(self.store.get_upload(uid)['chunks'], {})
        self.put(uid, 0, b'a' * MIB)
        before = (self.root / 'uploads' / uid).read_bytes()
        with self.assertRaises(Problem):
            self.put(uid, 1, b'b' * (8 * MIB))
        self.assertEqual((self.root / 'uploads' / uid).read_bytes(), before)
        self.assertEqual(len(self.store.get_upload(uid)['chunks']), 1)

    def test_small_and_empty_files_keep_valid_finalization(self):
        for data in [b'', b'small']:
            uid = self.store.begin('small.bin', len(data), 'd' * 64)['id']
            if data:
                self.put(uid, 0, data)
            self.assertEqual(self.store.finish(uid)['sha256'], hashlib.sha256(data).hexdigest())
