<template>
  <div v-if="normalized" class="dynamic-interaction">
    <LiquidGlassNotice
      v-if="normalized.title"
      :title="normalized.title"
      type="info"
      class="mb"
      source="backdrop"
    />
    <UnifiedInteractionRenderer
      :request="normalized"
      state="waiting"
      @submit="forwardSubmit"
      @cancel="forwardCancel"
      @action="forwardAction"
    />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { UnifiedInteractionRenderer, normalizeUiRequest } from '@/conversation'
import type { UiRequestPayload } from '@/types/interaction'
import { LiquidGlassNotice } from '@/components/common/liquid-glass'

const props = defineProps<{
  payload: UiRequestPayload | null | undefined
}>()

const emit = defineEmits<{
  /** action + 表单值，由父组件组装 uiSubmit 调后端 */
  action: [action: string, values: Record<string, unknown>]
}>()

const normalized = computed(() => normalizeUiRequest(props.payload))

function forwardAction(action: string, values: Record<string, unknown>) {
  emit('action', action, values)
}

function forwardSubmit(action: string, values: Record<string, unknown>) {
  emit('action', action, values)
}

function forwardCancel() {
  emit('action', 'cancel', {})
}
</script>

<style scoped>
.dynamic-interaction {
  margin-top: 8px;
  padding: 12px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: var(--el-fill-color-blank);
}
.mb {
  margin-bottom: 12px;
}
</style>
