<template>
  <div class="registry-detail-page project-workbench-page" :class="{ 'is-dark-detail': theme === 'dark' }">
    <AppPageBackground local />

    <PageHeader
      variant="entity"
      domain="project"
      :title="project?.name || projectCode"
    >
      <template #leading>
        <div class="app-page-header__entity-mark" aria-hidden="true">
          <el-icon><Box /></el-icon>
        </div>
      </template>
      <template #tags>
        <el-tag effect="plain">{{ project?.projectCode || projectCode }}</el-tag>
        <el-tag type="info" effect="plain">{{ project?.environment || 'dev' }}</el-tag>
      </template>
      <template #meta>
        <HeaderMetaList :items="headerMetaItems" />
      </template>
      <template #actions>
        <el-tooltip content="设为当前项目" placement="top">
          <el-button
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
        <el-tooltip content="更多操作" placement="top">
          <el-dropdown trigger="click" :disabled="!project?.id" @command="handleHeroMoreCommand">
            <el-button
              circle
              :icon="MoreFilled"
              :disabled="!project?.id"
              :loading="deleteLoading"
              aria-label="更多操作"
            />
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="delete" :disabled="deleteLoading">
                  删除项目
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </el-tooltip>
      </template>
    </PageHeader>

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
          <template #default="{ row }">{{ formatRelativeTime(row.lastHeartbeatAt) }}</template>
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

    <GlassDialog
      v-model="aiCodingDialogVisible"
      title="AI Coding 接入信息"
      description="让 Cursor、Claude Code、Codex 安全接入当前项目。"
      :status="aiCodingAccessEnabled ? '已启用' : '已关闭'"
      :status-tone="aiCodingAccessEnabled ? 'success' : 'neutral'"
      width="920px"
      class="ai-coding-dialog"
      destroy-on-close
      @open="aiCodingDialogTab = 'overview'"
    >
      <template #icon>
        <el-icon><Connection /></el-icon>
      </template>

      <section class="ai-coding-security-note glass-surface-control">
        <el-icon><Lock /></el-icon>
        <div>
          <strong>秘钥只用于本机 AI 编程工具</strong>
          <span>请通过请求头发送，勿提交到 Git、前端构建产物或聊天上下文。</span>
        </div>
      </section>

      <el-tabs v-model="aiCodingDialogTab" class="ai-coding-dialog__tabs">
        <el-tab-pane name="overview">
          <template #label>
            <span class="ai-coding-tab-label">接入概览</span>
          </template>

          <section
            class="ai-coding-access-card glass-surface-control"
            :class="{ 'is-enabled': aiCodingAccessEnabled }"
          >
            <div class="ai-coding-key-head">
              <GlassSectionHeader
                title="项目级统一秘钥"
                description="控制外部 AI 工具是否可免平台登录访问项目接口。"
              >
                <template #icon><el-icon><Lock /></el-icon></template>
              </GlassSectionHeader>
              <el-switch v-model="aiCodingAccessEnabled" active-text="启用" inactive-text="关闭" />
            </div>
            <div class="ai-coding-key-form">
              <el-input
                v-model="aiCodingAccessKey"
                :disabled="!aiCodingAccessEnabled"
                show-password
                placeholder="保存时为空会自动生成；清空并关闭后 AI 工具无法连接"
              >
                <template #prefix>
                  <el-icon><Key /></el-icon>
                </template>
              </el-input>
              <el-tooltip content="复制秘钥" placement="top">
                <el-button
                  circle
                  :icon="DocumentCopy"
                  :disabled="!aiCodingAccessEnabled || !aiCodingAccessKey.trim()"
                  aria-label="复制秘钥"
                  @click="copyText(aiCodingAccessKey, 'AI Coding 接入秘钥')"
                />
              </el-tooltip>
              <el-button :loading="aiCodingAccessSaving" type="primary" @click="saveAiCodingAccess">
                保存
              </el-button>
              <el-button type="danger" plain @click="clearAiCodingAccess">清空并关闭</el-button>
            </div>
          </section>

          <section class="ai-coding-identity-section">
            <GlassSectionHeader
              title="项目身份"
              description="AI 工具识别当前 ReachAI 项目所需的基础信息。"
              :meta="`${aiCodingProjectInfoRows.length} 项`"
            >
              <template #icon><el-icon><Postcard /></el-icon></template>
            </GlassSectionHeader>

            <div class="ai-coding-identity-grid">
              <GlassInfoItem
                v-for="(row, index) in aiCodingProjectInfoRows"
                :key="row.label"
                class="ai-coding-identity-card"
                :class="{ 'is-wide': index === 0 }"
                :label="row.label"
                :value="row.displayValue"
                :muted="!row.copyValue"
              >
                <template #leading>
                  <el-icon><component :is="aiCodingInfoIcon(row.label)" /></el-icon>
                </template>
                <template #trailing>
                  <el-tooltip :content="row.copyValue ? `复制${row.label}` : `${row.label}暂无可复制内容`" placement="top">
                    <el-button
                      circle
                      :icon="DocumentCopy"
                      :disabled="!row.copyValue"
                      :aria-label="`复制${row.label}`"
                      @click="copyText(row.copyValue, row.label)"
                    />
                  </el-tooltip>
                </template>
              </GlassInfoItem>
            </div>
          </section>
        </el-tab-pane>

        <el-tab-pane name="endpoints">
          <template #label>
            <span class="ai-coding-tab-label">
              完整接口
              <i>{{ aiCodingEndpointRows.length }}</i>
            </span>
          </template>

          <section class="ai-coding-endpoint-section">
            <GlassSectionHeader
              title="接口与请求头"
              description="用于 manifest 获取、上下文候选提交、状态查询与治理审计。"
              :meta="`${aiCodingEndpointRows.length} 项`"
            >
              <template #icon><el-icon><Link /></el-icon></template>
            </GlassSectionHeader>

            <div class="ai-coding-endpoint-list">
              <GlassInfoItem
                v-for="(row, index) in aiCodingEndpointRows"
                :key="row.label"
                class="ai-coding-endpoint-row"
                :label="row.label"
                :value="row.displayValue"
                compact
              >
                <template #leading>{{ String(index + 1).padStart(2, '0') }}</template>
                <template #trailing>
                  <el-button
                    class="ai-coding-endpoint-copy"
                    :icon="DocumentCopy"
                    :disabled="!row.copyValue"
                    @click="copyText(row.copyValue, row.label)"
                  >
                    复制
                  </el-button>
                </template>
              </GlassInfoItem>
            </div>
          </section>
        </el-tab-pane>
      </el-tabs>

      <template #hint>
        <el-icon><WarningFilled /></el-icon>
        <span>复制全部会包含当前启用的接入秘钥</span>
      </template>
      <template #footer>
        <el-button @click="aiCodingDialogVisible = false">关闭</el-button>
        <el-button type="primary" :icon="DocumentCopy" @click="copyAiCodingBundle">
          复制全部接入信息
        </el-button>
      </template>
    </GlassDialog>

    <GlassDialog
      v-model="editDialogVisible"
      title="编辑项目"
      eyebrow="项目配置"
      description="维护项目基础属性、接入方式与访问凭据。"
      class="edit-project-dialog"
      width="820px"
      destroy-on-close
    >
      <template #icon>
        <el-icon><EditPen /></el-icon>
      </template>
      <template #aside>
        <span class="edit-project-dialog__badge">
          {{ formatProjectKindLabel(editForm.projectKind || 'REGISTERED') }}
        </span>
      </template>

      <el-form class="edit-project-form" label-position="top">
        <section class="edit-project-section glass-surface-control">
          <GlassSectionHeader title="基础信息" description="用于识别项目、环境与负责人。">
            <template #icon><el-icon><Postcard /></el-icon></template>
          </GlassSectionHeader>
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

        <section class="edit-project-section glass-surface-control">
          <GlassSectionHeader title="接入配置" description="维护 SDK / 扫描接入地址与访问凭据。">
            <template #icon><el-icon><Connection /></el-icon></template>
          </GlassSectionHeader>
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
        <el-button @click="editDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="editSaving" @click="saveEditProject">保存</el-button>
      </template>
    </GlassDialog>

  </div>
