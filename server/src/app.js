import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import { COMMAND_TYPES, ROLES } from './store.js';
import { MAX_PASSWORD_LENGTH, MIN_PASSWORD_LENGTH, hashPassword, verifyPassword } from './password.js';
import { isValidDtc } from './dtc.js';

const PUBLIC_DIR = fileURLToPath(new URL('../public/', import.meta.url));
const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8' };
const MAX_BODY = 256 * 1024;
const DTC_KINDS = new Set(['stored', 'pending', 'permanent']);
const USERNAME_PATTERN = /^[a-z0-9._@+-]{3,64}$/i;
const MAX_DEVICES_PER_CUSTOMER = 10;
const LOGIN_MAX_FAILURES = 5;
const LOGIN_WINDOW_MS = 15 * 60_000;
const DUMMY_HASH = `scrypt$${'00'.repeat(16)}$${'00'.repeat(64)}`;

class HttpError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

async function readJson(req) {
  const chunks = [];
  let size = 0;
  for await (const chunk of req) {
    size += chunk.length;
    if (size > MAX_BODY) throw new HttpError(413, 'body too large');
    chunks.push(chunk);
  }
  if (size === 0) return {};
  try {
    return JSON.parse(Buffer.concat(chunks).toString('utf8'));
  } catch {
    throw new HttpError(400, 'invalid JSON');
  }
}

function bearer(req) {
  const header = req.headers.authorization ?? '';
  return header.startsWith('Bearer ') ? header.slice(7).trim() : '';
}

function parseDtcs(list) {
  if (!Array.isArray(list)) throw new HttpError(400, 'dtcs must be an array');
  if (list.length > 200) throw new HttpError(400, 'too many dtcs');
  const dtcs = list.map((d) => ({ code: String(d?.code ?? '').toUpperCase(), kind: d?.kind ?? 'stored' }));
  for (const d of dtcs) {
    if (!isValidDtc(d.code)) throw new HttpError(400, `invalid DTC code: ${d.code}`);
    if (!DTC_KINDS.has(d.kind)) throw new HttpError(400, `invalid DTC kind: ${d.kind}`);
  }
  return dtcs;
}

function parseVin(value) {
  if (value == null || value === '') return null;
  const vin = String(value).toUpperCase();
  if (!/^[A-HJ-NPR-Z0-9]{11,17}$/.test(vin)) throw new HttpError(400, 'invalid vin');
  return vin;
}

function parseScan(body) {
  return { vin: parseVin(body.vin), milOn: Boolean(body.milOn), dtcs: parseDtcs(body.dtcs) };
}

function parseName(value, field = 'name') {
  const name = String(value ?? '').trim();
  if (!name || name.length > 100) throw new HttpError(400, `${field} is required (max 100 chars)`);
  return name;
}

function parsePassword(value) {
  const password = String(value ?? '');
  if (password.length < MIN_PASSWORD_LENGTH || password.length > MAX_PASSWORD_LENGTH) {
    throw new HttpError(400, `password must be ${MIN_PASSWORD_LENGTH}-${MAX_PASSWORD_LENGTH} characters`);
  }
  return password;
}

function parseUsername(value) {
  const username = String(value ?? '').trim();
  if (!USERNAME_PATTERN.test(username)) {
    throw new HttpError(400, 'username must be 3-64 characters: letters, digits, . _ - @ +');
  }
  return username;
}

export async function ensureAdmin(store, { adminUsername, adminPassword }) {
  if (store.countUsers('admin') > 0) return false;
  if (String(adminPassword).length < MIN_PASSWORD_LENGTH) {
    throw new Error(`No admin exists: set ADMIN_PASSWORD (at least ${MIN_PASSWORD_LENGTH} characters) to create one`);
  }
  store.createUser({
    username: parseUsername(adminUsername),
    displayName: 'Administrator',
    passwordHash: await hashPassword(adminPassword),
    role: 'admin',
  });
  return true;
}

