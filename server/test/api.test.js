import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { openDatabase } from '../src/db.js';
import { createStore } from '../src/store.js';
import { createApp, ensureAdmin } from '../src/app.js';

const ADMIN_PASSWORD = 'admin-password-123';
let server;
let base;
let store;
let admin;

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

const login = async (username, password) => {
  const res = await call('/api/auth/login', { method: 'POST', body: { username, password } });
  return res.json?.token;
};

async function newCustomer(username, password = 'customer-pass-1') {
  const res = await call('/api/admin/users', {
    method: 'POST', token: admin, body: { username, displayName: `Cliente ${username}`, password },
  });
  assert.equal(res.status, 201);
  return { id: res.json.user.id, token: await login(username, password) };
}

const upload = (key, dtcs = [{ code: 'P0300' }], extra = {}) =>
  call('/api/device/scans', { method: 'POST', token: key, body: { milOn: true, dtcs, ...extra } });

before(async () => {
  const config = {
    adminUsername: 'admin', adminPassword: ADMIN_PASSWORD, sessionTtlMs: 3_600_000,
    commandTtlMs: 600000, deliveryTimeoutMs: 60000, maxPollSeconds: 5,
  };
  store = createStore(openDatabase(':memory:'), config);
  await ensureAdmin(store, config);
  server = createApp({ config, store });
  await new Promise((r) => server.listen(0, r));
  base = `http://127.0.0.1:${server.address().port}`;
  admin = await login('admin', ADMIN_PASSWORD);
});

after(() => {
  server.closeAllConnections();
  server.close();
});

test('an email address can be the admin username', async () => {
  const mail = createStore(openDatabase(':memory:'), { sessionTtlMs: 1000, commandTtlMs: 1, deliveryTimeoutMs: 1 });
  assert.equal(await ensureAdmin(mail, { adminUsername: 'info@dvgarage.it', adminPassword: 'long-enough-1' }), true);
  assert.equal(mail.findUserCredentials('INFO@dvgarage.it').user.role, 'admin');
});

test('ensureAdmin needs a password only when no admin exists', async () => {
  const empty = createStore(openDatabase(':memory:'), { sessionTtlMs: 1, commandTtlMs: 1, deliveryTimeoutMs: 1 });
  await assert.rejects(ensureAdmin(empty, { adminUsername: 'admin', adminPassword: '' }), /ADMIN_PASSWORD/);
  assert.equal(await ensureAdmin(empty, { adminUsername: 'admin', adminPassword: 'long-enough-1' }), true);
  assert.equal(await ensureAdmin(empty, { adminUsername: 'admin', adminPassword: '' }), false);
});

test('login rejects wrong credentials and throttles repeated failures', async () => {
  assert.equal((await call('/api/auth/login', { method: 'POST', body: { username: 'admin', password: 'nope' } })).status, 401);
  assert.equal((await call('/api/auth/login', { method: 'POST', body: { username: 'ghost', password: 'nope' } })).status, 401);
  for (let i = 0; i < 5; i++) {
    await call('/api/auth/login', { method: 'POST', body: { username: 'victim', password: 'x' } });
  }
  const blocked = await call('/api/auth/login', { method: 'POST', body: { username: 'victim', password: 'x' } });
  assert.equal(blocked.status, 429);
  assert.ok(await login('admin', ADMIN_PASSWORD));
});

test('protected endpoints require a valid session', async () => {
  assert.equal((await call('/api/me')).status, 401);
  assert.equal((await call('/api/me', { token: 'bogus' })).status, 401);
  assert.equal((await call('/api/admin/users', { token: 'bogus' })).status, 401);
  const me = await call('/api/me', { token: admin });
  assert.equal(me.json.user.role, 'admin');
});

test('logout invalidates the session', async () => {
  const token = await login('admin', ADMIN_PASSWORD);
  assert.equal((await call('/api/auth/logout', { method: 'POST', token })).status, 204);
  assert.equal((await call('/api/me', { token })).status, 401);
});

test('admin creates customers; duplicates and weak input are rejected', async () => {
  const bad = await call('/api/admin/users', { method: 'POST', token: admin, body: { username: 'a b', password: 'long-enough-1' } });
  assert.equal(bad.status, 400);
  const weak = await call('/api/admin/users', { method: 'POST', token: admin, body: { username: 'weakling', password: 'short' } });
  assert.equal(weak.status, 400);
  await newCustomer('mario');
  const dup = await call('/api/admin/users', { method: 'POST', token: admin, body: { username: 'MARIO', password: 'long-enough-1' } });
  assert.equal(dup.status, 409);
});

test('customers cannot use admin endpoints', async () => {
  const { token } = await newCustomer('nonadmin');
  assert.equal((await call('/api/admin/users', { token })).status, 403);
  assert.equal((await call('/api/admin/devices', { token })).status, 403);
  assert.equal((await call('/api/admin/workshop/reads', { token })).status, 403);
});

