import { createHash } from 'node:crypto';
import { lstat, mkdir, readFile, readdir, rename, rm, stat, writeFile } from 'node:fs/promises';
import path from 'node:path';
import type { AcceptanceCommandV1, LoadedManagedExecutorJob } from './job-config.js';
import { buildWorkerEnvironment, runBoundedProcess } from './process-policy.js';
import { ManagedEventSanitizer } from './sanitizer.js';

export const PATCH_FILE = 'workspace.patch';
export const TEST_REPORT_FILE = 'test-report.json';
export const EXECUTION_SUMMARY_FILE = 'execution-summary.json';
export const EVENT_LOG_FILE = 'events.ndjson';
export const EVIDENCE_MANIFEST_FILE = 'evidence-manifest.json';

export interface WorkspacePatchEvidenceV1 {
  schema: 'reachai.managed-executor.patch-evidence.v1';
  baseCommit: string;
  empty: boolean;
  bytes: number;
  sha256: string;
}

export type AcceptanceCommandOutcome = 'PASSED' | 'FAILED' | 'TIMED_OUT' | 'OUTPUT_LIMIT_EXCEEDED' | 'ERROR';

export interface AcceptanceCommandResultV1 {
  name: string;
  argv: string[];
  outcome: AcceptanceCommandOutcome;
  exitCode: number | null;
  durationMs: number;
  stdout: string | null;
  stderr: string | null;
}

export interface AcceptanceReportV1 {
  schema: 'reachai.managed-executor.test-report.v1';
  executionId: string;
  outcome: 'PASSED' | 'FAILED' | 'NOT_RUN';
  commands: AcceptanceCommandResultV1[];
}

export interface CollectedEvidenceV1 {
  patch: WorkspacePatchEvidenceV1;
  tests: AcceptanceReportV1;
  readonlyViolation: boolean;
}

export interface EvidenceManifestEntryV1 {
  name: string;
  mediaType: string;
  bytes: number;
  sha256: string;
}

export async function prepareOutputDirectory(outputDirectory: string): Promise<void> {
  try {
    const linkStats = await lstat(outputDirectory);
    if (linkStats.isSymbolicLink()) throw new Error('outputDirectory must not be a symbolic link');
    const existing = await stat(outputDirectory);
    if (!existing.isDirectory()) throw new Error('outputDirectory exists but is not a directory');
    const entries = await readdir(outputDirectory);
    if (entries.length > 0) throw new Error('outputDirectory must be empty');
  } catch (error) {
    if (isMissing(error)) {
      await mkdir(outputDirectory, { recursive: true, mode: 0o700 });
      return;
    }
    throw error;
  }
}

export async function collectEvidence(
  job: LoadedManagedExecutorJob,
  options: { runAcceptance?: boolean } = {},
): Promise<CollectedEvidenceV1> {
  const tests = options.runAcceptance === false
    ? notRunAcceptanceReport(job.executionId)
    : await runAcceptanceCommands(job);
  await writeJsonArtifact(path.join(job.outputDirectory, TEST_REPORT_FILE), tests);
  const patch = await collectWorkspacePatch(job);
  return {
    patch,
    tests,
    readonlyViolation: job.sandboxProfile === 'ANALYZE_READONLY' && !patch.empty,
  };
}

export async function writeJsonArtifact(filePath: string, value: unknown): Promise<void> {
  const temporaryPath = `${filePath}.tmp`;
  await writeFile(temporaryPath, `${JSON.stringify(value, null, 2)}\n`, {
    encoding: 'utf8',
    flag: 'wx',
    mode: 0o600,
  });
  await rename(temporaryPath, filePath);
}

export async function buildEvidenceManifest(
  executionId: string,
  outputDirectory: string,
  files: Array<{ name: string; mediaType: string }>,
): Promise<{
  schema: 'reachai.managed-executor.evidence-manifest.v1';
  executionId: string;
  artifacts: EvidenceManifestEntryV1[];
}> {
  const artifacts: EvidenceManifestEntryV1[] = [];
  for (const file of files) {
    if (path.basename(file.name) !== file.name) throw new Error('Manifest artifact names must be basenames');
    const content = await readFile(path.join(outputDirectory, file.name));
    artifacts.push({
      name: file.name,
      mediaType: file.mediaType,
      bytes: content.length,
      sha256: sha256(content),
    });
  }
  return {
    schema: 'reachai.managed-executor.evidence-manifest.v1',
    executionId,
    artifacts,
  };
}

