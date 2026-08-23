<template>
  <el-card v-if="job" shadow="never" class="document-job-card">
    <template #header>
      <div class="job-card-header">
        <div>
          <div class="job-card-title">{{ title }}</div>
          <div class="job-file-name">{{ job.fileName }}</div>
        </div>
        <div class="job-status-tags">
          <el-tag size="small" effect="plain">{{ documentProviderLabel(job.providerType) }}</el-tag>
          <el-tag size="small" effect="plain" :type="documentStatusTone(job.status)">
            {{ documentStatusLabel(job.status) }}
          </el-tag>
        </div>
      </div>
    </template>

    <el-steps
      :active="documentJobStep(job)"
      :process-status="job.status === 'FAILED' ? 'error' : 'process'"
      finish-status="success"
      align-center
      class="job-steps"
    >
      <el-step title="已接收" />
      <el-step title="文档解析" />
      <el-step :title="job.autoCommit ? '索引入库' : '等待确认'" />
      <el-step title="完成" />
    </el-steps>

    <div class="job-facts">
      <div><span>任务 ID</span><code>{{ job.jobId }}</code></div>
      <div><span>格式</span><strong>{{ job.fileType.toUpperCase() }}</strong></div>
      <div><span>Provider 版本</span><strong>{{ job.providerVersion || '等待解析结果' }}</strong></div>
      <div><span>当前阶段</span><strong>{{ job.stage }}</strong></div>
      <div><span>解析尝试</span><strong>{{ job.attemptCount }}/{{ job.maxAttempts }}</strong></div>
      <div><span>已用时间</span><strong>{{ formatDocumentJobDuration(job) }}</strong></div>
    </div>

    <el-alert
      v-if="job.errorCode || job.errorMessage"
      type="error"
      :closable="false"
      show-icon
      class="job-alert"
      :title="job.errorCode || 'DOCUMENT_IMPORT_FAILED'"
      :description="job.errorMessage || '文档处理失败，请重试或检查文件内容。'"
    />
    <el-alert
      v-if="clientError"
      type="warning"
      :closable="false"
      show-icon
      class="job-alert"
      title="页面刷新已暂停"
      :description="clientError"
    />

    <div v-if="showActions" class="job-actions">
      <el-button
        v-if="clientError && !isDocumentJobTerminal(job.status)"
        type="primary"
        plain
        size="small"
        :loading="busy"
        @click="$emit('refresh')"
      >
        继续刷新
      </el-button>
      <el-button
        v-if="canRetryDocumentJob(job.status)"
        type="primary"
        size="small"
        :loading="busy"
        @click="$emit('retry')"
      >
        {{ job.status === 'RETRY_WAIT' ? '立即重试' : '重试任务' }}
      </el-button>
      <el-button
        v-if="canCancelDocumentJob(job.status)"
        size="small"
        :loading="busy"
        @click="$emit('cancel')"
      >
        取消任务
      </el-button>
      <el-button
        v-if="dismissible && isDocumentJobTerminal(job.status)"
        link
        size="small"
        @click="$emit('dismiss')"
      >
        关闭记录
      </el-button>
    </div>
  </el-card>
</template>

<script setup lang="ts">
import type { DocumentImportJob } from '@/types/import'
import {
  canCancelDocumentJob,
  canRetryDocumentJob,
  documentJobStep,
  documentProviderLabel,
  documentStatusLabel,
  documentStatusTone,
  formatDocumentJobDuration,
  isDocumentJobTerminal,
} from '@/utils/documentImport'

withDefaults(defineProps<{
  job: DocumentImportJob | null
  title?: string
  busy?: boolean
  clientError?: string
  showActions?: boolean
  dismissible?: boolean
}>(), {
  title: '文档导入任务',
  busy: false,
  clientError: '',
  showActions: true,
  dismissible: false,
})

defineEmits<{
  refresh: []
  retry: []
  cancel: []
  dismiss: []
}>()
</script>

<style scoped lang="scss">
.document-job-card {
  margin-top: 16px;
}

.job-card-header,
.job-status-tags,
.job-actions {
  display: flex;
  align-items: center;
}

.job-card-header {
  justify-content: space-between;
  gap: 16px;
}

.job-card-title {
  font-weight: 650;
  color: var(--text-primary);
}

.job-file-name {
  margin-top: 3px;
  font-size: 12px;
  color: var(--text-muted);
}

.job-status-tags,
.job-actions {
  gap: 8px;
}

.job-steps {
  margin: 4px 0 20px;
}

.job-facts {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px 18px;
  padding: 14px;
  border: 1px solid var(--border-glass);
  border-radius: var(--radius-md);
  background: var(--bg-tertiary);
}

.job-facts > div {
  min-width: 0;
}

.job-facts span {
  display: block;
  margin-bottom: 3px;
  font-size: 11px;
  color: var(--text-muted);
}

.job-facts strong,
.job-facts code {
  display: block;
  overflow: hidden;
  font-size: 12px;
  color: var(--text-secondary);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.job-alert,
.job-actions {
  margin-top: 12px;
}

.job-actions {
  justify-content: flex-end;
}

@media (max-width: 1100px) {
  .job-facts {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
