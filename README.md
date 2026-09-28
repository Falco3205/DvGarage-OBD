# DvGarage OBD

Reads fault codes (DTC) from any ELM327-compatible Bluetooth OBD-II adapter, sends them to a server and lets you clear them remotely from a web dashboard.

```
Vehicle ──OBD-II── ELM327 adapter ──Bluetooth── Android app ──HTTPS── Server ── Dashboard
```

The car-side app keeps an outbound long-poll connection to the server, so no inbound ports are needed on the phone. A "clear errors" click in the dashboard is queued on the server and executed by the app on the vehicle as soon as it picks it up.

## Components

| Path | What it is |
| --- | --- |
| `obd-core/` | Pure Kotlin/JVM ELM327 + OBD-II protocol library (init, DTC/VIN/MIL decoding, clear). Unit tested. |
| `android/` | Android app (Compose UI + foreground service). Supports Bluetooth Classic (SPP) and BLE adapters. |
| `server/` | Node.js API + dashboard, SQLite storage, zero npm dependencies. |

## Server

Requires Node.js >= 22.13.

```
cd server
ADMIN_TOKEN=<at least 16 random chars> npm start
npm test
```

| Variable | Default | Meaning |
| --- | --- | --- |
| `ADMIN_TOKEN` | required | Bearer token for the dashboard/admin API |
| `PORT` | `3000` | Listen port |
| `DB_PATH` | `./data/obd.sqlite` | SQLite file |
| `COMMAND_TTL_SECONDS` | `600` | Queued commands not picked up in time expire |
| `DELIVERY_TIMEOUT_SECONDS` | `60` | A delivered command with no result is re-queued |

Open `http://host:3000/`, log in with the admin token, add a device (vehicle) and copy its key, shown once. **Run the server behind HTTPS**: the app refuses cleartext HTTP and keys travel as bearer tokens.

### API

Device (Bearer = device key):

- `POST /api/device/scans` `{ vin?, milOn, dtcs:[{code, kind: stored|pending|permanent}] }`
- `GET /api/device/commands/next?wait=20` → `{command}` or `204` (long poll)
- `POST /api/device/commands/:id/result` `{ ok, message? }`

Admin (Bearer = `ADMIN_TOKEN`):

- `GET/POST /api/admin/devices`, `GET/DELETE /api/admin/devices/:id`
- `POST /api/admin/devices/:id/commands` `{ type: clear_dtc|rescan }`
- `DELETE /api/admin/devices/:id/commands/:commandId` (pending only)

## Android app

Open `android/` in Android Studio (or `./gradlew :app:assembleDebug` with the Android SDK installed).

1. Pair the adapter in Android settings (PIN usually `1234` or `0000`) — or use *Scan BLE* for BLE adapters.
2. Enter server URL and device key, pick the adapter, press *Start*.
3. Keep the ignition on. Clearing codes works with engine off, ignition on.

The app reads mode 01 PID 01 (MIL), 03 (stored), 07 (pending), 0A (permanent), 09 PID 02 (VIN) at connect, on every `rescan` command and every 5 minutes, and executes mode 04 for `clear_dtc`, followed by a rescan whose result is uploaded.

Clearing codes also resets readiness monitors and freeze-frame data; the dashboard asks for confirmation.
