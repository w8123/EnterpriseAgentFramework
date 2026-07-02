<template>
  <div class="node-specific-panel">
    <el-divider>回复节点</el-divider>
    <el-form-item label="回复模板">
      <el-input
        v-model="config.template"
        type="textarea"
        :rows="7"
        placeholder="例如：已查询到 {{ lastOutput }}，也可以引用 outputAlias 字段。"
      />
    </el-form-item>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { AnswerNodeConfig, CanvasNodeData } from '@/types/studio'

const props = defineProps<{
  data: CanvasNodeData
}>()

const config = computed<AnswerNodeConfig>(() => {
  props.data.answerConfig ||= { template: props.data.template || '{{ lastOutput }}' }
  props.data.template = props.data.answerConfig.template
  props.data.writeToAnswer = true
  return props.data.answerConfig
})
</script>
