<template>
  <div>
    <p>{{ message || '请确认' }}</p>
    <el-button type="primary" @click="$emit('submit', { confirm: true })">是</el-button>
    <el-button @click="$emit('submit', { confirm: false })">否</el-button>
    <el-button text @click="$emit('cancel')">取消</el-button>
  </div>
</template>

<script setup lang="ts">
defineProps<{
  message?: string
}>()

defineEmits<{
  submit: [values: Record<string, unknown>]
  cancel: []
}>()
</script>
