<template>
  <div class="node-specific-panel">
    <el-divider>循环控制</el-divider>
    <el-form-item label="循环键">
      <el-input v-model="config.loopKey" placeholder="loop" />
    </el-form-item>
    <el-form-item label="最大次数">
      <el-input-number v-model="config.maxIterations" :min="1" :max="100" />
    </el-form-item>
    <el-form-item label="列表表达式">
      <el-input v-model="config.itemExpression" placeholder="例如：params.items / nodeOutput.extract.items" />
    </el-form-item>
    <el-form-item label="退出条件">
      <el-input v-model="config.breakCondition" placeholder="例如：success / contains:done / equals:approved" />
    </el-form-item>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { CanvasNodeData, LoopNodeConfig } from '@/types/studio'

const props = defineProps<{ data: CanvasNodeData }>()

const config = computed<LoopNodeConfig>(() => {
  props.data.loopConfig ||= {
    loopKey: 'loop',
    maxIterations: 3,
    itemExpression: '',
    breakCondition: '',
  }
  return props.data.loopConfig
})
</script>
