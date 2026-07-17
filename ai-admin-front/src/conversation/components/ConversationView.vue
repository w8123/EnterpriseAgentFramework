<template>
  <div
    class="reachai-conversation"
    :class="[
      `reachai-conversation--${density}`,
      `reachai-conversation--${chrome}`,
      {
        'reachai-conversation--atmosphere': atmosphere,
        'is-busy': busy,
      },
    ]"
    :data-surface="surface"
    :style="assetStyle"
  >
    <header v-if="$slots.header" class="reachai-conversation__header">
      <slot name="header" />
    </header>

    <ConversationMessageList
      :messages="snapshot.messages"
      :auto-follow="autoFollow"
      :assistant-label="assistantLabel"
      :resolve-status-hint="resolveStatusHint"
      :resolve-thinking-presentation="resolveThinkingPresentation"
      :show-avatars="showAvatars"
      :surface="surface"
      @interaction-submit="onInteractionSubmit"
      @interaction-cancel="onInteractionCancel"
      @retry="$emit('retry')"
    >
      <template v-if="$slots.empty" #empty>
        <slot name="empty" />
      </template>
      <template v-if="$slots['message-meta']" #message-meta="slotProps">
        <slot name="message-meta" v-bind="slotProps" />
      </template>
    </ConversationMessageList>

    <div v-if="snapshot.error" class="reachai-conversation__error" role="alert">
      <span>{{ snapshot.error }}</span>
      <button type="button" @click="$emit('retry')">重试</button>
    </div>

    <slot name="composer">
      <ConversationComposer
        v-if="!hideComposer"
        :disabled="composerDisabled"
        :show-stop="showStop"
        :placeholder="placeholder"
        @send="onSend"
        @stop="$emit('stop')"
      />
    </slot>

    <footer v-if="$slots.footer" class="reachai-conversation__footer">
      <slot name="footer" />
    </footer>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type {
  ConversationMessage,
  ConversationSnapshot,
  ConversationThinkingPresentation,
} from '../core/conversationTypes'
import ConversationMessageList from './ConversationMessageList.vue'
import ConversationComposer from './ConversationComposer.vue'
import { atmosphereUrl, prismAvatarUrl } from '../assets'
import '../styles/conversation-tokens.css'

const props = withDefaults(defineProps<{
  snapshot: ConversationSnapshot
  density?: 'compact' | 'comfortable'
  chrome?: 'inline' | 'panel'
  placeholder?: string
  hideComposer?: boolean
  autoFollow?: boolean
  disableSendWhileBusy?: boolean
  /** 连续氛围背景（消息区 + Composer 共用一张） */
  atmosphere?: boolean
  assistantLabel?: string
  showAvatars?: boolean
  surface?: 'admin' | 'embed'
  /** Shell 安全状态摘要；Embed 应只返回通用文案或空 */
  resolveStatusHint?: (message: ConversationMessage) => string | undefined
  /** Shell 安全思考详情；Embed 不得注入 Supervisor / 节点 / Trace */
  resolveThinkingPresentation?: (message: ConversationMessage) => ConversationThinkingPresentation | undefined
}>(), {
  density: 'comfortable',
  chrome: 'panel',
  hideComposer: false,
  autoFollow: true,
  disableSendWhileBusy: true,
  atmosphere: true,
  assistantLabel: '',
  showAvatars: true,
  surface: 'admin',
})

const emit = defineEmits<{
  send: [text: string]
  stop: []
  retry: []
  'interaction-submit': [interactionId: string, action: string, values: Record<string, unknown>]
  'interaction-cancel': [interactionId: string]
}>()

const assetStyle = computed(() => ({
  '--reachai-chat-atmosphere-image': `url("${atmosphereUrl}")`,
  '--reachai-chat-prism-avatar': `url("${prismAvatarUrl}")`,
}))

const busy = computed(() =>
  props.snapshot.turnStatus === 'sending'
  || props.snapshot.turnStatus === 'streaming',
)

const showStop = computed(() => busy.value)

