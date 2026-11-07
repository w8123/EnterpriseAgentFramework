import { createHash, createHmac, timingSafeEqual } from 'node:crypto';

function field(value: string): string {
  if (typeof value !== 'string' || !value.trim() || /[\r\n]/.test(value)) throw new Error('Invalid signature field');
  return value.trim();
}
const mac = (secret: string, value: string): string => createHmac('sha256', field(secret)).update(value, 'utf8').digest('hex');
export function registrySignature(projectCode: string, timestamp: string, nonce: string, secret: string): string {
  return mac(secret, [projectCode, timestamp, nonce].map(field).join('\n'));
}
export function bodySignature(method: string, path: string, projectCode: string, appKey: string, timestamp: string, nonce: string, body: Buffer, secret: string): string {
  path = field(path);
  if (!path.startsWith('/') || /[?#]/.test(path) || path.includes('..')) throw new Error('Invalid signed path');
  return mac(secret, ['REACHAI_PROJECT_REQUEST_V1', field(method).toUpperCase(), path, field(projectCode), field(appKey), field(timestamp), field(nonce), createHash('sha256').update(body).digest('hex')].join('\n'));
}
export function secureEqual(left: string, right: string): boolean {
  const a = Buffer.from(left, 'utf8'), b = Buffer.from(right, 'utf8');
  return a.length === b.length && timingSafeEqual(a, b);
}
export type Claims = Readonly<Record<string, unknown>>;
export function verifyInvocation(token: string, appKey: string, secret: string, projectCode: string, capabilityName: string, now = Math.floor(Date.now()/1000)): Claims {
  try {
    if (typeof token !== 'string' || token.length > 16384) throw new Error();
    const parts = token.split('.');
    if (parts.length !== 3 || parts.some(p => !/^[A-Za-z0-9_-]+$/.test(p))) throw new Error();
    const actual = Buffer.from(parts[2], 'base64url');
    const expected = createHmac('sha256', field(secret)).update(parts[0] + '.' + parts[1]).digest();
    if (actual.length !== expected.length || !timingSafeEqual(actual, expected)) throw new Error();
    const header = JSON.parse(Buffer.from(parts[0], 'base64url').toString('utf8'));
    const claims = JSON.parse(Buffer.from(parts[1], 'base64url').toString('utf8'));
    if (!header || header.alg !== 'HS256' || !claims || Array.isArray(claims) || typeof claims !== 'object') throw new Error();
    if (!Number.isInteger(claims.exp) || claims.exp <= now) throw new Error();
    for (const key of ['iat','nbf']) if (claims[key] !== undefined && (!Number.isInteger(claims[key]) || claims[key] > now)) throw new Error();
    if (claims.projectCode !== projectCode || claims.appKey !== appKey || claims.capabilityName !== capabilityName) throw new Error();
    return claims;
  } catch { throw new Error('Invalid invocation token'); }
}
