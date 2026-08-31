import {
  createServer,
  request as plainHttpRequest,
  type IncomingMessage,
  type Server,
  type ServerResponse,
} from 'node:http';
import { request as httpsRequest } from 'node:https';
import { chmod, lstat, mkdir, readFile, rm, stat } from 'node:fs/promises';
import path from 'node:path';

const JSON_REQUEST_LIMIT = 1024 * 1024;
const ARTIFACT_REQUEST_LIMIT = 64 * 1024 * 1024;
const RESPONSE_LIMIT = 4 * 1024 * 1024;
const MAX_INFLIGHT_REQUESTS = 4;
const MAX_REQUESTS_PER_MINUTE = 600;
const ARTIFACT_TYPES = new Set([
  'PATCH', 'TEST_REPORT', 'EXECUTION_SUMMARY', 'EVIDENCE_MANIFEST', 'EVENT_LOG',
]);

type BrokerBodyMode = 'NONE' | 'OPTIONAL_JSON' | 'JSON' | 'ARTIFACT';

interface BrokerRoute {
  method: string;
  path: string;
  maximumBodyBytes: number;
  bodyMode: BrokerBodyMode;
}

export interface RuntimeCredentialBrokerConfig {
  runtimeBaseUrl: string;
  executionId: string;
  workerId: string;
  workerToken: string;
  socketPath: string;
  allowPlaintextLoopback?: boolean;
}

export async function loadRuntimeCredentialBrokerConfig(
  environment: NodeJS.ProcessEnv = process.env,
): Promise<RuntimeCredentialBrokerConfig> {
  const runtimeBaseUrl = validatedRuntimeUrl(requiredText(
    environment.REACHAI_RUNTIME_BASE_URL, 'REACHAI_RUNTIME_BASE_URL', 2_048,
  )).toString();
  const executionId = identifier(
    environment.REACHAI_MANAGED_EXECUTION_ID, 'REACHAI_MANAGED_EXECUTION_ID',
  );
  const workerId = identifier(environment.REACHAI_MANAGED_WORKER_ID, 'REACHAI_MANAGED_WORKER_ID');
  const tokenFile = absolutePath(
    environment.REACHAI_MANAGED_WORKER_TOKEN_FILE || '/run/reachai/broker-secret/token',
    'REACHAI_MANAGED_WORKER_TOKEN_FILE',
  );
  const tokenStats = await stat(tokenFile);
  if (!tokenStats.isFile() || tokenStats.size > 512) throw new Error('Managed broker token file is invalid');
  const workerToken = (await readFile(tokenFile, 'utf8')).trim();
  if (workerToken.length < 32 || workerToken.length > 256 || /\s/.test(workerToken)) {
    throw new Error('Managed broker token file is invalid');
  }
  const socketPath = absolutePath(
    environment.REACHAI_MANAGED_RUNTIME_BROKER_SOCKET || '/run/reachai/broker/runtime.sock',
    'REACHAI_MANAGED_RUNTIME_BROKER_SOCKET',
  );
  if (socketPath.length > 100) throw new Error('Managed broker socket path is too long');
  return { runtimeBaseUrl, executionId, workerId, workerToken, socketPath };
}

