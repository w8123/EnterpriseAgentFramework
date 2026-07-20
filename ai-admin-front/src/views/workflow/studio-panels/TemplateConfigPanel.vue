<template>
  <div class="node-specific-panel">
    <el-divider>模板转换</el-divider>
    <el-alert
      title="TEMPLATE 是中间转换节点，输出 string，不会终止工作流。最终用户回答请使用 ANSWER。缺失变量按空字符串渲染。"
      type="info"
      :closable="false"
      class="panel-alert"
    />
    <el-form-item label="模板">
      <el-input
        v-model="templateText"
        type="textarea"
        :rows="8"
        placeholder="例如：订单 {{ var.orderId }} 状态为 {{ lastOutput }}"
      />
    </el-form-item>
    <div class="field-table-head">
      <strong>插入变量</strong>
    </div>
    <div class="variable-chips">
      <el-button
        v-for="option in normalizedVariableOptions.slice(0, 12)"
        :key="option"
        size="small"
        plain
        @click="insertVariable(option)"
      >
        {{ option }}
      </el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { CanvasNodeData, StudioVariableOption } from '@/types/studio'

const props = defineProps<{
  data: CanvasNodeData
  variableOptions?: Array<string | StudioVariableOption>
}>()

const templateText = computed({
  get: () => props.data.template || '',
  set: (value: string) => {
    props.data.template = value
    props.data.writeToAnswer = false
  },
})

const normalizedVariableOptions = computed(() => {
  const options = props.variableOptions || []
  return options.map((item) => (typeof item === 'string' ? item : item.value)).filter(Boolean)
})

function insertVariable(path: string) {
  const token = `{{ ${path} }}`
  const current = props.data.template || ''
  props.data.template = current ? `${current}${current.endsWith(' ') ? '' : ' '}${token}` : token
  props.data.writeToAnswer = false
}
</script>

<style scoped>
.panel-alert {
  margin-bottom: 12px;
}
.variable-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
</style>
