import { isRecord, type JsonRpcNotification, type JsonRpcRequest } from './protocol.js';
import { ManagedEventSanitizer } from './sanitizer.js';

export type ManagedExecutionEventType =
  | 'THREAD_STARTED'
  | 'TURN_STARTED'
  | 'COMMAND_STARTED'
  | 'COMMAND_COMPLETED'
  | 'FILE_CHANGE_STARTED'
  | 'FILE_CHANGE_COMPLETED'
  | 'TOOL_STARTED'
  | 'TOOL_COMPLETED'
  | 'DIFF_UPDATED'
  | 'FINAL_MESSAGE'
  | 'APPROVAL_REQUESTED'
  | 'APPROVAL_RESOLVED'
  | 'TURN_COMPLETED'
  | 'APP_SERVER_ERROR';

export type ManagedExecutionPhase =
  | 'PROVISIONING'
  | 'RUNNING'
  | 'WAITING_APPROVAL'
  | 'FINALIZING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CANCELLED';

export interface ManagedExecutionEventV1 {
  schema: 'reachai.managed-execution.event.v1';
  executionId: string;
  sequence: number;
  eventId: string;
  occurredAt: string;
  type: ManagedExecutionEventType;
  phase: ManagedExecutionPhase;
  visibility: 'OPERATOR' | 'PUBLIC';
  persistence: 'DURABLE';
  message: string;
  data: Record<string, unknown>;
}

export interface ManagedEventProjectorOptions {
  executionId: string;
  workspaceRoot: string;
  clock?: () => Date;
}

export class ManagedEventProjector {
  private readonly sanitizer: ManagedEventSanitizer;
  private readonly clock: () => Date;
  private sequence = 0;

  constructor(private readonly options: ManagedEventProjectorOptions) {
    if (!options.executionId.trim()) throw new Error('executionId is required');
    this.sanitizer = new ManagedEventSanitizer({ workspaceRoot: options.workspaceRoot });
    this.clock = options.clock || (() => new Date());
  }

  projectNotification(notification: JsonRpcNotification): ManagedExecutionEventV1[] {
    if (isReasoning(notification.method, notification.params)
      || isOutputDelta(notification.method)) {
      return [];
    }
    const params = record(notification.params);
    switch (notification.method) {
      case 'thread/started':
        return [this.event('THREAD_STARTED', 'RUNNING', 'OPERATOR', 'Codex thread started', {
          threadId: nestedText(params, 'thread', 'id'),
        })];
      case 'turn/started':
        return [this.event('TURN_STARTED', 'RUNNING', 'OPERATOR', 'Codex turn started', {
          threadId: text(params.threadId),
          turnId: nestedText(params, 'turn', 'id') || text(params.turnId),
        })];
      case 'turn/diff/updated':
        return [this.event('DIFF_UPDATED', 'RUNNING', 'OPERATOR', 'Workspace diff updated', {
          threadId: text(params.threadId),
          turnId: text(params.turnId),
        })];
      case 'item/started':
        return this.projectItem(record(params.item), true, params);
      case 'item/completed':
        return this.projectItem(record(params.item), false, params);
      case 'turn/completed':
        return [this.projectTurnCompleted(params)];
      case 'error':
        return [this.event('APP_SERVER_ERROR', 'FAILED', 'OPERATOR', 'Codex app-server reported an error', {
          code: nestedText(params, 'error', 'code') || text(params.code),
          message: this.sanitizer.text(nested(params, 'error', 'message') ?? params.message),
        })];
      default:
        return [];
    }
  }

  projectServerRequest(request: JsonRpcRequest): ManagedExecutionEventV1[] {
    const params = record(request.params);
    if (request.method === 'item/commandExecution/requestApproval') {
      return [this.event('APPROVAL_REQUESTED', 'WAITING_APPROVAL', 'OPERATOR', 'Command approval required', {
        approvalRequestId: String(request.id),
        approvalKind: 'COMMAND',
        threadId: text(params.threadId),
        turnId: text(params.turnId),
        itemId: text(params.itemId),
        reason: this.sanitizer.text(params.reason),
        command: this.sanitizer.command(params.command),
        cwd: this.sanitizer.path(params.cwd),
        networkHost: nestedText(params, 'networkApprovalContext', 'host'),
        networkProtocol: nestedText(params, 'networkApprovalContext', 'protocol'),
      })];
    }
    if (request.method === 'item/fileChange/requestApproval') {
      return [this.event('APPROVAL_REQUESTED', 'WAITING_APPROVAL', 'OPERATOR', 'File change approval required', {
        approvalRequestId: String(request.id),
        approvalKind: 'FILE_CHANGE',
        threadId: text(params.threadId),
        turnId: text(params.turnId),
        itemId: text(params.itemId),
        reason: this.sanitizer.text(params.reason),
      })];
    }
    if (request.method === 'item/permissions/requestApproval') {
      return [this.event('APPROVAL_REQUESTED', 'WAITING_APPROVAL', 'OPERATOR', 'Additional permission request denied by default', {
        approvalRequestId: String(request.id),
        approvalKind: 'PERMISSIONS',
        threadId: text(params.threadId),
        turnId: text(params.turnId),
        itemId: text(params.itemId),
      })];
    }
    return [];
  }

  approvalResolved(request: JsonRpcRequest, decision: 'accept' | 'decline' | 'cancel'): ManagedExecutionEventV1 {
    return this.event('APPROVAL_RESOLVED', 'RUNNING', 'OPERATOR', 'Approval request resolved', {
      approvalRequestId: String(request.id),
      approvalKind: approvalKind(request.method),
      decision,
    });
  }

