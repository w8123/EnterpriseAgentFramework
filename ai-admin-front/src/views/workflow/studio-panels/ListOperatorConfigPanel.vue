<template>
  <div class="node-specific-panel">
    <ConfigSection title="列表处理" description="按顺序执行下面的步骤，输出 items、count、first、last">
      <el-form-item label="要处理的列表">
        <el-input
          :model-value="config.source"
          placeholder="例如：var.orders / nodeOutput.tool_1.data"
          @update:model-value="write({ source: String($event || '') })"
        />
      </el-form-item>
      <div class="field-table-head">
        <strong>处理步骤</strong>
        <el-dropdown trigger="click" @command="addOperation">
          <el-button size="small" type="primary" plain>添加步骤</el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item v-for="item in OPERATION_TYPES" :key="item.value" :command="item.value">
                {{ item.label }}
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
      <div v-for="(operation, index) in config.operations" :key="index" class="condition-group">
        <div class="condition-group-head">
          <strong>{{ index + 1 }}. {{ labelOf(operation.type) }}</strong>
          <el-button text type="danger" @click="removeOperation(index)">删除</el-button>
        </div>
        <template v-if="operation.type === 'filter'">
          <div v-for="(condition, conditionIndex) in operation.conditions || []" :key="conditionIndex" class="condition-row">
            <el-input
              :model-value="condition.left"
              placeholder="item.status"
              @update:model-value="patchCondition(index, conditionIndex, { left: String($event || '') })"
            />
            <el-select
              :model-value="condition.operator"
              style="width: 130px"
              @update:model-value="patchCondition(index, conditionIndex, { operator: $event })"
            >
              <el-option v-for="op in CONDITION_OPERATORS" :key="op.value" :label="op.label" :value="op.value" />
            </el-select>
            <el-input
              :model-value="condition.right == null ? '' : String(condition.right)"
              :disabled="isUnaryOperator(condition.operator)"
              :placeholder="isUnaryOperator(condition.operator) ? '无需右值' : operatorPlaceholder(condition.operator)"
              @update:model-value="patchCondition(index, conditionIndex, { right: String($event ?? '') })"
            />
            <el-button text type="danger" @click="removeCondition(index, conditionIndex)">删除</el-button>
          </div>
          <div class="condition-group-head">
            <el-select
              :model-value="operation.logic || 'AND'"
              style="width: 120px"
              @update:model-value="patchOperation(index, { logic: $event })"
            >
              <el-option label="全部满足" value="AND" />
              <el-option label="任一满足" value="OR" />
            </el-select>
            <el-button size="small" text type="primary" @click="addCondition(index)">添加条件</el-button>
          </div>
          <div class="hint">用 item 表示列表里的每一项，例如 item.amount。</div>
        </template>
        <template v-else-if="operation.type === 'sort'">
          <div class="condition-row">
            <el-input
              :model-value="operation.field"
              placeholder="排序字段，如 amount（留空按值本身）"
              @update:model-value="patchOperation(index, { field: String($event || '') })"
            />
            <el-select
              :model-value="operation.order || 'asc'"
              style="width: 120px"
              @update:model-value="patchOperation(index, { order: $event })"
            >
              <el-option label="从小到大" value="asc" />
              <el-option label="从大到小" value="desc" />
            </el-select>
          </div>
        </template>
        <template v-else-if="operation.type === 'unique' || operation.type === 'pluck'">
          <el-input
            :model-value="operation.field"
            :placeholder="operation.type === 'unique' ? '按哪个字段去重，如 customerId（留空按整项）' : '要提取的字段，如 name'"
            @update:model-value="patchOperation(index, { field: String($event || '') })"
          />
        </template>
        <template v-else>
          <el-input-number
            :model-value="operation.count ?? 10"
            :min="0"
            :max="10000"
            @update:model-value="patchOperation(index, { count: Number($event ?? 0) })"
          />
          <span class="hint-inline">项</span>
        </template>
      </div>
      <div v-if="!config.operations.length" class="panel-empty-row">还没有步骤，原样输出列表。</div>
    </ConfigSection>
  </div>
</template>

<script setup lang="ts">
import ConfigSection from './ConfigSection.vue'
import { computed } from 'vue'
import type { CanvasNodeData, ListOperation, ListOperationType, ListOperatorNodeConfig, StudioCondition } from '@/types/studio'
import { CONDITION_OPERATORS, isUnaryOperator, operatorPlaceholder } from './conditionOperators'

const props = defineProps<{ data: CanvasNodeData }>()

const OPERATION_TYPES: { label: string; value: ListOperationType }[] = [
  { label: '筛选', value: 'filter' },
  { label: '排序', value: 'sort' },
  { label: '去重', value: 'unique' },
  { label: '取前 N 项', value: 'limit' },
  { label: '跳过前 N 项', value: 'offset' },
  { label: '提取字段', value: 'pluck' },
]

const config = computed<ListOperatorNodeConfig>(() => props.data.listConfig || { source: '', operations: [] })

function write(patch: Partial<ListOperatorNodeConfig>) {
  props.data.listConfig = { ...config.value, ...patch }
}

function labelOf(type: ListOperationType) {
  return OPERATION_TYPES.find((item) => item.value === type)?.label || type
}

function addOperation(type: ListOperationType) {
  const operation: ListOperation = { type }
  if (type === 'filter') {
    operation.logic = 'AND'
    operation.conditions = [{ left: 'item.status', operator: 'equals', right: '' }]
  }
  if (type === 'sort') operation.order = 'asc'
  if (type === 'limit' || type === 'offset') operation.count = 10
  write({ operations: [...config.value.operations, operation] })
}

function removeOperation(index: number) {
  write({ operations: config.value.operations.filter((_, i) => i !== index) })
}

function patchOperation(index: number, patch: Partial<ListOperation>) {
  write({ operations: config.value.operations.map((item, i) => (i === index ? { ...item, ...patch } : item)) })
}

function addCondition(index: number) {
  const current = config.value.operations[index]
  patchOperation(index, { conditions: [...(current.conditions || []), { left: 'item.', operator: 'equals', right: '' }] })
}

function removeCondition(index: number, conditionIndex: number) {
  const current = config.value.operations[index]
  patchOperation(index, { conditions: (current.conditions || []).filter((_, i) => i !== conditionIndex) })
}

function patchCondition(index: number, conditionIndex: number, patch: Partial<StudioCondition>) {
  const current = config.value.operations[index]
  patchOperation(index, {
    conditions: (current.conditions || []).map((item, i) => (i === conditionIndex ? { ...item, ...patch } : item)),
  })
}
</script>
