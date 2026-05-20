<template>
  <div class="node-specific-panel llm-panel">
    <div class="llm-section-head">
      <div>
        <strong>模型与提示词</strong>
        <span>配置本节点调用的模型、消息上下文和变量引用</span>
      </div>
      <el-tag size="small" effect="plain">LLM</el-tag>
    </div>

    <section class="llm-card model-card">
      <el-form-item label="模型实例">
        <el-select v-model="config.modelInstanceId" filterable placeholder="选择模型实例" style="width: 100%">
          <el-option v-for="item in modelOptions" :key="item.id" :label="`${item.name} / ${item.modelName}`" :value="item.id" />
        </el-select>
      </el-form-item>
    </section>

    <section class="llm-card prompt-card">
      <div class="llm-card-title">
        <div>
          <strong>Prompt 消息</strong>
          <span>{{ activeMessages.length }} 条消息 · 支持变量模板</span>
        </div>
        <div class="llm-toolbar">
          <el-segmented v-model="config.promptTemplateMode" :options="promptModeOptions" />
          <el-button size="small" type="primary" :icon="Plus" @click="addMessage('user')">消息</el-button>
        </div>
      </div>
    <template v-if="config.promptTemplateMode === 'simple'">
      <el-form-item label="系统提示词">
        <el-input v-model="config.systemPrompt" type="textarea" :rows="5" placeholder="节点级 system prompt；为空时运行时回退 Agent 配置" @change="syncSimpleMessages" />
      </el-form-item>
      <el-form-item label="用户提示词">
        <el-input v-model="config.userPrompt" type="textarea" :rows="5" placeholder="例如：请基于 {{ input }} 和 {{ knowledge_output }} 回答" @change="syncSimpleMessages" />
      </el-form-item>
    </template>

    <template v-else>
      <div class="message-list">
        <div v-for="(message, index) in activeMessages" :key="message.id" class="message-card">
          <div class="message-head">
            <el-select v-model="message.role" size="small" class="role-select">
              <el-option label="SYSTEM" value="system" />
              <el-option label="USER" value="user" />
              <el-option label="ASSISTANT" value="assistant" />
            </el-select>
            <span class="template-pill">变量模板</span>
            <el-switch v-model="message.enabled" size="small" />
            <el-button :icon="Top" size="small" circle :disabled="index === 0" @click="moveMessage(index, -1)" />
            <el-button :icon="Bottom" size="small" circle :disabled="index === activeMessages.length - 1" @click="moveMessage(index, 1)" />
            <el-button :icon="Delete" size="small" circle type="danger" :disabled="activeMessages.length <= 1" @click="removeMessage(index)" />
          </div>
          <el-input v-model="message.content" type="textarea" :rows="5" resize="vertical" placeholder="输入提示词，可引用 {{ input }}、{{ lastOutput }} 或上游节点输出" @focus="focusedMessageId = message.id" @change="syncLegacyPrompts" />
        </div>
      </div>
      <div class="quick-add-row">
        <el-button size="small" plain @click="addMessage('system')">添加 System</el-button>
        <el-button size="small" plain @click="addMessage('user')">添加 User</el-button>
        <el-button size="small" plain @click="addMessage('assistant')">添加 Assistant</el-button>
      </div>
    </template>
    </section>

    <section class="llm-card utility-card">
      <el-form-item label="变量引用">
        <div class="variable-picker">
          <el-select v-model="config.contextVariables" multiple filterable allow-create default-first-option placeholder="声明本节点会使用的变量" style="width: 100%">
            <el-option v-for="item in variableOptions" :key="item" :label="item" :value="item" />
          </el-select>
          <div class="variable-chips">
            <el-button v-for="item in variableOptions" :key="item" size="small" @click="insertVariable(item)">
              {{ item }}
            </el-button>
          </div>
        </div>
      </el-form-item>

      <el-collapse class="llm-collapse">
        <el-collapse-item name="preview">
          <template #title>提示词预览</template>
          <div class="prompt-preview">
            <div v-for="message in renderedPreview" :key="message.id" class="preview-message">
              <strong>{{ message.role.toUpperCase() }}</strong>
              <pre>{{ message.content }}</pre>
            </div>
          </div>
        </el-collapse-item>
      </el-collapse>
    </section>

    <div class="llm-section-head compact">
      <div>
        <strong>输出与治理</strong>
        <span>约束模型输出格式，配置多模态输入和运行参数</span>
      </div>
    </div>

    <section class="llm-card output-card">
      <el-form-item label="输出格式">
        <el-segmented v-model="config.outputFormat" :options="['text', 'json']" @change="handleOutputFormatChange" />
      </el-form-item>
      <el-form-item label="结构化输出">
        <el-switch v-model="config.structuredOutput" active-text="启用" inactive-text="关闭" @change="handleStructuredChange" />
        <el-switch v-if="config.structuredOutput" v-model="config.strictJsonSchema" class="strict-switch" active-text="严格校验" inactive-text="仅解析" />
      </el-form-item>
      <template v-if="config.structuredOutput">
        <div class="field-table-head">
          <strong>输出变量</strong>
          <el-button size="small" type="primary" plain @click="addField(config.outputSchema || (config.outputSchema = []))">添加字段</el-button>
        </div>
        <div v-for="(field, index) in config.outputSchema" :key="index" class="field-row schema-row llm-schema-row">
          <el-input v-model="field.name" placeholder="字段名" />
          <el-select v-model="field.type" style="width: 110px">
            <el-option label="string" value="string" />
            <el-option label="number" value="number" />
            <el-option label="integer" value="integer" />
            <el-option label="boolean" value="boolean" />
            <el-option label="object" value="object" />
            <el-option label="array" value="array" />
          </el-select>
          <el-switch v-model="field.required" active-text="必填" />
          <el-input v-model="field.description" placeholder="字段说明" />
          <el-input v-model="field.defaultValue" placeholder="默认值" />
          <el-button text type="danger" @click="config.outputSchema?.splice(index, 1)">删除</el-button>
        </div>
      </template>
    </section>

    <section class="llm-card runtime-card">
      <el-form-item label="视觉输入">
        <el-switch v-model="config.visionEnabled" active-text="启用" inactive-text="关闭" />
      </el-form-item>
      <el-form-item v-if="config.visionEnabled" label="图片变量">
        <el-select v-model="config.visionInputs" multiple filterable allow-create default-first-option placeholder="选择图片/文件变量" style="width: 100%">
          <el-option v-for="item in variableOptions" :key="item" :label="item" :value="item" />
        </el-select>
      </el-form-item>

      <el-form-item label="模型参数">
        <el-input :model-value="formatMap(paramsAsText)" type="textarea" :rows="5" placeholder="temperature = 0.2&#10;maxTokens = 1024&#10;topP = 0.8" @update:model-value="updateParams" />
      </el-form-item>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { Bottom, Delete, Plus, Top } from '@element-plus/icons-vue'
