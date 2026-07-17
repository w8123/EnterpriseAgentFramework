<template>
  <div class="reachai-block" :data-type="block.type">
    <div v-if="block.type === 'text'" class="reachai-block__text" :class="{ 'is-streaming': block.status === 'streaming' }">
      {{ block.text }}
      <span v-if="block.status === 'streaming'" class="reachai-block__caret" aria-hidden="true" />
    </div>
    <UnifiedInteractionRenderer
      v-else-if="block.type === 'interaction'"
      :request="block.request"
      :state="block.state"
      :error-message="block.errorMessage"
      @submit="(action, values) => onInteractionSubmit(block, action, values)"
      @cancel="onInteractionCancel(block)"
    />
    <div v-else-if="block.type === 'page_action'" class="reachai-block__page-action">
      <strong>{{ block.title || block.actionKey }}</strong>
      <span>{{ block.status }}</span>
      <p v-if="block.message">{{ block.message }}</p>
    </div>
    <div v-else-if="block.type === 'notice'" class="reachai-block__notice" :data-level="block.level || 'info'">
      {{ block.text }}
    </div>
    <div v-else-if="block.type === 'error'" class="reachai-block__error" role="alert">
      {{ block.text }}
      <button v-if="block.retryable" type="button" @click="$emit('retry')">重试</button>
    </div>
  </div>
</template>

<script setup lang="ts">
import type { ConversationBlock, ConversationInteractionBlock } from '../core/conversationTypes'
import UnifiedInteractionRenderer from './UnifiedInteractionRenderer.vue'

defineProps<{
  block: ConversationBlock
}>()

const emit = defineEmits<{
  'interaction-submit': [interactionId: string, action: string, values: Record<string, unknown>]
  'interaction-cancel': [interactionId: string]
  retry: []
}>()

function onInteractionSubmit(
  block: ConversationBlock,
  action: string,
  values: Record<string, unknown>,
) {
  if (block.type !== 'interaction') return
  emit('interaction-submit', (block as ConversationInteractionBlock).request.interactionId, action, values)
}

function onInteractionCancel(block: ConversationBlock) {
  if (block.type !== 'interaction') return
  emit('interaction-cancel', (block as ConversationInteractionBlock).request.interactionId)
}
</script>

<style scoped>
.reachai-block__text {
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--reachai-chat-text, #1e293b);
  font-size: 14px;
  font-weight: 500;
  line-height: 1.72;
}
.reachai-block__caret {
  display: inline-block;
  width: 6px;
  height: 1em;
  margin-left: 2px;
  vertical-align: text-bottom;
  background: var(--reachai-chat-primary, #6366f1);
  animation: reachai-blink 1s steps(1) infinite;
}
.reachai-block__notice {
  font-size: 13px;
  color: var(--reachai-chat-text-muted, #5b6b69);
  padding: 6px 0;
}
.reachai-block__error {
  color: var(--reachai-chat-danger, #b42318);
  font-size: 13px;
}
.reachai-block__page-action {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 13px;
  padding: 8px;
  border: 1px dashed var(--reachai-chat-border, #d7e0de);
  border-radius: 8px;
}
@keyframes reachai-blink {
  50% { opacity: 0; }
}
@media (prefers-reduced-motion: reduce) {
  .reachai-block__caret { animation: none; opacity: 0.5; }
}
</style>
