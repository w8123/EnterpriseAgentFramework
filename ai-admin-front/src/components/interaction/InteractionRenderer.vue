<template>
  <div class="interaction-renderer">
    <div class="interaction-header">
      <div>
        <h4>{{ uiRequest.title || titleByType }}</h4>
        <span>{{ normalizedComponent }}</span>
      </div>
      <el-tag v-if="uiRequest.missing?.length" type="warning" size="small">
        待补充 {{ uiRequest.missing.length }} 项
      </el-tag>
    </div>

    <el-form
      v-if="rendererKind === 'form'"
      label-position="top"
      class="interaction-form"
      @submit.prevent
    >
      <el-form-item
        v-for="field in uiRequest.fields || []"
        :key="fieldKey(field)"
        :label="fieldLabel(field)"
        :required="field.required"
      >
        <el-switch
          v-if="field.type === 'boolean'"
          v-model="formValues[fieldKey(field)]"
        />
        <el-input-number
          v-else-if="field.type === 'number' || field.type === 'integer'"
          v-model="formValues[fieldKey(field)]"
          style="width: 100%"
        />
        <el-select
          v-else-if="isSelectField(field)"
          v-model="formValues[fieldKey(field)]"
          :multiple="field.type === 'multi_select'"
          collapse-tags
          collapse-tags-tooltip
          filterable
          clearable
          style="width: 100%"
          :placeholder="field.placeholder || '请选择'"
        >
          <el-option
            v-for="option in field.options || []"
            :key="String(option.value)"
            :label="option.label"
            :value="option.value"
          />
        </el-select>
        <el-date-picker
          v-else-if="field.type === 'date'"
          v-model="formValues[fieldKey(field)]"
          type="date"
          value-format="YYYY-MM-DD"
          style="width: 100%"
        />
        <el-input
          v-else-if="field.type === 'textarea' || field.type === 'object' || field.type === 'array'"
          v-model="formValues[fieldKey(field)]"
          type="textarea"
          :rows="3"
          :placeholder="field.placeholder || placeholderFor(field.type)"
        />
        <el-input
          v-else
          v-model="formValues[fieldKey(field)]"
          :placeholder="field.placeholder || placeholderFor(field.type)"
        />
      </el-form-item>
      <div class="interaction-actions">
        <el-button @click="emitCancel">取消</el-button>
        <el-button type="primary" @click="emitSubmit('submit')">提交并继续</el-button>
      </div>
    </el-form>

    <div v-else-if="rendererKind === 'confirm'" class="confirm-card">
      <p>{{ uiRequest.message || '请确认是否继续。' }}</p>
      <div class="interaction-actions">
        <el-button @click="emitCancel">取消</el-button>
        <el-button @click="emitSubmit('reject', { confirm: false })">拒绝</el-button>
        <el-button type="primary" @click="emitSubmit('confirm', { confirm: true })">确认</el-button>
      </div>
    </div>

    <div v-else-if="rendererKind === 'select'" class="choice-card">
      <p v-if="uiRequest.message">{{ uiRequest.message }}</p>
      <el-radio-group v-model="choiceValue" class="choice-list">
        <el-radio
          v-for="option in choiceOptions"
          :key="String(option.value)"
          :label="option.value"
        >
          {{ option.label }}
        </el-radio>
      </el-radio-group>
      <div class="interaction-actions">
        <el-button @click="emitCancel">取消</el-button>
        <el-button type="primary" @click="emitSubmit('choose', { value: choiceValue })">选择</el-button>
      </div>
    </div>

    <el-table v-else-if="rendererKind === 'table'" :data="tableRows" size="small" border>
      <el-table-column
        v-for="column in tableColumns"
        :key="column.key"
        :prop="column.key"
        :label="column.label"
        min-width="120"
        show-overflow-tooltip
      />
    </el-table>

    <el-descriptions v-else-if="rendererKind === 'detail'" :column="1" border size="small">
      <el-descriptions-item v-for="item in detailItems" :key="item.key" :label="item.label">
        {{ stringify(item.value) }}
      </el-descriptions-item>
    </el-descriptions>

    <div v-else-if="rendererKind === 'list_card'" class="list-card">
      <article v-for="(row, index) in tableRows" :key="index" class="list-card-item">
        <strong>{{ row.title || row.name || `#${index + 1}` }}</strong>
        <span>{{ row.description || row.summary || stringify(row) }}</span>
      </article>
    </div>

    <div v-else-if="rendererKind === 'output_card' || rendererKind === 'summary_card'" class="output-card">
      <p v-if="uiRequest.message">{{ uiRequest.message }}</p>
      <el-descriptions :column="1" border size="small">
        <el-descriptions-item v-for="item in detailItems" :key="item.key" :label="item.label">
          {{ stringify(item.value) }}
        </el-descriptions-item>
      </el-descriptions>
      <div v-if="actionButtons.length" class="interaction-actions">
        <el-button
          v-for="action in actionButtons"
          :key="actionKey(action)"
          :type="actionButtonType(action)"
          @click="emitSubmit(actionKey(action), actionValues(action))"
        >
          {{ actionLabel(action) }}
        </el-button>
      </div>
    </div>

    <div v-else-if="rendererKind === 'custom'" class="custom-renderer-card">
      <el-alert
        :title="customRendererKey ? `已绑定注册渲染器：${customRendererKey}` : '缺少注册渲染器 rendererKey'"
        :type="customRendererKey ? 'info' : 'warning'"
        :closable="false"
      />
      <el-descriptions :column="1" border size="small">
        <el-descriptions-item v-for="item in detailItems" :key="item.key" :label="item.label">
          {{ stringify(item.value) }}
        </el-descriptions-item>
      </el-descriptions>
    </div>

    <div v-else-if="rendererKind === 'page_action'" class="page-action-card">
      <el-alert
        :title="pageActionTitle"
        type="info"
        :closable="false"
      />
      <el-descriptions :column="1" border size="small">
        <el-descriptions-item label="actionKey">{{ pageActionRequest.actionKey || '-' }}</el-descriptions-item>
        <el-descriptions-item label="requestId">{{ pageActionRequest.requestId || uiRequest.interactionId || '-' }}</el-descriptions-item>
        <el-descriptions-item label="confirm">{{ pageActionRequest.confirm === true ? 'true' : 'false' }}</el-descriptions-item>
        <el-descriptions-item label="args">{{ stringify(pageActionRequest.args || {}) }}</el-descriptions-item>
      </el-descriptions>
      <div class="interaction-actions">
        <el-button v-if="pageActionRequest.confirm" @click="emitCancel">取消</el-button>
        <el-button type="primary" @click="emitSubmit('page_action_ack', { requestId: pageActionRequest.requestId, actionKey: pageActionRequest.actionKey })">
          已收到
        </el-button>
      </div>
    </div>

    <div v-else class="unsupported-card">
      <el-alert
        :title="`未注册的交互组件：${uiRequest.component || 'unknown'}`"
        type="warning"
        :closable="false"
      />
      <pre>{{ stringify(uiRequest) }}</pre>
      <div class="interaction-actions">
        <el-button @click="emitCancel">取消</el-button>
        <el-button type="primary" @click="emitSubmit('submit')">按原始值提交</el-button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import type { UiFieldOptionPayload, UiFieldPayload, UiRequestPayload } from '@/types/interaction'

