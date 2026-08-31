import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import type { ExecuteManagedJobResult } from './execute-job.js';
import type { ManagedExecutionEventV1 } from './managed-events.js';
import {
  loadRemoteWorkerConfig,
  runRemoteWorker,
  validateManagedCodexConfiguration,
  type RemoteRuntimePort,
  type RemoteWorkerConfig,
} from './remote-worker.js';
import type {
  RuntimeArtifactDescriptorV1,
  RuntimeArtifactUploadV1,
  RuntimeWorkerClaimV1,
  RuntimeWorkerMutationV1,
} from './runtime-client.js';

const executionId = 'mex-remote-test';
const objective = 'Inspect the repository and produce a tested patch.';

test('claims, streams events, uploads the five-file evidence bundle, and completes only after Runtime verification', async () => {
  await withRemoteWorkspace(async (config) => {
    const runtime = new FakeRuntime();
    const result = await runRemoteWorker(config, {
      createRuntimeClient: () => runtime,
      executeJob: async (job, options) => {
        await options.eventSink.emit(event());
        await writeSuccessfulEvidence(job.outputDirectory);
        return successfulResult();
      },
    });

    assert.equal(result.status, 'SUCCEEDED');
    assert.equal(runtime.claims, 1);
    assert.equal(runtime.events.length, 1);
    assert.deepEqual(runtime.uploadedTypes, [
      'PATCH', 'TEST_REPORT', 'EXECUTION_SUMMARY', 'EVENT_LOG', 'EVIDENCE_MANIFEST',
    ]);
    assert.equal(runtime.completion?.outcome, 'SUCCEEDED');
    assert.equal(runtime.completion?.artifacts.length, 5);
    assert.ok(runtime.heartbeats >= 1);
  });
});

test('turns a Runtime cancel command into an abort and terminal CANCELLED completion', async () => {
  await withRemoteWorkspace(async (config) => {
    const runtime = new FakeRuntime(true);
    const result = await runRemoteWorker({ ...config, monitorIntervalMs: 1_000 }, {
      createRuntimeClient: () => runtime,
      executeJob: async (_job, options) => {
        await waitForAbort(options.signal);
        return cancelledResult();
      },
    });

    assert.equal(result.status, 'CANCELLED');
    assert.equal(runtime.completion?.outcome, 'CANCELLED');
    assert.deepEqual(runtime.uploadedTypes, []);
  });
});

