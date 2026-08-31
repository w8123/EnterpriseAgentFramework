<template>
  <div class="node-specific-panel">
    <el-divider>参数提取</el-divider>
    <el-form-item label="模式">
      <el-segmented v-model="config.mode" :options="['expression', 'llm']" />
    </el-form-item>
    <el-form-item v-if="config.mode === 'llm'" label="输入表达式">
      <el-input
        v-model="config.inputExpression"
        placeholder="input / lastOutput / nodeOutput.query_node"
      />
    </el-form-item>
    <el-form-item v-if="config.mode === 'llm'" label="模型实例">
      <el-select
        v-model="config.modelInstanceId"
        filterable
        placeholder="选择用于提取的模型"
        style="width: 100%"
        :loading="modelOptionsLoading"
        @visible-change="handleModelSelectVisible"
      >
        <el-option v-for="item in modelOptions" :key="item.id" :label="`${item.name} / ${item.modelName}`" :value="item.id" />
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
    <template v-if="config.mode === 'llm'">
      <el-form-item label="系统提示词">
        <el-input
          v-model="config.systemPrompt"
          type="textarea"
          :rows="3"
          placeholder="留空则使用平台内置的结构化参数提取提示词"
        />
      </el-form-item>
      <el-form-item label="用户提示词">
        <el-input
          v-model="config.userPrompt"
          type="textarea"
          :rows="4"
          placeholder="留空则直接使用输入表达式；也可通过 {{ nodeOutput.query_node }} 显式引用上游输出"
        />
      </el-form-item>
      <el-alert
        v-if="promptInputWarning"
        :title="promptInputWarning"
        type="error"
        :closable="false"
        show-icon
        class="parameter-prompt-alert"
      />
      <el-alert
        v-else
        title="用户提示词非空时会替代输入表达式的默认内容；如需使用上游结果，请在提示词中显式引用对应变量。"
        type="info"
        :closable="false"
        show-icon
        class="parameter-prompt-alert"
      />
    </template>
    <div class="field-table-head">
      <strong>目标字段结构</strong>
      <el-button size="small" type="primary" plain @click="addField(fields)">添加字段</el-button>
    </div>
    <div v-for="(field, index) in fields" :key="index" class="field-row">
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
      <el-input v-model="field.defaultValue" placeholder="默认值" />
      <el-input v-model="field.source" placeholder="来源表达式，如 lastOutput.id" />
      <el-button text type="danger" @click="fields.splice(index, 1)">删除</el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { CanvasNodeData, ParameterNodeConfig } from '@/types/studio'
import type { ModelInstance } from '@/types/model'
import ModelSelectEmptyState from '@/components/model/ModelSelectEmptyState.vue'
import { addField, ensureFieldList } from './panelUtils'
import { parameterPromptInputWarning } from './parameterPromptContract'

const props = defineProps<{
  data: CanvasNodeData
  modelOptions: ModelInstance[]
  modelOptionsLoading: boolean
  modelOptionsLoadError: boolean
}>()

const emit = defineEmits<{
  reloadModelOptions: []
}>()

const config = computed<ParameterNodeConfig>(() => {
  props.data.parameterConfig ||= {
    mode: 'expression',
    inputExpression: 'input',
    systemPrompt: '',
    userPrompt: '',
    modelParams: {},
    fields: [{ name: 'value', type: 'string', required: false, source: 'lastOutput' }],
  }
  return props.data.parameterConfig
})
const fields = computed(() => ensureFieldList(props.data))
const promptInputWarning = computed(() => parameterPromptInputWarning(config.value))

function handleModelSelectVisible(visible: boolean) {
  if (visible) emit('reloadModelOptions')
}
</script>

<style scoped>
.parameter-prompt-alert {
  margin: 2px 0 16px;
}
</style>
