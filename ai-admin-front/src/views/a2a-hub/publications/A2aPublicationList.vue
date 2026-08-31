<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, Search } from '@element-plus/icons-vue'
import AppDialog from '@/components/common/AppDialog.vue'
import AppDrawer from '@/components/common/AppDrawer.vue'
import WorkbenchPanel from '@/components/common/WorkbenchPanel.vue'
import AgentCardPreview from '@/components/a2a-hub/AgentCardPreview.vue'
import {
  createA2aPublication,
  getA2aPublication,
  listA2aPublications,
  listA2aTrustProfiles,
  preflightA2aPublication,
  publishA2aPublication,
  resumeA2aPublication,
  suspendA2aPublication,
} from '@/api/a2aHub'
import { listAgentConfigVersions, listAgents } from '@/api/workflow'
import type { AgentConfigVersion } from '@/types/agent'
import type { Agent } from '@/types/workflow'
import type {
  A2aEnvironment,
  A2aProtocolSkill,
  A2aPublication,
  A2aPublicationCreateRequest,
  A2aPublicationDetail,
  A2aPublicationRevision,
  A2aTrustProfile,
} from '@/types/a2aHub'
import { a2aFormatDate, a2aLabel, a2aTone } from '@/utils/a2aHub'

const loading = ref(false)
const submitting = ref(false)
const rows = ref<A2aPublication[]>([])
const total = ref(0)
const query = reactive({ search: '', status: '', limit: 20, offset: 0 })

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<A2aPublicationDetail | null>(null)
const selectedRevisionId = ref<number | null>(null)
const selectedRevision = computed(() =>
  detail.value?.revisions.find((item) => item.id === selectedRevisionId.value) ?? null,
)

const createVisible = ref(false)
const step = ref(0)
const agents = ref<Agent[]>([])
const configVersions = ref<AgentConfigVersion[]>([])
const trustProfiles = ref<A2aTrustProfile[]>([])

interface PublicationForm {
  publicationKey: string
  agentId: string
  agentConfigVersionId?: number
  environment: A2aEnvironment
  tenantScope: string
  name: string
  description: string
  agentVersion: string
  publicOrigin: string
  publicHost: string
  trustProfileId?: number
  providerOrganization: string
  providerUrl: string
  documentationUrl: string
  defaultInputModes: string[]
  defaultOutputModes: string[]
  protocolSkills: A2aProtocolSkill[]
}

const form = reactive<PublicationForm>(emptyForm())
const activeConfigs = computed(() => configVersions.value.filter((item) => item.status === 'ACTIVE'))
const applicableTrustProfiles = computed(() => trustProfiles.value.filter((item) =>
  item.status === 'ACTIVE'
  && item.environment === form.environment
  && ['INBOUND', 'BIDIRECTIONAL'].includes(item.direction),
))

function emptySkill(): A2aProtocolSkill {
  return {
    id: '',
    name: '',
    description: '',
    tags: [],
    examples: [],
    inputModes: ['text/plain'],
    outputModes: ['text/plain'],
  }
}

function emptyForm(): PublicationForm {
  return {
    publicationKey: '',
    agentId: '',
    environment: 'DEVELOPMENT',
    tenantScope: '',
    name: '',
    description: '',
    agentVersion: '1.0.0',
    publicOrigin: 'http://localhost:18603',
    publicHost: 'localhost:18603',
    providerOrganization: '',
    providerUrl: '',
    documentationUrl: '',
    defaultInputModes: ['text/plain', 'application/json', 'text/uri-list'],
    defaultOutputModes: ['text/plain'],
    protocolSkills: [emptySkill()],
  }
}

async function reload() {
  loading.value = true
  try {
    const { data } = await listA2aPublications({
      search: query.search || undefined,
      status: query.status || undefined,
      limit: query.limit,
      offset: query.offset,
    })
    rows.value = data?.items ?? []
    total.value = data?.total ?? 0
  } finally {
    loading.value = false
  }
}

