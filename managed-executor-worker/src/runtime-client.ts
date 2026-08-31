import type { ManagedExecutionEventV1 } from './managed-events.js';
import { request as httpRequest } from 'node:http';

export interface ManagedRuntimeClientOptions {
  baseUrl: string;
  executionId: string;
  workerId: string;
  workerToken?: string;
  brokerSocketPath?: string;
  requestTimeoutMs?: number;
  maxResponseBytes?: number;
  allowPlaintextLoopback?: boolean;
}

export interface RuntimeWorkerClaimV1 {
  schema: 'reachai.managed-executor.claim.v1';
  executionId: string;
  objective: string;
  objectiveSha256: string;
  workspaceProfile: 'ANALYZE_READONLY' | 'WORKSPACE_PATCH';
  executorProvider: 'CODEX';
  modelRef: string | null;
  acceptanceProfile: string;
  maxWallTimeMs: number;
  approvalTimeoutMs: number;
  leaseExpiresAt: string;
}

export interface RuntimeWorkerMutationV1 {
  executionId: string;
  status: string;
  lastEventSequence: number;
  cancelRequested: boolean;
  leaseExpiresAt: string | null;
}

export interface RuntimeWorkerCommandV1 {
  sequence: number;
  type: 'CANCEL' | 'APPROVAL_DECISION';
  data: Record<string, unknown>;
}

export interface RuntimeWorkerCommandBatchV1 {
  cursor: number;
  commands: RuntimeWorkerCommandV1[];
}

export interface RuntimeArtifactDescriptorV1 {
  artifactId: string;
  artifactType: 'PATCH' | 'TEST_REPORT' | 'EXECUTION_SUMMARY' | 'EVIDENCE_MANIFEST' | 'EVENT_LOG';
  objectKey: string;
  sha256: string;
  sizeBytes: number;
  mediaType: string;
}

export interface RuntimeArtifactUploadV1 extends RuntimeArtifactDescriptorV1 {
  schema: 'reachai.managed-executor.artifact-upload.v1';
  executionId: string;
  validationStatus: 'VERIFIED';
  scanStatus: 'CLEAN';
}

export class ManagedRuntimeHttpError extends Error {
  constructor(readonly status: number, readonly code: string) {
    super(`Runtime worker request failed (${status}, ${code})`);
    this.name = 'ManagedRuntimeHttpError';
  }
}

export class ManagedRuntimeClient {
  private readonly baseUrl: URL;
  private readonly requestTimeoutMs: number;
  private readonly maxResponseBytes: number;
  private readonly brokerSocketPath: string | undefined;

  constructor(private readonly options: ManagedRuntimeClientOptions) {
    this.baseUrl = validateBaseUrl(options.baseUrl, options.allowPlaintextLoopback === true);
    requireIdentifier(options.executionId, 'executionId');
    requireIdentifier(options.workerId, 'workerId');
    const hasToken = typeof options.workerToken === 'string';
    const hasBroker = typeof options.brokerSocketPath === 'string';
    if (hasToken === hasBroker) {
      throw new Error('Exactly one Runtime authentication transport is required');
    }
    if (hasToken && (options.workerToken!.length < 32
        || options.workerToken!.length > 256 || /\s/.test(options.workerToken!))) {
      throw new Error('workerToken is invalid');
    }
    if (hasBroker && (!options.brokerSocketPath!.startsWith('/')
        || options.brokerSocketPath!.length > 512
        || options.brokerSocketPath!.includes('\0'))) {
      throw new Error('brokerSocketPath is invalid');
    }
    this.brokerSocketPath = options.brokerSocketPath;
    this.requestTimeoutMs = clamp(options.requestTimeoutMs ?? 30_000, 1_000, 120_000);
    this.maxResponseBytes = clamp(options.maxResponseBytes ?? 1024 * 1024, 4_096, 4 * 1024 * 1024);
  }

  claim(): Promise<RuntimeWorkerClaimV1> {
    return this.request<RuntimeWorkerClaimV1>('POST', `${this.executionPath()}:claim`);
  }

  heartbeat(): Promise<RuntimeWorkerMutationV1> {
    return this.request<RuntimeWorkerMutationV1>('POST', `${this.executionPath()}:heartbeat`);
  }

  appendEvents(events: ManagedExecutionEventV1[]): Promise<RuntimeWorkerMutationV1> {
    if (events.length < 1 || events.length > 100) throw new Error('Runtime event batch must contain 1 to 100 events');
    return this.request<RuntimeWorkerMutationV1>('POST', `${this.executionPath()}/events:batch`, { events });
  }

  commands(afterSequence: number): Promise<RuntimeWorkerCommandBatchV1> {
    if (!Number.isSafeInteger(afterSequence) || afterSequence < 0) throw new Error('afterSequence is invalid');
    return this.request<RuntimeWorkerCommandBatchV1>(
      'GET',
      `${this.executionPath()}/commands?afterSequence=${afterSequence}`,
    );
  }

