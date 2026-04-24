<template>
  <div class="page-container">
    <div class="page-header">
      <h2>Skill 管理</h2>
      <div class="header-actions">
        <el-button type="primary" @click="openCreateDialog">
          <el-icon><Plus /></el-icon>新建 Skill
        </el-button>
        <el-button @click="onRefresh" :loading="loading">
          <el-icon><Refresh /></el-icon>刷新
        </el-button>
      </div>
    </div>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="Skill 是把「一段稳定的多步业务流程」打包成一个粗粒度能力"
      description="当前只支持 SUB_AGENT 形态：你在这里定义子 Agent 的系统提示词与可用工具白名单，Agent 在上层像调用普通 Tool 一样调用它。"
      style="margin-bottom: 16px"
    />

    <el-card shadow="never">
      <el-form :inline="true" class="tool-filter" @submit.prevent="handleSearch">
        <el-form-item label="关键词">
          <el-input
            v-model="filters.keyword"
            clearable
            placeholder="名称或描述"
            style="width: 200px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>
        <el-form-item label="启用">
          <el-select v-model="filters.enabled" clearable placeholder="全部" style="width: 120px">
            <el-option label="是" :value="true" />
            <el-option label="否" :value="false" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleSearch">查询</el-button>
          <el-button @click="resetFilters">重置</el-button>
        </el-form-item>
      </el-form>

      <el-table :data="skills" v-loading="loading" stripe>
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="expand-content">
              <h4>参数 Schema</h4>
              <el-table :data="row.parameters || []" size="small" border>
                <el-table-column prop="name" label="参数名" min-width="160" />
                <el-table-column prop="type" label="类型" width="120" />
                <el-table-column prop="description" label="描述" />
                <el-table-column prop="required" label="必填" width="80" align="center">
                  <template #default="{ row: p }">
                    <el-tag :type="p.required ? 'danger' : 'info'" size="small">
                      {{ p.required ? '是' : '否' }}
                    </el-tag>
                  </template>
                </el-table-column>
              </el-table>

              <h4 style="margin-top: 16px">子 Agent Spec</h4>
              <div class="meta-grid">
                <div><b>模型：</b>{{ row.spec?.llmProvider || '-' }} / {{ row.spec?.llmModel || '-' }}</div>
                <div><b>最大步数：</b>{{ row.spec?.maxSteps ?? 8 }}</div>
                <div class="span-2"><b>工具白名单：</b>{{ (row.spec?.toolWhitelist || []).join(', ') || '-' }}</div>
                <div class="span-2"><b>系统提示词：</b><pre class="prompt-preview">{{ row.spec?.systemPrompt || '-' }}</pre></div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="name" label="Skill 名" min-width="200">
          <template #default="{ row }">
            <el-text type="primary" tag="b">{{ row.name }}</el-text>
          </template>
        </el-table-column>
        <el-table-column label="形态" width="130" align="center">
          <template #default="{ row }">
            <el-tag size="small">{{ row.skillKind || 'SUB_AGENT' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="副作用" width="150" align="center">
          <template #default="{ row }">
            <el-tag :type="sideEffectTagType(row.sideEffect)" size="small">
              {{ row.sideEffect || 'WRITE' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="描述" min-width="280" show-overflow-tooltip />
        <el-table-column label="启用" width="90" align="center">
          <template #default="{ row }">
            <el-switch
              :model-value="row.enabled"
              @change="handleEnabledChange(row, $event as boolean)"
            />
          </template>
        </el-table-column>
        <el-table-column label="Agent 可见" width="110" align="center">
          <template #default="{ row }">
            <el-switch
              :model-value="row.agentVisible"
              @change="handleVisibleChange(row, $event as boolean)"
            />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEditDialog(row)">编辑</el-button>
            <el-button link type="primary" size="small" @click="openTest(row)">测试</el-button>
            <el-button link type="success" size="small" @click="openMetrics(row)">指标</el-button>
            <el-button link type="danger" size="small" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-if="!loading && skills.length === 0" description="尚未创建 Skill，点右上角新建" />
      <div v-if="total > 0" class="pagination-wrap">
        <el-pagination
          v-model:current-page="pagination.current"
          v-model:page-size="pagination.size"
          :page-sizes="[10, 20, 50, 100]"
          :total="total"
          layout="total, sizes, prev, pager, next, jumper"
          @current-change="fetchSkills"
          @size-change="handlePageSizeChange"
        />
      </div>
    </el-card>

    <el-dialog v-model="formDialogVisible" :title="formDialogTitle" width="820px" top="4vh">
      <el-form label-width="130px">
        <el-form-item label="Skill 名">
          <el-input v-model="form.name" :disabled="isEditMode" placeholder="snake_case，如 risk_customer_triage" />
        </el-form-item>
        <el-form-item label="描述（给 LLM 看）">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="3"
            placeholder="一句话讲清楚这个 Skill 解决什么问题、什么场景下调用。LLM 根据它决定是否选中。"
          />
        </el-form-item>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="形态">
              <el-select v-model="form.skillKind" style="width: 100%">
                <el-option
                  v-for="opt in SKILL_KIND_OPTIONS"
                  :key="opt.value"
                  :label="opt.label"
                  :value="opt.value"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="副作用等级">
              <el-select v-model="form.sideEffect" style="width: 100%">
                <el-option
                  v-for="opt in SIDE_EFFECT_OPTIONS"
                  :key="opt.value"
                  :label="opt.label"
                  :value="opt.value"
                />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>

        <el-divider content-position="left">参数 Schema（Agent 调用时会按此 schema 传参）</el-divider>
        <el-form-item label=" " label-width="0px">
          <ParameterTable
            v-model="form.parameters as any"
            :show-location="false"
            :type-options="['string', 'integer', 'number', 'boolean', 'object', 'array']"
          />
        </el-form-item>

        <el-divider content-position="left">SubAgent Spec</el-divider>
        <el-form-item label="系统提示词">
          <el-input
            v-model="form.spec.systemPrompt"
            type="textarea"
            :rows="6"
            placeholder="子 Agent 的角色设定 + 工作流程，用 Markdown/自然语言写。建议约束：1) 只在明确收到请求时执行；2) 步骤与顺序；3) 最终输出结构。"
          />
        </el-form-item>
        <el-form-item label="工具白名单">
          <el-select
            v-model="form.spec.toolWhitelist"
            multiple
            filterable
            placeholder="选择子 Agent 可调用的 Tool（不允许包含其他 Skill）"
            style="width: 100%"
          >
            <el-option
              v-for="t in toolOptions"
              :key="t.name"
              :label="`${t.name} — ${t.description?.slice(0, 40) || ''}`"
              :value="t.name"
            />
          </el-select>
          <div class="param-hint">只能挑选 kind=TOOL 的能力；子 Skill 嵌套会被运行时拦截。</div>
        </el-form-item>
        <el-row :gutter="16">
          <el-col :span="8">
            <el-form-item label="最大推理步数">
              <el-input-number v-model="form.spec.maxSteps" :min="1" :max="50" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="LLM Provider">
              <el-input v-model="form.spec.llmProvider" placeholder="留空继承父 Agent" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="LLM Model">
              <el-input v-model="form.spec.llmModel" placeholder="留空继承父 Agent" />
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item label="运行控制">
          <div class="switch-group">
            <el-switch v-model="form.enabled" />
            <span>启用</span>
            <el-switch v-model="form.agentVisible" />
            <span>Agent 可见</span>
          </div>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="formDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="testDialogVisible" :title="`测试 Skill — ${testingSkill?.name}`" width="640px">
      <el-form v-if="testingSkill" label-width="140px">
        <el-form-item
          v-for="param in testingSkill.parameters"
          :key="param.name"
          :label="param.name"
          :required="param.required"
        >
          <el-input
            v-model="testArgs[param.name]"
            :placeholder="param.description || param.type"
          />
          <div class="param-hint">{{ param.description }} ({{ param.type }})</div>
        </el-form-item>
      </el-form>

      <div v-if="testResult" class="test-result-area">
        <el-divider content-position="left">执行结果</el-divider>
        <el-alert
          :type="testResult.success ? 'success' : 'error'"
          :title="testResult.success ? '执行成功' : '执行失败'"
          :description="testResult.errorMessage || ''"
          :closable="false"
          show-icon
        />
        <pre v-if="testResult.result" class="result-content">{{ testResult.result }}</pre>
        <p class="result-duration">耗时：{{ testResult.durationMs }}ms</p>
      </div>

      <template #footer>
        <el-button @click="testDialogVisible = false">关闭</el-button>
        <el-button type="primary" @click="handleTest" :loading="testRunning">执行</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="metricsDialogVisible" :title="`Skill 指标 — ${metricsSkillName}`" width="760px">
      <div v-if="!metricsData">暂无指标数据</div>
      <template v-else>
        <el-row :gutter="12" class="metric-cards">
          <el-col :span="6"><el-statistic title="调用次数" :value="metricsData.callCount" /></el-col>
          <el-col :span="6"><el-statistic title="成功率" :value="Number((metricsData.successRate * 100).toFixed(2))" suffix="%" /></el-col>
          <el-col :span="6"><el-statistic title="P95 延迟" :value="metricsData.p95LatencyMs" suffix="ms" /></el-col>
          <el-col :span="6"><el-statistic title="P95 Token" :value="metricsData.p95TokenCost" /></el-col>
        </el-row>
        <el-table :data="metricsData.trends" stripe size="small" style="margin-top: 12px">
          <el-table-column prop="day" label="日期" width="130" />
          <el-table-column prop="callCount" label="调用量" width="100" />
          <el-table-column label="成功率" width="120">
            <template #default="{ row }">{{ (row.successRate * 100).toFixed(2) }}%</template>
          </el-table-column>
          <el-table-column prop="p95LatencyMs" label="P95 延迟(ms)" width="140" />
          <el-table-column prop="p95TokenCost" label="P95 Token" width="120" />
        </el-table>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import ParameterTable from '@/components/ParameterTable.vue'
import {
  createSkill,
  deleteSkill,
  getSkillMetrics,
  getSkills,
  testSkill,
  toggleSkill,
  updateSkill,
} from '@/api/skill'
import { getTools } from '@/api/tool'
import type { SkillInfo, SkillMetrics, SkillUpsertRequest } from '@/types/skill'
import { SIDE_EFFECT_OPTIONS, SKILL_KIND_OPTIONS } from '@/types/skill'
import type { ToolInfo } from '@/types/tool'

const skills = ref<SkillInfo[]>([])
const total = ref(0)
const loading = ref(false)
const filters = reactive({
  keyword: '',
  enabled: undefined as boolean | undefined,
})
const pagination = reactive({ current: 1, size: 20 })
const saving = ref(false)

const toolOptions = ref<ToolInfo[]>([])

const formDialogVisible = ref(false)
const editingName = ref<string | null>(null)
const form = reactive<SkillUpsertRequest>(createEmptyForm())
const isEditMode = computed(() => editingName.value !== null)
const formDialogTitle = computed(() =>
  isEditMode.value ? `编辑 Skill — ${form.name}` : '新建 Skill',
)

const testDialogVisible = ref(false)
const testingSkill = ref<SkillInfo | null>(null)
const testArgs = reactive<Record<string, string>>({})
const testResult = ref<{ success: boolean; result: string; errorMessage?: string; durationMs: number } | null>(null)
const testRunning = ref(false)
const metricsDialogVisible = ref(false)
const metricsSkillName = ref('')
const metricsData = ref<SkillMetrics | null>(null)

function createEmptyForm(): SkillUpsertRequest {
  return {
    name: '',
    description: '',
    parameters: [],
    skillKind: 'SUB_AGENT',
    sideEffect: 'WRITE',
    enabled: true,
    agentVisible: true,
    spec: {
      systemPrompt: '',
      toolWhitelist: [],
      llmProvider: '',
      llmModel: '',
      maxSteps: 8,
      useMultiAgentModel: false,
    },
  }
}

function sideEffectTagType(level?: string | null) {
  switch ((level || '').toUpperCase()) {
    case 'NONE':
    case 'READ_ONLY':
      return 'success'
    case 'IDEMPOTENT_WRITE':
      return 'info'
    case 'WRITE':
      return 'warning'
    case 'IRREVERSIBLE':
      return 'danger'
    default:
      return 'info'
  }
}

function buildListParams() {
  return {
    current: pagination.current,
    size: pagination.size,
    ...(filters.keyword.trim() ? { keyword: filters.keyword.trim() } : {}),
    ...(filters.enabled !== undefined ? { enabled: filters.enabled } : {}),
  }
}

async function fetchSkills() {
  loading.value = true
  try {
    const { data } = await getSkills(buildListParams())
    if (data && 'records' in data) {
      skills.value = Array.isArray(data.records) ? data.records : []
      total.value = typeof data.total === 'number' ? data.total : 0
    } else {
      skills.value = []
      total.value = 0
    }
  } catch {
    skills.value = []
    total.value = 0
    ElMessage.error('加载 Skill 列表失败')
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  pagination.current = 1
  return fetchSkills()
}

function handlePageSizeChange() {
  pagination.current = 1
  return fetchSkills()
}

function resetFilters() {
  filters.keyword = ''
  filters.enabled = undefined
  pagination.current = 1
  return fetchSkills()
}

async function loadToolOptions() {
  try {
    const { data } = await getTools({ current: 1, size: 200, enabled: true })
    if (data && 'records' in data) {
      toolOptions.value = data.records || []
    }
  } catch {
    toolOptions.value = []
  }
}

async function onRefresh() {
  await loadToolOptions()
  return fetchSkills()
}

function applyForm(data: SkillUpsertRequest) {
  form.name = data.name
  form.description = data.description
  form.parameters = [...(data.parameters || [])]
  form.skillKind = data.skillKind
  form.sideEffect = data.sideEffect ?? 'WRITE'
  form.enabled = data.enabled
  form.agentVisible = data.agentVisible
  form.spec = {
    systemPrompt: data.spec?.systemPrompt || '',
    toolWhitelist: [...(data.spec?.toolWhitelist || [])],
    llmProvider: data.spec?.llmProvider || '',
    llmModel: data.spec?.llmModel || '',
    maxSteps: data.spec?.maxSteps ?? 8,
    useMultiAgentModel: data.spec?.useMultiAgentModel ?? false,
  }
}

function openCreateDialog() {
  editingName.value = null
  applyForm(createEmptyForm())
  formDialogVisible.value = true
}

function openEditDialog(skill: SkillInfo) {
  editingName.value = skill.name
  applyForm({
    name: skill.name,
    description: skill.description,
    parameters: skill.parameters || [],
    skillKind: skill.skillKind || 'SUB_AGENT',
    sideEffect: skill.sideEffect || 'WRITE',
    enabled: skill.enabled,
    agentVisible: skill.agentVisible,
    spec: skill.spec || {
      systemPrompt: '',
      toolWhitelist: [],
      llmProvider: '',
      llmModel: '',
      maxSteps: 8,
      useMultiAgentModel: false,
    },
  })
  formDialogVisible.value = true
}

async function handleSave() {
  if (!form.name.trim() || !form.description.trim()) {
    ElMessage.warning('请填写 Skill 名与描述')
    return
  }
  if (!form.spec.systemPrompt.trim()) {
    ElMessage.warning('请填写子 Agent 系统提示词')
    return
  }
  saving.value = true
  try {
    const payload: SkillUpsertRequest = {
      ...form,
      parameters: [...form.parameters],
      spec: { ...form.spec, toolWhitelist: [...form.spec.toolWhitelist] },
    }
    if (isEditMode.value && editingName.value) {
      await updateSkill(editingName.value, payload)
      ElMessage.success('Skill 更新成功')
    } else {
      await createSkill(payload)
      ElMessage.success('Skill 创建成功')
    }
    formDialogVisible.value = false
    await fetchSkills()
  } catch (err) {
    const msg = (err as { response?: { data?: { message?: string } }; message?: string })
    ElMessage.error(msg.response?.data?.message || msg.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function handleDelete(skill: SkillInfo) {
  try {
    await ElMessageBox.confirm(`确认删除 Skill ${skill.name} 吗？`, '删除确认', { type: 'warning' })
    await deleteSkill(skill.name)
    ElMessage.success('Skill 删除成功')
    await fetchSkills()
  } catch (err) {
    if ((err as Error).message !== 'cancel') {
      ElMessage.error((err as Error).message || '删除失败')
    }
  }
}

async function handleEnabledChange(skill: SkillInfo, enabled: boolean) {
  try {
    await toggleSkill(skill.name, enabled)
    ElMessage.success(`已${enabled ? '启用' : '禁用'} ${skill.name}`)
    await fetchSkills()
  } catch (err) {
    ElMessage.error((err as Error).message || '状态更新失败')
  }
}

async function handleVisibleChange(skill: SkillInfo, agentVisible: boolean) {
  try {
    await updateSkill(skill.name, {
      name: skill.name,
      description: skill.description,
      parameters: skill.parameters || [],
      skillKind: skill.skillKind || 'SUB_AGENT',
      sideEffect: skill.sideEffect || 'WRITE',
      enabled: skill.enabled,
      agentVisible,
      spec: skill.spec || {
        systemPrompt: '',
        toolWhitelist: [],
        llmProvider: '',
        llmModel: '',
        maxSteps: 8,
        useMultiAgentModel: false,
      },
    })
    ElMessage.success('配置已更新')
    await fetchSkills()
  } catch (err) {
    ElMessage.error((err as Error).message || '更新失败')
  }
}

function openTest(skill: SkillInfo) {
  testingSkill.value = skill
  testResult.value = null
  Object.keys(testArgs).forEach((k) => delete testArgs[k])
  for (const p of skill.parameters || []) {
    testArgs[p.name] = ''
  }
  testDialogVisible.value = true
}

async function handleTest() {
  if (!testingSkill.value) return
  testRunning.value = true
  testResult.value = null
  try {
    const args: Record<string, unknown> = {}
    for (const [k, v] of Object.entries(testArgs)) {
      if (v !== '') args[k] = v
    }
    const { data } = await testSkill(testingSkill.value.name, args)
    testResult.value = data as never
  } catch (err) {
    testResult.value = {
      success: false,
      result: '',
      errorMessage: (err as Error).message || '执行失败',
      durationMs: 0,
    }
  } finally {
    testRunning.value = false
  }
}

async function openMetrics(skill: SkillInfo) {
  metricsSkillName.value = skill.name
  metricsData.value = null
  metricsDialogVisible.value = true
  try {
    const { data } = await getSkillMetrics(skill.name, 7)
    metricsData.value = data
  } catch {
    ElMessage.error('加载指标失败')
  }
}

onMounted(() => {
  loadToolOptions()
  fetchSkills()
})
</script>

<style scoped lang="scss">
.header-actions {
  display: flex;
  gap: 8px;
}

.tool-filter {
  margin-bottom: 8px;
  flex-wrap: wrap;
}

.tool-filter :deep(.el-form-item) {
  margin-bottom: 12px;
}

.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
  padding-top: 8px;
  border-top: 1px solid #ebeef5;
}

.expand-content {
  padding: 12px 20px;

  h4 {
    font-size: 13px;
    color: #909399;
    margin-bottom: 8px;
  }
}

.meta-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px 16px;
  font-size: 13px;
  color: #606266;

  .span-2 {
    grid-column: span 2;
  }
}

.prompt-preview {
  margin: 4px 0 0;
  padding: 8px 12px;
  background: #f7f8fa;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 240px;
  overflow: auto;
  font-size: 12px;
  line-height: 1.6;
}

.param-hint {
  font-size: 12px;
  color: #909399;
  margin-top: 2px;
}

.switch-group {
  display: flex;
  align-items: center;
  gap: 10px;
}

.test-result-area {
  margin-top: 12px;
}

.result-content {
  background: #fafafa;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  padding: 12px;
  font-size: 13px;
  margin-top: 12px;
  max-height: 260px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
}

.result-duration {
  font-size: 12px;
  color: #909399;
  margin-top: 8px;
}

.metric-cards {
  margin-bottom: 8px;
}
</style>
