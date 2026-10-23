<template>
  <div class="node-specific-panel">
    <ConfigSection title="赋值规则" description="设置目标变量与取值来源">
      <LiquidGlassNotice
        title="下游使用 var.变量名 引用结果。可选择变量、表达式或固定值；所有赋值计算成功后一起写入。"
        type="info"
        class="panel-alert"
        source="backdrop"
      />
      <LiquidGlassNotice
        v-if="jsonError"
        :title="jsonError"
        type="error"
        class="panel-alert"
        data-testid="variable-assign-json-error"
        source="backdrop"
      />
      <div class="field-table-head">
        <strong>赋值项</strong>
        <el-button size="small" type="primary" plain @click="addAssignment">添加赋值</el-button>
      </div>
      <div v-for="(item, index) in rows" :key="index" class="assign-row">
        <el-input v-model="item.target" placeholder="目标，如 customer_id 或 var.order.status" @change="sync" />
        <el-select v-model="item.valueKind" style="width: 140px" @change="onKindChange(item)">
          <el-option label="表达式/模板" value="expression" />
          <el-option label="数字" value="number" />
          <el-option label="布尔值" value="boolean" />
          <el-option label="对象 / 数组" value="json" />
          <el-option label="空值 null" value="null" />
        </el-select>
        <template v-if="item.valueKind === 'expression'">
          <el-select
            v-model="item.textValue"
            filterable
            :fit-input-width="true"
            popper-class="workflow-variable-assign-options"
            allow-create
            default-first-option
            placeholder="表达式/模板字符串，如 input 或 {{ params.q }}"
            @change="sync"
          >
            <el-option
              v-for="option in normalizedVariableOptions"
              :key="option.value"
              :label="option.label"
              :value="option.value"
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
    </ConfigSection>
  </div>
</template>

<script setup lang="ts">
import ConfigSection from './ConfigSection.vue'
import { computed, ref, watch } from 'vue'
import type { CanvasNodeData, StudioVariableOption } from '@/types/studio'
import {
  buildAssignmentsFromRows,
  type VariableAssignRow,
  type VariableAssignValueKind,
} from './variableAssignValue'
import { LiquidGlassNotice } from '@/components/common/liquid-glass'

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
  return options
    .map((item) => (typeof item === 'string' ? { value: item, label: item } : item))
    .filter((item) => Boolean(item.value))
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
  min-width: 0;
}
.assign-row > * {
  min-width: 0;
}
:global(.workflow-variable-assign-options .el-select-dropdown__item) {
  height: auto;
  min-height: var(--el-component-size);
  line-height: 1.5;
  padding-block: 6px;
  white-space: normal;
  overflow-wrap: anywhere;
}
@container studio-config (max-width: 640px) {
  .assign-row {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    padding: 12px;
    border: 1px solid var(--border-subtle);
    border-radius: 8px;
  }
  .assign-row > :nth-child(2) {
    width: 100% !important;
  }
  .assign-row > :nth-child(3) {
    grid-column: 1 / -1;
    width: 100%;
  }
  .assign-row > .el-button {
    grid-column: 2;
    justify-self: end;
  }
}
.panel-empty-row {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
