<template>
  <div class="node-specific-panel llm-panel">
    <ConfigSection title="模型">
      <el-form-item label="模型实例">
        <el-select
          v-model="config.modelInstanceId"
          filterable
          clearable
          placeholder="使用 Workflow 默认模型"
          :loading="modelOptionsLoading"
          @visible-change="handleModelSelectVisible"
        >
          <el-option
            v-for="item in modelOptions"
            :key="item.id"
            :label="`${item.name} / ${item.modelName}`"
            :value="item.id"
          />
          <template #empty>
            <ModelSelectEmptyState
              model-type="LLM"
              :option-count="modelOptions.length"
              :loading="modelOptionsLoading"
              :load-error="modelOptionsLoadError"
              @retry="emit('reloadModelOptions')"
            />
          </template>
        </el-select>
      </el-form-item>
    </ConfigSection>

    <ConfigSection title="提示词" description="按消息顺序发送给模型">
      <div class="prompt-toolbar">
        <el-segmented
          v-model="config.promptTemplateMode"
          :options="promptModeOptions"
          aria-label="提示词编辑模式"
          @change="handlePromptModeChange"
        />
        <div class="prompt-actions">
          <el-popover trigger="click" placement="bottom-end" :width="300" :disabled="formDisabled">
            <template #reference><el-button text :icon="Coin" :disabled="formDisabled">插入变量</el-button></template>
            <div class="prompt-variable-picker">
              <el-input
                v-model="variableKeyword"
                placeholder="搜索变量名称或路径"
                aria-label="搜索提示词变量"
                clearable
              />
              <div class="prompt-variable-list">
                <div v-for="group in groupedVariableOptions" :key="group.group" class="prompt-variable-group">
                  <span>{{ group.group }}</span>
                  <el-button
                    v-for="item in group.options"
                    :key="item.value"
                    text
                    :disabled="formDisabled"
                    @click="insertVariable(item.value)"
                  >
                    <span>{{ item.label }}</span
                    ><code>{{ item.value }}</code>
                  </el-button>
                </div>
                <p v-if="!groupedVariableOptions.length">没有匹配的变量</p>
              </div>
            </div>
          </el-popover>
          <el-popover trigger="click" placement="left-start" :width="360">
            <template #reference><el-button text :icon="View">预览</el-button></template>
            <div class="prompt-preview">
              <p class="preview-hint">变量位置以占位符显示，实际值在运行时替换。</p>
              <div v-for="message in renderedPreview" :key="message.id" class="preview-message">
                <strong>{{ message.role.toUpperCase() }}</strong>
                <pre>{{ message.content || '（空消息）' }}</pre>
              </div>
            </div>
          </el-popover>
        </div>
      </div>
      <template v-if="config.promptTemplateMode === 'simple'">
        <el-form-item label="系统提示词">
          <el-input
            v-model="config.systemPrompt"
            type="textarea"
            :rows="4"
            placeholder="定义角色、任务和回答约束"
            @focus="rememberPromptTarget('simple-system', $event)"
            @input="syncSimpleMessages"
          />
        </el-form-item>
        <el-form-item label="用户提示词">
          <el-input
            v-model="config.userPrompt"
            type="textarea"
            :rows="3"
            placeholder="输入任务，可通过「插入变量」引用上游结果"
            @focus="rememberPromptTarget('simple-user', $event)"
            @input="syncSimpleMessages"
          />
        </el-form-item>
      </template>
      <template v-else>
        <div class="message-list">
          <div
            v-for="(message, index) in activeMessages"
            :key="message.id"
            class="message-card"
            :class="{ 'is-disabled': message.enabled === false }"
          >
            <div class="message-head">
              <span class="message-index">{{ index + 1 }}</span>
              <el-select
                v-model="message.role"
                size="small"
                class="role-select"
                aria-label="消息角色"
                @change="syncPromptShortcuts"
              >
                <el-option label="系统 · System" value="system" /><el-option
                  label="用户 · User"
                  value="user"
                /><el-option label="助手 · Assistant" value="assistant" />
              </el-select>
              <div class="message-actions">
                <el-switch
                  v-model="message.enabled"
                  size="small"
                  aria-label="启用消息"
                  title="启用消息"
                  @change="syncPromptShortcuts"
                />
                <el-button
                  :icon="Top"
                  text
                  :disabled="index === 0"
                  aria-label="上移消息"
                  title="上移"
                  @click="moveMessage(index, -1)"
                />
                <el-button
                  :icon="Bottom"
                  text
                  :disabled="index === activeMessages.length - 1"
                  aria-label="下移消息"
                  title="下移"
                  @click="moveMessage(index, 1)"
                />
                <el-button
                  :icon="Delete"
                  text
                  :disabled="activeMessages.length <= 1"
                  aria-label="删除消息"
                  title="删除"
                  @click="removeMessage(index)"
                />
              </div>
            </div>
            <el-input
              v-model="message.content"
              type="textarea"
              :rows="message.role === 'system' ? 4 : 3"
              resize="vertical"
              :aria-label="`第 ${index + 1} 条消息内容`"
              placeholder="输入提示词，使用上方「插入变量」引用上下文"
              @focus="rememberPromptTarget(message.id, $event)"
              @input="syncPromptShortcuts"
            />
          </div>
        </div>
        <el-dropdown trigger="click" :disabled="formDisabled" @command="addMessage">
          <el-button class="add-message-button" plain :icon="Plus" :disabled="formDisabled">添加消息</el-button>
          <template #dropdown
            ><el-dropdown-menu
              ><el-dropdown-item command="system">系统消息</el-dropdown-item
              ><el-dropdown-item command="user">用户消息</el-dropdown-item
              ><el-dropdown-item command="assistant">助手消息</el-dropdown-item></el-dropdown-menu
            ></template
          >
        </el-dropdown>
        <p v-if="!simpleModeAvailable" class="panel-hint">包含多条、助手或停用的消息，请使用消息编排保留完整上下文。</p>
      </template>
      <ConfigSection
        title="附加上下文"
        description="运行时一并提供给模型的变量"
        :count="config.contextVariables?.length || 0"
        collapsible
      >
        <el-select
          v-model="config.contextVariables"
          multiple
          filterable
          allow-create
          default-first-option
          :filter-method="filterVariableOptions"
          placeholder="选择附加变量"
          popper-class="studio-variable-select"
          @visible-change="handleVariableVisibleChange"
        >
          <el-option-group v-for="group in groupedVariableOptions" :key="group.group" :label="group.group"
            ><el-option v-for="item in group.options" :key="item.value" :label="item.label" :value="item.value"
          /></el-option-group>
        </el-select>
      </ConfigSection>
    </ConfigSection>

    <ConfigSection title="输出" description="选择回答格式，按需声明结构化字段">
      <el-form-item label="输出格式"
        ><el-segmented
          v-model="config.outputFormat"
          :options="[
            { label: '文本', value: 'text' },
            { label: 'JSON', value: 'json' },
          ]"
          @change="handleOutputFormatChange"
      /></el-form-item>
      <el-form-item label="结构化输出"
        ><el-switch v-model="config.structuredOutput" active-text="按字段约束输出" @change="handleStructuredChange"
      /></el-form-item>
      <template v-if="config.structuredOutput">
        <el-form-item label="字段校验"
          ><el-switch v-model="config.strictJsonSchema" active-text="严格校验"
        /></el-form-item>
        <div class="field-table-head">
          <strong>输出字段</strong
          ><el-button
            size="small"
            text
            :icon="Plus"
            @click="addField(config.outputSchema || (config.outputSchema = []))"
            >添加字段</el-button
          >
        </div>
        <div v-for="(field, index) in config.outputSchema" :key="index" class="field-row llm-schema-row">
          <el-input v-model="field.name" placeholder="字段名" aria-label="输出字段名" />
          <el-select v-model="field.type" aria-label="输出字段类型"
            ><el-option
              v-for="type in ['string', 'number', 'integer', 'boolean', 'object', 'array']"
              :key="type"
              :label="type"
              :value="type"
          /></el-select>
          <el-input v-model="field.description" placeholder="字段说明" /><el-input
            v-model="field.defaultValue"
            placeholder="默认值"
          />
          <el-switch v-model="field.required" active-text="必填" /><el-button
            text
            type="danger"
            @click="config.outputSchema?.splice(index, 1)"
            >删除字段</el-button
          >
        </div>
      </template>
    </ConfigSection>

    <ConfigSection
      title="视觉输入"
      :description="config.visionEnabled ? '已启用图片输入' : '按需接收图片或文件变量'"
      collapsible
      :default-open="config.visionEnabled"
    >
      <el-form-item label="图片输入"><el-switch v-model="config.visionEnabled" active-text="启用" /></el-form-item>
      <el-form-item v-if="config.visionEnabled" label="图片变量">
        <el-select
          v-model="config.visionInputs"
          multiple
          filterable
          allow-create
          default-first-option
          :filter-method="filterVariableOptions"
          placeholder="选择图片或文件变量"
          @visible-change="handleVariableVisibleChange"
          ><el-option-group v-for="group in groupedVariableOptions" :key="group.group" :label="group.group"
            ><el-option
              v-for="item in group.options"
              :key="item.value"
              :label="item.label"
              :value="item.value" /></el-option-group
        ></el-select>
      </el-form-item>
    </ConfigSection>
    <ConfigSection
      title="模型参数"
      description="温度、最大输出长度等调用参数"
      collapsible
      :default-open="Object.keys(config.modelParams || {}).length > 0"
    >
      <el-input
        :model-value="formatMap(paramsAsText)"
        type="textarea"
        :rows="4"
        aria-label="模型参数"
        placeholder="temperature = 0.2&#10;maxTokens = 1024&#10;topP = 0.8"
        @update:model-value="updateParams"
      />
    </ConfigSection>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, shallowRef } from 'vue'
