<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { Check, Download, Refresh, VideoPlay, Warning } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  downloadManagedAiCodingArtifact,
  getManagedAiCodingExecution,
  resolveManagedAiCodingApproval,
  startManagedAiCodingExecution,
  streamManagedAiCodingExecution,
} from '@/api/aiCodingTasks'
import type {
  AiCodingManagedExecutionDetail,
  AiCodingTask,
  ManagedExecutionArtifact,
  ManagedExecutionStatus,
} from '@/types/aiCodingTask'

const props = defineProps<{
  task: AiCodingTask
}>()

const emit = defineEmits<{
  refreshTask: [taskId: string]
}>()

const detail = ref<AiCodingManagedExecutionDetail | null>(null)
const loading = ref(false)
const actionBusy = ref(false)
const loadError = ref('')
const previews = ref<Record<string, string>>({})
let pollTimer: ReturnType<typeof setTimeout> | null = null
let streamAbort: AbortController | null = null
let lastStreamFingerprint = ''
let generation = 0
const ARTIFACT_PREVIEW_MAX_BYTES = 512 * 1024

const execution = computed(() => detail.value?.execution || null)
const approval = computed(() => detail.value?.approval || null)
const artifacts = computed(() => detail.value?.artifacts || [])
const active = computed(() => execution.value
  ? !['SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED'].includes(execution.value.status)
  : false)

watch(
  () => [
    props.task.taskId,
    props.task.executionMode,
    props.task.managedExecutionId,
  ] as const,
  async () => {
    generation += 1
    stopPolling()
    stopStream()
    detail.value = null
    previews.value = {}
    loadError.value = ''
    if (
      props.task.executionMode === 'MANAGED_SANDBOX'
      && props.task.managedExecutionId
    ) {
      await load(generation)
    }
  },
  { immediate: true },
)

onUnmounted(() => {
  generation += 1
  stopPolling()
  stopStream()
})

async function load(
  expectedGeneration = generation,
  quiet = false,
  manageTransport = true,
) {
  if (!props.task.managedExecutionId || loading.value) return
  if (!quiet) loading.value = true
  try {
    const response = await getManagedAiCodingExecution(props.task.taskId)
    if (expectedGeneration !== generation) return
    detail.value = response.data
    loadError.value = ''
    emit('refreshTask', props.task.taskId)
    if (manageTransport) {
      if (active.value) startStream(expectedGeneration)
      else stopStream()
    }
  } catch (error) {
    if (expectedGeneration !== generation) return
    loadError.value = (error as Error).message || '隔离执行状态暂时不可用'
    if (manageTransport) schedulePoll(expectedGeneration)
  } finally {
    if (!quiet && expectedGeneration === generation) loading.value = false
  }
}

function startStream(expectedGeneration: number) {
  if (
    expectedGeneration !== generation
    || !active.value
    || !props.task.managedExecutionId
  ) return
  stopStream()
  stopPolling()
  const controller = new AbortController()
  streamAbort = controller
  lastStreamFingerprint = ''
  void streamManagedAiCodingExecution(
    props.task.taskId,
    (snapshot) => {
      if (expectedGeneration !== generation || controller.signal.aborted) return
      const fingerprint = [
        snapshot.status,
        snapshot.cleanupStatus,
        snapshot.pendingInteractionId || '',
        snapshot.lastEventSequence,
        snapshot.approvalCount,
      ].join('|')
      if (fingerprint === lastStreamFingerprint) return
      lastStreamFingerprint = fingerprint
      if (detail.value) {
        detail.value.execution.status = snapshot.status
        detail.value.execution.cleanupStatus = snapshot.cleanupStatus
        detail.value.execution.pendingInteractionId = snapshot.pendingInteractionId
        detail.value.execution.lastEventSequence = snapshot.lastEventSequence
        detail.value.execution.approvalCount = snapshot.approvalCount
      }
      void load(expectedGeneration, true, false)
    },
    controller.signal,
  ).then(() => {
    if (
      expectedGeneration === generation
      && !controller.signal.aborted
      && active.value
    ) schedulePoll(expectedGeneration)
  }).catch((error) => {
    if (controller.signal.aborted || expectedGeneration !== generation) return
    loadError.value = (error as Error).message || '隔离执行进度流暂时不可用'
    schedulePoll(expectedGeneration)
  })
}