const composerDisabled = computed(() =>
  props.disableSendWhileBusy && (busy.value || props.snapshot.turnStatus === 'waiting'),
)

function onSend(text: string) {
  emit('send', text)
}

function onInteractionSubmit(interactionId: string, action: string, values: Record<string, unknown>) {
  emit('interaction-submit', interactionId, action, values)
}

function onInteractionCancel(interactionId: string) {
  emit('interaction-cancel', interactionId)
}
</script>

<style scoped>
.reachai-conversation {
  position: relative;
  display: flex;
  flex-direction: column;
  min-height: 0;
  height: 100%;
  overflow: hidden;
  color: var(--reachai-chat-text, #14201f);
  font-family: var(--reachai-chat-font-family, inherit);
  border: 1px solid var(--reachai-chat-border, #d7e0de);
  border-radius: var(--reachai-chat-radius, 12px);
  background: var(--reachai-chat-surface, #fff);
  container-type: inline-size;
  container-name: reachai-conversation;
}

.reachai-conversation--atmosphere {
  background-color: transparent;
  background-image:
    linear-gradient(180deg, var(--reachai-chat-atmosphere-wash-top), var(--reachai-chat-atmosphere-wash-bottom)),
    var(--reachai-chat-atmosphere-theme-wash),
    radial-gradient(circle at 14% 28%, var(--reachai-chat-atmosphere-glow-primary), transparent 22%),
    radial-gradient(circle at 68% 62%, var(--reachai-chat-atmosphere-glow-secondary), transparent 30%),
    linear-gradient(180deg, transparent 38%, var(--reachai-chat-atmosphere-bottom-tint)),
    linear-gradient(180deg, rgb(255 255 255 / 0.2), rgb(231 237 255 / 0.12)),
    var(--reachai-chat-atmosphere-image);
  background-position: center, center, center, center, center, center, center bottom;
  background-size: cover, cover, cover, cover, cover, cover, 100% 100%;
  background-repeat: no-repeat;
}

.reachai-conversation--inline {
  border: none;
  border-radius: 0;
}

.reachai-conversation__header,
.reachai-conversation__footer {
  position: relative;
  z-index: 1;
  padding: 10px 12px;
  border-bottom: 1px solid var(--reachai-chat-border, #d7e0de);
  background: color-mix(in srgb, var(--reachai-chat-surface, #fff) 68%, transparent);
  backdrop-filter: blur(18px);
}

.reachai-conversation__footer {
  border-bottom: none;
  border-top: 1px solid var(--reachai-chat-border, #d7e0de);
}

.reachai-conversation__error {
  position: relative;
  z-index: 1;
  display: flex;
  justify-content: space-between;
  gap: 8px;
  align-items: center;
  padding: 8px 12px;
  color: var(--reachai-chat-danger, #b42318);
  background: color-mix(in srgb, var(--reachai-chat-danger, #b42318) 8%, white);
  font-size: 13px;
}

.reachai-conversation__error button {
  border: 1px solid var(--reachai-chat-border, #d7e0de);
  background: var(--reachai-chat-surface, #fff);
  border-radius: 6px;
  padding: 4px 8px;
  font: inherit;
  cursor: pointer;
}

/* 自定义 composer slot 也复用玻璃条视觉（Token 同源，禁止 Shell 各自覆盖） */
.reachai-conversation :deep(.reachai-composer),
.reachai-conversation :deep(.chat-input),
.reachai-conversation :deep(.debug-chat-composer) {
  position: relative;
  z-index: 1;
  border-top: 1px solid var(--reachai-chat-glass-border, rgb(255 255 255 / 0.72));
  background: var(--reachai-chat-glass-composer);
  box-shadow:
    var(--reachai-chat-glass-highlight),
    var(--reachai-chat-glass-shadow-composer);
  -webkit-backdrop-filter: var(--reachai-chat-glass-blur-composer);
  backdrop-filter: var(--reachai-chat-glass-blur-composer);
}
</style>
