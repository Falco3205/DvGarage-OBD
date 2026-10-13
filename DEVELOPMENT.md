# Development log

What was written and how, for future maintenance.

## Architecture decisions

- **Android native (Kotlin)**: cheap Chinese ELM327 adapters are overwhelmingly Bluetooth Classic SPP, which iOS does not allow; Android supports both SPP and BLE. BLE is supported too for the adapters that need it.
- **Pull-based remote commands**: the phone long-polls the server (`GET /commands/next?wait=20`), so it works behind mobile NAT and needs no push infrastructure. Server wakes waiting pollers in-memory when a command is queued.
- **Protocol logic isolated in `obd-core`** (pure JVM) so it is testable without a device or the Android SDK.
- **Server without dependencies**: plain `node:http`, `node:sqlite`, `node:test`. Requires Node >= 22.13.

## obd-core (`obd-core/src/main/kotlin/it/dvgarage/obd/core`)

- `Transport.kt`: `ObdTransport` interface and `PromptTransport`, which sends `cmd\r` and collects the reply until the ELM327 `>` prompt (thread-safe, timeout).
- `StreamTransport.kt`: `PromptTransport` over `InputStream`/`OutputStream` with a reader thread (Bluetooth Classic).
- `Elm327Client.kt`: init sequence `ATZ, ATE0, ATL0, ATS0, ATH0, ATSP0` (echo/linefeed/spaces/headers off, auto protocol) and `request()`.
- `ObdParser.kt`: reply classification (`Messages` / `NoData` / `Failure`), CAN multi-frame assembly (`0:`/`1:` lines + length line), DTC decoding (with/without CAN count byte, padding skipped, duplicates from several ECUs merged), MIL status, VIN.
- `ObdSession.kt`: `readSnapshot()` (0101, 03, 07, 0A, 0902) and `clearCodes()` (04 → positive `44`).
- Tests: `ObdParserTest`, `ObdSessionTest` (scripted fake adapter). Run `cd obd-core && ./gradlew test`.

## server (`server/`)

- `src/db.js`: schema (users, sessions, devices, scans, commands, workshop reads) and a migration adding `devices.user_id` to databases created by the first version.
- `src/password.js`: scrypt hashing/verification. `src/store.js`: all SQL. Sessions and device keys are stored as SHA-256 hashes; commands have states `pending → delivered → done|failed` plus `cancelled`/`expired`, one open command per type per vehicle. Delivered-but-unanswered commands return to `pending` after `DELIVERY_TIMEOUT_SECONDS`; pending ones expire after `COMMAND_TTL_SECONDS`, so a stale "clear" is never executed later.
- `src/app.js`: routing, authorization (admin vs customer ownership checks, `clear_dtc` admin-only), validation, login throttling (in memory, per address+user, with a dummy hash compare for unknown users), long-poll waiters, static dashboard. `ensureAdmin` seeds the first admin from env.
- `src/dtc.js`: DTC validation and a small description table.
- `public/index.html`: dashboard. Login; customers see their vehicles and can request rescans; admins also get Clienti (create/disable/reset password/delete) and Officina (stored direct readings) tabs.
- Tests: `npm test` (`test/api.test.js`, 20 end-to-end tests over HTTP with an in-memory DB). Dashboard additionally smoke-tested with headless Chromium for both roles.

## android (`android/`)

- `Settings.kt`: persisted account (server, session token, role), linked vehicle (id + device key), chosen adapter.
- `server/HttpJson.kt`, `ServerClient.kt` (device-key calls used by the service), `UserApi.kt` (login, vehicles, key rotation, customers, workshop reads).
- `bluetooth/ClassicTransport.kt`: SPP socket; tries insecure SPP, secure SPP, then reflective RFCOMM channel 1 (needed by many clones).
- `bluetooth/BleTransport.kt`: GATT; picks notify + write characteristics generically, 20-byte write chunks.
- `bluetooth/Adapters.kt`: paired list, BLE scan, transport factory (every transport is wrapped in `TracingTransport` feeding `service/TraceLog`).
- `service/ObdRunner.kt` + `ObdService.kt`: monitoring. Connect → init → upload snapshot → loop {long poll; `clear_dtc`/`rescan`; periodic rescan}. Bluetooth failures reconnect with exponential backoff; network failures retry without dropping the adapter; an ECU refusing to clear is reported as a failed command without tearing down the link.
- `service/WorkshopRunner.kt`: admin direct mode. Each action opens the adapter, runs, closes. State exposed as a flow; singleton so it survives activity recreation.
- `ui/`: `LoginScreen`, `HomeScreen` (tabs), `VehicleTab`, `WorkshopTab` (admin only), `LogTab`, shared `Components`; `MainActivity` holds the state and wiring.
- `obd-core` is included from `../obd-core` in `android/settings.gradle.kts`.

## Verification status

- `obd-core`: 21 unit tests pass.
- `server`: 20 end-to-end tests pass.
- `android`: written but **not compiled** in the authoring environment (neither the Android SDK nor Google Maven is reachable there). First step on a machine with the SDK: `cd android && ./gradlew :app:assembleDebug`, then test against a real adapter and share the Log tab output if anything misbehaves.

## Known limits

- Multi-ECU vehicles answering mode 03 are parsed with headers off (`ATH0`); frames of different ECUs are kept apart when each has its own length line, but exotic adapters/vehicles may need header-aware parsing.
- Web Bluetooth is not used: browsers cannot talk to Classic SPP adapters, so direct reading lives in the Android app.
