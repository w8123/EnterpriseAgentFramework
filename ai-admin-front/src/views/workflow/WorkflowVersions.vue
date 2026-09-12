<template>
  <WorkbenchPage class="workflow-versions">
    <PageHeader
      variant="entity"
      domain="workflow"
      eyebrow="Workflow Versions"
      :title="workflow?.name || 'Workflow 版本'"
      :description="workflow?.keySlug || workflowId"
    >
      <template #tags>
          <el-tag size="small" effect="plain">{{ formatWorkflowKindLabel(workflow?.workflowKind) }}</el-tag>
          <el-tag size="small" type="info" effect="plain">{{ formatWorkflowExecutionEngineLabel(workflow?.executionEngine) }}</el-tag>
          <el-tag size="small" :type="workflowStatusTagType(workflow?.status)">
            {{ formatWorkflowStatusLabel(workflow?.status) }}
          </el-tag>
      </template>
      <template #actions>
        <el-button v-if="canWriteWorkflow" :icon="ArrowLeft" @click="router.push(`/workflows/${workflowId}/studio`)">
          编排
        </el-button>
        <el-button v-if="canWriteWorkflow" :icon="CircleCheck" :loading="validating" @click="validateRelease">
          校验
        </el-button>
        <el-button v-if="canPublishWorkflow" type="primary" :icon="Upload" :disabled="publishing || rollingBackId !== null" @click="publishOpen = true">
          发布
        </el-button>
      </template>
    </PageHeader>

    <el-alert v-if="releaseError" type="error" :closable="false" show-icon :title="releaseError">
      <el-button text :disabled="publishing || rollingBackId !== null" @click="refreshReleaseState">刷新版本状态</el-button>
    </el-alert>

    <el-alert
      v-if="validation"
      class="validation-alert"
      :type="validation.valid ? 'success' : 'error'"
      :closable="false"
      show-icon
      :title="validation.valid ? 'Workflow 已可发布' : `${validation.errors.length} 个发布问题`"
    />

    <section v-if="validation && (!validation.valid || validation.warnings.length)" class="validation-panel">
      <div v-for="item in validationItems" :key="`${item.level}-${item.code}-${item.nodeId || ''}`" class="validation-item">
        <el-tag size="small" :type="item.level === 'ERROR' ? 'danger' : 'warning'">
          {{ item.level === 'ERROR' ? '错误' : '警告' }}
        </el-tag>
        <strong>{{ item.code }}</strong>
        <span v-if="item.nodeId">{{ item.nodeId }}</span>
        <p>{{ item.message }}</p>
      </div>
    </section>

    <el-table :data="versions" v-loading="loading" stripe>
      <el-table-column prop="version" label="版本" min-width="160">
        <template #default="{ row }">
          <strong>{{ row.version }}</strong>
          <el-tag v-if="row.status === 'ACTIVE'" class="active-tag" size="small" type="success">
            当前生效
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="生效方式" width="110">
        <template #default>全量</template>
      </el-table-column>
      <el-table-column prop="publishedBy" label="发布人" min-width="140" />
      <el-table-column prop="publishedAt" label="发布时间" min-width="180" />
      <el-table-column prop="note" label="备注" min-width="220" />
      <el-table-column label="操作" width="160" fixed="right">
        <template #default="{ row }">
          <el-button
            v-if="canPublishWorkflow"
            size="small"
            :disabled="row.status === 'ACTIVE' || publishing || rollingBackId !== null"
            :loading="rollingBackId === row.id"
            @click="rollback(row)"
          >
            回滚
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <AppDialog v-model="publishOpen" title="发布 Workflow 版本" width="460px"
      :close-on-click-modal="!publishing" :close-on-press-escape="!publishing" :show-close="!publishing">
      <el-alert v-if="releaseError" type="error" :closable="false" show-icon :title="releaseError">
        <el-button text :disabled="publishing" @click="refreshReleaseState">刷新版本状态</el-button>
      </el-alert>
      <el-form :model="publishForm" label-width="110px" :disabled="publishing" novalidate>
        <el-form-item label="版本">
          <el-input v-model="publishForm.version" placeholder="v1.0.0" />
        </el-form-item>
        <el-form-item label="生效方式">
          <el-input value="全量发布" disabled />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="publishForm.note" type="textarea" :rows="3" resize="none" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="publishing" @click="publishOpen = false">取消</el-button>
        <el-button type="primary" :loading="publishing" @click="publishVersion">发布</el-button>
      </template>
    </AppDialog>
  </WorkbenchPage>
</template>

