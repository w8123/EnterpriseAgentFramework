<template>
  <section class="invocation-input-editor" aria-labelledby="invocation-input-title">
    <header class="input-editor-header">
      <div>
        <h3 id="invocation-input-title">{{ title }}</h3>
        <p>{{ props.httpJsonBody ? '按请求体契约填写对象、数组或标量；完整 JSON 保留原值，留空表示不提供请求体。' : '只会提交当前可确定有效的 JSON 对象；完整 JSON 与表单会保留同一份输入语义。' }}</p>
      </div>
      <div class="input-editor-actions">
        <el-button v-if="sampleInput" text size="small" :disabled="props.disabled" @click="fillSample">填入来源样例</el-button>
        <el-radio-group v-if="!props.jsonOnly" v-model="mode" size="small" :disabled="props.disabled" aria-label="输入方式" @change="changeMode">
          <el-radio-button value="form">表单</el-radio-button>
          <el-radio-button value="json">JSON</el-radio-button>
        </el-radio-group>
      </div>
    </header>

    <LiquidGlassNotice
      v-if="singleDtoRootName"
      class="input-editor-note"
      title="单 DTO 输入形式"
      :description="`可使用平铺字段或 ${singleDtoRootName} 包装形式。表单会保留你当前的包装与额外字段；完整 JSON 按原样提交。`"
      type="info"
      source="backdrop"
    />

    <LiquidGlassNotice
      v-if="formModeNotice"
      class="input-editor-note"
      title="请继续使用 JSON 编辑"
      :description="formModeNotice"
      type="warning"
      source="backdrop"
    />

    <template v-if="mode === 'form'">
      <div v-if="!parameters.length" class="input-editor-empty">{{ props.httpJsonBody ? '此请求体没有可填写字段；可在 JSON 模式输入对象。' : '该业务方法无需输入参数。' }}</div>
      <div v-else class="input-form" role="group" aria-label="调用输入字段">
        <el-form-item
          v-for="parameter in parameters"
          :key="parameter.name"
          :label="parameterLabel(parameter)"
          :required="parameter.required"
          :error="fieldFor(parameter).error"
        >
          <template #label>
            <span class="input-field-label">
              <span>{{ parameterLabel(parameter) }}</span>
              <small>{{ parameter.type || '未声明类型' }}</small>
              <span v-if="parameterContainsSensitive(parameter)" class="sensitive-badge">敏感</span>
            </span>
          </template>
          <div class="input-field-control">
            <el-checkbox
              v-if="!parameter.required"
              v-model="fieldFor(parameter).present"
              class="input-field-present"
              :disabled="props.disabled"
              @change="onPresenceChanged(parameter, $event)"
            >填写</el-checkbox>
            <div class="input-field-value">
              <template v-if="parameterContainsSensitive(parameter) && !sensitiveVisible(parameter.name)">
                <span class="sensitive-field-preview" aria-label="敏感值已隐藏">{{ maskedFieldPreview(parameter) }}</span>
                <el-button
                  text
                  size="small"
                  class="sensitive-toggle"
                  :disabled="props.disabled || !fieldFor(parameter).present"
                  :aria-label="`显示并编辑 ${parameter.name} 的敏感值`"
                  @click="showSensitive(parameter.name)"
                >显示并编辑</el-button>
              </template>
              <template v-else>
                <el-checkbox
                  v-if="fieldFor(parameter).present"
                  v-model="fieldFor(parameter).isNull"
                  class="input-field-null"
                  :disabled="props.disabled"
                  @change="onNullChanged(parameter, $event)"
                >使用 null</el-checkbox>
                <el-select
                  v-if="isBooleanParameter(parameter)"
                  v-model="fieldFor(parameter).value"
                  :disabled="props.disabled || !fieldFor(parameter).present || fieldFor(parameter).isNull"
                  placeholder="请选择"
                  @change="onSelectChanged(parameter, $event)"
                >
                  <el-option label="true" value="true" />
                  <el-option label="false" value="false" />
                </el-select>
                <el-input
                  v-else-if="isComplexParameter(parameter)"
                  v-model="fieldFor(parameter).value"
                  type="textarea"
                  :rows="5"
                  :disabled="props.disabled || !fieldFor(parameter).present || fieldFor(parameter).isNull"
                  :placeholder="complexPlaceholder(parameter)"
                  @input="onTextChanged(parameter, $event)"
                />
                <el-input
                  v-else
                  v-model="fieldFor(parameter).value"
                  :type="isSensitiveParameter(parameter) ? 'password' : 'text'"
                  :inputmode="isNumericParameter(parameter) ? 'decimal' : 'text'"
                  :disabled="props.disabled || !fieldFor(parameter).present || fieldFor(parameter).isNull"
                  :placeholder="inputPlaceholder(parameter)"
                  autocomplete="off"
                  @input="onTextChanged(parameter, $event)"
                />
                <el-button
                  v-if="parameterContainsSensitive(parameter)"
                  text
                  size="small"
                  class="sensitive-toggle"
                  :disabled="props.disabled"
                  :aria-label="`隐藏 ${parameter.name} 的敏感值`"
                  @click="hideSensitive(parameter.name)"
                >隐藏</el-button>
              </template>
            </div>
          </div>
          <p v-if="parameter.description" class="input-field-hint">{{ parameter.description }}</p>
          <p v-if="isComplexParameter(parameter)" class="input-field-hint">对象、数组和 Map 使用字段级 JSON；无法安全保留的输入会留在完整 JSON 中。</p>
          <p v-if="fieldFor(parameter).error" class="input-field-error" role="alert">{{ fieldFor(parameter).error }}</p>
        </el-form-item>
      </div>
    </template>

    <template v-else>
      <div v-if="hasSensitiveParameters && !jsonSensitiveRevealed" class="json-sensitive-guard">
        <LiquidGlassNotice
          title="敏感字段已隐藏"
          description="下方只显示脱敏预览。若确需编辑完整 JSON，请显式显示敏感值；不要将输入复制到工单或聊天。"
          type="warning"
          source="backdrop"
        />
        <el-button type="primary" plain size="small" :disabled="props.disabled" @click="revealSensitiveJson">显示敏感值并编辑</el-button>
      </div>
      <div v-else-if="hasSensitiveParameters" class="json-sensitive-guard is-revealed">
        <LiquidGlassNotice
          title="正在显示敏感值"
          description="完成编辑后可重新隐藏。敏感输入不会被保存为最近调用记录。"
          type="warning"
          source="backdrop"
        />
        <el-button text size="small" :disabled="props.disabled" @click="hideSensitiveJson">重新隐藏敏感值</el-button>
      </div>
      <el-input
        v-model="jsonText"
        class="json-input"
        type="textarea"
        :rows="13"
        :disabled="props.disabled || (hasSensitiveParameters && !jsonSensitiveRevealed)"
        spellcheck="false"
        aria-label="JSON 调用输入"
        :aria-invalid="!!jsonError"
        :aria-describedby="jsonFeedbackId"
        @input="onJsonInput"
      />
      <p v-if="jsonError" :id="jsonFeedbackId" class="json-error" role="alert">{{ jsonError }}</p>
      <p v-else :id="jsonFeedbackId" class="json-hint">{{ props.httpJsonBody ? '对象、数组、字符串、数字和布尔按原值提交。文本请求体也用带引号的 JSON 字符串填写。' : 'JSON 顶层必须是对象；数字、布尔、数组、null 和对象会按当前原值提交。' }}</p>
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, useId, watch } from 'vue'
import type { ToolParameter } from '@/types/tool'
import {
  containsSensitiveParameter,
  explicitSampleInput,
  isArrayParameter,
  isBooleanParameter,
  isComplexParameter,
  isNumericParameter,
  isSensitiveParameter,
  maskInvocationInput,
  safeJson,
} from '../businessMethodInvocation'
import { LiquidGlassNotice } from '@/components/common/liquid-glass'

