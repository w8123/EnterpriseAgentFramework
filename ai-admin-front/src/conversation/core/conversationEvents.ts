import type { ConversationSnapshot } from './conversationTypes'
import { nowIso } from './conversationTypes'

export type ConversationEventType =
  | 'session.created'
  | 'session.restored'
  | 'turn.started'
  | 'message.started'
  | 'message.delta'
  | 'ui.requested'
  | 'page.action.requested'
  | 'turn.waiting'
  | 'turn.completed'
  | 'turn.cancelled'
  | 'turn.failed'
  | 'debug.supervisor.step'
  | 'debug.workflow.node.started'
  | 'debug.workflow.node.completed'
  | 'debug.workflow.node.waiting'
  | 'debug.workflow.node.failed'
  | 'debug.workflow.node.delta'
  | 'debug.trace.available'

export interface ConversationEventEnvelope<T = unknown> {
  protocolVersion: '1.0'
  type: ConversationEventType | string
  sessionId?: string
  turnId?: string
  sequence?: number
  timestamp: string
  traceId?: string
  data: T
}

export interface MessageDeltaData {
  text: string
  messageId?: string
}

export interface UiRequestedData {
  uiRequest: unknown
  messageId?: string
}

export interface PageActionRequestedData {
  requestId: string
  actionKey: string
  title?: string
  args?: Record<string, unknown>
  confirm?: boolean
  target?: Record<string, unknown>
  metadata?: Record<string, unknown>
}

export interface TurnTerminalData {
  answer?: string
  uiRequest?: unknown
  sessionId?: string
  traceId?: string
  metadata?: Record<string, unknown>
  result?: unknown
  message?: string
  error?: string
}

export function isDebugEventType(type: string): boolean {
  return type.startsWith('debug.')
}

export function isPublicEventType(type: string): boolean {
  return !isDebugEventType(type)
}

export function createEvent<T>(
  type: ConversationEventType | string,
  data: T,
  extras: Partial<Omit<ConversationEventEnvelope<T>, 'protocolVersion' | 'type' | 'data' | 'timestamp'>> = {},
): ConversationEventEnvelope<T> {
  return {
    protocolVersion: '1.0',
    type,
    timestamp: nowIso(),
    data,
    ...extras,
  }
}

export function snapshotFromRestore(snapshot: ConversationSnapshot): ConversationEventEnvelope<ConversationSnapshot> {
  return createEvent('session.restored', snapshot, { sessionId: snapshot.sessionId })
}
