<template>
  <ConfigSection
    title="异常处理"
    :description="summary"
    collapsible
  >
    <div class="node-specific-panel contract-panel error-handling-panel">
      <template v-if="retryable">
        <el-form-item label="失败重试">
          <el-switch v-model="retryEnabled" inline-prompt active-text="开" inactive-text="关" />
        </el-form-item>
        <template v-if="retryEnabled">
          <el-form-item label="最多尝试">
            <el-input-number v-model="retryMaxAttempts" :min="1" :max="5" />
            <span class="hint-inline">次（含第一次）</span>
          </el-form-item>
          <el-form-item label="间隔">
            <el-input-number v-model="retryBackoffSeconds" :min="0" :max="10" :step="0.5" />
            <span class="hint-inline">秒</span>
          </el-form-item>
        </template>
      </template>
      <el-form-item v-if="timeoutSupported" label="超时">
        <el-input-number v-model="timeoutSeconds" :min="0" :max="600" />
        <span class="hint-inline">秒（0 表示不限制）</span>
      </el-form-item>
      <el-form-item label="失败时">
        <el-radio-group v-model="strategy">
          <el-radio value="TERMINATE">停止或走失败连线</el-radio>
          <el-radio value="CONTINUE">用默认值继续</el-radio>
          <el-radio value="FALLBACK">跳到指定节点</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item v-if="strategy === 'CONTINUE'" label="默认输出">
        <el-input
          v-model="defaultOutputText"
          type="textarea"
          :rows="3"
          placeholder='JSON 对象，例如 {"found": false}'
        />
        <div v-if="defaultOutputError" class="hint is-error">{{ defaultOutputError }}</div>
      </el-form-item>
      <el-form-item v-if="strategy === 'FALLBACK'" label="跳到">
        <el-select v-model="fallbackNodeId" filterable placeholder="选择失败后执行的节点" style="width: 100%">
          <el-option v-for="item in fallbackOptions" :key="item.id" :label="item.label" :value="item.id" />
        </el-select>
      </el-form-item>
      <div class="hint">
        「停止或走失败连线」：画布上从本节点连出「失败时」连线就走那条线，否则整个流程停止。
        <template v-if="businessCall">业务调用超时后结果未知（可能已经执行），一律停止，不会用默认值继续或重试。</template>
      </div>
    </div>
  </ConfigSection>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import ConfigSection from './ConfigSection.vue'
import type { CanvasNode, CanvasNodeData, StudioErrorPolicy, StudioRetryPolicy } from '@/types/studio'
import { studioNodeRetryable } from '@/utils/studioNodeRegistry'

const props = defineProps<{
  data: CanvasNodeData
  nodeId?: string
  canvasNodes?: CanvasNode[]
}>()

const TIMEOUT_UNSUPPORTED = new Set(['start', 'end', 'userInput', 'interaction', 'approval', 'pageAction', 'answer'])

const retryable = computed(() => studioNodeRetryable(props.data.kind))
const timeoutSupported = computed(() => !TIMEOUT_UNSUPPORTED.has(props.data.kind))
const businessCall = computed(() => ['tool', 'http', 'mcp'].includes(props.data.kind))

const DEFAULT_RETRY: StudioRetryPolicy = { enabled: false, maxAttempts: 2, backoffMs: 1000 }

/** Read-only view: opening the panel must never write defaults into the node. */
const retry = computed<StudioRetryPolicy>(() => ({ ...DEFAULT_RETRY, ...(props.data.retry || {}) }))
const policy = computed<StudioErrorPolicy>(() => ({ strategy: 'TERMINATE', ...(props.data.errorPolicy || {}) }))

function writeRetry(patch: Partial<StudioRetryPolicy>) {
  props.data.retry = { ...DEFAULT_RETRY, ...(props.data.retry || {}), ...patch }
}
function writePolicy(mutate: (target: StudioErrorPolicy) => void) {
  const next: StudioErrorPolicy = { strategy: 'TERMINATE', ...(props.data.errorPolicy || {}) }
  mutate(next)
  props.data.errorPolicy = next
}

const retryEnabled = computed({
  get: () => !!retry.value.enabled,
  set: (value: boolean) => writeRetry({ enabled: value }),
})
const retryMaxAttempts = computed({
  get: () => retry.value.maxAttempts || 2,
  set: (value: number | undefined) => writeRetry({ maxAttempts: Math.max(1, Math.min(5, value || 1)) }),
})
const retryBackoffSeconds = computed({
  get: () => Math.round((retry.value.backoffMs || 0) / 100) / 10,
  set: (value: number | undefined) => writeRetry({ backoffMs: Math.round((value || 0) * 1000) }),
})
const strategy = computed({
  get: () => policy.value.strategy || 'TERMINATE',
  set: (value: StudioErrorPolicy['strategy']) => writePolicy((target) => { target.strategy = value }),
})
const fallbackNodeId = computed({
  get: () => policy.value.fallbackNodeId,
  set: (value: string | undefined) => writePolicy((target) => { target.fallbackNodeId = value }),
})

const timeoutSeconds = computed({
  get: () => Math.round((policy.value.timeoutMs || 0) / 1000),
  set: (value: number | undefined) => {
    const seconds = Math.max(0, Math.min(600, Math.round(value || 0)))
    if (seconds === 0 && !props.data.errorPolicy?.timeoutMs) return
    writePolicy((target) => {
      if (seconds === 0) delete target.timeoutMs
      else target.timeoutMs = seconds * 1000
    })
  },
})

const defaultOutputText = ref('')
const defaultOutputError = ref('')
watch(
  () => props.data,
  () => {
    const value = props.data.errorPolicy?.defaultOutput
    defaultOutputText.value = value ? JSON.stringify(value, null, 2) : ''
    defaultOutputError.value = ''
  },
  { immediate: true },
)
watch(defaultOutputText, (text) => {
  const trimmed = text.trim()
  if (!trimmed) {
    if (props.data.errorPolicy?.defaultOutput !== undefined) {
      writePolicy((target) => { delete target.defaultOutput })
    }
    defaultOutputError.value = ''
    return
  }
  try {
    const parsed = JSON.parse(trimmed)
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
      defaultOutputError.value = '默认输出必须是 JSON 对象'
      return
    }
    if (JSON.stringify(parsed) !== JSON.stringify(props.data.errorPolicy?.defaultOutput)) {
      writePolicy((target) => { target.defaultOutput = parsed })
    }
    defaultOutputError.value = ''
  } catch {
    defaultOutputError.value = 'JSON 格式不正确'
  }
})

const fallbackOptions = computed(() => (props.canvasNodes || [])
  .filter((node) => node.id !== props.nodeId && node.data?.kind !== 'start')
  .map((node) => ({ id: node.id, label: `${node.data?.label || node.id} · ${node.id}` })))

const summary = computed(() => {
  const parts: string[] = []
  if (retryable.value && retry.value.enabled) parts.push(`重试 ${retry.value.maxAttempts} 次`)
  if (policy.value.timeoutMs) parts.push(`超时 ${Math.round(policy.value.timeoutMs / 1000)} 秒`)
  parts.push(policy.value.strategy === 'CONTINUE'
    ? '失败用默认值继续'
    : policy.value.strategy === 'FALLBACK' ? '失败跳到指定节点' : '失败停止或走失败连线')
  return parts.join(' · ')
})
</script>

<style scoped>
.hint {
  margin-top: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.5;
}
.hint.is-error {
  color: var(--el-color-danger);
}
.hint-inline {
  margin-left: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
