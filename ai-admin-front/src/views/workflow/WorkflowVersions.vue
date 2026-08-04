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
        <el-button :icon="ArrowLeft" @click="router.push(`/workflows/${workflowId}/studio`)">
          编排
        </el-button>
        <el-button :icon="CircleCheck" :loading="validating" @click="validateRelease">
          校验
        </el-button>
        <el-button type="primary" :icon="Upload" @click="publishOpen = true">发布</el-button>
      </template>
    </PageHeader>

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
            size="small"
            :disabled="row.status === 'ACTIVE'"
            :loading="rollingBackId === row.id"
            @click="rollback(row)"
          >
            回滚
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <AppDialog v-model="publishOpen" title="发布 Workflow 版本" width="460px">
      <el-form :model="publishForm" label-width="110px">
        <el-form-item label="版本">
          <el-input v-model="publishForm.version" placeholder="v1.0.0" />
        </el-form-item>
        <el-form-item label="生效方式">
          <el-input value="全量发布" disabled />
        </el-form-item>
        <el-form-item label="发布人">
          <el-input v-model="publishForm.publishedBy" placeholder="operator" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="publishForm.note" type="textarea" :rows="3" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="publishOpen = false">取消</el-button>
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
    if (data.valid) ElMessage.success('Workflow 发布校验通过')
  } finally {
    validating.value = false
  }
}

async function publishVersion() {
  if (!publishForm.version.trim()) {
    ElMessage.warning('请填写版本号')
    return
  }
  publishing.value = true
  try {
    await publishWorkflowVersion(workflowId, {
      version: publishForm.version.trim(),
      rolloutPercent: 100,
      note: publishForm.note,
      publishedBy: publishForm.publishedBy,
    })
    publishOpen.value = false
    validation.value = null
    ElMessage.success('Workflow 版本已发布')
    await Promise.all([loadWorkflow(), loadVersions()])
  } finally {
    publishing.value = false
  }
}

async function rollback(row: WorkflowVersion) {
  await ElMessageBox.confirm(`确认回滚到 ${row.version}？`, '回滚 Workflow', {
    type: 'warning',
  })
  rollingBackId.value = row.id
  try {
    await rollbackWorkflowVersion(workflowId, row.id, publishForm.publishedBy || undefined)
    ElMessage.success('Workflow 已回滚')
    await Promise.all([loadWorkflow(), loadVersions()])
  } finally {
    rollingBackId.value = null
  }
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
