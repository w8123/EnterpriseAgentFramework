export type JsonRpcId = number | string;

export interface JsonRpcErrorObject {
  code: number;
  message: string;
  data?: unknown;
}

export interface JsonRpcRequest {
  id: JsonRpcId;
  method: string;
  params?: unknown;
}

export interface JsonRpcNotification {
  method: string;
  params?: unknown;
}

export interface JsonRpcSuccessResponse {
  id: JsonRpcId;
  result: unknown;
}

export interface JsonRpcErrorResponse {
  id: JsonRpcId;
  error: JsonRpcErrorObject;
}

export type JsonRpcResponse = JsonRpcSuccessResponse | JsonRpcErrorResponse;
export type JsonRpcMessage = JsonRpcRequest | JsonRpcNotification | JsonRpcResponse;

export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

export function hasOwn(value: object, key: string): boolean {
  return Object.prototype.hasOwnProperty.call(value, key);
}

export function isJsonRpcRequest(value: unknown): value is JsonRpcRequest {
  return isRecord(value)
    && (typeof value.id === 'number' || typeof value.id === 'string')
    && typeof value.method === 'string';
}

export function isJsonRpcNotification(value: unknown): value is JsonRpcNotification {
  return isRecord(value)
    && !hasOwn(value, 'id')
    && typeof value.method === 'string';
}

export function isJsonRpcResponse(value: unknown): value is JsonRpcResponse {
  if (!isRecord(value)
    || (typeof value.id !== 'number' && typeof value.id !== 'string')
    || typeof value.method === 'string') {
    return false;
  }
  return hasOwn(value, 'result') || hasOwn(value, 'error');
}

export class JsonRpcRemoteError extends Error {
  readonly code: number;
  readonly data: unknown;

  constructor(error: JsonRpcErrorObject) {
    super(error.message || 'Codex app-server request failed');
    this.name = 'JsonRpcRemoteError';
    this.code = error.code;
    this.data = error.data;
  }
}
