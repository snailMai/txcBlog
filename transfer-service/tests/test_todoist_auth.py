import tempfile
import time
import unittest
from pathlib import Path

from transfer.app import create_app

ORIGIN = 'https://tools.example.test'
TMP = Path(__file__).resolve().parents[1] / '.tmp/tests'


class TodoistAuthTests(unittest.TestCase):
    def setUp(self):
        TMP.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=TMP)
        self.app = create_app(Path(self.temp.name) / 'data', origin=ORIGIN, prefix='/transfer', testing=True)
        self.store = self.app.extensions['store']
        self.store.configure('synthetic-access-password')
        self.client = self.app.test_client()

    def tearDown(self):
        self.store.close()
        self.temp.cleanup()

    def request(self, path, method='GET', client=None, **kwargs):
        return (client or self.client).open('/transfer/api/' + path, method=method, base_url=ORIGIN, **kwargs)

    def login(self):
        token = self.request('session').json['csrf']
        response = self.request('login', 'POST', json={'password':'synthetic-access-password'},
                                headers={'Origin':ORIGIN, 'X-CSRF-Token':token})
        self.assertEqual(response.status_code, 200)
        return response.json['csrf']

    def test_shared_login_and_write_csrf(self):
        self.assertEqual(self.request('todoist-authorize').status_code, 401)
        token = self.login()
        self.assertEqual(self.request('todoist-authorize').status_code, 204)
        headers={'X-Forwarded-Method':'POST', 'Origin':ORIGIN, 'X-CSRF-Token':token}
        self.assertEqual(self.request('todoist-authorize', headers=headers).status_code, 204)
        for invalid in ({'Origin':'https://evil.example'}, {'X-CSRF-Token':''}):
            self.assertEqual(self.request('todoist-authorize', headers={**headers, **invalid}).status_code, 403)
        self.assertEqual(self.request('todoist-authorize', headers={'X-Forwarded-Method':'DELETE'}).status_code, 405)

    def test_existing_mount_cookie_is_migrated_without_new_login(self):
        self.login()
        cookie = self.client.get_cookie('snail_session', domain='tools.example.test', path='/').value
        legacy = self.app.test_client()
        legacy.set_cookie('snail_session', cookie, domain='tools.example.test', path='/transfer/')
        response = self.request('session', client=legacy)
        self.assertTrue(response.json['authenticated'])
        self.assertIsNone(legacy.get_cookie('snail_session', domain='tools.example.test', path='/transfer/'))
        root_cookie=legacy.get_cookie('snail_session', domain='tools.example.test', path='/')
        self.assertIsNotNone(root_cookie)
        self.assertTrue(root_cookie.secure and root_cookie.http_only)
        self.assertEqual(self.request('todoist-authorize', client=legacy).status_code, 204)

    def test_logout_expiration_and_password_change_revoke_access(self):
        token=self.login()
        self.assertEqual(self.request('logout','POST',json={},headers={'Origin':ORIGIN,'X-CSRF-Token':token}).status_code,200)
        self.assertEqual(self.request('todoist-authorize').status_code,401)
        self.login()
        with self.store.db() as db:db.execute('UPDATE sessions SET expires=?',(time.time()-1,))
        self.assertEqual(self.request('todoist-authorize').status_code,401)
        token=self.login()
        self.assertEqual(self.request('password','POST',json={'current_password':'synthetic-access-password',
            'new_password':'synthetic-new-password','confirm_password':'synthetic-new-password'},
            headers={'Origin':ORIGIN,'X-CSRF-Token':token}).status_code,200)
        self.assertEqual(self.request('todoist-authorize').status_code,401)


if __name__=='__main__':unittest.main()
