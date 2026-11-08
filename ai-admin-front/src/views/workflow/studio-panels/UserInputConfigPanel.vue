<template>
  <div class="node-specific-panel">
    <ConfigSection title="输入参数" description="声明 Workflow 接收的字段与必填规则">
      <LiquidGlassNotice
        class="node-config-alert"
        title="这些字段会在运行时写入 params，后续节点可使用 params.question 引用。"
        type="info"
        source="backdrop"
      />
      <el-form-item label="输出别名">
        <el-input v-model="config.outputAlias" placeholder="params" />
      </el-form-item>
      <div class="field-table-head">
        <strong>输入字段</strong>
        <el-button size="small" type="primary" plain @click="addInputField">添加字段</el-button>
      </div>
      <div v-for="(field, index) in config.fields" :key="index" class="field-row user-input-field-row">
        <el-input v-model="field.name" placeholder="变量名，如 question" />
        <el-select v-model="field.type" style="width: 112px">
          <el-option label="文本" value="string" />
          <el-option label="数字" value="number" />
          <el-option label="整数" value="integer" />
          <el-option label="是/否" value="boolean" />
          <el-option label="对象 JSON" value="object" />
          <el-option label="列表 JSON" value="array" />
          <el-option label="文件链接" value="file" />
        </el-select>
        <el-switch v-model="field.required" active-text="必填" />
        <el-input v-model="field.description" placeholder="说明（员工 AI 工具会看到）" />
        <el-input v-model="field.defaultValue" placeholder="默认值" />
        <el-input
          v-if="field.type === 'string' || field.type === 'number' || field.type === 'integer'"
          :model-value="(field.options || []).map((option) => option.value).join(', ')"
          placeholder="可选值，逗号分隔（留空不限）"
          @change="setOptions(field, $event)"
        />
        <el-button text type="danger" @click="removeInputField(index)">删除</el-button>
      </div>
      <div class="hint">这份表单就是工作流的入参：调试时按它填写，发布给员工 AI 工具时它就是工具的参数说明。</div>
      <div v-if="!config.fields.length" class="panel-empty-row">当前没有输入字段；流程不需要参数时可以留空。</div>
    </ConfigSection>
  </div>
</template>

<script setup lang="ts">
import ConfigSection from './ConfigSection.vue'
import { computed, watch } from 'vue'
import type { CanvasNodeData, StudioFieldSchema, UserInputNodeConfig } from '@/types/studio'
import { userInputOutputPorts } from '@/utils/studio'
import { LiquidGlassNotice } from '@/components/common/liquid-glass'

const props = defineProps<{
  data: CanvasNodeData
}>()

const config = computed<UserInputNodeConfig>(() => {
  props.data.userInputConfig ||= {
    outputAlias: props.data.outputAlias || 'params',
    fields: [{ name: 'question', type: 'string', required: true, description: '用户问题', source: 'input.message' }],
  }
  props.data.userInputConfig.outputAlias ||= 'params'
  props.data.userInputConfig.fields ||= []
  props.data.outputAlias = props.data.userInputConfig.outputAlias
  return props.data.userInputConfig
})

watch(
  () => config.value.outputAlias,
  (value) => {
    const alias = value?.trim() || 'params'
    props.data.userInputConfig!.outputAlias = alias
    props.data.outputAlias = alias
  },
)

watch(
  config,
  (value) => {
    props.data.outputs = userInputOutputPorts(value.fields, value.outputAlias || 'params')
  },
  { deep: true, immediate: true },
)

function setOptions(field: StudioFieldSchema, raw: string | number) {
  const values = String(raw ?? '').split(/[,，]/).map((item) => item.trim()).filter(Boolean)
  const numeric = field.type === 'number' || field.type === 'integer'
  field.options = values.map((value) => ({ label: value, value: numeric && !Number.isNaN(Number(value)) ? Number(value) : value }))
}

function addInputField() {
  config.value.fields.push({
    name: `field_${config.value.fields.length + 1}`,
    type: 'string',
    required: false,
    description: '',
    source: 'input.message',
  })
}

function removeInputField(index: number) {
  config.value.fields.splice(index, 1)
}
</script>
