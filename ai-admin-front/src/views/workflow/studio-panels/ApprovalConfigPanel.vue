<template>
  <div class="node-specific-panel">
    <el-divider>人工确认</el-divider>
    <el-form-item label="确认标题">
      <el-input v-model="config.title" placeholder="例如：订单退款确认" />
    </el-form-item>
    <el-form-item label="确认内容">
      <el-input v-model="config.prompt" type="textarea" :rows="5" placeholder="{{ lastOutput }}" />
    </el-form-item>
    <el-form-item label="审批人">
      <el-input :model-value="config.approvers.join(', ')" placeholder="userA, userB 或角色编码" @update:model-value="config.approvers = parseList($event)" />
    </el-form-item>
    <el-form-item label="超时秒数">
      <el-input-number v-model="config.timeoutSeconds" :min="30" :max="604800" />
    </el-form-item>
    <el-form-item label="默认分支">
      <el-input v-model="config.defaultRoute" placeholder="approved / rejected / timeout" />
    </el-form-item>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { CanvasNodeData, HumanApprovalNodeConfig } from '@/types/studio'

const props = defineProps<{ data: CanvasNodeData }>()

const config = computed<HumanApprovalNodeConfig>(() => {
  props.data.approvalConfig ||= {
    title: '人工确认',
    prompt: '{{ lastOutput }}',
    approvers: [],
    timeoutSeconds: 3600,
    defaultRoute: 'approved',
  }
  return props.data.approvalConfig
})

function parseList(value: string) {
  return value.split(',').map((item) => item.trim()).filter(Boolean)
}
</script>
