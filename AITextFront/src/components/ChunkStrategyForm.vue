<template>
  <el-form
    :model="config"
    label-width="100px"
    label-position="left"
    class="form-section"
  >
    <el-form-item label="切分策略">
      <el-radio-group v-model="config.chunkStrategy" @change="handleStrategyChange">
        <el-radio-button
          v-for="option in CHUNK_STRATEGY_OPTIONS"
          :key="option.value"
          :value="option.value"
        >
          {{ option.label }}
        </el-radio-button>
      </el-radio-group>
      <div class="strategy-desc">
        {{ currentStrategyDesc }}
      </div>
    </el-form-item>

    <el-form-item label="切分大小">
      <el-slider
        v-model="config.chunkSize"
        :min="100"
        :max="2000"
        :step="50"
        show-input
        @change="handleConfigChange"
      />
    </el-form-item>

    <el-form-item label="重叠字符">
      <el-slider
        v-model="config.chunkOverlap"
        :min="0"
        :max="500"
        :step="10"
        show-input
        @change="handleConfigChange"
      />
    </el-form-item>
  </el-form>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { ChunkConfig, ChunkStrategyType } from '@/types/import'
import { CHUNK_STRATEGY_OPTIONS } from '@/utils'

const config = defineModel<ChunkConfig>('config', { required: true })
const emit = defineEmits<{ change: [] }>()

const currentStrategyDesc = computed(() => {
  const found = CHUNK_STRATEGY_OPTIONS.find(
    (o) => o.value === config.value.chunkStrategy,
  )
  return found?.description || ''
})

function handleStrategyChange(_val: ChunkStrategyType) {
  emit('change')
}

function handleConfigChange() {
  emit('change')
}
</script>

<style scoped>
.strategy-desc {
  margin-top: 4px;
  font-size: 12px;
  color: #909399;
}
</style>
