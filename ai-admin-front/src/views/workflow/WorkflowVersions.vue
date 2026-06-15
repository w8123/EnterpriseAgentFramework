<template>
  <div class="workflow-versions">
    <header class="page-header">
      <div>
        <h1>{{ workflow?.name || 'Workflow Versions' }}</h1>
        <p>{{ workflow?.keySlug || workflowId }}</p>
        <div class="meta-row">
          <el-tag size="small" effect="plain">{{ workflow?.workflowType || 'CHAT' }}</el-tag>
          <el-tag size="small" type="info" effect="plain">{{ workflow?.runtimeType || 'LANGGRAPH4J' }}</el-tag>
          <el-tag size="small" :type="workflow?.status === 'ACTIVE' ? 'success' : 'warning'">
            {{ workflow?.status || 'DRAFT' }}
          </el-tag>
        </div>
      </div>
      <div class="header-actions">
        <el-button :icon="ArrowLeft" @click="router.push(`/workflows/${workflowId}/studio`)">
          Studio
        </el-button>
        <el-button :icon="CircleCheck" :loading="validating" @click="validateRelease">
          Validate
        </el-button>
        <el-button type="primary" :icon="Upload" @click="publishOpen = true">Publish</el-button>
      </div>
    </header>

    <el-alert
      v-if="validation"
      class="validation-alert"
      :type="validation.valid ? 'success' : 'error'"
      :closable="false"
      show-icon
      :title="validation.valid ? 'Workflow is ready to publish' : `${validation.errors.length} release issue(s)`"
    />

    <section v-if="validation && (!validation.valid || validation.warnings.length)" class="validation-panel">
      <div v-for="item in validationItems" :key="`${item.level}-${item.code}-${item.nodeId || ''}`" class="validation-item">
        <el-tag size="small" :type="item.level === 'ERROR' ? 'danger' : 'warning'">{{ item.level }}</el-tag>
        <strong>{{ item.code }}</strong>
        <span v-if="item.nodeId">{{ item.nodeId }}</span>
        <p>{{ item.message }}</p>
      </div>
    </section>

    <el-table :data="versions" v-loading="loading" stripe>
      <el-table-column prop="version" label="Version" min-width="160">
        <template #default="{ row }">
          <strong>{{ row.version }}</strong>
          <el-tag v-if="row.status === 'ACTIVE'" class="active-tag" size="small" type="success">
            ACTIVE
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="rolloutPercent" label="Rollout" width="110">
        <template #default="{ row }">{{ row.rolloutPercent ?? 100 }}%</template>
      </el-table-column>
      <el-table-column prop="publishedBy" label="Published By" min-width="140" />
      <el-table-column prop="publishedAt" label="Published At" min-width="180" />
      <el-table-column prop="note" label="Note" min-width="220" />
      <el-table-column label="Actions" width="160" fixed="right">
        <template #default="{ row }">
          <el-button
            size="small"
            :disabled="row.status === 'ACTIVE'"
            :loading="rollingBackId === row.id"
            @click="rollback(row)"
          >
            Rollback
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="publishOpen" title="Publish Workflow Version" width="460px">
      <el-form :model="publishForm" label-width="110px">
        <el-form-item label="Version">
          <el-input v-model="publishForm.version" placeholder="v1.0.0" />
        </el-form-item>
        <el-form-item label="Rollout">
          <el-input-number v-model="publishForm.rolloutPercent" :min="0" :max="100" />
        </el-form-item>
        <el-form-item label="Published By">
          <el-input v-model="publishForm.publishedBy" placeholder="operator" />
        </el-form-item>
        <el-form-item label="Note">
          <el-input v-model="publishForm.note" type="textarea" :rows="3" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="publishOpen = false">Cancel</el-button>
        <el-button type="primary" :loading="publishing" @click="publishVersion">Publish</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, CircleCheck, Upload } from '@element-plus/icons-vue'
