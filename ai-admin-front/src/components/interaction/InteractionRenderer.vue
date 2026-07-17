<template>
  <div class="interaction-renderer">
    <div class="interaction-header">
      <div>
        <h4>{{ uiRequest.title || titleByType }}</h4>
        <span>{{ normalized?.component || uiRequest.component }}</span>
      </div>
      <el-tag v-if="uiRequest.missing?.length" type="warning" size="small">
        待补充 {{ uiRequest.missing.length }} 项
      </el-tag>
    </div>

    <UnifiedInteractionRenderer
      v-if="normalized"
      :request="normalized"
      state="waiting"
      @submit="onSubmit"
      @cancel="onCancel"
      @action="onAction"
    />
    <el-alert
      v-else
      :title="`无法解析交互请求：${uiRequest.component || 'unknown'}`"
      type="warning"
      :closable="false"
    />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { UnifiedInteractionRenderer, normalizeUiRequest } from '@/conversation'
import type { UiRequestPayload } from '@/types/interaction'

const props = defineProps<{
  uiRequest: UiRequestPayload
}>()

const emit = defineEmits<{
  submit: [values: Record<string, unknown>]
  cancel: []
  action: [action: string, values: Record<string, unknown>]
}>()

const normalized = computed(() => normalizeUiRequest(props.uiRequest))

const titleByType = computed(() => {
  if (props.uiRequest.type === 'COLLECT_INPUT') return '信息采集'
  if (props.uiRequest.type === 'USER_CHOICE') return '用户选择'
  if (props.uiRequest.type === 'CONFIRM_ACTION') return '确认操作'
  return '交互输出'
})

function onSubmit(action: string, values: Record<string, unknown>) {
  emit('action', action, values)
  emit('submit', values)
}

function onCancel() {
  emit('cancel')
  emit('action', 'cancel', {})
}

function onAction(action: string, values: Record<string, unknown>) {
  emit('action', action, values)
}
</script>

<style scoped>
.interaction-renderer {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 14px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: var(--el-fill-color-blank);
}

.interaction-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.interaction-header h4 {
  margin: 0 0 4px;
  font-size: 15px;
  font-weight: 650;
}

.interaction-header span {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
