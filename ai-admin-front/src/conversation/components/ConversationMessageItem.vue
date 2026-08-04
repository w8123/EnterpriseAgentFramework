<template>
  <article
    class="reachai-message"
    :class="messageClasses"
    :data-message-id="message.id"
    :data-role="message.role"
    :data-status="message.status"
  >
    <!-- 用户消息：右对齐气泡 + 头像，不渲染「你」 -->
    <template v-if="message.role === 'user'">
      <div class="reachai-message__user-row">
        <div class="reachai-message__user-bubble">
          <ConversationBlockRenderer
            v-for="block in message.blocks"
            :key="block.id"
            :block="block"
            @interaction-submit="(...args) => $emit('interaction-submit', ...args)"
            @interaction-cancel="(id) => $emit('interaction-cancel', id)"
            @retry="$emit('retry')"
          />
        </div>
        <div v-if="showAvatars" class="reachai-message__user-avatar" aria-hidden="true">
          <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8">
            <circle cx="12" cy="8" r="3.5" />
            <path d="M5 19.5c1.2-3.2 3.6-4.8 7-4.8s5.8 1.6 7 4.8" stroke-linecap="round" />
          </svg>
        </div>
      </div>
    </template>

    <!-- 智能体 / 系统 / runtime：棱镜卡片 -->
    <template v-else>
      <div
        class="reachai-message__assistant-row"
        :class="{ 'reachai-message__assistant-row--without-avatar': !showAvatars }"
      >
        <div v-if="showAvatars" class="reachai-message__prism-avatar" aria-hidden="true">
          <span class="reachai-message__prism-avatar-img" />
        </div>

        <div class="reachai-message__card-shell" :class="shellClasses">
          <div class="reachai-message__card" :class="cardClasses">
            <header
              v-if="showThinkingHeader || showWeakLabel || statusCapsule"
              class="reachai-message__card-heading"
            >
              <div v-if="showThinkingHeader" class="reachai-message__thinking-title">
                <span class="reachai-message__spark" aria-hidden="true">✦</span>
                <strong>思考中…</strong>
                <span class="reachai-message__thinking-dots" aria-hidden="true"><i /><i /><i /></span>
              </div>
              <div v-else-if="showWeakLabel" class="reachai-message__weak-label">
                {{ assistantLabel }}
              </div>
              <span v-else class="reachai-message__heading-spacer" aria-hidden="true" />
              <span v-if="statusCapsule" class="reachai-message__status-capsule" :data-tone="statusCapsule.tone">
                {{ statusCapsule.label }}
              </span>
            </header>

            <p v-if="showThinkingHeader && statusHint" class="reachai-message__status-hint">
              {{ statusHint }}
            </p>

            <div
              v-if="showThinkingHeader && thinkingPresentation?.label"
              class="reachai-message__thinking-details"
            >
              <button
                type="button"
                class="reachai-message__thinking-toggle"
                :aria-expanded="thinkingExpanded"
                @click="thinkingExpanded = !thinkingExpanded"
              >
                <span>{{ thinkingPresentation.label }}</span>
                <span class="reachai-message__thinking-chevron" aria-hidden="true">
                  {{ thinkingExpanded ? '▴' : '▾' }}
                </span>
              </button>
              <ul
                v-if="thinkingExpanded && thinkingSteps.length"
                class="reachai-message__thinking-steps"
              >
                <li
                  v-for="step in thinkingSteps"
                  :key="step.id"
                  class="reachai-message__thinking-step"
                  :data-state="step.state"
                >
                  <span class="reachai-message__thinking-step-mark" aria-hidden="true" />
                  <div class="reachai-message__thinking-step-body">
                    <strong>{{ step.title }}</strong>
                    <p v-if="step.detail">{{ step.detail }}</p>
                  </div>
                </li>
              </ul>
            </div>

            <div class="reachai-message__body">
              <ConversationBlockRenderer
                v-for="block in message.blocks"
                :key="block.id"
                :block="block"
                @interaction-submit="(...args) => $emit('interaction-submit', ...args)"
                @interaction-cancel="(id) => $emit('interaction-cancel', id)"
                @retry="$emit('retry')"
              />
              <div
                v-if="!message.blocks.length && isThinking && !statusHint"
                class="reachai-message__loading"
              >
                正在处理
              </div>
            </div>

            <footer v-if="$slots.meta" class="reachai-message__meta">
              <slot name="meta" :message="message" />
            </footer>
          </div>
        </div>
      </div>
    </template>
  </article>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type {
  ConversationMessage,
  ConversationThinkingPresentation,
} from '../core/conversationTypes'
import ConversationBlockRenderer from './ConversationBlockRenderer.vue'

