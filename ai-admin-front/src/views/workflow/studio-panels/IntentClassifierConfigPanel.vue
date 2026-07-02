<template>
  <div class="node-specific-panel">
    <el-divider>意图分类</el-divider>
    <el-form-item label="分类策略">
      <el-segmented v-model="config.strategy" :options="strategyOptions" />
    </el-form-item>
    <el-form-item label="输入表达式">
      <el-input v-model="config.inputExpression" placeholder="input / lastOutput / params.question" />
    </el-form-item>
    <template v-if="showLlmSettings">
      <el-form-item label="模型实例">
        <el-select v-model="config.modelInstanceId" filterable placeholder="选择用于分类的模型" style="width: 100%">
          <el-option
            v-for="item in modelOptions"
            :key="item.id"
            :label="`${item.name} / ${item.modelName}`"
            :value="item.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="置信度阈值">
        <el-input-number v-model="config.confidenceThreshold" :min="0" :max="1" :step="0.05" style="width: 100%" />
      </el-form-item>
      <el-form-item label="LLM Prompt">
        <el-input
          v-model="config.llmPrompt"
          type="textarea"
          :rows="3"
          placeholder="留空则使用内置分类提示；可用 {{ input }} 等模板变量"
        />
      </el-form-item>
    </template>
    <div class="field-table-head">
      <strong>分类分支</strong>
      <el-button size="small" type="primary" plain @click="addClass">添加分类</el-button>
    </div>
    <div v-for="(item, index) in config.classes" :key="index" class="classifier-row">
      <el-input v-model="item.id" placeholder="route id" />
      <el-input v-model="item.label" placeholder="显示名" />
      <el-input v-model="item.description" placeholder="语义描述（LLM/HYBRID 推荐填写）" />
      <el-input
        v-if="showKeywordSettings"
        :model-value="item.keywords.join(', ')"
        placeholder="关键词，逗号分隔"
        @update:model-value="item.keywords = splitKeywords($event)"
      />
      <el-button text type="danger" @click="config.classes.splice(index, 1)">删除</el-button>
    </div>
    <el-form-item label="默认分支">
      <el-input v-model="config.defaultRoute" placeholder="else" />
    </el-form-item>
  </div>
</template>

<script setup lang="ts">
import { computed, watch } from 'vue'
import type { CanvasNodeData, IntentClassifierNodeConfig } from '@/types/studio'
import type { ModelInstance } from '@/types/model'
import { classifierOutputPorts } from '@/utils/studio'

const props = defineProps<{
  data: CanvasNodeData
  modelOptions: ModelInstance[]
}>()

const strategyOptions = [
  { label: '关键词', value: 'KEYWORD' },
  { label: 'LLM', value: 'LLM' },
  { label: '混合', value: 'HYBRID' },
]

const config = computed<IntentClassifierNodeConfig>(() => {
  props.data.classifierConfig ||= {
    inputExpression: 'input',
    strategy: 'KEYWORD',
    classes: [{ id: 'matched', label: 'Matched', keywords: [] }],
    defaultRoute: 'else',
    modelInstanceId: '',
    confidenceThreshold: 0.7,
    llmPrompt: '',
  }
  props.data.classifierConfig.strategy ||= 'KEYWORD'
  props.data.classifierConfig.confidenceThreshold ??= 0.7
  props.data.classifierConfig.modelInstanceId ||= ''
  props.data.classifierConfig.llmPrompt ||= ''
  return props.data.classifierConfig
})

const showKeywordSettings = computed(() => config.value.strategy !== 'LLM')
const showLlmSettings = computed(() => config.value.strategy === 'LLM' || config.value.strategy === 'HYBRID')

function addClass() {
  config.value.classes.push({
    id: `route_${config.value.classes.length + 1}`,
    label: `分支 ${config.value.classes.length + 1}`,
    description: '',
    keywords: [],
  })
}

watch(config, (value) => {
  props.data.outputs = classifierOutputPorts(value)
}, { deep: true, immediate: true })

function splitKeywords(value: string) {
  return (value || '')
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean)
}
</script>
