#!/usr/bin/env node
import {
  loadRuntimeCredentialBrokerConfig,
  startRuntimeCredentialBroker,
} from './runtime-credential-broker.js';

async function main(): Promise<void> {
  const config = await loadRuntimeCredentialBrokerConfig();
  const broker = await startRuntimeCredentialBroker(config);
  const stop = async () => {
    await broker.close().catch(() => undefined);
    process.exit(0);
  };
  process.once('SIGTERM', () => void stop());
  process.once('SIGINT', () => void stop());
}

main().catch(() => {
  process.stderr.write('Managed Runtime credential broker failed\n');
  process.exitCode = 1;
});