function applyFilters() {
  query.offset = 0
  reload()
}

async function openDetail(row: A2aPublication) {
  detailVisible.value = true
  detailLoading.value = true
  try {
    const { data } = await getA2aPublication(row.id)
    detail.value = data
    selectedRevisionId.value = data.publication.currentRevisionId
      ?? data.revisions[0]?.id
      ?? null
  } finally {
    detailLoading.value = false
  }
}

async function loadCreateOptions() {
  const [agentResult, trustResult] = await Promise.all([
    listAgents(),
    listA2aTrustProfiles({ status: 'ACTIVE', limit: 100 }),
  ])
  agents.value = (agentResult.data ?? []).filter((item) => item.enabled && item.activeConfigVersionId)
  trustProfiles.value = trustResult.data?.items ?? []
}

async function openCreate() {
  Object.assign(form, emptyForm())
  configVersions.value = []
  step.value = 0
  createVisible.value = true
  await loadCreateOptions()
}

async function agentChanged(agentId: string) {
  form.agentConfigVersionId = undefined
  const agent = agents.value.find((item) => item.id === agentId)
  if (agent) {
    form.publicationKey = agent.keySlug
    form.name = agent.name
    form.description = agent.description ?? ''
  }
  if (!agentId) return
  const { data } = await listAgentConfigVersions(agentId)
  configVersions.value = data ?? []
  form.agentConfigVersionId = activeConfigs.value[0]?.id
}

function validateStep(): string | null {
  if (step.value === 0 && (!form.agentId || !form.agentConfigVersionId)) return '请选择已有已发布配置版本的 Agent'
  if (step.value === 1) {
    if (!form.publicationKey.trim() || !form.name.trim() || !form.description.trim()) return '请完整填写发布标识、名称和说明'
    const invalid = form.protocolSkills.some((item) =>
      !item.id.trim() || !item.name.trim() || !item.description.trim() || item.tags.length === 0,
    )
    if (invalid) return '每个协议 AgentSkill 都必须填写 ID、名称、说明和至少一个标签'
  }
  if (step.value === 2 && (!form.publicOrigin.trim() || !form.publicHost.trim())) return '请填写公开 Origin 和 Host'
  if (step.value === 3 && !form.trustProfileId) return '请选择适用于当前环境和入站方向的 Trust Profile'
  return null
}

function nextStep() {
  const message = validateStep()
  if (message) {
    ElMessage.warning(message)
    return
  }
  step.value = Math.min(4, step.value + 1)
}

async function submitCreate() {
  const error = validateStep()
  if (error || !form.agentConfigVersionId || !form.trustProfileId) {
    ElMessage.warning(error || '发布草稿信息不完整')
    return
  }
  const body: A2aPublicationCreateRequest = {
    publicationKey: form.publicationKey.trim(),
    agentId: form.agentId,
    environment: form.environment,
    tenantScope: form.tenantScope.trim(),
    publicHost: form.publicHost.trim(),
    trustProfileId: form.trustProfileId,
    revision: {
      agentConfigVersionId: form.agentConfigVersionId,
      agentVersion: form.agentVersion.trim(),
      name: form.name.trim(),
      description: form.description.trim(),
      providerOrganization: form.providerOrganization.trim() || undefined,
      providerUrl: form.providerUrl.trim() || undefined,
      documentationUrl: form.documentationUrl.trim() || undefined,
      publicOrigin: form.publicOrigin.trim(),
      streamingSupported: false,
      pushNotificationsSupported: false,
      extendedCardSupported: false,
      defaultInputModes: [...form.defaultInputModes],
      defaultOutputModes: [...form.defaultOutputModes],
      protocolSkills: form.protocolSkills.map((item) => ({ ...item })),
    },
  }
  submitting.value = true
  try {
    const { data } = await createA2aPublication(body)
    ElMessage.success('Publication 草稿已创建，请执行预检后发布')
    createVisible.value = false
    await reload()
    await openDetail(data.publication)
  } finally {
    submitting.value = false
  }
}

