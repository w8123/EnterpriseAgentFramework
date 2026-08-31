import assert from 'node:assert/strict';
import { createServer, type IncomingMessage, type ServerResponse } from 'node:http';
import test from 'node:test';
import { createHash } from 'node:crypto';
import { mkdtemp, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import type { ManagedExecutionEventV1 } from './managed-events.js';
import {
  ManagedRuntimeClient,
  ManagedRuntimeHttpError,
  type RuntimeWorkerMutationV1,
} from './runtime-client.js';

const executionId = 'mex-runtime-client';
const workerId = 'worker-1';
const workerToken = 'worker-token-0123456789-abcdefghij-XYZ';

test('uses the execution-scoped bearer token for the complete Runtime worker lifecycle', async () => {
  const requests: Array<{ method: string; url: string; body: unknown }> = [];
  await withServer(async (request, response) => {
    assert.equal(request.headers.authorization, `Bearer ${workerToken}`);
    assert.equal(request.headers['x-reachai-worker-id'], workerId);
    const body = await readJsonBody(request);
    requests.push({ method: request.method || '', url: request.url || '', body });

    if (request.url?.endsWith(':claim')) {
      return json(response, 200, {
        schema: 'reachai.managed-executor.claim.v1',
        executionId,
        objective: 'Inspect and patch the repository.',
        objectiveSha256: 'a'.repeat(64),
        workspaceProfile: 'WORKSPACE_PATCH',
        executorProvider: 'CODEX',
        modelRef: null,
        acceptanceProfile: 'default',
        maxWallTimeMs: 300_000,
        approvalTimeoutMs: 30_000,
        leaseExpiresAt: '2026-08-24T12:00:00.000Z',
      });
    }
    if (request.url?.endsWith(':heartbeat')) return mutation(response, 1);
    if (request.url?.includes('/events:batch')) return mutation(response, 1);
    if (request.url?.includes('/commands?afterSequence=0')) {
      return json(response, 200, { cursor: 1, commands: [{ sequence: 1, type: 'CANCEL', data: {} }] });
    }
    if (request.url?.includes('/artifacts/PATCH')) {
      const uploaded = Buffer.from((body as { raw: string }).raw, 'base64');
      return json(response, 200, {
        schema: 'reachai.managed-executor.artifact-upload.v1',
        executionId,
        artifactId: request.headers['x-reachai-artifact-id'],
        artifactType: 'PATCH',
        objectKey: `managed-executions/${executionId}/artifacts/artifact-patch/workspace.patch`,
        sha256: request.headers['x-reachai-artifact-sha256'],
        sizeBytes: uploaded.length,
        mediaType: request.headers['content-type'],
        validationStatus: 'VERIFIED',
        scanStatus: 'CLEAN',
      });
    }
    if (request.url?.endsWith(':complete')) return mutation(response, 1, 'FINALIZING');
    return json(response, 404, { code: 'NOT_FOUND' });
  }, async (baseUrl) => {
    const client = runtimeClient(baseUrl, true);
    const claim = await client.claim();
    assert.equal(claim.executionId, executionId);
    await client.heartbeat();
    await client.appendEvents([event()]);
    const commands = await client.commands(0);
    assert.equal(commands.commands[0]?.type, 'CANCEL');
    const patch = Buffer.from('diff --git a/a b/a\n', 'utf8');
    const uploaded = await client.uploadArtifact({
      artifactId: 'artifact-patch',
      artifactType: 'PATCH',
      sha256: createHash('sha256').update(patch).digest('hex'),
      mediaType: 'text/x-diff',
      content: patch,
    });
    assert.equal(uploaded.validationStatus, 'VERIFIED');
    const completed = await client.complete('SUCCEEDED', { artifacts: [] });
    assert.equal(completed.status, 'FINALIZING');
    assert.match(client.toString(), /auth=<redacted>/);
    assert.doesNotMatch(client.toString(), new RegExp(workerToken));
  });

  assert.equal(requests.length, 6);
  assert.deepEqual(requests[2]?.body, { events: [event()] });
  assert.deepEqual(requests[5]?.body, { outcome: 'SUCCEEDED', artifacts: [] });
});

test('uses a Unix broker socket without placing a bearer token in the worker request', {
  skip: process.platform === 'win32' ? 'Unix domain sockets are exercised in Linux CI and Kubernetes' : false,
}, async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), 'reachai-runtime-client-socket-'));
  const socketPath = path.join(root, 'runtime.sock');
  const server = createServer((request, response) => {
    assert.equal(request.url, `/internal/runtime/managed-worker/executions/${executionId}:claim`);
    assert.equal(request.headers.authorization, undefined);
    assert.equal(request.headers['x-reachai-worker-id'], undefined);
    json(response, 200, {
      schema: 'reachai.managed-executor.claim.v1',
      executionId,
      objective: 'Inspect the repository.',
      objectiveSha256: 'a'.repeat(64),
      workspaceProfile: 'ANALYZE_READONLY',
      executorProvider: 'CODEX',
      modelRef: null,
      acceptanceProfile: 'PROJECT_DEFAULT',
      maxWallTimeMs: 300_000,
      approvalTimeoutMs: 30_000,
      leaseExpiresAt: '2026-08-24T12:00:00.000Z',
    });
  });
  try {
    await new Promise<void>((resolve, reject) => {
      server.once('error', reject);
      server.listen(socketPath, resolve);
    });
    const client = new ManagedRuntimeClient({
      baseUrl: 'https://runtime.internal',
      executionId,
      workerId,
      brokerSocketPath: socketPath,
    });
    assert.equal((await client.claim()).executionId, executionId);
  } finally {
    server.closeAllConnections();
    await new Promise<void>((resolve) => server.close(() => resolve()));
    await rm(root, { recursive: true, force: true });
  }
});

