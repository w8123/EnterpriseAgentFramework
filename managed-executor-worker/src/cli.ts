#!/usr/bin/env node
import { executeManagedJob } from './execute-job.js';
import { loadManagedExecutorJob } from './job-config.js';

async function main(): Promise<number> {
  const configPath = parseArgs(process.argv.slice(2));
  const job = await loadManagedExecutorJob(configPath);
  const result = await executeManagedJob(job);
  process.stdout.write(`${JSON.stringify(result.workerResult)}\n`);
  return result.exitCode;
}

function parseArgs(args: string[]): string {
  if (args.length !== 2 || args[0] !== '--job' || !args[1]) {
    throw new Error('Usage: reachai-managed-executor-worker --job <absolute-job-config.json>');
  }
  return args[1];
}

main()
  .then((exitCode) => {
    process.exitCode = exitCode;
  })
  .catch((error: unknown) => {
    void error;
    process.stderr.write('Managed Executor Worker failed during initialization\n');
    process.exitCode = 2;
  });