import type { CanvasNodeData, LlmNodeConfig, LlmPromptMessage } from '@/types/studio'
import type { ModelInstance } from '@/types/model'
import { addField, formatMap, parseMap } from './panelUtils'

const props = defineProps<{
  data: CanvasNodeData
  modelOptions: ModelInstance[]
  variableOptions: string[]
}>()

const promptModeOptions = [
  { label: '消息编排', value: 'messages' },
  { label: '简洁模式', value: 'simple' },
]
const focusedMessageId = ref<string>('')

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
  syncLegacyPrompts()
}

function removeMessage(index: number) {
  config.value.messages = activeMessages.value.filter((_, idx) => idx !== index)
  syncLegacyPrompts()
}

function moveMessage(index: number, delta: number) {
  const next = [...activeMessages.value]
  const target = index + delta
  if (target < 0 || target >= next.length) return
  const [item] = next.splice(index, 1)
  next.splice(target, 0, item)
  config.value.messages = next
}

function insertVariable(name: string) {
  const token = `{{ ${name} }}`
  const target = activeMessages.value.find((message) => message.id === focusedMessageId.value)
    || [...activeMessages.value].reverse().find((message) => message.role === 'user')
    || activeMessages.value[activeMessages.value.length - 1]
  if (target) {
    target.content = target.content ? `${target.content}\n${token}` : token
  }
  if (!config.value.contextVariables?.includes(name)) {
    config.value.contextVariables = [...(config.value.contextVariables || []), name]
  }
  syncLegacyPrompts()
}