test('requires exactly one Runtime authentication transport', () => {
  assert.throws(() => new ManagedRuntimeClient({
    baseUrl: 'https://runtime.internal', executionId, workerId,
  }), /Exactly one/);
  assert.throws(() => new ManagedRuntimeClient({
    baseUrl: 'https://runtime.internal', executionId, workerId,
    workerToken, brokerSocketPath: '/run/reachai/broker/runtime.sock',
  }), /Exactly one/);
});

test('requires HTTPS except for an explicitly enabled loopback test endpoint', () => {
  assert.throws(() => runtimeClient('http://example.com'), /must use HTTPS/);
  assert.throws(() => runtimeClient('http://127.0.0.1:18080'), /must use HTTPS/);
  assert.doesNotThrow(() => runtimeClient('http://127.0.0.1:18080', true));
  assert.throws(() => runtimeClient('https://user:password@example.com'), /forbidden components/);
});

test('does not follow redirects with an execution bearer token', async () => {
  await withServer((_request, response) => {
    response.writeHead(307, { location: 'https://attacker.invalid/steal-token' });
    response.end();
  }, async (baseUrl) => {
    await assertRuntimeError(runtimeClient(baseUrl, true).claim(), 307, 'MANAGED_RUNTIME_REDIRECT_REJECTED');
  });
});

test('does not echo an untrusted Runtime error body or worker token', async () => {
  await withServer((_request, response) => {
    json(response, 401, {
      code: 'AUTH_FAILED',
      message: `server reflected Authorization: Bearer ${workerToken}`,
    });
  }, async (baseUrl) => {
    const error = await captureRuntimeError(runtimeClient(baseUrl, true).claim());
    assert.equal(error.status, 401);
    assert.equal(error.code, 'AUTH_FAILED');
    assert.doesNotMatch(error.message, new RegExp(workerToken));
    assert.doesNotMatch(error.message, /server reflected|Authorization/);
  });
});

test('rejects declared and streamed response bodies over the configured limit', async () => {
  await withServer((_request, response) => {
    response.writeHead(200, { 'content-type': 'application/json', 'content-length': '5000' });
    response.end('{}');
  }, async (baseUrl) => {
    await assertRuntimeError(
      runtimeClient(baseUrl, true, { maxResponseBytes: 4_096 }).claim(),
      200,
      'MANAGED_RUNTIME_RESPONSE_TOO_LARGE',
    );
  });

  await withServer((_request, response) => {
    response.writeHead(200, { 'content-type': 'application/json' });
    response.end(JSON.stringify({ padding: 'x'.repeat(5_000) }));
  }, async (baseUrl) => {
    await assertRuntimeError(
      runtimeClient(baseUrl, true, { maxResponseBytes: 4_096 }).claim(),
      200,
      'MANAGED_RUNTIME_RESPONSE_TOO_LARGE',
    );
  });
});