<script setup lang="ts">
import AppDialog from '@/components/common/AppDialog.vue'
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
  WorkflowWorkingCopy,
  WorkflowPublishRequest,
  WorkflowReleaseValidationResult,
  WorkflowVersion,
} from '@/types/workflow'
import {
  formatWorkflowExecutionEngineLabel,
  formatWorkflowKindLabel,
  formatWorkflowStatusLabel,
  workflowStatusTagType,
} from '@/utils/workflowLabels'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import PageHeader from '@/components/common/PageHeader.vue'
import {
  hasPlatformResourcePermission,
  PLATFORM_PERMISSION_WORKFLOW_PUBLISH,
  PLATFORM_PERMISSION_WORKFLOW_WRITE,
} from '@/auth/platformAccess'
import { platformSessionUser } from '@/auth/platformSession'

const route = useRoute()
const router = useRouter()
const workflowId = String(route.params.workflowId || '')

const loading = ref(false)
const validating = ref(false)
const publishing = ref(false)
const rollingBackId = ref<number | null>(null)
const publishOpen = ref(false)
const workflow = ref<WorkflowWorkingCopy | null>(null)
const versions = ref<WorkflowVersion[]>([])
const validation = ref<WorkflowReleaseValidationResult | null>(null)
const releaseError = ref('')
const publishForm = reactive<WorkflowPublishRequest>({
  version: '',
  rolloutPercent: 100,
  note: '',
})

const validationItems = computed(() => [
  ...(validation.value?.errors || []),
  ...(validation.value?.warnings || []),
])
const canWriteWorkflow = computed(() => hasPlatformResourcePermission(
  platformSessionUser.value?.permissionGrants,
  PLATFORM_PERMISSION_WORKFLOW_WRITE,
  'PROJECT',
  null,
  workflow.value?.projectCode,
))
const canPublishWorkflow = computed(() => hasPlatformResourcePermission(
  platformSessionUser.value?.permissionGrants,
  PLATFORM_PERMISSION_WORKFLOW_PUBLISH,
  'PROJECT',
  null,
  workflow.value?.projectCode,
))

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
  if (!canWriteWorkflow.value) return
  validating.value = true
  try {
    const { data } = await validateWorkflowVersion(workflowId)
    validation.value = data
    if (data.valid) ElMessage.success('Workflow 发布校验通过')
  } finally {
    validating.value = false
  }
}

async function publishVersion() {
  if (!canPublishWorkflow.value || publishing.value || rollingBackId.value !== null) return
  const baseRevision = workflow.value?.updatedAt
  if (!baseRevision) {
    releaseError.value = '未能读取草稿修订，请刷新版本状态后再发布。'
    return
  }
  if (!publishForm.version.trim()) {
    ElMessage.warning('请填写版本号')
    return
  }
  publishing.value = true
  releaseError.value = ''
  try {
    await publishWorkflowVersion(workflowId, {
      version: publishForm.version.trim(),
      rolloutPercent: 100,
      note: publishForm.note,
      baseRevision,
    })
    publishOpen.value = false
    validation.value = null
    ElMessage.success('Workflow 版本已发布')
    await refreshAfterRelease()
  } catch (error) {
    releaseError.value = releaseFailure(error)
  } finally {
    publishing.value = false
  }
}

async function rollback(row: WorkflowVersion) {
  if (!canPublishWorkflow.value || publishing.value || rollingBackId.value !== null) return
  const baseRevision = workflow.value?.updatedAt
  if (!baseRevision) {
    releaseError.value = '未能读取草稿修订，请刷新版本状态后再回滚。'
    return
  }
  rollingBackId.value = row.id
  try {
    await ElMessageBox.confirm(`将活动发布版本切换到 ${row.version}，当前编辑草稿会保留。确认回滚？`, '回滚 Workflow', {
      type: 'warning', confirmButtonText: '回滚版本', cancelButtonText: '取消',
    })
  } catch {
    rollingBackId.value = null
    return
  }
  releaseError.value = ''
  try {
    await rollbackWorkflowVersion(workflowId, row.id, baseRevision)
    ElMessage.success('Workflow 已回滚')
    await refreshAfterRelease()
  } catch (error) {
    releaseError.value = releaseFailure(error)
  } finally {
    rollingBackId.value = null
  }
}

async function refreshAfterRelease() {
  try {
    await Promise.all([loadWorkflow(), loadVersions()])
  } catch {
    releaseError.value = '操作已完成，但版本状态未能刷新。请刷新版本状态后继续。'
  }
}

async function refreshReleaseState() {
  try {
    await Promise.all([loadWorkflow(), loadVersions()])
    releaseError.value = ''
  } catch { releaseError.value = '版本状态加载失败，请重试。' }
}

function releaseFailure(error: unknown) {
  const failure = error as { response?: { status?: number; data?: { message?: string } } }
  if (failure.response?.status === 409) return '草稿已被更新，本次操作未执行。请刷新版本状态并检查最新草稿后重试。'
  return failure.response?.data?.message || '暂未能确认操作结果，请刷新版本状态后再重试。'
}
</script>

<style scoped>
.workflow-versions {
  min-height: calc(100vh - 56px);
  background: var(--el-bg-color-page);
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

</style>
