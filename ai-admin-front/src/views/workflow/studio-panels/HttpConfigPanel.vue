<template>
  <div class="node-specific-panel">
    <el-divider>接口请求</el-divider>
    <el-form-item label="请求方法">
      <el-select v-model="config.method" style="width: 100%">
        <el-option v-for="method in methods" :key="method" :label="method" :value="method" />
      </el-select>
    </el-form-item>
    <el-form-item label="URL">
      <el-input v-model="config.url" placeholder="https://api.example.com/orders/{{ params.orderId }}" />
    </el-form-item>
    <el-form-item label="查询参数">
      <el-input :model-value="formatMap(config.queryParams)" type="textarea" :rows="4" placeholder="page = 1&#10;keyword = {{ input }}" @update:model-value="config.queryParams = parseMap($event)" />
    </el-form-item>
    <el-form-item label="请求头">
      <el-input :model-value="formatMap(config.headers)" type="textarea" :rows="4" placeholder="Content-Type = application/json" @update:model-value="config.headers = parseMap($event)" />
    </el-form-item>
    <el-form-item label="请求体类型">
      <el-segmented v-model="config.bodyType" :options="['none', 'json', 'text']" />
    </el-form-item>
    <el-form-item v-if="config.bodyType !== 'none'" label="请求体">
      <el-input v-model="config.body" type="textarea" :rows="6" placeholder="{ &quot;query&quot;: &quot;{{ input }}&quot; }" />
    </el-form-item>
    <el-form-item label="超时时间">
      <el-input-number v-model="config.timeoutMs" :min="1000" :max="120000" :step="1000" />
    </el-form-item>
    <el-form-item label="凭据引用">
      <CredentialSelect
        v-model="config.credentialRef"
        :credentials="credentialOptions"
        :project-id="projectId"
        :project-code="projectCode"
        @created="$emit('credentialCreated', $event)"
      />
    </el-form-item>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { CanvasNodeData, HttpNodeConfig } from '@/types/studio'
import type { WorkflowCredential } from '@/types/workflowCredential'
import { formatMap, parseMap } from './panelUtils'
import CredentialSelect from './CredentialSelect.vue'

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
</script>
