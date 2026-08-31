import assert from 'node:assert/strict';
import { mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { loadManagedExecutorJob, MANAGED_EXECUTOR_JOB_SCHEMA } from './job-config.js';
import { buildWorkerEnvironment } from './process-policy.js';

test('loads a bounded strict job contract with an objective file', async () => {
  const fixture = await createFixture();
  try {
    const configPath = path.join(fixture.root, 'job.json');
    await writeFile(configPath, JSON.stringify({
      schema: MANAGED_EXECUTOR_JOB_SCHEMA,
      executionId: 'mex_config_1',
      workspaceRoot: fixture.workspace,
      objectiveFile: fixture.objective,
      outputDirectory: fixture.output,
      sandboxProfile: 'WORKSPACE_PATCH',
      acceptanceCommands: [{
        name: 'node-version',
        argv: [process.execPath, '--version'],
        timeoutMs: 5_000,
      }],
    }), 'utf8');

    const loaded = await loadManagedExecutorJob(configPath);
    assert.equal(loaded.executionId, 'mex_config_1');
    assert.equal(loaded.objective, 'Inspect and verify the fixture.');
    assert.equal(loaded.acceptanceCommands[0]?.argv[0], process.execPath);
    assert.equal(loaded.maxWallTimeMs, 30 * 60_000);
  } finally {
    await rm(fixture.root, { recursive: true, force: true });
  }
});

test('rejects unknown job fields and output paths inside the workspace', async () => {
  const fixture = await createFixture();
  try {
    const unknownConfig = path.join(fixture.root, 'unknown.json');
    await writeFile(unknownConfig, JSON.stringify({
      schema: MANAGED_EXECUTOR_JOB_SCHEMA,
      executionId: 'mex_config_2',
      workspaceRoot: fixture.workspace,
      objectiveFile: fixture.objective,
      outputDirectory: fixture.output,
      sandboxProfile: 'ANALYZE_READONLY',
      secretOverride: 'must-not-be-accepted',
    }), 'utf8');
    await assert.rejects(loadManagedExecutorJob(unknownConfig), /unknown fields/);

    const insideConfig = path.join(fixture.root, 'inside.json');
    await writeFile(insideConfig, JSON.stringify({
      schema: MANAGED_EXECUTOR_JOB_SCHEMA,
      executionId: 'mex_config_3',
      workspaceRoot: fixture.workspace,
      objectiveFile: fixture.objective,
      outputDirectory: path.join(fixture.workspace, 'artifacts'),
      sandboxProfile: 'ANALYZE_READONLY',
    }), 'utf8');
    await assert.rejects(loadManagedExecutorJob(insideConfig), /outside workspaceRoot/);
  } finally {
    await rm(fixture.root, { recursive: true, force: true });
  }
});

test('worker child environment drops ambient credentials and home directories', () => {
  const environment = buildWorkerEnvironment({
    PATH: 'safe-path',
    HOME: '/secret/home',
    USERPROFILE: 'C:\\secret-home',
    OPENAI_API_KEY: 'sk-should-not-pass',
    AWS_SECRET_ACCESS_KEY: 'should-not-pass',
    CODEX_HOME: '/isolated/codex-home',
  });
  assert.equal(environment.PATH, 'safe-path');
  assert.equal(environment.CODEX_HOME, '/isolated/codex-home');
  assert.equal(environment.HOME, undefined);
  assert.equal(environment.USERPROFILE, undefined);
  assert.equal(environment.OPENAI_API_KEY, undefined);
  assert.equal(environment.AWS_SECRET_ACCESS_KEY, undefined);
});

async function createFixture(): Promise<{
  root: string;
  workspace: string;
  objective: string;
  output: string;
}> {
  const root = await mkdtemp(path.join(os.tmpdir(), 'reachai-worker-config-'));
  const workspace = path.join(root, 'workspace');
  const objective = path.join(root, 'objective.txt');
  await mkdir(workspace);
  await writeFile(objective, 'Inspect and verify the fixture.', 'utf8');
  return { root, workspace, objective, output: path.join(root, 'output') };
}