import { useFormDisabled } from 'element-plus'
import ConfigSection from './ConfigSection.vue'
import { Bottom, Coin, Delete, Plus, Top, View } from '@element-plus/icons-vue'
import type { CanvasNodeData, LlmNodeConfig, LlmPromptMessage, StudioVariableOption } from '@/types/studio'
import type { ModelInstance } from '@/types/model'
import ModelSelectEmptyState from '@/components/model/ModelSelectEmptyState.vue'
import { addField, formatMap, parseMap } from './panelUtils'

const props = defineProps<{
  data: CanvasNodeData
  modelOptions: ModelInstance[]
  modelOptionsLoading: boolean
  modelOptionsLoadError: boolean
  variableOptions: Array<string | StudioVariableOption>
}>()

const emit = defineEmits<{
  reloadModelOptions: []
}>()

const formDisabled = useFormDisabled()
const simpleModeAvailable = computed(() => activeMessages.value.length <= 2
  && activeMessages.value.every((message) => ['system', 'user'].includes(message.role) && message.enabled !== false)
  && new Set(activeMessages.value.map((message) => message.role)).size === activeMessages.value.length)
const promptModeOptions = computed(() => [
  { label: '消息编排', value: 'messages' },
  { label: '简洁', value: 'simple', disabled: !simpleModeAvailable.value },
])
const focusedMessageId = ref('')
const focusedTextarea = shallowRef<HTMLTextAreaElement | null>(null)
function rememberPromptTarget(id: string, event: FocusEvent) {
  focusedMessageId.value = id
  focusedTextarea.value = event.target as HTMLTextAreaElement
}
function handlePromptModeChange() {
  focusedMessageId.value = ''
  focusedTextarea.value = null
  if (config.value.promptTemplateMode === 'simple') syncPromptShortcuts()
}
const variableKeyword = ref('')

