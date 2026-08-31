import type { JsonRpcRequest } from './protocol.js';
import type { RuntimeWorkerCommandBatchV1 } from './runtime-client.js';

export type ManagedApprovalDecision = 'accept' | 'decline' | 'cancel';

export interface ApprovalBroker {
  decide(request: JsonRpcRequest, signal: AbortSignal): Promise<ManagedApprovalDecision>;
}

export class FailClosedApprovalBroker implements ApprovalBroker {
  async decide(_request: JsonRpcRequest, _signal: AbortSignal): Promise<ManagedApprovalDecision> {
    return 'cancel';
  }
}

export interface RuntimeApprovalCommandPort {
  commands(afterSequence: number): Promise<RuntimeWorkerCommandBatchV1>;
}

/** Polls one-shot Runtime decisions; malformed, mismatched and failed responses stay fail closed. */
export class RuntimeApprovalBroker implements ApprovalBroker {
  private cursor = 0;

  constructor(
    private readonly runtime: RuntimeApprovalCommandPort,
    private readonly pollIntervalMs = 1_000,
  ) {
    if (!Number.isSafeInteger(pollIntervalMs) || pollIntervalMs < 50 || pollIntervalMs > 30_000) {
      throw new Error('Runtime approval poll interval is invalid');
    }
  }

  async decide(request: JsonRpcRequest, signal: AbortSignal): Promise<ManagedApprovalDecision> {
    const approvalRequestId = String(request.id);
    while (!signal.aborted) {
      let batch: RuntimeWorkerCommandBatchV1;
      try {
        batch = await this.runtime.commands(this.cursor);
      } catch {
        return 'cancel';
      }
      if (!Number.isSafeInteger(batch.cursor) || batch.cursor < this.cursor || !Array.isArray(batch.commands)) {
        return 'cancel';
      }
      this.cursor = batch.cursor;
      for (const command of batch.commands) {
        if (command.type === 'CANCEL') return 'cancel';
        if (command.type !== 'APPROVAL_DECISION') continue;
        if (String(command.data?.approvalRequestId ?? '') !== approvalRequestId) continue;
        const decision = command.data?.decision;
        return decision === 'accept' || decision === 'decline' ? decision : 'cancel';
      }
      await abortableDelay(this.pollIntervalMs, signal);
    }
    return 'cancel';
  }
}

export function normalizeApprovalDecision(value: unknown): ManagedApprovalDecision {
  if (value === 'accept' || value === 'decline' || value === 'cancel') return value;
  return 'cancel';
}

function abortableDelay(milliseconds: number, signal: AbortSignal): Promise<void> {
  if (signal.aborted) return Promise.resolve();
  return new Promise((resolve) => {
    const timer = setTimeout(done, milliseconds);
    const onAbort = () => done();
    function done() {
      clearTimeout(timer);
      signal.removeEventListener('abort', onAbort);
      resolve();
    }
    signal.addEventListener('abort', onAbort, { once: true });
  });
}
