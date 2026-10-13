export function loadConfig(env = process.env) {
  return {
    port: Number(env.PORT ?? 3000),
    dbPath: env.DB_PATH ?? './data/obd.sqlite',
    adminUsername: env.ADMIN_USERNAME ?? 'admin',
    adminPassword: env.ADMIN_PASSWORD ?? '',
    sessionTtlMs: Number(env.SESSION_TTL_DAYS ?? 30) * 86_400_000,
    commandTtlMs: Number(env.COMMAND_TTL_SECONDS ?? 600) * 1000,
    deliveryTimeoutMs: Number(env.DELIVERY_TIMEOUT_SECONDS ?? 60) * 1000,
    maxPollSeconds: Number(env.MAX_POLL_SECONDS ?? 25),
  };
}