function schedulePoll(expectedGeneration: number) {
  stopPolling()
  if (expectedGeneration !== generation || !active.value) return
  pollTimer = setTimeout(() => load(expectedGeneration, true), 3000)
}

function stopPolling() {
  if (pollTimer) clearTimeout(pollTimer)
  pollTimer = null
}

function stopStream() {
  streamAbort?.abort()
  streamAbort = null
  lastStreamFingerprint = ''
}

async function start() {
  actionBusy.value = true
  try {
    const response = await startManagedAiCodingExecution(props.task.taskId)
    detail.value = response.data
    loadError.value = ''
    ElMessage.success('隔离执行已启动')
    emit('refreshTask', props.task.taskId)
    startStream(generation)
  } catch (error) {
    ElMessage.error((error as Error).message || '启动隔离执行失败')
  } finally {
    actionBusy.value = false
  }
}

async function decide(decision: 'APPROVE' | 'REJECT') {
  if (!approval.value) return
  const approving = decision === 'APPROVE'
  await ElMessageBox.confirm(
    approving
      ? '只批准这一次操作。请确认下方命令或文件变更属于当前任务范围。'
      : '拒绝后，本次操作不会执行；Codex 可能改用其他路径或结束任务。',
    approving ? '批准一次' : '拒绝操作',
    {
      confirmButtonText: approving ? '批准一次' : '确认拒绝',
      cancelButtonText: '返回',
      type: approving ? 'warning' : 'info',
    },
  )
  actionBusy.value = true
  try {
    const response = await resolveManagedAiCodingApproval(
      props.task.taskId,
      approval.value.interactionId,
      {
        decision,
        idempotencyKey: `${approval.value.interactionId}:${decision.toLowerCase()}`,
      },
    )
    detail.value = response.data
    ElMessage.success(approving ? '已批准一次' : '已拒绝该操作')
    emit('refreshTask', props.task.taskId)
    startStream(generation)
  } catch (error) {
    ElMessage.error((error as Error).message || '写入审批结果失败')
  } finally {
    actionBusy.value = false
  }
}

async function previewArtifact(artifact: ManagedExecutionArtifact) {
  if (previews.value[artifact.artifactId]) {
    const next = { ...previews.value }
    delete next[artifact.artifactId]
    previews.value = next
    return
  }
  if (artifact.sizeBytes > ARTIFACT_PREVIEW_MAX_BYTES) {
    ElMessage.warning('该证据超过 512 KiB 预览上限，请下载后查看')
    return
  }
  actionBusy.value = true
  try {
    const response = await downloadManagedAiCodingArtifact(
      props.task.taskId,
      artifact.artifactId,
    )
    if (response.data.size > ARTIFACT_PREVIEW_MAX_BYTES) {
      throw new Error('证据响应超过 512 KiB 预览上限')
    }
    previews.value = {
      ...previews.value,
      [artifact.artifactId]: await response.data.text(),
    }
  } catch (error) {
    ElMessage.error((error as Error).message || '读取证据失败')
  } finally {
    actionBusy.value = false
  }
}

async function downloadArtifact(artifact: ManagedExecutionArtifact) {
  actionBusy.value = true
  try {
    const response = await downloadManagedAiCodingArtifact(
      props.task.taskId,
      artifact.artifactId,
    )
    const url = URL.createObjectURL(response.data)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = artifactFilename(artifact)
    anchor.click()
    setTimeout(() => URL.revokeObjectURL(url), 0)
  } catch (error) {
    ElMessage.error((error as Error).message || '下载证据失败')
  } finally {
    actionBusy.value = false
  }
}

function statusLabel(status?: ManagedExecutionStatus) {
  if (!status) return '等待启动'
  return {
    REQUESTED: '已请求',
    QUEUED: '排队中',
    PROVISIONING: '创建沙箱',
    RUNNING: '执行中',
    WAITING_APPROVAL: '等待审批',
    WAITING_USER: '等待用户',
    FINALIZING: '校验证据',
    CANCELLING: '取消中',
    SUCCEEDED: '执行成功',
    FAILED: '执行失败',
    TIMED_OUT: '执行超时',
    CANCELLED: '已取消',
  }[status]
}

function statusTagType(status?: ManagedExecutionStatus) {
  if (status === 'SUCCEEDED') return 'success'
  if (['FAILED', 'TIMED_OUT'].includes(status || '')) return 'danger'
  if (['WAITING_APPROVAL', 'WAITING_USER', 'CANCELLING'].includes(status || '')) {
    return 'warning'
  }
  if (status === 'CANCELLED') return 'info'
  return 'primary'
}

