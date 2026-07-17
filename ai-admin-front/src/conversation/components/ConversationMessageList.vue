<template>
  <div ref="listEl" class="reachai-message-list" role="log" aria-live="polite" @scroll="onScroll">
    <div v-if="!messages.length" class="reachai-message-list__empty">
      <slot name="empty">开始对话吧</slot>
    </div>
    <ConversationMessageItem
      v-for="message in messages"
      :key="message.id"
      :message="message"
      :assistant-label="assistantLabel"
      :status-hint="resolveHint(message)"
      :thinking-presentation="resolveThinking(message)"
      :show-avatars="showAvatars"
      :surface="surface"
      @interaction-submit="(...args) => $emit('interaction-submit', ...args)"
      @interaction-cancel="(id) => $emit('interaction-cancel', id)"
      @retry="$emit('retry')"
    >
      <template v-if="$slots['message-meta']" #meta="slotProps">
        <slot name="message-meta" v-bind="slotProps" />
      </template>
    </ConversationMessageItem>
    <button
      v-if="showJump"
      type="button"
      class="reachai-message-list__jump"
      @click="scrollToBottom(true)"
    >
      回到底部
    </button>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onMounted, ref, watch } from 'vue'
import type {
  ConversationMessage,
  ConversationThinkingPresentation,
} from '../core/conversationTypes'
import ConversationMessageItem from './ConversationMessageItem.vue'

const props = withDefaults(defineProps<{
  messages: ConversationMessage[]
  autoFollow?: boolean
  assistantLabel?: string
  resolveStatusHint?: (message: ConversationMessage) => string | undefined
  resolveThinkingPresentation?: (message: ConversationMessage) => ConversationThinkingPresentation | undefined
  showAvatars?: boolean
  surface?: 'admin' | 'embed'
}>(), {
  autoFollow: true,
  assistantLabel: '',
  showAvatars: true,
  surface: 'admin',
})

defineEmits<{
  'interaction-submit': [interactionId: string, action: string, values: Record<string, unknown>]
  'interaction-cancel': [interactionId: string]
  retry: []
}>()

const listEl = ref<HTMLElement | null>(null)
const stickToBottom = ref(true)
const showJump = ref(false)

function resolveHint(message: ConversationMessage) {
  return props.resolveStatusHint?.(message) || ''
}

function resolveThinking(message: ConversationMessage) {
  return props.resolveThinkingPresentation?.(message) || null
}

function onScroll() {
  const el = listEl.value
  if (!el) return
  const distance = el.scrollHeight - el.scrollTop - el.clientHeight
  stickToBottom.value = distance < 48
  showJump.value = !stickToBottom.value
}

function scrollToBottom(force = false) {
  const el = listEl.value
  if (!el) return
  if (!force && !stickToBottom.value) return
  el.scrollTop = el.scrollHeight
  showJump.value = false
  stickToBottom.value = true
}

watch(
  () => props.messages,
  async () => {
    if (props.autoFollow === false) return
    await nextTick()
    scrollToBottom()
  },
  { deep: true },
)

onMounted(() => scrollToBottom(true))

defineExpose({ scrollToBottom })
</script>

<style scoped>
.reachai-message-list {
  position: relative;
  z-index: 1;
  flex: 1;
  overflow: auto;
  padding: var(--reachai-chat-list-padding, 24px 28px 28px);
  min-height: 0;
  background: transparent;
  scrollbar-width: thin;
}

.reachai-message-list__empty {
  position: relative;
  z-index: 1;
  color: var(--reachai-chat-text-muted, #5b6b69);
  font-size: 14px;
  padding: 24px 8px;
  text-align: center;
}

.reachai-message-list__jump {
  position: sticky;
  bottom: 8px;
  left: 50%;
  transform: translateX(-50%);
  border: 1px solid var(--reachai-chat-border, #d7e0de);
  background: color-mix(in srgb, var(--reachai-chat-surface, #fff) 82%, transparent);
  color: var(--reachai-chat-text, #14201f);
  border-radius: 999px;
  padding: 6px 12px;
  font: inherit;
  font-size: 12px;
  box-shadow: var(--reachai-chat-shadow, 0 8px 24px rgba(15, 23, 22, 0.08));
  backdrop-filter: blur(12px);
  cursor: pointer;
}
</style>
