import { readFile, realpath, stat } from 'node:fs/promises';
import path from 'node:path';
import { isRecord } from './protocol.js';
import type { ManagedSandboxProfile } from './runner.js';

export const MANAGED_EXECUTOR_JOB_SCHEMA = 'reachai.managed-executor.job.v1' as const;

export interface AcceptanceCommandV1 {
  name: string;
  argv: string[];
  timeoutMs: number;
}

export interface ManagedExecutorJobConfigV1 {
  schema: typeof MANAGED_EXECUTOR_JOB_SCHEMA;
  executionId: string;
  workspaceRoot: string;
  objectiveFile: string;
  outputDirectory: string;
  sandboxProfile: ManagedSandboxProfile;
  acceptanceCommands: AcceptanceCommandV1[];
  model?: string;
  codexBinary?: string;
  maxWallTimeMs: number;
  approvalTimeoutMs: number;
}

export interface LoadedManagedExecutorJob extends ManagedExecutorJobConfigV1 {
  configPath: string;
  objective: string;
}

const MAX_CONFIG_BYTES = 256 * 1024;
const MAX_OBJECTIVE_BYTES = 128 * 1024;

export async function loadManagedExecutorJob(configPathInput: string): Promise<LoadedManagedExecutorJob> {
  if (!path.isAbsolute(configPathInput)) throw new Error('Job config path must be absolute');
  const configPath = await realpath(configPathInput);
  const configStats = await stat(configPath);
  if (!configStats.isFile() || configStats.size > MAX_CONFIG_BYTES) {
    throw new Error('Job config must be a regular file no larger than 256 KiB');
  }

  let raw: unknown;
  try {
    raw = JSON.parse(await readFile(configPath, 'utf8'));
  } catch {
    throw new Error('Job config is not valid UTF-8 JSON');
  }
  if (!isRecord(raw)) throw new Error('Job config must be a JSON object');
  rejectUnknownKeys(raw, new Set([
    'schema',
    'executionId',
    'workspaceRoot',
    'objectiveFile',
    'outputDirectory',
    'sandboxProfile',
    'acceptanceCommands',
    'model',
    'codexBinary',
    'maxWallTimeMs',
    'approvalTimeoutMs',
  ]), 'job config');

  if (raw.schema !== MANAGED_EXECUTOR_JOB_SCHEMA) {
    throw new Error('Unsupported job schema');
  }
  const executionId = requiredText(raw.executionId, 'executionId', 128);
  if (!/^[A-Za-z0-9][A-Za-z0-9._:-]*$/.test(executionId)) {
    throw new Error('executionId contains unsupported characters');
  }

  const workspaceRootInput = requiredAbsolutePath(raw.workspaceRoot, 'workspaceRoot');
  const workspaceRoot = await realpath(workspaceRootInput);
  if (!(await stat(workspaceRoot)).isDirectory()) throw new Error('workspaceRoot must be a directory');

  const objectiveFileInput = requiredAbsolutePath(raw.objectiveFile, 'objectiveFile');
  const objectiveFile = await realpath(objectiveFileInput);
  const objectiveStats = await stat(objectiveFile);
  if (!objectiveStats.isFile() || objectiveStats.size > MAX_OBJECTIVE_BYTES) {
    throw new Error('objectiveFile must be a regular file no larger than 128 KiB');
  }
  const objective = await readFile(objectiveFile, 'utf8');
  if (!objective.trim()) throw new Error('objectiveFile must not be empty');

  const outputDirectory = path.normalize(requiredAbsolutePath(raw.outputDirectory, 'outputDirectory'));
  if (isSameOrDescendant(outputDirectory, workspaceRoot)) {
    throw new Error('outputDirectory must be outside workspaceRoot');
  }

  const sandboxProfile = requiredText(raw.sandboxProfile, 'sandboxProfile', 64);
  if (sandboxProfile !== 'ANALYZE_READONLY' && sandboxProfile !== 'WORKSPACE_PATCH') {
    throw new Error('Unsupported sandboxProfile');
  }

  const acceptanceCommands = parseAcceptanceCommands(raw.acceptanceCommands);
  const model = optionalText(raw.model, 'model', 256);
  const codexBinary = optionalText(raw.codexBinary, 'codexBinary', 1_024);

  return {
    schema: MANAGED_EXECUTOR_JOB_SCHEMA,
    configPath,
    executionId,
    workspaceRoot,
    objectiveFile,
    outputDirectory,
    sandboxProfile,
    acceptanceCommands,
    maxWallTimeMs: boundedInteger(raw.maxWallTimeMs, 'maxWallTimeMs', 30 * 60_000, 1_000, 4 * 60 * 60_000),
    approvalTimeoutMs: boundedInteger(raw.approvalTimeoutMs, 'approvalTimeoutMs', 10 * 60_000, 1_000, 30 * 60_000),
    objective,
    ...(model ? { model } : {}),
    ...(codexBinary ? { codexBinary } : {}),
  };
}