function artifactLabel(type: string) {
  return {
    PATCH: '工作区补丁',
    TEST_REPORT: '测试报告',
    EXECUTION_SUMMARY: '执行摘要',
    EVIDENCE_MANIFEST: '证据清单',
    EVENT_LOG: '事件日志',
  }[type] || type
}

function artifactFilename(artifact: ManagedExecutionArtifact) {
  return {
    PATCH: 'workspace.patch',
    TEST_REPORT: 'test-report.json',
    EXECUTION_SUMMARY: 'execution-summary.json',
    EVIDENCE_MANIFEST: 'evidence-manifest.json',
    EVENT_LOG: 'events.ndjson',
  }[artifact.artifactType] || `${artifact.artifactId}.txt`
}

function formatBytes(bytes: number) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KiB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MiB`
}

function formatRetention(value: string) {
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? '留存时间未知' : `保留至 ${parsed.toLocaleString('zh-CN')}`
}
</script>

<template>
  <section class="managed-execution-panel" v-loading="loading">
    <header>
      <span>
        <small>REACHAI MANAGED EXECUTOR</small>
        <h3>隔离执行与证据</h3>
      </span>
      <div>
        <el-tag
          :type="statusTagType(execution?.status)"
          effect="light"
          round
        >
          {{ statusLabel(execution?.status) }}
        </el-tag>
        <el-button
          v-if="task.managedExecutionId"
          :icon="Refresh"
          circle
          :loading="loading"
          aria-label="刷新隔离执行"
          @click="load(generation)"
        />
      </div>
    </header>

    <el-alert
      v-if="loadError"
      type="warning"
      :closable="false"
      show-icon
      :title="loadError"
    />

    <div v-if="!task.managedExecutionId" class="managed-execution-start">
      <el-icon><VideoPlay /></el-icon>
      <span>
        <strong>任务尚未启动</strong>
        <small>Runtime 会创建一次性工作区；默认关闭网络且不挂载宿主机目录。</small>
      </span>
      <el-button type="primary" :loading="actionBusy" @click="start">
        启动隔离执行
      </el-button>
    </div>

    <template v-else-if="execution">
      <dl class="managed-execution-facts">
        <div>
          <dt>沙箱配置</dt>
          <dd>{{ execution.sandboxProfile === 'ANALYZE_READONLY' ? '只读分析' : '工作区补丁' }}</dd>
        </div>
        <div>
          <dt>清理状态</dt>
          <dd>{{ execution.cleanupStatus }}</dd>
        </div>
        <div>
          <dt>事件序号</dt>
          <dd>#{{ execution.lastEventSequence }}</dd>
        </div>
        <div>
          <dt>最长时限</dt>
          <dd>{{ Math.round(execution.maxWallTimeSeconds / 60) }} 分钟</dd>
        </div>
      </dl>

      <article v-if="approval" class="managed-approval">
        <header>
          <span>
            <el-icon><Warning /></el-icon>
            <strong>需要一次性审批</strong>
          </span>
          <el-tag type="warning" effect="plain">
            {{ approval.uiRequest.approvalKind || '操作' }}
          </el-tag>
        </header>
        <p>{{ approval.uiRequest.message || approval.uiRequest.reason || 'Codex 请求执行当前任务范围内的一次操作。' }}</p>
        <pre v-if="approval.uiRequest.command?.length">{{ approval.uiRequest.command.join(' ') }}</pre>
        <footer>
          <el-button :disabled="actionBusy" @click="decide('REJECT')">
            拒绝
          </el-button>
          <el-button
            type="warning"
            :icon="Check"
            :loading="actionBusy"
            @click="decide('APPROVE')"
          >
            仅批准这一次
          </el-button>
        </footer>
      </article>

      <section class="managed-artifacts">
        <header>
          <span>
            <small>VERIFIED EVIDENCE</small>
            <h4>Runtime 校验的证据包</h4>
          </span>
          <el-tag effect="plain">{{ artifacts.length }} / 5</el-tag>
        </header>
        <p v-if="!artifacts.length" class="managed-empty">
          执行完成并通过独立校验后，这里会出现补丁、测试报告、摘要、清单和事件日志。
        </p>
        <article v-for="artifact in artifacts" :key="artifact.artifactId">
          <header>
            <span>
              <strong>{{ artifactLabel(artifact.artifactType) }}</strong>
              <small>{{ formatBytes(artifact.sizeBytes) }} · {{ artifact.sha256.slice(0, 12) }} · {{ formatRetention(artifact.retentionExpiresAt) }}</small>
            </span>
            <div>
              <el-tag
                :type="artifact.validationStatus === 'VERIFIED' && artifact.scanStatus === 'CLEAN' ? 'success' : 'warning'"
                size="small"
                effect="plain"
              >
                {{ artifact.validationStatus }} / {{ artifact.scanStatus }}
              </el-tag>
              <el-button text :disabled="actionBusy" @click="previewArtifact(artifact)">
                {{ previews[artifact.artifactId] ? '收起' : '预览' }}
              </el-button>
              <el-button
                text
                :icon="Download"
                :disabled="actionBusy"
                @click="downloadArtifact(artifact)"
              >
                下载
              </el-button>
            </div>
          </header>
          <pre v-if="previews[artifact.artifactId]" class="managed-artifact-preview">{{ previews[artifact.artifactId] }}</pre>
        </article>
      </section>
    </template>
  </section>
</template>

<style scoped>
.managed-execution-panel {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 12px;
  padding: 16px;
  border: 1px solid color-mix(in srgb, var(--brand-primary) 22%, var(--border-subtle));
  border-radius: 14px;
  background: linear-gradient(145deg, color-mix(in srgb, var(--brand-primary) 6%, var(--surface-solid-panel)), var(--surface-solid-panel));
}

.managed-execution-panel > header,
.managed-artifacts > header,
.managed-artifacts article > header,
.managed-approval > header,
.managed-approval > footer,
.managed-execution-start {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.managed-execution-panel header span,
.managed-execution-start span {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 3px;
}

.managed-execution-panel header div {
  display: flex;
  align-items: center;
  gap: 8px;
}

.managed-execution-panel h3,
.managed-execution-panel h4,
.managed-execution-panel p,
.managed-execution-panel pre {
  margin: 0;
}

.managed-execution-panel h3 { font-size: 0.9rem; color: var(--text-primary); }
.managed-execution-panel h4 { font-size: 0.82rem; color: var(--text-primary); }
.managed-execution-panel small { color: var(--text-muted); font-size: 0.68rem; }

.managed-execution-facts {
  display: grid;
  margin: 0;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
}

.managed-execution-facts div {
  padding: 10px;
  border-radius: 10px;
  background: var(--surface-solid-control);
}

.managed-execution-facts dt { color: var(--text-muted); font-size: 0.66rem; }
.managed-execution-facts dd { margin: 5px 0 0; color: var(--text-primary); font-size: 0.76rem; font-weight: 700; }

.managed-execution-start,
.managed-approval,
.managed-artifacts article {
  padding: 12px;
  border: 1px solid var(--border-subtle);
  border-radius: 12px;
  background: var(--surface-solid-control);
}

.managed-execution-start > .el-icon { color: var(--brand-primary); font-size: 1.4rem; }
.managed-execution-start span { flex: 1; }

.managed-approval {
  display: flex;
  flex-direction: column;
  gap: 10px;
  border-color: color-mix(in srgb, var(--status-warning) 38%, var(--border-subtle));
}

.managed-approval header span { flex-direction: row; align-items: center; }
.managed-approval p { color: var(--text-secondary); font-size: 0.76rem; line-height: 1.6; }
.managed-approval pre,
.managed-artifact-preview {
  max-height: 360px;
  padding: 10px;
  overflow: auto;
  border-radius: 9px;
  background: var(--surface-solid-overlay);
  color: var(--text-secondary);
  font: 0.7rem/1.55 ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  white-space: pre-wrap;
  word-break: break-word;
}

.managed-approval footer { justify-content: flex-end; }
.managed-artifacts { display: flex; flex-direction: column; gap: 8px; }
.managed-artifacts article { display: flex; flex-direction: column; gap: 8px; }
.managed-empty { padding: 14px; color: var(--text-muted); font-size: 0.74rem; text-align: center; }

@media (max-width: 720px) {
  .managed-execution-facts { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .managed-execution-start { align-items: stretch; flex-direction: column; }
  .managed-artifacts article > header { align-items: flex-start; flex-direction: column; }
}
</style>