export async function startRuntimeCredentialBroker(
  config: RuntimeCredentialBrokerConfig,
): Promise<{ server: Server; close: () => Promise<void> }> {
  validateConfig(config);
  await prepareSocket(config.socketPath);
  const admission = new BrokerAdmissionGate(
    MAX_INFLIGHT_REQUESTS, MAX_REQUESTS_PER_MINUTE, 60_000,
  );
  const server = createServer((request, response) => {
    const decision = admission.acquire();
    if (decision !== 'ALLOW') {
      request.resume();
      sendJson(response, 429, decision === 'SATURATED'
        ? 'MANAGED_BROKER_CONCURRENCY_EXCEEDED' : 'MANAGED_BROKER_RATE_EXCEEDED');
      return;
    }
    void proxyRequest(config, request, response)
      .catch(() => {
        if (!response.headersSent) sendJson(response, 502, 'MANAGED_BROKER_UPSTREAM_FAILED');
        else response.destroy();
      })
      .finally(() => admission.release());
  });
  server.maxHeadersCount = 32;
  server.maxConnections = 8;
  server.headersTimeout = 10_000;
  server.requestTimeout = 120_000;
  await new Promise<void>((resolve, reject) => {
    server.once('error', reject);
    server.listen(config.socketPath, resolve);
  });
  await chmod(config.socketPath, 0o660);
  return {
    server,
    close: async () => {
      server.closeAllConnections();
      await new Promise<void>((resolve, reject) => {
        server.close((error) => error ? reject(error) : resolve());
      });
      await rm(config.socketPath, { force: true });
    },
  };
}

async function proxyRequest(
  config: RuntimeCredentialBrokerConfig,
  incoming: IncomingMessage,
  outgoing: ServerResponse,
): Promise<void> {
  const route = allowedRoute(incoming, config.executionId);
  if (!route) {
    incoming.resume();
    sendJson(outgoing, 404, 'MANAGED_BROKER_ROUTE_DENIED');
    return;
  }
  const contentLength = exactContentLength(incoming, route);
  if (contentLength === null) {
    incoming.resume();
    sendJson(outgoing, 400, 'MANAGED_BROKER_REQUEST_INVALID');
    return;
  }
  const runtime = new URL(route.path, config.runtimeBaseUrl);
  const headers: Record<string, string> = {
    accept: 'application/json',
    authorization: `Bearer ${config.workerToken}`,
    'x-reachai-worker-id': config.workerId,
  };
  if (contentLength > 0) headers['content-length'] = String(contentLength);
  for (const name of ['content-type', 'x-reachai-artifact-id', 'x-reachai-artifact-sha256']) {
    const value = incoming.headers[name];
    if (typeof value === 'string' && value.length <= 256) headers[name] = value;
  }

  await new Promise<void>((resolve) => {
    let settled = false;
    const finish = () => {
      if (settled) return false;
      settled = true;
      resolve();
      return true;
    };
    const fail = (status: number, code: string) => {
      if (!finish()) return;
      if (!outgoing.headersSent) sendJson(outgoing, status, code);
      else outgoing.destroy();
    };
    const requestTransport = runtime.protocol === 'http:' ? plainHttpRequest : httpsRequest;
    const upstream = requestTransport(runtime, {
      method: route.method,
      headers,
      timeout: 120_000,
      agent: false,
    }, (response) => {
      const status = response.statusCode ?? 502;
      const chunks: Buffer[] = [];
      let bytes = 0;
      response.on('data', (chunk: Buffer) => {
        bytes += chunk.byteLength;
        if (bytes > RESPONSE_LIMIT) {
          response.destroy();
          fail(502, 'MANAGED_BROKER_RESPONSE_TOO_LARGE');
          return;
        }
        chunks.push(Buffer.from(chunk));
      });
      response.once('error', () => {
        fail(502, 'MANAGED_BROKER_UPSTREAM_FAILED');
      });
      response.once('end', () => {
        if (!finish()) return;
        if (status >= 300 && status < 400) {
          sendJson(outgoing, 502, 'MANAGED_BROKER_REDIRECT_REJECTED');
          return;
        }
        const body = Buffer.concat(chunks);
        outgoing.writeHead(status, {
          'content-type': 'application/json',
          'content-length': String(body.byteLength),
          'cache-control': 'no-store',
        });
        outgoing.end(body);
      });
    });
    outgoing.once('close', () => {
      if (!settled) {
        upstream.destroy();
        finish();
      }
    });
    upstream.once('timeout', () => upstream.destroy());
    upstream.once('error', () => {
      incoming.resume();
      fail(502, 'MANAGED_BROKER_UPSTREAM_FAILED');
    });
    let received = 0;
    incoming.on('data', (chunk: Buffer) => {
      received += chunk.byteLength;
      if (received > route.maximumBodyBytes || received > contentLength) {
        incoming.destroy();
        upstream.destroy();
        fail(413, 'MANAGED_BROKER_REQUEST_TOO_LARGE');
        return;
      }
      upstream.write(chunk);
    });
    incoming.once('end', () => {
      if (received !== contentLength) {
        upstream.destroy();
        fail(400, 'MANAGED_BROKER_REQUEST_INVALID');
        return;
      }
      upstream.end();
    });
    incoming.once('aborted', () => {
      upstream.destroy();
      finish();
    });
    incoming.once('error', () => upstream.destroy());
  });
}