type EditorMode = 'form' | 'json'

interface FormField {
  present: boolean
  value: string
  isNull: boolean
  error: string
}

interface BusinessMethodInvocationDraftState {
  valid: boolean
  revision: number
  mode: EditorMode
  error?: string
}

const props = withDefaults(defineProps<{
  parameters: ToolParameter[]
  /** Named Console variant reuses this editor without a second form/JSON implementation. */
  title?: string
  /** Full owner declaration tree used only for safe display masking. */
  sourceParameters?: ToolParameter[]
  modelValue: unknown
  /** HTTP Console variant; business-method inputs retain their object-only contract. */
  httpJsonBody?: boolean
  httpBodySchema?: Record<string, unknown>
  jsonValueValidator?: (value: unknown) => string
  jsonOnly?: boolean
  singleDtoRootName?: string | null
  disabled?: boolean
}>(), {
  singleDtoRootName: null,
  title: '调用输入',
  sourceParameters: () => [],
  disabled: false,
  httpJsonBody: false,
  jsonOnly: false,
})
const emit = defineEmits<{
  'update:modelValue': [value: Record<string, unknown>]
  'http-body-change': [value: unknown]
  'dirty-change': [value: boolean]
  'draft-change': [state: BusinessMethodInvocationDraftState]
}>()

