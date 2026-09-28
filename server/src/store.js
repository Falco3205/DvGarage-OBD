import { createHash, randomBytes } from 'node:crypto';
import { describeDtc } from './dtc.js';

export const COMMAND_TYPES = new Set(['clear_dtc', 'rescan']);

const hashKey = (key) => createHash('sha256').update(key).digest('hex');

export function createStore(db, { commandTtlMs, deliveryTimeoutMs }, now = () => Date.now()) {
  const q = (sql) => db.prepare(sql);

  const deviceView = (row) => ({
    id: row.id,
    name: row.name,
    vin: row.vin,
    milOn: row.mil_on === null ? null : Boolean(row.mil_on),
    createdAt: row.created_at,
    lastSeenAt: row.last_seen_at,
  });

  const commandView = (row) => ({
    id: row.id,
    deviceId: row.device_id,
    type: row.type,
    status: row.status,
    createdAt: row.created_at,
    deliveredAt: row.delivered_at,
    completedAt: row.completed_at,
    message: row.message,
  });

  function expireCommands() {
    const t = now();
    q(`UPDATE commands SET status='pending', delivered_at=NULL
       WHERE status='delivered' AND delivered_at < ?`).run(t - deliveryTimeoutMs);
    q(`UPDATE commands SET status='expired', completed_at=?, message='not delivered in time'
       WHERE status='pending' AND created_at < ?`).run(t, t - commandTtlMs);
  }

  function inTransaction(fn) {
    db.exec('BEGIN IMMEDIATE');
    try {
      const result = fn();
      db.exec('COMMIT');
      return result;
    } catch (err) {
      db.exec('ROLLBACK');
      throw err;
    }
  }

  return {
    createDevice(name) {
      const key = randomBytes(32).toString('hex');
      const info = q('INSERT INTO devices (name, key_hash, created_at) VALUES (?, ?, ?)')
        .run(name, hashKey(key), now());
      return { id: Number(info.lastInsertRowid), name, key };
    },

    findDeviceByKey(key) {
      const row = q('SELECT * FROM devices WHERE key_hash = ?').get(hashKey(key));
      return row ? deviceView(row) : null;
    },

    listDevices() {
      return q('SELECT * FROM devices ORDER BY id').all().map(deviceView);
    },

    getDevice(id) {
      const row = q('SELECT * FROM devices WHERE id = ?').get(id);
      return row ? deviceView(row) : null;
    },

    deleteDevice(id) {
      return q('DELETE FROM devices WHERE id = ?').run(id).changes > 0;
    },

    touchDevice(id) {
      q('UPDATE devices SET last_seen_at = ? WHERE id = ?').run(now(), id);
    },

    recordScan(deviceId, { vin, milOn, dtcs }) {
      return inTransaction(() => {
        const info = q('INSERT INTO scans (device_id, created_at, vin, mil_on) VALUES (?, ?, ?, ?)')
          .run(deviceId, now(), vin ?? null, milOn ? 1 : 0);
        const scanId = Number(info.lastInsertRowid);
        const insert = q('INSERT OR IGNORE INTO scan_dtcs (scan_id, code, kind) VALUES (?, ?, ?)');
        for (const { code, kind } of dtcs) insert.run(scanId, code, kind);
        q('UPDATE devices SET vin = COALESCE(?, vin), mil_on = ?, last_seen_at = ? WHERE id = ?')
          .run(vin ?? null, milOn ? 1 : 0, now(), deviceId);
        return scanId;
      });
    },

    listScans(deviceId, limit = 20) {
      const scans = q('SELECT * FROM scans WHERE device_id = ? ORDER BY id DESC LIMIT ?')
        .all(deviceId, limit);
      const dtcQuery = q('SELECT code, kind FROM scan_dtcs WHERE scan_id = ? ORDER BY kind, code');
      return scans.map((s) => ({
        id: s.id,
        createdAt: s.created_at,
        vin: s.vin,
        milOn: Boolean(s.mil_on),
        dtcs: dtcQuery.all(s.id).map((d) => ({ ...describeDtc(d.code), kind: d.kind })),
      }));
    },

    createCommand(deviceId, type) {
      expireCommands();
      const existing = q(`SELECT * FROM commands WHERE device_id = ? AND type = ?
                          AND status IN ('pending','delivered')`).get(deviceId, type);
      if (existing) return { command: commandView(existing), created: false };
      const info = q(`INSERT INTO commands (device_id, type, status, created_at)
                      VALUES (?, ?, 'pending', ?)`).run(deviceId, type, now());
      const row = q('SELECT * FROM commands WHERE id = ?').get(Number(info.lastInsertRowid));
      return { command: commandView(row), created: true };
    },

    listCommands(deviceId, limit = 20) {
      expireCommands();
      return q('SELECT * FROM commands WHERE device_id = ? ORDER BY id DESC LIMIT ?')
        .all(deviceId, limit).map(commandView);
    },

    cancelCommand(deviceId, commandId) {
      const info = q(`UPDATE commands SET status='cancelled', completed_at=?
                      WHERE id = ? AND device_id = ? AND status = 'pending'`)
        .run(now(), commandId, deviceId);
      return info.changes > 0;
    },

    claimNextCommand(deviceId) {
      expireCommands();
      return inTransaction(() => {
        const row = q(`SELECT * FROM commands WHERE device_id = ? AND status = 'pending'
                       ORDER BY id LIMIT 1`).get(deviceId);
        if (!row) return null;
        q(`UPDATE commands SET status='delivered', delivered_at=? WHERE id = ?`).run(now(), row.id);
        return commandView(q('SELECT * FROM commands WHERE id = ?').get(row.id));
      });
    },

    completeCommand(deviceId, commandId, { ok, message }) {
      const info = q(`UPDATE commands SET status = ?, completed_at = ?, message = ?
                      WHERE id = ? AND device_id = ? AND status = 'delivered'`)
        .run(ok ? 'done' : 'failed', now(), message ?? null, commandId, deviceId);
      return info.changes > 0;
    },
  };
}