test('customer registers a vehicle and sees only their own data', async () => {
  const anna = await newCustomer('anna');
  const luca = await newCustomer('luca');
  const created = await call('/api/me/devices', { method: 'POST', token: anna.token, body: { name: 'Panda' } });
  assert.equal(created.status, 201);
  const { id, key } = created.json.device;

  assert.equal((await upload(key, [{ code: 'P0420', kind: 'pending' }], { vin: 'ZFA16900001234567' })).status, 201);

  const detail = await call(`/api/devices/${id}`, { token: anna.token });
  assert.equal(detail.json.scans[0].dtcs[0].code, 'P0420');
  assert.equal(detail.json.device.owner, 'Cliente anna');

  assert.equal((await call(`/api/devices/${id}`, { token: luca.token })).status, 404);
  assert.deepEqual((await call('/api/me/devices', { token: luca.token })).json.devices, []);
  assert.equal((await call(`/api/devices/${id}`, { method: 'DELETE', token: luca.token })).status, 404);
  assert.equal((await call(`/api/devices/${id}`, { token: admin })).status, 200);
});

test('customers may request a rescan but not a clear', async () => {
  const { token } = await newCustomer('rescanner');
  const { id } = (await call('/api/me/devices', { method: 'POST', token, body: { name: 'Golf' } })).json.device;
  assert.equal((await call(`/api/devices/${id}/commands`, { method: 'POST', token, body: { type: 'clear_dtc' } })).status, 403);
  assert.equal((await call(`/api/devices/${id}/commands`, { method: 'POST', token, body: { type: 'rescan' } })).status, 201);
  assert.equal((await call(`/api/devices/${id}/commands`, { method: 'POST', token: admin, body: { type: 'clear_dtc' } })).status, 201);
});

test('customers are limited to ten vehicles', async () => {
  const { token } = await newCustomer('collector');
  for (let i = 0; i < 10; i++) {
    assert.equal((await call('/api/me/devices', { method: 'POST', token, body: { name: `Auto ${i}` } })).status, 201);
  }
  assert.equal((await call('/api/me/devices', { method: 'POST', token, body: { name: 'Extra' } })).status, 409);
});

test('device endpoints reject unknown keys and user sessions', async () => {
  assert.equal((await call('/api/device/commands/next', { token: 'nope' })).status, 401);
  assert.equal((await call('/api/device/commands/next', { token: admin })).status, 401);
});

test('scan upload validates input', async () => {
  const { token } = await newCustomer('validator');
  const { key } = (await call('/api/me/devices', { method: 'POST', token, body: { name: 'Fiesta' } })).json.device;
  assert.equal((await upload(key, [{ code: 'XYZ' }])).status, 400);
  assert.equal((await upload(key, [{ code: 'P0300', kind: 'weird' }])).status, 400);
  assert.equal((await upload(key, [{ code: 'p0300' }], { vin: 'bad vin' })).status, 400);
  assert.equal((await upload(key, [{ code: 'p0300' }])).status, 201);
});

test('clear command flows from admin to device and back', async () => {
  const { token } = await newCustomer('flow');
  const { id, key } = (await call('/api/me/devices', { method: 'POST', token, body: { name: 'Clio' } })).json.device;

  const created = await call(`/api/devices/${id}/commands`, { method: 'POST', token: admin, body: { type: 'clear_dtc' } });
  assert.equal(created.status, 201);
  const duplicate = await call(`/api/devices/${id}/commands`, { method: 'POST', token: admin, body: { type: 'clear_dtc' } });
  assert.equal(duplicate.status, 200);
  assert.equal(duplicate.json.command.id, created.json.command.id);

  const next = await call('/api/device/commands/next', { token: key });
  assert.equal(next.json.command.status, 'delivered');
  assert.equal((await call('/api/device/commands/next', { token: key })).status, 204);

  const result = await call(`/api/device/commands/${next.json.command.id}/result`, {
    method: 'POST', token: key, body: { ok: true, message: 'cleared' },
  });
  assert.equal(result.status, 200);
  const again = await call(`/api/device/commands/${next.json.command.id}/result`, { method: 'POST', token: key, body: { ok: true } });
  assert.equal(again.status, 409);

  const detail = await call(`/api/devices/${id}`, { token });
  assert.equal(detail.json.commands[0].status, 'done');
});

test('long poll wakes up when a command is queued', async () => {
  const { token } = await newCustomer('poller');
  const { id, key } = (await call('/api/me/devices', { method: 'POST', token, body: { name: 'Polo' } })).json.device;
  const started = Date.now();
  const polling = call('/api/device/commands/next?wait=5', { token: key });
  await new Promise((r) => setTimeout(r, 200));
  await call(`/api/devices/${id}/commands`, { method: 'POST', token, body: { type: 'rescan' } });
  const res = await polling;
  assert.equal(res.json.command.type, 'rescan');
  assert.ok(Date.now() - started < 3000);
});

