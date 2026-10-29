const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const vm = require('node:vm');
const { test } = require('node:test');

test('挂载的分享 Worker 不接管 todoist 或站点根路径，失败也返回挂载首页', async () => {
  const handlers = {};
  const context = vm.createContext({ URL, Response,
    self: { registration: { scope: 'https://tools.example/transfer/' },
      addEventListener: (name, callback) => { handlers[name] = callback } } });
  vm.runInContext(readFileSync(new URL('../transfer/static/sw.js', `file://${__filename}`), 'utf8'), context);
  for (const path of ['/api/todoist/commands', '/share-target', '/Todoist', '/transfer/api/messages']) {
    handlers.fetch({ request: { url: `https://tools.example${path}`, method: 'POST' },
      respondWith: () => assert.fail(`Worker intercepted ${path}`) });
  }
  let result;
  handlers.fetch({ request: { url: 'https://tools.example/transfer/share-target', method: 'POST',
    formData: async () => { throw new Error('synthetic failure') } }, respondWith: promise => { result = promise } });
  const response = await result;
  assert.equal(response.headers.get('location'), 'https://tools.example/transfer/?share_error=1');
});
