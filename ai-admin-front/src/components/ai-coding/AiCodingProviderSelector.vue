<script setup lang="ts">
import claudeCodeIcon from '@/assets/ai-coding-providers/claude-code.svg'
import codexIcon from '@/assets/ai-coding-providers/codex.svg'
import cursorIcon from '@/assets/ai-coding-providers/cursor.svg'
import traeIcon from '@/assets/ai-coding-providers/trae.svg'
import type { AiCodingExecutorProvider } from '@/types/aiCodingTask'
import { AI_CODING_EXECUTOR_OPTIONS } from '@/utils/aiCodingPresentation'

withDefaults(
  defineProps<{
    modelValue: AiCodingExecutorProvider
    ariaLabel?: string
    compact?: boolean
  }>(),
  {
    ariaLabel: 'AI 编程工具',
    compact: false,
  },
)

const emit = defineEmits<{
  'update:modelValue': [value: AiCodingExecutorProvider]
}>()

const providerIcons: Readonly<Record<AiCodingExecutorProvider, string>> = {
  CODEX: codexIcon,
  CURSOR: cursorIcon,
  TRAE: traeIcon,
  CLAUDE_CODE: claudeCodeIcon,
}

function providerClass(provider: AiCodingExecutorProvider) {
  return `is-${provider.toLowerCase().replace(/_/g, '-')}`
}
</script>

<template>
  <div
    class="ai-coding-provider-selector"
    :class="{ 'is-compact': compact }"
    role="tablist"
    :aria-label="ariaLabel"
  >
    <el-tooltip
      v-for="option in AI_CODING_EXECUTOR_OPTIONS"
      :key="option.value"
      :content="option.label"
      placement="top"
      :show-after="120"
    >
      <button
        type="button"
        class="ai-coding-provider-selector__button"
        :class="[
          providerClass(option.value),
          { 'is-active': modelValue === option.value },
        ]"
        role="tab"
        :aria-label="option.label"
        :aria-selected="modelValue === option.value"
        @click="emit('update:modelValue', option.value)"
      >
        <img
          :src="providerIcons[option.value]"
          alt=""
          aria-hidden="true"
        />
      </button>
    </el-tooltip>
  </div>
</template>

<style scoped>
.ai-coding-provider-selector {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  width: fit-content;
  padding: 5px;
  border: 1px solid var(--border-subtle);
  border-radius: 14px;
  background: color-mix(in srgb, var(--surface-solid-control) 92%, transparent);
  box-shadow:
    inset 0 1px 0 rgb(255 255 255 / 0.42),
    0 6px 18px rgb(var(--brand-primary-rgb) / 0.06);
}

.ai-coding-provider-selector__button {
  display: grid;
  width: 44px;
  height: 44px;
  padding: 0;
  border: 1px solid transparent;
  border-radius: 11px;
  background: transparent;
  cursor: pointer;
  place-items: center;
  transition:
    border-color 160ms ease,
    background-color 160ms ease,
    box-shadow 160ms ease,
    transform 160ms ease;
}

.ai-coding-provider-selector__button:hover {
  border-color: rgb(var(--brand-primary-rgb) / 0.2);
  background: rgb(var(--brand-primary-rgb) / 0.07);
  transform: translateY(-1px);
}

.ai-coding-provider-selector__button.is-active {
  border-color: rgb(var(--brand-primary-rgb) / 0.48);
  background: rgb(var(--brand-primary-rgb) / 0.13);
  box-shadow:
    0 0 0 2px rgb(var(--brand-primary-rgb) / 0.08),
    0 6px 14px rgb(var(--brand-primary-rgb) / 0.13);
}

.ai-coding-provider-selector__button:focus-visible {
  outline: 3px solid rgb(var(--brand-primary-rgb) / 0.24);
  outline-offset: 2px;
}

.ai-coding-provider-selector__button img {
  display: block;
  width: 28px;
  height: 28px;
  object-fit: contain;
}

.ai-coding-provider-selector__button.is-codex img {
  width: 31px;
  height: 31px;
}

.ai-coding-provider-selector__button.is-cursor {
  background-color: #fff;
}

.ai-coding-provider-selector__button.is-cursor:hover {
  background-color: color-mix(in srgb, #fff 90%, var(--brand-primary));
}

.ai-coding-provider-selector__button.is-cursor.is-active {
  background-color: color-mix(in srgb, #fff 84%, var(--brand-primary));
}

.ai-coding-provider-selector__button.is-trae:not(.is-active) {
  background-color: color-mix(in srgb, #32f08c 7%, transparent);
}

.ai-coding-provider-selector__button.is-claude-code:not(.is-active) {
  background-color: color-mix(in srgb, #d97757 7%, transparent);
}

.ai-coding-provider-selector.is-compact {
  gap: 6px;
  padding: 4px;
  border-radius: 12px;
}

.ai-coding-provider-selector.is-compact .ai-coding-provider-selector__button {
  width: 38px;
  height: 38px;
  border-radius: 9px;
}

.ai-coding-provider-selector.is-compact .ai-coding-provider-selector__button img {
  width: 24px;
  height: 24px;
}

.ai-coding-provider-selector.is-compact .ai-coding-provider-selector__button.is-codex img {
  width: 27px;
  height: 27px;
}

@media (max-width: 560px) {
  .ai-coding-provider-selector {
    gap: 5px;
    padding: 4px;
  }

  .ai-coding-provider-selector__button {
    width: 40px;
    height: 40px;
  }
}
</style>