test('times out while a Runtime response body is stalled', async () => {
  await withServer((_request, response) => {
    response.writeHead(200, { 'content-type': 'application/json' });
    response.write('{"schema":');
  }, async (baseUrl) => {
    const startedAt = Date.now();
    await assertRuntimeError(
      runtimeClient(baseUrl, true, { requestTimeoutMs: 1_000 }).claim(),
      0,
      'MANAGED_RUNTIME_REQUEST_TIMEOUT',
    );
    assert.ok(Date.now() - startedAt < 3_000);
  });
});

function runtimeClient(
  baseUrl: string,
  allowPlaintextLoopback = false,
  overrides: { requestTimeoutMs?: number; maxResponseBytes?: number } = {},
): ManagedRuntimeClient {
  return new ManagedRuntimeClient({
    baseUrl,
    executionId,
    workerId,
    workerToken,
    allowPlaintextLoopback,
    ...overrides,
  });
}

function event(): ManagedExecutionEventV1 {
  return {
    schema: 'reachai.managed-execution.event.v1',
    executionId,
    sequence: 1,
    eventId: `${executionId}:1`,
    occurredAt: '2026-08-24T00:00:00.000Z',
    type: 'TURN_STARTED',
    phase: 'RUNNING',
    visibility: 'OPERATOR',
    persistence: 'DURABLE',
    message: 'Codex turn started',
    data: {},
  };
}

function mutation(response: ServerResponse, lastEventSequence: number, status = 'RUNNING'): void {
  const body: RuntimeWorkerMutationV1 = {
    executionId,
    status,
    lastEventSequence,
    cancelRequested: false,
    leaseExpiresAt: '2026-08-24T12:00:00.000Z',
  };
  json(response, 200, body);
}

function json(response: ServerResponse, status: number, body: unknown): void {
  response.writeHead(status, { 'content-type': 'application/json' });
  response.end(JSON.stringify(body));
}

async function readJsonBody(request: IncomingMessage): Promise<unknown> {
  const chunks: Buffer[] = [];
  for await (const chunk of request) chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk));
  if (chunks.length === 0) return null;
  const content = Buffer.concat(chunks);
  if (request.headers['content-type'] !== 'application/json') {
    return { raw: content.toString('base64') };
  }
  return JSON.parse(content.toString('utf8')) as unknown;
}

async function withServer(
  handler: (request: IncomingMessage, response: ServerResponse) => void | Promise<void>,
  run: (baseUrl: string) => void | Promise<void>,
): Promise<void> {
  const server = createServer((request, response) => {
    Promise.resolve(handler(request, response)).catch(() => {
      if (!response.headersSent) response.writeHead(500, { 'content-type': 'application/json' });
      response.end(JSON.stringify({ code: 'TEST_SERVER_FAILED' }));
    });
  });
  await new Promise<void>((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  const address = server.address();
  assert.ok(address && typeof address !== 'string');
  try {
    await run(`http://127.0.0.1:${address.port}`);
  } finally {
    server.closeAllConnections();
    await new Promise<void>((resolve, reject) => {
      server.close((error) => error ? reject(error) : resolve());
    });
  }
}

async function captureRuntimeError(promise: Promise<unknown>): Promise<ManagedRuntimeHttpError> {
  try {
    await promise;
  } catch (error) {
    assert.ok(error instanceof ManagedRuntimeHttpError);
    return error;
  }
  assert.fail('Expected ManagedRuntimeHttpError');
}

async function assertRuntimeError(promise: Promise<unknown>, status: number, code: string): Promise<void> {
  const error = await captureRuntimeError(promise);
  assert.equal(error.status, status);
  assert.equal(error.code, code);
}
