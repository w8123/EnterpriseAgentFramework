<template>
  <div class="registry-detail-page project-workbench-page workbench-page--list" :class="{ 'is-dark-detail': theme === 'dark' }">
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
        <el-tag v-if="project?.environment" type="info" effect="plain">
          {{ project.environment }}
        </el-tag>
      </template>
      <template #meta>
        <HeaderMetaList :items="headerMetaItems" />
      </template>
      <template #actions>
        <el-tooltip content="AI Coding 接入" placement="top">
          <el-button
            circle
            :icon="Key"
            :disabled="!project?.id"
            aria-label="AI Coding 接入"
            @click="openAiCodingDialog"
          />
        </el-tooltip>
        <el-tooltip content="刷新" placement="top">
          <el-button
            circle
            :icon="Refresh"
            :loading="loading"
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
        <el-tooltip content="删除项目" placement="top">
          <el-button
            circle
            type="danger"
            :icon="Delete"
            :disabled="!project?.id"
            :loading="deleteLoading"
            aria-label="删除项目"
            @click="handleDeleteProject"
          />
        </el-tooltip>
      </template>
    </PageHeader>

    <ProjectRouteMissingState
      v-if="projectMissing"
      :project-code="projectCode"
    />

    <ProjectWorkbenchLoadErrorState
      v-else-if="loadError"
      title="项目详情加载失败"
      :message="loadError"
      :loading="loading"
      @retry="refresh"
    />

    <template v-else>
      <el-tabs :model-value="projectSection" class="project-section-tabs" @tab-change="selectProjectSection">
        <el-tab-pane label="项目工作台" name="overview" />
        <el-tab-pane label="来源变化" name="source-changes" />
      </el-tabs>
      <ProjectSourceChanges v-if="projectSection === 'source-changes' && project?.projectCode === projectCode" :project="project" />

      <el-alert
        v-if="projectSection === 'overview' && projectDetailLoadError"
        class="page-alert"
        type="warning"
        show-icon
        :closable="false"
        :title="projectDetailLoadError"
      />

      <el-alert
        v-if="projectSection === 'overview' && pageCatalogLoadError"
        class="page-alert"
        type="error"
        show-icon
        :closable="false"
        :title="pageCatalogLoadError"
      >
        <el-button
          link
          type="primary"
          :loading="loadingPageCatalog"
          @click="loadPageCatalog"
        >
          重新加载页面目录
        </el-button>
      </el-alert>

      <section v-if="projectSection === 'overview'" class="workbench-grid">
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

      <el-card v-if="projectSection === 'overview'" class="detail-card instance-card workbench-list-surface" shadow="never">
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

        <el-alert
          v-if="instancesLoadError"
          type="error"
          show-icon
          :closable="false"
          :title="instancesLoadError"
        >
          <el-button
            link
            type="primary"
            :loading="loadingInstances"
            @click="loadInstances"
          >
            重新加载实例
          </el-button>
        </el-alert>

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
            :default-page-size="10"
            :page-sizes="[5, 10, 20, 50]"
          />
        </div>
      </el-card>

    </template>

    <GlassDialog
      v-model="aiCodingDialogVisible"
      title="AI Coding 接入信息"
      description="让 Codex、Cursor、Trae、Claude Code 安全接入当前项目。"
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

          <el-alert
            v-if="aiCodingAccessLoadError"
            :title="aiCodingAccessLoadError"
            type="error"
            show-icon
            :closable="false"
            class="ai-coding-load-alert"
          >
            <el-button
              link
              type="primary"
              :loading="credentialPolicyLoading"
              @click="reloadAiCodingSettings"
            >
              重新加载
            </el-button>
          </el-alert>

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
              <el-switch
                v-model="aiCodingAccessEnabled"
                active-text="启用"
                inactive-text="关闭"
                :disabled="credentialPolicyLoading || Boolean(aiCodingAccessLoadError)"
              />
            </div>
            <div class="ai-coding-key-form">
              <el-input
                v-model="aiCodingAccessKey"
                :disabled="
                  credentialPolicyLoading
                    || Boolean(aiCodingAccessLoadError)
                    || !aiCodingAccessEnabled
                "
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
                  :disabled="
                    Boolean(aiCodingAccessLoadError)
                      || !aiCodingAccessEnabled
                      || !aiCodingAccessKey.trim()
                  "
                  aria-label="复制秘钥"
                  @click="copyText(aiCodingAccessKey, 'AI Coding 接入秘钥')"
                />
              </el-tooltip>
              <el-button
                :loading="aiCodingAccessSaving"
                :disabled="credentialPolicyLoading || Boolean(aiCodingAccessLoadError)"
                type="primary"
                @click="saveAiCodingAccess"
              >
                保存
              </el-button>
              <el-button
                type="danger"
                plain
                :disabled="credentialPolicyLoading || Boolean(aiCodingAccessLoadError)"
                @click="clearAiCodingAccess"
              >
                清空并关闭
              </el-button>
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

        <el-tab-pane name="credentials">
          <template #label>
            <span class="ai-coding-tab-label">任务凭据</span>
          </template>

          <section
            class="ai-coding-credential-policy glass-surface-control"
            v-loading="credentialPolicyLoading"
          >
            <GlassSectionHeader
              title="交接与任务 Token"
              description="控制新建 AI Coding 任务的交接窗口和回传凭据有效期。"
              :meta="
                credentialPolicyLoadError
                  ? '加载失败'
                  : credentialPolicyCustomized
                    ? '项目自定义'
                    : '平台默认'
              "
            >
              <template #icon><el-icon><Timer /></el-icon></template>
            </GlassSectionHeader>

            <el-alert
              v-if="credentialPolicyLoadError"
              :title="credentialPolicyLoadError"
              type="error"
              show-icon
              :closable="false"
              class="ai-coding-load-alert"
            >
              <el-button
                link
                type="primary"
                :loading="credentialPolicyLoading"
                @click="reloadAiCodingSettings"
              >
                重新加载
              </el-button>
            </el-alert>

            <div class="ai-coding-credential-grid">
              <label>
                <span>
                  <strong>交接包激活有效期</strong>
                  <small>交接码只能成功使用一次，超时后需要重新签发。</small>
                </span>
                <span class="ai-coding-duration-control">
                  <el-input-number
                    v-model="handoffActivationTtlHours"
                    :min="1"
                    :max="168"
                    :disabled="Boolean(credentialPolicyLoadError)"
                    controls-position="right"
                  />
                  <em>小时</em>
                </span>
              </label>

              <label>
                <span>
                  <strong>任务 Token 有效期</strong>
                  <small>AI Coding 客户端激活后，用于持续回传进度、问题和结果。</small>
                </span>
                <span class="ai-coding-duration-control">
                  <el-input-number
                    v-model="taskTokenTtlHours"
                    :min="1"
                    :max="720"
                    :disabled="Boolean(credentialPolicyLoadError)"
                    controls-position="right"
                  />
                  <em>小时</em>
                </span>
              </label>
            </div>

            <div class="ai-coding-credential-actions">
              <p>
                保存后只影响新签发或新激活的交接包。已经丢失 taskToken 的任务仍需重新生成交接包。
              </p>
              <el-button
                type="primary"
                :loading="credentialPolicySaving"
                :disabled="credentialPolicyLoading || Boolean(credentialPolicyLoadError)"
                @click="saveCredentialPolicy"
              >
                保存任务凭据设置
              </el-button>
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
        <el-button
          type="primary"
          :icon="DocumentCopy"
          :disabled="Boolean(aiCodingAccessLoadError)"
          @click="copyAiCodingBundle"
        >
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
                <el-form-item label="App Key" :required="!project?.registryCredentialConfigured">
                  <el-input
                    v-model="editCredentialForm.appKey"
                    :placeholder="project?.registryCredentialConfigured ? '留空保持当前凭据；更换时须同时填写 Secret' : '请输入 App Key'"
                  />
                </el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item label="App Secret" :required="!project?.registryCredentialConfigured">
                  <el-input
                    v-model="editCredentialForm.appSecret"
                    show-password
                    :placeholder="project?.registryCredentialConfigured ? '留空保持当前凭据；填写后更新凭据' : '请输入 App Secret'"
                  />
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
  Postcard,
  Refresh,
  Tickets,
  Timer,
  WarningFilled,
} from '@element-plus/icons-vue'
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
import ProjectSourceChanges from '@/views/registry/components/ProjectSourceChanges.vue'
import ProjectRouteMissingState from '@/views/registry/components/ProjectRouteMissingState.vue'
import ProjectWorkbenchLoadErrorState from '@/views/registry/components/ProjectWorkbenchLoadErrorState.vue'

