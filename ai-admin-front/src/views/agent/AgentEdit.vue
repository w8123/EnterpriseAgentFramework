<template>
  <WorkbenchPage density="compact" full-height>
    <PageHeader
      variant="standard"
      domain="agent"
      :title="isNew ? '新建 Agent' : `Agent Supervisor 工作台 - ${form.name || agentId}`"
      eyebrow="Agent / Supervisor"
      density="compact"
      compact
      :artwork="false"
    >
      <template #tags>
        <StatusTag
          :label="form.enabled !== false ? '启用' : '停用'"
          :tone="form.enabled !== false ? 'success' : 'info'"
        />
      </template>
      <template #actions>
        <el-button v-if="!isNew" :icon="Clock" @click="openConfigVersions">配置版本</el-button>
        <el-button v-if="!isNew" :icon="VideoPlay" @click="openDebug">调试</el-button>
        <el-button v-if="!isNew" type="success" plain :loading="publishing" @click="handlePublish">
          发布 Supervisor 配置
        </el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </template>
    </PageHeader>

    <el-form
      ref="formRef"
      class="agent-config-form"
      :model="form"
      :rules="rules"
      label-position="top"
      v-loading="pageLoading"
    >
      <main class="workbench-layout">
        <section class="agent-workbench__main">
          <div class="agent-foundation-grid">
            <WorkbenchPanel
              class="agent-core-panel"
              title="Agent 身份与接入"
              description="维护稳定身份、项目归属和业务访问范围。"
              density="compact"
            >
              <template #actions>
                <el-switch v-model="form.enabled" active-text="启用" inactive-text="停用" />
              </template>

              <div class="form-grid two agent-identity-grid">
                <el-form-item label="名称" prop="name">
                  <el-input v-model="form.name" placeholder="如：合同审核助手" />
                </el-form-item>
                <el-form-item label="keySlug" prop="keySlug">
                  <el-input v-model="form.keySlug" placeholder="如 contract-review" :disabled="!isNew" />
                </el-form-item>
                <el-form-item label="所属项目">
                  <el-select
                    v-model="form.projectId"
                    clearable
                    filterable
                    placeholder="平台级 / 全局"
                    @change="handleProjectChange"
                  >
                    <el-option
                      v-for="project in scanProjects"
                      :key="project.id"
                      :label="projectOptionLabel(project)"
                      :value="project.id"
                    />
                  </el-select>
                </el-form-item>
                <el-form-item label="运行角色">
                  <el-select
                    v-model="form.allowedRoles"
                    multiple
                    filterable
                    allow-create
                    default-first-option
                    collapse-tags
                    collapse-tags-tooltip
                    placeholder="留空表示不限制业务角色"
                  />
                </el-form-item>
                <el-form-item label="描述" class="wide">
                  <el-input v-model="form.description" type="textarea" :rows="3" placeholder="一句话描述 Agent 的职责与适用场景" />
                </el-form-item>
              </div>
            </WorkbenchPanel>

            <WorkbenchPanel
              class="agent-core-panel"
              title="Supervisor 运行配置"
              description="配置模型和行为边界；发布后的版本才会进入 Runtime。"
              density="compact"
            >
              <template #actions>
                <el-button plain :icon="Setting" @click="advancedSettingsVisible = true">高级设置</el-button>
                <StatusTag :label="configStatusLabel" :tone="configStatusTone" />
              </template>

              <div class="supervisor-config-stack">
                <el-form-item label="默认模型">
                  <el-select
                    v-model="supervisor.modelInstanceId"
                    clearable
                    filterable
                    class="workflow-tool-select"
                    placeholder="发布前必须选择模型"
                  >
                    <el-option
                      v-for="item in llmModelInstances"
                      :key="item.id"
                      :label="`${item.name} (${item.modelName})`"
                      :value="item.id"
                    />
                  </el-select>
                </el-form-item>

                <el-form-item label="System Prompt">
                  <el-input
                    v-model="supervisor.systemPrompt"
                    type="textarea"
                    :rows="5"
                    placeholder="定义 Supervisor 的理解、规划、工具选择与回答边界"
                  />
                </el-form-item>
              </div>
            </WorkbenchPanel>
          </div>

        <WorkbenchPanel
          title="Agent 工具与能力"
          description="统一配置 Supervisor 可调用的 Workflow、Tool、MCP、CLI 等能力；当前支持 Workflow-as-Tool。"
          density="compact"
        >
          <template #actions>
            <el-button v-if="workflowTools.length" type="primary" plain :icon="Plus" @click="openWorkflowPicker">
              选择 Workflow
            </el-button>
            <StatusTag :label="`${workflowTools.length} 个 Workflow`" tone="neutral" />
          </template>
          <div v-if="!workflowTools.length" class="workflow-tool-zero-state">
            <span class="workflow-tool-zero-state__icon">
              <el-icon><Plus /></el-icon>
            </span>
            <div class="workflow-tool-zero-state__copy">
              <strong>还没有可调用的能力</strong>
              <p>先添加已发布 Workflow；后续 Tool、MCP、CLI 也会在这里统一管理。</p>
            </div>
            <el-button type="primary" plain :icon="Plus" @click="openWorkflowPicker">添加第一个 Workflow</el-button>
          </div>
          <el-table v-if="workflowTools.length" :data="workflowTools" row-key="workflowId" class="workflow-tool-table">
            <el-table-column type="expand">
              <template #default="{ row }">
                <div class="tool-override-grid">
                  <el-form-item label="说明覆盖">
                    <el-input v-model="row.descriptionOverride" type="textarea" :rows="2" placeholder="留空时沿用 Workflow 描述" />
                  </el-form-item>
                  <el-form-item label="输入 Schema">
                    <el-input v-model="row.inputSchemaOverrideJson" type="textarea" :rows="3" placeholder="留空时沿用 Workflow 输入 Schema" />
                  </el-form-item>
                  <el-form-item label="输出 Schema">
                    <el-input v-model="row.outputSchemaOverrideJson" type="textarea" :rows="3" placeholder="留空时沿用 Workflow 输出 Schema" />
                  </el-form-item>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="Workflow" min-width="180">
              <template #default="{ row }">
                <strong>{{ row.workflowName || workflowById(row.workflowId)?.name || row.workflowId }}</strong>
                <small class="tool-cell-hint">{{ row.workflowKeySlug || workflowById(row.workflowId)?.keySlug }}</small>
              </template>
            </el-table-column>
            <el-table-column label="Tool Name" min-width="190">
              <template #default="{ row }"><el-input v-model="row.toolName" /></template>
            </el-table-column>
            <el-table-column label="风险" width="116">
              <template #default="{ row }">
                <el-select v-model="row.riskLevel" @change="syncReadOnly(row)">
                  <el-option label="只读" value="READ" />
                  <el-option label="写入" value="WRITE" />
                  <el-option label="页面动作" value="PAGE_ACTION" />
                  <el-option label="不可逆" value="IRREVERSIBLE" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="权限键" min-width="190">
              <template #default="{ row }"><el-input v-model="row.permissionKey" /></template>
            </el-table-column>
            <el-table-column label="启用" width="72" align="center">
              <template #default="{ row }"><el-switch v-model="row.enabled" /></template>
            </el-table-column>
            <el-table-column label="顺序" width="132" align="center">
              <template #default="{ $index }">
                <el-button link :icon="ArrowUp" :disabled="$index === 0" @click="moveWorkflowTool($index, -1)" />
                <el-button link :icon="ArrowDown" :disabled="$index === workflowTools.length - 1" @click="moveWorkflowTool($index, 1)" />
                <el-button link type="danger" :icon="Delete" @click="removeWorkflowTool($index)" />
              </template>
            </el-table-column>
          </el-table>
          <el-alert
            type="info"
            :closable="false"
            title="用户明确要求打开或操作页面时，Supervisor 才会选择含 PAGE_ACTION 的 Workflow；普通事实查询优先 API Workflow。"
          />
        </WorkbenchPanel>
        </section>

      </main>

      <AppDialog
        v-model="workflowPickerVisible"
        title="选择 Workflow 工具"
        width="900px"
        destroy-on-close
      >
        <div class="workflow-picker-intro">
          <div>
            <strong>从已发布 Workflow 中选择</strong>
            <p>添加后可继续配置 Tool Name、风险等级、权限键和 Schema。</p>
          </div>
          <span>{{ workflowPickerTotal }} 个可用</span>
        </div>
        <div class="workflow-picker-searchbar">
          <el-input
            v-model="workflowPickerKeyword"
            clearable
            :prefix-icon="Search"
            placeholder="搜索 Workflow 名称、keySlug、描述、项目或类型"
            @input="scheduleWorkflowPickerSearch"
          />
          <div class="workflow-picker-search-summary">
            <span>{{ workflowPickerTotal }} 个结果</span>
            <i />
            <strong>已选 {{ workflowPickerSelection.length }}</strong>
          </div>
        </div>
        <div class="workflow-picker-results" v-loading="workflowPickerLoading">
          <div v-if="workflowPickerRows.length" class="workflow-picker-list">
            <div
              v-for="workflow in workflowPickerRows"
              :key="workflow.id"
              class="workflow-picker-item"
              :class="{ 'is-selected': workflowPickerSelection.includes(workflow.id) }"
              role="checkbox"
              tabindex="0"
              :aria-checked="workflowPickerSelection.includes(workflow.id)"
              @click="toggleWorkflowPickerRow(workflow.id)"
              @keydown.enter.prevent="toggleWorkflowPickerRow(workflow.id)"
              @keydown.space.prevent="toggleWorkflowPickerRow(workflow.id)"
            >
              <el-checkbox
                class="workflow-picker-checkbox"
                :model-value="workflowPickerSelection.includes(workflow.id)"
                @click.stop
                @change="toggleWorkflowPickerRow(workflow.id, Boolean($event))"
              />
              <div class="workflow-avatar" :class="workflowAvatarClass(workflow.workflowType)">
                {{ workflowInitial(workflow.name) }}
              </div>
              <div class="workflow-picker-copy">
                <div class="workflow-picker-name-line">
                  <strong>{{ workflow.name }}</strong>
                  <el-tag :type="workflowTypeTagType(workflow.workflowType)" effect="light" size="small">
                    {{ formatWorkflowTypeLabel(workflow.workflowType) }}
                  </el-tag>
                </div>
                <span>{{ workflow.keySlug }}</span>
                <p :title="workflow.description || '可执行图资产'">
                  {{ workflow.description || '可执行图资产' }}
                </p>
              </div>
              <div class="workflow-picker-project" :title="workflow.projectCode || '平台级 Workflow'">
                <span>所属项目</span>
                <strong>{{ workflow.projectCode || '平台级' }}</strong>
              </div>
              <div v-if="workflowPickerSelection.includes(workflow.id)" class="workflow-picker-selected-mark">
                <el-icon><Check /></el-icon>
              </div>
            </div>
          </div>
          <el-empty
            v-else-if="!workflowPickerLoading"
            class="workflow-picker-no-results"
            description="没有匹配的已发布 Workflow"
            :image-size="72"
          />
        </div>
        <div v-if="workflowPickerTotal" class="workflow-picker-pagination">
          <el-pagination
            v-model:current-page="workflowPickerPage"
            background
            layout="total, prev, pager, next"
            :page-size="workflowPickerPageSize"
            :total="workflowPickerTotal"
            @current-change="loadWorkflowPickerPage"
          />
        </div>
        <template #footer>
          <el-button @click="workflowPickerVisible = false">取消</el-button>
          <el-button type="primary" @click="applyWorkflowPickerSelection">
            确认选择（{{ workflowPickerSelection.length }}）
          </el-button>
        </template>
      </AppDialog>

      <AppDrawer
        v-model="advancedSettingsVisible"
        title="Supervisor 高级设置"
        description="大多数 Agent 使用默认值即可；这些设置会随 Supervisor 配置草稿一起保存。"
        size="720px"
      >
        <div class="form-grid two">
          <el-form-item label="运行时">
            <el-input model-value="AgentScope Java 2.0.0 GA" disabled />
          </el-form-item>
          <el-form-item label="工具目录">
            <el-input model-value="ALLOW_LIST（显式白名单）" disabled />
          </el-form-item>
          <el-form-item label="权限策略" class="wide">
            <el-select v-model="supervisor.policyProfile">
              <el-option label="研发（保留风险边界）" value="DEV_ALLOW_ALL" />
              <el-option label="标准策略" value="STANDARD" />
              <el-option label="严格策略" value="STRICT" />
            </el-select>
          </el-form-item>
          <el-form-item label="最大计划步数">
            <el-input-number v-model="supervisor.maxPlanSteps" :min="1" :max="20" />
          </el-form-item>
          <el-form-item label="Workflow 调用">
            <el-input-number v-model="supervisor.maxWorkflowCalls" :min="1" :max="20" />
          </el-form-item>
          <el-form-item label="最大重规划">
            <el-input-number v-model="supervisor.maxReplans" :min="0" :max="10" />
          </el-form-item>
          <el-form-item label="总超时（秒）">
            <el-input-number v-model="totalTimeoutSeconds" :min="10" :max="600" />
          </el-form-item>
          <el-form-item label="Workflow 超时（秒）">
            <el-input-number v-model="workflowTimeoutSeconds" :min="5" :max="300" />
          </el-form-item>
          <el-form-item label="页面等待（秒）">
            <el-input-number v-model="pageBridgeTimeoutSeconds" :min="5" :max="120" />
          </el-form-item>
          <el-form-item label="并行只读工具" class="wide">
            <el-switch v-model="supervisor.parallelReadOnly" active-text="允许" inactive-text="串行" />
          </el-form-item>
        </div>
        <el-alert
          class="advanced-settings-note"
          :type="supervisor.policyProfile === 'DEV_ALLOW_ALL' ? 'warning' : 'success'"
          :closable="false"
          :title="policyProfileHint"
        />
        <el-alert
          class="advanced-settings-note"
          type="info"
          :closable="false"
          title="总超时是整次 Supervisor 执行上限，应大于单个 Workflow 超时；页面等待只控制跨页面导航与动作回传。"
        />
        <el-form-item label="扩展配置">
          <el-input
            v-model="entryConfigText"
            type="textarea"
            :rows="6"
            placeholder='策略 JSON，如 {"policy":{"allowedTenantIds":["default"],"permissionRoles":{"team:read":["operator"]},"irreversibleMode":"DENY"}}'
          />
        </el-form-item>
        <template #footer>
          <el-button type="primary" @click="advancedSettingsVisible = false">完成</el-button>
        </template>
      </AppDrawer>
    </el-form>

    <AppDrawer
      v-model="versionDrawerVisible"
      title="Supervisor 配置版本"
      description="ACTIVE / ARCHIVED 是不可变快照；需要修改时先复制为新的 DRAFT。"
      size="680px"
    >
      <el-table :data="configVersions" class="config-version-table">
        <el-table-column label="版本" width="90">
          <template #default="{ row }">v{{ row.versionNo }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }"><el-tag :type="versionStatusType(row.status)">{{ row.status }}</el-tag></template>
        </el-table-column>
        <el-table-column label="运行时" prop="runtimeType" min-width="120" />
        <el-table-column label="工具" width="72">
          <template #default="{ row }">{{ row.tools?.length || 0 }}</template>
        </el-table-column>
        <el-table-column label="更新时间" min-width="168">
          <template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="168" fixed="right">
          <template #default="{ row }">
            <el-button link @click="loadConfigToForm(row)">查看</el-button>
            <el-button v-if="row.status !== 'DRAFT'" link type="primary" @click="handleCopyVersion(row)">复制为草稿</el-button>
          </template>
        </el-table-column>
      </el-table>
    </AppDrawer>
  </WorkbenchPage>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { ArrowDown, ArrowUp, Check, Clock, Delete, Plus, Search, Setting, VideoPlay } from '@element-plus/icons-vue'
