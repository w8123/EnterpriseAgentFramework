/**
 * Embed SDK 安全出口：只导出 Embed 运行所需的 core / transport / UI，
 * 避免把 AgentDebug / WorkflowDraft（依赖管理端 API）打进 SDK bundle。
 */
export {
  createEmptySnapshot,
  createId,
  nowIso,
  textContentOf,
  type ConversationSnapshot,
  type ConversationMessage,
  type ConversationBlock,
  type ConversationTurnInput,
  type UiRequestV1,
  type InteractionRenderState,
} from './core/conversationTypes'

export {
  createConversationController,
  type ConversationController,
  type ConversationControllerOptions,
} from './core/createConversationController'

export { normalizeUiRequest } from './core/normalizeUiRequest'
export { createEmbedTransport, type EmbedTransportOptions } from './transports/createEmbedTransport'

export { default as ConversationView } from './components/ConversationView.vue'
export { default as UnifiedInteractionRenderer } from './components/UnifiedInteractionRenderer.vue'

export {
  registerCustomInteractionRenderer,
  unregisterCustomInteractionRenderer,
  resolveRendererKind,
} from './renderers/interactionRegistry'
