import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import {
  buildEvidenceManifest,
  collectEvidence,
  EVENT_LOG_FILE,
  EXECUTION_SUMMARY_FILE,
  PATCH_FILE,
  prepareOutputDirectory,
  TEST_REPORT_FILE,
  writeJsonArtifact,
} from './evidence.js';
import { MANAGED_EXECUTOR_JOB_SCHEMA, type LoadedManagedExecutorJob } from './job-config.js';

const execFileAsync = promisify(execFile);

test('collects tracked and untracked changes without touching the real Git index', async () => {
  const fixture = await createGitFixture('WORKSPACE_PATCH');
  try {
    await writeFile(path.join(fixture.workspace, 'tracked.txt'), 'changed\n', 'utf8');
    await writeFile(path.join(fixture.workspace, 'untracked.txt'), 'new file\n', 'utf8');
    await prepareOutputDirectory(fixture.output);

    const evidence = await collectEvidence(fixture.job);
    assert.equal(evidence.patch.empty, false);
    assert.equal(evidence.tests.outcome, 'PASSED');
    assert.equal(evidence.readonlyViolation, false);

    const patchText = await readFile(path.join(fixture.output, PATCH_FILE), 'utf8');
    assert.match(patchText, /tracked\.txt/);
    assert.match(patchText, /untracked\.txt/);

    const cached = await runGitResult(fixture.workspace, ['diff', '--cached', '--quiet']);
    assert.equal(cached.exitCode, 0, 'evidence collection must not stage the real workspace index');

    const report = JSON.parse(await readFile(path.join(fixture.output, TEST_REPORT_FILE), 'utf8')) as {
      commands: Array<{ stdout: string }>;
    };
    assert.match(report.commands[0]?.stdout || '', /Bearer <redacted>/);
    assert.doesNotMatch(report.commands[0]?.stdout || '', /abcdefghijklmnop/);
  } finally {
    await rm(fixture.root, { recursive: true, force: true });
  }
});

test('marks any final source diff as a read-only profile violation', async () => {
  const fixture = await createGitFixture('ANALYZE_READONLY');
  try {
    await writeFile(path.join(fixture.workspace, 'tracked.txt'), 'unexpected change\n', 'utf8');
    await prepareOutputDirectory(fixture.output);
    const evidence = await collectEvidence(fixture.job, { runAcceptance: false });
    assert.equal(evidence.tests.outcome, 'NOT_RUN');
    assert.equal(evidence.readonlyViolation, true);
  } finally {
    await rm(fixture.root, { recursive: true, force: true });
  }
});

test('builds a digest manifest over only explicit evidence artifacts', async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), 'reachai-worker-manifest-'));
  try {
    await writeFile(path.join(root, EVENT_LOG_FILE), '{"event":1}\n', 'utf8');
    await writeJsonArtifact(path.join(root, EXECUTION_SUMMARY_FILE), { outcome: 'SUCCEEDED' });
    const manifest = await buildEvidenceManifest('mex_manifest_1', root, [
      { name: EVENT_LOG_FILE, mediaType: 'application/x-ndjson' },
      { name: EXECUTION_SUMMARY_FILE, mediaType: 'application/json' },
    ]);
    assert.equal(manifest.artifacts.length, 2);
    assert.match(manifest.artifacts[0]?.sha256 || '', /^[a-f0-9]{64}$/);
    assert.deepEqual(manifest.artifacts.map((artifact) => artifact.name), [EVENT_LOG_FILE, EXECUTION_SUMMARY_FILE]);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

async function createGitFixture(
  sandboxProfile: 'ANALYZE_READONLY' | 'WORKSPACE_PATCH',
): Promise<{
  root: string;
  workspace: string;
  output: string;
  job: LoadedManagedExecutorJob;
}> {
  const root = await mkdtemp(path.join(os.tmpdir(), 'reachai-worker-evidence-'));
  const workspace = path.join(root, 'repo');
  const output = path.join(root, 'output');
  const objectiveFile = path.join(root, 'objective.txt');
  const configPath = path.join(root, 'job.json');
  await mkdir(workspace);
  await writeFile(objectiveFile, 'Test objective', 'utf8');
  await runGit(workspace, ['init']);
  await runGit(workspace, ['config', 'user.email', 'managed-executor@example.invalid']);
  await runGit(workspace, ['config', 'user.name', 'Managed Executor Test']);
  await writeFile(path.join(workspace, 'tracked.txt'), 'original\n', 'utf8');
  await runGit(workspace, ['add', 'tracked.txt']);
  await runGit(workspace, ['commit', '-m', 'fixture']);

  return {
    root,
    workspace,
    output,
    job: {
      schema: MANAGED_EXECUTOR_JOB_SCHEMA,
      configPath,
      executionId: `mex_evidence_${sandboxProfile.toLowerCase()}`,
      workspaceRoot: workspace,
      objectiveFile,
      outputDirectory: output,
      sandboxProfile,
      acceptanceCommands: sandboxProfile === 'WORKSPACE_PATCH' ? [{
        name: 'sanitized-output',
        argv: [process.execPath, '-e', "process.stdout.write('Bearer abcdefghijklmnop')"],
        timeoutMs: 10_000,
      }] : [],
      maxWallTimeMs: 30_000,
      approvalTimeoutMs: 10_000,
      objective: 'Test objective',
    },
  };
}

async function runGit(cwd: string, args: string[]): Promise<void> {
  await execFileAsync('git', args, { cwd, windowsHide: true });
}

async function runGitResult(cwd: string, args: string[]): Promise<{ exitCode: number }> {
  try {
    await runGit(cwd, args);
    return { exitCode: 0 };
  } catch (error) {
    const exitCode = error && typeof error === 'object' && 'code' in error && typeof error.code === 'number'
      ? error.code
      : -1;
    return { exitCode };
  }
}