import PageHeader from '@/components/common/PageHeader.vue'
import AppDialog from '@/components/common/AppDialog.vue'
import AppDrawer from '@/components/common/AppDrawer.vue'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import WorkbenchPanel from '@/components/common/WorkbenchPanel.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import type {
  AgentConfigDraft,
  AgentConfigVersion,
  Agent,
  AgentIdentityForm,
  AgentWorkflowToolConfig,
} from '@/types/agent'
import type { WorkflowDefinition } from '@/types/workflow'
import {
  createAgent,
  copyAgentConfigToDraft,
  getAgent,
  listAgentConfigVersions,
  publishAgentConfig,
  saveAgentConfigDraft,
  searchWorkflows,
  updateAgent,
} from '@/api/workflow'
import { getScanProjects } from '@/api/scanProject'
import { getModelInstances } from '@/api/model'
import type { ScanProject } from '@/types/scanProject'
import type { ModelInstance } from '@/types/model'
import { useProjectStore } from '@/store/project'
import { formatWorkflowTypeLabel } from '@/utils/workflowLabels'

const route = useRoute()
const router = useRouter()
const advancedSettingsVisible = ref(false)
const workflowPickerVisible = ref(false)
const workflowPickerKeyword = ref('')
const workflowPickerSelection = ref<string[]>([])
const workflowPickerPage = ref(1)
const workflowPickerPageSize = ref(10)
const workflowPickerRows = ref<WorkflowDefinition[]>([])
const workflowPickerTotal = ref(0)
const workflowPickerLoading = ref(false)
const projectStore = useProjectStore()
const agentId = route.params.id as string
const isNew = agentId === 'new'

