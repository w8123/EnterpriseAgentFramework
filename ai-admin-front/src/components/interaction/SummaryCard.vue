<template>
  <div class="summary-card">
    <h4 v-if="title">{{ title }}</h4>
    <p v-if="message" class="hint">{{ message }}</p>
    <el-descriptions :column="1" border size="small">
      <el-descriptions-item v-for="(val, key) in summary" :key="key" :label="String(key)">
        {{ val }}
      </el-descriptions-item>
    </el-descriptions>
    <div class="actions">
      <el-button type="primary" @click="$emit('submit')">确认提交</el-button>
      <el-button @click="$emit('modify')">修改</el-button>
      <el-button @click="$emit('cancel')">取消</el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
defineProps<{
  title?: string
  message?: string
  summary?: Record<string, unknown>
}>()

defineEmits<{
  submit: []
  modify: []
  cancel: []
}>()
</script>

<style scoped>
.summary-card {
  margin-top: 8px;
}
.hint {
  color: #909399;
  font-size: 13px;
  margin: 8px 0;
}
.actions {
  margin-top: 12px;
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
</style>