async function executeCommand(kind: 'preflight' | 'publish' | 'suspend' | 'resume', revision?: A2aPublicationRevision) {
  if (!detail.value) return
  const publication = detail.value.publication
  if (kind === 'publish') {
    await ElMessageBox.confirm(
      `确认将修订 r${revision?.revisionNo} 发布到 ${publication.publicHost}？发布后该修订不可变。`,
      '发布确认',
      { type: 'warning' },
    )
  }
  detailLoading.value = true
  try {
    const response = kind === 'preflight'
      ? await preflightA2aPublication(publication.id, revision!.id)
      : kind === 'publish'
        ? await publishA2aPublication(publication.id, revision!.id)
        : kind === 'suspend'
          ? await suspendA2aPublication(publication.id)
          : await resumeA2aPublication(publication.id)
    detail.value = response.data
    selectedRevisionId.value = response.data.publication.currentRevisionId
      ?? selectedRevisionId.value
    ElMessage.success({ preflight: '预检通过', publish: '发布成功', suspend: '已暂停新任务', resume: '已恢复发布' }[kind])
    await reload()
  } finally {
    detailLoading.value = false
  }
}

onMounted(reload)
</script>

<template>
  <section class="publication-page">
    <div class="section-intro">
      <div><h2>本地发布</h2><p>用不可变修订把已发布的 ReachAI Agent 转换为可发现、可授权的 A2A 服务。</p></div>
      <div class="section-actions">
        <el-button :icon="Refresh" :loading="loading" @click="reload">刷新</el-button>
        <el-button type="primary" :icon="Plus" @click="openCreate">创建 Publication</el-button>
      </div>
    </div>

    <WorkbenchPanel level="control" density="compact">
      <div class="filter-row">
        <el-input v-model="query.search" :prefix-icon="Search" clearable placeholder="搜索发布标识、Agent、项目或 Host" @keyup.enter="applyFilters" />
        <el-select v-model="query.status" clearable placeholder="全部状态" @change="applyFilters">
          <el-option v-for="value in ['DRAFT', 'READY', 'PUBLISHED', 'SUSPENDED', 'ARCHIVED']" :key="value" :label="a2aLabel(value)" :value="value" />
        </el-select>
        <el-button type="primary" @click="applyFilters">查询</el-button>
      </div>
    </WorkbenchPanel>

    <el-card class="workbench-list-surface" shadow="never">
      <el-table :data="rows" v-loading="loading" stripe @row-click="openDetail">
        <el-table-column prop="publicationKey" label="Publication" min-width="170">
          <template #default="{ row }"><div class="cell-stack"><strong>{{ row.publicationKey }}</strong><small>{{ row.publicHost }}</small></div></template>
        </el-table-column>
        <el-table-column prop="agentId" label="本地 Agent" min-width="150" show-overflow-tooltip />
        <el-table-column prop="projectCode" label="项目" width="130" show-overflow-tooltip />
        <el-table-column prop="environment" label="环境" width="90"><template #default="{ row }">{{ a2aLabel(row.environment) }}</template></el-table-column>
        <el-table-column label="状态" width="105"><template #default="{ row }"><el-tag :type="a2aTone(row.status)" size="small">{{ a2aLabel(row.status) }}</el-tag></template></el-table-column>
        <el-table-column prop="currentRevisionId" label="当前修订" width="90"><template #default="{ row }">{{ row.currentRevisionId ? `#${row.currentRevisionId}` : '-' }}</template></el-table-column>
        <el-table-column prop="updatedAt" label="更新时间" width="170"><template #default="{ row }">{{ a2aFormatDate(row.updatedAt) }}</template></el-table-column>
        <el-table-column label="操作" width="80" fixed="right"><template #default="{ row }"><el-button link type="primary" @click.stop="openDetail(row)">查看</el-button></template></el-table-column>
      </el-table>
      <el-pagination
        :current-page="Math.floor(query.offset / query.limit) + 1"
        :page-size="query.limit"
        :total="total"
        layout="total, sizes, prev, pager, next"
        :page-sizes="[20, 50, 100]"
        @current-change="(page: number) => { query.offset = (page - 1) * query.limit; reload() }"
        @size-change="(size: number) => { query.limit = size; query.offset = 0; reload() }"
      />
    </el-card>

    <AppDrawer v-model="detailVisible" title="Publication 详情" size="min(880px, 92vw)">
      <div v-if="detail" v-loading="detailLoading" class="detail-content">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="Publication">{{ detail.publication.publicationKey }}</el-descriptions-item>
          <el-descriptions-item label="状态"><el-tag :type="a2aTone(detail.publication.status)">{{ a2aLabel(detail.publication.status) }}</el-tag></el-descriptions-item>
          <el-descriptions-item label="公开 Host">{{ detail.publication.publicHost }}</el-descriptions-item>
          <el-descriptions-item label="环境 / 租户">{{ a2aLabel(detail.publication.environment) }} / {{ detail.publication.tenantScope || '全局' }}</el-descriptions-item>
          <el-descriptions-item label="Agent">{{ detail.publication.agentId }}</el-descriptions-item>
          <el-descriptions-item label="Trust Profile">#{{ detail.publication.trustProfileId }}</el-descriptions-item>
        </el-descriptions>
        <div class="command-row">
          <el-button v-if="detail.publication.status === 'PUBLISHED'" type="warning" @click="executeCommand('suspend')">暂停新任务</el-button>
          <el-button v-if="detail.publication.status === 'SUSPENDED'" type="success" @click="executeCommand('resume')">恢复发布</el-button>
        </div>
        <div class="revision-layout">
          <div class="revision-list">
            <button
              v-for="revision in detail.revisions"
              :key="revision.id"
              type="button"
              :class="['revision-item', { 'is-active': selectedRevisionId === revision.id }]"
              @click="selectedRevisionId = revision.id"
            >
              <span><strong>r{{ revision.revisionNo }} · {{ revision.agentVersion }}</strong><small>{{ a2aLabel(revision.status) }} · {{ a2aFormatDate(revision.createdAt) }}</small></span>
              <el-tag :type="a2aTone(revision.conformanceStatus)" size="small">TCK {{ a2aLabel(revision.conformanceStatus) }}</el-tag>
            </button>
          </div>
          <div class="revision-detail" v-if="selectedRevision">
            <div class="command-row">
              <el-button v-if="selectedRevision.status === 'DRAFT'" @click="executeCommand('preflight', selectedRevision)">运行发布前检查</el-button>
              <el-button v-if="selectedRevision.status === 'READY'" type="primary" @click="executeCommand('publish', selectedRevision)">发布此修订</el-button>
            </div>
            <AgentCardPreview :revision="selectedRevision" />
          </div>
        </div>
      </div>
    </AppDrawer>

    <AppDialog v-model="createVisible" title="创建本地 Publication" description="草稿不会立即对外服务；完成预检并显式发布后才会生效。" width="min(900px, 94vw)" :close-on-click-modal="false">
      <el-steps :active="step" finish-status="success" align-center class="create-steps">
        <el-step title="选择 Agent" /><el-step title="描述能力" /><el-step title="定义入口" /><el-step title="绑定安全" /><el-step title="确认" />
      </el-steps>

      <el-form label-position="top" class="create-form">
        <template v-if="step === 0">
          <el-form-item label="本地 Agent">
            <el-select v-model="form.agentId" filterable placeholder="仅显示已启用且存在 ACTIVE 配置的 Agent" @change="agentChanged">
              <el-option v-for="agent in agents" :key="agent.id" :label="`${agent.name} (${agent.keySlug})`" :value="agent.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="固定配置版本">
            <el-select v-model="form.agentConfigVersionId" placeholder="选择 ACTIVE 配置版本">
              <el-option v-for="version in activeConfigs" :key="version.id" :label="`v${version.versionNo} · ${version.runtimeType}`" :value="version.id" />
            </el-select>
          </el-form-item>
          <el-alert type="info" :closable="false" show-icon title="Publication 固定到一个已发布 Agent 配置版本，后续 Agent 改动不会静默改变外部契约。" />
        </template>

        <template v-else-if="step === 1">
          <div class="form-grid">
            <el-form-item label="发布标识"><el-input v-model="form.publicationKey" placeholder="例如 finance-reviewer" /></el-form-item>
            <el-form-item label="对外版本"><el-input v-model="form.agentVersion" placeholder="例如 1.0.0" /></el-form-item>
          </div>
          <el-form-item label="对外名称"><el-input v-model="form.name" /></el-form-item>
          <el-form-item label="对外说明"><el-input v-model="form.description" type="textarea" :rows="3" /></el-form-item>
          <div class="skills-heading"><strong>协议 AgentSkill</strong><el-button link type="primary" :icon="Plus" @click="form.protocolSkills.push(emptySkill())">添加</el-button></div>
          <div v-for="(skill, index) in form.protocolSkills" :key="index" class="skill-editor">
            <div class="form-grid"><el-form-item label="Skill ID"><el-input v-model="skill.id" placeholder="review_invoice" /></el-form-item><el-form-item label="名称"><el-input v-model="skill.name" /></el-form-item></div>
            <el-form-item label="说明"><el-input v-model="skill.description" /></el-form-item>
            <el-form-item label="标签"><el-select v-model="skill.tags" multiple filterable allow-create default-first-option placeholder="输入后回车" /></el-form-item>
            <el-button v-if="form.protocolSkills.length > 1" link type="danger" @click="form.protocolSkills.splice(index, 1)">删除此 Skill</el-button>
          </div>
        </template>

        <template v-else-if="step === 2">
          <div class="form-grid"><el-form-item label="环境"><el-select v-model="form.environment" @change="form.trustProfileId = undefined"><el-option v-for="value in ['DEVELOPMENT', 'TEST', 'STAGING', 'PRODUCTION']" :key="value" :value="value" :label="a2aLabel(value)" /></el-select></el-form-item><el-form-item label="租户范围"><el-input v-model="form.tenantScope" placeholder="留空表示全局" /></el-form-item></div>
          <el-form-item label="公开 Origin"><el-input v-model="form.publicOrigin" placeholder="https://agent.example.com" /></el-form-item>
          <el-form-item label="Host（必须与 Origin authority 一致）"><el-input v-model="form.publicHost" placeholder="agent.example.com" /></el-form-item>
          <div class="form-grid"><el-form-item label="默认输入媒体类型"><el-select v-model="form.defaultInputModes" multiple filterable allow-create /></el-form-item><el-form-item label="默认输出媒体类型"><el-select v-model="form.defaultOutputModes" multiple filterable allow-create /></el-form-item></div>
          <el-alert type="warning" :closable="false" show-icon title="首发仅声明已实现的非流式 HTTP+JSON 能力；Streaming、Push 与 Extended Card 不会出现在 Card 中。" />
        </template>

        <template v-else-if="step === 3">
          <el-form-item label="Trust Profile">
            <el-select v-model="form.trustProfileId" filterable placeholder="选择同环境且允许入站的有效策略">
              <el-option v-for="profile in applicableTrustProfiles" :key="profile.id" :label="`${profile.name} · ${a2aLabel(profile.trustLevel)}`" :value="profile.id" />
            </el-select>
          </el-form-item>
          <el-alert v-if="!applicableTrustProfiles.length" type="error" :closable="false" show-icon title="当前环境没有可用的入站 Trust Profile，请先到“信任与策略”创建。" />
          <div class="form-grid"><el-form-item label="提供方组织（可选）"><el-input v-model="form.providerOrganization" /></el-form-item><el-form-item label="提供方 URL（可选）"><el-input v-model="form.providerUrl" /></el-form-item></div>
          <el-form-item label="接入文档 URL（可选）"><el-input v-model="form.documentationUrl" /></el-form-item>
        </template>

        <template v-else>
          <el-descriptions :column="2" border>
            <el-descriptions-item label="Publication">{{ form.publicationKey }}</el-descriptions-item>
            <el-descriptions-item label="Agent / Config">{{ form.agentId }} / #{{ form.agentConfigVersionId }}</el-descriptions-item>
            <el-descriptions-item label="环境 / 租户">{{ a2aLabel(form.environment) }} / {{ form.tenantScope || '全局' }}</el-descriptions-item>
            <el-descriptions-item label="入口">{{ form.publicOrigin }}/a2a/v1</el-descriptions-item>
            <el-descriptions-item label="Trust Profile">#{{ form.trustProfileId }}</el-descriptions-item>
            <el-descriptions-item label="协议 AgentSkill">{{ form.protocolSkills.map((item) => item.id).join(', ') }}</el-descriptions-item>
          </el-descriptions>
          <el-alert type="info" :closable="false" show-icon title="创建后状态为草稿；仍需逐个执行发布前检查和发布命令。" />
        </template>
      </el-form>

      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button v-if="step > 0" @click="step--">上一步</el-button>
        <el-button v-if="step < 4" type="primary" @click="nextStep">下一步</el-button>
        <el-button v-else type="primary" :loading="submitting" @click="submitCreate">创建草稿</el-button>
      </template>
    </AppDialog>
  </section>
