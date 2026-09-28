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

- `src/store.js`: SQLite access. Devices (API key stored as SHA-256), scans + DTCs, commands with states `pending → delivered → done|failed`, plus `cancelled` / `expired`. One open command per type per device (idempotent enqueue). Delivered-but-unanswered commands return to `pending` after `DELIVERY_TIMEOUT_SECONDS`; pending ones expire after `COMMAND_TTL_SECONDS` so a stale "clear" is never executed later.
- `src/app.js`: routing, auth (constant-time admin token compare, device key lookup), validation, long-poll waiters, static dashboard.
- `src/dtc.js`: DTC validation and a small description table (unknown codes still show system/type).
- `public/index.html`: dashboard, no build step; polls every 5 s.
- Tests: `npm test` (`test/api.test.js`, end-to-end over HTTP with an in-memory DB).

## android (`android/`)

- `bluetooth/ClassicTransport.kt`: SPP socket; tries insecure SPP, secure SPP, then reflective RFCOMM channel 1 (needed by many clones).
- `bluetooth/BleTransport.kt`: GATT; picks the notify + write characteristics generically (covers FFE0/FFF0/18F0 style adapters), 20-byte write chunks.
- `bluetooth/Adapters.kt`: paired list, BLE scan, transport factory.
- `server/ServerClient.kt`: `HttpURLConnection` client with `abort()` to unblock a long poll on stop.
- `service/ObdRunner.kt`: worker thread. Connect → init → upload snapshot → loop {long poll; run `clear_dtc`/`rescan`; periodic rescan}. Bluetooth failures reconnect with exponential backoff (3 s → 60 s); network failures retry without dropping the adapter; an ECU refusing to clear is reported as a failed command without tearing down the link.
- `service/ObdService.kt`: foreground service (`connectedDevice`) hosting the runner. `service/AppState.kt`: state flow for the UI.
- `ui/`: Compose screen with settings, adapter picker, status and last DTC list.
- `obd-core` is included from `../obd-core` in `android/settings.gradle.kts`.

## Verification status

- `obd-core`: 19 unit tests pass.
- `server`: 7 end-to-end tests pass.
- `android`: written but **not compiled** in the authoring environment (no Android SDK reachable). First step on a machine with the SDK: `cd android && ./gradlew :app:assembleDebug`, then test against a real adapter.
