<template>
  <div class="node-specific-panel">
    <ConfigSection title="回复内容" description="把模板内容写入最终回答">
      <el-form-item label="回复模板">
        <el-input
          v-model="config.template"
          type="textarea"
          :rows="7"
          placeholder="例如：已查询到 {{ lastOutput }}，也可以引用 outputAlias 字段。"
        />
      </el-form-item>
      <el-form-item label="直达回答">
        <el-switch v-model="config.directAnswer" />
        <div class="answer-direct-hint">
          开启后，只读流程命中时助手会把这段内容原样作为最终回答，不再多轮推理和改写，更快也更省 token。请确认模板内容可以直接给员工看。
        </div>
      </el-form-item>
    </ConfigSection>
  </div>
</template>

<script setup lang="ts">
import ConfigSection from './ConfigSection.vue'
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

<style scoped lang="scss">
.answer-direct-hint {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--text-secondary);
}
</style>