  complete(
    outcome: 'SUCCEEDED' | 'FAILED' | 'CANCELLED',
    options: {
      errorCode?: string;
      errorMessage?: string;
      artifacts?: RuntimeArtifactDescriptorV1[];
    } = {},
  ): Promise<RuntimeWorkerMutationV1> {
    return this.request<RuntimeWorkerMutationV1>('POST', `${this.executionPath()}:complete`, {
      outcome,
      artifacts: options.artifacts || [],
      ...(options.errorCode ? { errorCode: options.errorCode } : {}),
      ...(options.errorMessage ? { errorMessage: options.errorMessage } : {}),
    });
  }

  uploadArtifact(options: {
    artifactId: string;
    artifactType: RuntimeArtifactDescriptorV1['artifactType'];
    sha256: string;
    mediaType: string;
    content: Uint8Array;
  }): Promise<RuntimeArtifactUploadV1> {
    requireIdentifier(options.artifactId, 'artifactId');
    if (!/^[a-f0-9]{64}$/.test(options.sha256)) throw new Error('artifact sha256 is invalid');
    if (!/^[a-z0-9.+-]+\/[a-z0-9.+-]+$/.test(options.mediaType)) throw new Error('artifact mediaType is invalid');
    if (options.content.byteLength > 64 * 1024 * 1024) throw new Error('Runtime artifact exceeds the byte limit');
    return this.requestTransport<RuntimeArtifactUploadV1>(
      'PUT',
      `${this.executionPath()}/artifacts/${encodeURIComponent(options.artifactType)}`,
      Buffer.from(options.content),
      {
        'content-type': options.mediaType,
        'content-length': String(options.content.byteLength),
        'x-reachai-artifact-id': options.artifactId,
        'x-reachai-artifact-sha256': options.sha256,
      },
    );
  }

  toString(): string {
    return `ManagedRuntimeClient[executionId=${this.options.executionId}, workerId=${this.options.workerId}, auth=<redacted>]`;
  }

  private executionPath(): string {
    return `/internal/runtime/managed-worker/executions/${encodeURIComponent(this.options.executionId)}`;
  }

  private async request<T>(method: 'GET' | 'POST', requestPath: string, body?: unknown): Promise<T> {
    const serializedBody = body === undefined ? undefined : JSON.stringify(body);
    if (serializedBody && Buffer.byteLength(serializedBody, 'utf8') > 1024 * 1024) {
      throw new Error('Runtime worker request exceeds the byte limit');
    }
    return this.requestTransport<T>(
      method,
      requestPath,
      serializedBody,
      serializedBody === undefined ? {} : { 'content-type': 'application/json' },
    );
  }

  private async requestTransport<T>(
    method: 'GET' | 'POST' | 'PUT',
    requestPath: string,
    body: BodyInit | undefined,
    extraHeaders: Record<string, string>,
  ): Promise<T> {
    if (this.brokerSocketPath) {
      return this.requestViaBroker<T>(method, requestPath, body, extraHeaders);
    }
    const url = new URL(requestPath, this.baseUrl);
    const controller = new AbortController();
    const timeout = setTimeout(
      () => controller.abort(new Error('Runtime worker request timed out')),
      this.requestTimeoutMs,
    );
    try {
      const response = await fetch(url, {
        method,
        redirect: 'manual',
        signal: controller.signal,
        headers: {
          accept: 'application/json',
          authorization: `Bearer ${this.options.workerToken!}`,
          'x-reachai-worker-id': this.options.workerId,
          ...extraHeaders,
        },
        ...(body === undefined ? {} : { body }),
      });

      if (response.status >= 300 && response.status < 400) {
        await response.body?.cancel().catch(() => undefined);
        throw new ManagedRuntimeHttpError(response.status, 'MANAGED_RUNTIME_REDIRECT_REJECTED');
      }
      const text = await readBoundedResponse(response, this.maxResponseBytes);
      let parsed: unknown = null;
      try {
        parsed = text ? JSON.parse(text) : null;
      } catch {
        throw new ManagedRuntimeHttpError(response.status, 'MANAGED_RUNTIME_RESPONSE_INVALID');
      }
      if (!response.ok) {
        const code = isRecord(parsed) && typeof parsed.code === 'string'
          ? safeCode(parsed.code)
          : 'MANAGED_RUNTIME_REQUEST_FAILED';
        throw new ManagedRuntimeHttpError(response.status, code);
      }
      if (!isRecord(parsed)) throw new ManagedRuntimeHttpError(response.status, 'MANAGED_RUNTIME_RESPONSE_INVALID');
      return parsed as T;
    } catch (error) {
      if (error instanceof ManagedRuntimeHttpError) throw error;
      if (controller.signal.aborted) {
        throw new ManagedRuntimeHttpError(0, 'MANAGED_RUNTIME_REQUEST_TIMEOUT');
      }
      throw new ManagedRuntimeHttpError(0, 'MANAGED_RUNTIME_TRANSPORT_FAILED');
    } finally {
      clearTimeout(timeout);
    }
  }