const formRef = ref<FormInstance>()
const pageLoading = ref(false)
const saving = ref(false)
const publishing = ref(false)
const scanProjects = ref<ScanProject[]>([])
const llmModelInstances = ref<ModelInstance[]>([])
const publishedWorkflows = ref<WorkflowDefinition[]>([])
const workflowTools = ref<AgentWorkflowToolConfig[]>([])
const configVersions = ref<AgentConfigVersion[]>([])
const currentConfig = ref<AgentConfigVersion | null>(null)
const versionDrawerVisible = ref(false)
const entryConfigText = ref('{}')
const DEFAULT_TOTAL_TIMEOUT_MS = 300_000
const DEFAULT_WORKFLOW_TIMEOUT_MS = 180_000
const DEFAULT_PAGE_BRIDGE_TIMEOUT_MS = 30_000

const supervisor = reactive({
  systemPrompt: '',
  modelInstanceId: '',
  maxPlanSteps: 6,
  maxWorkflowCalls: 4,
  maxReplans: 2,
  totalTimeoutMs: DEFAULT_TOTAL_TIMEOUT_MS,
  workflowTimeoutMs: DEFAULT_WORKFLOW_TIMEOUT_MS,
  pageBridgeTimeoutMs: DEFAULT_PAGE_BRIDGE_TIMEOUT_MS,
  parallelReadOnly: true,
  policyProfile: 'DEV_ALLOW_ALL',
})