const fallbackVariableOptions: StudioVariableOption[] = [
  { value: 'params', label: '用户输入 · 全部参数', group: '用户输入', description: '用户输入节点写入的 params 对象' },
  { value: 'params.question', label: '用户输入 · 用户问题', group: '用户输入', description: '用户输入节点的 question 字段' },
  { value: 'sys.userId', label: '系统变量 · 当前用户 ID', group: '系统变量', description: '运行上下文中的用户标识' },
  { value: 'sys.tenantId', label: '系统变量 · 租户 ID', group: '系统变量', description: '运行上下文中的租户标识' },
  { value: 'sys.roles', label: '系统变量 · 用户角色', group: '系统变量', description: '当前用户角色列表' },
  { value: 'input', label: '运行态 · 原始输入消息', group: '运行态变量', description: '本次运行的原始消息' },
  { value: 'answer', label: '运行态 · 最终回答', group: '运行态变量', description: '当前已生成的 answer' },
  { value: 'lastOutput', label: '运行态 · 上一步输出', group: '运行态变量', description: '便捷变量，适合快速串联原型' },
  { value: 'lastRoute', label: '运行态 · 命中分支', group: '运行态变量', description: '条件或意图分类节点最近一次路由' },
]

const config = computed<LlmNodeConfig>(() => {
  props.data.llmConfig ||= defaultConfig()
  const value = props.data.llmConfig
  value.messages ||= defaultMessages(value.systemPrompt || '', value.userPrompt || '{{ input }}')
  value.contextVariables ||= ['input', 'lastOutput']
  value.modelParams ||= {}
  value.outputFormat ||= 'text'
  value.structuredOutput ??= value.outputFormat === 'json'
  value.strictJsonSchema ??= true
  value.outputSchema ||= []
  value.visionEnabled ??= false
  value.visionInputs ||= []
  value.promptTemplateMode ||= 'messages'
  return value
})

const activeMessages = computed(() => config.value.messages || [])

function handleModelSelectVisible(visible: boolean) {
  if (visible) emit('reloadModelOptions')
}

