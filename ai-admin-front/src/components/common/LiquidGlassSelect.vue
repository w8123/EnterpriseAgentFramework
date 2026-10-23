<script setup lang="ts">
import LiquidGlassControl from './LiquidGlassControl.vue'
import LiquidGlassDropdownSurface from './LiquidGlassDropdownSurface.vue'
import { ref } from 'vue'
import type { SelectInstance } from 'element-plus'
import { nativeControlAttrs, useLiquidGlassSource } from './liquidGlassContext'

defineOptions({ inheritAttrs: false })
const props = withDefaults(defineProps<{
  dropdownTitle?: string
  popperClass?: string
  source?: 'backdrop' | 'wallpaper'
}>(), {
  dropdownTitle: '',
  popperClass: '',
})
const source = useLiquidGlassSource(() => props.source)
const native = ref<SelectInstance>()
defineExpose({ native, focus: () => native.value?.focus(), blur: () => native.value?.blur() })
</script>

<template>
  <LiquidGlassControl :class="['liquid-glass-select', $attrs.class]" :style="$attrs.style" kind="field" :source="source">
    <el-select
      ref="native"
      v-bind="nativeControlAttrs($attrs)"
      :popper-class="`liquid-select-popper ${source === 'wallpaper' ? 'glass-workspace-adaptive' : ''} ${props.popperClass}`"
      :show-arrow="false"
      :offset="8"
    >
      <template #header>
        <LiquidGlassDropdownSurface :source="source" :reference="native?.$el" anchor-selector=".el-select__suffix" />
        <span class="liquid-select-caption" aria-hidden="true">{{ props.dropdownTitle || $attrs.placeholder || '请选择' }}</span>
        <slot name="header" />
      </template>
      <slot />
      <template v-for="name in Object.keys($slots).filter(name => name !== 'default' && name !== 'header')" #[name]="slotProps">
        <slot :name="name" v-bind="slotProps || {}" />
      </template>
    </el-select>
  </LiquidGlassControl>
</template>

<style lang="scss">
.liquid-glass-select .el-select__wrapper,
.liquid-glass-control:has(.el-select) .el-select__wrapper {
  min-height: var(--control-height) !important;
  padding-inline: 14px !important;
  border-radius: inherit !important;
  background: transparent !important;
  background-color: transparent !important;
  box-shadow: none !important;
}

// The popup samples wallpaper rather than the table beneath it. This keeps the
// environment visible without refracting business text into the option labels.
.liquid-select-popper.el-popper {
  isolation: isolate;
  min-width: 190px;
  border: 0;
  border-radius: 0;
  background: transparent;
  box-shadow: none;
  color: var(--text-primary);
  -webkit-backdrop-filter: none;
  backdrop-filter: none;
}

.liquid-select-popper.el-popper .el-select-dropdown {
  position: relative;
  z-index: 1;
  border: 0;
  border-radius: inherit;
  background: transparent;
  box-shadow: none;
  -webkit-backdrop-filter: none;
  backdrop-filter: none;
}

.liquid-select-popper .el-select-dropdown__header {
  position: relative;
  padding: 37px 16px 5px;
  border: 0;
}

.liquid-select-popper[data-liquid-drop-side]:not([data-liquid-drop-side='top']) .el-select-dropdown__header {
  padding-top: 13px;
}

.liquid-select-caption {
  display: block;
  color: var(--text-secondary);
  font-size: 11px;
  font-weight: 500;
  line-height: 16px;
}

.liquid-select-popper .el-select-dropdown__list {
  padding: 5px 12px 12px;
}

.liquid-select-popper[data-liquid-drop-side='bottom'] .el-select-dropdown__list {
  padding-bottom: 36px;
}

.liquid-select-popper[data-liquid-drop-side='right'] .el-select-dropdown__header {
  padding-right: 42px;
}

.liquid-select-popper[data-liquid-drop-side='right'] .el-select-dropdown__list {
  padding-right: 38px;
}

.liquid-select-popper[data-liquid-drop-side='left'] .el-select-dropdown__header {
  padding-left: 42px;
}

.liquid-select-popper[data-liquid-drop-side='left'] .el-select-dropdown__list {
  padding-left: 38px;
}

.liquid-select-popper .el-select-dropdown__item {
  position: relative;
  display: flex;
  align-items: center;
  min-height: var(--control-height);
  height: auto;
  margin-block: 2px;
  padding: 8px 32px 8px 11px;
  border-radius: var(--radius-md);
  color: var(--text-primary);
  font-size: 13px;
  font-weight: 400;
  line-height: 20px;
  transition: background-color var(--motion-duration-fast) ease, box-shadow var(--motion-duration-fast) ease;
}

.liquid-select-popper .el-select-dropdown__item.is-hovering:not(.is-disabled) {
  background: var(--surface-glass-inset);
  box-shadow: var(--glass-rim-double);
}

.liquid-select-popper .el-select-dropdown__item.is-selected:not(.is-disabled) {
  color: var(--text-primary);
  background: var(--surface-glass-inset);
  box-shadow: var(--shadow-glass-inset);
  font-weight: 600;

  &::after {
    content: '';
    position: absolute;
    right: 14px;
    width: 5px;
    height: 9px;
    border-right: 1.5px solid currentColor;
    border-bottom: 1.5px solid currentColor;
    transform: translateY(-1px) rotate(45deg);
  }
}

.liquid-select-popper .el-select-dropdown__item.is-disabled {
  color: var(--text-disabled);
}

@media (forced-colors: active) {
  .liquid-select-popper.el-popper {
    background: transparent;
    box-shadow: none;
    color: CanvasText;
  }

  .liquid-select-popper .el-select-dropdown__item,
  .liquid-select-popper .liquid-select-caption {
    color: CanvasText;
  }

  .liquid-select-popper .el-select-dropdown__item.is-selected:not(.is-disabled) {
    outline: 1px solid Highlight;
    outline-offset: -1px;
    color: Highlight;
    background: Canvas;
    box-shadow: none;
  }
}
</style>