const mode = ref<EditorMode>(props.jsonOnly ? 'json' : 'form')
const fields = ref<Record<string, FormField>>({})
const draftValue = ref<unknown>({})
const jsonText = ref('{}')
const jsonError = ref('')
const formModeNotice = ref('')
const jsonSensitiveRevealed = ref(false)
/** Keeps an invalid revealed draft off-screen while its masked projection remains visible. */
const hiddenSensitiveJsonDraft = ref<string | null>(null)
const sensitiveVisibleFields = ref<Record<string, boolean>>({})
const revision = ref(0)
const jsonFeedbackId = useId()
const isValidDraft = ref(false)
const hasInvalidFormDraft = ref(false)
let appliedInputSignature = ''
let appliedParametersSignature = ''

const maskingParameters = computed(() => props.sourceParameters.length ? props.sourceParameters : props.parameters)
const singleDtoRootSensitive = computed(() => Boolean(
  props.singleDtoRootName
  && maskingParameters.value.some((parameter) => parameter.name === props.singleDtoRootName && isSensitiveParameter(parameter)),
))
const sensitiveName = /password|passwd|secret|token|credential|authorization|cookie|private[-_]?key|api[-_]?key/i
function httpSensitive(schema: Record<string, unknown> = {}, value?: unknown): boolean {
  if (schema.format === 'password' || schema.writeOnly === true) return true
  if (schema.items && typeof schema.items === 'object' && httpSensitive(schema.items as Record<string, unknown>)) return true
  if (Array.isArray(value)) return value.some(entry => httpSensitive((schema.items || {}) as Record<string, unknown>, entry))
  const properties = (schema.properties || {}) as Record<string, Record<string, unknown>>
  if (Object.entries(properties).some(([key, field]) => sensitiveName.test(key) || httpSensitive(field))) return true
  const object = asRecord(value)
  return !!object && Object.entries(object).some(([key, entry]) => sensitiveName.test(key)
    || httpSensitive(properties[key] || (typeof schema.additionalProperties === 'object' ? schema.additionalProperties as Record<string, unknown> : {}), entry))
}
function maskedHttpValue(value: unknown, schema: Record<string, unknown> = {}): unknown {
  if (schema.format === 'password' || schema.writeOnly === true) return '••••••'
  if (Array.isArray(value)) return value.map(entry => maskedHttpValue(entry, (schema.items || {}) as Record<string, unknown>))
  const object = asRecord(value)
  const properties = (schema.properties || {}) as Record<string, Record<string, unknown>>
  return object ? Object.fromEntries(Object.entries(object).map(([key, entry]) => [key, sensitiveName.test(key) ? '••••••'
    : maskedHttpValue(entry, properties[key] || (typeof schema.additionalProperties === 'object' ? schema.additionalProperties as Record<string, unknown> : {}))])) : value
}
const hasSensitiveParameters = computed(() => props.httpJsonBody
  ? httpSensitive(props.httpBodySchema, draftValue.value) : containsSensitiveParameter(maskingParameters.value))
