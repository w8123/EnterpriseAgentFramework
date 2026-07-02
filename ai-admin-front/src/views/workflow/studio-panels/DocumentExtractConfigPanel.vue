<template>
  <div class="node-specific-panel">
    <el-divider>文档抽取</el-divider>
    <el-form-item label="来源表达式">
      <el-input v-model="config.sourceExpression" placeholder="lastOutput / params.fileText / nodeOutput.http.body" />
    </el-form-item>
    <el-form-item label="输出格式">
      <el-segmented v-model="config.format" :options="formatOptions" />
    </el-form-item>
    <div class="field-table-head">
      <strong>抽取字段</strong>
      <el-button size="small" type="primary" plain @click="addField(config.fields)">添加字段</el-button>
    </div>
    <div v-for="(field, index) in config.fields" :key="index" class="field-row">
      <el-input v-model="field.name" placeholder="字段名" />
      <el-select v-model="field.type" style="width: 110px">
        <el-option label="文本" value="string" />
        <el-option label="数字" value="number" />
        <el-option label="布尔" value="boolean" />
        <el-option label="对象" value="object" />
        <el-option label="数组" value="array" />
      </el-select>
      <el-input v-model="field.source" placeholder="正则或表达式，可选" />
      <el-button text type="danger" @click="config.fields.splice(index, 1)">删除</el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { CanvasNodeData, DocumentExtractNodeConfig, StudioFieldSchema } from '@/types/studio'
import { addField } from './panelUtils'

const props = defineProps<{ data: CanvasNodeData }>()
const formatOptions = ['text', 'markdown', 'json']

const config = computed<DocumentExtractNodeConfig>(() => {
  props.data.documentExtractConfig ||= {
    sourceExpression: 'lastOutput',
    format: 'text',
    fields: [] as StudioFieldSchema[],
  }
  return props.data.documentExtractConfig
})
</script>
