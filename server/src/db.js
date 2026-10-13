import { DatabaseSync } from 'node:sqlite';
import { mkdirSync } from 'node:fs';
import { dirname } from 'node:path';

const SCHEMA = `
CREATE TABLE IF NOT EXISTS users (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  username TEXT NOT NULL UNIQUE COLLATE NOCASE,
  display_name TEXT NOT NULL,
  password_hash TEXT NOT NULL,
  role TEXT NOT NULL CHECK (role IN ('admin', 'customer')),
  disabled INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS sessions (
  token_hash TEXT PRIMARY KEY,
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_sessions_user ON sessions(user_id);
CREATE TABLE IF NOT EXISTS devices (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER REFERENCES users(id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  key_hash TEXT NOT NULL UNIQUE,
  created_at INTEGER NOT NULL,
  last_seen_at INTEGER,
  vin TEXT,
  mil_on INTEGER
);
CREATE TABLE IF NOT EXISTS scans (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  created_at INTEGER NOT NULL,
  vin TEXT,
  mil_on INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_scans_device ON scans(device_id, id DESC);
CREATE TABLE IF NOT EXISTS scan_dtcs (
  scan_id INTEGER NOT NULL REFERENCES scans(id) ON DELETE CASCADE,
  code TEXT NOT NULL,
  kind TEXT NOT NULL,
  PRIMARY KEY (scan_id, code, kind)
);
CREATE TABLE IF NOT EXISTS commands (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  type TEXT NOT NULL,
  status TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  delivered_at INTEGER,
  completed_at INTEGER,
  message TEXT
);
CREATE INDEX IF NOT EXISTS idx_commands_device ON commands(device_id, status, id);
CREATE TABLE IF NOT EXISTS workshop_reads (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  admin_id INTEGER REFERENCES users(id) ON DELETE SET NULL,
  customer_id INTEGER REFERENCES users(id) ON DELETE SET NULL,
  label TEXT NOT NULL,
  vin TEXT,
  mil_on INTEGER NOT NULL,
  cleared INTEGER NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS workshop_read_dtcs (
  read_id INTEGER NOT NULL REFERENCES workshop_reads(id) ON DELETE CASCADE,
  code TEXT NOT NULL,
  kind TEXT NOT NULL,
  PRIMARY KEY (read_id, code, kind)
);
`;

function migrate(db) {
  const columns = db.prepare('PRAGMA table_info(devices)').all().map((c) => c.name);
  if (!columns.includes('user_id')) {
    db.exec('ALTER TABLE devices ADD COLUMN user_id INTEGER REFERENCES users(id) ON DELETE CASCADE');
  }
  db.exec('CREATE INDEX IF NOT EXISTS idx_devices_user ON devices(user_id)');
}

export function openDatabase(path) {
  if (path !== ':memory:') mkdirSync(dirname(path), { recursive: true });
  const db = new DatabaseSync(path);
  db.exec('PRAGMA journal_mode = WAL');
  db.exec('PRAGMA foreign_keys = ON');
  db.exec(SCHEMA);
  migrate(db);
  return db;
}