const props = defineProps<{
  uiRequest: UiRequestPayload
}>()

const emit = defineEmits<{
  submit: [values: Record<string, unknown>]
  cancel: []
  action: [action: string, values: Record<string, unknown>]
}>()

const formValues = reactive<Record<string, unknown>>({})
const choiceValue = ref<unknown>('')

const componentRegistry: Record<string, string> = {
  form: 'form',
  text_question: 'form',
  confirm: 'confirm',
  choice: 'select',
  select: 'select',
  multi_select: 'select',
  table: 'table',
  detail: 'detail',
  card: 'output_card',
  report: 'summary_card',
  summary_card: 'summary_card',
  list_card: 'list_card',
  output_card: 'output_card',
  custom: 'custom',
  page_action: 'page_action',
}

const normalizedComponent = computed(() => String(props.uiRequest.component || '').toLowerCase())
const rendererKind = computed(() => componentRegistry[normalizedComponent.value] || normalizedComponent.value || 'json')

const titleByType = computed(() => {
  if (props.uiRequest.type === 'COLLECT_INPUT') return '信息采集'
  if (props.uiRequest.type === 'USER_CHOICE') return '用户选择'
  if (props.uiRequest.type === 'CONFIRM_ACTION') return '确认操作'
  return '交互输出'
})

const choiceOptions = computed<UiFieldOptionPayload[]>(() => {
  const firstField = props.uiRequest.fields?.[0]
  if (firstField?.options?.length) return firstField.options
  const actions = props.uiRequest.actions || []
  return actions.map((action) => ({
    value: String(action.value ?? action.action ?? action.key ?? action.label),
    label: String(action.label ?? action.name ?? action.action ?? action.value),
  }))
})

const displayData = computed(() => normalizeDisplayData(props.uiRequest.data))

const displaySummary = computed(() => {
  const summary = props.uiRequest.summary
  if (summary && Object.keys(summary).length) {
    const entries = Object.entries(summary)
    if (entries.length === 1 && entries[0][0] === 'value') {
      return normalizeDisplayData(entries[0][1])
    }
    return normalizeDisplayData(summary)
  }
  return displayData.value
})

const tableRows = computed<Record<string, unknown>[]>(() => {
  return rowsFromData(displayData.value)
})

const customRendererKey = computed(() =>
  String(props.uiRequest.schema?.rendererKey || props.uiRequest.extension?.rendererKey || '').trim(),
)

const pageActionRequest = computed<Record<string, unknown>>(() => {
  const extensionRequest = props.uiRequest.extension?.pageActionRequest
  if (isRecord(extensionRequest)) return extensionRequest
  if (isRecord(props.uiRequest.data)) return props.uiRequest.data
  return {}
})

const pageActionTitle = computed(() => {
  const actionKey = String(pageActionRequest.value.actionKey || '').trim()
  return actionKey ? `页面动作请求：${actionKey}` : '页面动作请求'
})

