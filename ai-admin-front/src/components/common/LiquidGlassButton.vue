<script setup lang="ts">
import { computed, ref, useAttrs } from 'vue'
import type { ButtonInstance } from 'element-plus'
import LiquidGlassControl from './LiquidGlassControl.vue'
import { nativeControlAttrs, type LiquidGlassSource } from './liquidGlassContext'

defineOptions({ inheritAttrs: false })
const props = withDefaults(defineProps<{
  source?: LiquidGlassSource
  tone?: 'neutral' | 'primary'
  circle?: boolean
}>(), { circle: false })
const attrs = useAttrs()
const native = ref<ButtonInstance>()
const tone = computed(() => props.tone ?? (attrs.type === 'primary' ? 'primary' : 'neutral'))
defineExpose({ native, focus: () => native.value?.ref?.focus(), blur: () => native.value?.ref?.blur() })
</script>

<template>
  <LiquidGlassControl
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
