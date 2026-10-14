<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { platformSessionId } from '@/auth/platformSession'
import { getCapabilityEnrichment } from '@/api/semanticDoc'
import { BUSINESS_METHOD_CATALOG_PATH, httpApiDetailLocation } from '../../businessCapabilityRoutes'

const props = defineProps<{ projectId: number; observationId: number; canRead: boolean }>()
const route = useRoute()
const router = useRouter()
const loading = ref(false)
const error = ref('')
let sequence = 0
let disposed = false
watch(() => [props.projectId, props.observationId, props.canRead, platformSessionId.value], () => {
  sequence++; loading.value = false; error.value = ''
}, { flush: 'sync' })
onBeforeUnmount(() => { disposed = true; sequence++ })

async function openOwner() {
  if (disposed || loading.value || !props.canRead || !Number.isSafeInteger(props.projectId) || props.projectId <= 0
    || !Number.isSafeInteger(props.observationId) || props.observationId <= 0) return
  const expected = ++sequence
  loading.value = true; error.value = ''
  try {
    const { data } = await getCapabilityEnrichment(props.projectId, 'SOURCE_OBSERVATION', props.observationId)
    if (disposed || expected !== sequence) return
    if (data.projectId !== props.projectId || !Number.isSafeInteger(data.subjectId) || data.subjectId <= 0
      || !['BUSINESS_METHOD', 'HTTP_API'].includes(data.subjectType) || !data.subjectKey?.trim()) {
      error.value = '未能确认唯一对应能力，请核对来源关联。'; return
    }
    const query = { ...route.query, projectId: String(props.projectId), subjectType: data.subjectType,
      subjectId: String(data.subjectId), subjectKey: data.subjectKey }
    await router.push(data.subjectType === 'HTTP_API' ? httpApiDetailLocation(data.subjectId, query)
      : { path: BUSINESS_METHOD_CATALOG_PATH, query })
  } catch {
    if (!disposed && expected === sequence) error.value = '对应能力读取失败，请重试。'
  } finally {
    if (!disposed && expected === sequence) loading.value = false
  }
}
</script>

<template>
  <span class="source-owner-link">
    <el-button link type="primary" :loading="loading" :disabled="!canRead" @click="openOwner">查看对应能力</el-button>
    <span v-if="error" class="source-owner-link__message" role="status">{{ error }}</span>
    <span v-else-if="!canRead" class="source-owner-link__message">当前账号无权读取能力目录</span>
  </span>
</template>

<style scoped lang="scss">
.source-owner-link { display: inline-flex; flex-direction: column; align-items: flex-start; min-width: 0; }
.source-owner-link__message { color: var(--text-secondary); font-size: 12px; line-height: 1.5; overflow-wrap: anywhere; text-align: left; }
</style>
