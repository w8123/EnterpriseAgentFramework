import assert from 'node:assert/strict';
import test from 'node:test';
import type { ApprovalBroker, ManagedApprovalDecision } from './approval.js';
import type { ManagedExecutionEventV1 } from './managed-events.js';
import type { JsonRpcId, JsonRpcNotification, JsonRpcRequest } from './protocol.js';
import {
  ManagedExecutorRunner,
  type AppServerPort,
  type ManagedEventSink,
} from './runner.js';

test('runs one ephemeral workspace-write Codex turn through the documented lifecycle', async () => {
  const port = new FakeAppServerPort();
  const events: ManagedExecutionEventV1[] = [];
  const runner = new ManagedExecutorRunner(port, sink(events));

  const result = await runner.run({
    executionId: 'mex-runner',
    workspaceRoot: absoluteWorkspace(),
    objective: 'Inspect the repository and produce a tested patch.',
    sandboxProfile: 'WORKSPACE_PATCH',
    maxWallTimeMs: 5_000,
  });

  assert.deepEqual(result, { threadId: 'thr-1', turnId: 'turn-1', status: 'completed' });
  assert.deepEqual(port.notifications, [{ method: 'initialized', params: undefined }]);
  const threadStart = port.requests.find((request) => request.method === 'thread/start');
  assert.equal((threadStart?.params as Record<string, unknown>).ephemeral, true);
  assert.equal((threadStart?.params as Record<string, unknown>).sandbox, 'workspace-write');
  const turnStart = port.requests.find((request) => request.method === 'turn/start');
  const sandboxPolicy = ((turnStart?.params as Record<string, unknown>).sandboxPolicy) as Record<string, unknown>;
  assert.equal(sandboxPolicy.type, 'workspaceWrite');
  assert.equal(sandboxPolicy.networkAccess, false);
  assert.ok(events.some((event) => event.type === 'TURN_STARTED'));
  assert.ok(events.some((event) => event.type === 'TURN_COMPLETED' && event.phase === 'SUCCEEDED'));
});

test('fails closed on app-server approval requests by default', async () => {
  const port = new FakeAppServerPort({ requestApproval: true });
  const events: ManagedExecutionEventV1[] = [];
  const runner = new ManagedExecutorRunner(port, sink(events));

  await runner.run({
    executionId: 'mex-approval',
    workspaceRoot: absoluteWorkspace(),
    objective: 'Run a command that requires approval.',
    sandboxProfile: 'ANALYZE_READONLY',
    maxWallTimeMs: 5_000,
  });

  assert.deepEqual(port.responses, [{ id: 'approval-1', result: { decision: 'cancel' } }]);
  assert.ok(events.some((event) => event.type === 'APPROVAL_REQUESTED'));
  assert.ok(events.some((event) => event.type === 'APPROVAL_RESOLVED'
    && event.data.decision === 'cancel'));
});

test('passes only one-shot approval decisions from an explicit broker', async () => {
  const port = new FakeAppServerPort({ requestApproval: true });
  const broker: ApprovalBroker = {
    async decide(_request, _signal): Promise<ManagedApprovalDecision> {
      return 'accept';
    },
  };
  const runner = new ManagedExecutorRunner(port, sink([]), broker);
  await runner.run({
    executionId: 'mex-approval-explicit',
    workspaceRoot: absoluteWorkspace(),
    objective: 'Apply a safe workspace-only patch.',
    sandboxProfile: 'WORKSPACE_PATCH',
    maxWallTimeMs: 5_000,
  });
  assert.deepEqual(port.responses, [{ id: 'approval-1', result: { decision: 'accept' } }]);
});

