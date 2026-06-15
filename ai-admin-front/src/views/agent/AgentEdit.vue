<template>
  <div class="agent-workbench page-container">
    <header class="workbench-header">
      <div class="header-left">
        <el-button :icon="ArrowLeft" text @click="router.push('/agent')">返回</el-button>
        <div>
          <div class="eyebrow">智能体入口</div>
          <h2>{{ isNew ? '新建智能体' : `编辑智能体 - ${form.name || agentId}` }}</h2>
        </div>
        <el-tag size="small" effect="plain">{{ agentKindLabel(form.agentKind) }}</el-tag>
        <el-tag size="small" :type="form.enabled !== false ? 'success' : 'info'" effect="plain">
          {{ form.enabled !== false ? '启用' : '停用' }}
        </el-tag>
      </div>
      <div class="header-actions">
        <el-tooltip content="复制 keySlug 和 ID" placement="bottom">
          <span>
            <el-button :icon="DocumentCopy" :disabled="!canCopyAgentIdentity" @click="copyAgentIdentity">
              复制标识
            </el-button>
          </span>
        </el-tooltip>
        <el-button v-if="!isNew" :icon="Connection" @click="openBindings">Workflow 绑定</el-button>
        <el-button v-if="!isNew" :icon="Share" @click="openStudio">Workflow 画布</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </div>
    </header>

    <el-form ref="formRef" :model="form" :rules="rules" label-width="108px" v-loading="pageLoading">
      <main class="workbench-layout">
        <section class="panel">
          <div class="panel-head">
            <div>
              <h3>身份与策略</h3>
              <p>配置 Agent 入口身份、可见性与默认策略；图编排请在 Workflow Studio 完成。</p>
            </div>
            <el-switch v-model="form.enabled" active-text="启用" inactive-text="停用" />
          </div>

          <div class="form-grid two">
            <el-form-item label="名称" prop="name">
              <el-input v-model="form.name" placeholder="如：合同审核助手" />
            </el-form-item>
            <el-form-item label="keySlug" prop="keySlug">
              <el-input v-model="form.keySlug" placeholder="如 contract-review" :disabled="!isNew" />
            </el-form-item>
            <el-form-item label="入口类型" prop="agentKind">
              <el-select v-model="form.agentKind">
                <el-option
                  v-for="item in agentKindOptions"
                  :key="item.value"
                  :label="item.label"
                  :value="item.value"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="可见性">
              <el-select v-model="form.visibility">
                <el-option label="项目" value="PROJECT" />
                <el-option label="私有" value="PRIVATE" />
                <el-option label="公开" value="PUBLIC" />
              </el-select>
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
            <el-form-item label="默认模型">
              <el-select v-model="form.modelInstanceId" clearable filterable placeholder="可选">
                <el-option
                  v-for="item in llmModelInstances"
                  :key="item.id"
                  :label="`${item.name} (${item.modelName})`"
                  :value="item.id"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="运行角色" class="wide">
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
          </div>

          <el-form-item label="描述">
            <el-input v-model="form.description" type="textarea" :rows="2" placeholder="一句话描述入口用途" />
          </el-form-item>

          <el-form-item label="System Prompt">
            <el-input
              v-model="form.systemPrompt"
              type="textarea"
              :rows="6"
              placeholder="定义入口默认角色与回答风格（Workflow 节点可覆盖）"
            />
          </el-form-item>

          <el-form-item label="入口配置">
            <el-input
              v-model="entryConfigText"
              type="textarea"
              :rows="5"
              placeholder='JSON，如 {"intentType":"GENERAL_CHAT"}'
            />
          </el-form-item>
        </section>

        <aside v-if="!isNew" class="side-panel">
          <section class="summary-panel">
            <h3>关联 Workflow</h3>
            <p>通过绑定将入口路由到 Workflow；图编辑与发布在 Workflow Studio / Versions 完成。</p>
            <el-button class="summary-action" type="primary" plain :icon="Connection" @click="openBindings">
              管理 Workflow 绑定
            </el-button>
            <el-button class="summary-action" :icon="Share" @click="openStudio">
              打开 Workflow 画布
            </el-button>
          </section>
        </aside>
      </main>
    </el-form>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { ArrowLeft, Connection, DocumentCopy, Share } from '@element-plus/icons-vue'
import type { AgentEntry, AgentEntryForm, AgentEntryKind } from '@/types/agent'
import { createAgentEntry, getAgentEntry, updateAgentEntry } from '@/api/workflow'
import { getScanProjects } from '@/api/scanProject'
import { getModelInstances } from '@/api/model'
import type { ScanProject } from '@/types/scanProject'
import type { ModelInstance } from '@/types/model'
import { useProjectStore } from '@/store/project'

const route = useRoute()
const router = useRouter()
const projectStore = useProjectStore()
const agentId = route.params.id as string
const isNew = agentId === 'new'

const formRef = ref<FormInstance>()
const pageLoading = ref(false)
const saving = ref(false)
const scanProjects = ref<ScanProject[]>([])
const llmModelInstances = ref<ModelInstance[]>([])
const entryConfigText = ref('{}')

const agentKindOptions = [
  { value: 'PROJECT_ENTRY', label: '项目入口' },
  { value: 'GLOBAL_EMBED', label: '全局嵌入' },
  { value: 'PAGE_ENTRY', label: '页面入口' },
]

const form = reactive<AgentEntryForm>({
  keySlug: '',
  name: '',
  description: '',
  agentKind: 'PROJECT_ENTRY',
  projectId: null,
  projectCode: null,
  visibility: 'PROJECT',
  systemPrompt: '',
  modelInstanceId: '',
  allowedRoles: [],
  entryConfig: {},
  enabled: true,
})

