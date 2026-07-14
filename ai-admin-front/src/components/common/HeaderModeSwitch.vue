<script setup lang="ts">
import type { Component } from 'vue'

export interface HeaderModeOption {
  value: string
  label: string
  icon?: Component
}

const props = withDefaults(
  defineProps<{
    modelValue: string
    options: HeaderModeOption[]
    ariaLabel?: string
  }>(),
  {
    ariaLabel: '页面模式',
  },
)

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()
</script>

<template>
  <div class="header-mode-switch" role="tablist" :aria-label="props.ariaLabel">
    <button
      v-for="option in props.options"
      :key="option.value"
      class="header-mode-switch__item"
      :class="{ 'is-active': option.value === props.modelValue }"
      type="button"
      role="tab"
      :aria-selected="option.value === props.modelValue"
      @click="emit('update:modelValue', option.value)"
    >
      <el-icon v-if="option.icon" aria-hidden="true"><component :is="option.icon" /></el-icon>
      <span>{{ option.label }}</span>
    </button>
  </div>
</template>

<style scoped lang="scss">
.header-mode-switch {
  display: inline-flex;
  max-width: 100%;
  align-items: center;
  gap: 4px;
}

.header-mode-switch__item {
  display: inline-flex;
  min-height: 40px;
  align-items: center;
  justify-content: center;
  gap: 7px;
  padding: 0 18px;
  border: 1px solid transparent;
  border-radius: 9px;
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
  font: inherit;
  font-size: 0.8125rem;
  font-weight: 760;
  transition:
    color var(--motion-duration-fast) ease,
    background var(--motion-duration-fast) ease,
    transform var(--motion-duration-fast) ease;
}

.header-mode-switch__item:hover {
  color: var(--brand-active);
  transform: translateY(-1px);
}

.header-mode-switch__item.is-active {
  border-color: rgb(255 255 255 / 0.84);
  background: linear-gradient(
    135deg,
    color-mix(in srgb, var(--brand-active) 76%, #0f172a 24%),
    color-mix(in srgb, var(--brand-primary) 72%, #0f172a 28%)
  );
  color: #fff;
  box-shadow:
    0 0 0 1px rgb(255 255 255 / 0.42),
    0 11px 22px -13px rgb(15 23 42 / 0.58),
    inset 0 1px 0 rgb(255 255 255 / 0.28);
}

.header-mode-switch__item:focus-visible {
  outline: 2px solid var(--border-focus);
  outline-offset: 2px;
}

@media (max-width: 760px) {
  .header-mode-switch,
  .header-mode-switch__item {
    flex: 1 1 auto;
  }
}
</style>
