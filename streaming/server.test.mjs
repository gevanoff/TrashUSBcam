import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from './server.mjs';

const key = 'publisher-key-for-tests-32-characters';
async function fixture(t, options = {}) {
  const server = createServer({ publishKey: key, ...options });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => { server.close(resolve); server.closeAllConnections(); }));
  const base = `http://127.0.0.1:${server.address().port}`;
  async function api(path, method = 'GET', auth, body) {
    const response = await fetch(base + path, { method,
      headers: { ...(auth ? { Authorization: `Bearer ${auth}` } : {}), 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body) });
    return { code: response.status, data: await response.json() };
  }
  const create = async () => (await api('/api/sessions', 'POST', key)).data;
  return { api, create, base };
}

test('publisher key is required; viewing capability cannot read signaling before explicit claim', async t => {
  const { api, create } = await fixture(t);
  assert.equal((await api('/api/sessions', 'POST')).code, 401);
  const s = await create(); const path = `/api/sessions/${s.id}`;
  assert.equal((await api(path, 'GET', s.viewerToken)).code, 401);
  assert.equal((await api(path, 'GET', s.publisherToken)).code, 200);
});

test('one viewer, role isolation, SDP exchange, pause and immediate revocation', async t => {
  const { api, create } = await fixture(t);
  const s = await create(), path = `/api/sessions/${s.id}`;
  const join = await api(path + '/join', 'POST', s.viewerToken);
  assert.equal(join.code, 200);
  const viewer = join.data.accessToken;
  assert.equal((await api(path + '/join', 'POST', s.viewerToken)).code, 409);
  assert.equal((await api(path + '/host', 'PUT', viewer, { active: true })).code, 403);
  assert.equal((await api(path, 'DELETE', viewer)).code, 403);
  assert.equal((await api(path + '/answer', 'PUT', viewer, { answer: 'v=0\r\nanswer' })).code, 409);
  assert.equal((await api(path + '/host', 'PUT', s.publisherToken, { active: true, offer: 'v=0\r\noffer' })).code, 200);
  const state = (await api(path, 'GET', viewer)).data;
  assert.equal(state.offer, 'v=0\r\noffer'); assert.equal(state.active, true);
  assert.equal(state.publisherToken, undefined); assert.equal(state.viewerToken, undefined);
  assert.equal((await api(path + '/answer', 'PUT', s.publisherToken, { answer: 'v=0\r\nanswer' })).code, 403);
  assert.equal((await api(path + '/answer', 'PUT', viewer, { answer: 'v=0\r\nanswer' })).code, 200);
  assert.equal((await api(path + '/answer', 'PUT', viewer, { answer: 'v=0\r\nreplace' })).code, 409);
  assert.equal((await api(path, 'GET', s.publisherToken)).data.answer, 'v=0\r\nanswer');
  await api(path + '/host', 'PUT', s.publisherToken, { active: false });
  assert.equal((await api(path, 'GET', viewer)).data.active, false);
  assert.equal((await api(path, 'DELETE', s.publisherToken)).code, 200);
  assert.equal((await api(path, 'GET', viewer)).code, 404);
});

test('expiry and missing publisher heartbeat invalidate links, independent of viewer polling', async t => {
  let clock = 1000;
  const { api, create } = await fixture(t, { now: () => clock, ttl: 10000, idle: 1000 });
  const s = await create(), path = `/api/sessions/${s.id}`;
  clock += 1001;
  assert.equal((await api(path, 'GET', s.publisherToken)).code, 404);
  const next = await create();
  for (let n = 0; n < 9; n++) {
    clock += 900;
    assert.equal((await api(`/api/sessions/${next.id}/host`, 'PUT', next.publisherToken, { active: false })).code, 200);
  }
  clock += 2000;
  assert.equal((await api(`/api/sessions/${next.id}`, 'GET', next.publisherToken)).code, 404);
});

test('bounds, invalid SDP and TURN credentials', async t => {
  const { api, create } = await fixture(t, { maxSessions: 1, turnUrls: ['turn:relay.example:3478'], turnSecret: 'test-secret' });
  const s = await create();
  assert.equal((await api('/api/sessions', 'POST', key)).code, 429);
  assert.deepEqual(s.iceServers[0].urls, ['turn:relay.example:3478']);
  assert.ok(s.iceServers[0].credential); assert.ok(s.iceServers[0].username);
  assert.equal((await api(`/api/sessions/${s.id}/host`, 'PUT', s.publisherToken, { active: true, offer: 'bad' })).code, 400);
  assert.equal((await api(`/api/sessions/${s.id}/host`, 'PUT', s.publisherToken, { active: true, offer: 'x'.repeat(70000) })).code, 413);
});

test('viewer assets have no third party scripts and suppress referrers/caching', async t => {
  const { base } = await fixture(t);
  const response = await fetch(base + '/');
  assert.equal(response.status, 200);
  assert.equal(response.headers.get('referrer-policy'), 'no-referrer');
  assert.equal(response.headers.get('cache-control'), 'no-store');
  assert.match(response.headers.get('content-security-policy'), /frame-ancestors 'none'/);
  assert.match(await response.text(), /Watch live/);
});