const sampleInput = computed(() => explicitSampleInput({ parameters: props.parameters, singleDtoRootName: props.singleDtoRootName || null }))

function cloneRecord(value: Record<string, unknown>) {
  try {
    return JSON.parse(JSON.stringify(value)) as Record<string, unknown>
  } catch {
    return { ...value }
  }
}

function cloneInput(value: unknown): unknown {
  return value === undefined ? undefined : JSON.parse(JSON.stringify(value))
}

function signature(value: unknown) {
  try {
    return JSON.stringify(value) || ''
  } catch {
    return String(value)
  }
}

function hasOwn(value: Record<string, unknown>, key: string) {
  return Object.prototype.hasOwnProperty.call(value, key)
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null
}

function fieldValue(parameter: ToolParameter, value: unknown) {
  if (value === undefined || value === null) return ''
  if (isComplexParameter(parameter)) return safeJson(value)
  if (isBooleanParameter(parameter)) return value === true ? 'true' : value === false ? 'false' : String(value)
  return String(value)
}

function resolveFormPayload(value = draftValue.value) {
  const object = asRecord(value) || (props.httpJsonBody && value === undefined && !props.jsonOnly ? {} : null)
  if (!object) return { payload: null, wrapped: false, reason: '当前请求体不是对象，请继续使用 JSON 编辑。' }
  const rootName = props.singleDtoRootName
  if (rootName && hasOwn(object, rootName)) {
    const wrapped = asRecord(object[rootName])
    if (!wrapped) return { payload: null, wrapped: true, reason: `“${rootName}”当前不是对象，无法在表单中无损编辑。` }
    return { payload: wrapped, wrapped: true, reason: '' }
  }
  return { payload: object, wrapped: false, reason: '' }
}

function formCompatibility(value = draftValue.value) {
  const binding = resolveFormPayload(value)
  if (!binding.payload) return binding.reason
  for (const parameter of props.parameters) {
    if (!hasOwn(binding.payload, parameter.name)) continue
    const field = binding.payload[parameter.name]
    if (field === null) continue
    if (isArrayParameter(parameter) && !Array.isArray(field)) return `“${parameter.name}”当前不是数组，无法在表单中无损编辑。`
    if (isComplexParameter(parameter) && !isArrayParameter(parameter) && !asRecord(field)) return `“${parameter.name}”当前不是对象，无法在表单中无损编辑。`
    if (isBooleanParameter(parameter) && typeof field !== 'boolean') return `“${parameter.name}”当前不是布尔值，无法在表单中无损编辑。`
    if (isNumericParameter(parameter) && (typeof field !== 'number' || !Number.isFinite(field))) return `“${parameter.name}”当前不是有限数字，无法在表单中无损编辑。`
    if (!isComplexParameter(parameter) && !isBooleanParameter(parameter) && !isNumericParameter(parameter) && typeof field !== 'string') {
      return `“${parameter.name}”当前不是文本，无法在表单中无损编辑。`
    }
  }
  return ''
}

function resetFields() {
  const binding = resolveFormPayload()
  const payload = binding.payload || {}
  const next: Record<string, FormField> = {}
  for (const parameter of props.parameters) {
    const present = hasOwn(payload, parameter.name)
    const value = payload[parameter.name]
    next[parameter.name] = {
      present: parameter.required || present,
      value: fieldValue(parameter, value),
      isNull: present && value === null,
      error: '',
    }
  }
  fields.value = next
}

function syncJsonText() {
  jsonText.value = (hasSensitiveParameters.value && !jsonSensitiveRevealed.value
    ? safeJson(props.httpJsonBody ? maskedHttpValue(draftValue.value, props.httpBodySchema)
      : maskInvocationInput(asRecord(draftValue.value) || {}, maskingParameters.value))
    : safeJson(draftValue.value)) ?? ''
}

