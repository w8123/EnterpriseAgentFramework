import { createHash } from 'node:crypto';
import { mkdir, readFile, realpath, stat, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { RuntimeApprovalBroker } from './approval.js';
import {
  executeManagedJob,
  type ExecuteManagedJobResult,
  type ManagedJobArtifactFile,
} from './execute-job.js';
import {
  loadManagedExecutorJob,
  MANAGED_EXECUTOR_JOB_SCHEMA,
  type AcceptanceCommandV1,
  type LoadedManagedExecutorJob,
} from './job-config.js';
import type { ManagedEventSink } from './runner.js';
import {
  ManagedRuntimeClient,
  type RuntimeArtifactDescriptorV1,
  type RuntimeArtifactUploadV1,
  type RuntimeWorkerClaimV1,
  type RuntimeWorkerCommandBatchV1,
  type RuntimeWorkerMutationV1,
} from './runtime-client.js';

export interface RemoteWorkerConfig {
  runtimeBaseUrl: string;
  executionId: string;
  workerId: string;
  workerToken?: string;
  brokerSocketPath?: string;
  workspaceRoot: string;
  stateDirectory: string;
  outputDirectory: string;
  acceptanceCommands: AcceptanceCommandV1[];
  codexConfigFile?: string;
  codexHome?: string;
  modelRelayHost?: string;
  modelRelayPort?: number;
  allowPlaintextLoopback?: boolean;
  monitorIntervalMs?: number;
}

export interface RemoteRuntimePort {
  claim(): Promise<RuntimeWorkerClaimV1>;
  heartbeat(): Promise<RuntimeWorkerMutationV1>;
  appendEvents(events: Parameters<ManagedRuntimeClient['appendEvents']>[0]): Promise<RuntimeWorkerMutationV1>;
  commands(afterSequence: number): Promise<RuntimeWorkerCommandBatchV1>;
  uploadArtifact(options: Parameters<ManagedRuntimeClient['uploadArtifact']>[0]): Promise<RuntimeArtifactUploadV1>;
  complete(
    outcome: 'SUCCEEDED' | 'FAILED' | 'CANCELLED',
    options?: { errorCode?: string; errorMessage?: string; artifacts?: RuntimeArtifactDescriptorV1[] },
  ): Promise<RuntimeWorkerMutationV1>;
}

export interface RemoteWorkerDependencies {
  createRuntimeClient?: (config: RemoteWorkerConfig) => RemoteRuntimePort;
  executeJob?: (
    job: LoadedManagedExecutorJob,
    options: { eventSink: ManagedEventSink; signal: AbortSignal; approvalBroker: RuntimeApprovalBroker },
  ) => Promise<ExecuteManagedJobResult>;
}

export async function loadRemoteWorkerConfig(
  environment: NodeJS.ProcessEnv = process.env,
): Promise<RemoteWorkerConfig> {
  const runtimeBaseUrl = requiredText(environment.REACHAI_RUNTIME_BASE_URL, 'REACHAI_RUNTIME_BASE_URL', 2_048);
  const executionId = identifier(environment.REACHAI_MANAGED_EXECUTION_ID, 'REACHAI_MANAGED_EXECUTION_ID');
  const workerId = identifier(environment.REACHAI_MANAGED_WORKER_ID, 'REACHAI_MANAGED_WORKER_ID');
  const brokerSocketPath = environment.REACHAI_MANAGED_RUNTIME_BROKER_SOCKET
    ? requiredAbsolutePath(
      environment.REACHAI_MANAGED_RUNTIME_BROKER_SOCKET,
      'REACHAI_MANAGED_RUNTIME_BROKER_SOCKET',
    )
    : undefined;
  let workerToken: string | undefined;
  if (!brokerSocketPath) {
    const tokenFile = requiredAbsolutePath(
      environment.REACHAI_MANAGED_WORKER_TOKEN_FILE || '/run/reachai/worker-token/token',
      'REACHAI_MANAGED_WORKER_TOKEN_FILE',
    );
    const tokenStats = await stat(tokenFile);
    if (!tokenStats.isFile() || tokenStats.size > 512) throw new Error('Managed worker token file is invalid');
    workerToken = (await readFile(tokenFile, 'utf8')).trim();
    if (workerToken.length < 32 || workerToken.length > 256 || /\s/.test(workerToken)) {
      throw new Error('Managed worker token file is invalid');
    }
  }
  const workspaceRoot = await existingDirectory(
    environment.REACHAI_MANAGED_WORKSPACE_ROOT || '/workspace',
    'REACHAI_MANAGED_WORKSPACE_ROOT',
  );
  const stateDirectory = requiredAbsolutePath(
    environment.REACHAI_MANAGED_STATE_DIRECTORY || '/run/reachai/job',
    'REACHAI_MANAGED_STATE_DIRECTORY',
  );
  const outputDirectory = requiredAbsolutePath(
    environment.REACHAI_MANAGED_OUTPUT_DIRECTORY || '/run/reachai/output',
    'REACHAI_MANAGED_OUTPUT_DIRECTORY',
  );
  if (isSameOrDescendant(stateDirectory, workspaceRoot)
      || isSameOrDescendant(outputDirectory, workspaceRoot)) {
    throw new Error('Managed worker state and output directories must be outside the workspace');
  }
  const acceptanceCommands = await loadAcceptanceCommands(
    environment.REACHAI_MANAGED_ACCEPTANCE_COMMANDS_FILE,
  );
  const codexConfigFile = environment.REACHAI_CODEX_CONFIG_FILE
    ? requiredAbsolutePath(environment.REACHAI_CODEX_CONFIG_FILE, 'REACHAI_CODEX_CONFIG_FILE')
    : undefined;
  const codexHome = codexConfigFile
    ? requiredAbsolutePath(environment.CODEX_HOME || '', 'CODEX_HOME')
    : undefined;
  const modelRelayHost = codexConfigFile
    ? serviceHost(environment.REACHAI_MANAGED_MODEL_RELAY_HOST, 'REACHAI_MANAGED_MODEL_RELAY_HOST')
    : undefined;
  const modelRelayPort = codexConfigFile
    ? servicePort(environment.REACHAI_MANAGED_MODEL_RELAY_PORT, 'REACHAI_MANAGED_MODEL_RELAY_PORT')
    : undefined;
  if (codexHome && isSameOrDescendant(codexHome, workspaceRoot)) {
    throw new Error('CODEX_HOME must be outside the workspace');
  }
  return {
    runtimeBaseUrl,
    executionId,
    workerId,
    ...(workerToken ? { workerToken } : {}),
    ...(brokerSocketPath ? { brokerSocketPath } : {}),
    workspaceRoot,
    stateDirectory,
    outputDirectory,
    acceptanceCommands,
    ...(codexConfigFile ? { codexConfigFile } : {}),
    ...(codexHome ? { codexHome } : {}),
    ...(modelRelayHost ? { modelRelayHost } : {}),
    ...(modelRelayPort ? { modelRelayPort } : {}),
    ...(environment.REACHAI_RUNTIME_ALLOW_PLAINTEXT_LOOPBACK === 'true'
      ? { allowPlaintextLoopback: true } : {}),
  };
}

export async function runRemoteWorker(
  config: RemoteWorkerConfig,
  dependencies: RemoteWorkerDependencies = {},
): Promise<RuntimeWorkerMutationV1> {
  await prepareCodexConfiguration(config);
  const client = (dependencies.createRuntimeClient || defaultClient)(config);
  const claim = await client.claim();
  validateClaim(config, claim);
  const job = await createJob(config, claim);
  const executionAbort = new AbortController();
  const monitorAbort = new AbortController();
  const monitor = monitorRuntime(client, executionAbort, monitorAbort.signal, config.monitorIntervalMs ?? 5_000);
  let monitorStopped = false;
  const stopMonitor = async () => {
    if (monitorStopped) return;
    monitorStopped = true;
    monitorAbort.abort();
    await monitor.catch(() => undefined);
  };
  const eventSink: ManagedEventSink = {
    async emit(event) {
      await client.appendEvents([event]);
    },
  };

  try {
    const result = await (dependencies.executeJob || executeManagedJob)(job, {
      eventSink,
      signal: executionAbort.signal,
      approvalBroker: new RuntimeApprovalBroker(client),
    });

    if (executionAbort.signal.aborted || result.summary.outcome === 'CANCELLED') {
      await stopMonitor();
      return client.complete('CANCELLED', {
        errorCode: 'MANAGED_EXECUTION_CANCELLED',
        errorMessage: 'Managed Executor execution cancelled',
      });
    }
    if (result.exitCode !== 0 || result.summary.outcome !== 'SUCCEEDED') {
      await stopMonitor();
      return client.complete('FAILED', {
        errorCode: 'MANAGED_EXECUTION_WORKER_FAILED',
        errorMessage: 'Managed Executor worker failed',
      });
    }

    const artifacts = await uploadEvidence(client, config, result.artifactFiles, executionAbort.signal);
    await stopMonitor();
    if (executionAbort.signal.aborted) {
      return client.complete('CANCELLED', {
        errorCode: 'MANAGED_EXECUTION_CANCELLED',
        errorMessage: 'Managed Executor execution cancelled',
      });
    }
    const completed = await client.complete('SUCCEEDED', { artifacts });
    if (completed.status !== 'SUCCEEDED') {
      throw new Error('Runtime did not independently finalize Managed Executor evidence');
    }
    return completed;
  } finally {
    await stopMonitor();
  }
}

async function prepareCodexConfiguration(config: RemoteWorkerConfig): Promise<void> {
  if (!config.codexConfigFile || !config.codexHome) return;
  if (!config.modelRelayHost || !config.modelRelayPort) {
    throw new Error('Managed Model Relay policy is required');
  }
  const source = await realpath(config.codexConfigFile);
  const sourceStats = await stat(source);
  if (!sourceStats.isFile() || sourceStats.size > 256 * 1024) {
    throw new Error('Managed Codex configuration file is invalid');
  }
  const content = await readFile(source, 'utf8');
  if (content.includes('\0')) throw new Error('Managed Codex configuration file is invalid');
  validateManagedCodexConfiguration(content, config.modelRelayHost, config.modelRelayPort);
  await mkdir(config.codexHome, { recursive: true, mode: 0o700 });
  await writeFile(path.join(config.codexHome, 'config.toml'), content, {
    encoding: 'utf8',
    flag: 'wx',
    mode: 0o600,
  });
}

/**
 * Managed configuration is deliberately narrower than the general Codex CLI
 * configuration surface. Network destinations remain enforced by Kubernetes,
 * while this check prevents credentials, local extension processes, and policy
 * overrides from entering the untrusted Codex process through config.toml.
 */
export function validateManagedCodexConfiguration(
  content: string,
  expectedRelayHost: string,
  expectedRelayPort: number,
): void {
  if (Buffer.byteLength(content, 'utf8') > 256 * 1024 || content.includes('\0')) {
    throw new Error('Managed Codex configuration file is invalid');
  }
  const relayHost = serviceHost(expectedRelayHost, 'expectedRelayHost');
  const relayPort = boundedPort(expectedRelayPort, 'expectedRelayPort');
  const rootValues = new Map<string, string>();
  const providerValues = new Map<string, string>();
  let providerTable: string | undefined;
  let inProviderTable = false;
  for (const rawLine of content.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith('#')) continue;
    if (line.startsWith('[')) {
      const table = /^\[model_providers\.([A-Za-z0-9][A-Za-z0-9_-]{0,63})\]$/.exec(line);
      if (!table || providerTable) forbiddenManagedCodexConfiguration();
      providerTable = table[1];
      inProviderTable = true;
      continue;
    }
    const assignment = /^([A-Za-z][A-Za-z0-9_]*)\s*=\s*(.+)$/.exec(line);
    if (!assignment) forbiddenManagedCodexConfiguration();
    const rawKey = assignment[1];
    const rawValue = assignment[2];
    if (!rawKey || !rawValue) forbiddenManagedCodexConfiguration();
    const key = rawKey.toLowerCase();
    const values = inProviderTable ? providerValues : rootValues;
    const allowed = inProviderTable
      ? new Set(['name', 'base_url', 'wire_api', 'requires_openai_auth'])
      : new Set(['model', 'model_provider']);
    if (!allowed.has(key) || values.has(key)) forbiddenManagedCodexConfiguration();
    values.set(key, rawValue);
  }
  if (rootValues.size !== 2 || providerValues.size !== 4 || !providerTable) {
    forbiddenManagedCodexConfiguration();
  }
  const model = managedIdentifierString(rootValues.get('model'));
  const selectedProvider = managedIdentifierString(rootValues.get('model_provider'));
  const providerName = managedDisplayString(providerValues.get('name'));
  const baseUrl = managedUrlString(providerValues.get('base_url'));
  const wireApi = managedIdentifierString(providerValues.get('wire_api'));
  if (!model || !providerName || selectedProvider !== providerTable
      || wireApi !== 'responses' || providerValues.get('requires_openai_auth') !== 'false') {
    forbiddenManagedCodexConfiguration();
  }
  let relay: URL;
  try {
    relay = new URL(baseUrl);
  } catch {
    forbiddenManagedCodexConfiguration();
  }
  const effectivePort = relay.port ? Number(relay.port) : 443;
  if (relay.protocol !== 'https:' || relay.username || relay.password || relay.search || relay.hash
      || relay.hostname.toLowerCase() !== relayHost || effectivePort !== relayPort) {
    forbiddenManagedCodexConfiguration();
  }
}

