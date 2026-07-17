<template>
  <div
    class="reachai-interaction"
    :class="[
      `reachai-interaction--${kind}`,
      `reachai-interaction--${state}`,
      { 'is-disabled': isDisabled },
    ]"
    role="region"
    :aria-label="request.title || request.component || 'interaction'"
  >
    <header v-if="request.title || request.missing?.length" class="reachai-interaction__header">
      <strong v-if="request.title">{{ request.title }}</strong>
      <span v-if="request.missing?.length" class="reachai-interaction__badge">待补充 {{ request.missing.length }} 项</span>
    </header>

    <p v-if="request.message && kind !== 'form'" class="reachai-interaction__message">{{ request.message }}</p>

    <div v-if="stateLabel" class="reachai-interaction__state" aria-live="polite">{{ stateLabel }}</div>

    <!-- host-registered custom renderer -->
    <component
      v-if="customComponent"
      :is="customComponent"
      class="reachai-interaction__custom-host"
    />

    <!-- form / text_question -->
    <form v-else-if="kind === 'form'" class="reachai-interaction__form" @submit.prevent="submitForm('submit')">
      <label v-for="field in request.fields || []" :key="field.key" class="reachai-interaction__field">
        <span>{{ field.label }}<em v-if="field.required">*</em></span>
        <input
          v-if="field.type === 'boolean'"
          v-model="formValues[field.key]"
          type="checkbox"
          :disabled="isDisabled"
        />
        <input
          v-else-if="field.type === 'number' || field.type === 'integer'"
          v-model.number="formValues[field.key]"
          type="number"
          :disabled="isDisabled"
          :placeholder="field.placeholder"
        />
        <select
          v-else-if="isSelectField(field)"
          :value="selectDisplayValue(field)"
          :multiple="field.type === 'multi_select'"
          :disabled="isDisabled"
          @change="onSelectChange(field, $event)"
        >
          <option v-for="opt in field.options || []" :key="String(opt.value)" :value="opt.value">
            {{ opt.label }}
          </option>
        </select>
        <input
          v-else-if="field.type === 'date'"
          v-model="formValues[field.key]"
          type="date"
          :disabled="isDisabled"
        />
        <textarea
          v-else-if="field.type === 'textarea' || field.type === 'object' || field.type === 'array'"
          :value="String(formValues[field.key] ?? '')"
          rows="3"
          :disabled="isDisabled"
          :placeholder="field.placeholder"
          @input="formValues[field.key] = ($event.target as HTMLTextAreaElement).value"
        />
        <input
          v-else
          :value="String(formValues[field.key] ?? '')"
          type="text"
          :disabled="isDisabled"
          :placeholder="field.placeholder"
          @input="formValues[field.key] = ($event.target as HTMLInputElement).value"
        />
      </label>
      <div v-if="showActionButtons" class="reachai-interaction__actions">
        <button type="button" :disabled="isDisabled" @click="emitCancel">取消</button>
        <button type="submit" class="is-primary" :disabled="isDisabled">提交</button>
      </div>
    </form>

    <!-- confirm -->
    <div v-else-if="kind === 'confirm'" class="reachai-interaction__confirm">
      <p>{{ request.message || '请确认是否继续。' }}</p>
      <div v-if="showActionButtons" class="reachai-interaction__actions">
        <button type="button" :disabled="isDisabled" @click="emitCancel">取消</button>
        <button type="button" :disabled="isDisabled" @click="submit('reject', { confirm: false })">拒绝</button>
        <button type="button" class="is-primary" :disabled="isDisabled" @click="submit('confirm', { confirm: true })">确认</button>
      </div>
    </div>

    <!-- select / choice / multi_select -->
    <div v-else-if="kind === 'select'" class="reachai-interaction__choice">
      <label v-for="opt in choiceOptions" :key="String(opt.value)" class="reachai-interaction__option">
        <input
          v-if="request.component === 'multi_select'"
          v-model="multiChoice"
          type="checkbox"
          :value="opt.value"
          :disabled="isDisabled"
        />
        <input
          v-else
          v-model="choiceValue"
          type="radio"
          :name="choiceRadioName"
          :value="opt.value"
          :disabled="isDisabled"
        />
        <span>{{ opt.label }}</span>
      </label>
      <div v-if="showActionButtons" class="reachai-interaction__actions">
        <button type="button" :disabled="isDisabled" @click="emitCancel">取消</button>
        <button type="button" class="is-primary" :disabled="isDisabled" @click="submitChoice">选择</button>
      </div>
    </div>

    <!-- table -->
    <div v-else-if="kind === 'table'" class="reachai-interaction__table-wrap">
      <table class="reachai-interaction__table">
        <thead>
          <tr>
            <th v-for="col in tableColumns" :key="col.key">{{ col.label }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, idx) in tableRows" :key="idx">
            <td v-for="col in tableColumns" :key="col.key">{{ stringify(row[col.key]) }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- detail / summary / output / card / report -->
    <div v-else-if="kind === 'detail' || kind === 'summary_card' || kind === 'output_card'" class="reachai-interaction__detail">
      <dl>
        <div v-for="item in detailItems" :key="item.key">
          <dt>{{ item.label }}</dt>
          <dd>{{ stringify(item.value) }}</dd>
        </div>
      </dl>
      <div v-if="showActionButtons && actionButtons.length" class="reachai-interaction__actions">
        <button
          v-for="action in actionButtons"
          :key="actionKey(action)"
          type="button"
          :class="{ 'is-primary': actionButtonPrimary(action) }"
          :disabled="isDisabled"
          @click="submit(actionKey(action), actionValues(action))"
        >
          {{ actionLabel(action) }}
        </button>
      </div>
    </div>

    <!-- list_card -->
    <div v-else-if="kind === 'list_card'" class="reachai-interaction__list">
      <article v-for="(row, index) in tableRows" :key="index" class="reachai-interaction__list-item">
        <strong>{{ row.title || row.name || `#${index + 1}` }}</strong>
        <span>{{ row.description || row.summary || stringify(row) }}</span>
      </article>
    </div>

    <!-- page_action -->
    <div v-else-if="kind === 'page_action'" class="reachai-interaction__page-action">
      <p>{{ pageActionTitle }}</p>
      <dl>
        <div><dt>actionKey</dt><dd>{{ pageActionRequest.actionKey || '-' }}</dd></div>
        <div><dt>requestId</dt><dd>{{ pageActionRequest.requestId || request.interactionId || '-' }}</dd></div>
        <div><dt>args</dt><dd>{{ stringify(pageActionRequest.args || {}) }}</dd></div>
      </dl>
      <div class="reachai-interaction__actions">
        <button v-if="pageActionRequest.confirm" type="button" :disabled="isDisabled" @click="emitCancel">取消</button>
        <button
          type="button"
          class="is-primary"
          :disabled="isDisabled"
          @click="submit('page_action_ack', { requestId: pageActionRequest.requestId, actionKey: pageActionRequest.actionKey })"
        >
          已收到
        </button>
      </div>
    </div>

    <!-- custom safe fallback -->
    <div v-else class="reachai-interaction__custom">
      <p class="reachai-interaction__warn">
        {{ customRendererKey ? `自定义渲染器未在宿主注册：${customRendererKey}` : `未注册的交互组件：${request.component || 'unknown'}` }}
      </p>
      <pre>{{ stringify(request.data ?? request.summary ?? {}) }}</pre>
      <div class="reachai-interaction__actions">
        <button type="button" :disabled="isDisabled" @click="emitCancel">取消</button>
        <button type="button" class="is-primary" :disabled="isDisabled" @click="submit('submit', {})">按原始值提交</button>
      </div>
    </div>

    <p v-if="validationError || errorMessage" class="reachai-interaction__error" role="alert">
      {{ validationError || errorMessage }}
    </p>
    <div v-if="state === 'failed'" class="reachai-interaction__actions">
      <button type="button" class="is-primary" @click="retryLast">重试</button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, defineComponent, h, reactive, ref, watch, type Component } from 'vue'
