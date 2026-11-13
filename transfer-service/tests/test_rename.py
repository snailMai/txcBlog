import hashlib
import io
import tempfile
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from PIL import Image
from transfer.app import create_app
from transfer.backup import create_backup, restore_backup
from transfer.storage import Store, new_id

ORIGIN = 'http://127.0.0.1:8765'
TMP = Path(__file__).resolve().parents[1] / '.tmp/tests'


class RenameTests(unittest.TestCase):
    def setUp(self):
        TMP.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=TMP)
        self.app = create_app(Path(self.temp.name) / 'data', origin=ORIGIN, testing=True)
        self.store = self.app.extensions['store']
        self.store.configure('synthetic-rename-password')
        self.client = self.app.test_client()
        token = self.client.get('/api/session', base_url=ORIGIN).json['csrf']
        self.csrf = self.client.post('/api/login', base_url=ORIGIN, json={'password': 'synthetic-rename-password'},
            headers={'Origin': ORIGIN, 'X-CSRF-Token': token}).json['csrf']

    def tearDown(self):
        self.store.close()
        self.temp.cleanup()

    def upload(self, name='original.txt', data=b'synthetic original bytes', send=True):
        digest = hashlib.sha256(data).hexdigest()
        uid = self.store.begin(name, len(data), digest)['id']
        if data:
            self.store.put_chunk(uid, 0, data, digest)
        self.store.finish(uid)
        if send:
            self.store.send(new_id(), '合成记录', 'Windows', [uid])
        return uid

    def rename(self, uid, stem, expected, **kwargs):
        return self.client.patch('/api/uploads/' + uid, base_url=ORIGIN,
            json={'stem': stem, 'expected_name': expected},
            headers={'Origin': ORIGIN, 'X-CSRF-Token': self.csrf, **kwargs})

    def test_changes_only_name_download_search_and_backup(self):
        uid = self.upload()
        before = self.store.get_upload(uid)
        self.assertEqual(self.rename(uid, '项目复盘与行动', 'original.txt').json['name'], '项目复盘与行动.txt')
        after = self.store.get_upload(uid)
        self.assertEqual({k: v for k, v in before.items() if k != 'name'}, {k: v for k, v in after.items() if k != 'name'})
        self.assertEqual((self.store.root / 'files' / uid).read_bytes(), b'synthetic original bytes')
        response = self.client.get('/api/files/' + uid + '/download', base_url=ORIGIN)
        self.assertIn('filename*=', response.headers['Content-Disposition'])
        self.assertEqual(response.data, b'synthetic original bytes')
        response.close()
        self.assertEqual(self.store.history(search='项目复盘')['total'], 1)
        self.assertEqual(self.store.history(search='original.txt')['total'], 0)
        backup = Path(self.temp.name) / 'backup.zip'
        with create_backup(self.store) as archive:
            backup.write_bytes(archive.read())
        restored = Path(self.temp.name) / 'restored'
        restore_backup(backup, restored)
        copy = Store(restored)
        try:
            self.assertEqual(copy.attachment(uid)['name'], '项目复盘与行动.txt')
            self.assertEqual((copy.root / 'files' / uid).read_bytes(), b'synthetic original bytes')
        finally:
            copy.close()

    def test_suffix_preserved_and_client_suffix_field_rejected(self):
        uid = self.upload('photo.JPEG')
        self.assertEqual(self.rename(uid, '网络配置', 'photo.JPEG').json['name'], '网络配置.JPEG')
        response = self.client.patch('/api/uploads/' + uid, base_url=ORIGIN,
            json={'stem': 'other', 'expected_name': '网络配置.JPEG', 'extension': '.exe'},
            headers={'Origin': ORIGIN, 'X-CSRF-Token': self.csrf})
        self.assertEqual(response.status_code, 400)
        self.assertEqual(self.store.get_upload(uid)['name'], '网络配置.JPEG')

    def test_invalid_and_boundary_names_never_modify_data(self):
        uid = self.upload()
        for stem in ['', ' ', '.', '..', 'a/b', 'a\\b', 'a\r\nb', 'a\x00b', 'a\x7fb', 'a:b', 'a*', 'a?', 'a"', 'a<', 'a>', 'a|', 'a ', 'a.', 'x' * 237, 1, None]:
            with self.subTest(stem=repr(stem)):
                self.assertEqual(self.rename(uid, stem, 'original.txt').status_code, 400)
                self.assertEqual(self.store.get_upload(uid)['name'], 'original.txt')
        self.assertEqual(self.rename(uid, 'x' * 236, 'original.txt').status_code, 200)

    def test_no_suffix_and_hidden_name_do_not_gain_extension(self):
        for original in ['README', '.env']:
            uid = self.upload(original)
            self.assertEqual(self.rename(uid, 'report.txt', original).status_code, 400)
            self.assertEqual(self.rename(uid, '项目说明', original).json['name'], '项目说明')

    def test_repeat_requests_are_idempotent_and_stale_page_conflicts(self):
        uid = self.upload()
        self.assertEqual(self.rename(uid, 'first', 'original.txt').status_code, 200)
        self.assertEqual(self.rename(uid, 'first', 'original.txt').status_code, 200)
        self.assertEqual(self.rename(uid, 'second', 'original.txt').status_code, 409)
        self.assertEqual(self.store.get_upload(uid)['name'], 'first.txt')

    def test_concurrent_renames_allow_one_winner(self):
        uid = self.upload()
        def change(stem):
            try:
                return self.store.rename(uid, stem, 'original.txt')['name']
            except Exception as error:
                return error.status
        with ThreadPoolExecutor(max_workers=2) as pool:
            result = list(pool.map(change, ['first', 'second']))
        self.assertEqual(sum(value == 409 for value in result), 1)

    def test_deleted_unknown_and_invalid_ids_rejected(self):
        uid = self.upload()
        self.store.remove_message(self.store.get_upload(uid)['message_id'])
        self.assertEqual(self.rename(uid, 'name', 'original.txt').status_code, 404)
        for other in ['../file', 'a' * 32]:
            self.assertEqual(self.rename(other, 'name', 'original.txt').status_code, 404)

    def test_pending_upload_rename_preserves_fingerprint_and_chunks(self):
        uid = self.store.begin('original.txt', 4, 'a' * 64)['id']
        self.store.put_chunk(uid, 0, b'data', hashlib.sha256(b'data').hexdigest())
        self.assertEqual(self.rename(uid, '自定义名称', 'original.txt').status_code, 200)
        result = self.store.finish(uid)
        self.assertEqual(result['name'], '自定义名称.txt')
        self.assertEqual(result['fingerprint'], 'a' * 64)

    def test_initial_upload_custom_stem_reuses_original_suffix_and_old_api(self):
        for name, stem, expected in [('original.PDF', '会议摘要', '会议摘要.PDF'), ('README', '项目说明', '项目说明')]:
            response = self.client.post('/api/uploads', base_url=ORIGIN,
                json={'name': name, 'stem': stem, 'size': 0, 'fingerprint': 'a' * 64},
                headers={'Origin': ORIGIN, 'X-CSRF-Token': self.csrf})
            self.assertEqual(response.status_code, 201)
            self.assertEqual(response.json['name'], expected)
        self.assertEqual(self.store.begin('legacy.txt', 0, 'b' * 64)['name'], 'legacy.txt')
        for stem in ['', 'folder/file', 'name.exe']:
            original = 'README' if stem == 'name.exe' else 'file.txt'
            response = self.client.post('/api/uploads', base_url=ORIGIN,
                json={'name': original, 'stem': stem, 'size': 0, 'fingerprint': 'a' * 64},
                headers={'Origin': ORIGIN, 'X-CSRF-Token': self.csrf})
            self.assertEqual(response.status_code, 400)

    def test_same_names_on_different_files_are_allowed(self):
        first = self.upload(); second = self.upload()
        for uid in [first, second]:
            self.assertEqual(self.rename(uid, 'same', 'original.txt').status_code, 200)

    def test_compound_suffix_and_dotted_stem_preserved(self):
        for original, expected in [('archive.TAR.GZ', '项目备份.TAR.GZ'), ('report.v1.pdf', '项目备份.pdf')]:
            uid = self.upload(original)
            self.assertEqual(self.rename(uid, '项目备份', original).json['name'], expected)

    def test_image_bytes_thumbnail_and_mime_preserved(self):
        output = io.BytesIO(); Image.new('RGB', (12, 18), 'green').save(output, format='PNG')
        data = output.getvalue(); uid = self.upload('image.png', data)
        before = (self.store.root / 'thumbs' / uid).read_bytes()
        self.assertEqual(self.rename(uid, '路由器配置', 'image.png').status_code, 200)
        self.assertEqual(self.store.get_upload(uid)['mime'], 'image/png')
        self.assertEqual((self.store.root / 'files' / uid).read_bytes(), data)
        self.assertEqual((self.store.root / 'thumbs' / uid).read_bytes(), before)

    def test_authentication_origin_and_csrf_are_required(self):
        uid = self.upload()
        self.assertEqual(self.rename(uid, 'name', 'original.txt', **{'X-CSRF-Token': ''}).status_code, 403)
        self.assertEqual(self.rename(uid, 'name', 'original.txt', Origin='https://evil.example').status_code, 403)
        other = self.app.test_client()
        token = other.get('/api/session', base_url=ORIGIN).json['csrf']
        self.assertEqual(other.patch('/api/uploads/' + uid, base_url=ORIGIN,
            json={'stem': 'name', 'expected_name': 'original.txt'}, headers={'Origin': ORIGIN, 'X-CSRF-Token': token}).status_code, 401)
        self.assertEqual(self.store.get_upload(uid)['name'], 'original.txt')


if __name__ == '__main__':
    unittest.main()
