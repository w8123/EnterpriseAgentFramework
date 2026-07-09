<template>
  <div class="registry-detail-page" :class="{ 'is-dark-detail': theme === 'dark' }">
    <AppPageBackground local />

    <section class="project-hero">
      <div class="hero-corner-actions">
        <el-tooltip content="设为当前项目" placement="top">
          <el-button
            class="primary-icon-action"
            circle
            :icon="Star"
            :disabled="!project"
            aria-label="设为当前项目"
            @click="setCurrentProject"
          />
        </el-tooltip>
        <el-tooltip content="刷新" placement="top">
          <el-button
            circle
            :icon="Refresh"
            aria-label="刷新"
            @click="refresh"
          />
        </el-tooltip>
        <el-tooltip content="编辑项目" placement="top">
          <el-button
            circle
            :icon="EditPen"
            :disabled="!project?.id"
            aria-label="编辑项目"
            @click="openEditDialog"
          />
        </el-tooltip>
        <el-dropdown trigger="click" :disabled="!project?.id" @command="handleHeroMoreCommand">
          <el-button class="more-action" :disabled="!project?.id" :loading="deleteLoading">
            更多
            <el-icon class="el-icon--right"><ArrowDown /></el-icon>
          </el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="delete" :disabled="deleteLoading">
                删除项目
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>

      <div class="hero-main">
        <div class="project-mark">
          <el-icon><Box /></el-icon>
        </div>

        <div class="project-title-block">
          <div class="title-row">
            <h1>{{ project?.name || projectCode }}</h1>
            <el-tag class="code-tag" effect="plain">{{ project?.projectCode || projectCode }}</el-tag>
            <el-tag class="env-tag" effect="plain">{{ project?.environment || 'dev' }}</el-tag>
          </div>
          <div class="project-meta">
            <span>
              状态：
              <i class="online-dot" />
              <b>{{ formatProjectKindLabel(project?.projectKind || 'REGISTERED') }}</b>
            </span>
            <span class="meta-sdk">
              SDK：<b>{{ project?.sdkVersion || '-' }}</b>
            </span>
            <span>
              可见性：<b>{{ formatVisibilityLabel(project?.visibility || 'PRIVATE') }}</b>
            </span>
            <span>
              负责人：<b>{{ project?.owner || '-' }}</b>
            </span>
            <span class="heartbeat-meta">
              最近心跳：<b>{{ latestHeartbeatLabel }}</b>
            </span>
            <span>
              实例：<b>{{ onlineInstanceCount }} 在线</b>
            </span>
          </div>
        </div>
      </div>
    </section>

    <div class="metric-strip">
      <template v-for="(item, index) in healthMetrics" :key="item.label">
        <div v-if="index > 0" class="metric-divider" aria-hidden="true" />
        <component
          :is="item.clickable ? 'button' : 'div'"
          class="metric-segment"
          :class="[`tone-${item.tone}`, { 'is-clickable': item.clickable }]"
          :type="item.clickable ? 'button' : undefined"
          @click="item.clickable ? item.action?.() : undefined"
        >
          <MetricIconBg class="metric-segment-icon">
            <el-icon><component :is="item.icon" /></el-icon>
          </MetricIconBg>
          <div class="metric-content">
            <span class="metric-label">{{ item.label }}</span>
            <strong>{{ item.value }}</strong>
            <small>{{ item.desc }}</small>
          </div>
        </component>
      </template>
    </div>

    <section class="workbench-grid">
      <el-card v-for="group in workbenchGroups" :key="group.title" class="detail-card workbench-card" shadow="never">
        <template #header>
          <div class="section-title">
            <span class="title-mark" />
            <span>{{ group.title }}</span>
          </div>
        </template>

        <div class="task-list">
          <button
            v-for="item in group.items"
            :key="item.title"
            class="task-entry"
            type="button"
            :disabled="item.disabled"
            @click="item.action"
          >
            <span class="task-icon" :class="item.tone">
              <el-icon><component :is="item.icon" /></el-icon>
            </span>
            <span class="task-content">
              <span class="task-topline">
                <strong>{{ item.title }}</strong>
              </span>
              <small>{{ item.desc }}</small>
            </span>
            <el-icon class="task-arrow"><ArrowRight /></el-icon>
          </button>
        </div>
      </el-card>
    </section>

    <el-card class="detail-card instance-card" shadow="never">
      <template #header>
        <div class="table-header">
          <div class="section-title">
            <span class="title-mark" />
            <span>实例心跳（{{ instances.length }}）</span>
          </div>
          <div class="header-actions">
            <el-tooltip :content="`清理离线（${offlineInstanceCount}）`" placement="top">
              <el-button
                circle
                :icon="Delete"
                :disabled="offlineInstanceCount === 0"
                :loading="purgingOffline"
                aria-label="清理离线实例"
                @click="purgeOfflineInstances"
              />
            </el-tooltip>
            <el-tooltip content="刷新实例" placement="top">
              <el-button circle :icon="Refresh" aria-label="刷新实例" @click="loadInstances" />
            </el-tooltip>
          </div>
        </div>
      </template>

      <el-table v-loading="loadingInstances" :data="instances" row-key="id" class="instance-table">
        <el-table-column prop="instanceId" label="实例 ID" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="instance-id">{{ row.instanceId }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="120">
          <template #default="{ row }">
            <span class="status-pill" :class="{ offline: row.status !== 'ONLINE', disabled: row.status === 'DISABLED' }">
              <i />
              {{ formatInstanceStatusLabel(row.status) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="host" label="主机" min-width="180">
          <template #default="{ row }">{{ row.host || '-' }}</template>
        </el-table-column>
        <el-table-column prop="port" label="端口" width="100">
          <template #default="{ row }">{{ row.port || '-' }}</template>
        </el-table-column>
        <el-table-column prop="appVersion" label="应用版本" width="130">
          <template #default="{ row }">{{ row.appVersion || '-' }}</template>
        </el-table-column>
        <el-table-column prop="sdkVersion" label="SDK 版本" width="130">
          <template #default="{ row }">{{ row.sdkVersion || '-' }}</template>
        </el-table-column>
        <el-table-column prop="lastHeartbeatAt" label="最近心跳" min-width="180">
          <template #default="{ row }">{{ formatHeartbeatDisplay(row.lastHeartbeatAt) }}</template>
        </el-table-column>
        <el-table-column label="治理" width="130">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'DISABLED'"
              size="small"
              @click="setInstanceStatus(row, 'OFFLINE')"
            >
              解除禁用
            </el-button>
            <el-button
              v-else
              size="small"
              type="danger"
              plain
              @click="setInstanceStatus(row, 'DISABLED')"
            >
              禁用
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="table-footer">
        <span>共 {{ instances.length }} 条</span>
        <el-pagination
          class="registry-pagination"
          background
          layout="prev, pager, next, sizes"
          :total="instances.length || 1"
          :page-size="10"
          :page-sizes="[5, 10, 20, 50]"
        />
      </div>
    </el-card>

    <el-dialog
      v-model="aiCodingDialogVisible"
      title="AI Coding 接入信息"
      width="720px"
      class="ai-coding-dialog"
      destroy-on-close
    >
      <el-alert
        type="info"
        show-icon
        :closable="false"
        title="供 Cursor、Claude Code、Codex 接入本项目的页面助手与 Workflow AI Coding。"
        description="下方信息可逐项复制，也可一键复制全部；秘钥请勿提交到 Git 或聊天上下文。"
      />
      <section class="ai-coding-dialog-manage">
        <div class="ai-coding-key-head">
          <div>
            <strong>项目级统一秘钥</strong>
            <span>启用后外部 AI 工具可免平台登录访问本项目 AI Coding 接口。</span>
          </div>
          <el-switch v-model="aiCodingAccessEnabled" active-text="启用" inactive-text="关闭" />
        </div>
        <div class="ai-coding-key-form">
          <el-input
            v-model="aiCodingAccessKey"
            :disabled="!aiCodingAccessEnabled"
            show-password
            placeholder="保存时为空会自动生成；清空并关闭后 AI 工具无法连接"
          />
          <el-button :loading="aiCodingAccessSaving" type="primary" @click="saveAiCodingAccess">保存</el-button>
          <el-button @click="clearAiCodingAccess">清空并关闭</el-button>
        </div>
      </section>
      <section class="ai-coding-info-list">
        <div v-for="row in aiCodingInfoRows" :key="row.label" class="ai-coding-info-row">
          <span class="ai-coding-info-label">{{ row.label }}</span>
          <code class="ai-coding-info-value">{{ row.displayValue }}</code>
          <el-button
            link
            type="primary"
            :icon="DocumentCopy"
            :disabled="!row.copyValue"
            @click="copyText(row.copyValue, row.label)"
          >
            复制
          </el-button>
        </div>
      </section>
      <template #footer>
        <el-button @click="aiCodingDialogVisible = false">关闭</el-button>
        <el-button type="primary" :icon="DocumentCopy" @click="copyAiCodingBundle">复制全部接入信息</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="editDialogVisible" class="edit-project-dialog" width="760px" destroy-on-close>
      <template #header>
        <div class="edit-project-dialog__header">
          <div class="edit-project-dialog__mark">
            <el-icon><EditPen /></el-icon>
          </div>
          <div class="edit-project-dialog__title">
            <span>项目配置</span>
            <strong>编辑项目</strong>
          </div>
          <div class="edit-project-dialog__badge">
            {{ formatProjectKindLabel(editForm.projectKind || 'REGISTERED') }}
          </div>
        </div>
      </template>

      <el-form class="edit-project-form" label-position="top">
        <section class="edit-project-section">
          <div class="edit-project-section__head">
            <span>基础信息</span>
          </div>
          <el-form-item label="项目名称" required>
            <el-input v-model="editForm.name" placeholder="项目名称" />
          </el-form-item>
          <el-row class="edit-project-row" :gutter="14">
            <el-col :span="12">
              <el-form-item label="项目编码" :required="isEditingSdkProject">
                <el-input v-model="editForm.projectCode" :placeholder="isEditingSdkProject ? '如：customer-service' : '如 order-service'" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="接入方式">
                <el-select v-model="editForm.projectKind" style="width: 100%" :disabled="editAccessLockedToSdk">
                  <el-option v-for="opt in projectKindOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
                </el-select>
              </el-form-item>
            </el-col>
          </el-row>
          <el-row class="edit-project-row" :gutter="14">
            <el-col :span="12">
              <el-form-item label="环境">
                <el-input v-model="editForm.environment" placeholder="dev / test / prod" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="负责人">
                <el-input v-model="editForm.owner" placeholder="负责人" />
              </el-form-item>
            </el-col>
          </el-row>
        </section>

        <section class="edit-project-section">
          <div class="edit-project-section__head">
            <span>接入配置</span>
          </div>
          <el-row class="edit-project-row" :gutter="14">
            <el-col :span="12">
              <el-form-item :label="isEditingSdkProject ? 'Base URL' : '项目域名'" required>
                <el-input v-model="editForm.baseUrl" placeholder="http://localhost:8080" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="可见性">
                <el-select v-model="editForm.visibility" style="width: 100%">
                  <el-option v-for="opt in visibilityOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
                </el-select>
              </el-form-item>
            </el-col>
          </el-row>
          <template v-if="isEditingSdkProject">
            <el-row class="edit-project-row" :gutter="14">
              <el-col :span="12">
                <el-form-item label="App Key" required>
                  <el-input v-model="editCredentialForm.appKey" placeholder="请输入 App Key" />
                </el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item label="App Secret" required>
                  <el-input v-model="editCredentialForm.appSecret" show-password placeholder="请输入 App Secret" />
                </el-form-item>
              </el-col>
            </el-row>
          </template>
          <template v-else>
            <el-form-item label="Context Path">
              <el-input v-model="editForm.contextPath" placeholder="/api" />
            </el-form-item>
            <el-form-item label="扫描路径" :required="editForm.projectKind !== 'REGISTERED'">
              <el-input v-model="editForm.scanPath" placeholder="服务器上的绝对路径或 OpenAPI 所在目录" />
              <div v-if="editForm.projectKind === 'REGISTERED'" class="form-hint">SDK 接入项目可不配置扫描路径。</div>
            </el-form-item>
            <el-row class="edit-project-row" :gutter="14">
              <el-col :span="12">
                <el-form-item label="扫描方式" required>
                  <el-select v-model="editForm.scanType" style="width: 100%">
                    <el-option label="OpenAPI" value="openapi" />
                    <el-option label="Controller" value="controller" />
                    <el-option label="自动（SDK）" value="auto" />
                  </el-select>
                </el-form-item>
              </el-col>
              <el-col v-if="editForm.scanType === 'openapi'" :span="12">
                <el-form-item label="规范文件">
                  <el-input v-model="editForm.specFile" placeholder="可选，相对 scanPath；留空自动发现" />
                </el-form-item>
              </el-col>
            </el-row>
          </template>
        </section>
      </el-form>
      <template #footer>
        <div class="edit-project-dialog__footer">
          <el-button @click="editDialogVisible = false">取消</el-button>
          <el-button type="primary" :loading="editSaving" @click="saveEditProject">保存</el-button>
        </div>
      </template>
    </el-dialog>

  </div>
</template>

<script setup lang="ts">
import { computed, onMounted } from 'vue'
import {
  ArrowDown,
  ArrowRight,
  Box,
  Delete,
  DocumentCopy,
  EditPen,
  Refresh,
  Star,
} from '@element-plus/icons-vue'
import MetricIconBg from '@/components/common/MetricIconBg.vue'
import { formatInstanceStatusLabel } from '@/utils/registryLabels'
import AppPageBackground from '@/components/common/AppPageBackground.vue'
import {
  formatProjectKindLabel,
  formatVisibilityLabel,
  PROJECT_KIND_SELECT_OPTIONS,
  VISIBILITY_SELECT_OPTIONS,
} from '@/utils/projectLabels'
import { useTheme } from '@/composables/useTheme'
import { useRegistryProjectAiCodingAccess } from '@/views/registry/composables/useRegistryProjectAiCodingAccess'
import { useRegistryProjectDetailActions } from '@/views/registry/composables/useRegistryProjectDetailActions'
import { useRegistryProjectDetailData } from '@/views/registry/composables/useRegistryProjectDetailData'
import { useRegistryProjectDetailNavigation } from '@/views/registry/composables/useRegistryProjectDetailNavigation'
import { useRegistryProjectDetailUiState } from '@/views/registry/composables/useRegistryProjectDetailUiState'
import { useRegistryProjectWorkbench } from '@/views/registry/composables/useRegistryProjectWorkbench'
import {
  formatHeartbeatDisplay,
} from '@/views/registry/registryProjectDetailViewModel'

const { theme } = useTheme()

const projectKindOptions = PROJECT_KIND_SELECT_OPTIONS
const visibilityOptions = VISIBILITY_SELECT_OPTIONS

let loadAiCodingAccessFn: (projectId: number) => Promise<void> = async () => {}

const {
  projectCode,
  project,
  instances,
  pageRegistry,
  pageActions,
  loadingInstances,
  offlineInstanceCount,
  isSdkBackedProject,
  refresh,
  loadInstances,
} = useRegistryProjectDetailData({
  loadAiCodingAccess: (projectId) => loadAiCodingAccessFn(projectId),
})

const {
  editDialogVisible,
  editSaving,
  deleteLoading,
  purgingOffline,
  editAccessLockedToSdk,
  editForm,
  editCredentialForm,
  isEditingSdkProject,
} = useRegistryProjectDetailUiState()

const {
  aiCodingAccessSaving,
  aiCodingAccessEnabled,
  aiCodingAccessKey,
  aiCodingDialogVisible,
  aiCodingInfoRows,
  saveAiCodingAccess,
  clearAiCodingAccess,
  openAiCodingDialog,
  copyText,
  copyAiCodingBundle,
  loadAiCodingAccess,
} = useRegistryProjectAiCodingAccess({
  project,
  projectCode,
})

loadAiCodingAccessFn = loadAiCodingAccess

const {
  goCapability,
  goScanProjectDetail,
  goCapabilitySync,
  goWorkflowList,
  goPageActionGovernance,
  goContextGovernance,
  goContextCandidateReview,
  goPageAssistantWizard,
  goSdkAccessWizard,
} = useRegistryProjectDetailNavigation({
  project,
  projectCode,
})

const {
  purgeOfflineInstances,
  setInstanceStatus,
  openEditDialog,
  saveEditProject,
  handleDeleteProject,
  setCurrentProject,
} = useRegistryProjectDetailActions({
  project,
  projectCode,
  offlineInstanceCount,
  refresh,
  loadInstances,
  editDialogVisible,
  editSaving,
  deleteLoading,
  purgingOffline,
  editAccessLockedToSdk,
  editForm,
  editCredentialForm,
  isEditingSdkProject,
})

const { healthMetrics, workbenchGroups } = useRegistryProjectWorkbench({
  project,
  projectCode,
  instances,
  pageRegistry,
  pageActions,
  aiCodingAccessEnabled,
  aiCodingAccessKey,
  isSdkBackedProject,
  formatHeartbeatDisplay,
  openAiCodingDialog,
  goCapability,
  goScanProjectDetail,
  goCapabilitySync,
  goWorkflowList,
  goPageActionGovernance,
  goContextGovernance,
  goContextCandidateReview,
  goPageAssistantWizard,
  goSdkAccessWizard,
})

const latestHeartbeatLabel = computed(() =>
  formatHeartbeatDisplay(instances.value[0]?.lastHeartbeatAt || project.value?.lastScannedAt || null),
)

const onlineInstanceCount = computed(() =>
  instances.value.filter((item) => item.status === 'ONLINE').length,
)

function handleHeroMoreCommand(command: string | number | object) {
  if (command === 'delete') {
    handleDeleteProject()
  }
}

onMounted(refresh)
</script>

<style scoped lang="scss">
@use './styles/RegistryProjectDetail.scss';
</style>

<style lang="scss">
@use './styles/RegistryProjectDetail.global.scss';
</style>
