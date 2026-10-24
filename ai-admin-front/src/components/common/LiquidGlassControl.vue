<script setup lang="ts">
import LiquidGlassLayer from './LiquidGlassLayer.vue'
import { useLiquidGlassSource } from './liquidGlassContext'

const props = withDefaults(defineProps<{
  kind?: 'button' | 'field'
  tone?: 'neutral' | 'primary'
  source?: 'backdrop' | 'wallpaper'
  shape?: 'pill' | 'circle'
}>(), {
  kind: 'button',
  tone: 'neutral',
  shape: 'pill',
})
const source = useLiquidGlassSource(() => props.source)

function moveHighlight(event: PointerEvent) {
  if (event.pointerType === 'touch') return
  const host = event.currentTarget as HTMLElement
  const bounds = host.getBoundingClientRect()
  if (bounds.width) host.style.setProperty('--control-light-x', `${Math.round((event.clientX - bounds.left) / bounds.width * 100)}%`)
}

function resetHighlight(event: PointerEvent) {
  (event.currentTarget as HTMLElement).style.removeProperty('--control-light-x')
}
</script>

<template>
  <div
    class="liquid-glass-control glass-surface-lens liquid-glass-host"
    :class="[`liquid-glass-control--${kind}`, `liquid-glass-control--${tone}`, { 'samples-wallpaper': source === 'wallpaper', 'glass-workspace-adaptive': source === 'wallpaper', 'liquid-glass-control--circle': shape === 'circle' }]"
    @pointermove="moveHighlight"
    @pointerleave="resetHighlight"
  >
    <LiquidGlassLayer :bezel="7" :depth="12" :source="source" />
    <div class="liquid-glass-control__content"><slot /></div>
  </div>
</template>

<style scoped lang="scss">
.liquid-glass-control {
  --glass-tint: var(--surface-glass-lens);
  --control-ink: var(--text-primary);
  --control-ink-muted: var(--text-secondary);
  position: relative;
  display: inline-grid;
  align-items: center;
  min-width: 0;
  max-width: 100%;
  border-color: var(--glass-control-border);
  border-radius: var(--glass-control-radius);
  color: var(--control-ink);
  vertical-align: middle;
  transition: box-shadow var(--motion-duration-normal) ease, transform var(--motion-duration-fast) var(--motion-easing-emphasized);

  &.is-liquid-refracting.samples-wallpaper {
    --control-ink: var(--text-on-wallpaper);
    --control-ink-muted: var(--text-on-wallpaper-secondary);
  }

  &::before {
    background:
      radial-gradient(80% 160% at var(--control-light-x, 24%) -100%, var(--border-glass-edge), transparent 78%),
      var(--glass-droplet-caustic);
    opacity: 0.55;
  }

  &:has(:focus-visible),
  &.liquid-glass-control--field:focus-within {
    outline: 2px solid var(--border-focus);
    outline-offset: 3px;
  }
}

// 危险、警告按钮在玻璃上不加色底，只保留语义色字，避免误点永久操作。
.liquid-glass-control.liquid-glass-control--button:has(.el-button--danger) {
  --control-ink: var(--status-danger);
}

.liquid-glass-control.liquid-glass-control--button:has(.el-button--warning) {
  --control-ink: var(--status-warning);
}

.liquid-glass-control--primary {
  --glass-tint: var(--surface-glass-action);
  --control-ink: var(--glass-control-primary-ink, var(--text-primary));
}

.liquid-glass-control--field {
  display: grid;
  width: 100%;

  &:has(.is-disabled) {
    opacity: 0.62;
  }
}

.liquid-glass-control--circle {
  flex: 0 0 var(--control-height);
  width: var(--control-height);
  height: var(--control-height);
  border-radius: 50%;
}

.liquid-glass-control__content {
  position: relative;
  z-index: 1;
  display: grid;
  min-width: 0;
  border-radius: inherit;
}

// Element Plus retains button, input, loading and keyboard behavior. This
// decorator supplies the optical surface without another clickable element.
.liquid-glass-control--button :deep(.el-button:not(.is-text):not(.is-link)) {
  --el-button-text-color: var(--control-ink);
  --el-button-hover-text-color: var(--control-ink);
  --el-button-active-text-color: var(--control-ink);
  width: 100%;
  min-height: var(--control-height);
  height: auto;
  margin: 0;
  padding: 10px 18px;
  border: 0;
  border-radius: inherit;
  color: var(--control-ink);
  background: transparent;
  box-shadow: none;
  filter: none;
  transform: none;
  font-weight: 600;

  &:focus-visible {
    outline: 0;
  }
}

.liquid-glass-control--button:has(.el-button:hover:not(.is-disabled)) {
  transform: var(--glass-control-hover-transform);
  box-shadow: var(--workbench-control-hover-shadow, var(--shadow-glass-lens), var(--shadow-active));
}

.liquid-glass-control--circle.liquid-glass-control--button :deep(.el-button:not(.is-text):not(.is-link)) {
  width: var(--control-height);
  height: var(--control-height);
  padding: 0;
}

.liquid-glass-control--button:has(.el-button:active:not(.is-disabled)) {
  transform: scale(0.97);
}

.liquid-glass-control--button:has(.el-button.is-disabled) {
  opacity: 0.62;
  transform: none;
}

.liquid-glass-control.liquid-glass-control--button :deep(.el-button.is-disabled) {
  opacity: 1;
}

.liquid-glass-control.liquid-glass-control--field :deep(.el-input__wrapper),
.liquid-glass-control.liquid-glass-control--field :deep(.el-select__wrapper) {
  box-sizing: border-box;
  min-height: var(--control-height) !important;
  padding-inline: 14px !important;
  border-radius: inherit !important;
  background: transparent !important;
  box-shadow: none !important;
}

.liquid-glass-control--field :deep(.el-input),
.liquid-glass-control--field :deep(.el-select) {
  --el-input-text-color: var(--control-ink);
  --el-input-placeholder-color: var(--control-ink-muted);
  --el-text-color-regular: var(--control-ink);
  --el-text-color-placeholder: var(--control-ink-muted);
  width: 100%;
  min-width: 0;
  border-radius: inherit;
}

.liquid-glass-control--field :deep(.el-input__inner),
.liquid-glass-control--field :deep(.el-select__selected-item),
.liquid-glass-control--field :deep(.el-select__input) {
  color: var(--control-ink);
}

.liquid-glass-control--field :deep(.el-select__placeholder.is-transparent),
.liquid-glass-control--field :deep(.el-input__prefix),
.liquid-glass-control--field :deep(.el-input__suffix),
.liquid-glass-control--field :deep(.el-select__caret) {
  color: var(--control-ink-muted);
}

@media (prefers-reduced-motion: reduce) {
  .liquid-glass-control {
    transition: none;
  }

  .liquid-glass-control--button:has(.el-button:hover:not(.is-disabled)),
  .liquid-glass-control--button:has(.el-button:active:not(.is-disabled)) {
    transform: none;
  }
}

@media (forced-colors: active) {
  .liquid-glass-control {
    --control-ink: CanvasText;
    --control-ink-muted: CanvasText;
    border-color: CanvasText;
  }
}
</style>
