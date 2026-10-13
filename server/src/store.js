import { createHash, randomBytes } from 'node:crypto';
import { describeDtc } from './dtc.js';

export const COMMAND_TYPES = new Set(['clear_dtc', 'rescan']);
export const ROLES = new Set(['admin', 'customer']);

const hashSecret = (secret) => createHash('sha256').update(secret).digest('hex');

export function createStore(db, { commandTtlMs, deliveryTimeoutMs, sessionTtlMs }, now = () => Date.now()) {
  const q = (sql) => db.prepare(sql);

  const userView = (row) => ({
    id: row.id,
    username: row.username,
    displayName: row.display_name,
    role: row.role,
    disabled: Boolean(row.disabled),
    createdAt: row.created_at,
  });

  const deviceView = (row) => ({
    id: row.id,
    userId: row.user_id,
    owner: row.owner_name ?? null,
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

  const DEVICE_SELECT = `SELECT d.*, u.display_name AS owner_name
                         FROM devices d LEFT JOIN users u ON u.id = d.user_id`;

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

  const dtcRows = (table, idColumn, id) =>
    q(`SELECT code, kind FROM ${table} WHERE ${idColumn} = ? ORDER BY kind, code`)
      .all(id).map((d) => ({ ...describeDtc(d.code), kind: d.kind }));

  return {
    countUsers(role) {
      return q('SELECT COUNT(*) AS n FROM users WHERE role = ?').get(role).n;
    },

    createUser({ username, displayName, passwordHash, role }) {
      const info = q(`INSERT INTO users (username, display_name, password_hash, role, created_at)
                      VALUES (?, ?, ?, ?, ?)`).run(username, displayName, passwordHash, role, now());
      return this.getUser(Number(info.lastInsertRowid));
    },

    getUser(id) {
      const row = q('SELECT * FROM users WHERE id = ?').get(id);
      return row ? userView(row) : null;
    },

    findUserCredentials(username) {
      const row = q('SELECT * FROM users WHERE username = ?').get(username);
      return row ? { user: userView(row), passwordHash: row.password_hash } : null;
    },

    listUsers(role) {
      const rows = role
        ? q('SELECT * FROM users WHERE role = ? ORDER BY display_name').all(role)
        : q('SELECT * FROM users ORDER BY role, display_name').all();
      return rows.map(userView);
    },

    updateUser(id, { displayName, disabled, passwordHash }) {
      return inTransaction(() => {
        const current = q('SELECT * FROM users WHERE id = ?').get(id);
        if (!current) return null;
        q('UPDATE users SET display_name = ?, disabled = ?, password_hash = ? WHERE id = ?').run(
          displayName ?? current.display_name,
          disabled === undefined ? current.disabled : Number(disabled),
          passwordHash ?? current.password_hash,
          id,
        );
        if (passwordHash || disabled) q('DELETE FROM sessions WHERE user_id = ?').run(id);
        return this.getUser(id);
      });
    },

    deleteUser(id) {
      return q('DELETE FROM users WHERE id = ?').run(id).changes > 0;
    },

    createSession(userId) {
      const token = randomBytes(32).toString('hex');
      const t = now();
      q('DELETE FROM sessions WHERE expires_at < ?').run(t);
      q('INSERT INTO sessions (token_hash, user_id, created_at, expires_at) VALUES (?, ?, ?, ?)')
        .run(hashSecret(token), userId, t, t + sessionTtlMs);
      return token;
    },

    findSessionUser(token) {
      const row = q(`SELECT u.* FROM sessions s JOIN users u ON u.id = s.user_id
                     WHERE s.token_hash = ? AND s.expires_at > ? AND u.disabled = 0`)
        .get(hashSecret(token), now());
      return row ? userView(row) : null;
    },

    deleteSession(token) {
      q('DELETE FROM sessions WHERE token_hash = ?').run(hashSecret(token));
    },

    deleteUserSessions(userId) {
      q('DELETE FROM sessions WHERE user_id = ?').run(userId);
    },

    setPassword(userId, passwordHash) {
      q('UPDATE users SET password_hash = ? WHERE id = ?').run(passwordHash, userId);
    },

    createDevice(name, userId = null) {
      const key = randomBytes(32).toString('hex');
      const info = q('INSERT INTO devices (user_id, name, key_hash, created_at) VALUES (?, ?, ?, ?)')
        .run(userId, name, hashSecret(key), now());
      return { id: Number(info.lastInsertRowid), name, userId, key };
    },

    rotateDeviceKey(id) {
      const key = randomBytes(32).toString('hex');
      const info = q('UPDATE devices SET key_hash = ? WHERE id = ?').run(hashSecret(key), id);
      return info.changes > 0 ? key : null;
    },

    findDeviceByKey(key) {
      const row = q(`${DEVICE_SELECT} WHERE d.key_hash = ?`).get(hashSecret(key));
      return row ? deviceView(row) : null;
    },

    listDevices({ userId } = {}) {
      const rows = userId === undefined
        ? q(`${DEVICE_SELECT} ORDER BY d.id`).all()
        : q(`${DEVICE_SELECT} WHERE d.user_id = ? ORDER BY d.id`).all(userId);
      return rows.map(deviceView);
    },

    countDevices(userId) {
      return q('SELECT COUNT(*) AS n FROM devices WHERE user_id = ?').get(userId).n;
    },

    getDevice(id) {
      const row = q(`${DEVICE_SELECT} WHERE d.id = ?`).get(id);
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
      return q('SELECT * FROM scans WHERE device_id = ? ORDER BY id DESC LIMIT ?')
        .all(deviceId, limit)
        .map((s) => ({
          id: s.id,
          createdAt: s.created_at,
          vin: s.vin,
          milOn: Boolean(s.mil_on),
          dtcs: dtcRows('scan_dtcs', 'scan_id', s.id),
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

    recordWorkshopRead(adminId, { customerId, label, vin, milOn, cleared, dtcs }) {
      return inTransaction(() => {
        const info = q(`INSERT INTO workshop_reads (admin_id, customer_id, label, vin, mil_on, cleared, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)`)
          .run(adminId, customerId ?? null, label, vin ?? null, milOn ? 1 : 0, cleared ? 1 : 0, now());
        const id = Number(info.lastInsertRowid);
        const insert = q('INSERT OR IGNORE INTO workshop_read_dtcs (read_id, code, kind) VALUES (?, ?, ?)');
        for (const { code, kind } of dtcs) insert.run(id, code, kind);
        return id;
      });
    },

    listWorkshopReads(limit = 50) {
      return q(`SELECT r.*, c.display_name AS customer_name, a.display_name AS admin_name
                FROM workshop_reads r
                LEFT JOIN users c ON c.id = r.customer_id
                LEFT JOIN users a ON a.id = r.admin_id
                ORDER BY r.id DESC LIMIT ?`).all(limit).map((r) => ({
        id: r.id,
        createdAt: r.created_at,
        label: r.label,
        vin: r.vin,
        milOn: Boolean(r.mil_on),
        cleared: Boolean(r.cleared),
        customerId: r.customer_id,
        customer: r.customer_name,
        admin: r.admin_name,
        dtcs: dtcRows('workshop_read_dtcs', 'read_id', r.id),
      }));
    },

    deleteWorkshopRead(id) {
      return q('DELETE FROM workshop_reads WHERE id = ?').run(id).changes > 0;
    },
  };
}