test('cancels an approval when a broker ignores its abort signal', async () => {
  const port = new FakeAppServerPort({ requestApproval: true });
  const broker: ApprovalBroker = {
    decide(): Promise<ManagedApprovalDecision> {
      return new Promise(() => undefined);
    },
  };
  const runner = new ManagedExecutorRunner(port, sink([]), broker);
  const started = Date.now();
  await runner.run({
    executionId: 'mex-approval-timeout',
    workspaceRoot: absoluteWorkspace(),
    objective: 'Request an approval that will time out.',
    sandboxProfile: 'WORKSPACE_PATCH',
    maxWallTimeMs: 5_000,
    approvalTimeoutMs: 1_000,
  });
  assert.ok(Date.now() - started < 3_000);
  assert.deepEqual(port.responses, [{ id: 'approval-1', result: { decision: 'cancel' } }]);
});

function sink(events: ManagedExecutionEventV1[]): ManagedEventSink {
  return {
    async emit(event) {
      events.push(event);
    },
  };
}

interface FakePortOptions {
  requestApproval?: boolean;
}

class FakeAppServerPort implements AppServerPort {
  readonly requests: Array<{ method: string; params: unknown }> = [];
  readonly notifications: Array<{ method: string; params: unknown }> = [];
  readonly responses: Array<{ id: JsonRpcId; result: unknown }> = [];
  private readonly notificationListeners = new Set<(notification: JsonRpcNotification) => void>();
  private readonly requestListeners = new Set<(request: JsonRpcRequest) => void>();
  private readonly protocolErrorListeners = new Set<(error: Error) => void>();
  private readonly closeListeners = new Set<(error: Error) => void>();

  constructor(private readonly options: FakePortOptions = {}) {
  }

  async request<T>(method: string, params?: unknown): Promise<T> {
    this.requests.push({ method, params });
    if (method === 'initialize') return {} as T;
    if (method === 'thread/start') return { thread: { id: 'thr-1' } } as T;
    if (method === 'turn/start') {
      queueMicrotask(() => {
        this.emitNotification({
          method: 'turn/started',
          params: { threadId: 'thr-1', turn: { id: 'turn-1' } },
        });
        if (this.options.requestApproval) {
          this.emitRequest({
            id: 'approval-1',
            method: 'item/commandExecution/requestApproval',
            params: {
              threadId: 'thr-1',
              turnId: 'turn-1',
              itemId: 'command-1',
              command: ['git', 'status'],
            },
          });
        } else {
          this.complete();
        }
      });
      return { turn: { id: 'turn-1', status: 'inProgress' } } as T;
    }
    if (method === 'turn/interrupt') return {} as T;
    throw new Error(`Unexpected request: ${method}`);
  }

  notify(method: string, params?: unknown): void {
    this.notifications.push({ method, params });
  }

  respond(id: JsonRpcId, result: unknown): void {
    this.responses.push({ id, result });
    if (id === 'approval-1') queueMicrotask(() => this.complete());
  }

  onNotification(listener: (notification: JsonRpcNotification) => void): () => void {
    this.notificationListeners.add(listener);
    return () => this.notificationListeners.delete(listener);
  }

  onServerRequest(listener: (request: JsonRpcRequest) => void): () => void {
    this.requestListeners.add(listener);
    return () => this.requestListeners.delete(listener);
  }

  onProtocolError(listener: (error: Error) => void): () => void {
    this.protocolErrorListeners.add(listener);
    return () => this.protocolErrorListeners.delete(listener);
  }

  onClose(listener: (error: Error) => void): () => void {
    this.closeListeners.add(listener);
    return () => this.closeListeners.delete(listener);
  }

  private emitNotification(notification: JsonRpcNotification): void {
    for (const listener of this.notificationListeners) listener(notification);
  }

  private emitRequest(request: JsonRpcRequest): void {
    for (const listener of this.requestListeners) listener(request);
  }

  private complete(): void {
    this.emitNotification({
      method: 'turn/completed',
      params: { threadId: 'thr-1', turn: { id: 'turn-1', status: 'completed' } },
    });
  }
}

function absoluteWorkspace(): string {
  return process.platform === 'win32' ? 'C:\\sandbox\\workspace' : '/sandbox/workspace';
}