function parseAcceptanceCommands(value: unknown): AcceptanceCommandV1[] {
  if (value === undefined) return [];
  if (!Array.isArray(value) || value.length > 20) {
    throw new Error('acceptanceCommands must be an array with at most 20 entries');
  }
  const names = new Set<string>();
  return value.map((candidate, index) => {
    if (!isRecord(candidate)) throw new Error(`acceptanceCommands[${index}] must be an object`);
    rejectUnknownKeys(candidate, new Set(['name', 'argv', 'timeoutMs']), `acceptanceCommands[${index}]`);
    const name = requiredText(candidate.name, `acceptanceCommands[${index}].name`, 128);
    if (names.has(name)) throw new Error('Acceptance command names must be unique');
    names.add(name);
    if (!Array.isArray(candidate.argv) || candidate.argv.length < 1 || candidate.argv.length > 64) {
      throw new Error(`acceptanceCommands[${index}].argv must contain 1 to 64 arguments`);
    }
    const argv = candidate.argv.map((part, partIndex) => requiredText(
      part,
      `acceptanceCommands[${index}].argv[${partIndex}]`,
      2_000,
    ));
    return {
      name,
      argv,
      timeoutMs: boundedInteger(candidate.timeoutMs, `acceptanceCommands[${index}].timeoutMs`, 10 * 60_000, 1_000, 60 * 60_000),
    };
  });
}

function rejectUnknownKeys(value: Record<string, unknown>, allowed: Set<string>, label: string): void {
  const unknown = Object.keys(value).filter((key) => !allowed.has(key));
  if (unknown.length > 0) throw new Error(`${label} contains unknown fields`);
}

function requiredAbsolutePath(value: unknown, label: string): string {
  const result = requiredText(value, label, 4_096);
  if (!path.isAbsolute(result)) throw new Error(`${label} must be absolute`);
  return path.normalize(result);
}

function requiredText(value: unknown, label: string, maxLength: number): string {
  if (typeof value !== 'string' || !value.trim()) throw new Error(`${label} is required`);
  if (value.includes('\0') || Array.from(value).length > maxLength) throw new Error(`${label} is too long or invalid`);
  return value.trim();
}

function optionalText(value: unknown, label: string, maxLength: number): string | undefined {
  if (value === undefined || value === null || value === '') return undefined;
  return requiredText(value, label, maxLength);
}

function boundedInteger(
  value: unknown,
  label: string,
  fallback: number,
  minimum: number,
  maximum: number,
): number {
  if (value === undefined) return fallback;
  if (typeof value !== 'number' || !Number.isInteger(value) || value < minimum || value > maximum) {
    throw new Error(`${label} must be an integer between ${minimum} and ${maximum}`);
  }
  return value;
}

function isSameOrDescendant(candidate: string, parent: string): boolean {
  const relative = path.relative(parent, candidate);
  return relative === '' || (!relative.startsWith('..') && !path.isAbsolute(relative));
}
