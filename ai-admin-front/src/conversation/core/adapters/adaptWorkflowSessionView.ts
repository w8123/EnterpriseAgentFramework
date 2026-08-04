import type { ConversationEventEnvelope } from '../conversationEvents'
import { createEvent } from '../conversationEvents'
import type {
  ConversationMessage,
  ConversationSnapshot,
} from '../conversationTypes'
import { createEmptySnapshot, createId, nowIso } from '../conversationTypes'
import {
  isBlockingUiRequest,
  isCardOnlyUiRequest,
  isTextOnlyUiRequest,
  normalizeUiRequest,
} from '../normalizeUiRequest'

export interface WorkflowDebugStepLike {
  index?: number
  nodeId?: string
  nodeName?: string
  nodeType?: string
  status?: string
  startedAt?: string
  finishedAt?: string
  error?: string
  input?: unknown
  output?: unknown
}

export interface WorkflowDebugMessageLike {
  id?: string
  role?: string
  content?: string
  uiRequest?: unknown
  createdAt?: string
}

export interface WorkflowDebugSessionViewLike {
  sessionId?: string
  status?: string
  messages?: WorkflowDebugMessageLike[]
  steps?: WorkflowDebugStepLike[]
  uiRequest?: unknown
  answer?: string
  traceId?: string
  errorMessage?: string
  currentNodeId?: string
}

function mapMessage(raw: WorkflowDebugMessageLike): ConversationMessage {
  const blocks: ConversationMessage['blocks'] = []
  const ui = normalizeUiRequest(raw.uiRequest)
  if (raw.content && !isCardOnlyUiRequest(ui)) {
    blocks.push({
      id: createId('blk'),
      type: 'text',
      text: String(raw.content),
      status: 'completed',
    })
  }
  if (ui && !isTextOnlyUiRequest(ui)) {
    blocks.push({
      id: createId('blk'),
      type: 'interaction',
      request: ui,
      state: isBlockingUiRequest(raw.uiRequest) ? 'waiting' : 'resolved',
    })
  }
  const role = (raw.role || 'assistant').toLowerCase()
  return {
    id: raw.id || createId('msg'),
    role: role === 'user' ? 'user' : role === 'system' ? 'system' : 'assistant',
    status: 'completed',
    blocks,
    createdAt: raw.createdAt || nowIso(),
  }
}

export function adaptWorkflowSessionViewToSnapshot(view: WorkflowDebugSessionViewLike): ConversationSnapshot {
  const snapshot = createEmptySnapshot(view.sessionId)
  snapshot.messages = (view.messages || []).map(mapMessage)

  if (view.uiRequest) {
    const ui = normalizeUiRequest(view.uiRequest)
    const lastAssistant = [...snapshot.messages].reverse().find((m) => m.role === 'assistant')
    if (ui && lastAssistant && isCardOnlyUiRequest(ui)) {
      lastAssistant.blocks = lastAssistant.blocks.filter((block) => block.type !== 'text')
    }
    if (ui && !isTextOnlyUiRequest(ui) && lastAssistant && !lastAssistant.blocks.some((b) => b.type === 'interaction' && b.request.interactionId === ui.interactionId)) {
      lastAssistant.blocks.push({
        id: createId('blk'),
        type: 'interaction',
        request: ui,
        state: isBlockingUiRequest(view.uiRequest) ? 'waiting' : 'resolved',
      })
    } else if (ui && !isTextOnlyUiRequest(ui) && !lastAssistant) {
      snapshot.messages.push({
        id: createId('msg'),
        role: 'assistant',
        status: 'completed',
        blocks: [{
          id: createId('blk'),
          type: 'interaction',
          request: ui,
          state: isBlockingUiRequest(view.uiRequest) ? 'waiting' : 'resolved',
        }],
        createdAt: nowIso(),
      })
    }
  }

  const status = String(view.status || '').toUpperCase()
  if (status === 'SUSPENDED' || status === 'WAITING' || status === 'WAITING_USER') {
    snapshot.turnStatus = 'waiting'
  } else if (status === 'FAILED' || status === 'ERROR' || status === 'EXPIRED') {
    snapshot.turnStatus = 'failed'
    snapshot.error = view.errorMessage ? String(view.errorMessage) : 'Workflow 调试失败'
  } else if (status === 'CANCELLED') {
    snapshot.turnStatus = 'cancelled'
  } else {
    snapshot.turnStatus = 'completed'
  }

  snapshot.metadata = {
    traceId: view.traceId,
    currentNodeId: view.currentNodeId,
    status: view.status,
    steps: view.steps,
  }
  return snapshot
}

/**
 * 将 WorkflowDebugSessionView REST 快照转为公共事件 + 节点调试事件。
 */
