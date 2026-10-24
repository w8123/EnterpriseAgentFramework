<script setup lang="ts">
import { computed, ref, useAttrs } from 'vue'
import type { ButtonInstance } from 'element-plus'
import LiquidGlassControl from './LiquidGlassControl.vue'
import { nativeControlAttrs, useLiquidGlassSource, type LiquidGlassSource } from './liquidGlassContext'

defineOptions({ inheritAttrs: false })
const props = withDefaults(defineProps<{
  source?: LiquidGlassSource
  tone?: 'neutral' | 'primary'
  circle?: boolean
  link?: boolean
}>(), { circle: false })
const attrs = useAttrs()
const source = useLiquidGlassSource(() => props.source)
const native = ref<ButtonInstance>()
const tone = computed(() => props.tone ?? (attrs.type === 'primary' ? 'primary' : 'neutral'))
defineExpose({ native, focus: () => native.value?.ref?.focus(), blur: () => native.value?.ref?.blur() })
</script>

<template>
  <el-button
    v-if="props.link"
    ref="native"
    v-bind="nativeControlAttrs($attrs)"
    link
    :circle="props.circle"
    :class="['liquid-glass-button', 'liquid-glass-button--link', { 'samples-wallpaper': source === 'wallpaper' }, $attrs.class]"
    :style="$attrs.style"
  >
    <template v-for="(_, name) in $slots" #[name]="slotProps">
      <slot :name="name" v-bind="slotProps || {}" />
    </template>
  </el-button>
  <LiquidGlassControl
    v-else
    :class="['liquid-glass-button', $attrs.class]"
    :style="$attrs.style"
    :source="props.source"
    :tone="tone"
    :shape="props.circle ? 'circle' : 'pill'"
  >
    <el-button ref="native" v-bind="nativeControlAttrs($attrs)" :circle="props.circle">
      <template v-for="(_, name) in $slots" #[name]="slotProps">
        <slot :name="name" v-bind="slotProps || {}" />
      </template>
    </el-button>
  </LiquidGlassControl>
</template>

<style scoped lang="scss">
.liquid-glass-button--link {
  --el-color-primary: var(--brand-on-surface);
  --el-text-color-regular: var(--text-secondary);
  flex-shrink: 0;
  white-space: nowrap;
}

.liquid-glass-button--link.samples-wallpaper {
  --el-color-primary: var(--text-on-wallpaper);
  --el-text-color-regular: var(--text-on-wallpaper-secondary);
}
</style>