function fieldFor(parameter: ToolParameter) {
  if (!fields.value[parameter.name]) {
    fields.value[parameter.name] = { present: parameter.required, value: '', isNull: false, error: '' }
  }
  return fields.value[parameter.name]
}

function parameterLabel(parameter: ToolParameter) {
  return parameter.name
}

function parameterContainsSensitive(parameter: ToolParameter) {
  if (props.httpJsonBody) {
    const properties = (props.httpBodySchema?.properties || {}) as Record<string, Record<string, unknown>>
    return isSensitiveParameter(parameter) || httpSensitive(properties[parameter.name], resolveFormPayload().payload?.[parameter.name])
  }
  return singleDtoRootSensitive.value || containsSensitiveParameter([parameter])
}

function maskedFieldPreview(parameter: ToolParameter) {
  if (singleDtoRootSensitive.value) return '••••••'
  const binding = resolveFormPayload()
  const raw = binding.payload?.[parameter.name]
  if (props.httpJsonBody) {
    const properties = (props.httpBodySchema?.properties || {}) as Record<string, Record<string, unknown>>
    return isComplexParameter(parameter) && !isSensitiveParameter(parameter)
      ? safeJson(maskedHttpValue(raw, properties[parameter.name])) : '••••••'
  }
  const masked = maskInvocationInput({ [parameter.name]: raw }, [parameter])
  const projection = masked[parameter.name]
  return isComplexParameter(parameter) ? safeJson(projection) : '••••••'
}

function complexPlaceholder(parameter: ToolParameter) {
  return isArrayParameter(parameter) ? '[\n  ...\n]' : '{\n  ...\n}'
}

function inputPlaceholder(parameter: ToolParameter) {
  if (isNumericParameter(parameter)) return '输入数字'
  if (parameterContainsSensitive(parameter)) return '输入敏感值'
  return '输入值'
}

function parseComplex(parameter: ToolParameter, text: string): unknown | null {
  try {
    const parsed = JSON.parse(text)
    if (isArrayParameter(parameter) && !Array.isArray(parsed)) throw new Error('array')
    if (!isArrayParameter(parameter) && !asRecord(parsed)) throw new Error('object')
    return parsed
  } catch {
    fieldFor(parameter).error = isArrayParameter(parameter) ? '请输入合法 JSON 数组。' : '请输入合法 JSON 对象。'
    return null
  }
}

function normalizeInteger(parameter: ToolParameter) {
  return ['integer', 'int', 'long', 'short', 'byte', 'java.lang.integer', 'java.lang.long'].includes(String(parameter.type || '').trim().toLowerCase())
}

function formInput(showErrors: boolean): Record<string, unknown> | null {
  const binding = resolveFormPayload()
  if (!binding.payload) {
    formModeNotice.value = binding.reason
    return null
  }
  const result = cloneRecord(binding.payload)
  let valid = true
  for (const parameter of props.parameters) {
    const field = fieldFor(parameter)
    field.error = ''
    if (!field.present) {
      delete result[parameter.name]
      continue
    }
    if (field.isNull) {
      result[parameter.name] = null
      continue
    }
    if (isComplexParameter(parameter)) {
      if (!field.value.trim()) {
        if (showErrors) field.error = '请输入 JSON 值。'
        valid = false
        continue
      }
      const parsed = parseComplex(parameter, field.value)
      if (parsed === null) {
        valid = false
        continue
      }
      result[parameter.name] = parsed
      continue
    }
    if (isBooleanParameter(parameter)) {
      if (field.value !== 'true' && field.value !== 'false') {
        if (showErrors) field.error = '请选择 true 或 false。'
        valid = false
      } else result[parameter.name] = field.value === 'true'
      continue
    }
    if (isNumericParameter(parameter)) {
      const numeric = Number(field.value)
      if (!field.value.trim() || !Number.isFinite(numeric) || (normalizeInteger(parameter) && !Number.isInteger(numeric))) {
        if (showErrors) field.error = normalizeInteger(parameter) ? '请输入有限整数。' : '请输入有限数字。'
        valid = false
      } else result[parameter.name] = numeric
      continue
    }
    if (parameter.required && !field.value.trim()) {
      if (showErrors) field.error = '请填写该必填字段。'
      valid = false
      continue
    }
    result[parameter.name] = field.value
  }
  if (!valid) return null
  if (binding.wrapped && props.singleDtoRootName) return { ...asRecord(draftValue.value), [props.singleDtoRootName]: result }
  return result
}

