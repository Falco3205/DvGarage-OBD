import { loadConfig } from './config.js';
import { openDatabase } from './db.js';
import { createStore } from './store.js';
import { createApp } from './app.js';

const config = loadConfig();
const store = createStore(openDatabase(config.dbPath), config);
const server = createApp({ config, store });

server.listen(config.port, () => {
  console.log(`DvGarage OBD server listening on :${config.port}`);
});

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => {
    server.close(() => process.exit(0));
    server.closeAllConnections();
  });
}