async function collectWorkspacePatch(job: LoadedManagedExecutorJob): Promise<WorkspacePatchEvidenceV1> {
  const environment = buildWorkerEnvironment();
  const gitRootResult = await runGit(['rev-parse', '--show-toplevel'], job.workspaceRoot, environment, 30_000, 64 * 1024);
  requireSuccessfulGit(gitRootResult, 'resolve repository root');
  const gitRoot = path.resolve(gitRootResult.stdout.trim());
  if (!samePath(gitRoot, job.workspaceRoot)) {
    throw new Error('workspaceRoot must be the Git repository root');
  }

  const baseResult = await runGit(['rev-parse', 'HEAD'], gitRoot, environment, 30_000, 64 * 1024);
  requireSuccessfulGit(baseResult, 'resolve base commit');
  const baseCommit = baseResult.stdout.trim();
  if (!/^[a-f0-9]{40,64}$/i.test(baseCommit)) throw new Error('Git returned an invalid base commit');

  const alternateIndex = path.join(job.outputDirectory, '.evidence-index');
  const gitEnvironment = { ...environment, GIT_INDEX_FILE: alternateIndex };
  try {
    const readTree = await runGit(['read-tree', 'HEAD'], gitRoot, gitEnvironment, 60_000, 128 * 1024);
    requireSuccessfulGit(readTree, 'initialize evidence index');
    const add = await runGit(['add', '-A', '--', '.'], gitRoot, gitEnvironment, 5 * 60_000, 256 * 1024);
    requireSuccessfulGit(add, 'snapshot workspace');
    const diff = await runGit(
      ['diff', '--cached', '--binary', '--no-ext-diff', '--no-textconv', 'HEAD', '--'],
      gitRoot,
      gitEnvironment,
      5 * 60_000,
      32 * 1024 * 1024,
    );
    requireSuccessfulGit(diff, 'generate workspace patch');
    if (diff.outputLimitExceeded) throw new Error('Workspace patch exceeds the 32 MiB limit');
    const patch = Buffer.from(diff.stdout, 'utf8');
    await writeFile(path.join(job.outputDirectory, PATCH_FILE), patch, { flag: 'wx', mode: 0o600 });
    return {
      schema: 'reachai.managed-executor.patch-evidence.v1',
      baseCommit,
      empty: patch.length === 0,
      bytes: patch.length,
      sha256: sha256(patch),
    };
  } finally {
    await rm(alternateIndex, { force: true });
    await rm(`${alternateIndex}.lock`, { force: true });
  }
}

async function runAcceptanceCommands(job: LoadedManagedExecutorJob): Promise<AcceptanceReportV1> {
  if (job.acceptanceCommands.length === 0) {
    return notRunAcceptanceReport(job.executionId);
  }
  const sanitizer = new ManagedEventSanitizer({ workspaceRoot: job.workspaceRoot, maxTextCharacters: 32_000 });
  const environment = buildWorkerEnvironment();
  const commands: AcceptanceCommandResultV1[] = [];
  for (const command of job.acceptanceCommands) {
    commands.push(await runAcceptanceCommand(command, job.workspaceRoot, environment, sanitizer));
  }
  return {
    schema: 'reachai.managed-executor.test-report.v1',
    executionId: job.executionId,
    outcome: commands.every((command) => command.outcome === 'PASSED') ? 'PASSED' : 'FAILED',
    commands,
  };
}

function notRunAcceptanceReport(executionId: string): AcceptanceReportV1 {
  return {
    schema: 'reachai.managed-executor.test-report.v1',
    executionId,
    outcome: 'NOT_RUN',
    commands: [],
  };
}

async function runAcceptanceCommand(
  command: AcceptanceCommandV1,
  workspaceRoot: string,
  environment: NodeJS.ProcessEnv,
  sanitizer: ManagedEventSanitizer,
): Promise<AcceptanceCommandResultV1> {
  try {
    const result = await runBoundedProcess(command.argv, {
      cwd: workspaceRoot,
      env: environment,
      timeoutMs: command.timeoutMs,
      maxOutputBytes: 512 * 1024,
    });
    const outcome: AcceptanceCommandOutcome = result.timedOut
      ? 'TIMED_OUT'
      : result.outputLimitExceeded
        ? 'OUTPUT_LIMIT_EXCEEDED'
        : result.exitCode === 0
          ? 'PASSED'
          : 'FAILED';
    return {
      name: command.name,
      argv: command.argv.map((part) => sanitizer.text(part, 2_000) || ''),
      outcome,
      exitCode: result.exitCode,
      durationMs: result.durationMs,
      stdout: sanitizer.text(result.stdout, 32_000),
      stderr: sanitizer.text(result.stderr, 32_000),
    };
  } catch (error) {
    return {
      name: command.name,
      argv: command.argv.map((part) => sanitizer.text(part, 2_000) || ''),
      outcome: 'ERROR',
      exitCode: null,
      durationMs: 0,
      stdout: null,
      stderr: sanitizer.text(error instanceof Error ? error.message : 'Acceptance command failed'),
    };
  }
}

async function runGit(
  args: string[],
  cwd: string,
  env: NodeJS.ProcessEnv,
  timeoutMs: number,
  maxOutputBytes: number,
) {
  return runBoundedProcess(['git', '-c', 'core.pager=cat', ...args], {
    cwd,
    env,
    timeoutMs,
    maxOutputBytes,
  });
}

function requireSuccessfulGit(result: Awaited<ReturnType<typeof runGit>>, operation: string): void {
  if (result.exitCode !== 0 || result.timedOut || result.outputLimitExceeded) {
    throw new Error(`Failed to ${operation}`);
  }
}

function sha256(content: Buffer): string {
  return createHash('sha256').update(content).digest('hex');
}

function samePath(left: string, right: string): boolean {
  const normalize = (value: string) => {
    const normalized = path.resolve(value).replaceAll('\\', '/').replace(/\/$/, '');
    return process.platform === 'win32' ? normalized.toLowerCase() : normalized;
  };
  return normalize(left) === normalize(right);
}

function isMissing(error: unknown): boolean {
  return error instanceof Error && 'code' in error && error.code === 'ENOENT';
}