const totalTimeoutSeconds = computed({
  get: () => Math.round(supervisor.totalTimeoutMs / 1000),
  set: (value: number) => { supervisor.totalTimeoutMs = value * 1000 },
})
const workflowTimeoutSeconds = computed({
  get: () => Math.round(supervisor.workflowTimeoutMs / 1000),
  set: (value: number) => { supervisor.workflowTimeoutMs = value * 1000 },
})
const pageBridgeTimeoutSeconds = computed({
  get: () => Math.round(supervisor.pageBridgeTimeoutMs / 1000),
  set: (value: number) => { supervisor.pageBridgeTimeoutMs = value * 1000 },
})
const configStatusLabel = computed(() => currentConfig.value
  ? `${currentConfig.value.status} · v${currentConfig.value.versionNo}`
  : '尚未保存草稿')
const configStatusTone = computed(() => currentConfig.value?.status === 'ACTIVE' ? 'success' : 'warning')
const policyProfileHint = computed(() => supervisor.policyProfile === 'DEV_ALLOW_ALL'
  ? '研发策略仅跳过 tenant allowlist 与 permission-role 映射；页面显式意图、WRITE 确认和不可逆默认拒绝仍然生效。'
  : '执行前校验 project、tenant、Agent roles、permissionKey 角色映射和风险等级，并写入 Guard / Trace。')
const selectedWorkflowIds = computed<string[]>({
  get: () => workflowTools.value.map((tool) => tool.workflowId),
  set: (workflowIds) => syncWorkflowSelection(workflowIds),
})

let workflowPickerSearchTimer: ReturnType<typeof setTimeout> | null = null
let workflowPickerRequestSequence = 0

const form = reactive<AgentIdentityForm>({
  keySlug: '',
  name: '',
  description: '',
  projectId: null,
  projectCode: null,
  visibility: 'PROJECT',
  allowedRoles: [],
  enabled: true,
})

const rules: FormRules = {
  name: [{ required: true, message: '请输入智能体名称', trigger: 'blur' }],
  keySlug: [{ required: true, message: '请输入 keySlug', trigger: 'blur' }],
}

function projectOptionLabel(project?: ScanProject | null) {
  if (!project) return ''
  const code = project.projectCode ? ` / ${project.projectCode}` : ''
  const env = project.environment ? ` · ${project.environment}` : ''
  return `${project.name}${code}${env}`
}

function projectCodeById(projectId?: number | null) {
  if (projectId == null) return null
  return scanProjects.value.find((project) => project.id === projectId)?.projectCode || null
}

function parseAllowedRoles(json?: string | null): string[] {
  if (!json?.trim()) return []
  try {
    const parsed = JSON.parse(json)
    return Array.isArray(parsed) ? parsed.map(String) : []
  } catch {
    return []
  }
}

function entryToForm(entry: Agent): void {
  Object.assign(form, {
    keySlug: entry.keySlug || '',
    name: entry.name || '',
    description: entry.description || '',
    projectId: entry.projectId ?? null,
    projectCode: entry.projectCode ?? null,
    visibility: entry.visibility || 'PROJECT',
    allowedRoles: parseAllowedRoles(entry.allowedRolesJson),
    enabled: entry.enabled !== false,
  })
}

