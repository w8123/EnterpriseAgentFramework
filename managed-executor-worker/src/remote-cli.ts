#!/usr/bin/env node
import { loadRemoteWorkerConfig, runRemoteWorker } from './remote-worker.js';

async function main(): Promise<void> {
  const config = await loadRemoteWorkerConfig();
  const result = await runRemoteWorker(config);
  process.stdout.write(`${JSON.stringify({
    schema: 'reachai.managed-executor.remote-worker-result.v1',
    executionId: result.executionId,
    status: result.status,
  })}\n`);
}

main().catch(() => {
  process.stderr.write('Managed Executor remote Worker failed\n');
  process.exitCode = 1;
});
