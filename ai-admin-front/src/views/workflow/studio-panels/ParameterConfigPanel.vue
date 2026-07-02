<template>
  <div class="node-specific-panel">
    <el-divider>参数提取</el-divider>
    <el-form-item label="模式">
      <el-segmented v-model="config.mode" :options="['expression', 'llm']" />
    </el-form-item>
    <el-form-item v-if="config.mode === 'llm'" label="模型实例">
      <el-select v-model="config.modelInstanceId" filterable placeholder="选择用于提取的模型" style="width: 100%">
        <el-option v-for="item in modelOptions" :key="item.id" :label="`${item.name} / ${item.modelName}`" :value="item.id" />
      </el-select>
    </el-form-item>
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
import { addField, ensureFieldList } from './panelUtils'

const props = defineProps<{
  data: CanvasNodeData
  modelOptions: ModelInstance[]
}>()

const config = computed<ParameterNodeConfig>(() => {
  props.data.parameterConfig ||= {
    mode: 'expression',
    fields: [{ name: 'value', type: 'string', required: false, source: 'lastOutput' }],
  }
  return props.data.parameterConfig
})
const fields = computed(() => ensureFieldList(props.data))
</script>
