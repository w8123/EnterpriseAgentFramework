<template>
  <div v-if="payload" class="dynamic-interaction">
    <el-alert v-if="payload.title" :title="payload.title" type="info" :closable="false" show-icon class="mb" />
    <FormCard
      v-if="payload.component === 'form'"
      :fields="payload.fields || []"
      :prefilled="payload.prefilled"
      @submit="forwardSubmit('submit', $event)"
      @cancel="forwardSubmit('cancel', {})"
    />
    <SummaryCard
      v-else-if="payload.component === 'summary_card'"
      :title="payload.title"
      :message="payload.message"
      :summary="payload.summary"
      @submit="forwardSubmit('submit', {})"
      @modify="forwardSubmit('modify', {})"
      @cancel="forwardSubmit('cancel', {})"
    />
    <TextQuestionCard
      v-else-if="payload.component === 'text_question'"
      :message="payload.message"
      @submit="forwardSubmit('submit', $event)"
      @cancel="forwardSubmit('cancel', {})"
    />
    <SelectCard
      v-else-if="payload.component === 'select' && payload.fields?.[0]"
      :field="payload.fields[0]"
      :prefilled="payload.prefilled"
      @submit="forwardSubmit('submit', $event)"
      @cancel="forwardSubmit('cancel', {})"
    />
    <ConfirmCard
      v-else-if="payload.component === 'confirm'"
      :message="payload.message"
      @submit="forwardSubmit('submit', $event)"
      @cancel="forwardSubmit('cancel', {})"
    />
    <el-alert v-else :title="'不支持的 UI 组件: ' + (payload.component || '')" type="warning" :closable="false" />
  </div>
</template>

<script setup lang="ts">
import type { UiRequestPayload } from '@/types/interaction'
import FormCard from './FormCard.vue'
import SummaryCard from './SummaryCard.vue'
import TextQuestionCard from './TextQuestionCard.vue'
import SelectCard from './SelectCard.vue'
import ConfirmCard from './ConfirmCard.vue'

const props = defineProps<{
  payload: UiRequestPayload | null | undefined
}>()

const emit = defineEmits<{
  /** action + 表单值，由父组件组装 uiSubmit 调后端 */
  action: [action: string, values: Record<string, unknown>]
}>()

function forwardSubmit(action: string, values: Record<string, unknown>) {
  emit('action', action, values)
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
