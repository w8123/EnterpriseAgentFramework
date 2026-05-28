<template>
  <div class="node-specific-panel">
    <el-divider>页面动作</el-divider>
    <el-alert
      title="页面动作只会请求宿主业务页面执行已注册的 actionKey，不会下发任意 JS。"
      type="info"
      :closable="false"
    />
    <el-form label-position="top" class="page-action-form">
      <el-form-item label="动作标识 actionKey">
        <el-input v-model="config.actionKey" placeholder="例如 team.openDetail / order.refreshList" @change="sync" />
      </el-form-item>
      <el-form-item label="展示标题">
        <el-input v-model="config.title" placeholder="例如 打开班组详情" @change="sync" />
      </el-form-item>
      <el-form-item label="执行前确认">
        <el-switch v-model="config.confirm" @change="sync" />
      </el-form-item>
      <el-form-item label="输出别名">
        <el-input v-model="config.outputAlias" placeholder="page_action_result" @change="sync" />
      </el-form-item>
      <el-form-item label="动作参数 JSON">
        <el-input
          v-model="argsText"
          type="textarea"
          :rows="6"
          placeholder='例如 { "teamId": "bzsdk__page_result.data.records.0.id" }'
          @change="syncArgs"
        />
      </el-form-item>
      <el-alert
        v-if="argsError"
        :title="argsError"
        type="warning"
        :closable="false"
      />
    </el-form>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { CanvasNodeData, PageActionNodeConfig } from '@/types/studio'

const props = defineProps<{
  data: CanvasNodeData
}>()

const argsText = ref('{}')
const argsError = ref('')

const config = computed<PageActionNodeConfig>(() => {
  props.data.pageActionConfig ||= {
    actionKey: '',
    title: props.data.label || '页面动作',
    confirm: true,
    args: {},
    outputAlias: props.data.outputAlias || 'page_action_result',
    metadata: {},
  }
  props.data.pageActionConfig.outputAlias ||= props.data.outputAlias || 'page_action_result'
  props.data.outputAlias = props.data.pageActionConfig.outputAlias
  return props.data.pageActionConfig
})

watch(
  () => config.value.args,
  (args) => {
    argsText.value = JSON.stringify(args || {}, null, 2)
  },
  { immediate: true, deep: true },
)

function sync() {
  config.value.outputAlias = config.value.outputAlias || 'page_action_result'
  props.data.outputAlias = config.value.outputAlias
  props.data.outputs = [{ id: config.value.outputAlias, name: config.value.outputAlias, type: 'object' }]
}

function syncArgs() {
  try {
    const parsed = JSON.parse(argsText.value || '{}')
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
      throw new Error('动作参数必须是 JSON 对象')
    }
    const next: Record<string, string> = {}
    Object.entries(parsed as Record<string, unknown>).forEach(([key, value]) => {
      next[key] = String(value ?? '')
    })
    config.value.args = next
    argsError.value = ''
    sync()
  } catch (error) {
    argsError.value = error instanceof Error ? error.message : '动作参数 JSON 格式不正确'
  }
}
</script>

<style scoped>
.page-action-form {
  display: grid;
  gap: 12px;
}
</style>