</template>

<style scoped lang="scss">
.publication-page,
.detail-content {
  display: flex;
  flex-direction: column;
  gap: var(--layout-page-gap);
}

.section-intro,
.section-actions,
.filter-row,
.command-row,
.skills-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.section-intro { align-items: flex-start; }
.section-intro h2, .section-intro p { margin: 0; }
.section-intro h2 { font-size: 18px; }
.section-intro p { margin-top: 4px; color: var(--text-muted); font-size: 13px; }
.filter-row > .el-input { flex: 1; max-width: 520px; }
.filter-row > .el-select { width: 160px; }
.cell-stack strong, .cell-stack small { display: block; }
.cell-stack small { margin-top: 3px; color: var(--text-muted); font-size: 12px; }
.command-row { justify-content: flex-end; }
.revision-layout { display: grid; grid-template-columns: 250px minmax(0, 1fr); gap: 14px; min-height: 360px; }
.revision-list { display: flex; flex-direction: column; gap: 7px; }
.revision-item { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 10px; border: 1px solid var(--border-divider); border-radius: var(--radius-md); color: var(--text-primary); text-align: left; background: var(--surface-glass-control); cursor: pointer; }
.revision-item.is-active { border-color: rgb(var(--brand-primary-rgb) / 0.4); background: var(--surface-glass-selected); }
.revision-item strong, .revision-item small { display: block; }
.revision-item small { margin-top: 3px; color: var(--text-muted); font-size: 11px; }
.revision-detail { min-width: 0; }
.revision-detail > .command-row { margin-bottom: 10px; }
.create-steps { margin-bottom: 24px; }
.create-form { min-height: 380px; }
.create-form :deep(.el-select) { width: 100%; }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
.skills-heading { margin: 14px 0 8px; }
.skill-editor { margin-bottom: 10px; padding: 12px; border: 1px solid var(--border-divider); border-radius: var(--radius-md); background: var(--surface-glass-control); }
.workbench-list-surface :deep(.el-pagination) { justify-content: flex-end; margin-top: 12px; }

@media (max-width: 760px) {
  .section-intro, .filter-row { align-items: stretch; flex-direction: column; }
  .filter-row > .el-input, .filter-row > .el-select { width: 100%; max-width: none; }
  .revision-layout, .form-grid { grid-template-columns: 1fr; }
}
</style>