import type { InteractionRenderState, UiFieldPayload, UiRequestV1, UiActionPayload } from '../core/conversationTypes'
import {
  getCustomInteractionRenderer,
  resolveRendererKind,
} from '../renderers/interactionRegistry'

const props = defineProps<{
  request: UiRequestV1
  state?: InteractionRenderState
  errorMessage?: string
  disabled?: boolean
}>()

const emit = defineEmits<{
  submit: [action: string, values: Record<string, unknown>]
  cancel: []
  action: [action: string, values: Record<string, unknown>]
}>()

const kind = computed(() => resolveRendererKind(props.request.component))
const state = computed(() => props.state || 'waiting')
const isDisabled = computed(() =>
  props.disabled
  || state.value === 'submitting'
  || state.value === 'resolved'
  || state.value === 'expired'
  || state.value === 'cancelled',
)
const choiceRadioName = computed(() => `reachai-choice-${props.request.interactionId || 'default'}`)
const validationError = ref('')
const actionButtons = computed(() => (props.request.actions || []) as UiActionPayload[])

const isReadonlyDisplay = computed(() => {
  if (props.request.behavior?.readonly === true) return true
  const displayOnly = new Set(['table', 'detail', 'summary_card', 'list_card', 'output_card', 'card', 'report'])
  return displayOnly.has(kind.value) && !actionButtons.value.length
})