  private requestViaBroker<T>(
    method: 'GET' | 'POST' | 'PUT',
    requestPath: string,
    body: BodyInit | undefined,
    extraHeaders: Record<string, string>,
  ): Promise<T> {
    const payload = transportBody(body);
    return new Promise<T>((resolve, reject) => {
      const request = httpRequest({
        socketPath: this.brokerSocketPath,
        path: requestPath,
        method,
        headers: {
          accept: 'application/json',
          ...extraHeaders,
          ...(payload === undefined ? {} : { 'content-length': String(payload.byteLength) }),
        },
      }, (response) => {
        const status = response.statusCode ?? 0;
        if (status >= 300 && status < 400) {
          response.resume();
          reject(new ManagedRuntimeHttpError(status, 'MANAGED_RUNTIME_REDIRECT_REJECTED'));
          return;
        }
        const chunks: Buffer[] = [];
        let bytes = 0;
        response.on('data', (chunk: Buffer) => {
          bytes += chunk.byteLength;
          if (bytes > this.maxResponseBytes) {
            response.destroy(new ManagedRuntimeHttpError(status, 'MANAGED_RUNTIME_RESPONSE_TOO_LARGE'));
            return;
          }
          chunks.push(Buffer.from(chunk));
        });
        response.once('error', reject);
        response.once('end', () => {
          let parsed: unknown = null;
          try {
            const text = Buffer.concat(chunks).toString('utf8');
            parsed = text ? JSON.parse(text) : null;
          } catch {
            reject(new ManagedRuntimeHttpError(status, 'MANAGED_RUNTIME_RESPONSE_INVALID'));
            return;
          }
          if (status < 200 || status >= 300) {
            const code = isRecord(parsed) && typeof parsed.code === 'string'
              ? safeCode(parsed.code)
              : 'MANAGED_RUNTIME_REQUEST_FAILED';
            reject(new ManagedRuntimeHttpError(status, code));
            return;
          }
          if (!isRecord(parsed)) {
            reject(new ManagedRuntimeHttpError(status, 'MANAGED_RUNTIME_RESPONSE_INVALID'));
            return;
          }
          resolve(parsed as T);
        });
      });
      request.setTimeout(this.requestTimeoutMs, () => {
        request.destroy(new ManagedRuntimeHttpError(0, 'MANAGED_RUNTIME_REQUEST_TIMEOUT'));
      });
      request.once('error', (error) => reject(error instanceof ManagedRuntimeHttpError
        ? error : new ManagedRuntimeHttpError(0, 'MANAGED_RUNTIME_TRANSPORT_FAILED')));
      request.end(payload);
    });
  }
}

export class ManagedRuntimeEventSink {
  constructor(private readonly client: ManagedRuntimeClient) {
  }

  async emit(event: ManagedExecutionEventV1): Promise<void> {
    await this.client.appendEvents([event]);
  }
}

async function readBoundedResponse(response: Response, maximumBytes: number): Promise<string> {
  const contentLength = Number(response.headers.get('content-length'));
  if (Number.isFinite(contentLength) && contentLength > maximumBytes) {
    await response.body?.cancel().catch(() => undefined);
    throw new ManagedRuntimeHttpError(response.status, 'MANAGED_RUNTIME_RESPONSE_TOO_LARGE');
  }
  if (!response.body) return '';
  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let bytes = 0;
  while (true) {
    const next = await reader.read();
    if (next.done) break;
    bytes += next.value.byteLength;
    if (bytes > maximumBytes) {
      await reader.cancel().catch(() => undefined);
      throw new ManagedRuntimeHttpError(response.status, 'MANAGED_RUNTIME_RESPONSE_TOO_LARGE');
    }
    chunks.push(next.value);
  }
  return Buffer.concat(chunks.map((chunk) => Buffer.from(chunk))).toString('utf8');
}

function validateBaseUrl(value: string, allowPlaintextLoopback: boolean): URL {
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    throw new Error('Runtime baseUrl is invalid');
  }
  if (url.username || url.password || url.search || url.hash) throw new Error('Runtime baseUrl contains forbidden components');
  const loopback = ['localhost', '127.0.0.1', '::1'].includes(url.hostname.toLowerCase());
  if (url.protocol !== 'https:' && !(allowPlaintextLoopback && url.protocol === 'http:' && loopback)) {
    throw new Error('Runtime baseUrl must use HTTPS');
  }
  url.pathname = '/';
  return url;
}

function requireIdentifier(value: string, label: string): void {
  if (!/^[A-Za-z0-9._:-]{1,128}$/.test(value)) throw new Error(`${label} is invalid`);
}

function safeCode(value: string): string {
  const normalized = value.trim().toUpperCase();
  return /^[A-Z0-9_]{1,128}$/.test(normalized) ? normalized : 'MANAGED_RUNTIME_REQUEST_FAILED';
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function transportBody(body: BodyInit | undefined): Buffer | undefined {
  if (body === undefined) return undefined;
  if (typeof body === 'string') return Buffer.from(body, 'utf8');
  if (body instanceof Uint8Array) return Buffer.from(body);
  throw new Error('Runtime request body type is unsupported');
}

function clamp(value: number, minimum: number, maximum: number): number {
  return Math.min(maximum, Math.max(minimum, Math.trunc(value)));
}
