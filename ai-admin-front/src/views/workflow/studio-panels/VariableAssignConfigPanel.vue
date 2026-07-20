<template>
  <div class="node-specific-panel">
    <el-divider>变量赋值</el-divider>
    <el-alert
      title="目标写入 var.&lt;alias&gt; 业务命名空间。先解析全部右值，再原子写入。字符串默认按表达式/模板解析；number/boolean/object/array/null 以原生 JSON 类型保存。"
      type="info"
      :closable="false"
      class="panel-alert"
    />
    <el-alert
      v-if="jsonError"
      :title="jsonError"
      type="error"
      :closable="false"
      class="panel-alert"
      data-testid="variable-assign-json-error"
    />
    <div class="field-table-head">
      <strong>赋值项</strong>
      <el-button size="small" type="primary" plain @click="addAssignment">添加赋值</el-button>
    </div>
    <div v-for="(item, index) in rows" :key="index" class="assign-row">
      <el-input v-model="item.target" placeholder="目标，如 customer_id 或 var.order.status" @change="sync" />
      <el-select v-model="item.valueKind" style="width: 140px" @change="onKindChange(item)">
        <el-option label="表达式/模板" value="expression" />
        <el-option label="number" value="number" />
        <el-option label="boolean" value="boolean" />
        <el-option label="object/array JSON" value="json" />
        <el-option label="null" value="null" />
      </el-select>
      <template v-if="item.valueKind === 'expression'">
        <el-select
          v-model="item.textValue"
          filterable
          allow-create
          default-first-option
          placeholder="表达式/模板字符串，如 input 或 {{ params.q }}"
          @change="sync"
        >
          <el-option
            v-for="option in normalizedVariableOptions"
            :key="option"
            :label="option"
            :value="option"
          />
        </el-select>
      </template>
      <template v-else-if="item.valueKind === 'number'">
        <el-input-number v-model="item.numberValue" controls-position="right" @change="sync" />
      </template>
      <template v-else-if="item.valueKind === 'boolean'">
        <el-switch v-model="item.boolValue" @change="sync" />
      </template>
      <template v-else-if="item.valueKind === 'json'">
        <el-input
          v-model="item.jsonText"
          type="textarea"
          :rows="2"
          placeholder='例如 {"a":1} 或 [1,true]'
          @change="sync"
        />
      </template>
      <template v-else>
        <el-tag type="info">null</el-tag>
      </template>
      <el-button text type="danger" @click="removeAssignment(index)">删除</el-button>
    </div>
    <div v-if="!rows.length" class="panel-empty-row">至少配置一条赋值。</div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { CanvasNodeData, StudioVariableOption } from '@/types/studio'
import {
  buildAssignmentsFromRows,
  type VariableAssignRow,
  type VariableAssignValueKind,
} from './variableAssignValue'

const props = defineProps<{
  data: CanvasNodeData
  variableOptions?: Array<string | StudioVariableOption>
  nodeId?: string
}>()

const emit = defineEmits<{
  (e: 'validation-change', payload: { nodeId?: string; valid: boolean; message?: string }): void
}>()

const rows = ref<VariableAssignRow[]>([])
const jsonError = ref('')

const normalizedVariableOptions = computed(() => {
  const options = props.variableOptions || []
  return options.map((item) => (typeof item === 'string' ? item : item.value)).filter(Boolean)
})

function detectKind(value: unknown): VariableAssignValueKind {
  if (value === null) return 'null'
  if (typeof value === 'number') return 'number'
  if (typeof value === 'boolean') return 'boolean'
  if (typeof value === 'object') return 'json'
  return 'expression'
}

function toRow(target: string, value: unknown): VariableAssignRow {
  const kind = detectKind(value)
  return {
    target,
    valueKind: kind,
    textValue: kind === 'expression' ? String(value ?? '') : '',
    numberValue: kind === 'number' ? Number(value) : undefined,
    boolValue: kind === 'boolean' ? Boolean(value) : false,
    jsonText: kind === 'json' ? JSON.stringify(value, null, 2) : '',
  }
}

watch(
  () => props.data.assignments,
  (assignments) => {
    const next = Object.entries(assignments || {}).map(([target, value]) => toRow(target, value))
    if (JSON.stringify(next) !== JSON.stringify(rows.value)) {
      rows.value = next.length ? next : [toRow('var_field_1', 'input')]
      sync()
    }
  },
  { immediate: true, deep: true },
)

function onKindChange(item: VariableAssignRow) {
  if (item.valueKind === 'number' && item.numberValue == null) item.numberValue = 0
  if (item.valueKind === 'boolean') item.boolValue = false
  if (item.valueKind === 'json' && !item.jsonText) item.jsonText = '{}'
  if (item.valueKind === 'expression' && !item.textValue) item.textValue = 'input'
  sync()
}

function sync() {
  const built = buildAssignmentsFromRows(rows.value)
  if (!built.ok) {
    jsonError.value = `第 ${built.index + 1} 项：${built.error}`
    emit('validation-change', {
      nodeId: props.nodeId,
      valid: false,
      message: jsonError.value,
    })
    return
  }
  jsonError.value = ''
  props.data.assignments = built.assignments as CanvasNodeData['assignments']
  emit('validation-change', { nodeId: props.nodeId, valid: true })
}

function addAssignment() {
  rows.value.push(toRow(`var_field_${rows.value.length + 1}`, 'input'))
  sync()
}

function removeAssignment(index: number) {
  rows.value.splice(index, 1)
  sync()
}
</script>

<style scoped>
.panel-alert {
  margin-bottom: 12px;
}
.assign-row {
  display: grid;
  grid-template-columns: minmax(120px, 1fr) 140px minmax(160px, 1.6fr) auto;
  gap: 8px;
  margin-bottom: 8px;
  align-items: center;
}
.panel-empty-row {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