function buildPayload(): Partial<Agent> {
  return {
    keySlug: form.keySlug?.trim(),
    name: form.name?.trim(),
    description: form.description || null,
    projectId: form.projectId ?? null,
    projectCode: form.projectCode ?? projectCodeById(form.projectId) ?? null,
    visibility: form.visibility || 'PROJECT',
    allowedRolesJson: form.allowedRoles?.length ? JSON.stringify(form.allowedRoles) : null,
    enabled: form.enabled !== false,
  }
}

function workflowToolName(workflow: WorkflowDefinition): string {
  const raw = (workflow.keySlug || workflow.id || 'workflow').replace(/[^A-Za-z0-9_]/g, '_')
  const prefixed = /^[A-Za-z]/.test(raw) ? raw : `wf_${raw}`
  return prefixed.length >= 2 ? prefixed.slice(0, 128) : `wf_${prefixed}`
}

function workflowById(workflowId: string) {
  return publishedWorkflows.value.find((item) => item.id === workflowId)
}

function openWorkflowPicker() {
  if (workflowPickerSearchTimer) {
    clearTimeout(workflowPickerSearchTimer)
    workflowPickerSearchTimer = null
  }
  workflowPickerKeyword.value = ''
  workflowPickerSelection.value = [...selectedWorkflowIds.value]
  workflowPickerPage.value = 1
  workflowPickerRows.value = []
  workflowPickerTotal.value = 0
  workflowPickerVisible.value = true
  void loadWorkflowPickerPage()
}

function scheduleWorkflowPickerSearch() {
  workflowPickerPage.value = 1
  if (workflowPickerSearchTimer) clearTimeout(workflowPickerSearchTimer)
  workflowPickerSearchTimer = setTimeout(() => {
    workflowPickerSearchTimer = null
    void loadWorkflowPickerPage()
  }, 300)
}

function toggleWorkflowPickerRow(workflowId: string, selected?: boolean) {
  const next = new Set(workflowPickerSelection.value)
  const shouldSelect = selected ?? !next.has(workflowId)
  if (shouldSelect) next.add(workflowId)
  else next.delete(workflowId)
  workflowPickerSelection.value = [...next]
}

function applyWorkflowPickerSelection() {
  selectedWorkflowIds.value = [...workflowPickerSelection.value]
  workflowPickerVisible.value = false
}

function workflowInitial(value?: string | null) {
  return (value || 'W').trim().slice(0, 1).toUpperCase()
}

function workflowTypeKey(value?: string | null) {
  return String(value || 'CHAT').toUpperCase()
}

function workflowAvatarClass(type?: string | null) {
  const key = workflowTypeKey(type)
  if (key === 'PAGE_ACTION' || key === 'PAGE_ASSISTANT') return 'page-action'
  if (key === 'SDK_GRAPH') return 'sdk-graph'
  return 'chat'
}

function workflowTypeTagType(type?: string | null) {
  const key = workflowTypeKey(type)
  if (key === 'PAGE_ACTION' || key === 'PAGE_ASSISTANT') return 'success'
  if (key === 'SDK_GRAPH') return 'warning'
  return 'info'
}

function cachePublishedWorkflows(workflows: WorkflowDefinition[]) {
  const cached = new Map(publishedWorkflows.value.map((workflow) => [workflow.id, workflow]))
  for (const workflow of workflows) cached.set(workflow.id, workflow)
  publishedWorkflows.value = [...cached.values()]
}

async function loadWorkflowPickerPage() {
  const requestSequence = ++workflowPickerRequestSequence
  workflowPickerLoading.value = true
  try {
    const { data } = await searchWorkflows({
      status: 'ACTIVE',
      keyword: workflowPickerKeyword.value.trim() || undefined,
      current: workflowPickerPage.value,
      size: workflowPickerPageSize.value,
    })
    if (requestSequence !== workflowPickerRequestSequence) return
    const rows = Array.isArray(data?.records) ? data.records : []
    workflowPickerRows.value = rows
    workflowPickerTotal.value = Math.max(Number(data?.total) || 0, 0)
    cachePublishedWorkflows(rows)

    const maxPage = Math.max(1, Math.ceil(workflowPickerTotal.value / workflowPickerPageSize.value))
    if (workflowPickerPage.value > maxPage) {
      workflowPickerPage.value = maxPage
      void loadWorkflowPickerPage()
    }
  } catch {
    if (requestSequence !== workflowPickerRequestSequence) return
    workflowPickerRows.value = []
    workflowPickerTotal.value = 0
    ElMessage.error('加载可选 Workflow 失败')
  } finally {
    if (requestSequence === workflowPickerRequestSequence) {
      workflowPickerLoading.value = false
    }
  }
}

function defaultWorkflowTool(workflow: WorkflowDefinition): AgentWorkflowToolConfig {
  const pageAction = workflow.workflowType === 'PAGE_ACTION' || workflow.workflowType === 'PAGE_ASSISTANT'
  return {
    workflowId: workflow.id,
    workflowKeySlug: workflow.keySlug,
    workflowName: workflow.name,
    toolName: workflowToolName(workflow),
    riskLevel: pageAction ? 'PAGE_ACTION' : 'READ',
    permissionKey: `workflow:${workflow.keySlug}`,
    readOnly: !pageAction,
    enabled: true,
    priority: workflowTools.value.length,
  }
}

function syncWorkflowSelection(workflowIds: string[]) {
  const current = new Map(workflowTools.value.map((tool) => [tool.workflowId, tool]))
  workflowTools.value = workflowIds.flatMap((workflowId, priority) => {
    const existing = current.get(workflowId)
    const workflow = workflowById(workflowId)
    if (!existing && !workflow) return []
    return [{ ...(existing || defaultWorkflowTool(workflow!)), priority }]
  })
}

