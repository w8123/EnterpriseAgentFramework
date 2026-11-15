import type {
  ConversationSnapshot,
  ConversationTurnInput,
  UiSubmitPayload,
} from '../core/conversationTypes'
import type { ConversationEventEnvelope } from '../core/conversationEvents'

export type { ConversationSnapshot, ConversationTurnInput, UiSubmitPayload }

export interface ConversationTransportCapabilities {
  eventStreaming: boolean
  tokenStreaming: boolean
  abort: boolean
  restore: boolean
  structuredInitialInput: boolean
  pageActions: boolean
}

export interface ApprovalStatusView {
  interactionId: string
  /** PENDING / APPROVED / REJECTED / CONTINUED_APPROVED / CONTINUED_REJECTED / WITHDRAWN / EXPIRED */
  status: string
  sessionStatus?: string
  assignees?: string
  approverName?: string
  comment?: string
  decidedAt?: string
  expiresAt?: string
}

export type ConversationTransportKind = 'agent-debug' | 'workflow-working-copy' | 'embed'

export interface ConversationTransport {
  readonly kind: ConversationTransportKind
  readonly capabilities: ConversationTransportCapabilities

  startTurn(
    input: ConversationTurnInput,
    signal?: AbortSignal,
  ): AsyncIterable<ConversationEventEnvelope>

  resumeInteraction?(
    interactionId: string,
    action: string,
    values: Record<string, unknown>,
    signal?: AbortSignal,
  ): AsyncIterable<ConversationEventEnvelope>

  /** 指定审批人的人工确认：查询审批进度（谁来批、是否已批）。不支持时不提供。 */
  approvalStatus?(interactionId: string): Promise<ApprovalStatusView | null>

  restoreSession?(): Promise<ConversationSnapshot | null>
  cancelTurn?(): Promise<void>
  clearSession?(): Promise<void>
  dispose(): void
}