function managedIdentifierString(raw: string | undefined): string {
  const value = managedBasicString(raw, 128);
  if (!/^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/.test(value)) {
    forbiddenManagedCodexConfiguration();
  }
  return value;
}

function managedDisplayString(raw: string | undefined): string {
  const value = managedBasicString(raw, 128);
  for (const character of value) {
    const code = character.codePointAt(0) ?? 0;
    if (code < 0x20 || character === '"' || character === '\\') {
      forbiddenManagedCodexConfiguration();
    }
  }
  return value;
}

function managedUrlString(raw: string | undefined): string {
  const value = managedBasicString(raw, 2_048);
  if (/\s|["\\]/.test(value)) forbiddenManagedCodexConfiguration();
  return value;
}

function managedBasicString(raw: string | undefined, maximum: number): string {
  if (!raw || raw.length < 3 || raw.length > maximum + 2
      || !raw.startsWith('"') || !raw.endsWith('"')) {
    forbiddenManagedCodexConfiguration();
  }
  const value = raw.slice(1, -1);
  if (!value || value.includes('"') || value.includes('\\')) forbiddenManagedCodexConfiguration();
  return value;
}

function forbiddenManagedCodexConfiguration(): never {
  throw new Error('Managed Codex configuration contains a forbidden or unsupported surface');
}

async function createJob(config: RemoteWorkerConfig, claim: RuntimeWorkerClaimV1): Promise<LoadedManagedExecutorJob> {
  await mkdir(config.stateDirectory, { recursive: true, mode: 0o700 });
  const objectiveFile = path.join(config.stateDirectory, 'objective.txt');
  const configFile = path.join(config.stateDirectory, 'job.json');
  await writeFile(objectiveFile, claim.objective, { encoding: 'utf8', flag: 'wx', mode: 0o600 });
  await writeFile(configFile, `${JSON.stringify({
    schema: MANAGED_EXECUTOR_JOB_SCHEMA,
    executionId: config.executionId,
    workspaceRoot: config.workspaceRoot,
    objectiveFile,
    outputDirectory: config.outputDirectory,
    sandboxProfile: claim.workspaceProfile,
    acceptanceCommands: config.acceptanceCommands,
    ...(claim.modelRef ? { model: claim.modelRef } : {}),
    maxWallTimeMs: claim.maxWallTimeMs,
    approvalTimeoutMs: claim.approvalTimeoutMs,
  }, null, 2)}\n`, { encoding: 'utf8', flag: 'wx', mode: 0o600 });
  return loadManagedExecutorJob(configFile);
}

async function monitorRuntime(
  client: RemoteRuntimePort,
  executionAbort: AbortController,
  monitorSignal: AbortSignal,
  intervalMs: number,
): Promise<void> {
  let cursor = 0;
  const delayMs = Math.min(30_000, Math.max(1_000, Math.trunc(intervalMs)));
  while (!monitorSignal.aborted && !executionAbort.signal.aborted) {
    try {
      const heartbeat = await client.heartbeat();
      if (heartbeat.cancelRequested) {
        executionAbort.abort(new Error('Managed Executor cancellation requested'));
        return;
      }
      const batch = await client.commands(cursor);
      cursor = Math.max(cursor, batch.cursor);
      if (batch.commands.some((command) => command.type === 'CANCEL')) {
        executionAbort.abort(new Error('Managed Executor cancellation requested'));
        return;
      }
    } catch {
      executionAbort.abort(new Error('Managed Executor Runtime monitor failed'));
      return;
    }
    await abortableDelay(delayMs, monitorSignal);
  }
}

async function uploadEvidence(
  client: RemoteRuntimePort,
  config: RemoteWorkerConfig,
  artifactFiles: ManagedJobArtifactFile[],
  signal: AbortSignal,
): Promise<RuntimeArtifactDescriptorV1[]> {
  const byType = new Map(artifactFiles.map((artifact) => [artifact.artifactType, artifact]));
  const requiredOrder: ManagedJobArtifactFile['artifactType'][] = [
    'PATCH', 'TEST_REPORT', 'EXECUTION_SUMMARY', 'EVENT_LOG', 'EVIDENCE_MANIFEST',
  ];
  if (byType.size !== requiredOrder.length || requiredOrder.some((type) => !byType.has(type))) {
    throw new Error('Managed Executor success evidence bundle is incomplete');
  }
  const uploaded: RuntimeArtifactDescriptorV1[] = [];
  for (const type of requiredOrder) {
    if (signal.aborted) break;
    const artifact = byType.get(type);
    if (!artifact) throw new Error('Managed Executor success evidence bundle is incomplete');
    const filePath = path.join(config.outputDirectory, artifact.name);
    const resolved = await realpath(filePath);
    if (!isSameOrDescendant(resolved, config.outputDirectory)) {
      throw new Error('Managed Executor artifact path is outside the output directory');
    }
    const fileStats = await stat(resolved);
    if (!fileStats.isFile() || fileStats.size > 64 * 1024 * 1024) {
      throw new Error('Managed Executor artifact file is invalid');
    }
    const content = await readFile(resolved);
    const digest = sha256(content);
    const response = await client.uploadArtifact({
      artifactId: `artifact-${type.toLowerCase().replaceAll('_', '-')}`,
      artifactType: type,
      sha256: digest,
      mediaType: artifact.mediaType,
      content,
    });
    if (response.executionId !== config.executionId
        || response.artifactType !== type
        || response.sha256 !== digest
        || response.sizeBytes !== content.length
        || response.mediaType !== artifact.mediaType
        || response.validationStatus !== 'VERIFIED'
        || response.scanStatus !== 'CLEAN') {
      throw new Error('Runtime returned inconsistent Managed Executor artifact metadata');
    }
    uploaded.push({
      artifactId: response.artifactId,
      artifactType: response.artifactType,
      objectKey: response.objectKey,
      sha256: response.sha256,
      sizeBytes: response.sizeBytes,
      mediaType: response.mediaType,
    });
    if (signal.aborted) break;
  }
  return uploaded;
}

function validateClaim(config: RemoteWorkerConfig, claim: RuntimeWorkerClaimV1): void {
  if (claim.schema !== 'reachai.managed-executor.claim.v1'
      || claim.executionId !== config.executionId
      || sha256(Buffer.from(claim.objective, 'utf8')) !== claim.objectiveSha256
      || !['ANALYZE_READONLY', 'WORKSPACE_PATCH'].includes(claim.workspaceProfile)
      || claim.executorProvider !== 'CODEX') {
    throw new Error('Runtime returned an invalid Managed Executor claim');
  }
}

function defaultClient(config: RemoteWorkerConfig): ManagedRuntimeClient {
  return new ManagedRuntimeClient({
    baseUrl: config.runtimeBaseUrl,
    executionId: config.executionId,
    workerId: config.workerId,
    ...(config.workerToken ? { workerToken: config.workerToken } : {}),
    ...(config.brokerSocketPath ? { brokerSocketPath: config.brokerSocketPath } : {}),
    ...(config.allowPlaintextLoopback ? { allowPlaintextLoopback: true } : {}),
  });
}

async function loadAcceptanceCommands(filePath: string | undefined): Promise<AcceptanceCommandV1[]> {
  if (!filePath) return [];
  const resolved = requiredAbsolutePath(filePath, 'REACHAI_MANAGED_ACCEPTANCE_COMMANDS_FILE');
  const fileStats = await stat(resolved);
  if (!fileStats.isFile() || fileStats.size > 64 * 1024) {
    throw new Error('Managed acceptance commands file is invalid');
  }
  let value: unknown;
  try {
    value = JSON.parse(await readFile(resolved, 'utf8'));
  } catch {
    throw new Error('Managed acceptance commands file is invalid');
  }
  if (!Array.isArray(value)) throw new Error('Managed acceptance commands file is invalid');
  return value as AcceptanceCommandV1[];
}

async function existingDirectory(value: string, label: string): Promise<string> {
  const resolved = await realpath(requiredAbsolutePath(value, label));
  if (!(await stat(resolved)).isDirectory()) throw new Error(`${label} must be a directory`);
  return resolved;
}

function requiredAbsolutePath(value: string, label: string): string {
  const text = requiredText(value, label, 4_096);
  if (!path.isAbsolute(text)) throw new Error(`${label} must be absolute`);
  return path.normalize(text);
}

function requiredText(value: string | undefined, label: string, maximum: number): string {
  if (!value || !value.trim() || value.includes('\0') || value.length > maximum) {
    throw new Error(`${label} is invalid`);
  }
  return value.trim();
}

function identifier(value: string | undefined, label: string): string {
  const result = requiredText(value, label, 128);
  if (!/^[A-Za-z0-9._:-]+$/.test(result)) throw new Error(`${label} is invalid`);
  return result;
}

function serviceHost(value: string | undefined, label: string): string {
  const host = requiredText(value, label, 253).toLowerCase();
  if (host.endsWith('.') || host.includes('..')) throw new Error(`${label} is invalid`);
  for (const part of host.split('.')) {
    if (!/^[a-z0-9]([-a-z0-9]*[a-z0-9])?$/.test(part) || part.length > 63) {
      throw new Error(`${label} is invalid`);
    }
  }
  return host;
}

function servicePort(value: string | undefined, label: string): number {
  if (!value || !/^\d{1,5}$/.test(value)) throw new Error(`${label} is invalid`);
  return boundedPort(Number(value), label);
}

function boundedPort(value: number, label: string): number {
  if (!Number.isInteger(value) || value < 1 || value > 65_535) {
    throw new Error(`${label} is invalid`);
  }
  return value;
}

function isSameOrDescendant(candidate: string, root: string): boolean {
  const relative = path.relative(path.resolve(root), path.resolve(candidate));
  return relative === '' || (!relative.startsWith('..') && !path.isAbsolute(relative));
}

function sha256(content: Uint8Array): string {
  return createHash('sha256').update(content).digest('hex');
}

function abortableDelay(milliseconds: number, signal: AbortSignal): Promise<void> {
  if (signal.aborted) return Promise.resolve();
  return new Promise((resolve) => {
    const timer = setTimeout(done, milliseconds);
    const onAbort = () => done();
    function done() {
      clearTimeout(timer);
      signal.removeEventListener('abort', onAbort);
      resolve();
    }
    signal.addEventListener('abort', onAbort, { once: true });
  });
}