const { theme } = useTheme()

const projectKindOptions = PROJECT_KIND_SELECT_OPTIONS
const visibilityOptions = VISIBILITY_SELECT_OPTIONS
const aiCodingDialogTab = ref<'overview' | 'endpoints' | 'credentials'>('overview')

let loadAiCodingAccessFn: (projectId: number) => Promise<void> = async () => {}

const {
  projectCode,
  project,
  instances,
  loading,
  loadingInstances,
  loadingPageCatalog,
  projectMissing,
  loadError,
  projectDetailLoadError,
  instancesLoadError,
  pageCatalogLoadError,
  offlineInstanceCount,
  isSdkBackedProject,
  refresh,
  loadInstances,
  loadPageCatalog,
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
  credentialPolicyLoading,
  credentialPolicySaving,
  aiCodingAccessLoadError,
  credentialPolicyLoadError,
  handoffActivationTtlHours,
  taskTokenTtlHours,
  credentialPolicyCustomized,
  aiCodingInfoRows,
  saveAiCodingAccess,
  clearAiCodingAccess,
  saveCredentialPolicy,
  openAiCodingDialog,
  copyText,
  copyAiCodingBundle,
  loadAiCodingAccess,
} = useRegistryProjectAiCodingAccess({
  project,
  projectCode,
})