const paramsAsText = computed(() => {
  const out: Record<string, string> = {}
  Object.entries(config.value.modelParams || {}).forEach(([key, value]) => {
    out[key] = String(value)
  })
  return out
})

const renderedPreview = computed(() => activeMessages.value
  .filter((message) => message.enabled !== false)
  .map((message) => ({
    ...message,
    content: renderPreview(message.content),
  })))

const normalizedVariableOptions = computed<StudioVariableOption[]>(() => {
  const map = new Map<string, StudioVariableOption>()
  for (const item of fallbackVariableOptions) map.set(item.value, item)
  for (const item of props.variableOptions) {
    const option = normalizeVariableOption(item)
    if (option.value) map.set(option.value, option)
  }
  return Array.from(map.values())
})

const groupedVariableOptions = computed(() => {
  const keyword = variableKeyword.value.trim().toLowerCase()
  const filtered = normalizedVariableOptions.value.filter((item) => {
    if (!keyword) return true
    return [item.label, item.value, item.group, item.description, item.source]
      .filter(Boolean)
      .join(' ')
      .toLowerCase()
      .includes(keyword)
  })
  const groups = ['用户输入', '系统变量', '节点输出', '运行态变量', '高级表达式']
  return groups
    .map((group) => ({
      group,
      options: filtered.filter((item) => item.group === group),
    }))
    .filter((group) => group.options.length)
})

function filterVariableOptions(keyword: string) {
  variableKeyword.value = keyword
}

function handleVariableVisibleChange(visible: boolean) {
  if (!visible) variableKeyword.value = ''
}

function normalizeVariableOption(item: string | StudioVariableOption): StudioVariableOption {
  if (typeof item !== 'string') return item
  if (item.startsWith('sys.')) {
    return { value: item, label: systemVariableLabel(item), group: '系统变量', description: item }
  }
  if (item.startsWith('nodeOutput.')) {
    return {
      value: item,
      label: `节点输出 · ${item.replace('nodeOutput.', '')}`,
      group: '节点输出',
      description: item,
    }
  }
  if (item.startsWith('var.')) {
    return {
      value: item,
      label: `业务别名 · ${item.replace('var.', '')}`,
      group: '节点输出',
      description: item,
    }
  }
  if (['input', 'answer', 'lastOutput', 'lastRoute', 'lastSuccess', 'lastError'].includes(item)) {
    return { value: item, label: runtimeVariableLabel(item), group: '运行态变量', description: item }
  }
  if (item.startsWith('params')) {
    return {
      value: item,
      label: item === 'params' ? '用户输入 · 全部参数' : `用户输入 · ${item.replace('params.', '')}`,
      group: '用户输入',
      description: item,
    }
  }
  return { value: item, label: `表达式 · ${item}`, group: '高级表达式', description: item }
}

function systemVariableLabel(value: string) {
  const labels: Record<string, string> = {
    'sys.userId': '系统变量 · 当前用户 ID',
    'sys.tenantId': '系统变量 · 租户 ID',
    'sys.roles': '系统变量 · 用户角色',
  }
  return labels[value] || `系统变量 · ${value.replace('sys.', '')}`
}

function runtimeVariableLabel(value: string) {
  const labels: Record<string, string> = {
    input: '运行态 · 原始输入消息',
    answer: '运行态 · 最终回答',
    lastOutput: '运行态 · 上一步输出',
    lastRoute: '运行态 · 命中分支',
    lastSuccess: '运行态 · 上一步是否成功',
    lastError: '运行态 · 上一步错误',
  }
  return labels[value] || `运行态 · ${value}`
}

function defaultConfig(): LlmNodeConfig {
  return {
    modelInstanceId: '',
    systemPrompt: '',
    userPrompt: '{{ input }}',
    messages: defaultMessages('', '{{ input }}'),
    contextVariables: ['input', 'lastOutput'],
    modelParams: {},
    outputFormat: 'text',
    structuredOutput: false,
    strictJsonSchema: true,
    outputSchema: [],
    visionEnabled: false,
    visionInputs: [],
    promptTemplateMode: 'messages',
  }
}

function defaultMessages(systemPrompt: string, userPrompt: string): LlmPromptMessage[] {
  return [
    { id: 'system', role: 'system', content: systemPrompt, templateEngine: 'mustache', enabled: true },
    { id: 'user', role: 'user', content: userPrompt, templateEngine: 'mustache', enabled: true },
  ]
}