function emitDraft(valid: boolean, dirty = false) {
  isValidDraft.value = valid
  if (dirty) {
    revision.value += 1
    emit('dirty-change', true)
  }
  emit('draft-change', { valid, revision: revision.value, mode: mode.value,
    error: valid ? '' : jsonError.value || formModeNotice.value })
}

function commitInput(next: unknown, dirty = false, notify = true) {
  const error = props.httpJsonBody ? props.jsonValueValidator?.(next) : ''
  if (error) {
    if (mode.value === 'json') jsonError.value = error
    else { formModeNotice.value = error; hasInvalidFormDraft.value = true }
    emitDraft(false, dirty)
    return false
  }
  draftValue.value = cloneInput(next)
  appliedInputSignature = signature(draftValue.value)
  if (props.httpJsonBody) emit('http-body-change', cloneInput(draftValue.value))
  else emit('update:modelValue', cloneInput(draftValue.value) as Record<string, unknown>)
  if (notify) emitDraft(true, dirty)
  else isValidDraft.value = true
  return true
}

function onFormChanged() {
  const next = formInput(true)
  if (next) {
    hasInvalidFormDraft.value = false
    formModeNotice.value = ''
    commitInput(next, true)
  } else {
    hasInvalidFormDraft.value = true
    emitDraft(false, true)
  }
}

function onTextChanged(parameter: ToolParameter, value: unknown) {
  const field = fieldFor(parameter)
  field.value = value === null || value === undefined ? '' : String(value)
  field.isNull = false
  onFormChanged()
}

function onSelectChanged(parameter: ToolParameter, value: unknown) {
  fieldFor(parameter).value = value === 'true' || value === 'false' ? value : ''
  fieldFor(parameter).isNull = false
  onFormChanged()
}

function onPresenceChanged(parameter: ToolParameter, value: unknown) {
  fieldFor(parameter).present = value === true
  onFormChanged()
}

function onNullChanged(parameter: ToolParameter, value: unknown) {
  fieldFor(parameter).isNull = value === true
  if (fieldFor(parameter).isNull) fieldFor(parameter).error = ''
  onFormChanged()
}

function changeMode(nextMode: EditorMode) {
  if (nextMode === 'form') {
    if (jsonError.value) {
      mode.value = 'json'
      formModeNotice.value = '当前 JSON 尚未修正；为保留原始草稿，请继续在 JSON 模式编辑。'
      emitDraft(false)
      return
    }
    const incompatibility = formCompatibility()
    if (incompatibility) {
      mode.value = 'json'
      formModeNotice.value = incompatibility
      emitDraft(isValidDraft.value)
      return
    }
    formModeNotice.value = ''
    mode.value = 'form'
    resetFields()
    emitDraft(Boolean(formInput(false)))
    return
  }
  if (hasInvalidFormDraft.value) {
    mode.value = 'form'
    formModeNotice.value = '当前表单有未修正的输入；为保留当前草稿，请先修正后再切换到 JSON。'
    emitDraft(false)
    return
  }
  formModeNotice.value = ''
  mode.value = 'json'
  syncJsonText()
  emitDraft(isValidDraft.value)
}