loadAiCodingAccessFn = loadAiCodingAccess

function reloadAiCodingSettings() {
  if (project.value?.id) {
    void loadAiCodingAccess(project.value.id)
  }
}

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
  projectSection,
  selectProjectSection,
  goCapabilitySync,
  goCapability,
  goScanProjectDetail,
  goWorkflowList,
  goPageActionGovernance,
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

const { workbenchGroups } = useRegistryProjectWorkbench({
  project,
  projectCode,
  isSdkBackedProject,
  goCapabilitySync,
  goCapability,
  goScanProjectDetail,
  goWorkflowList,
  goPageActionGovernance,
  goPageAssistantWizard,
  goSdkAccessWizard,
})

const headerMetaItems = computed<HeaderMetaItem[]>(() => {
  if (projectMissing.value) {
    return [{
      key: 'status',
      label: '状态',
      value: '项目不存在',
      tone: 'danger',
    }]
  }
  if (loadError.value) {
    return [{
      key: 'status',
      label: '状态',
      value: '数据不可用',
      tone: 'danger',
    }]
  }
  if (!project.value) {
    return [{
      key: 'status',
      label: '状态',
      value: loading.value ? '正在加载' : '尚未加载',
      tone: 'info',
    }]
  }
  return [
    {
      key: 'status',
      label: '状态',
      value: project.value.projectKind
        ? formatProjectKindLabel(project.value.projectKind)
        : '未标注',
      tone: project.value.projectKind ? 'success' : 'info',
    },
    {
      key: 'sdk',
      label: 'SDK',
      value: project.value.sdkVersion
        || (['REGISTERED', 'HYBRID'].includes(project.value.projectKind || '')
          ? 'Starter'
          : '-'),
    },
    {
      key: 'visibility',
      label: '可见性',
      value: project.value.visibility
        ? formatVisibilityLabel(project.value.visibility)
        : '未标注',
    },
    { key: 'owner', label: '负责人', value: project.value.owner || '-' },
  ]
})

onMounted(refresh)
</script>

<style scoped lang="scss">
@use './styles/RegistryProjectDetail.scss';
</style>

<style lang="scss">
@use './styles/RegistryProjectDetail.global.scss';
</style>