export function createApp({ config, store }) {
  const waiters = new Map();
  const loginFailures = new Map();

  function notify(deviceId) {
    const list = waiters.get(deviceId);
    if (!list) return;
    waiters.delete(deviceId);
    for (const wake of list) wake();
  }

  function waitForCommand(deviceId, seconds, req) {
    return new Promise((resolve) => {
      let timer;
      const wake = () => {
        clearTimeout(timer);
        resolve();
      };
      timer = setTimeout(() => {
        const rest = (waiters.get(deviceId) ?? []).filter((w) => w !== wake);
        if (rest.length) waiters.set(deviceId, rest);
        else waiters.delete(deviceId);
        resolve();
      }, seconds * 1000);
      waiters.set(deviceId, [...(waiters.get(deviceId) ?? []), wake]);
      req.on('close', wake);
    });
  }

  function throttleKey(req, username) {
    return `${req.socket.remoteAddress}|${username.toLowerCase()}`;
  }

  function assertNotThrottled(key) {
    const entry = loginFailures.get(key);
    if (!entry) return;
    if (Date.now() - entry.since > LOGIN_WINDOW_MS) loginFailures.delete(key);
    else if (entry.count >= LOGIN_MAX_FAILURES) throw new HttpError(429, 'too many failed attempts, try again later');
  }

  function recordFailure(key) {
    const entry = loginFailures.get(key);
    if (!entry || Date.now() - entry.since > LOGIN_WINDOW_MS) loginFailures.set(key, { count: 1, since: Date.now() });
    else entry.count += 1;
  }

  function requireUser(req) {
    const token = bearer(req);
    const user = token ? store.findSessionUser(token) : null;
    if (!user) throw new HttpError(401, 'unauthorized');
    return user;
  }

  function requireAdmin(req) {
    const user = requireUser(req);
    if (user.role !== 'admin') throw new HttpError(403, 'admin only');
    return user;
  }

  function requireDevice(req) {
    const key = bearer(req);
    const device = key ? store.findDeviceByKey(key) : null;
    if (!device) throw new HttpError(401, 'unauthorized');
    return device;
  }

  const requireId = (value) => {
    const id = Number(value);
    if (!Number.isInteger(id) || id <= 0) throw new HttpError(400, 'invalid id');
    return id;
  };

  function accessibleDevice(user, rawId) {
    const device = store.getDevice(requireId(rawId));
    if (!device || (user.role !== 'admin' && device.userId !== user.id)) throw new HttpError(404, 'device not found');
    return device;
  }

  function existingUser(rawId) {
    const user = store.getUser(requireId(rawId));
    if (!user) throw new HttpError(404, 'user not found');
    return user;
  }

  async function createUserFrom(body, role) {
    const username = parseUsername(body.username);
    const displayName = parseName(body.displayName ?? username, 'displayName');
    const passwordHash = await hashPassword(parsePassword(body.password));
    try {
      return store.createUser({ username, displayName, passwordHash, role });
    } catch (err) {
      if (String(err.message).includes('UNIQUE')) throw new HttpError(409, 'username already taken');
      throw err;
    }
  }

  const routes = [
    ['GET', /^\/api\/health$/, async () => ({ ok: true })],

    ['POST', /^\/api\/auth\/login$/, async (req) => {
      const body = await readJson(req);
      const username = String(body.username ?? '').trim();
      const password = String(body.password ?? '');
      const key = throttleKey(req, username);
      assertNotThrottled(key);
      const credentials = store.findUserCredentials(username);
      const valid = await verifyPassword(password, credentials?.passwordHash ?? DUMMY_HASH);
      if (!credentials || !valid || credentials.user.disabled) {
        recordFailure(key);
        throw new HttpError(401, 'invalid username or password');
      }
      loginFailures.delete(key);
      return { token: store.createSession(credentials.user.id), user: credentials.user };
    }],

    ['POST', /^\/api\/auth\/logout$/, async (req) => {
      requireUser(req);
      store.deleteSession(bearer(req));
      return { status: 204 };
    }],

    ['GET', /^\/api\/me$/, async (req) => ({ user: requireUser(req) })],

    ['POST', /^\/api\/me\/password$/, async (req) => {
      const user = requireUser(req);
      const body = await readJson(req);
      const credentials = store.findUserCredentials(user.username);
      if (!(await verifyPassword(String(body.currentPassword ?? ''), credentials.passwordHash))) {
        throw new HttpError(403, 'current password is wrong');
      }
      store.setPassword(user.id, await hashPassword(parsePassword(body.newPassword)));
      store.deleteUserSessions(user.id);
      return { token: store.createSession(user.id) };
    }],

    ['GET', /^\/api\/me\/devices$/, async (req) => {
      const user = requireUser(req);
      return { devices: store.listDevices({ userId: user.id }) };
    }],

    ['POST', /^\/api\/me\/devices$/, async (req) => {
      const user = requireUser(req);
      const name = parseName((await readJson(req)).name);
      if (user.role === 'customer' && store.countDevices(user.id) >= MAX_DEVICES_PER_CUSTOMER) {
        throw new HttpError(409, `at most ${MAX_DEVICES_PER_CUSTOMER} vehicles per account`);
      }
      return { status: 201, body: { device: store.createDevice(name, user.id) } };
    }],

    ['GET', /^\/api\/devices\/(\d+)$/, async (req, url, m) => {
      const device = accessibleDevice(requireUser(req), m[1]);
      return { device, scans: store.listScans(device.id, 20), commands: store.listCommands(device.id, 20) };
    }],

    ['DELETE', /^\/api\/devices\/(\d+)$/, async (req, url, m) => {
      const device = accessibleDevice(requireUser(req), m[1]);
      store.deleteDevice(device.id);
      return { status: 204 };
    }],

    ['POST', /^\/api\/devices\/(\d+)\/key$/, async (req, url, m) => {
      const device = accessibleDevice(requireUser(req), m[1]);
      return { device: { id: device.id, name: device.name, key: store.rotateDeviceKey(device.id) } };
    }],

    ['POST', /^\/api\/devices\/(\d+)\/commands$/, async (req, url, m) => {
      const user = requireUser(req);
      const device = accessibleDevice(user, m[1]);
      const type = (await readJson(req)).type;
      if (!COMMAND_TYPES.has(type)) throw new HttpError(400, `type must be one of: ${[...COMMAND_TYPES].join(', ')}`);
      if (type === 'clear_dtc' && user.role !== 'admin') throw new HttpError(403, 'only the workshop can clear codes');
      const { command, created } = store.createCommand(device.id, type);
      if (created) notify(device.id);
      return { status: created ? 201 : 200, body: { command } };
    }],

    ['DELETE', /^\/api\/devices\/(\d+)\/commands\/(\d+)$/, async (req, url, m) => {
      const device = accessibleDevice(requireUser(req), m[1]);
      if (!store.cancelCommand(device.id, requireId(m[2]))) throw new HttpError(409, 'only pending commands can be cancelled');
      return { status: 204 };
    }],

    ['GET', /^\/api\/admin\/devices$/, async (req) => {
      requireAdmin(req);
      return { devices: store.listDevices() };
    }],

    ['POST', /^\/api\/admin\/devices$/, async (req) => {
      requireAdmin(req);
      const body = await readJson(req);
      const userId = body.userId == null ? null : existingUser(body.userId).id;
      return { status: 201, body: { device: store.createDevice(parseName(body.name), userId) } };
    }],

    ['GET', /^\/api\/admin\/users$/, async (req, url) => {
      requireAdmin(req);
      const role = url.searchParams.get('role');
      if (role && !ROLES.has(role)) throw new HttpError(400, 'invalid role');
      return { users: store.listUsers(role || undefined) };
    }],

    ['POST', /^\/api\/admin\/users$/, async (req) => {
      requireAdmin(req);
      const body = await readJson(req);
      const role = body.role ?? 'customer';
      if (!ROLES.has(role)) throw new HttpError(400, 'invalid role');
      return { status: 201, body: { user: await createUserFrom(body, role) } };
    }],

    ['PATCH', /^\/api\/admin\/users\/(\d+)$/, async (req, url, m) => {
      const admin = requireAdmin(req);
      const target = existingUser(m[1]);
      const body = await readJson(req);
      if (target.id === admin.id && body.disabled === true) throw new HttpError(409, 'you cannot disable yourself');
      const user = store.updateUser(target.id, {
        displayName: body.displayName === undefined ? undefined : parseName(body.displayName, 'displayName'),
        disabled: body.disabled === undefined ? undefined : Boolean(body.disabled),
        passwordHash: body.password === undefined ? undefined : await hashPassword(parsePassword(body.password)),
      });
      return { user };
    }],

    ['DELETE', /^\/api\/admin\/users\/(\d+)$/, async (req, url, m) => {
      const admin = requireAdmin(req);
      const target = existingUser(m[1]);
      if (target.id === admin.id) throw new HttpError(409, 'you cannot delete yourself');
      store.deleteUser(target.id);
      return { status: 204 };
    }],

    ['GET', /^\/api\/admin\/workshop\/reads$/, async (req) => {
      requireAdmin(req);
      return { reads: store.listWorkshopReads(50) };
    }],

    ['POST', /^\/api\/admin\/workshop\/reads$/, async (req) => {
      const admin = requireAdmin(req);
      const body = await readJson(req);
      const scan = parseScan(body);
      const customerId = body.customerId == null ? null : existingUser(body.customerId).id;
      const id = store.recordWorkshopRead(admin.id, {
        ...scan,
        customerId,
        label: parseName(body.label ?? 'Lettura officina', 'label'),
        cleared: Boolean(body.cleared),
      });
      return { status: 201, body: { id } };
    }],

    ['DELETE', /^\/api\/admin\/workshop\/reads\/(\d+)$/, async (req, url, m) => {
      requireAdmin(req);
      if (!store.deleteWorkshopRead(requireId(m[1]))) throw new HttpError(404, 'read not found');
      return { status: 204 };
    }],

    ['POST', /^\/api\/device\/scans$/, async (req) => {
      const device = requireDevice(req);
      const id = store.recordScan(device.id, parseScan(await readJson(req)));
      return { status: 201, body: { id } };
    }],

    ['GET', /^\/api\/device\/commands\/next$/, async (req, url) => {
      const device = requireDevice(req);
      store.touchDevice(device.id);
      const wait = Math.min(Math.max(Number(url.searchParams.get('wait') ?? 0) || 0, 0), config.maxPollSeconds);
      let command = store.claimNextCommand(device.id);
      if (!command && wait > 0) {
        await waitForCommand(device.id, wait, req);
        command = store.claimNextCommand(device.id);
      }
      return command ? { command } : { status: 204 };
    }],

    ['POST', /^\/api\/device\/commands\/(\d+)\/result$/, async (req, url, m) => {
      const device = requireDevice(req);
      const body = await readJson(req);
      if (typeof body.ok !== 'boolean') throw new HttpError(400, 'ok must be a boolean');
      const message = body.message == null ? null : String(body.message).slice(0, 500);
      if (!store.completeCommand(device.id, requireId(m[1]), { ok: body.ok, message })) {
        throw new HttpError(409, 'command is not awaiting a result');
      }
      return { ok: true };
    }],
  ];

  async function serveStatic(pathname, res) {
    const relative = pathname === '/' ? 'index.html' : normalize(pathname).replace(/^([/\\])+/, '');
    if (relative.includes('..')) throw new HttpError(404, 'not found');
    try {
      const data = await readFile(join(PUBLIC_DIR, relative));
      res.writeHead(200, { 'content-type': MIME[extname(relative)] ?? 'application/octet-stream' });
      res.end(data);
    } catch {
      throw new HttpError(404, 'not found');
    }
  }

  const send = (res, status, body) => {
    if (body === undefined) {
      res.writeHead(status).end();
      return;
    }
    res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' });
    res.end(JSON.stringify(body));
  };

  async function handle(req, res) {
    const url = new URL(req.url, 'http://localhost');
    if (!url.pathname.startsWith('/api/')) {
      if (req.method !== 'GET') throw new HttpError(405, 'method not allowed');
      return serveStatic(url.pathname, res);
    }
    let pathMatched = false;
    for (const [method, pattern, handler] of routes) {
      const m = pattern.exec(url.pathname);
      if (!m) continue;
      pathMatched = true;
      if (method !== req.method) continue;
      const result = await handler(req, url, m);
      if (result.status !== undefined) return send(res, result.status, result.body);
      return send(res, 200, result);
    }
    throw new HttpError(pathMatched ? 405 : 404, pathMatched ? 'method not allowed' : 'not found');
  }

  return createServer(async (req, res) => {
    try {
      await handle(req, res);
    } catch (err) {
      if (res.headersSent) return res.end();
      if (err instanceof HttpError) return send(res, err.status, { error: err.message });
      console.error(err);
      send(res, 500, { error: 'internal error' });
    }
  });
}
