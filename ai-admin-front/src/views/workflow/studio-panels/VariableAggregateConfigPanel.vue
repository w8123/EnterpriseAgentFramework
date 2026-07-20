<template>
  <div class="node-specific-panel">
    <el-divider>变量聚合</el-divider>
    <el-form-item label="聚合模式">
      <el-segmented v-model="config.mode" :options="['object', 'array', 'text']" />
    </el-form-item>
    <el-form-item v-if="config.mode === 'text'" label="文本模板">
      <el-input v-model="config.template" type="textarea" :rows="4" placeholder="{{ itemA }} - {{ itemB }}" />
    </el-form-item>
    <div class="field-table-head">
      <strong>聚合项</strong>
      <el-button size="small" type="primary" plain @click="addItem">添加项</el-button>
    </div>
    <div v-for="(item, index) in config.items" :key="index" class="aggregate-row">
      <el-input v-model="item.name" placeholder="名称" />
      <el-select
        v-model="item.source"
        filterable
        allow-create
        default-first-option
        placeholder="来源，如 var.result / nodeOutput.n1 / input"
      >
        <el-option
          v-for="option in normalizedVariableOptions"
          :key="option"
          :label="option"
          :value="option"
        />
      </el-select>
      <el-button text type="danger" @click="config.items.splice(index, 1)">删除</el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { CanvasNodeData, StudioVariableOption, VariableAggregateNodeConfig } from '@/types/studio'

const props = defineProps<{
  data: CanvasNodeData
  variableOptions?: Array<string | StudioVariableOption>
}>()

const config = computed<VariableAggregateNodeConfig>(() => {
  props.data.aggregateConfig ||= {
    mode: 'object',
    items: [{ name: 'value', source: 'lastOutput' }],
    template: '',
  }
  return props.data.aggregateConfig
})

const normalizedVariableOptions = computed(() => {
  const options = props.variableOptions || []
  return options.map((item) => (typeof item === 'string' ? item : item.value)).filter(Boolean)
})

function addItem() {
  config.value.items.push({
    name: `item_${config.value.items.length + 1}`,
    source: 'lastOutput',
  })
}
</script>