const rules: FormRules = {
  name: [{ required: true, message: '请输入智能体名称', trigger: 'blur' }],
  keySlug: [{ required: true, message: '请输入 keySlug', trigger: 'blur' }],
  agentKind: [{ required: true, message: '请选择入口类型', trigger: 'change' }],
}

const canCopyAgentIdentity = computed(() => !isNew && Boolean(agentId))

const agentIdentityCopy = computed(() => [
  `agentId: ${agentId}`,
  `keySlug: ${form.keySlug || '(未配置)'}`,
].join('\n'))

function agentKindLabel(kind?: AgentEntryKind | null) {
  return agentKindOptions.find((item) => item.value === kind)?.label || kind || '项目入口'
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

function parseEntryConfig(json?: string | null): Record<string, unknown> {
  if (!json?.trim()) return {}
  try {
    const parsed = JSON.parse(json)
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed)
      ? parsed as Record<string, unknown>
      : {}
  } catch {
    return {}
  }
}

function entryToForm(entry: AgentEntry): void {
  Object.assign(form, {
    keySlug: entry.keySlug || '',
    name: entry.name || '',
    description: entry.description || '',
    agentKind: entry.agentKind || 'PROJECT_ENTRY',
    projectId: entry.projectId ?? null,
    projectCode: entry.projectCode ?? null,
    visibility: entry.visibility || 'PROJECT',
    systemPrompt: entry.systemPrompt || '',
    modelInstanceId: entry.modelInstanceId || '',
    allowedRoles: parseAllowedRoles(entry.allowedRolesJson),
    entryConfig: parseEntryConfig(entry.entryConfigJson),
    enabled: entry.enabled !== false,
  })
  entryConfigText.value = JSON.stringify(form.entryConfig || {}, null, 2)
}

function buildPayload(): Partial<AgentEntry> {
  let entryConfig: Record<string, unknown> = {}
  if (entryConfigText.value.trim()) {
    try {
      const parsed = JSON.parse(entryConfigText.value)
      if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
        entryConfig = parsed as Record<string, unknown>
      }
    } catch {
      throw new Error('入口配置 JSON 格式无效')
    }
  }
  return {
    keySlug: form.keySlug?.trim(),
    name: form.name?.trim(),
    description: form.description || null,
    agentKind: form.agentKind || 'PROJECT_ENTRY',
    projectId: form.projectId ?? null,
    projectCode: form.projectCode ?? projectCodeById(form.projectId) ?? null,
    visibility: form.visibility || 'PROJECT',
    systemPrompt: form.systemPrompt || null,
    modelInstanceId: form.modelInstanceId || null,
    allowedRolesJson: form.allowedRoles?.length ? JSON.stringify(form.allowedRoles) : null,
    entryConfigJson: Object.keys(entryConfig).length ? JSON.stringify(entryConfig) : null,
    enabled: form.enabled !== false,
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
      await createAgentEntry(payload)
      ElMessage.success('创建成功')
    } else {
      await updateAgentEntry(agentId, payload)
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

function openBindings() {
  if (isNew) return
  router.push(`/agents/${agentId}/bindings`)
}

function openStudio() {
  if (isNew) return
  router.push(`/agent/${agentId}/studio`)
}

async function copyAgentIdentity() {
  if (!canCopyAgentIdentity.value) return
  await navigator.clipboard.writeText(agentIdentityCopy.value)
  ElMessage.success('已复制 Agent keySlug 和 ID')
}

async function loadAgent() {
  if (isNew) return
  pageLoading.value = true
  try {
    const { data } = await getAgentEntry(agentId)
    entryToForm(data)
  } catch {
    ElMessage.error('加载 Agent 失败')
  } finally {
    pageLoading.value = false
  }
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
</script>

<style scoped lang="scss">
.agent-workbench {
  min-height: calc(100vh - 64px);
  padding: 20px 24px 28px;
  background: #f6f8fc;
}

.workbench-header,
.panel,
.summary-panel {
  border: 1px solid #e5eaf4;
  background: #fff;
  box-shadow: 0 8px 22px rgba(31, 45, 61, 0.04);
}

.workbench-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 82px;
  padding: 18px 22px;
  border-radius: 8px;
}

.header-left,
.header-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.eyebrow {
  color: #7d8ca5;
  font-size: 12px;
  font-weight: 700;
}

h2,
h3 {
  margin: 0;
  color: #172033;
}

h2 {
  font-size: 22px;
}

h3 {
  font-size: 16px;
}

p {
  margin: 4px 0 0;
  color: #68758c;
  font-size: 13px;
}

.workbench-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 272px;
  gap: 14px;
  margin-top: 14px;
}

.panel,
.summary-panel {
  border-radius: 10px;
  padding: 18px 20px;
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
}

.form-grid {
  display: grid;
  gap: 14px 22px;
}

.form-grid.two {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.wide {
  grid-column: span 2;
}

:deep(.el-select),
:deep(.el-input),
:deep(.el-textarea) {
  width: 100%;
}

.summary-action {
  width: 100%;
  margin-top: 10px;
}

@media (max-width: 1280px) {
  .workbench-layout {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 960px) {
  .workbench-header,
  .panel-head {
    align-items: flex-start;
    flex-direction: column;
  }

  .form-grid.two {
    grid-template-columns: 1fr;
  }

  .wide {
    grid-column: auto;
  }
}
</style>