export class BrokerAdmissionGate {
  private windowStartedAt: number;
  private requestsInWindow = 0;
  private inflight = 0;

  constructor(
    private readonly maximumInflight: number,
    private readonly maximumRequestsPerWindow: number,
    private readonly windowMilliseconds: number,
    private readonly now: () => number = Date.now,
  ) {
    if (maximumInflight < 1 || maximumRequestsPerWindow < 1 || windowMilliseconds < 1_000) {
      throw new Error('Managed broker admission limits are invalid');
    }
    this.windowStartedAt = now();
  }

  acquire(): 'ALLOW' | 'SATURATED' | 'RATE_LIMITED' {
    const current = this.now();
    if (current - this.windowStartedAt >= this.windowMilliseconds) {
      this.windowStartedAt = current;
      this.requestsInWindow = 0;
    }
    if (this.inflight >= this.maximumInflight) return 'SATURATED';
    if (this.requestsInWindow >= this.maximumRequestsPerWindow) return 'RATE_LIMITED';
    this.inflight++;
    this.requestsInWindow++;
    return 'ALLOW';
  }

  release(): void {
    if (this.inflight > 0) this.inflight--;
  }
}

function allowedRoute(
  request: IncomingMessage,
  executionId: string,
): BrokerRoute | null {
  const method = request.method || '';
  const requestUrl = request.url || '';
  if (requestUrl.length > 512 || requestUrl.includes('#')) return null;
  let parsed: URL;
  try {
    parsed = new URL(requestUrl, 'http://broker.local');
  } catch {
    return null;
  }
  const base = `/internal/runtime/managed-worker/executions/${encodeURIComponent(executionId)}`;
  const optionalJsonPostPaths = new Set([`${base}:claim`, `${base}:heartbeat`]);
  if (method === 'POST' && optionalJsonPostPaths.has(parsed.pathname) && parsed.search === '') {
    return {
      method, path: parsed.pathname, maximumBodyBytes: JSON_REQUEST_LIMIT, bodyMode: 'OPTIONAL_JSON',
    };
  }
  const jsonPostPaths = new Set([`${base}/events:batch`, `${base}:complete`]);
  if (method === 'POST' && jsonPostPaths.has(parsed.pathname) && parsed.search === '') {
    return { method, path: parsed.pathname, maximumBodyBytes: JSON_REQUEST_LIMIT, bodyMode: 'JSON' };
  }
  if (method === 'GET' && parsed.pathname === `${base}/commands`
      && [...parsed.searchParams.keys()].join(',') === 'afterSequence'
      && /^\d{1,20}$/.test(parsed.searchParams.get('afterSequence') || '')) {
    return {
      method, path: `${parsed.pathname}${parsed.search}`, maximumBodyBytes: 0, bodyMode: 'NONE',
    };
  }
  const artifactPrefix = `${base}/artifacts/`;
  if (method === 'PUT' && parsed.pathname.startsWith(artifactPrefix) && parsed.search === '') {
    const type = decodeURIComponent(parsed.pathname.slice(artifactPrefix.length));
    if (ARTIFACT_TYPES.has(type)) {
      return {
        method, path: parsed.pathname, maximumBodyBytes: ARTIFACT_REQUEST_LIMIT, bodyMode: 'ARTIFACT',
      };
    }
  }
  return null;
}

