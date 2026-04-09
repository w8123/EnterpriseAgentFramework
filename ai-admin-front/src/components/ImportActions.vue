<template>
  <div class="import-actions">
    <el-button @click="handleReset">
      <el-icon><RefreshLeft /></el-icon>
      重置
    </el-button>

    <el-button
      type="primary"
      :loading="loading"
      :disabled="!canImport"
      @click="handleImport"
    >
      <el-icon><Upload /></el-icon>
      开始入库
    </el-button>
  </div>
</template>

<script setup lang="ts">
import { RefreshLeft, Upload } from '@element-plus/icons-vue'

defineProps<{
  loading: boolean
  canImport: boolean
}>()

const emit = defineEmits<{
  import: []
  reset: []
}>()

function handleImport() {
  emit('import')
}

function handleReset() {
  emit('reset')
}
</script>

<style scoped>
.import-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  padding: 16px 0;
}
</style>
