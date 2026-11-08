<template>
  <LiquidGlassButton
    :source="source"
    :size="size"
    :type="type"
    :icon="MagicStick"
    :disabled="disabled"
    :data-testid="`ai-handoff-button-${scenario}`"
    @click="open"
  >{{ label }}</LiquidGlassButton>
  <AiHandoffDialog v-model="visible" :scenario="scenario" :context="context" :missing-hint="missingHint">
    <template v-if="$slots.fallback" #fallback><slot name="fallback" /></template>
  </AiHandoffDialog>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { MagicStick } from '@element-plus/icons-vue'
import { LiquidGlassButton, type LiquidGlassSource } from '@/components/common/liquid-glass'
import AiHandoffDialog from './AiHandoffDialog.vue'
import type { AiPromptContext } from './aiPromptScenarios'

const props = withDefaults(defineProps<{
  scenario: string
  context?: AiPromptContext
  label?: string
  missingHint?: string
  source?: LiquidGlassSource
  size?: 'small' | 'default' | 'large'
  type?: 'primary' | 'default'
  disabled?: boolean
  /** 打开前准备上下文（例如先创建任务、签发领取码）；返回 false 则不打开。 */
  beforeOpen?: () => Promise<boolean | void> | boolean | void
}>(), { context: () => ({}), label: '交给我的 AI', missingHint: '', source: 'wallpaper', size: 'default', type: 'default', disabled: false, beforeOpen: undefined })

const visible = ref(false)

async function open() {
  if (props.beforeOpen) {
    const result = await props.beforeOpen()
    if (result === false) return
  }
  visible.value = true
}
</script>