function onJsonInput() {
  if (hasSensitiveParameters.value && !jsonSensitiveRevealed.value) return
  try {
    const parsed = props.httpJsonBody && !jsonText.value.trim() ? undefined : JSON.parse(jsonText.value)
    if (!props.httpJsonBody && !asRecord(parsed)) throw new Error('object')
    jsonError.value = ''
    formModeNotice.value = ''
    hiddenSensitiveJsonDraft.value = null
    commitInput(parsed, true)
  } catch {
    jsonError.value = props.httpJsonBody ? '请输入合法的 JSON 请求体；修正前不会提交此前的输入。' : '请输入合法的 JSON 对象；修正前不会提交此前的输入。'
    emitDraft(false, true)
  }
}

function fillSample() {
  if (!sampleInput.value) return
  jsonSensitiveRevealed.value = false
  hiddenSensitiveJsonDraft.value = null
  sensitiveVisibleFields.value = {}
  formModeNotice.value = ''
  commitInput(sampleInput.value, true)
  if (mode.value === 'form') resetFields()
  else syncJsonText()
}

function revealSensitiveJson() {
  jsonSensitiveRevealed.value = true
  jsonText.value = hiddenSensitiveJsonDraft.value ?? safeJson(draftValue.value) ?? ''
}

function hideSensitiveJson() {
  hiddenSensitiveJsonDraft.value = jsonError.value ? jsonText.value : null
  jsonSensitiveRevealed.value = false
  syncJsonText()
}

function sensitiveVisible(name: string) {
  return sensitiveVisibleFields.value[name] === true
}

function showSensitive(name: string) {
  sensitiveVisibleFields.value = { ...sensitiveVisibleFields.value, [name]: true }
}

function hideSensitive(name: string) {
  sensitiveVisibleFields.value = { ...sensitiveVisibleFields.value, [name]: false }
}

function validate() {
  if (mode.value === 'json') {
    if (hasSensitiveParameters.value && !jsonSensitiveRevealed.value) {
      return { valid: isValidDraft.value, revision: revision.value, mode: mode.value }
    }
    try {
      const parsed = props.httpJsonBody && !jsonText.value.trim() ? undefined : JSON.parse(jsonText.value)
      if (!props.httpJsonBody && !asRecord(parsed)) throw new Error('object')
      jsonError.value = ''
      const valid = commitInput(parsed, false, false)
      return { valid, revision: revision.value, mode: mode.value }
    } catch {
      jsonError.value = props.httpJsonBody ? '请输入合法的 JSON 请求体；修正前不会提交此前的输入。' : '请输入合法的 JSON 对象；修正前不会提交此前的输入。'
      emitDraft(false)
      return { valid: false, revision: revision.value, mode: mode.value }
    }
  }
  const next = formInput(true)
  if (!next) {
    emitDraft(false)
    return { valid: false, revision: revision.value, mode: mode.value }
  }
  const valid = commitInput(next, false, false)
  return { valid, revision: revision.value, mode: mode.value }
}

function syncIncoming(value: unknown, force = false) {
  const inputSignature = signature(value)
  if (!force && inputSignature === appliedInputSignature) return
  appliedInputSignature = inputSignature
  draftValue.value = cloneInput(value)
  jsonError.value = ''
  hiddenSensitiveJsonDraft.value = null
  hasInvalidFormDraft.value = false
  formModeNotice.value = ''
  const incompatibility = formCompatibility()
  if (props.jsonOnly || (mode.value === 'form' && incompatibility)) {
    mode.value = 'json'
    formModeNotice.value = props.jsonOnly ? '' : incompatibility
  }
  if (mode.value === 'form') {
    resetFields()
    emitDraft(Boolean(formInput(false)))
  } else {
    syncJsonText()
    emitDraft(true)
  }
}

watch(
  [() => props.modelValue, () => props.parameters, () => props.sourceParameters, () => props.singleDtoRootName,
    () => props.httpJsonBody, () => props.httpBodySchema, () => props.jsonOnly],
  () => {
    const parametersSignature = signature([props.parameters, props.sourceParameters, props.singleDtoRootName,
      props.httpJsonBody, props.httpBodySchema, props.jsonOnly])
    const changedParameters = parametersSignature !== appliedParametersSignature
    if (changedParameters) {
      appliedParametersSignature = parametersSignature
      jsonSensitiveRevealed.value = false
      sensitiveVisibleFields.value = {}
    }
    syncIncoming(props.modelValue, changedParameters)
  },
  { deep: true, immediate: true },
)

