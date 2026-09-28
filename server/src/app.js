import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { timingSafeEqual } from 'node:crypto';
import { extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import { COMMAND_TYPES } from './store.js';
import { isValidDtc } from './dtc.js';

const PUBLIC_DIR = fileURLToPath(new URL('../public/', import.meta.url));
const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8' };
const MAX_BODY = 256 * 1024;
const DTC_KINDS = new Set(['stored', 'pending', 'permanent']);

class HttpError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

const safeEqual = (a, b) => {
  const x = Buffer.from(a);
  const y = Buffer.from(b);
  return x.length === y.length && timingSafeEqual(x, y);
};

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

function parseScan(body) {
  if (!Array.isArray(body.dtcs)) throw new HttpError(400, 'dtcs must be an array');
  const dtcs = body.dtcs.map((d) => ({ code: String(d?.code ?? '').toUpperCase(), kind: d?.kind ?? 'stored' }));
  for (const d of dtcs) {
    if (!isValidDtc(d.code)) throw new HttpError(400, `invalid DTC code: ${d.code}`);
    if (!DTC_KINDS.has(d.kind)) throw new HttpError(400, `invalid DTC kind: ${d.kind}`);
  }
  const vin = body.vin == null ? null : String(body.vin).toUpperCase();
  if (vin !== null && !/^[A-HJ-NPR-Z0-9]{11,17}$/.test(vin)) throw new HttpError(400, 'invalid vin');
  return { vin, milOn: Boolean(body.milOn), dtcs };
}

export function createApp({ config, store }) {
  const waiters = new Map();

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
        const list = waiters.get(deviceId);
        if (list) {
          const rest = list.filter((w) => w !== wake);
          if (rest.length) waiters.set(deviceId, rest);
          else waiters.delete(deviceId);
        }
        resolve();
      }, seconds * 1000);
      waiters.set(deviceId, [...(waiters.get(deviceId) ?? []), wake]);
      req.on('close', wake);
    });
  }

  function requireAdmin(req) {
    if (!safeEqual(bearer(req), config.adminToken)) throw new HttpError(401, 'unauthorized');
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

  const requireAdminDevice = (id) => {
    const device = store.getDevice(requireId(id));
    if (!device) throw new HttpError(404, 'device not found');
    return device;
  };

  const routes = [
    ['GET', /^\/api\/health$/, async () => ({ ok: true })],

    ['POST', /^\/api\/device\/scans$/, async (req) => {
      const device = requireDevice(req);
      const scan = parseScan(await readJson(req));
      const id = store.recordScan(device.id, scan);
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

    ['GET', /^\/api\/admin\/devices$/, async (req) => {
      requireAdmin(req);
      return { devices: store.listDevices() };
    }],

    ['POST', /^\/api\/admin\/devices$/, async (req) => {
      requireAdmin(req);
      const name = String((await readJson(req)).name ?? '').trim();
      if (!name || name.length > 100) throw new HttpError(400, 'name is required (max 100 chars)');
      return { status: 201, body: { device: store.createDevice(name) } };
    }],

    ['DELETE', /^\/api\/admin\/devices\/(\d+)$/, async (req, url, m) => {
      requireAdmin(req);
      if (!store.deleteDevice(requireId(m[1]))) throw new HttpError(404, 'device not found');
      return { status: 204 };
    }],

    ['GET', /^\/api\/admin\/devices\/(\d+)$/, async (req, url, m) => {
      requireAdmin(req);
      const device = requireAdminDevice(m[1]);
      return {
        device,
        scans: store.listScans(device.id, 20),
        commands: store.listCommands(device.id, 20),
      };
    }],

    ['POST', /^\/api\/admin\/devices\/(\d+)\/commands$/, async (req, url, m) => {
      requireAdmin(req);
      const device = requireAdminDevice(m[1]);
      const type = (await readJson(req)).type;
      if (!COMMAND_TYPES.has(type)) throw new HttpError(400, `type must be one of: ${[...COMMAND_TYPES].join(', ')}`);
      const { command, created } = store.createCommand(device.id, type);
      if (created) notify(device.id);
      return { status: created ? 201 : 200, body: { command } };
    }],

    ['DELETE', /^\/api\/admin\/devices\/(\d+)\/commands\/(\d+)$/, async (req, url, m) => {
      requireAdmin(req);
      const device = requireAdminDevice(m[1]);
      if (!store.cancelCommand(device.id, requireId(m[2]))) throw new HttpError(409, 'only pending commands can be cancelled');
      return { status: 204 };
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
