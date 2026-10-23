<script setup lang="ts">
import { ref } from 'vue'
import type { InputInstance } from 'element-plus'
import LiquidGlassControl from './LiquidGlassControl.vue'
import { nativeControlAttrs, type LiquidGlassSource } from './liquidGlassContext'

defineOptions({ inheritAttrs: false })
defineProps<{ source?: LiquidGlassSource }>()
const native = ref<InputInstance>()
defineExpose({
  native,
  focus: () => native.value?.focus(),
  blur: () => native.value?.blur(),
  select: () => native.value?.select(),
  clear: () => native.value?.clear(),
})
</script>

<template>
  <LiquidGlassControl :class="['liquid-glass-input', $attrs.class]" :style="$attrs.style" kind="field" :source="source">
    <el-input ref="native" v-bind="nativeControlAttrs($attrs)">
      <template v-for="(_, name) in $slots" #[name]="slotProps">
        <slot :name="name" v-bind="slotProps || {}" />
      </template>
    </el-input>
  </LiquidGlassControl>
</template>