const showActionButtons = computed(() => !isReadonlyDisplay.value)

const stateLabel = computed(() => {
  switch (state.value) {
    case 'submitting': return '提交中…'
    case 'resolved': return '已提交'
    case 'failed': return '提交失败'
    case 'expired': return '已过期'
    case 'cancelled': return '已取消'
    default: return ''
  }
})

const formValues = reactive<Record<string, string | number | boolean | string[]>>({})
const choiceValue = ref<string | number | boolean | null>(null)
const multiChoice = ref<unknown[]>([])
const lastSubmit = ref<{ action: string; values: Record<string, unknown> } | null>(null)

function normalizeFormPrefill(pre: unknown, field: UiFieldPayload): string | number | boolean | string[] {
  if (pre === undefined || pre === null) {
    if (field.type === 'boolean') return false
    if (field.type === 'multi_select') return []
    return ''
  }
  if (typeof pre === 'string' || typeof pre === 'number' || typeof pre === 'boolean') return pre
  if (Array.isArray(pre)) return pre.map(String)
  return String(pre)
}

watch(
  () => props.request,
  (req) => {
    validationError.value = ''
    Object.keys(formValues).forEach((k) => delete formValues[k])
    for (const field of req.fields || []) {
      const pre = req.prefilled?.[field.key]
      formValues[field.key] = normalizeFormPrefill(pre, field)
    }
    const first = req.fields?.[0]
    const firstKey = first?.key
    const choicePrefill = firstKey && req.prefilled?.[firstKey] !== undefined
      ? req.prefilled[firstKey]
      : req.prefilled?.value
    if (req.component === 'multi_select') {
      if (Array.isArray(choicePrefill)) {
        multiChoice.value = [...choicePrefill]
      } else if (choicePrefill == null) {
        multiChoice.value = []
      } else {
        multiChoice.value = [choicePrefill]
      }
      choiceValue.value = null
      return
    }
    if (choicePrefill !== undefined) {
      if (typeof choicePrefill === 'string' || typeof choicePrefill === 'number' || typeof choicePrefill === 'boolean') {
        choiceValue.value = choicePrefill
      } else if (choicePrefill == null) {
        choiceValue.value = null
      } else {
        choiceValue.value = String(choicePrefill)
      }
    }
  },
  { immediate: true, deep: true },
)

function isSelectField(field: UiFieldPayload) {
  return field.type === 'select' || field.type === 'multi_select' || !!field.options?.length
}

function selectDisplayValue(field: UiFieldPayload): string | number | readonly string[] | undefined {
  const value = formValues[field.key]
  if (field.type === 'multi_select') {
    return Array.isArray(value) ? value.map(String) : []
  }
  if (typeof value === 'string' || typeof value === 'number') return value
  return value == null ? undefined : String(value)
}

function onSelectChange(field: UiFieldPayload, event: Event) {
  const target = event.target as HTMLSelectElement
  if (field.type === 'multi_select') {
    formValues[field.key] = Array.from(target.selectedOptions).map((opt) => opt.value)
  } else {
    formValues[field.key] = target.value
  }
}

const choiceOptions = computed(() => {
  const field = props.request.fields?.[0]
  if (field?.options?.length) return field.options
  const data = props.request.data
  if (Array.isArray(data)) {
    return data.map((item, index) => {
      if (item && typeof item === 'object') {
        const rec = item as Record<string, unknown>
        return { value: rec.value ?? rec.id ?? index, label: String(rec.label ?? rec.name ?? rec.value ?? index) }
      }
      return { value: item as string | number | boolean, label: String(item) }
    })
  }
  return []
})

const tableRows = computed((): Record<string, unknown>[] => {
  const data = props.request.data
  if (Array.isArray(data)) {
    return data.map((row) => (row && typeof row === 'object' ? row as Record<string, unknown> : { value: row }))
  }
  if (data && typeof data === 'object') return [data as Record<string, unknown>]
  if (props.request.summary) return [props.request.summary]
  return []
})