</template>

<script setup lang="ts">
import GlassDialog from '@/components/common/GlassDialog.vue'
import GlassInfoItem from '@/components/common/GlassInfoItem.vue'
import GlassSectionHeader from '@/components/common/GlassSectionHeader.vue'
import { computed, onMounted, ref } from 'vue'
import {
  ArrowRight,
  Box,
  Connection,
  Delete,
  DocumentCopy,
  EditPen,
  Grid,
  Key,
  Link,
  Lock,
  Monitor,
  MoreFilled,
  Postcard,
  Refresh,
  Star,
  Tickets,
  WarningFilled,
} from '@element-plus/icons-vue'
import MetricIconBg from '@/components/common/MetricIconBg.vue'
import PageHeader from '@/components/common/PageHeader.vue'
import HeaderMetaList, { type HeaderMetaItem } from '@/components/common/HeaderMetaList.vue'
import { formatInstanceStatusLabel } from '@/utils/registryLabels'
import { formatRelativeTime } from '@/utils/relativeTime'
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

const { theme } = useTheme()

const projectKindOptions = PROJECT_KIND_SELECT_OPTIONS
const visibilityOptions = VISIBILITY_SELECT_OPTIONS
const aiCodingDialogTab = ref<'overview' | 'endpoints'>('overview')

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

const aiCodingProjectInfoRows = computed(() => aiCodingInfoRows.value.slice(0, 5))
const aiCodingEndpointRows = computed(() => aiCodingInfoRows.value.slice(6))

function aiCodingInfoIcon(label: string) {
  if (label === 'ReachAI 平台地址') return Monitor
  if (label === '项目 ID') return Tickets
  if (label === '项目编码') return Grid
  if (label === '项目名称') return Postcard
  if (label === 'App Key') return Key
  return Link
}

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
  formatRelativeTime,
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
  formatRelativeTime(instances.value[0]?.lastHeartbeatAt || project.value?.lastScannedAt || null),
)

const onlineInstanceCount = computed(() =>
  instances.value.filter((item) => item.status === 'ONLINE').length,
)

const headerMetaItems = computed<HeaderMetaItem[]>(() => [
  {
    key: 'status',
    label: '状态',
    value: formatProjectKindLabel(project.value?.projectKind || 'REGISTERED'),
    tone: 'success',
  },
  { key: 'sdk', label: 'SDK', value: project.value?.sdkVersion || '-' },
  {
    key: 'visibility',
    label: '可见性',
    value: formatVisibilityLabel(project.value?.visibility || 'PRIVATE'),
  },
  { key: 'owner', label: '负责人', value: project.value?.owner || '-' },
  { key: 'heartbeat', label: '最近心跳', value: latestHeartbeatLabel.value },
  { key: 'instances', label: '实例', value: `${onlineInstanceCount.value} 在线` },
])

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
