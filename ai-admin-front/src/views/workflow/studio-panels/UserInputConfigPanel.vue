<template>
  <div class="node-specific-panel">
    <el-divider>用户输入</el-divider>
    <el-alert
      class="node-config-alert"
      title="这些字段会在运行时写入 params，后续节点可使用 params.question 引用。"
      type="info"
      :closable="false"
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
        <el-option label="string" value="string" />
        <el-option label="number" value="number" />
        <el-option label="integer" value="integer" />
        <el-option label="boolean" value="boolean" />
        <el-option label="object" value="object" />
        <el-option label="array" value="array" />
        <el-option label="file" value="file" />
      </el-select>
      <el-switch v-model="field.required" active-text="必填" />
      <el-input v-model="field.description" placeholder="展示名称 / 说明" />
      <el-input v-model="field.defaultValue" placeholder="默认值" />
      <el-button text type="danger" @click="removeInputField(index)">删除</el-button>
    </div>
    <div v-if="!config.fields.length" class="panel-empty-row">
      还没有输入字段，发布前至少需要配置一个字段。
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, watch } from 'vue'
import type { CanvasNodeData, UserInputNodeConfig } from '@/types/studio'
import { userInputOutputPorts } from '@/utils/studio'

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

function addInputField() {
  config.value.fields.push({
    name: `field_${config.value.fields.length + 1}`,
    type: 'string',
    required: false,
    description: '',
  })
}

function removeInputField(index: number) {
  config.value.fields.splice(index, 1)
}
</script>
