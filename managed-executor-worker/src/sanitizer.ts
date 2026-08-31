import path from 'node:path';
import { isRecord } from './protocol.js';

const SECRET_FIELD = /(?:authorization|cookie|password|passwd|secret|token|api[_-]?key|access[_-]?key|private[_-]?key|credential)/i;
const ANSI_ESCAPE = /\u001b(?:\[[0-?]*[ -/]*[@-~]|\][^\u0007]*(?:\u0007|\u001b\\))/g;
const PRIVATE_KEY = /-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z0-9 ]*PRIVATE KEY-----/gi;
const BEARER = /\bBearer\s+[A-Za-z0-9._~+/=-]{8,}/gi;
const OPENAI_KEY = /\bsk-[A-Za-z0-9_-]{12,}\b/g;
const GITHUB_TOKEN = /\bgh[pousr]_[A-Za-z0-9]{20,}\b/gi;
const AWS_ACCESS_KEY = /\b(?:AKIA|ASIA)[A-Z0-9]{16}\b/g;
const KEY_VALUE_SECRET = /\b(?:api[_-]?key|access[_-]?token|refresh[_-]?token|password|passwd|secret|authorization|cookie)\b\s*[:=]\s*(?:"[^"]*"|'[^']*'|[^\s,;]+)/gi;

export interface SanitizerOptions {
  workspaceRoot: string;
  maxTextCharacters?: number;
  maxDepth?: number;
  maxArrayItems?: number;
  maxObjectKeys?: number;
}

export class ManagedEventSanitizer {
  private readonly workspaceRoot: string;
  private readonly workspaceComparable: string;
  private readonly maxTextCharacters: number;
  private readonly maxDepth: number;
  private readonly maxArrayItems: number;
  private readonly maxObjectKeys: number;

  constructor(options: SanitizerOptions) {
    this.workspaceRoot = path.resolve(options.workspaceRoot);
    this.workspaceComparable = comparablePath(this.workspaceRoot);
    this.maxTextCharacters = bounded(options.maxTextCharacters, 2_000, 64, 16_000);
    this.maxDepth = bounded(options.maxDepth, 5, 1, 10);
    this.maxArrayItems = bounded(options.maxArrayItems, 50, 1, 200);
    this.maxObjectKeys = bounded(options.maxObjectKeys, 64, 1, 200);
  }

  text(value: unknown, maxCharacters = this.maxTextCharacters): string | null {
    if (value === null || value === undefined) return null;
    let result = String(value)
      .replace(ANSI_ESCAPE, '')
      .replace(PRIVATE_KEY, '<redacted-private-key>')
      .replace(BEARER, 'Bearer <redacted>')
      .replace(OPENAI_KEY, '<redacted-openai-key>')
      .replace(GITHUB_TOKEN, '<redacted-github-token>')
      .replace(AWS_ACCESS_KEY, '<redacted-aws-key>')
      .replace(KEY_VALUE_SECRET, (match) => `${match.split(/[:=]/, 1)[0]}=<redacted>`)
      .replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/g, '');
    result = replaceAllPathForms(result, this.workspaceRoot, '$WORKSPACE');
    result = redactCommonExternalPaths(result);
    return truncate(result, bounded(maxCharacters, this.maxTextCharacters, 16, 32_000));
  }

  path(value: unknown): string | null {
    const text = this.text(value, 4_096);
    if (!text) return text;
    if (text.startsWith('$WORKSPACE')) return normalizeSeparators(text);
    if (!path.isAbsolute(text)) return truncate(normalizeSeparators(text), 1_000);
    const resolved = path.resolve(text);
    const comparable = comparablePath(resolved);
    if (comparable === this.workspaceComparable) return '$WORKSPACE';
    if (comparable.startsWith(`${this.workspaceComparable}/`)) {
      const relative = normalizeSeparators(path.relative(this.workspaceRoot, resolved));
      return `$WORKSPACE/${relative}`;
    }
    return '<external-path>';
  }

  command(value: unknown): string[] {
    if (Array.isArray(value)) {
      return value.slice(0, this.maxArrayItems)
        .map((part) => this.text(part, 1_000) || '')
        .filter((part) => part.length > 0);
    }
    const text = this.text(value, 2_000);
    return text ? [text] : [];
  }

  value(value: unknown): unknown {
    return this.sanitizeValue(value, 0, null);
  }

  boundedObject(value: unknown, maxBytes = 16 * 1024): Record<string, unknown> {
    const sanitized = this.value(value);
    if (!isRecord(sanitized)) return {};
    const json = JSON.stringify(sanitized);
    if (Buffer.byteLength(json, 'utf8') <= maxBytes) return sanitized;
    return {
      truncated: true,
      originalBytes: Buffer.byteLength(json, 'utf8'),
    };
  }

  private sanitizeValue(value: unknown, depth: number, key: string | null): unknown {
    if (key && SECRET_FIELD.test(key)) return '<redacted>';
    if (value === null || typeof value === 'boolean' || typeof value === 'number') return value;
    if (typeof value === 'string') return this.text(value);
    if (depth >= this.maxDepth) return '<max-depth>';
    if (Array.isArray(value)) {
      const items = value.slice(0, this.maxArrayItems)
        .map((item) => this.sanitizeValue(item, depth + 1, null));
      if (value.length > this.maxArrayItems) items.push(`<${value.length - this.maxArrayItems} more>`);
      return items;
    }
    if (!isRecord(value)) return `<unsupported:${typeof value}>`;
    const output: Record<string, unknown> = {};
    const entries = Object.entries(value).slice(0, this.maxObjectKeys);
    for (const [childKey, childValue] of entries) {
      output[childKey] = this.sanitizeValue(childValue, depth + 1, childKey);
    }
    if (Object.keys(value).length > this.maxObjectKeys) output._truncatedKeys = true;
    return output;
  }
}

function replaceAllPathForms(value: string, absolutePath: string, replacement: string): string {
  const forms = new Set([
    absolutePath,
    absolutePath.replaceAll('\\', '/'),
    absolutePath.replaceAll('/', '\\'),
  ]);
  let result = value;
  for (const form of forms) {
    if (!form) continue;
    result = result.replace(new RegExp(escapeRegExp(form), process.platform === 'win32' ? 'gi' : 'g'), replacement);
  }
  return result;
}

function redactCommonExternalPaths(value: string): string {
  return value
    .replace(/\b[A-Za-z]:[\\/][^\s\r\n\t"'<>|]+/g, '<external-path>')
    .replace(/(?<![A-Za-z0-9_$])\/(?:[A-Za-z0-9._-]+\/)+[A-Za-z0-9._-]+/g, '<external-path>');
}

function comparablePath(value: string): string {
  const normalized = normalizeSeparators(path.resolve(value)).replace(/\/$/, '');
  return process.platform === 'win32' ? normalized.toLowerCase() : normalized;
}

function normalizeSeparators(value: string): string {
  return value.replaceAll('\\', '/');
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function truncate(value: string, maxCharacters: number): string {
  const characters = Array.from(value);
  if (characters.length <= maxCharacters) return value;
  return `${characters.slice(0, Math.max(1, maxCharacters - 1)).join('')}…`;
}

function bounded(value: number | undefined, fallback: number, min: number, max: number): number {
  if (!Number.isFinite(value)) return fallback;
  return Math.min(max, Math.max(min, Math.trunc(value as number)));
}
