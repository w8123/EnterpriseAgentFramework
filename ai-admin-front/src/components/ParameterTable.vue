<template>
  <div class="parameter-editor">
    <el-table :data="rows" size="small" border>
      <el-table-column label="参数名" min-width="140">
        <template #default="{ row }">
          <el-input v-model="row.name" :disabled="disabled" placeholder="snake_case" />
        </template>
      </el-table-column>
      <el-table-column label="类型" width="130">
        <template #default="{ row }">
          <el-select v-model="row.type" :disabled="disabled" style="width: 100%">
            <el-option v-for="t in typeOptions" :key="t" :label="t" :value="t" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column v-if="showLocation" label="位置" width="120">
        <template #default="{ row }">
          <el-select v-model="row.location" :disabled="disabled" style="width: 100%">
            <el-option v-for="loc in locationOptions" :key="loc" :label="loc" :value="loc" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="描述" min-width="220">
        <template #default="{ row }">
          <el-input v-model="row.description" :disabled="disabled" />
        </template>
      </el-table-column>
      <el-table-column label="必填" width="80" align="center">
        <template #default="{ row }">
          <el-switch v-model="row.required" :disabled="disabled" />
        </template>
      </el-table-column>
      <el-table-column width="80" align="center">
        <template #default="{ $index }">
          <el-button link type="danger" :disabled="disabled" @click="remove($index)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-button class="add-parameter-button" :disabled="disabled" @click="add">+ 添加参数</el-button>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

/**
 * 参数编辑通用组件 —— Tool / Skill 管理端复用。
 *
 * 只认"一层"参数；Tool 的嵌套参数目前来自后端扫描，不在这里编辑（保持 Phase 2.0 范围）。
 */
export interface ParameterRow {
  name: string
  type: string
  description: string
  required: boolean
  location?: string | null
  children?: ParameterRow[]
}

const props = withDefaults(
  defineProps<{
    modelValue: ParameterRow[]
    disabled?: boolean
    showLocation?: boolean
    typeOptions?: string[]
    locationOptions?: string[]
    defaultLocation?: string
    defaultType?: string
  }>(),
  {
    disabled: false,
    showLocation: true,
    typeOptions: () => ['string', 'integer', 'number', 'boolean', 'object', 'array'],
    locationOptions: () => ['QUERY', 'PATH', 'BODY'],
    defaultLocation: 'QUERY',
    defaultType: 'string',
  },
)

const emit = defineEmits<{
  (e: 'update:modelValue', value: ParameterRow[]): void
}>()

const rows = computed({
  get: () => props.modelValue,
  set: (val) => emit('update:modelValue', val),
})

function add() {
  const next = [...(rows.value || [])]
  next.push({
    name: '',
    type: props.defaultType,
    description: '',
    required: false,
    ...(props.showLocation ? { location: props.defaultLocation } : {}),
  })
  emit('update:modelValue', next)
}

function remove(index: number) {
  const next = [...(rows.value || [])]
  next.splice(index, 1)
  emit('update:modelValue', next)
}
</script>

<style scoped>
.parameter-editor {
  width: 100%;
}
.add-parameter-button {
  margin-top: 8px;
}
</style>