test('pending commands can be cancelled', async () => {
  const { token } = await newCustomer('canceller');
  const { id, key } = (await call('/api/me/devices', { method: 'POST', token, body: { name: 'Ibiza' } })).json.device;
  const { json } = await call(`/api/devices/${id}/commands`, { method: 'POST', token, body: { type: 'rescan' } });
  assert.equal((await call(`/api/devices/${id}/commands/${json.command.id}`, { method: 'DELETE', token })).status, 204);
  assert.equal((await call('/api/device/commands/next', { token: key })).status, 204);
});

test('password change keeps the user signed in and revokes old sessions', async () => {
  const user = await newCustomer('changer', 'old-password-1');
  const wrong = await call('/api/me/password', { method: 'POST', token: user.token, body: { currentPassword: 'x', newPassword: 'new-password-1' } });
  assert.equal(wrong.status, 403);
  const ok = await call('/api/me/password', { method: 'POST', token: user.token, body: { currentPassword: 'old-password-1', newPassword: 'new-password-1' } });
  assert.equal(ok.status, 200);
  assert.equal((await call('/api/me', { token: user.token })).status, 401);
  assert.equal((await call('/api/me', { token: ok.json.token })).status, 200);
  assert.equal(await login('changer', 'old-password-1'), undefined);
  assert.ok(await login('changer', 'new-password-1'));
});

test('disabling a user revokes access; admin cannot disable or delete themselves', async () => {
  const user = await newCustomer('suspended');
  const me = (await call('/api/me', { token: admin })).json.user;
  assert.equal((await call(`/api/admin/users/${me.id}`, { method: 'PATCH', token: admin, body: { disabled: true } })).status, 409);
  assert.equal((await call(`/api/admin/users/${me.id}`, { method: 'DELETE', token: admin })).status, 409);

  await call(`/api/admin/users/${user.id}`, { method: 'PATCH', token: admin, body: { disabled: true } });
  assert.equal((await call('/api/me', { token: user.token })).status, 401);
  assert.equal(await login('suspended', 'customer-pass-1'), undefined);

  await call(`/api/admin/users/${user.id}`, { method: 'PATCH', token: admin, body: { disabled: false, password: 'reset-password-1' } });
  assert.ok(await login('suspended', 'reset-password-1'));
});

test('deleting a customer removes their vehicles', async () => {
  const user = await newCustomer('leaver');
  const { key } = (await call('/api/me/devices', { method: 'POST', token: user.token, body: { name: 'Uno' } })).json.device;
  assert.equal((await call(`/api/admin/users/${user.id}`, { method: 'DELETE', token: admin })).status, 204);
  assert.equal((await call('/api/device/commands/next', { token: key })).status, 401);
});

test('admin can register a vehicle for a customer', async () => {
  const user = await newCustomer('assigned');
  const res = await call('/api/admin/devices', { method: 'POST', token: admin, body: { name: 'Punto', userId: user.id } });
  assert.equal(res.status, 201);
  const mine = await call('/api/me/devices', { token: user.token });
  assert.equal(mine.json.devices[0].name, 'Punto');
});

test('workshop reads can be stored without a customer or linked to one', async () => {
  const user = await newCustomer('walkin');
  const free = await call('/api/admin/workshop/reads', {
    method: 'POST', token: admin,
    body: { label: 'Passante', vin: 'ZFA16900001234567', milOn: true, dtcs: [{ code: 'P0171' }], cleared: false },
  });
  assert.equal(free.status, 201);
  const linked = await call('/api/admin/workshop/reads', {
    method: 'POST', token: admin, body: { label: 'Tagliando', milOn: false, dtcs: [], cleared: true, customerId: user.id },
  });
  assert.equal(linked.status, 201);

  const { reads } = (await call('/api/admin/workshop/reads', { token: admin })).json;
  assert.equal(reads[0].customer, 'Cliente walkin');
  assert.equal(reads[0].cleared, true);
  assert.equal(reads[1].customer, null);
  assert.equal(reads[1].dtcs[0].code, 'P0171');
  assert.equal((await call(`/api/admin/workshop/reads/${reads[0].id}`, { method: 'DELETE', token: admin })).status, 204);

  const bad = await call('/api/admin/workshop/reads', { method: 'POST', token: admin, body: { dtcs: [{ code: 'nope' }] } });
  assert.equal(bad.status, 400);
  const ghost = await call('/api/admin/workshop/reads', { method: 'POST', token: admin, body: { dtcs: [], customerId: 99999 } });
  assert.equal(ghost.status, 404);
});

test('linking a phone to an existing vehicle rotates its key', async () => {
  const owner = await newCustomer('rotator');
  const other = await newCustomer('bystander');
  const { id, key } = (await call('/api/me/devices', { method: 'POST', token: owner.token, body: { name: 'Mito' } })).json.device;
  assert.equal((await call(`/api/devices/${id}/key`, { method: 'POST', token: other.token })).status, 404);
  const rotated = await call(`/api/devices/${id}/key`, { method: 'POST', token: owner.token });
  assert.equal(rotated.status, 200);
  assert.notEqual(rotated.json.device.key, key);
  assert.equal((await call('/api/device/commands/next', { token: key })).status, 401);
  assert.equal((await call('/api/device/commands/next', { token: rotated.json.device.key })).status, 204);
});
