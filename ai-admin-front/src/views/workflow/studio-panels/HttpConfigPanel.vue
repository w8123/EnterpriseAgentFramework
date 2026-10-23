<template>
  <div class="node-specific-panel">
    <LiquidGlassNotice
      v-if="marketRef"
      :title="`来自 API 市场 · ${marketRef.entryKey || '外部 API'}`"
      :description="marketReferenceDescription"
      type="info"
      class="market-reference-alert"
      source="backdrop"
    />
    <ConfigSection title="请求地址">
      <el-form-item label="请求方法">
        <el-select v-model="config.method" style="width: 100%">
          <el-option v-for="method in methods" :key="method" :label="method" :value="method" />
        </el-select>
      </el-form-item>
      <el-form-item label="URL">
        <el-input v-model="config.url" placeholder="https://api.example.com/orders/{{ params.orderId }}" />
      </el-form-item>
    </ConfigSection>
    <ConfigSection title="查询参数" collapsible :default-open="Object.keys(config.queryParams || {}).length > 0">
      <el-form-item label="查询参数">
        <el-input
          :model-value="formatMap(config.queryParams)"
          type="textarea"
          :rows="4"
          placeholder="page = 1&#10;keyword = {{ input }}"
          @update:model-value="config.queryParams = parseMap($event)"
        />
      </el-form-item>
    </ConfigSection>
    <ConfigSection title="请求头" collapsible :default-open="Object.keys(config.headers || {}).length > 0">
      <el-form-item label="请求头">
        <el-input
          :model-value="formatMap(config.headers)"
          type="textarea"
          :rows="4"
          placeholder="Content-Type = application/json"
          @update:model-value="config.headers = parseMap($event)"
        />
      </el-form-item>
    </ConfigSection>
    <ConfigSection title="请求体">
      <el-form-item label="请求体类型">
        <el-segmented
          v-model="config.bodyType"
          :options="[
            { label: '无', value: 'none' },
            { label: 'JSON', value: 'json' },
            { label: '文本', value: 'text' },
          ]"
        />
      </el-form-item>
      <el-form-item v-if="config.bodyType !== 'none'" label="请求体">
        <el-input v-model="config.body" type="textarea" :rows="6" placeholder='{ "query": "{{ input }}" }' />
      </el-form-item>
    </ConfigSection>
    <ConfigSection title="认证与超时">
      <el-form-item label="超时时间（毫秒）">
        <el-input-number v-model="config.timeoutMs" :min="1000" :max="120000" :step="1000" />
      </el-form-item>
      <el-form-item label="凭据引用">
        <CredentialSelect
          :model-value="config.credentialRef"
          :credentials="credentialOptions"
          :project-id="projectId"
          :project-code="projectCode"
          @update:model-value="applyCredentialRef"
          @created="$emit('credentialCreated', $event)"
        />
      </el-form-item>
    </ConfigSection>
  </div>
</template>

<script setup lang="ts">
import ConfigSection from './ConfigSection.vue'
import { computed } from 'vue'
import type { CanvasNodeData, HttpNodeConfig } from '@/types/studio'
import type { WorkflowCredential } from '@/types/workflowCredential'
import { formatMap, parseMap } from './panelUtils'
import CredentialSelect from './CredentialSelect.vue'
import { LiquidGlassNotice } from '@/components/common/liquid-glass'

const props = defineProps<{
  data: CanvasNodeData
  credentialOptions: WorkflowCredential[]
  projectId?: number | null
  projectCode?: string | null
}>()
defineEmits<{
  credentialCreated: [credential: WorkflowCredential]
}>()
const methods = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE']

const marketRef = computed(() => {
  const value = props.data.marketRef
  return value && typeof value === 'object' ? value : null
})

const marketReferenceDescription = computed(() => {
  const value = marketRef.value
  if (!value) return ''
  const identity = [value.versionKey, value.operationKey].filter(Boolean).join(' · ')
  const credential = value.credentialRequired ? '需要 Runtime 凭据' : '无需凭据'
  return [identity, credential, '目录引用会随 Workflow 版本保存'].filter(Boolean).join('；')
})

const config = computed<HttpNodeConfig>(() => {
  props.data.httpConfig ||= {
    method: 'GET',
    url: '',
    queryParams: {},
    headers: {},
    bodyType: 'none',
    body: '',
    timeoutMs: 30000,
    credentialRef: '',
  }
  return props.data.httpConfig
})

function applyCredentialRef(value?: string) {
  config.value.credentialRef = value || ''
  if (!marketRef.value?.credentialRequired) return
  props.data.needsConfiguration = !value
  props.data.placeholderReason = value ? undefined : '请在 Workflow Studio 中选择项目凭据'
}
</script>

<style scoped>
.market-reference-alert {
  margin-bottom: 16px;
}
</style>