const actionButtons = computed(() => {
  return (props.uiRequest.actions || []).filter(isRecord)
})

const tableColumns = computed(() => {
  const schemaColumns = (props.uiRequest.schema?.columns || []) as Array<{ key: string; label?: string }>
  if (schemaColumns.length) {
    return schemaColumns.map((column) => ({ key: column.key, label: column.label || column.key }))
  }
  const first = tableRows.value[0]
  return first ? Object.keys(first).map((key) => ({ key, label: key })) : []
})

const detailItems = computed(() => {
  const data = displaySummary.value
  if (!data || typeof data !== 'object' || Array.isArray(data)) {
    return [{ key: 'value', label: '结果', value: data }]
  }
  return Object.entries(data as Record<string, unknown>).map(([key, value]) => ({ key, label: key, value }))
})

function normalizeDisplayData(value: unknown): unknown {
  if (typeof value === 'string') {
    const text = value.trim()
    if (!text) return value
    if ((text.startsWith('{') && text.endsWith('}')) || (text.startsWith('[') && text.endsWith(']'))) {
      try {
        return normalizeDisplayData(JSON.parse(text))
      } catch {
        return value
      }
    }
  }
  if (value && typeof value === 'object' && !Array.isArray(value)) {
    const record = value as Record<string, unknown>
    if (typeof record.data === 'string') {
      return { ...record, data: normalizeDisplayData(record.data) }
    }
  }
  return value
}

function rowsFromData(value: unknown): Record<string, unknown>[] {
  const normalized = normalizeDisplayData(value)
  if (Array.isArray(normalized)) return normalized.filter(isRecord) as Record<string, unknown>[]
  if (!isRecord(normalized)) return []
  const direct = rowsFromRecord(normalized)
  if (direct.length) return direct
  for (const key of ['data', 'result', 'payload']) {
    const nested = rowsFromData(normalized[key])
    if (nested.length) return nested
  }
  return []
}

function rowsFromRecord(record: Record<string, unknown>): Record<string, unknown>[] {
  for (const key of ['records', 'items', 'rows', 'list']) {
    const rows = record[key]
    if (Array.isArray(rows)) return rows.filter(isRecord) as Record<string, unknown>[]
  }
  return []
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === 'object' && !Array.isArray(value)
}

watch(
  () => props.uiRequest,
  (uiRequest) => {
    Object.keys(formValues).forEach((key) => delete formValues[key])
    for (const field of uiRequest.fields || []) {
      const key = fieldKey(field)
      if (!key) continue
      formValues[key] = uiRequest.prefilled?.[key] ?? defaultValue(field)
    }
    choiceValue.value = choiceOptions.value[0]?.value ?? ''
  },
  { immediate: true, deep: true },
)

function fieldKey(field: UiFieldPayload) {
  return String(field.key || field.name || '').trim()
}

function fieldLabel(field: UiFieldPayload) {
  return field.label || field.name || field.key
}

function defaultValue(field: UiFieldPayload) {
  if (field.type === 'multi_select') return []
  if (field.type === 'boolean') return false
  return ''
}

function isSelectField(field: UiFieldPayload) {
  return field.type === 'select' || field.type === 'multi_select' || Boolean(field.options?.length)
}

function placeholderFor(type: string) {
  if (type === 'file') return '请输入文件 ID 或资源标识'
  if (type === 'number' || type === 'integer') return '请输入数字'
  if (type === 'object' || type === 'array') return '请输入 JSON'
  return '请输入'
}

function emitSubmit(action: string, values?: Record<string, unknown>) {
  const payload = values ?? { ...formValues }
  emit('action', action, payload)
  emit('submit', payload)
}

function emitCancel() {
  emit('cancel')
  emit('action', 'cancel', {})
}

function stringify(value: unknown) {
  if (value == null) return ''
  if (typeof value === 'string') return value
  return JSON.stringify(value, null, 2)
}

function actionKey(action: Record<string, unknown>) {
  return String(action.action || action.key || action.value || action.label || 'submit')
}

function actionLabel(action: Record<string, unknown>) {
  return String(action.label || action.name || action.action || action.key || 'Submit')
}

function actionValues(action: Record<string, unknown>) {
  return isRecord(action.values) ? action.values : {}
}

function actionButtonType(action: Record<string, unknown>) {
  const key = actionKey(action)
  return key === 'submit' || key === 'confirm' ? 'primary' : 'default'
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

.interaction-header,
.interaction-actions {
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

.interaction-actions {
  justify-content: flex-end;
}

.choice-list {
  display: grid;
  gap: 8px;
}

.list-card {
  display: grid;
  gap: 8px;
}

.list-card-item {
  display: grid;
  gap: 4px;
  padding: 10px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-fill-color-lighter);
}

.list-card-item span,
.output-card p,
.confirm-card p,
.choice-card p {
  margin: 0;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
}

.unsupported-card {
  display: grid;
  gap: 10px;
}

.unsupported-card pre {
  max-height: 260px;
  overflow: auto;
  margin: 0;
  padding: 12px;
  border-radius: 6px;
  background: var(--el-fill-color-light);
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