  protocolError(error: Error): ManagedExecutionEventV1 {
    return this.event('APP_SERVER_ERROR', 'FAILED', 'OPERATOR', 'Codex app-server protocol error', {
      message: this.sanitizer.text(error.message),
    });
  }

  private projectItem(
    item: Record<string, unknown>,
    started: boolean,
    params: Record<string, unknown>,
  ): ManagedExecutionEventV1[] {
    const itemType = text(item.type);
    if (!itemType || itemType.toLowerCase().includes('reasoning')) return [];
    const common = {
      threadId: text(params.threadId),
      turnId: text(params.turnId),
      itemId: text(item.id),
    };
    if (itemType === 'commandExecution') {
      return [this.event(
        started ? 'COMMAND_STARTED' : 'COMMAND_COMPLETED',
        'RUNNING',
        'OPERATOR',
        started ? 'Command started' : 'Command completed',
        {
          ...common,
          command: this.sanitizer.command(item.command),
          cwd: this.sanitizer.path(item.cwd),
          ...(started ? {} : {
            status: this.sanitizer.text(item.status, 128),
            exitCode: number(item.exitCode),
            durationMs: number(item.durationMs),
          }),
        },
      )];
    }
    if (itemType === 'fileChange') {
      const changes = Array.isArray(item.changes)
        ? item.changes.slice(0, 100).map((change) => {
          const row = record(change);
          return {
            path: this.sanitizer.path(row.path),
            kind: this.sanitizer.text(row.kind, 64),
          };
        })
        : [];
      return [this.event(
        started ? 'FILE_CHANGE_STARTED' : 'FILE_CHANGE_COMPLETED',
        'RUNNING',
        'OPERATOR',
        started ? 'File change started' : 'File change completed',
        { ...common, changes },
      )];
    }
    if (!started && itemType === 'agentMessage') {
      return [this.event('FINAL_MESSAGE', 'FINALIZING', 'PUBLIC', 'Codex produced a final message', {
        ...common,
        text: this.sanitizer.text(item.text ?? item.message ?? item.content, 16_000),
      })];
    }
    if (isToolItem(itemType)) {
      return [this.event(
        started ? 'TOOL_STARTED' : 'TOOL_COMPLETED',
        'RUNNING',
        'OPERATOR',
        started ? 'Codex tool started' : 'Codex tool completed',
        {
          ...common,
          toolType: this.sanitizer.text(itemType, 128),
          toolName: this.sanitizer.text(item.name ?? item.toolName, 256),
          ...(started ? {} : { status: this.sanitizer.text(item.status, 128) }),
        },
      )];
    }
    return [];
  }

  private projectTurnCompleted(params: Record<string, unknown>): ManagedExecutionEventV1 {
    const turn = record(params.turn);
    const status = text(turn.status ?? params.status) || 'unknown';
    const normalized = status.toLowerCase();
    const phase: ManagedExecutionPhase = normalized === 'completed'
      ? 'SUCCEEDED'
      : normalized === 'interrupted'
        ? 'CANCELLED'
        : 'FAILED';
    return this.event('TURN_COMPLETED', phase, 'OPERATOR', `Codex turn ${status}`, {
      threadId: text(params.threadId),
      turnId: text(turn.id ?? params.turnId),
      status,
      errorCode: nestedText(turn, 'error', 'code'),
      errorMessage: this.sanitizer.text(nested(turn, 'error', 'message')),
    });
  }

  private event(
    type: ManagedExecutionEventType,
    phase: ManagedExecutionPhase,
    visibility: 'OPERATOR' | 'PUBLIC',
    message: string,
    data: Record<string, unknown>,
  ): ManagedExecutionEventV1 {
    const sequence = ++this.sequence;
    return {
      schema: 'reachai.managed-execution.event.v1',
      executionId: this.options.executionId,
      sequence,
      eventId: `${this.options.executionId}:${sequence}`,
      occurredAt: this.clock().toISOString(),
      type,
      phase,
      visibility,
      persistence: 'DURABLE',
      message,
      data: this.sanitizer.boundedObject(data),
    };
  }
}

function record(value: unknown): Record<string, unknown> {
  return isRecord(value) ? value : {};
}

function text(value: unknown): string | null {
  if (value === null || value === undefined) return null;
  return String(value);
}

function number(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

function nested(value: Record<string, unknown>, parent: string, child: string): unknown {
  const parentValue = record(value[parent]);
  return parentValue[child];
}

function nestedText(value: Record<string, unknown>, parent: string, child: string): string | null {
  return text(nested(value, parent, child));
}

function isReasoning(method: string, params: unknown): boolean {
  if (method.toLowerCase().includes('reasoning')) return true;
  const item = record(record(params).item);
  return (text(item.type) || '').toLowerCase().includes('reasoning');
}

function isOutputDelta(method: string): boolean {
  return method === 'item/commandExecution/outputDelta'
    || method === 'item/reasoning/textDelta'
    || method === 'item/reasoning/summaryTextDelta';
}

function isToolItem(type: string): boolean {
  return ['mcpToolCall', 'dynamicToolCall', 'webSearch', 'imageGeneration'].includes(type);
}

function approvalKind(method: string): string {
  if (method.includes('commandExecution')) return 'COMMAND';
  if (method.includes('fileChange')) return 'FILE_CHANGE';
  if (method.includes('permissions')) return 'PERMISSIONS';
  return 'UNKNOWN';
}