export function adaptWorkflowSessionView(
  view: WorkflowDebugSessionViewLike,
  _options: { isRestore?: boolean } = {},
): { snapshot: ConversationSnapshot } {
  return { snapshot: adaptWorkflowSessionViewToSnapshot(view) }
}

export function workflowSessionViewToEvents(
  view: WorkflowDebugSessionViewLike,
  options: { isRestore?: boolean; previousStepCount?: number } = {},
): Generator<ConversationEventEnvelope> {
  if (options.isRestore) {
    const snapshot = adaptWorkflowSessionViewToSnapshot(view)
    return (function* () {
      yield createEvent('session.restored', snapshot, { sessionId: snapshot.sessionId })
    })()
  }
  return adaptWorkflowSessionViewToEvents(view, { previousStepCount: options.previousStepCount })
}

export function* adaptWorkflowSessionViewToEvents(
  view: WorkflowDebugSessionViewLike,
  options: { previousStepCount?: number } = {},
): Generator<ConversationEventEnvelope> {
  const sessionId = view.sessionId
  const traceId = view.traceId ? String(view.traceId) : undefined
  const steps = view.steps || []
  const startIndex = options.previousStepCount || 0

  if (sessionId) {
    yield createEvent('session.created', { sessionId }, { sessionId, traceId })
  }

  for (let i = startIndex; i < steps.length; i += 1) {
    const step = steps[i]
    const nodeStatus = String(step.status || '').toUpperCase()
    const payload = {
      index: step.index ?? i,
      nodeId: step.nodeId,
      nodeName: step.nodeName,
      nodeType: step.nodeType,
      status: step.status,
      input: step.input,
      output: step.output,
      error: step.error,
    }
    if (nodeStatus === 'RUNNING' || nodeStatus === 'STARTED') {
      yield createEvent('debug.workflow.node.started', payload, { sessionId, traceId })
    } else if (nodeStatus === 'WAITING' || nodeStatus === 'WAITING_USER') {
      yield createEvent('debug.workflow.node.waiting', payload, { sessionId, traceId })
    } else if (nodeStatus === 'FAILED' || nodeStatus === 'ERROR') {
      yield createEvent('debug.workflow.node.failed', payload, { sessionId, traceId })
    } else {
      yield createEvent('debug.workflow.node.completed', payload, { sessionId, traceId })
    }
  }

  if (traceId) {
    yield createEvent('debug.trace.available', { traceId }, { sessionId, traceId })
  }

  const snapshot = adaptWorkflowSessionViewToSnapshot(view)
  // 发出消息增量：以 snapshot 消息为准，简化为完成事件
  const lastAssistant = [...snapshot.messages].reverse().find((m) => m.role === 'assistant')
  const interaction = lastAssistant?.blocks.find((block) => block.type === 'interaction')
  const effectiveUiRequest = view.uiRequest
    || (interaction?.type === 'interaction' ? interaction.request : undefined)
  const visibleAnswer = lastAssistant?.blocks.filter((b) => b.type === 'text').map((b) => (b as { text: string }).text).join('')
    || (!isCardOnlyUiRequest(effectiveUiRequest) && view.answer ? String(view.answer) : '')
  const completionAnswer = view.answer ? String(view.answer) : visibleAnswer

  const status = String(view.status || '').toUpperCase()
  if (status === 'SUSPENDED' || status === 'WAITING' || status === 'WAITING_USER') {
    if (visibleAnswer) yield createEvent('message.delta', { text: visibleAnswer }, { sessionId, traceId })
    if (effectiveUiRequest && !isTextOnlyUiRequest(effectiveUiRequest)) {
      yield createEvent('ui.requested', {
        uiRequest: effectiveUiRequest,
      }, { sessionId, traceId })
    }
    yield createEvent('turn.waiting', {
      answer: completionAnswer,
      uiRequest: effectiveUiRequest,
      sessionId,
      traceId,
      result: view,
    }, { sessionId, traceId })
  } else if (status === 'FAILED' || status === 'ERROR' || status === 'EXPIRED') {
    yield createEvent('turn.failed', {
      message: view.errorMessage || 'Workflow 调试失败',
      error: view.errorMessage,
    }, { sessionId, traceId })
  } else if (status === 'CANCELLED') {
    yield createEvent('turn.cancelled', { sessionId }, { sessionId, traceId })
  } else {
    if (visibleAnswer) yield createEvent('message.delta', { text: visibleAnswer }, { sessionId, traceId })
    if (effectiveUiRequest && !isTextOnlyUiRequest(effectiveUiRequest)) {
      yield createEvent('ui.requested', { uiRequest: effectiveUiRequest }, { sessionId, traceId })
    }
    yield createEvent('turn.completed', {
      answer: completionAnswer,
      uiRequest: effectiveUiRequest,
      sessionId,
      traceId,
      result: view,
    }, { sessionId, traceId })
  }
}
