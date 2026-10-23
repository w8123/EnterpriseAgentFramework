<template>
  <div class="node-specific-panel">
    <ConfigSection title="输出转换" description="用表达式生成所需的输出字段">
      <LiquidGlassNotice
        title="当前运行时使用安全表达式模式：不会执行任意脚本，只会把表达式解析为输出字段。"
        type="info"
        class="node-config-alert"
        source="backdrop"
      />
      <el-form-item label="说明">
        <el-input
          v-model="config.code"
          type="textarea"
          :rows="4"
          placeholder="记录转换意图，真正输出在下方字段中配置。"
        />
      </el-form-item>
      <el-form-item label="输出字段">
        <el-input
          :model-value="formatMap(config.outputs)"
          type="textarea"
          :rows="6"
          placeholder="result = lastOutput&#10;customerId = params.customerId"
          @update:model-value="config.outputs = parseMap($event)"
        />
      </el-form-item>
    </ConfigSection>
  </div>
</template>

<script setup lang="ts">
import ConfigSection from './ConfigSection.vue'
import { computed } from 'vue'
import type { CanvasNodeData, CodeNodeConfig } from '@/types/studio'
import { formatMap, parseMap } from './panelUtils'
import { LiquidGlassNotice } from '@/components/common/liquid-glass'

const props = defineProps<{
  data: CanvasNodeData
}>()

const config = computed<CodeNodeConfig>(() => {
  props.data.codeConfig ||= {
    language: 'expression',
    code: '// Safe expression mode',
    outputs: { result: 'lastOutput' },
  }
  return props.data.codeConfig
})
</script>