function syncReadOnly(tool: AgentWorkflowToolConfig) {
  tool.readOnly = tool.riskLevel === 'READ'
}

function moveWorkflowTool(index: number, delta: number) {
  const target = index + delta
  if (target < 0 || target >= workflowTools.value.length) return
  const next = [...workflowTools.value]
  ;[next[index], next[target]] = [next[target], next[index]]
  workflowTools.value = next.map((tool, priority) => ({ ...tool, priority }))
}

function removeWorkflowTool(index: number) {
  workflowTools.value = workflowTools.value
    .filter((_, itemIndex) => itemIndex !== index)
    .map((tool, priority) => ({ ...tool, priority }))
}

function validateJson(value: string, label: string) {
  if (!value.trim()) return
  try {
    JSON.parse(value)
  } catch {
    throw new Error(`${label} JSON 格式无效`)
  }
}

function buildConfigDraft(): AgentConfigDraft {
  validateJson(entryConfigText.value, '扩展配置')
  const toolNames = new Set<string>()
  for (const tool of workflowTools.value) {
    if (!/^[A-Za-z][A-Za-z0-9_]{1,127}$/.test(tool.toolName || '')) {
      throw new Error(`Workflow 工具 ${tool.workflowName || tool.workflowId} 的 Tool Name 格式无效`)
    }
    if (toolNames.has(tool.toolName)) throw new Error(`Tool Name 重复：${tool.toolName}`)
    toolNames.add(tool.toolName)
    validateJson(tool.inputSchemaOverrideJson || '', `${tool.toolName} 输入 Schema`)
    validateJson(tool.outputSchemaOverrideJson || '', `${tool.toolName} 输出 Schema`)
  }
  return {
    runtimeType: 'AGENTSCOPE',
    systemPrompt: supervisor.systemPrompt || null,
    modelInstanceId: supervisor.modelInstanceId || null,
    maxPlanSteps: supervisor.maxPlanSteps,
    maxWorkflowCalls: supervisor.maxWorkflowCalls,
    maxReplans: supervisor.maxReplans,
    totalTimeoutMs: supervisor.totalTimeoutMs,
    workflowTimeoutMs: supervisor.workflowTimeoutMs,
    pageBridgeTimeoutMs: supervisor.pageBridgeTimeoutMs,
    parallelReadOnly: supervisor.parallelReadOnly,
    policyProfile: supervisor.policyProfile,
    toolCatalogMode: 'ALLOW_LIST',
    configJson: entryConfigText.value.trim() || null,
    tools: workflowTools.value.map((tool, priority) => ({ ...tool, priority })),
  }
}

async function handleProjectChange(projectId: number | null | undefined) {
  form.projectCode = projectCodeById(projectId)
}

async function handleSave() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    const payload = buildPayload()
    if (isNew) {
      const { data } = await createAgent(payload)
      const { data: draft } = await saveAgentConfigDraft(data.id, buildConfigDraft())
      currentConfig.value = draft
      ElMessage.success('Agent 已创建，Supervisor 配置已保存为草稿')
    } else {
      await updateAgent(agentId, payload)
      const { data } = await saveAgentConfigDraft(agentId, buildConfigDraft())
      currentConfig.value = data
      ElMessage.success('保存成功')
    }
    router.push('/agent')
  } catch (error) {
    const message = error instanceof Error ? error.message : ''
    const response = (error as { response?: { data?: { message?: string; error?: string } } })?.response
    ElMessage.error(message || response?.data?.message || response?.data?.error || '保存失败')
  } finally {
    saving.value = false
  }
}

async function handlePublish() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid || isNew) return
  publishing.value = true
  try {
    await ElMessageBox.confirm(
      '发布后，该版本将成为 Runtime 唯一生效的 Supervisor 配置，当前 ACTIVE 版本会归档。确认继续？',
      '发布 Supervisor 配置',
      { type: 'warning', confirmButtonText: '确认发布' },
    )
    await updateAgent(agentId, buildPayload())
    const { data: draft } = await saveAgentConfigDraft(agentId, buildConfigDraft())
    const { data: active } = await publishAgentConfig(agentId, draft.id, 'admin-ui')
    currentConfig.value = active
    await refreshConfigVersions()
    ElMessage.success(`Supervisor 配置 v${active.versionNo} 已发布`)
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      const message = error instanceof Error ? error.message : '发布失败'
      ElMessage.error(message)
    }
  } finally {
    publishing.value = false
  }
}

function openDebug() {
  if (!isNew) router.push(`/agent/${agentId}/debug`)
}

async function refreshConfigVersions() {
  if (isNew) return
  const { data } = await listAgentConfigVersions(agentId)
  configVersions.value = Array.isArray(data) ? data : []
}

async function openConfigVersions() {
  await refreshConfigVersions()
  versionDrawerVisible.value = true
}

function loadConfigToForm(config: AgentConfigVersion) {
  currentConfig.value = config
  Object.assign(supervisor, {
    systemPrompt: config.systemPrompt || '',
    modelInstanceId: config.modelInstanceId || '',
    maxPlanSteps: config.maxPlanSteps ?? 6,
    maxWorkflowCalls: config.maxWorkflowCalls ?? 4,
    maxReplans: config.maxReplans ?? 2,
    totalTimeoutMs: config.totalTimeoutMs ?? DEFAULT_TOTAL_TIMEOUT_MS,
    workflowTimeoutMs: config.workflowTimeoutMs ?? DEFAULT_WORKFLOW_TIMEOUT_MS,
    pageBridgeTimeoutMs: config.pageBridgeTimeoutMs ?? DEFAULT_PAGE_BRIDGE_TIMEOUT_MS,
    parallelReadOnly: config.parallelReadOnly !== false,
    policyProfile: config.policyProfile || 'DEV_ALLOW_ALL',
  })
  entryConfigText.value = config.configJson || '{}'
  workflowTools.value = (config.tools || []).map((tool, priority) => ({ ...tool, priority }))
}

