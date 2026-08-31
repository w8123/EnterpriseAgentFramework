import { open, type FileHandle } from 'node:fs/promises';
import type { ManagedExecutionEventV1 } from './managed-events.js';
import type { ManagedEventSink } from './runner.js';

export class NdjsonEventSink implements ManagedEventSink {
  private closed = false;

  private constructor(private readonly handle: FileHandle) {
  }

  static async create(filePath: string): Promise<NdjsonEventSink> {
    return new NdjsonEventSink(await open(filePath, 'wx', 0o600));
  }

  async emit(event: ManagedExecutionEventV1): Promise<void> {
    if (this.closed) throw new Error('Managed event sink is closed');
    await this.handle.appendFile(`${JSON.stringify(event)}\n`, 'utf8');
    await this.handle.sync();
  }

  async close(): Promise<void> {
    if (this.closed) return;
    this.closed = true;
    await this.handle.close();
  }
}
