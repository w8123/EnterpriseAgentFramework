<template>
  <div class="node-specific-panel">
    <ConfigSection title="并行方式" description="从本节点拉出的每条线是一条分支，分支在同一个下游节点汇合">
      <LiquidGlassNotice
        title="至少连出 2 条分支，最多 8 条；所有分支要汇合到同一个节点。分支里不能放回复、用户输入、交互、页面动作或另一个并行节点。"
        type="info"
        source="backdrop"
      />
      <el-form-item label="同时运行">
        <el-input-number v-model="config.maxConcurrency" :min="1" :max="4" />
        <span class="hint-inline">条分支（最多 4）</span>
      </el-form-item>
      <el-form-item label="分支失败时">
        <el-radio-group v-model="config.failureMode">
          <el-radio value="FAIL_FAST">整体失败</el-radio>
          <el-radio value="CONTINUE">继续汇合</el-radio>
        </el-radio-group>
      </el-form-item>
      <div class="hint">
        各分支在自己的变量副本上运行，完成后把产生的节点输出和变量合并回来。
        汇合节点可通过 <code>nodeOutput.{{ nodeId || '本节点' }}.branches</code> 查看每条分支的结果。
      </div>
    </ConfigSection>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import ConfigSection from './ConfigSection.vue'
import { LiquidGlassNotice } from '@/components/common/liquid-glass'
import type { CanvasNodeData, ParallelNodeConfig } from '@/types/studio'
import { normalizeParallelConfig } from '@/utils/studio/defaults'

const props = defineProps<{ data: CanvasNodeData; nodeId?: string }>()

const config = computed<ParallelNodeConfig>(() => {
  if (!props.data.parallelConfig) props.data.parallelConfig = normalizeParallelConfig(undefined)
  return props.data.parallelConfig
})
</script>

<style scoped>
.hint {
  margin-top: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.5;
}
.hint-inline {
  margin-left: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