async function handleCopyVersion(config: AgentConfigVersion) {
  try {
    await ElMessageBox.confirm(
      `将 v${config.versionNo} 复制为新的可编辑草稿；现有草稿会被替换。确认继续？`,
      '复制配置版本',
      { type: 'warning' },
    )
    const { data } = await copyAgentConfigToDraft(agentId, config.id)
    loadConfigToForm(data)
    await refreshConfigVersions()
    ElMessage.success(`已从 v${config.versionNo} 创建草稿 v${data.versionNo}`)
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error('复制配置版本失败')
  }
}

function versionStatusType(status?: string) {
  if (status === 'ACTIVE') return 'success'
  if (status === 'DRAFT') return 'warning'
  return 'info'
}

function formatDateTime(value?: string | null) {
  if (!value) return '-'
  const parsed = Date.parse(value)
  return Number.isFinite(parsed) ? new Date(parsed).toLocaleString() : value
}

async function loadAgent() {
  if (isNew) return
  pageLoading.value = true
  try {
    const { data } = await getAgent(agentId)
    entryToForm(data)
    await loadAgentConfig()
  } catch {
    ElMessage.error('加载 Agent 失败')
  } finally {
    pageLoading.value = false
  }
}

async function loadAgentConfig() {
  if (isNew) return
  await refreshConfigVersions()
  const config = configVersions.value.find((item) => item.status === 'DRAFT')
    || configVersions.value.find((item) => item.status === 'ACTIVE')
    || configVersions.value[0]
  if (!config) return
  loadConfigToForm(config)
}

async function loadScanProjects() {
  try {
    const { data } = await getScanProjects()
    scanProjects.value = Array.isArray(data) ? data : []
    projectStore.projects = scanProjects.value
    if (isNew) {
      const queryProjectId = Number(route.query.projectId)
      form.projectId = Number.isFinite(queryProjectId) && queryProjectId > 0
        ? queryProjectId
        : projectStore.currentProjectId ?? null
      form.projectCode = projectCodeById(form.projectId)
    }
  } catch {
    scanProjects.value = []
  }
}

async function loadModelInstances() {
  try {
    const { data } = await getModelInstances({ modelType: 'LLM' })
    const list = data?.data ?? (Array.isArray(data) ? data : [])
    llmModelInstances.value = list.filter((item: ModelInstance) => item.status === 'ACTIVE')
  } catch {
    llmModelInstances.value = []
  }
}

onMounted(async () => {
  await Promise.all([loadScanProjects(), loadModelInstances()])
  await loadAgent()
})

onUnmounted(() => {
  if (workflowPickerSearchTimer) clearTimeout(workflowPickerSearchTimer)
})
</script>

<style scoped lang="scss">
.workbench-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: var(--section-gap);
}

.agent-workbench__main,
.form-grid {
  min-width: 0;
}

.agent-workbench__main {
  display: grid;
  gap: var(--section-gap);
}

.agent-foundation-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.03fr) minmax(0, 0.97fr);
  gap: var(--section-gap);
  align-items: stretch;
}

.agent-core-panel {
  height: 100%;
}

.form-grid {
  display: grid;
  gap: var(--section-gap);
}

