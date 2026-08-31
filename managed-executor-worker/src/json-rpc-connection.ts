import { EventEmitter } from 'node:events';
import { createInterface, type Interface } from 'node:readline';
import type { Readable, Writable } from 'node:stream';
import {
  isJsonRpcNotification,
  isJsonRpcRequest,
  isJsonRpcResponse,
  isRecord,
  JsonRpcRemoteError,
  type JsonRpcId,
  type JsonRpcNotification,
  type JsonRpcRequest,
} from './protocol.js';

interface PendingRequest {
  resolve: (value: unknown) => void;
  reject: (reason: Error) => void;
  timer: NodeJS.Timeout;
}

export interface JsonRpcConnectionOptions {
  requestTimeoutMs?: number;
  maxMessageBytes?: number;
}

export class JsonRpcConnection {
  private readonly events = new EventEmitter();
  private readonly reader: Interface;
  private readonly pending = new Map<JsonRpcId, PendingRequest>();
  private readonly requestTimeoutMs: number;
  private readonly maxMessageBytes: number;
  private nextRequestId = 1;
  private closed = false;

  constructor(
    input: Readable,
    private readonly output: Writable,
    options: JsonRpcConnectionOptions = {},
  ) {
    this.requestTimeoutMs = boundedInteger(options.requestTimeoutMs, 30_000, 100, 300_000);
    this.maxMessageBytes = boundedInteger(options.maxMessageBytes, 4 * 1024 * 1024, 1024, 16 * 1024 * 1024);
    this.reader = createInterface({ input, crlfDelay: Infinity });
    this.reader.on('line', (line) => this.handleLine(line));
    this.reader.on('close', () => this.close(new Error('Codex app-server transport closed')));
    input.on('error', (error) => this.close(toError(error, 'Codex app-server input failed')));
    output.on('error', (error) => this.close(toError(error, 'Codex app-server output failed')));
  }

  async request<T>(method: string, params?: unknown, timeoutMs?: number): Promise<T> {
    this.requireOpen();
    const id = this.nextRequestId++;
    const effectiveTimeout = boundedInteger(timeoutMs, this.requestTimeoutMs, 100, 300_000);
    const response = new Promise<T>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`Codex app-server request timed out: ${method}`));
      }, effectiveTimeout);
      timer.unref?.();
      this.pending.set(id, {
        resolve: (value) => resolve(value as T),
        reject,
        timer,
      });
    });
    try {
      this.write({ id, method, ...(params === undefined ? {} : { params }) });
    } catch (error) {
      const pending = this.pending.get(id);
      if (pending) {
        clearTimeout(pending.timer);
        this.pending.delete(id);
      }
      throw error;
    }
    return response;
  }

  notify(method: string, params?: unknown): void {
    this.requireOpen();
    this.write({ method, ...(params === undefined ? {} : { params }) });
  }

  respond(id: JsonRpcId, result: unknown): void {
    this.requireOpen();
    this.write({ id, result });
  }

  respondError(id: JsonRpcId, code: number, message: string): void {
    this.requireOpen();
    this.write({ id, error: { code, message } });
  }

  onNotification(listener: (notification: JsonRpcNotification) => void): () => void {
    this.events.on('notification', listener);
    return () => this.events.off('notification', listener);
  }

  onServerRequest(listener: (request: JsonRpcRequest) => void): () => void {
    this.events.on('serverRequest', listener);
    return () => this.events.off('serverRequest', listener);
  }

  onProtocolError(listener: (error: Error) => void): () => void {
    this.events.on('protocolError', listener);
    return () => this.events.off('protocolError', listener);
  }

  onClose(listener: (error: Error) => void): () => void {
    this.events.on('closed', listener);
    return () => this.events.off('closed', listener);
  }

  close(reason = new Error('Codex app-server connection closed')): void {
    if (this.closed) return;
    this.closed = true;
    this.reader.close();
    for (const request of this.pending.values()) {
      clearTimeout(request.timer);
      request.reject(reason);
    }
    this.pending.clear();
    this.events.emit('closed', reason);
  }

  private handleLine(line: string): void {
    if (this.closed || line.trim().length === 0) return;
    if (Buffer.byteLength(line, 'utf8') > this.maxMessageBytes) {
      this.protocolError(new Error('Codex app-server message exceeded the configured byte limit'));
      return;
    }
    let value: unknown;
    try {
      value = JSON.parse(line) as unknown;
    } catch {
      this.protocolError(new Error('Codex app-server emitted invalid JSON'));
      return;
    }

    if (isJsonRpcResponse(value)) {
      const pending = this.pending.get(value.id);
      if (!pending) {
        this.protocolError(new Error('Codex app-server returned an unknown response id'));
        return;
      }
      clearTimeout(pending.timer);
      this.pending.delete(value.id);
      if ('error' in value && isRecord(value.error)) {
        pending.reject(new JsonRpcRemoteError({
          code: typeof value.error.code === 'number' ? value.error.code : -1,
          message: typeof value.error.message === 'string'
            ? value.error.message
            : 'Codex app-server request failed',
          ...(Object.prototype.hasOwnProperty.call(value.error, 'data')
            ? { data: value.error.data }
            : {}),
        }));
      } else {
        pending.resolve('result' in value ? value.result : undefined);
      }
      return;
    }

    if (isJsonRpcRequest(value)) {
      this.events.emit('serverRequest', value);
      return;
    }
    if (isJsonRpcNotification(value)) {
      this.events.emit('notification', value);
      return;
    }
    this.protocolError(new Error('Codex app-server emitted an unsupported message shape'));
  }

  private protocolError(error: Error): void {
    this.events.emit('protocolError', error);
  }

  private write(message: unknown): void {
    const line = `${JSON.stringify(message)}\n`;
    if (Buffer.byteLength(line, 'utf8') > this.maxMessageBytes) {
      throw new Error('Codex app-server request exceeded the configured byte limit');
    }
    const accepted = this.output.write(line, 'utf8');
    if (!accepted) {
      this.events.emit('backpressure');
    }
  }

  private requireOpen(): void {
    if (this.closed) {
      throw new Error('Codex app-server connection is closed');
    }
  }
}

function boundedInteger(value: number | undefined, fallback: number, min: number, max: number): number {
  if (!Number.isFinite(value)) return fallback;
  return Math.min(max, Math.max(min, Math.trunc(value as number)));
}

function toError(value: unknown, fallback: string): Error {
  return value instanceof Error ? value : new Error(fallback);
}
