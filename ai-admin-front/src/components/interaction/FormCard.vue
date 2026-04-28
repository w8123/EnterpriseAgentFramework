<template>
  <el-form label-position="top" @submit.prevent>
    <el-form-item v-for="f in fields" :key="f.key" :label="f.label + (f.required ? ' *' : '')">
      <el-select
        v-if="f.type === 'select' && (f.options?.length ?? 0) > 0"
        v-model="local[f.key]"
        filterable
        clearable
        style="width: 100%"
        :placeholder="'请选择' + f.label"
      >
        <el-option v-for="o in f.options" :key="o.value" :label="o.label" :value="o.value" />
      </el-select>
      <el-select
        v-else-if="f.type === 'multi_select'"
        v-model="local[f.key]"
        multiple
        collapse-tags
        collapse-tags-tooltip
        filterable
        clearable
        style="width: 100%"
        :placeholder="
          (f.options?.length ?? 0) > 0
            ? '请选择' + f.label + '（可多选）'
            : '暂无选项（请检查 TOOL_CALL 配置或 Tool 返回值）'
        "
      >
        <el-option v-for="o in f.options || []" :key="o.value" :label="o.label" :value="o.value" />
      </el-select>
      <el-date-picker
        v-else-if="f.type === 'date'"
        v-model="local[f.key]"
        type="date"
        value-format="YYYY-MM-DD"
        style="width: 100%"
      />
      <el-input v-else v-model="local[f.key]" :placeholder="f.label" />
    </el-form-item>
    <el-form-item>
      <el-button type="primary" @click="emitSubmit">提交</el-button>
      <el-button @click="$emit('cancel')">取消</el-button>
    </el-form-item>
  </el-form>
</template>

<script setup lang="ts">
import { reactive, watch } from 'vue'
import type { UiFieldPayload } from '@/types/interaction'

const props = defineProps<{
  fields: UiFieldPayload[]
  prefilled?: Record<string, unknown>
}>()

const emit = defineEmits<{
  submit: [values: Record<string, unknown>]
  cancel: []
}>()

const local = reactive<Record<string, unknown>>({})

function defaultEmpty(f: UiFieldPayload): unknown {
  return f.type === 'multi_select' ? [] : ''
}

function sync() {
  for (const f of props.fields) {
    const v = props.prefilled?.[f.key]
    if (v !== undefined && v !== null) {
      local[f.key] = v
    } else if (local[f.key] === undefined) {
      local[f.key] = defaultEmpty(f)
    }
  }
}

watch(
  () => [props.fields, props.prefilled],
  () => sync(),
  { immediate: true, deep: true },
)

function emitSubmit() {
  emit('submit', { ...local })
}
</script>
