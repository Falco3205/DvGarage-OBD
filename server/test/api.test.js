import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { openDatabase } from '../src/db.js';
import { createStore } from '../src/store.js';
import { createApp } from '../src/app.js';

const ADMIN = 'admin-token-1234567890';
let server;
let base;
let device;

const call = async (path, { method = 'GET', token, body } = {}) => {
  const res = await fetch(base + path, {
    method,
    headers: {
      ...(token ? { authorization: `Bearer ${token}` } : {}),
      ...(body ? { 'content-type': 'application/json' } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  return { status: res.status, json: text ? JSON.parse(text) : null };
};

before(async () => {
  const config = { adminToken: ADMIN, commandTtlMs: 600000, deliveryTimeoutMs: 60000, maxPollSeconds: 5 };
  server = createApp({ config, store: createStore(openDatabase(':memory:'), config) });
  await new Promise((r) => server.listen(0, r));
  base = `http://127.0.0.1:${server.address().port}`;
  const created = await call('/api/admin/devices', { method: 'POST', token: ADMIN, body: { name: 'Fiat Panda' } });
  device = created.json.device;
});

after(() => {
  server.closeAllConnections();
  server.close();
});

test('admin endpoints reject missing or wrong token', async () => {
  assert.equal((await call('/api/admin/devices')).status, 401);
  assert.equal((await call('/api/admin/devices', { token: 'wrong' })).status, 401);
});

test('device endpoints reject unknown keys', async () => {
  assert.equal((await call('/api/device/commands/next', { token: 'nope' })).status, 401);
});

test('scan upload validates and stores DTCs', async () => {
  const bad = await call('/api/device/scans', { method: 'POST', token: device.key, body: { dtcs: [{ code: 'XYZ' }] } });
  assert.equal(bad.status, 400);

  const ok = await call('/api/device/scans', {
    method: 'POST',
    token: device.key,
    body: { vin: 'ZFA16900001234567', milOn: true, dtcs: [{ code: 'p0300', kind: 'stored' }, { code: 'P0420', kind: 'pending' }] },
  });
  assert.equal(ok.status, 201);

  const detail = await call(`/api/admin/devices/${device.id}`, { token: ADMIN });
  assert.equal(detail.json.device.vin, 'ZFA16900001234567');
  assert.equal(detail.json.device.milOn, true);
  const [scan] = detail.json.scans;
  assert.deepEqual(scan.dtcs.map((d) => `${d.code}:${d.kind}`), ['P0420:pending', 'P0300:stored']);
  assert.match(scan.dtcs[1].description, /misfire/i);
});

test('clear command flows from admin to device and back', async () => {
  const created = await call(`/api/admin/devices/${device.id}/commands`, { method: 'POST', token: ADMIN, body: { type: 'clear_dtc' } });
  assert.equal(created.status, 201);

  const duplicate = await call(`/api/admin/devices/${device.id}/commands`, { method: 'POST', token: ADMIN, body: { type: 'clear_dtc' } });
  assert.equal(duplicate.status, 200);
  assert.equal(duplicate.json.command.id, created.json.command.id);

  const next = await call('/api/device/commands/next', { token: device.key });
  assert.equal(next.json.command.type, 'clear_dtc');
  assert.equal(next.json.command.status, 'delivered');

  assert.equal((await call('/api/device/commands/next', { token: device.key })).status, 204);

  const result = await call(`/api/device/commands/${next.json.command.id}/result`, {
    method: 'POST', token: device.key, body: { ok: true, message: 'cleared' },
  });
  assert.equal(result.status, 200);

  const again = await call(`/api/device/commands/${next.json.command.id}/result`, {
    method: 'POST', token: device.key, body: { ok: true },
  });
  assert.equal(again.status, 409);

  const detail = await call(`/api/admin/devices/${device.id}`, { token: ADMIN });
  assert.equal(detail.json.commands[0].status, 'done');
});

test('long poll wakes up when a command is queued', async () => {
  const started = Date.now();
  const polling = call('/api/device/commands/next?wait=5', { token: device.key });
  await new Promise((r) => setTimeout(r, 200));
  await call(`/api/admin/devices/${device.id}/commands`, { method: 'POST', token: ADMIN, body: { type: 'rescan' } });
  const res = await polling;
  assert.equal(res.json.command.type, 'rescan');
  assert.ok(Date.now() - started < 3000);
});

test('pending commands can be cancelled', async () => {
  const other = (await call('/api/admin/devices', { method: 'POST', token: ADMIN, body: { name: 'Second' } })).json.device;
  const { json } = await call(`/api/admin/devices/${other.id}/commands`, { method: 'POST', token: ADMIN, body: { type: 'clear_dtc' } });
  const del = await call(`/api/admin/devices/${other.id}/commands/${json.command.id}`, { method: 'DELETE', token: ADMIN });
  assert.equal(del.status, 204);
  assert.equal((await call('/api/device/commands/next', { token: other.key })).status, 204);
});

test('unknown command types are rejected', async () => {
  const res = await call(`/api/admin/devices/${device.id}/commands`, { method: 'POST', token: ADMIN, body: { type: 'flash_ecu' } });
  assert.equal(res.status, 400);
});
