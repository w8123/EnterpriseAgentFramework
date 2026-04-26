<template>
  <el-form label-position="top" @submit.prevent>
    <el-form-item v-if="field" :label="field.label">
      <el-select v-model="val" filterable clearable style="width: 100%">
        <el-option v-for="o in field.options || []" :key="o.value" :label="o.label" :value="o.value" />
      </el-select>
    </el-form-item>
    <el-button type="primary" @click="emitSubmit">下一步</el-button>
    <el-button @click="$emit('cancel')">取消</el-button>
  </el-form>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import type { UiFieldPayload } from '@/types/interaction'

const props = defineProps<{
  field?: UiFieldPayload
  prefilled?: Record<string, unknown>
}>()

const emit = defineEmits<{
  submit: [values: Record<string, unknown>]
  cancel: []
}>()

const val = ref<string | undefined>()

watch(
  () => props.field,
  (f) => {
    if (f && props.prefilled && props.prefilled[f.key] != null) {
      val.value = String(props.prefilled[f.key])
    }
  },
  { immediate: true },
)

function emitSubmit() {
  if (!props.field) return
  emit('submit', { [props.field.key]: val.value })
}
</script>
