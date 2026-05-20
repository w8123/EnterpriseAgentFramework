<template>
  <div class="node-specific-panel">
    <el-divider>意图分类</el-divider>
    <el-form-item label="输入表达式">
      <el-input v-model="config.inputExpression" placeholder="input / lastOutput / params.question" />
    </el-form-item>
    <div class="field-table-head">
      <strong>分类分支</strong>
      <el-button size="small" type="primary" plain @click="addClass">添加分类</el-button>
    </div>
    <div v-for="(item, index) in config.classes" :key="index" class="classifier-row">
      <el-input v-model="item.id" placeholder="route id" />
      <el-input v-model="item.label" placeholder="显示名" />
      <el-input
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
import { classifierOutputPorts } from '@/utils/studio'

const props = defineProps<{
  data: CanvasNodeData
}>()

const config = computed<IntentClassifierNodeConfig>(() => {
  props.data.classifierConfig ||= {
    inputExpression: 'input',
    classes: [{ id: 'matched', label: 'Matched', keywords: [] }],
    defaultRoute: 'else',
  }
  return props.data.classifierConfig
})

function addClass() {
  config.value.classes.push({
    id: `route_${config.value.classes.length + 1}`,
    label: `分支 ${config.value.classes.length + 1}`,
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