.form-grid.two {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.agent-identity-grid,
.supervisor-config-stack {
  gap: 16px;
}

.supervisor-config-stack {
  display: grid;
}

.agent-identity-grid :deep(.el-form-item),
.supervisor-config-stack :deep(.el-form-item) {
  margin-bottom: 0;
}

.agent-config-form :deep(.el-form-item__label) {
  padding-bottom: 7px;
  color: var(--text-secondary);
  font-weight: 650;
  line-height: 20px;
}

.agent-config-form :deep(.el-input__wrapper),
.agent-config-form :deep(.el-select__wrapper),
.agent-config-form :deep(.el-textarea__inner) {
  border-radius: 9px;
}

.wide {
  grid-column: span 2;
}

.advanced-settings-note {
  margin-bottom: var(--section-gap);
}

.workflow-tool-select {
  width: 100%;
}

.workflow-tool-zero-state {
  display: flex;
  min-height: 84px;
  align-items: center;
  gap: 14px;
  padding: 14px 16px;
  border: 1px dashed rgb(var(--brand-primary-rgb) / 0.24);
  border-radius: 12px;
  background: rgb(var(--brand-primary-rgb) / 0.025);
}

.workflow-tool-zero-state__icon {
  display: inline-flex;
  width: 40px;
  height: 40px;
  flex: 0 0 40px;
  align-items: center;
  justify-content: center;
  border-radius: 11px;
  color: var(--brand-active);
  background: var(--brand-selected-bg);
  font-size: 18px;
}

.workflow-tool-zero-state__copy {
  min-width: 0;
  flex: 1;
}

.workflow-tool-zero-state__copy strong {
  color: var(--text-primary);
  font-size: 14px;
  line-height: 20px;
}

.workflow-tool-zero-state__copy p {
  margin: 3px 0 0;
  color: var(--text-secondary);
  font-size: 13px;
  line-height: 20px;
}

.workflow-tool-table,
.config-version-table {
  margin-top: 12px;
}

.workflow-picker-intro {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 18px;
}

.workflow-picker-intro strong {
  display: block;
  color: var(--text-primary);
  font-size: 15px;
  line-height: 22px;
}

.workflow-picker-intro p {
  margin: 3px 0 0;
  color: var(--text-secondary);
  font-size: 13px;
  line-height: 20px;
}

.workflow-picker-intro > span {
  flex: 0 0 auto;
  padding: 5px 10px;
  border-radius: 999px;
  color: var(--brand-active);
  background: var(--brand-selected-bg);
  font-size: 12px;
  font-weight: 700;
}

.workflow-picker-searchbar {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 10px;
  border: 1px solid var(--border-divider);
  border-radius: 12px;
  background: var(--surface-raised, rgb(255 255 255 / 0.72));
  box-shadow: 0 8px 24px rgb(15 23 42 / 0.035);
}

.workflow-picker-searchbar .el-input {
  flex: 1;
  min-width: 0;
}

.workflow-picker-searchbar :deep(.el-input__wrapper) {
  min-height: 40px;
  border-radius: 9px;
  box-shadow: 0 0 0 1px rgb(var(--brand-primary-rgb) / 0.15) inset;
}

.workflow-picker-search-summary {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 10px;
  padding: 0 6px;
  color: var(--text-secondary);
  font-size: 12px;
  white-space: nowrap;
}

.workflow-picker-search-summary i {
  width: 1px;
  height: 14px;
  background: var(--border-divider);
}

.workflow-picker-search-summary strong {
  color: var(--brand-active);
}

.workflow-picker-results {
  position: relative;
  min-height: 112px;
}

.workflow-picker-list {
  display: grid;
  gap: 8px;
  max-height: 466px;
  margin-top: 14px;
  padding: 2px;
  overflow-y: auto;
  scrollbar-gutter: stable;
}

.workflow-picker-item {
  position: relative;
  display: grid;
  grid-template-columns: auto auto minmax(0, 1fr) minmax(120px, 170px) 28px;
  align-items: center;
  gap: 13px;
  min-height: 82px;
  padding: 12px 14px;
  border: 1px solid var(--border-divider);
  border-radius: 12px;
  background: var(--surface-raised, rgb(255 255 255 / 0.72));
  cursor: pointer;
  transition: border-color 0.16s ease, background 0.16s ease, box-shadow 0.16s ease, transform 0.16s ease;
}

.workflow-picker-item:hover {
  border-color: rgb(var(--brand-primary-rgb) / 0.28);
  background: rgb(var(--brand-primary-rgb) / 0.025);
  box-shadow: 0 10px 24px rgb(15 23 42 / 0.045);
  transform: translateY(-1px);
}

.workflow-picker-item:focus-visible {
  outline: 2px solid rgb(var(--brand-primary-rgb) / 0.32);
  outline-offset: 2px;
}

.workflow-picker-item.is-selected {
  border-color: rgb(var(--brand-primary-rgb) / 0.46);
  background: var(--brand-selected-bg);
  box-shadow: 0 10px 26px rgb(var(--brand-primary-rgb) / 0.08);
}

.workflow-picker-checkbox {
  flex: 0 0 auto;
}

.workflow-picker-copy {
  min-width: 0;
}

.workflow-picker-name-line {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.workflow-picker-name-line > strong {
  overflow: hidden;
  color: var(--text-primary);
  font-size: 14px;
  line-height: 20px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.workflow-picker-copy > span {
  display: block;
  overflow: hidden;
  margin-top: 2px;
  color: var(--text-muted);
  font-size: 12px;
  line-height: 18px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.workflow-picker-copy > p {
  overflow: hidden;
  margin: 4px 0 0;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 18px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.workflow-picker-project {
  min-width: 0;
  padding-left: 14px;
  border-left: 1px solid var(--border-divider);
}

.workflow-picker-project span,
.workflow-picker-project strong {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.workflow-picker-project span {
  color: var(--text-muted);
  font-size: 11px;
  line-height: 17px;
}

.workflow-picker-project strong {
  margin-top: 3px;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 18px;
}

.workflow-picker-selected-mark {
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  border-radius: 999px;
  color: #fff;
  background: var(--brand-primary);
  box-shadow: 0 6px 14px rgb(var(--brand-primary-rgb) / 0.2);
}

.workflow-picker-no-results {
  min-height: 220px;
}

.workflow-picker-pagination {
  display: flex;
  align-items: center;
  justify-content: center;
  margin-top: 16px;
}

.workflow-avatar {
  display: grid;
  place-items: center;
  flex: 0 0 34px;
  width: 34px;
  height: 34px;
  border-radius: 10px;
  color: #fff;
  font-weight: 800;
}

.workflow-avatar.chat {
  background: linear-gradient(135deg, var(--brand-hover), var(--brand-active));
}

.workflow-avatar.sdk-graph {
  background: linear-gradient(135deg, #22c55e, #14b8a6);
}

.workflow-avatar.page-action {
  background: linear-gradient(135deg, #f97316, #f59e0b);
}

.tool-cell-hint {
  display: block;
  margin-top: 3px;
  color: var(--text-muted);
}

.tool-override-grid {
  display: grid;
  gap: 12px;
  padding: 8px 20px;
}

@media (max-width: 1280px) {
  .agent-foundation-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 960px) {
  .form-grid.two {
    grid-template-columns: 1fr;
  }

  .wide {
    grid-column: auto;
  }

  .workflow-tool-zero-state {
    align-items: flex-start;
    flex-wrap: wrap;
  }

  .workflow-tool-zero-state .el-button {
    width: 100%;
  }
}
</style>