const tableColumns = computed(() => {
  const declared = resolveDeclaredColumns(props.request)
  if (declared.length) return declared
  const first = tableRows.value[0]
  if (!first) return [] as Array<{ key: string; label: string }>
  return Object.keys(first).map((key) => ({ key, label: key }))
})

function resolveDeclaredColumns(request: UiRequestV1): Array<{ key: string; label: string }> {
  const candidates = [
    request.schema?.columns,
    request.extension?.columns,
    (request.data && typeof request.data === 'object' && !Array.isArray(request.data)
      ? (request.data as Record<string, unknown>).columns
      : undefined),
  ]
  for (const raw of candidates) {
    if (!Array.isArray(raw) || !raw.length) continue
    return raw.map((item, index) => {
      if (typeof item === 'string') return { key: item, label: item }
      if (item && typeof item === 'object') {
        const rec = item as Record<string, unknown>
        const key = String(rec.key ?? rec.field ?? rec.name ?? index)
        return { key, label: String(rec.label ?? rec.title ?? key) }
      }
      return { key: String(index), label: String(item) }
    })
  }
  return []
}

const detailItems = computed(() => {
  const source = (props.request.summary || props.request.data || {}) as Record<string, unknown>
  if (Array.isArray(source)) {
    return source.map((item, index) => ({
      key: String(index),
      label: `#${index + 1}`,
      value: item,
    }))
  }
  return Object.entries(source || {}).map(([key, value]) => ({ key, label: key, value }))
})

const pageActionRequest = computed(() => {
  const ext = props.request.extension?.pageActionRequest
  if (ext && typeof ext === 'object') return ext as Record<string, unknown>
  if (props.request.data && typeof props.request.data === 'object') return props.request.data as Record<string, unknown>
  return {}
})

const pageActionTitle = computed(() =>
  String(pageActionRequest.value.title || props.request.title || '页面动作'),
)

const customRendererKey = computed(() =>
  props.request.extension?.rendererKey ? String(props.request.extension.rendererKey) : '',
)

const customComponent = computed((): Component | null => {
  const key = customRendererKey.value
  if (!key) return null
  const factory = getCustomInteractionRenderer(key)
  if (!factory) return null
  const rendered = factory({
    request: props.request,
    state: state.value,
    disabled: isDisabled.value,
    onSubmit: (action, values) => submit(action, values),
    onCancel: () => emitCancel(),
  })
  if (!rendered) return null
  return defineComponent({
    name: `ReachAiCustomInteraction_${key}`,
    setup() {
      return () => h(rendered as Component)
    },
  })
})

function stringify(value: unknown): string {
  if (value == null) return '-'
  if (typeof value === 'string') return value
  try {
    return JSON.stringify(value)
  } catch {
    return String(value)
  }
}

function actionKey(action: UiActionPayload): string {
  return String(action.key || action.action || 'submit')
}

function actionLabel(action: UiActionPayload): string {
  return String(action.label || action.key || action.action || '提交')
}

function actionValues(action: UiActionPayload): Record<string, unknown> {
  return (action.values && typeof action.values === 'object' ? action.values : { ...(action as object) }) as Record<string, unknown>
}

function actionButtonPrimary(action: UiActionPayload): boolean {
  return action.type === 'primary' || actionKey(action) === 'confirm' || actionKey(action) === 'submit'
}

function submit(action: string, values: Record<string, unknown>) {
  if (isDisabled.value && state.value !== 'failed') return
  lastSubmit.value = { action, values }
  emit('submit', action, values)
  emit('action', action, values)
}

function submitForm(action: string) {
  for (const field of props.request.fields || []) {
    if (field.required) {
      const value = formValues[field.key]
      if (value === '' || value == null || (Array.isArray(value) && !value.length)) {
        validationError.value = `请填写必填项：${field.label || field.key}`
        return
      }
    }
  }
  validationError.value = ''
  submit(action, { ...formValues })
}

function submitChoice() {
  if (props.request.component === 'multi_select') {
    if (!multiChoice.value.length && props.request.fields?.some((f) => f.required)) {
      validationError.value = '请至少选择一项'
      return
    }
    validationError.value = ''
    submit('choose', { value: [...multiChoice.value] })
  } else {
    if ((choiceValue.value === null || choiceValue.value === '') && props.request.fields?.some((f) => f.required)) {
      validationError.value = '请选择一项'
      return
    }
    validationError.value = ''
    submit('choose', { value: choiceValue.value })
  }
}

