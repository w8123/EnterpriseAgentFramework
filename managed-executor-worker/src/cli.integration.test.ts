import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import {
  EVIDENCE_MANIFEST_FILE,
  EVENT_LOG_FILE,
  EXECUTION_SUMMARY_FILE,
  PATCH_FILE,
  TEST_REPORT_FILE,
} from './evidence.js';
import { MANAGED_EXECUTOR_JOB_SCHEMA } from './job-config.js';

const execFileAsync = promisify(execFile);

test('runs the real worker CLI against a fake stdio app-server and writes a complete evidence bundle', async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), 'reachai-worker-cli-'));
  const workspace = path.join(root, 'repo');
  const output = path.join(root, 'output');
  const objectiveFile = path.join(root, 'objective.txt');
  const configFile = path.join(root, 'job.json');
  const fakeCodex = path.join(root, 'fake-codex.mjs');
  try {
    await mkdir(workspace);
    await runGit(workspace, ['init']);
    await runGit(workspace, ['config', 'user.email', 'managed-executor@example.invalid']);
    await runGit(workspace, ['config', 'user.name', 'Managed Executor Test']);
    await writeFile(path.join(workspace, 'source.txt'), 'unchanged\n', 'utf8');
    await runGit(workspace, ['add', 'source.txt']);
    await runGit(workspace, ['commit', '-m', 'fixture']);
    await writeFile(objectiveFile, 'Inspect the repository without changing it.', 'utf8');
    await writeFile(fakeCodex, fakeAppServerSource(), 'utf8');
    await writeFile(configFile, JSON.stringify({
      schema: MANAGED_EXECUTOR_JOB_SCHEMA,
      executionId: 'mex_cli_e2e',
      workspaceRoot: workspace,
      objectiveFile,
      outputDirectory: output,
      sandboxProfile: 'WORKSPACE_PATCH',
      codexBinary: fakeCodex,
      maxWallTimeMs: 10_000,
      approvalTimeoutMs: 2_000,
      acceptanceCommands: [{
        name: 'fixture-check',
        argv: [process.execPath, '-e', "process.stdout.write('fixture ok')"],
        timeoutMs: 5_000,
      }],
    }), 'utf8');

    const cliPath = path.join(path.dirname(fileURLToPath(import.meta.url)), 'cli.js');
    let result: { stdout: string; stderr: string };
    try {
      result = await execFileAsync(process.execPath, [cliPath, '--job', configFile], {
        cwd: workspace,
        windowsHide: true,
        timeout: 20_000,
      });
    } catch (error) {
      const summary = await readFile(path.join(output, EXECUTION_SUMMARY_FILE), 'utf8').catch(() => null);
      const events = await readFile(path.join(output, EVENT_LOG_FILE), 'utf8').catch(() => null);
      const details = error && typeof error === 'object'
        ? JSON.stringify({
          stdout: 'stdout' in error ? error.stdout : null,
          stderr: 'stderr' in error ? error.stderr : null,
          code: 'code' in error ? error.code : null,
          summary,
          events,
        })
        : String(error);
      assert.fail(`Worker CLI failed: ${details}`);
    }
    const workerResult = JSON.parse(result.stdout) as { outcome: string; verificationOutcome: string };
    assert.equal(workerResult.outcome, 'SUCCEEDED');
    assert.equal(workerResult.verificationOutcome, 'PASSED');

    const expectedFiles = [
      EVIDENCE_MANIFEST_FILE,
      EVENT_LOG_FILE,
      EXECUTION_SUMMARY_FILE,
      PATCH_FILE,
      TEST_REPORT_FILE,
    ];
    for (const file of expectedFiles) {
      await assert.doesNotReject(readFile(path.join(output, file)));
    }
    assert.equal((await readFile(path.join(output, PATCH_FILE))).length, 0);

    const events = await readFile(path.join(output, EVENT_LOG_FILE), 'utf8');
    assert.match(events, /TURN_STARTED/);
    assert.match(events, /FINAL_MESSAGE/);
    assert.match(events, /TURN_COMPLETED/);
    assert.doesNotMatch(events, /hidden reasoning/);
    assert.doesNotMatch(events, /raw command output/);

    const manifest = JSON.parse(await readFile(path.join(output, EVIDENCE_MANIFEST_FILE), 'utf8')) as {
      artifacts: Array<{ name: string; sha256: string }>;
    };
    assert.equal(manifest.artifacts.length, 4);
    assert.ok(manifest.artifacts.every((artifact) => /^[a-f0-9]{64}$/.test(artifact.sha256)));
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

async function runGit(cwd: string, args: string[]): Promise<void> {
  await execFileAsync('git', args, { cwd, windowsHide: true });
}

function fakeAppServerSource(): string {
  return `
import readline from 'node:readline';

const lines = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });
const send = (message) => process.stdout.write(JSON.stringify(message) + '\\n');
lines.on('line', (line) => {
  const message = JSON.parse(line);
  if (!Object.prototype.hasOwnProperty.call(message, 'id')) return;
  if (message.method === 'initialize') {
    send({ id: message.id, result: {} });
    return;
  }
  if (message.method === 'thread/start') {
    send({ id: message.id, result: { thread: { id: 'fake-thread' } } });
    send({ method: 'thread/started', params: { thread: { id: 'fake-thread' } } });
    return;
  }
  if (message.method === 'turn/start') {
    send({ id: message.id, result: { turn: { id: 'fake-turn', status: 'inProgress' } } });
    send({ method: 'turn/started', params: { threadId: 'fake-thread', turn: { id: 'fake-turn' } } });
    send({ method: 'item/reasoning/textDelta', params: { delta: 'hidden reasoning' } });
    send({ method: 'item/commandExecution/outputDelta', params: { delta: 'raw command output' } });
    send({
      method: 'item/completed',
      params: {
        threadId: 'fake-thread',
        turnId: 'fake-turn',
        item: { id: 'message-1', type: 'agentMessage', text: 'Inspected the fixture.' }
      }
    });
    send({
      method: 'turn/completed',
      params: { threadId: 'fake-thread', turn: { id: 'fake-turn', status: 'completed' } }
    });
    return;
  }
  if (message.method === 'turn/interrupt') send({ id: message.id, result: {} });
});
`;
}