function addMessage(role: LlmPromptMessage['role']) {
  const message: LlmPromptMessage = {
    id: `msg-${Date.now()}-${Math.random().toString(16).slice(2)}`,
    role,
    content: role === 'user' ? '{{ input }}' : '',
    templateEngine: 'mustache',
    enabled: true,
  }
  config.value.messages = [...activeMessages.value, message]
  focusedMessageId.value = message.id
  focusedTextarea.value = null
  syncPromptShortcuts()
}

function removeMessage(index: number) {
  config.value.messages = activeMessages.value.filter((_, idx) => idx !== index)
  syncPromptShortcuts()
}

function moveMessage(index: number, delta: number) {
  const next = [...activeMessages.value]
  const target = index + delta
  if (target < 0 || target >= next.length) return
  const [item] = next.splice(index, 1)
  next.splice(target, 0, item)
  config.value.messages = next
  syncPromptShortcuts()
}

async function insertVariable(name: string) {
  if (formDisabled.value) return
  const token = `{{ ${name} }}`
  const simple = config.value.promptTemplateMode === 'simple'
  const target = activeMessages.value.find((message) => message.id === focusedMessageId.value)
    || [...activeMessages.value].reverse().find((message) => message.role === 'user')
    || activeMessages.value[activeMessages.value.length - 1]
  const system = simple && focusedMessageId.value === 'simple-system'
  const content = simple ? (system ? config.value.systemPrompt || '' : config.value.userPrompt || '') : target?.content || ''
  const textarea = focusedTextarea.value?.isConnected ? focusedTextarea.value : null
  const start = textarea?.selectionStart ?? content.length
  const end = textarea?.selectionEnd ?? content.length
  const next = content.slice(0, start) + token + content.slice(end)
  if (simple) {
    if (system) config.value.systemPrompt = next
    else config.value.userPrompt = next
    syncSimpleMessages()
  } else if (target) {
    target.content = next
    syncPromptShortcuts()
  }
  if (!config.value.contextVariables?.includes(name)) {
    config.value.contextVariables = [...(config.value.contextVariables || []), name]
  }
  await nextTick()
  textarea?.focus()
  textarea?.setSelectionRange(start + token.length, start + token.length)
}

function syncPromptShortcuts() {
  const system = activeMessages.value.find((message) => message.role === 'system' && message.enabled !== false)
  const user = [...activeMessages.value].reverse().find((message) => message.role === 'user' && message.enabled !== false)
  config.value.systemPrompt = system?.content || ''
  config.value.userPrompt = user?.content || '{{ input }}'
}

function syncSimpleMessages() {
  config.value.messages = defaultMessages(config.value.systemPrompt || '', config.value.userPrompt || '{{ input }}')
}

function handleOutputFormatChange() {
  config.value.structuredOutput = config.value.outputFormat === 'json'
}

function handleStructuredChange() {
  config.value.outputFormat = config.value.structuredOutput ? 'json' : 'text'
}

function renderPreview(text: string) {
  return (text || '').replace(/\{\{\s*([^}]+?)\s*}}/g, (_, key) => {
    const name = String(key).trim()
    return `<${name}>`
  })
}

function updateParams(text: string) {
  const parsed = parseMap(text)
  const params: Record<string, string | number | boolean> = {}
  Object.entries(parsed).forEach(([key, value]) => {
    if (value === 'true' || value === 'false') params[key] = value === 'true'
    else if (value !== '' && Number.isFinite(Number(value))) params[key] = Number(value)
    else params[key] = value
  })
  config.value.modelParams = params
}
</script>

<style scoped lang="scss" src="./llm-panel.scss"></style>
<style lang="scss">
.prompt-variable-picker { display: grid; gap: 10px; }
.prompt-variable-list { max-height: 300px; overflow-y: auto; }
.prompt-variable-group { display: grid; gap: 3px; margin-bottom: 10px; }
.prompt-variable-group > span { padding: 4px 6px; color: var(--text-muted); font-size: 11px; }
.prompt-variable-group .el-button { height: auto; padding: 7px 8px; margin: 0; justify-content: flex-start; }
.prompt-variable-group .el-button > span { display: grid; gap: 3px; min-width: 0; text-align: left; }
.prompt-variable-group .el-button span span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--text-primary); font-size: 12px; }
.prompt-variable-group code { color: var(--brand-primary); font: 11px/1.5 Consolas, monospace; }
.prompt-preview { max-height: 420px; overflow-y: auto; }
.preview-hint { margin: 0 0 12px; color: var(--text-muted); font-size: 12px; }
.preview-message { padding: 10px 0; border-top: 1px solid var(--border-divider); }
.preview-message strong { color: var(--brand-primary); font: 11px/1.5 Consolas, monospace; }
.preview-message pre { margin: 6px 0 0; color: var(--text-primary); font-size: 12px; line-height: 1.65; white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
