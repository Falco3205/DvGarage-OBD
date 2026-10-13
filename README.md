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
ADMIN_PASSWORD=<at least 8 chars> npm start
npm test
```

On first start, if no administrator exists, one is created from `ADMIN_USERNAME` (default `admin`) and `ADMIN_PASSWORD`. After that `ADMIN_PASSWORD` is no longer needed.

| Variable | Default | Meaning |
| --- | --- | --- |
| `ADMIN_USERNAME` | `admin` | Username of the first administrator |
| `ADMIN_PASSWORD` | — | Required only while no administrator exists |
| `PORT` | `3000` | Listen port |
| `DB_PATH` | `./data/obd.sqlite` | SQLite file |
| `SESSION_TTL_DAYS` | `30` | Login session lifetime |
| `COMMAND_TTL_SECONDS` | `600` | Queued commands not picked up in time expire |
| `DELIVERY_TIMEOUT_SECONDS` | `60` | A delivered command with no result is re-queued |

**Run the server behind HTTPS**: the app refuses cleartext HTTP and sessions travel as bearer tokens.

### Users and roles

- **Admin (officina)**: creates customers, sees every vehicle, can clear codes remotely, and can store direct workshop readings.
- **Customer**: logs in on the web or in the app, registers their own vehicles (max 10), sees their codes and can request a rescan. Clearing codes is reserved to the workshop.

Passwords are hashed with scrypt, sessions are random tokens stored hashed, logins are throttled (5 failures / 15 min per user and address), disabling a user or changing a password revokes their sessions.

### API

Auth: `POST /api/auth/login` `{username, password}` → `{token, user}`; `POST /api/auth/logout`; `GET /api/me`; `POST /api/me/password`.

Vehicles (session token; customers see only their own, admins all):

- `GET/POST /api/me/devices`
- `GET/DELETE /api/devices/:id`
- `POST /api/devices/:id/key` — issues a new device key (used by the app to link a phone to an existing vehicle)
- `POST /api/devices/:id/commands` `{ type: rescan|clear_dtc }` (`clear_dtc` admin only)
- `DELETE /api/devices/:id/commands/:commandId` (pending only)

Admin only:

- `GET/POST /api/admin/users`, `PATCH/DELETE /api/admin/users/:id`
- `GET/POST /api/admin/devices`
- `GET/POST /api/admin/workshop/reads`, `DELETE /api/admin/workshop/reads/:id`

Device (Bearer = device key, used by the app's monitoring service):

- `POST /api/device/scans` `{ vin?, milOn, dtcs:[{code, kind: stored|pending|permanent}] }`
- `GET /api/device/commands/next?wait=20` → `{command}` or `204` (long poll)
- `POST /api/device/commands/:id/result` `{ ok, message? }`

## Android app

Open `android/` in Android Studio (or `./gradlew :app:assembleDebug` with the Android SDK installed).

1. Pair the adapter in Android settings (PIN usually `1234` or `0000`) — or use *Cerca BLE* for BLE adapters.
2. Log in with the server address and your account.
3. **Veicolo tab** (customers and admins): pick or create the vehicle, pick the adapter, *Avvia monitoraggio*. The phone then reports codes to the server and executes remote commands.
4. **Officina tab** (admins only): connect straight to the adapter, *Leggi errori*, *Cancella errori*, and optionally *Salva lettura* on the server with or without a customer. No customer phone involved.
5. **Log tab**: raw ELM327 traffic; share it when something does not work with a particular adapter.

The app reads mode 01 PID 01 (MIL), 03 (stored), 07 (pending), 0A (permanent), 09 PID 02 (VIN), and executes mode 04 for clearing. Keep the ignition on; clearing works with the engine off. Clearing also resets readiness monitors and freeze-frame data.