import {
  getWorkflow,
  listWorkflowVersions,
  publishWorkflowVersion,
  rollbackWorkflowVersion,
  validateWorkflowVersion,
} from '@/api/workflow'
import type {
  WorkflowDefinition,
  WorkflowPublishRequest,
  WorkflowReleaseValidationResult,
  WorkflowVersion,
} from '@/types/workflow'

const route = useRoute()
const router = useRouter()
const workflowId = String(route.params.workflowId || '')

const loading = ref(false)
const validating = ref(false)
const publishing = ref(false)
const rollingBackId = ref<number | null>(null)
const publishOpen = ref(false)
const workflow = ref<WorkflowDefinition | null>(null)
const versions = ref<WorkflowVersion[]>([])
const validation = ref<WorkflowReleaseValidationResult | null>(null)
const publishForm = reactive<WorkflowPublishRequest>({
  version: '',
  rolloutPercent: 100,
  publishedBy: '',
  note: '',
})

const validationItems = computed(() => [
  ...(validation.value?.errors || []),
  ...(validation.value?.warnings || []),
])

onMounted(async () => {
  await Promise.all([loadWorkflow(), loadVersions()])
})

async function loadWorkflow() {
  const { data } = await getWorkflow(workflowId)
  workflow.value = data
}

async function loadVersions() {
  loading.value = true
  try {
    const { data } = await listWorkflowVersions(workflowId)
    versions.value = Array.isArray(data) ? data : []
  } finally {
    loading.value = false
  }
}

async function validateRelease() {
  validating.value = true
  try {
    const { data } = await validateWorkflowVersion(workflowId)
    validation.value = data
    if (data.valid) ElMessage.success('Workflow release validation passed')
  } finally {
    validating.value = false
  }
}

async function publishVersion() {
  if (!publishForm.version.trim()) {
    ElMessage.warning('Version is required')
    return
  }
  publishing.value = true
  try {
    await publishWorkflowVersion(workflowId, {
      version: publishForm.version.trim(),
      rolloutPercent: publishForm.rolloutPercent,
      note: publishForm.note,
      publishedBy: publishForm.publishedBy,
    })
    publishOpen.value = false
    validation.value = null
    ElMessage.success('Workflow version published')
    await Promise.all([loadWorkflow(), loadVersions()])
  } finally {
    publishing.value = false
  }
}

async function rollback(row: WorkflowVersion) {
  await ElMessageBox.confirm(`Rollback to ${row.version}?`, 'Rollback Workflow', {
    type: 'warning',
  })
  rollingBackId.value = row.id
  try {
    await rollbackWorkflowVersion(workflowId, row.id, publishForm.publishedBy || undefined)
    ElMessage.success('Workflow rolled back')
    await Promise.all([loadWorkflow(), loadVersions()])
  } finally {
    rollingBackId.value = null
  }
}
</script>

<style scoped>
.workflow-versions {
  min-height: calc(100vh - 56px);
  padding: 20px;
  background: var(--el-bg-color-page);
}

.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.page-header h1 {
  margin: 0 0 6px;
  font-size: 20px;
  font-weight: 700;
  letter-spacing: 0;
}

.page-header p {
  margin: 0 0 8px;
  color: var(--el-text-color-secondary);
}

.header-actions,
.meta-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.validation-alert,
.validation-panel {
  margin-bottom: 14px;
}

.validation-panel {
  display: grid;
  gap: 8px;
  padding: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-bg-color);
}

.validation-item {
  display: grid;
  grid-template-columns: auto auto 1fr;
  align-items: center;
  gap: 8px;
}

.validation-item p {
  grid-column: 1 / -1;
  margin: 0;
  color: var(--el-text-color-secondary);
}

.active-tag {
  margin-left: 8px;
}

@media (max-width: 760px) {
  .page-header,
  .header-actions {
    align-items: stretch;
    flex-direction: column;
  }
}
</style>