const props = withDefaults(defineProps<{
  message: ConversationMessage
  assistantLabel?: string
  statusHint?: string
  thinkingPresentation?: ConversationThinkingPresentation | null
  showAvatars?: boolean
  /** Embed 等场景不展示失败/取消状态胶囊以外的调试文案 */
  surface?: 'admin' | 'embed'
}>(), {
  assistantLabel: '',
  statusHint: '',
  thinkingPresentation: null,
  showAvatars: true,
  surface: 'admin',
})

defineEmits<{
  'interaction-submit': [interactionId: string, action: string, values: Record<string, unknown>]
  'interaction-cancel': [interactionId: string]
  retry: []
}>()

const thinkingExpanded = ref(false)

const isThinking = computed(() =>
  props.message.role !== 'user'
  && (props.message.status === 'pending' || props.message.status === 'streaming'),
)

const showThinkingHeader = computed(() => isThinking.value)

const thinkingSteps = computed(() => props.thinkingPresentation?.steps || [])

watch(
  () => props.message.id,
  () => {
    thinkingExpanded.value = false
  },
)

watch(isThinking, (value) => {
  if (!value) thinkingExpanded.value = false
})

const showWeakLabel = computed(() =>
  !isThinking.value
  && !!props.assistantLabel
  && props.message.role === 'assistant'
  && props.surface === 'admin',
)

const messageClasses = computed(() => [
  `reachai-message--${props.message.role}`,
  `reachai-message--${props.message.status}`,
  {
    'reachai-message--thinking': isThinking.value,
    'reachai-message--prism': props.message.role !== 'user',
  },
])

const shellClasses = computed(() => ({
  'reachai-message__card-shell--thinking': isThinking.value,
  'reachai-message__card-shell--response': !isThinking.value,
  'reachai-message__card-shell--failed': props.message.status === 'failed',
  'reachai-message__card-shell--cancelled': props.message.status === 'cancelled',
}))

const cardClasses = computed(() => ({
  'reachai-message__card--thinking': isThinking.value,
  'reachai-message__card--response': !isThinking.value,
  [`reachai-message__card--${props.message.status}`]: true,
}))

const statusCapsule = computed(() => {
  if (isThinking.value) return null
  if (props.message.status === 'failed') return { label: '失败', tone: 'danger' }
  if (props.message.status === 'cancelled') return { label: '已取消', tone: 'warning' }
  return null
})
</script>

<style scoped>
.reachai-message {
  position: relative;
  z-index: 1;
  margin: 0 0 var(--reachai-chat-space-y, 28px);
  max-width: 100%;
}

.reachai-message__user-row {
  display: flex;
  justify-content: flex-end;
  align-items: flex-start;
  gap: 11px;
  margin-left: auto;
}

