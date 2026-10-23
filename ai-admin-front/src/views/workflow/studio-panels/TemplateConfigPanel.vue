<template>
  <div class="node-specific-panel">
    <ConfigSection title="文本模板" description="引用变量生成文本，供后续节点继续使用">
      <LiquidGlassNotice
        title="生成的文本供后续节点使用；最终回复请使用回复节点。缺失变量会替换为空字符串。"
        type="info"
        class="panel-alert"
        source="backdrop"
      />
      <el-form-item label="模板">
        <el-input
          v-model="templateText"
          type="textarea"
          :rows="8"
          placeholder="例如：订单 {{ var.orderId }} 状态为 {{ lastOutput }}"
        />
      </el-form-item>
      <div class="field-table-head">
        <strong>插入变量</strong>
      </div>
      <div class="variable-chips">
        <el-button
          v-for="option in normalizedVariableOptions.slice(0, 12)"
          :key="option"
          size="small"
          plain
          @click="insertVariable(option)"
        >
          {{ option }}
        </el-button>
      </div>
    </ConfigSection>
  </div>
</template>

<script setup lang="ts">
import ConfigSection from './ConfigSection.vue'
import { computed } from 'vue'
import type { CanvasNodeData, StudioVariableOption } from '@/types/studio'
import { LiquidGlassNotice } from '@/components/common/liquid-glass'

const props = defineProps<{
  data: CanvasNodeData
  variableOptions?: Array<string | StudioVariableOption>
}>()

const templateText = computed({
  get: () => props.data.template || '',
  set: (value: string) => {
    props.data.template = value
    props.data.writeToAnswer = false
  },
})

const normalizedVariableOptions = computed(() => {
  const options = props.variableOptions || []
  return options.map((item) => (typeof item === 'string' ? item : item.value)).filter(Boolean)
})

function insertVariable(path: string) {
  const token = `{{ ${path} }}`
  const current = props.data.template || ''
  props.data.template = current ? `${current}${current.endsWith(' ') ? '' : ' '}${token}` : token
  props.data.writeToAnswer = false
}
</script>

<style scoped>
.panel-alert {
  margin-bottom: 12px;
}
.variable-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
</style>
