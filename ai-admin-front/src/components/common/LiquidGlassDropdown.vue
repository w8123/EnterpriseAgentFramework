<script setup lang="ts">
import { ref } from 'vue'
import type { DropdownInstance } from 'element-plus'
import LiquidGlassDropdownSurface from './LiquidGlassDropdownSurface.vue'
import { useLiquidGlassSource, type LiquidGlassSource } from './liquidGlassContext'

defineOptions({ inheritAttrs: false })
const props = withDefaults(defineProps<{ source?: LiquidGlassSource; popperClass?: string; title?: string }>(), { popperClass: '', title: '' })
const source = useLiquidGlassSource(() => props.source)
const native = ref<DropdownInstance>()
defineExpose({ native, handleOpen: () => native.value?.handleOpen(), handleClose: () => native.value?.handleClose() })
</script>

<template>
  <el-dropdown ref="native" v-bind="$attrs" :popper-class="`liquid-menu-popper ${source === 'wallpaper' ? 'glass-workspace-adaptive' : ''} ${props.popperClass}`" :show-arrow="false" :offset="8">
    <slot />
    <template #dropdown>
      <LiquidGlassDropdownSurface :source="source" :reference="native?.$el" anchor-selector=".el-icon--right" />
      <div class="liquid-menu-content">
        <span v-if="props.title" class="liquid-menu-title">{{ props.title }}</span>
        <slot name="dropdown" />
      </div>
    </template>
  </el-dropdown>
</template>

<style lang="scss">
.liquid-menu-popper.el-popper { isolation: isolate; min-width: 200px; max-width: calc(100vw - 24px); padding: 0; border: 0; border-radius: 0; background: transparent; box-shadow: none; -webkit-backdrop-filter: none; backdrop-filter: none; }
.liquid-menu-content { position: relative; z-index: 1; padding: 36px 10px 10px; color: var(--text-primary); }
.liquid-menu-popper[data-liquid-drop-side='bottom'] .liquid-menu-content { padding: 10px 10px 36px; }
.liquid-menu-popper[data-liquid-drop-side='right'] .liquid-menu-content { padding: 10px 36px 10px 10px; }
.liquid-menu-popper[data-liquid-drop-side='left'] .liquid-menu-content { padding: 10px 10px 10px 36px; }
.liquid-menu-title { display: block; padding: 0 7px 7px; color: var(--text-secondary); font-size: 11px; }
.liquid-menu-popper .el-dropdown-menu { padding: 0; background: transparent; box-shadow: none; -webkit-backdrop-filter: none; backdrop-filter: none; }
.liquid-menu-popper .el-dropdown-menu__item { min-height: var(--control-height); gap: 8px; margin-block: 2px; padding: 8px 12px; border-radius: var(--radius-md); color: var(--text-primary); font-size: 13px; }
.liquid-menu-popper .el-dropdown-menu__item:not(.is-disabled):is(:hover, :focus) { color: var(--text-primary); background: var(--surface-glass-inset); box-shadow: var(--glass-rim-double); }
.liquid-menu-popper .el-dropdown-menu__item.is-disabled { color: var(--text-disabled); }
@media (forced-colors: active) {
  .liquid-menu-popper .el-dropdown-menu__item, .liquid-menu-title { color: CanvasText; }
  .liquid-menu-popper .el-dropdown-menu__item:not(.is-disabled):focus { outline: 1px solid Highlight; color: Highlight; }
}
</style>