.reachai-message__user-bubble {
  max-width: min(var(--reachai-chat-card-max-width, 820px), var(--reachai-chat-user-max-width, 70%));
  min-width: 0;
  padding: 13px 18px;
  border-radius: var(--reachai-chat-message-radius, 18px) var(--reachai-chat-message-radius, 18px) 5px var(--reachai-chat-message-radius, 18px);
  color: var(--reachai-chat-primary-contrast, #fff);
  background: var(--reachai-chat-user-bubble);
  box-shadow: var(--reachai-chat-user-shadow);
  font-size: 14px;
  line-height: 1.7;
  word-break: break-word;
}

.reachai-message__user-bubble :deep(.reachai-block__text) {
  color: inherit;
  white-space: pre-wrap;
}

.reachai-message__user-avatar {
  display: grid;
  width: var(--reachai-chat-user-avatar-size, 38px);
  height: var(--reachai-chat-user-avatar-size, 38px);
  margin-top: 4px;
  flex: 0 0 auto;
  place-items: center;
  border: 1px solid rgb(var(--reachai-chat-primary-rgb, 99 102 241) / 0.16);
  border-radius: 13px;
  color: var(--reachai-chat-primary);
  background: rgb(255 255 255 / 0.66);
  box-shadow: inset 0 1px 0 rgb(255 255 255 / 0.8);
  backdrop-filter: blur(14px);
}

.reachai-message__assistant-row {
  display: grid;
  grid-template-columns: var(--reachai-chat-avatar-size, 56px) minmax(0, min(var(--reachai-chat-card-max-width, 820px), calc(100% - 76px)));
  gap: 14px;
  align-items: start;
}

.reachai-message__assistant-row--without-avatar {
  grid-template-columns: minmax(0, 1fr);
  gap: 0;
}

.reachai-message__prism-avatar {
  display: grid;
  width: var(--reachai-chat-avatar-size, 56px);
  height: var(--reachai-chat-avatar-size, 56px);
  place-items: center;
  border: 1px solid rgb(255 255 255 / 0.7);
  border-radius: 50%;
  background: rgb(255 255 255 / 0.48);
  box-shadow:
    0 12px 30px rgb(var(--reachai-chat-primary-rgb, 99 102 241) / 0.18),
    inset 0 0 0 5px rgb(255 255 255 / 0.3);
  backdrop-filter: blur(14px);
}

.reachai-message__prism-avatar-img {
  display: block;
  width: calc(var(--reachai-chat-avatar-size, 56px) * 0.84);
  height: calc(var(--reachai-chat-avatar-size, 56px) * 0.84);
  border-radius: 50%;
  background: var(--reachai-chat-prism-avatar) center / cover no-repeat;
}

.reachai-message__card-shell {
  position: relative;
  isolation: isolate;
  min-width: 0;
  padding: 2px;
  overflow: hidden;
  border-radius: calc(var(--reachai-chat-card-radius, 24px) + 2px);
  background: var(--reachai-chat-card-border);
  box-shadow: var(--reachai-chat-glass-shadow);
}

.reachai-message__card-shell--failed {
  background: var(--reachai-chat-card-border-danger);
}

.reachai-message__card-shell--cancelled {
  background: var(--reachai-chat-card-border-warning);
}

.reachai-message__card-shell--thinking::before {
  content: '';
  position: absolute;
  z-index: -1;
  width: 150%;
  aspect-ratio: 1;
  top: 50%;
  left: 50%;
  background: conic-gradient(
    from 210deg,
    var(--reachai-chat-spectrum-anchor),
    var(--reachai-chat-spectrum-cool) 22%,
    var(--reachai-chat-spectrum-violet) 39%,
    var(--reachai-chat-spectrum-rose) 58%,
    var(--reachai-chat-spectrum-warm) 75%,
    var(--reachai-chat-spectrum-cool) 89%,
    var(--reachai-chat-spectrum-anchor)
  );
  transform: translate(-50%, -50%);
  animation: reachai-prism-rim var(--reachai-chat-motion-duration, 4.2s) linear infinite;
}

.reachai-message__card-shell--thinking::after {
  content: '';
  position: absolute;
  z-index: -2;
  inset: -16px;
  border-radius: 34px;
  background: linear-gradient(
    112deg,
    color-mix(in srgb, var(--reachai-chat-spectrum-anchor) 22%, transparent),
    color-mix(in srgb, var(--reachai-chat-spectrum-cool) 22%, transparent) 30%,
    color-mix(in srgb, var(--reachai-chat-spectrum-violet) 18%, transparent) 48%,
    color-mix(in srgb, var(--reachai-chat-spectrum-rose) 20%, transparent) 70%,
    color-mix(in srgb, var(--reachai-chat-spectrum-warm) 18%, transparent)
  );
  filter: blur(20px);
  animation: reachai-prism-breathe var(--reachai-chat-motion-breathe, 2.4s) ease-in-out infinite alternate;
}

.reachai-message__card {
  position: relative;
  border-radius: var(--reachai-chat-card-radius, 24px);
  color: var(--reachai-chat-text);
  /* 中性阅读层压在彩色辉光之上；辉光收在四角，正文区保持安静 */
  background:
    var(--reachai-chat-glass-card-reading-layer),
    radial-gradient(circle at 0% 0%, var(--reachai-chat-card-cool-glow), transparent 28%),
    radial-gradient(circle at 100% 0%, var(--reachai-chat-card-rose-glow), transparent 26%),
    radial-gradient(circle at 100% 100%, var(--reachai-chat-card-warm-glow), transparent 30%),
    radial-gradient(circle at 0% 100%, var(--reachai-chat-card-anchor-glow), transparent 26%),
    var(--reachai-chat-card-base);
  box-shadow:
    var(--reachai-chat-glass-highlight),
    var(--reachai-chat-glass-inset-edge);
  -webkit-backdrop-filter: var(--reachai-chat-glass-blur);
  backdrop-filter: var(--reachai-chat-glass-blur);
}

.reachai-message__card--failed {
  background:
    radial-gradient(circle at 8% 0%, color-mix(in srgb, var(--reachai-chat-danger) 8%, transparent), transparent 38%),
    var(--reachai-chat-glass-card-reading-layer),
    var(--reachai-chat-card-base);
}

.reachai-message__card--cancelled {
  background:
    radial-gradient(circle at 8% 0%, color-mix(in srgb, var(--reachai-chat-warning) 9%, transparent), transparent 38%),
    var(--reachai-chat-glass-card-reading-layer),
    var(--reachai-chat-card-base);
}

.reachai-message__card-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 16px 20px 0;
}

