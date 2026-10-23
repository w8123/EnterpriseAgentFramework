<template>
  <div class="reachai-block" :data-type="block.type">
    <div
      v-if="block.type === 'text' && markdown"
      class="reachai-block__text reachai-block__text--markdown"
      :class="{ 'is-streaming': block.status === 'streaming' }"
    >
      <!-- renderSafeMarkdown 只保留白名单标签和安全链接，不输出可执行 HTML -->
      <div class="reachai-block__markdown" v-html="renderSafeMarkdown(block.text)" />
      <span v-if="block.status === 'streaming'" class="reachai-block__caret" aria-hidden="true" />
    </div>
    <div v-else-if="block.type === 'text'" class="reachai-block__text" :class="{ 'is-streaming': block.status === 'streaming' }">
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
import { renderSafeMarkdown } from '../../utils/safeMarkdown'
import UnifiedInteractionRenderer from './UnifiedInteractionRenderer.vue'

defineProps<{
  block: ConversationBlock
  /** 助手回复按 Markdown 渲染；用户输入保持原文。 */
  markdown?: boolean
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
.reachai-block__text--markdown {
  white-space: normal;
}
.reachai-block__markdown :deep(p),
.reachai-block__markdown :deep(ul),
.reachai-block__markdown :deep(ol),
.reachai-block__markdown :deep(pre),
.reachai-block__markdown :deep(blockquote),
.reachai-block__markdown :deep(table) {
  margin: 0 0 8px;
}
.reachai-block__markdown > :deep(:last-child) {
  margin-bottom: 0;
}
.reachai-block__markdown :deep(ul),
.reachai-block__markdown :deep(ol) {
  padding-left: 20px;
}
.reachai-block__markdown :deep(h1),
.reachai-block__markdown :deep(h2),
.reachai-block__markdown :deep(h3),
.reachai-block__markdown :deep(h4) {
  margin: 4px 0 6px;
  font-size: 15px;
  font-weight: 700;
}
.reachai-block__markdown :deep(strong) {
  font-weight: 700;
}
.reachai-block__markdown :deep(code) {
  padding: 1px 4px;
  border-radius: 4px;
  background: rgb(var(--reachai-chat-primary-rgb, 99 102 241) / 0.08);
  font-family: var(--font-family-mono, ui-monospace, monospace);
  font-size: 0.92em;
}
.reachai-block__markdown :deep(pre) {
  overflow-x: auto;
  padding: 8px 10px;
  border-radius: 8px;
  background: rgb(var(--reachai-chat-primary-rgb, 99 102 241) / 0.06);
}
.reachai-block__markdown :deep(pre code) {
  padding: 0;
  background: transparent;
}
.reachai-block__markdown :deep(table) {
  display: block;
  overflow-x: auto;
  border-collapse: collapse;
  font-size: 13px;
}
.reachai-block__markdown :deep(th),
.reachai-block__markdown :deep(td) {
  padding: 4px 8px;
  border: 1px solid rgb(var(--reachai-chat-primary-rgb, 99 102 241) / 0.16);
  text-align: left;
}
.reachai-block__markdown :deep(a) {
  color: var(--reachai-chat-primary, #6366f1);
}
.reachai-block__markdown :deep(img) {
  max-width: 100%;
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
