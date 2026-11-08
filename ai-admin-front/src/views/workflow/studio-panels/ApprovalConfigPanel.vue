<template>
  <div class="node-specific-panel">
    <ConfigSection title="确认内容" description="流程在这里暂停，运行的人批准后走「批准」线，拒绝走「拒绝」线">
      <el-form-item label="标题">
        <el-input
          :model-value="config.title"
          placeholder="例如：确认给客户退款"
          @update:model-value="write({ title: String($event || '') })"
        />
      </el-form-item>
      <el-form-item label="要确认的内容">
        <el-input
          :model-value="config.prompt"
          type="textarea"
          :rows="5"
          placeholder="可以引用变量，例如：退款 {{ params.amount }} 元给 {{ params.customer }}"
          @update:model-value="write({ prompt: String($event || '') })"
        />
      </el-form-item>
      <el-form-item label="最长等待">
        <el-input-number
          :model-value="Math.round((config.timeoutSeconds || 3600) / 60)"
          :min="1"
          :max="10080"
          @update:model-value="write({ timeoutSeconds: Math.max(1, Number($event || 60)) * 60 })"
        />
        <span class="hint-inline">分钟，超时后这次确认失效</span>
      </el-form-item>
      <div class="hint">
        在调试、Agent 对话和嵌入式助手里会弹出确认卡片。定时自动化和员工 AI 工具（MCP）调用时不能暂停，会直接失败；
        这类场景里的写操作已经由「AI 操作审核」把关。
      </div>
    </ConfigSection>
  </div>
</template>

<script setup lang="ts">
import ConfigSection from './ConfigSection.vue'
import { computed } from 'vue'
import type { CanvasNodeData, HumanApprovalNodeConfig } from '@/types/studio'

const props = defineProps<{ data: CanvasNodeData }>()

const config = computed<HumanApprovalNodeConfig>(() => props.data.approvalConfig || {
  title: '人工确认',
  prompt: '{{ lastOutput }}',
  approvers: [],
  timeoutSeconds: 3600,
})

function write(patch: Partial<HumanApprovalNodeConfig>) {
  props.data.approvalConfig = { ...config.value, ...patch }
}
</script>
