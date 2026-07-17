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

export type ConversationTransportKind = 'agent-debug' | 'workflow-draft' | 'embed'

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

  restoreSession?(): Promise<ConversationSnapshot | null>
  cancelTurn?(): Promise<void>
  clearSession?(): Promise<void>
  dispose(): void
}
