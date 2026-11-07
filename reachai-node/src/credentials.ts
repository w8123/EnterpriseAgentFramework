import { randomBytes, createCipheriv, createDecipheriv } from 'node:crypto';
import { promises as fs } from 'node:fs';
import { dirname } from 'node:path';

export interface Credentials { appKey: string; appSecret: string }
export interface CredentialStore {
  load(): Promise<Credentials | null>;
  save(value: Credentials): Promise<void>;
  enrollmentUncertain(): Promise<boolean>;
  markEnrollmentStarted(): Promise<void>;
  clearEnrollmentStarted(): Promise<void>;
}
function validate(value: Credentials): Credentials {
  if (!value || typeof value.appKey !== 'string' || !value.appKey.trim() || typeof value.appSecret !== 'string' || !value.appSecret.trim()) throw new Error('Project credentials required');
  return value;
}
/** Key is a 32-byte key supplied independently of the file, as base64. Protect the directory with OS ACLs. */
export class EncryptedFileCredentialStore implements CredentialStore {
  #key: Buffer;
  constructor(private readonly path: string, keyBase64: string) {
    this.#key = Buffer.from(keyBase64, 'base64');
    if (this.#key.length !== 32 || this.#key.toString('base64') !== keyBase64) throw new Error('Credential key must be canonical base64 for 32 random bytes');
  }
  private get marker(): string { return this.path + '.enrollment-pending'; }
  async load(): Promise<Credentials | null> {
    let data: Buffer; try { data = await fs.readFile(this.path); } catch (e) { if ((e as NodeJS.ErrnoException).code === 'ENOENT') return null; throw e; }
    if (data.length < 29 || data[0] !== 1) throw new Error('Invalid credential file');
    const decipher = createDecipheriv('aes-256-gcm', this.#key, data.subarray(1,13));
    decipher.setAuthTag(data.subarray(13,29));
    return validate(JSON.parse(Buffer.concat([decipher.update(data.subarray(29)), decipher.final()]).toString('utf8')));
  }
  async save(value: Credentials): Promise<void> {
    validate(value); await fs.mkdir(dirname(this.path), { recursive: true, mode: 0o700 });
    const iv = randomBytes(12), cipher = createCipheriv('aes-256-gcm', this.#key, iv);
    const encrypted = Buffer.concat([cipher.update(JSON.stringify(value), 'utf8'), cipher.final()]);
    const data = Buffer.concat([Buffer.from([1]), iv, cipher.getAuthTag(), encrypted]);
    const temporary = this.path + '.' + randomBytes(12).toString('hex') + '.tmp';
    const handle = await fs.open(temporary, 'wx', 0o600);
    try { await handle.writeFile(data); await handle.sync(); } finally { await handle.close(); }
    try { await fs.link(temporary, this.path); } finally { await fs.unlink(temporary); }
  }
  async enrollmentUncertain(): Promise<boolean> { try { await fs.access(this.marker); return true; } catch (e) { if ((e as NodeJS.ErrnoException).code === 'ENOENT') return false; throw e; } }
  async markEnrollmentStarted(): Promise<void> {
    await fs.mkdir(dirname(this.marker), { recursive: true, mode: 0o700 });
    const handle = await fs.open(this.marker, 'wx', 0o600);
    try { await handle.writeFile('Enrollment outcome may be uncertain; verify before retrying.\n'); await handle.sync(); } finally { await handle.close(); }
  }
  async clearEnrollmentStarted(): Promise<void> { try { await fs.unlink(this.marker); } catch (e) { if ((e as NodeJS.ErrnoException).code !== 'ENOENT') throw e; } }
}