function syncLegacyPrompts() {
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

<style scoped lang="scss">
.llm-panel {
  display: grid;
  gap: 14px;

  .llm-toolbar,
  .quick-add-row,
  .variable-chips {
    display: flex;
    align-items: center;
    gap: 8px;
    flex-wrap: wrap;
  }

  .llm-section-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    margin: 2px 0 0;

    &.compact {
      margin-top: 6px;
    }

    strong,
    span {
      display: block;
    }

    strong {
      color: #0f172a;
      font-size: 15px;
      font-weight: 850;
    }

    span {
      margin-top: 4px;
      color: #64748b;
      font-size: 12px;
      line-height: 1.5;
    }
  }

  .llm-card {
    padding: 14px;
    border: 1px solid #e2e8f0;
    border-radius: 12px;
    background: #ffffff;
    box-shadow: 0 12px 28px rgba(15, 23, 42, 0.04);

    :deep(.el-form-item) {
      margin-bottom: 12px;

      &:last-child {
        margin-bottom: 0;
      }
    }

    :deep(.el-form-item__label) {
      color: #475569;
      font-size: 12px;
      font-weight: 700;
    }

    :deep(.el-input__wrapper),
    :deep(.el-textarea__inner),
    :deep(.el-select__wrapper) {
      border-radius: 8px;
      box-shadow: 0 0 0 1px #dbe3ef inset;
    }
  }

  .llm-card-title {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 12px;
    margin-bottom: 12px;

    strong,
    span {
      display: block;
    }

    strong {
      color: #111827;
      font-size: 14px;
      font-weight: 850;
    }

    span {
      margin-top: 4px;
      color: #64748b;
      font-size: 12px;
    }
  }

  .llm-toolbar {
    flex-shrink: 0;
    justify-content: space-between;
  }

  .message-list {
    display: grid;
    gap: 10px;
    margin-bottom: 10px;
  }

  .message-card {
    padding: 12px;
    border: 1px solid #e2e8f0;
    border-radius: 12px;
    background:
      linear-gradient(180deg, rgba(248, 250, 252, 0.82), rgba(255, 255, 255, 0.98)),
      #ffffff;
    box-shadow: 0 8px 20px rgba(15, 23, 42, 0.035);
  }

  .message-head {
    display: flex;
    align-items: center;
    gap: 6px;
    margin-bottom: 10px;

    :deep(.el-button.is-circle) {
      width: 28px;
      height: 28px;
      border-color: #e2e8f0;
      color: #64748b;
    }

    :deep(.el-button--danger.is-text),
    :deep(.el-button--danger) {
      border-color: #fecdd3;
      background: #fff1f2;
      color: #e11d48;
    }
  }

  .template-pill {
    display: inline-flex;
    align-items: center;
    height: 28px;
    padding: 0 9px;
    border: 1px solid #dbeafe;
    border-radius: 8px;
    background: #eff6ff;
    color: #2563eb;
    font-size: 12px;
    font-weight: 750;
    white-space: nowrap;
  }

  .role-select {
    width: 122px;

    :deep(.el-select__wrapper) {
      min-height: 30px;
      border-radius: 8px;
      background: #f8fafc;
    }
  }

  .variable-picker {
    display: grid;
    gap: 8px;
    width: 100%;
  }

  .variable-chips {
    max-height: 76px;
    overflow: auto;

    :deep(.el-button) {
      border-color: #dbeafe;
      background: #eff6ff;
      color: #2563eb;
    }
  }

  .llm-collapse {
    margin: 4px 0 0;
    border: 1px solid #e2e8f0;
    border-radius: 10px;
    padding: 0 10px;
    background: #fbfdff;

    :deep(.el-collapse-item__header) {
      height: 38px;
      border-bottom: 0;
      background: transparent;
      color: #334155;
      font-size: 13px;
      font-weight: 800;
    }

    :deep(.el-collapse-item__wrap) {
      border-bottom: 0;
      background: transparent;
    }
  }

  .prompt-preview {
    display: grid;
    gap: 8px;
  }

  .preview-message {
    padding: 9px 0 0;
    border-top: 1px solid #edf2f7;

    strong {
      color: #4f46e5;
      font-size: 12px;
    }

    pre {
      margin: 4px 0 0;
      white-space: pre-wrap;
      word-break: break-word;
      color: #334155;
      font-size: 12px;
      line-height: 1.55;
    }
  }

  .strict-switch {
    margin-left: 12px;
  }

  .llm-schema-row {
    grid-template-columns: minmax(90px, 0.8fr) auto auto minmax(110px, 1fr) minmax(90px, 0.8fr) auto;
    padding: 8px;
    border: 1px solid #e2e8f0;
    border-radius: 10px;
    background: #f8fafc;
  }

  :deep(.el-segmented) {
    --el-segmented-item-selected-bg-color: #635bff;
    --el-segmented-item-selected-color: #ffffff;
    border-radius: 9px;
    background: #eef2ff;
  }

  :deep(.el-textarea__inner) {
    min-height: 120px;
    line-height: 1.55;
    color: #334155;
  }

  :deep(.el-switch.is-checked .el-switch__core) {
    background-color: #635bff;
    border-color: #635bff;
  }
}
</style>