.reachai-message__thinking-title {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  color: var(--reachai-chat-text);
}

.reachai-message__thinking-title strong {
  font-size: 16px;
  font-weight: 750;
}

.reachai-message__spark {
  color: var(--reachai-chat-primary);
  font-size: 14px;
  line-height: 1;
}

.reachai-message__thinking-dots {
  display: inline-flex;
  gap: 4px;
  align-items: center;
}

.reachai-message__thinking-dots i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--reachai-chat-primary);
  animation: reachai-prism-dot 1.3s ease-in-out infinite;
}

.reachai-message__thinking-dots i:nth-child(2) { animation-delay: 0.16s; }
.reachai-message__thinking-dots i:nth-child(3) { animation-delay: 0.32s; }

.reachai-message__weak-label {
  overflow: hidden;
  max-width: 70%;
  color: var(--reachai-chat-text-muted);
  font-size: 12px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.reachai-message__status-capsule {
  flex: 0 0 auto;
  min-height: 22px;
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 700;
  background: var(--reachai-chat-glass-control);
  color: var(--reachai-chat-text-muted);
  border: 1px solid var(--reachai-chat-glass-border-soft);
}

.reachai-message__status-capsule[data-tone='danger'] {
  color: var(--reachai-chat-danger);
  background: color-mix(in srgb, var(--reachai-chat-danger) 12%, white);
}

.reachai-message__status-capsule[data-tone='warning'] {
  color: var(--reachai-chat-warning);
  background: color-mix(in srgb, var(--reachai-chat-warning) 12%, white);
}

.reachai-message__status-hint {
  margin: 10px 20px 0;
  color: var(--reachai-chat-text-muted);
  font-size: 12px;
  line-height: 1.6;
}

.reachai-message__thinking-details {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  margin: 10px 20px 0;
  max-width: 100%;
}

.reachai-message__thinking-toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 100%;
  min-height: 28px;
  padding: 4px 12px;
  border: 1px solid var(--reachai-chat-glass-border-soft);
  border-radius: 999px;
  background: var(--reachai-chat-glass-control);
  color: var(--reachai-chat-text-muted);
  font: inherit;
  font-size: 12px;
  font-weight: 650;
  cursor: pointer;
}

.reachai-message__thinking-toggle span:first-child {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.reachai-message__thinking-chevron {
  flex: 0 0 auto;
  font-size: 11px;
  line-height: 1;
}

.reachai-message__thinking-steps {
  display: grid;
  gap: 8px;
  width: 100%;
  margin: 0;
  padding: 10px 12px;
  list-style: none;
  border: 1px solid var(--reachai-chat-glass-border-soft);
  border-radius: 14px;
  background: color-mix(in srgb, var(--reachai-chat-glass-control) 72%, transparent);
}

.reachai-message__thinking-step {
  display: grid;
  grid-template-columns: 14px minmax(0, 1fr);
  gap: 8px;
  align-items: start;
  min-width: 0;
}

.reachai-message__thinking-step-mark {
  width: 8px;
  height: 8px;
  margin-top: 5px;
  border-radius: 50%;
  background: var(--reachai-chat-text-muted);
  opacity: 0.45;
}

.reachai-message__thinking-step[data-state='complete'] .reachai-message__thinking-step-mark {
  background: var(--reachai-chat-primary);
  opacity: 0.85;
}

.reachai-message__thinking-step[data-state='active'] .reachai-message__thinking-step-mark {
  background: var(--reachai-chat-primary);
  opacity: 1;
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--reachai-chat-primary) 18%, transparent);
}