test('loads the execution token from a file and rejects state paths inside the workspace', async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), 'reachai-remote-config-'));
  try {
    const workspace = path.join(root, 'workspace');
    const tokenFile = path.join(root, 'token');
    await mkdir(workspace);
    await writeFile(tokenFile, `${'t'.repeat(48)}\n`, { mode: 0o600 });
    const environment = {
      REACHAI_RUNTIME_BASE_URL: 'https://runtime.internal',
      REACHAI_MANAGED_EXECUTION_ID: executionId,
      REACHAI_MANAGED_WORKER_ID: 'worker-1',
      REACHAI_MANAGED_WORKER_TOKEN_FILE: tokenFile,
      REACHAI_MANAGED_WORKSPACE_ROOT: workspace,
      REACHAI_MANAGED_STATE_DIRECTORY: path.join(root, 'state'),
      REACHAI_MANAGED_OUTPUT_DIRECTORY: path.join(root, 'output'),
    };
    const loaded = await loadRemoteWorkerConfig(environment);
    assert.equal(loaded.workerToken, 't'.repeat(48));

    await assert.rejects(
      loadRemoteWorkerConfig({
        ...environment,
        REACHAI_MANAGED_OUTPUT_DIRECTORY: path.join(workspace, 'evidence'),
      }),
      /outside the workspace/,
    );
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test('loads broker mode without reading or exposing a worker token file', async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), 'reachai-remote-broker-config-'));
  try {
    const workspace = path.join(root, 'workspace');
    const socketPath = path.join(root, 'runtime.sock');
    await mkdir(workspace);
    const loaded = await loadRemoteWorkerConfig({
      REACHAI_RUNTIME_BASE_URL: 'https://runtime.internal',
      REACHAI_MANAGED_EXECUTION_ID: executionId,
      REACHAI_MANAGED_WORKER_ID: 'worker-1',
      REACHAI_MANAGED_RUNTIME_BROKER_SOCKET: socketPath,
      REACHAI_MANAGED_WORKER_TOKEN_FILE: path.join(root, 'must-not-exist'),
      REACHAI_MANAGED_WORKSPACE_ROOT: workspace,
      REACHAI_MANAGED_STATE_DIRECTORY: path.join(root, 'state'),
      REACHAI_MANAGED_OUTPUT_DIRECTORY: path.join(root, 'output'),
    });
    assert.equal(loaded.brokerSocketPath, socketPath);
    assert.equal(loaded.workerToken, undefined);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test('accepts only one credential-free, destination-bound Model Relay config', () => {
  assert.doesNotThrow(() => validateManagedCodexConfiguration(`
model = "reachai-codex"
model_provider = "reachai-relay"
[model_providers.reachai-relay]
name = "ReachAI Model Relay"
base_url = "https://model-relay.reachai-egress.svc/v1"
wire_api = "responses"
requires_openai_auth = false
`, 'model-relay.reachai-egress.svc', 443));
  for (const forbidden of [
    'api_key = "do-not-store-this"',
    '"api_key" = "do-not-store-this"',
    'model_providers = { relay = { api_key = "do-not-store-this" } }',
    'env_key = "OPENAI_API_KEY"',
    '[mcp_servers.local]\ncommand = "node"',
    'notify = ["/workspace/hook"]',
    'sandbox_mode = "danger-full-access"',
    '[features]\nexperimental = true',
    '[model_providers.relay.http_headers]\nAuthorization = "Bearer secret"',
    `model = "reachai-codex"
model_provider = "reachai-relay"
[model_providers.reachai-relay]
name = "Wrong relay"
base_url = "https://evil.example/v1"
wire_api = "responses"
requires_openai_auth = false`,
  ]) {
    assert.throws(() => validateManagedCodexConfiguration(
      forbidden, 'model-relay.reachai-egress.svc', 443,
    ), /forbidden/);
  }
});

class FakeRuntime implements RemoteRuntimePort {
  claims = 0;
  heartbeats = 0;
  events: ManagedExecutionEventV1[] = [];
  uploadedTypes: string[] = [];
  completion: { outcome: string; artifacts: RuntimeArtifactDescriptorV1[] } | null = null;

  constructor(private readonly cancel = false) {
  }

  async claim(): Promise<RuntimeWorkerClaimV1> {
    this.claims++;
    return {
      schema: 'reachai.managed-executor.claim.v1',
      executionId,
      objective,
      objectiveSha256: sha256(Buffer.from(objective)),
      workspaceProfile: 'WORKSPACE_PATCH',
      executorProvider: 'CODEX',
      modelRef: null,
      acceptanceProfile: 'PROJECT_DEFAULT',
      maxWallTimeMs: 60_000,
      approvalTimeoutMs: 30_000,
      leaseExpiresAt: '2026-08-24T12:00:00.000Z',
    };
  }

  async heartbeat(): Promise<RuntimeWorkerMutationV1> {
    this.heartbeats++;
    return mutation(this.cancel ? 'CANCELLING' : 'RUNNING', this.cancel);
  }

  async appendEvents(events: ManagedExecutionEventV1[]): Promise<RuntimeWorkerMutationV1> {
    this.events.push(...events);
    return mutation('FINALIZING', false);
  }

  async commands(afterSequence: number) {
    return this.cancel
      ? { cursor: afterSequence + 1, commands: [{ sequence: afterSequence + 1, type: 'CANCEL' as const, data: {} }] }
      : { cursor: afterSequence, commands: [] };
  }

  async uploadArtifact(options: Parameters<RemoteRuntimePort['uploadArtifact']>[0]): Promise<RuntimeArtifactUploadV1> {
    this.uploadedTypes.push(options.artifactType);
    return {
      schema: 'reachai.managed-executor.artifact-upload.v1',
      executionId,
      artifactId: options.artifactId,
      artifactType: options.artifactType,
      objectKey: `managed-executions/${executionId}/artifacts/${options.artifactId}/file`,
      sha256: options.sha256,
      sizeBytes: options.content.byteLength,
      mediaType: options.mediaType,
      validationStatus: 'VERIFIED',
      scanStatus: 'CLEAN',
    };
  }

  async complete(
    outcome: 'SUCCEEDED' | 'FAILED' | 'CANCELLED',
    options: { errorCode?: string; errorMessage?: string; artifacts?: RuntimeArtifactDescriptorV1[] } = {},
  ): Promise<RuntimeWorkerMutationV1> {
    this.completion = { outcome, artifacts: options.artifacts || [] };
    return mutation(outcome, false);
  }
}

async function withRemoteWorkspace(run: (config: RemoteWorkerConfig) => Promise<void>): Promise<void> {
  const root = await mkdtemp(path.join(os.tmpdir(), 'reachai-remote-worker-'));
  try {
    const workspaceRoot = path.join(root, 'workspace');
    await mkdir(workspaceRoot);
    await run({
      runtimeBaseUrl: 'https://runtime.internal',
      executionId,
      workerId: 'worker-1',
      workerToken: 'token-0123456789-abcdefghijklmnopqrstuvwxyz',
      workspaceRoot,
      stateDirectory: path.join(root, 'state'),
      outputDirectory: path.join(root, 'output'),
      acceptanceCommands: [],
    });
  } finally {
    await rm(root, { recursive: true, force: true });
  }
}

async function writeSuccessfulEvidence(outputDirectory: string): Promise<void> {
  await mkdir(outputDirectory, { recursive: true });
  await Promise.all([
    writeFile(path.join(outputDirectory, 'workspace.patch'), 'diff --git a/a b/a\n'),
    writeFile(path.join(outputDirectory, 'test-report.json'), '{}\n'),
    writeFile(path.join(outputDirectory, 'execution-summary.json'), '{}\n'),
    writeFile(path.join(outputDirectory, 'events.ndjson'), '{}\n'),
    writeFile(path.join(outputDirectory, 'evidence-manifest.json'), '{}\n'),
  ]);
}

function successfulResult(): ExecuteManagedJobResult {
  return {
    exitCode: 0,
    summary: summary('SUCCEEDED'),
    workerResult: {
      schema: 'reachai.managed-executor.worker-result.v1',
      executionId,
      outcome: 'SUCCEEDED',
      verificationOutcome: 'PASSED',
    },
    artifactFiles: [
      { artifactType: 'EVENT_LOG', name: 'events.ndjson', mediaType: 'application/x-ndjson' },
      { artifactType: 'EXECUTION_SUMMARY', name: 'execution-summary.json', mediaType: 'application/json' },
      { artifactType: 'EVIDENCE_MANIFEST', name: 'evidence-manifest.json', mediaType: 'application/json' },
      { artifactType: 'PATCH', name: 'workspace.patch', mediaType: 'text/x-diff' },
      { artifactType: 'TEST_REPORT', name: 'test-report.json', mediaType: 'application/json' },
    ],
  };
}

function cancelledResult(): ExecuteManagedJobResult {
  return {
    exitCode: 1,
    summary: summary('CANCELLED'),
    workerResult: {
      schema: 'reachai.managed-executor.worker-result.v1',
      executionId,
      outcome: 'CANCELLED',
      verificationOutcome: 'NOT_RUN',
    },
    artifactFiles: [],
  };
}

function summary(outcome: 'SUCCEEDED' | 'CANCELLED'): ExecuteManagedJobResult['summary'] {
  return {
    schema: 'reachai.managed-executor.execution-summary.v1',
    executionId,
    startedAt: '2026-08-24T00:00:00.000Z',
    completedAt: '2026-08-24T00:00:01.000Z',
    outcome,
    verificationOutcome: outcome === 'SUCCEEDED' ? 'PASSED' : 'NOT_RUN',
    readonlyViolation: false,
    appServer: { threadId: null, turnId: null, status: null },
    patch: null,
    error: outcome === 'SUCCEEDED' ? null : 'Managed Executor execution cancelled',
  };
}

function event(): ManagedExecutionEventV1 {
  return {
    schema: 'reachai.managed-execution.event.v1',
    executionId,
    sequence: 1,
    eventId: `${executionId}:1`,
    occurredAt: '2026-08-24T00:00:00.000Z',
    type: 'TURN_COMPLETED',
    phase: 'SUCCEEDED',
    visibility: 'OPERATOR',
    persistence: 'DURABLE',
    message: 'Turn completed',
    data: {},
  };
}

function mutation(status: string, cancelRequested: boolean): RuntimeWorkerMutationV1 {
  return {
    executionId,
    status,
    lastEventSequence: 1,
    cancelRequested,
    leaseExpiresAt: status === 'SUCCEEDED' || status === 'CANCELLED'
      ? null : '2026-08-24T12:00:00.000Z',
  };
}

function sha256(content: Uint8Array): string {
  return createHash('sha256').update(content).digest('hex');
}

function waitForAbort(signal: AbortSignal): Promise<void> {
  if (signal.aborted) return Promise.resolve();
  return new Promise((resolve) => signal.addEventListener('abort', () => resolve(), { once: true }));
}