watch(hasSensitiveParameters, sensitive => {
  if (props.httpJsonBody && sensitive && !jsonSensitiveRevealed.value) syncJsonText()
})

defineExpose({ validate })
</script>

<style scoped lang="scss">
.invocation-input-editor { padding: 18px 0 8px; }
.input-editor-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 14px; }
.input-editor-header h3 { margin: 0; color: var(--text-primary); font-size: 14px; }
.input-editor-header p, .input-field-hint, .json-hint { margin: 6px 0 0; color: var(--text-secondary); font-size: 12px; line-height: 1.7; }
.input-field-error { margin: 6px 0 0; color: var(--status-danger); font-size: 12px; line-height: 1.7; }
.input-editor-actions { display: flex; align-items: center; gap: 8px; flex-shrink: 0; }
.input-editor-note { margin-bottom: 14px; }
.input-form { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 16px; }
.input-form :deep(.el-form-item) { margin-bottom: 16px; }
.input-field-label { display: inline-flex; align-items: center; gap: 7px; min-width: 0; overflow-wrap: anywhere; color: var(--text-primary); }
.input-field-label small { color: var(--text-muted); font: 11px/1.4 ui-monospace, Consolas, monospace; }
.sensitive-badge { padding: 1px 5px; border-radius: var(--radius-sm); color: var(--status-warning); background: color-mix(in srgb, var(--status-warning) 10%, transparent); font-size: 10px; }
.input-field-control { display: flex; align-items: flex-start; gap: 8px; width: 100%; }
.input-field-present { flex: 0 0 auto; margin-top: 7px; color: var(--text-secondary); font-size: 12px; }
.input-field-value { display: flex; align-items: flex-start; gap: 6px; flex: 1; min-width: 0; }
.input-field-value > :deep(.el-input), .input-field-value > :deep(.el-select) { flex: 1; min-width: 0; }
.input-field-null { flex: 0 0 auto; margin-top: 7px; color: var(--text-secondary); font-size: 12px; white-space: nowrap; }
.sensitive-toggle { flex: 0 0 auto; margin-top: 2px; }
.sensitive-field-preview { flex: 1; min-width: 0; max-height: 120px; padding: 8px 10px; overflow: auto; border: 1px solid var(--border-divider); border-radius: var(--radius-sm); background: var(--surface-solid-control); color: var(--text-secondary); font: 12px/1.6 ui-monospace, Consolas, monospace; white-space: pre-wrap; overflow-wrap: anywhere; }
.input-editor-empty { padding: 18px; border: 1px dashed var(--border-readable); border-radius: var(--radius-md); color: var(--text-secondary); font-size: 13px; }
.json-sensitive-guard { display: flex; align-items: flex-start; gap: 12px; margin-bottom: 12px; }
.json-sensitive-guard > .el-alert { flex: 1; }
.json-sensitive-guard > .el-button { flex: 0 0 auto; margin-top: 4px; }
.json-input :deep(textarea) { font: 12px/1.7 ui-monospace, Consolas, monospace; }
.json-error { margin: 8px 0 0; color: var(--status-danger); font-size: 12px; line-height: 1.7; }
@media (max-width: 720px) {
  .input-editor-header { align-items: stretch; flex-direction: column; }
  .input-editor-actions { justify-content: space-between; }
  .input-form { grid-template-columns: 1fr; }
  .input-field-value { flex-wrap: wrap; }
  .input-field-null { flex-basis: 100%; margin-top: 0; }
  .input-field-value > :deep(.el-input), .input-field-value > :deep(.el-select) { flex: 1 1 100%; }
  .json-sensitive-guard { flex-direction: column; }
  .json-sensitive-guard > .el-button { margin-top: 0; }
}
</style>