.reachai-message__thinking-step[data-state='error'] .reachai-message__thinking-step-mark {
  background: var(--reachai-chat-danger);
  opacity: 1;
}

.reachai-message__thinking-step-body {
  min-width: 0;
}

.reachai-message__thinking-step-body strong {
  display: block;
  color: var(--reachai-chat-text);
  font-size: 12px;
  font-weight: 650;
  line-height: 1.45;
  word-break: break-word;
}

.reachai-message__thinking-step-body p {
  margin: 2px 0 0;
  color: var(--reachai-chat-text-muted);
  font-size: 11px;
  line-height: 1.5;
  word-break: break-word;
}

.reachai-message__body {
  padding: 14px 20px 16px;
  color: var(--reachai-chat-text);
  font-size: 14px;
  font-weight: 500;
  line-height: 1.72;
  word-break: break-word;
}

.reachai-message__body :deep(strong),
.reachai-message__body :deep(b) {
  font-weight: 650;
  color: var(--reachai-chat-text);
}

.reachai-message__card--thinking .reachai-message__body {
  padding-top: 8px;
}

.reachai-message__body :deep(.reachai-interaction) {
  margin-top: 10px;
  padding: 12px;
  border: 1px solid var(--reachai-chat-glass-border-soft);
  border-radius: 14px;
  background: var(--reachai-chat-glass-interaction);
  box-shadow: var(--reachai-chat-glass-highlight);
  /* 外层卡片已负责 blur，内层不再叠加 backdrop-filter */
  -webkit-backdrop-filter: none;
  backdrop-filter: none;
}

.reachai-message__loading {
  font-size: 13px;
  color: var(--reachai-chat-text-muted);
}

.reachai-message__meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 7px;
  padding: 12px 20px 16px;
  border-top: 1px solid var(--reachai-chat-glass-border-soft);
}

.reachai-message__meta:empty {
  display: none;
  padding: 0;
  border: 0;
}

@media (prefers-reduced-motion: reduce) {
  .reachai-message__card-shell--thinking::before,
  .reachai-message__card-shell--thinking::after,
  .reachai-message__thinking-dots i {
    animation: none !important;
  }
}

/*
 * Compact mode and the ResizeObserver-driven narrow state share one
 * Angular 12/Critters-compatible layout contract. Do not replace these
 * selectors with @container: older enterprise CSS optimizers cannot parse it.
 */
:global(.reachai-conversation--compact) .reachai-message__user-avatar,
:global(.reachai-conversation--compact) .reachai-message__prism-avatar,
:global(.reachai-conversation--narrow) .reachai-message__user-avatar,
:global(.reachai-conversation--narrow) .reachai-message__prism-avatar {
  display: none;
}

:global(.reachai-conversation--compact) .reachai-message__assistant-row,
:global(.reachai-conversation--narrow) .reachai-message__assistant-row {
  grid-template-columns: minmax(0, 1fr);
  gap: 0;
}

:global(.reachai-conversation--compact) .reachai-message__user-row,
:global(.reachai-conversation--narrow) .reachai-message__user-row {
  gap: 0;
}

:global(.reachai-conversation--compact) .reachai-message__user-bubble,
:global(.reachai-conversation--narrow) .reachai-message__user-bubble {
  max-width: min(var(--reachai-chat-card-max-width, 820px), 92%);
}

@media (max-width: 520px) {
  .reachai-message__user-avatar,
  .reachai-message__prism-avatar {
    display: none;
  }

  .reachai-message__assistant-row {
    grid-template-columns: minmax(0, 1fr);
    gap: 0;
  }

  .reachai-message__user-row {
    gap: 0;
  }

  .reachai-message__user-bubble {
    max-width: min(var(--reachai-chat-card-max-width, 820px), 92%);
  }
}
</style>