function emitCancel() {
  if (isDisabled.value) return
  emit('cancel')
  emit('action', 'cancel', {})
}

function retryLast() {
  if (lastSubmit.value) {
    emit('submit', lastSubmit.value.action, lastSubmit.value.values)
    emit('action', lastSubmit.value.action, lastSubmit.value.values)
  }
}
</script>

<style scoped>
.reachai-interaction {
  border: 1px solid var(--reachai-chat-glass-border-soft, var(--reachai-chat-border, #d7e0de));
  border-radius: var(--reachai-chat-radius, 12px);
  background: var(--reachai-chat-glass-interaction, var(--reachai-chat-surface, #fff));
  box-shadow: var(--reachai-chat-glass-highlight, none);
  /* 嵌套层不重复 blur；依赖外层智能体卡片 backdrop-filter */
  -webkit-backdrop-filter: none;
  backdrop-filter: none;
  padding: 12px;
  margin-top: 8px;
  font-family: var(--reachai-chat-font-family, inherit);
  color: var(--reachai-chat-text, #1e293b);
}
.reachai-interaction__header {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
}
.reachai-interaction__badge,
.reachai-interaction__state {
  font-size: 12px;
  color: var(--reachai-chat-text-muted, #5b6b69);
}
.reachai-interaction__field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-bottom: 10px;
  font-size: 13px;
}
.reachai-interaction__field em {
  color: var(--reachai-chat-danger, #b42318);
  margin-left: 2px;
}
.reachai-interaction__field input,
.reachai-interaction__field select,
.reachai-interaction__field textarea,
.reachai-interaction__actions button {
  font: inherit;
  border: 1px solid var(--reachai-chat-glass-border-soft, var(--reachai-chat-border, #d7e0de));
  border-radius: 8px;
  padding: 8px 10px;
  background: var(--reachai-chat-glass-control, var(--reachai-chat-surface-muted, #f5f7f7));
  color: inherit;
  max-width: 100%;
  box-sizing: border-box;
}
.reachai-interaction__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}
.reachai-interaction__actions button.is-primary {
  background: var(--reachai-chat-primary, #0f766e);
  color: var(--reachai-chat-primary-contrast, #fff);
  border-color: transparent;
}
.reachai-interaction__actions button:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
.reachai-interaction__table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}
.reachai-interaction__table th,
.reachai-interaction__table td {
  border: 1px solid var(--reachai-chat-glass-border-soft, var(--reachai-chat-border, #d7e0de));
  padding: 6px 8px;
  text-align: left;
  background: var(--reachai-chat-glass-control, transparent);
}
.reachai-interaction__detail dl,
.reachai-interaction__page-action dl {
  margin: 0;
}
.reachai-interaction__detail dt,
.reachai-interaction__page-action dt {
  font-size: 12px;
  color: var(--reachai-chat-text-muted, #5b6b69);
}
.reachai-interaction__detail dd,
.reachai-interaction__page-action dd {
  margin: 0 0 8px;
  font-size: 13px;
}
.reachai-interaction__list-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 8px 0;
  border-bottom: 1px solid var(--reachai-chat-border, #d7e0de);
}
.reachai-interaction__warn {
  color: #9a6700;
  font-size: 13px;
}
.reachai-interaction__error {
  color: var(--reachai-chat-danger, #b42318);
  font-size: 13px;
  margin-top: 8px;
}
.reachai-interaction--resolved {
  /* 不用整卡 opacity，避免套娃透明 */
  box-shadow: var(--reachai-chat-glass-inset-edge, none);
}
.reachai-interaction--expired,
.reachai-interaction--cancelled {
  border-color: color-mix(in srgb, var(--reachai-chat-warning, #b54708) 28%, transparent);
  background: color-mix(in srgb, var(--reachai-chat-warning, #b54708) 6%, var(--reachai-chat-glass-interaction, transparent));
}
.reachai-interaction pre {
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 12px;
  background: var(--reachai-chat-glass-control, var(--reachai-chat-surface-muted, #f5f7f7));
  padding: 8px;
  border-radius: 8px;
}
@media (prefers-reduced-motion: reduce) {
  .reachai-interaction * {
    transition: none !important;
  }
}
</style>
