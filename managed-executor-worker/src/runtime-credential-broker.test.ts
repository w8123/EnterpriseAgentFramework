import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtemp, rm } from 'node:fs/promises';
import { createServer, request as httpRequest, type IncomingMessage, type ServerResponse } from 'node:http';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { ManagedRuntimeClient } from './runtime-client.js';
import { BrokerAdmissionGate, startRuntimeCredentialBroker } from './runtime-credential-broker.js';

const executionId = 'mex-broker-test';
const workerId = 'worker-broker-1';
const workerToken = 'broker-token-0123456789-abcdefghijklmnopqrstuvwxyz';

test('credential broker exposes only execution-scoped Runtime routes and keeps the token out of the worker', {
  skip: process.platform === 'win32' ? 'Unix credential broker is exercised in Linux CI and Kubernetes' : false,
}, async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), 'reachai-runtime-broker-'));
  const socketPath = path.join(root, 'runtime.sock');
  let upstreamRequests = 0;
  const runtime = createServer(async (request, response) => {
    upstreamRequests++;
    assert.equal(request.headers.authorization, `Bearer ${workerToken}`);
    assert.equal(request.headers['x-reachai-worker-id'], workerId);
    const body = await readBody(request);
    if (request.url?.endsWith(':claim')) {
      assert.equal(body.length, 0);
      return json(response, 200, {
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
    }
    if (request.url?.endsWith(':heartbeat')) return mutation(response, 'RUNNING');
    if (request.url?.includes('/events:batch')) return mutation(response, 'RUNNING');
    if (request.url?.includes('/commands?afterSequence=0')) {
      return json(response, 200, { cursor: 0, commands: [] });
    }
    if (request.url?.includes('/artifacts/PATCH')) {
      return json(response, 200, {
        schema: 'reachai.managed-executor.artifact-upload.v1',
        executionId,
        artifactId: request.headers['x-reachai-artifact-id'],
        artifactType: 'PATCH',
        objectKey: `managed-executions/${executionId}/artifacts/artifact-patch/workspace.patch`,
        sha256: request.headers['x-reachai-artifact-sha256'],
        sizeBytes: body.length,
        mediaType: request.headers['content-type'],
        validationStatus: 'VERIFIED',
        scanStatus: 'CLEAN',
      });
    }
    if (request.url?.endsWith(':complete')) return mutation(response, 'FINALIZING');
    return json(response, 404, { code: 'NOT_FOUND' });
  });
  await listenTcp(runtime);
  const address = runtime.address();
  assert.ok(address && typeof address !== 'string');
  const broker = await startRuntimeCredentialBroker({
    runtimeBaseUrl: `http://127.0.0.1:${address.port}`,
    executionId,
    workerId,
    workerToken,
    socketPath,
    allowPlaintextLoopback: true,
  });
  try {
    const client = new ManagedRuntimeClient({
      baseUrl: 'https://runtime.internal',
      executionId,
      workerId,
      brokerSocketPath: socketPath,
    });
    assert.equal((await client.claim()).executionId, executionId);
    await client.heartbeat();
    await client.appendEvents([{
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
    }]);
    assert.deepEqual((await client.commands(0)).commands, []);
    const patch = Buffer.from('diff --git a/a b/a\n', 'utf8');
    await client.uploadArtifact({
      artifactId: 'artifact-patch',
      artifactType: 'PATCH',
      sha256: createHash('sha256').update(patch).digest('hex'),
      mediaType: 'text/x-diff',
      content: patch,
    });
    await client.complete('SUCCEEDED');

    const denied = await socketRequest(socketPath,
      '/internal/runtime/managed-worker/executions/another-execution:claim');
    assert.equal(denied.status, 404);
    assert.deepEqual(JSON.parse(denied.body), { code: 'MANAGED_BROKER_ROUTE_DENIED' });
    assert.equal(upstreamRequests, 6);
    assert.doesNotMatch(client.toString(), new RegExp(workerToken));
  } finally {
    await broker.close();
    runtime.closeAllConnections();
    await new Promise<void>((resolve) => runtime.close(() => resolve()));
    await rm(root, { recursive: true, force: true });
  }
});

test('credential broker requires HTTPS outside a programmatic loopback test', async () => {
  await assert.rejects(startRuntimeCredentialBroker({
    runtimeBaseUrl: 'http://runtime.internal',
    executionId,
    workerId,
    workerToken,
    socketPath: '/run/reachai/broker/runtime.sock',
  }), /credential-free HTTPS/);
});

test('credential broker bounds concurrent and per-window amplification', () => {
  let now = 1_000;
  const gate = new BrokerAdmissionGate(2, 3, 60_000, () => now);
  assert.equal(gate.acquire(), 'ALLOW');
  assert.equal(gate.acquire(), 'ALLOW');
  assert.equal(gate.acquire(), 'SATURATED');
  gate.release();
  assert.equal(gate.acquire(), 'ALLOW');
  gate.release();
  gate.release();
  assert.equal(gate.acquire(), 'RATE_LIMITED');
  now += 60_000;
  assert.equal(gate.acquire(), 'ALLOW');
});

function mutation(response: ServerResponse, status: string): void {
  json(response, 200, {
    executionId,
    status,
    lastEventSequence: 1,
    cancelRequested: false,
    leaseExpiresAt: '2026-08-24T12:00:00.000Z',
  });
}

function json(response: ServerResponse, status: number, body: unknown): void {
  response.writeHead(status, { 'content-type': 'application/json' });
  response.end(JSON.stringify(body));
}

async function readBody(request: IncomingMessage): Promise<Buffer> {
  const chunks: Buffer[] = [];
  for await (const chunk of request) chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk));
  return Buffer.concat(chunks);
}

async function listenTcp(server: ReturnType<typeof createServer>): Promise<void> {
  await new Promise<void>((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
}

function socketRequest(socketPath: string, requestPath: string): Promise<{ status: number; body: string }> {
  return new Promise((resolve, reject) => {
    const request = httpRequest({ socketPath, path: requestPath, method: 'POST' }, (response) => {
      const chunks: Buffer[] = [];
      response.on('data', (chunk: Buffer) => chunks.push(Buffer.from(chunk)));
      response.once('end', () => resolve({
        status: response.statusCode ?? 0,
        body: Buffer.concat(chunks).toString('utf8'),
      }));
    });
    request.once('error', reject);
    request.end();
  });
}
