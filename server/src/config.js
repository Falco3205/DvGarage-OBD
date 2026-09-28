export function loadConfig(env = process.env) {
  const adminToken = env.ADMIN_TOKEN;
  if (!adminToken || adminToken.length < 16) {
    throw new Error('ADMIN_TOKEN is required and must be at least 16 characters');
  }
  return {
    port: Number(env.PORT ?? 3000),
    dbPath: env.DB_PATH ?? './data/obd.sqlite',
    adminToken,
    commandTtlMs: Number(env.COMMAND_TTL_SECONDS ?? 600) * 1000,
    deliveryTimeoutMs: Number(env.DELIVERY_TIMEOUT_SECONDS ?? 60) * 1000,
    maxPollSeconds: Number(env.MAX_POLL_SECONDS ?? 25),
  };
}