function exactContentLength(request: IncomingMessage, route: BrokerRoute): number | null {
  if (request.headers['transfer-encoding'] !== undefined) return null;
  const raw = request.headers['content-length'];
  if (raw === undefined) {
    return route.bodyMode === 'NONE' || route.bodyMode === 'OPTIONAL_JSON' ? 0 : null;
  }
  if (typeof raw !== 'string' || !/^\d{1,10}$/.test(raw)) return null;
  const value = Number(raw);
  if (!Number.isSafeInteger(value) || value < 0 || value > route.maximumBodyBytes) return null;
  if (route.bodyMode === 'NONE') return value === 0 ? 0 : null;
  const contentType = request.headers['content-type'];
  if (route.bodyMode === 'JSON' || route.bodyMode === 'OPTIONAL_JSON') {
    if (value === 0) return route.bodyMode === 'OPTIONAL_JSON' ? 0 : null;
    return contentType === 'application/json' ? value : null;
  }
  return typeof contentType === 'string'
    && /^[a-z0-9.+-]+\/[a-z0-9.+-]+$/.test(contentType) ? value : null;
}

async function prepareSocket(socketPath: string): Promise<void> {
  await mkdir(path.dirname(socketPath), { recursive: true, mode: 0o770 });
  try {
    const existing = await lstat(socketPath);
    if (!existing.isSocket()) throw new Error('Managed broker socket path already exists');
    await rm(socketPath);
  } catch (error) {
    if (!isMissing(error)) throw error;
  }
}

function validateConfig(config: RuntimeCredentialBrokerConfig): void {
  validatedRuntimeUrl(config.runtimeBaseUrl, config.allowPlaintextLoopback === true);
  identifier(config.executionId, 'executionId');
  identifier(config.workerId, 'workerId');
  if (config.workerToken.length < 32 || config.workerToken.length > 256 || /\s/.test(config.workerToken)) {
    throw new Error('workerToken is invalid');
  }
  absolutePath(config.socketPath, 'socketPath');
}

function validatedRuntimeUrl(value: string, allowPlaintextLoopback = false): URL {
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    throw new Error('Runtime base URL is invalid');
  }
  const loopback = ['localhost', '127.0.0.1', '::1'].includes(url.hostname.toLowerCase());
  if ((url.protocol !== 'https:' && !(allowPlaintextLoopback && url.protocol === 'http:' && loopback))
      || !url.hostname || url.username || url.password
      || url.search || url.hash || (url.pathname !== '/' && url.pathname !== '')) {
    throw new Error('Runtime base URL must be credential-free HTTPS origin');
  }
  url.pathname = '/';
  return url;
}

function identifier(value: string | undefined, field: string): string {
  const result = requiredText(value, field, 128);
  if (!/^[A-Za-z0-9._:-]+$/.test(result)) throw new Error(`${field} is invalid`);
  return result;
}

function requiredText(value: string | undefined, field: string, maximum: number): string {
  if (!value || !value.trim() || value.length > maximum || value.includes('\0')) {
    throw new Error(`${field} is invalid`);
  }
  return value.trim();
}

function absolutePath(value: string, field: string): string {
  if (!path.isAbsolute(value) || value.includes('\0')) throw new Error(`${field} must be absolute`);
  return path.resolve(value);
}

function sendJson(response: ServerResponse, status: number, code: string): void {
  const body = Buffer.from(JSON.stringify({ code }), 'utf8');
  response.writeHead(status, {
    'content-type': 'application/json',
    'content-length': String(body.byteLength),
    'cache-control': 'no-store',
  });
  response.end(body);
}

function isMissing(error: unknown): boolean {
  return typeof error === 'object' && error !== null && 'code' in error
    && (error as { code?: string }).code === 'ENOENT';
}
