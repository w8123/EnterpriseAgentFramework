<template>
  <form
    class="reachai-composer"
    :class="{ 'is-disabled': disabled }"
    novalidate
    @submit.prevent="onSubmit"
  >
    <textarea
      ref="inputEl"
      v-model="draft"
      class="reachai-composer__input resize-none"
      rows="2"
      :placeholder="placeholder"
      :disabled="disabled"
      :aria-label="placeholder"
      @keydown="onKeydown"
    />
    <div class="reachai-composer__actions">
      <span class="reachai-composer__hint"><kbd>Ctrl</kbd><b>+</b><kbd>Enter</kbd><em>发送</em></span>
      <div class="reachai-composer__buttons">
        <button
          v-if="showStop"
          type="button"
          class="reachai-composer__stop"
          @click="$emit('stop')"
        >
          停止
        </button>
        <button
          type="submit"
          class="reachai-composer__send"
          :class="{ 'is-busy': showStop }"
          :disabled="disabled || !draft.trim()"
        >
          {{ showStop ? '思考中' : '发送' }}
        </button>
      </div>
    </div>
  </form>
</template>

<script setup lang="ts">
import { ref } from 'vue'

withDefaults(defineProps<{
  placeholder?: string
  disabled?: boolean
  showStop?: boolean
}>(), {
  placeholder: '输入消息',
  disabled: false,
  showStop: false,
})

const emit = defineEmits<{
  send: [text: string]
  stop: []
}>()

const draft = ref('')
const inputEl = ref<HTMLTextAreaElement | null>(null)

function onSubmit() {
  const text = draft.value.trim()
  if (!text) return
  emit('send', text)
  draft.value = ''
}

function onKeydown(event: KeyboardEvent) {
  if (!event.isComposing && (event.ctrlKey || event.metaKey) && event.key === 'Enter') {
    event.preventDefault()
    onSubmit()
  }
}

defineExpose({
  focus() {
    inputEl.value?.focus()
  },
  setValue(value: string) {
    draft.value = value
  },
})
</script>

<style scoped>
.reachai-composer {
  display: flex;
  flex: 0 0 auto;
  flex-direction: column;
  gap: 0;
  margin: 0;
  padding: var(--reachai-chat-composer-padding, 12px 16px 14px);
  border-top: 1px solid var(--reachai-chat-glass-border, rgb(255 255 255 / 0.72));
  background: var(--reachai-chat-glass-composer);
  box-shadow:
    var(--reachai-chat-glass-highlight),
    var(--reachai-chat-glass-shadow-composer);
  -webkit-backdrop-filter: var(--reachai-chat-glass-blur-composer);
  backdrop-filter: var(--reachai-chat-glass-blur-composer);
  transition: box-shadow 160ms ease, border-color 160ms ease;
}

.reachai-composer:focus-within {
  border-top-color: rgb(var(--reachai-chat-primary-rgb, 99 102 241) / 0.28);
  box-shadow:
    var(--reachai-chat-glass-highlight),
    var(--reachai-chat-glass-focus-ring),
    var(--reachai-chat-glass-shadow-composer);
}

.reachai-composer.is-disabled {
  box-shadow: var(--reachai-chat-glass-inset-edge);
}

.reachai-composer.is-disabled .reachai-composer__send {
  filter: saturate(0.72);
  opacity: 0.55;
}

.reachai-composer__input {
  width: 100%;
  min-height: 56px;
  resize: none;
  border: 0;
  border-radius: 0;
  padding: 10px 4px;
  font: inherit;
  font-size: 13px;
  font-weight: 500;
  line-height: 1.6;
  color: var(--reachai-chat-text, #1e293b);
  background: transparent;
  box-shadow: none;
}

.reachai-composer__input::placeholder {
  color: var(--reachai-chat-text-muted, #64748b);
  opacity: 1;
}

.reachai-composer__input:focus {
  outline: none;
}

.reachai-composer__input:disabled {
  cursor: not-allowed;
  opacity: 1;
  color: var(--reachai-chat-text-secondary, #526176);
}

.reachai-composer__actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-top: 10px;
  border-top: 1px solid var(--reachai-chat-glass-border-soft, rgb(115 133 172 / 0.14));
}

.reachai-composer__hint {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--reachai-chat-text-muted, #5b6b69);
  font-size: 11px;
  font-weight: 650;
}

.reachai-composer__hint kbd {
  padding: 1px 5px;
  border-radius: 4px;
  border: 1px solid var(--reachai-chat-glass-border-soft);
  background: var(--reachai-chat-glass-control);
  font: inherit;
  font-size: 10px;
}

.reachai-composer__hint b {
  font-weight: 700;
}

.reachai-composer__hint em {
  font-style: normal;
  margin-left: 2px;
}

.reachai-composer__buttons {
  display: flex;
  gap: 8px;
  align-items: center;
}

.reachai-composer__send,
.reachai-composer__stop {
  border: none;
  border-radius: 13px;
  padding: 8px 16px;
  font: inherit;
  font-size: 13px;
  font-weight: 650;
  cursor: pointer;
}

.reachai-composer__send {
  min-width: 94px;
  color: var(--reachai-chat-primary-contrast, #fff);
  background: linear-gradient(
    135deg,
    var(--reachai-chat-primary-soft, #818cf8),
    var(--reachai-chat-primary, #6366f1)
  );
  box-shadow: 0 10px 24px rgb(var(--reachai-chat-primary-rgb, 99 102 241) / 0.22);
}

.reachai-composer__send:disabled {
  opacity: 0.55;
  cursor: not-allowed;
  box-shadow: none;
}

.reachai-composer__send.is-busy {
  opacity: 0.92;
}

.reachai-composer__stop {
  background: transparent;
  border: 1px solid var(--reachai-chat-glass-border-soft);
  color: var(--reachai-chat-text);
}

.reachai-conversation--compact .reachai-composer__input {
  min-height: 44px;
  font-size: 13px;
}

.reachai-conversation--compact .reachai-composer__send {
  min-width: 72px;
  padding: 7px 12px;
  border-radius: 10px;
}
</style>
