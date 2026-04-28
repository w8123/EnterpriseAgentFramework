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
        <el-button type="warning" plain @click="openPendingInteractionsDialog">
          <el-icon><List /></el-icon>测试挂起交互
        </el-button>
      </div>
    </div>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="Skill 是把「一段稳定的多步业务流程」打包成一个粗粒度能力"
      description="支持多种形态：SUB_AGENT 用子 Agent（系统提示词 + 工具白名单）封装一段推理流程；INTERACTIVE_FORM 用结构化表单收集参数后调用目标 Tool。上层 Agent 仍像调用普通 Tool 一样选用它们。"
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
        <el-form-item label="草稿">
          <el-select v-model="filters.draft" clearable placeholder="全部" style="width: 120px">
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

              <template v-if="row.skillKind === 'INTERACTIVE_FORM'">
                <h4 style="margin-top: 16px">InteractiveForm Spec</h4>
                <InteractiveFormSpecEditor
                  :model-value="normalizeInteractiveFormSpec(row.spec)"
                  :tool-options="toolOptions"
                  readonly
                />
              </template>
              <template v-else>
                <h4 style="margin-top: 16px">子 Agent Spec</h4>
                <div class="meta-grid">
                  <div><b>模型：</b>{{ (row.spec as any)?.llmProvider || '-' }} / {{ (row.spec as any)?.llmModel || '-' }}</div>
                  <div><b>最大步数：</b>{{ (row.spec as any)?.maxSteps ?? 8 }}</div>
                  <div class="span-2"><b>工具白名单：</b>{{ ((row.spec as any)?.toolWhitelist || []).join(', ') || '-' }}</div>
                  <div class="span-2"><b>系统提示词：</b><pre class="prompt-preview">{{ (row.spec as any)?.systemPrompt || '-' }}</pre></div>
                </div>
              </template>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="name" label="Skill 名" min-width="220">
          <template #default="{ row }">
            <el-text type="primary" tag="b">{{ row.name }}</el-text>
            <el-tag v-if="row.draft" type="warning" size="small" style="margin-left: 8px">草稿</el-tag>
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
              :disabled="Boolean(row.draft)"
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
            <el-button
              link
              type="primary"
              size="small"
              :disabled="Boolean(row.draft)"
              @click="openTest(row)"
            >
              测试
            </el-button>
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

    <el-dialog v-model="formDialogVisible" :title="formDialogTitle" width="960px" top="4vh">
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
              <el-select v-model="form.skillKind" style="width: 100%" @change="onSkillKindChange">
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

        <el-divider content-position="left">Skill Spec</el-divider>
        <template v-if="form.skillKind === 'INTERACTIVE_FORM'">
          <el-form-item label=" " label-width="0px">
            <InteractiveFormSpecEditor v-model="interactiveSpec" :tool-options="toolOptions" />
          </el-form-item>
        </template>
        <template v-else>
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
        </template>

        <el-form-item label="运行控制">
          <div class="switch-group">
            <el-tooltip
              :disabled="!editingIsDraft"
              content="草稿需先「发布」后才能在列表中启用"
              placement="top"
            >
              <span class="switch-inline">
                <el-switch v-model="form.enabled" :disabled="editingIsDraft" />
                <span>启用</span>
              </span>
            </el-tooltip>
            <el-switch v-model="form.agentVisible" />
            <span>Agent 可见</span>
          </div>
          <div v-if="editingIsDraft" class="param-hint">当前为草稿；「发布」通过校验后即可在列表中启用。</div>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="formDialogVisible = false">取消</el-button>
        <el-button v-if="!isEditMode || editingIsDraft" :loading="saving" @click="handleSaveDraft">
          暂存
        </el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">
          {{ isEditMode && editingIsDraft ? '发布' : '保存' }}
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="testDialogVisible" :title="`测试 Skill — ${testingSkill?.name}`" width="720px">
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
          v-if="testResult.interactionPending && testResult.success"
          type="warning"
          title="等待交互（非执行失败）"
          :description="testResult.result || '请按下方提示继续。'"
          :closable="false"
          show-icon
        />
        <el-alert v-else :type="testResult.success ? 'success' : 'error'" :closable="false" show-icon>
          <template #title>
            <span>{{ testResult.success ? '执行成功' : '执行失败' }}</span>
          </template>
          <template #default>
            <p style="margin: 0">
              {{
                testResult.errorMessage ||
                (!testResult.success ? testResult.result : '') ||
                ''
              }}
            </p>
            <p v-if="isPendingQuotaBlockError(testResult.errorMessage)" style="margin: 8px 0 0">
              <el-button link type="primary" @click="openPendingInteractionsDialog">
                查看并取消挂起中的交互
              </el-button>
              <span class="param-hint">（管理端测试会话 skill-admin-test，最多 5 条未完成）</span>
            </p>
          </template>
        </el-alert>
        <pre
          v-if="testResult.result && !(testResult.interactionPending && testResult.success)"
          class="result-content"
        >{{ testResult.result }}</pre>
        <!-- 与 Agent 调试台一致：挂起时用 DynamicInteraction 填写 / 确认 -->
        <div
          v-if="testResult.interactionPending && testResult.interactionId && testResult.uiRequest"
          class="skill-test-interaction-wrap"
          v-loading="testResumeRunning"
        >
          <p class="param-hint skill-test-interaction-hint">
            下方为与对话里相同的交互界面（表单、确认卡等），填写后提交即可继续；也可展开底部查看原始 JSON。
          </p>
          <DynamicInteraction
            :payload="testResult.uiRequest as unknown as UiRequestPayload"
            @action="handleTestUiAction"
          />
          <el-collapse class="skill-test-raw-json">
            <el-collapse-item title="原始 uiRequest JSON（调试）" name="raw">
              <pre class="result-content ui-payload">{{ formatUiRequestPreview(testResult.uiRequest) }}</pre>
            </el-collapse-item>
            <el-collapse-item
              v-if="uiRequestComponent(testResult.uiRequest) === 'form'"
              title="手动输入 JSON（备选，与上方表单二选一）"
              name="json"
            >
              <p class="param-hint">若组件未覆盖某字段类型，可在此提交键值 JSON：</p>
              <el-input
                v-model="testResumeValuesJson"
                type="textarea"
                :rows="4"
                placeholder='例如 { "deptId": "001" }'
              />
              <el-button
                type="primary"
                class="test-resume-btn"
                :loading="testResumeRunning"
                @click="handleTestResumeJsonSubmit"
              >
                按 JSON 提交本批
              </el-button>
            </el-collapse-item>
          </el-collapse>
        </div>
        <p class="result-duration">耗时：{{ testResult.durationMs }}ms</p>
      </div>

      <template #footer>
        <el-button @click="testDialogVisible = false">关闭</el-button>
        <el-button type="primary" @click="handleTest" :loading="testRunning && !testResumeRunning">执行</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="pendingDialogVisible"
      title="Skill 测试 — 挂起中的未完成交互"
      width="760px"
      @opened="loadPendingInteractions"
    >
      <el-alert
        type="info"
        :closable="false"
        show-icon
        style="margin-bottom: 12px"
        title="说明"
        description="页面「测试」按钮使用固定测试身份（skill-admin-test）。InteractiveForm Skill 挂起后会计入未完成条数，达到 5 条后将无法新开测试。可在此取消单条或全部取消后重试。"
      />
      <div style="margin-bottom: 10px; display: flex; gap: 8px; flex-wrap: wrap">
        <el-button size="small" :loading="pendingLoading" @click="loadPendingInteractions">刷新列表</el-button>
        <el-button
          size="small"
          type="danger"
          plain
          :loading="pendingCancelAllRunning"
          :disabled="pendingList.length === 0"
          @click="handleCancelAllPending"
        >
          全部取消
        </el-button>
      </div>
      <el-table :data="pendingList" v-loading="pendingLoading" stripe size="small" max-height="360">
        <el-table-column prop="skillName" label="Skill" min-width="140" show-overflow-tooltip />
        <el-table-column prop="uiTitle" label="界面标题" min-width="120" show-overflow-tooltip />
        <el-table-column prop="interactionId" label="interactionId" min-width="220" show-overflow-tooltip />
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">{{ formatPendingTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="过期时间" width="170">
          <template #default="{ row }">{{ formatPendingTime(row.expiresAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button
              link
              type="danger"
              size="small"
              :loading="pendingRowDeleting === row.interactionId"
              @click="handleCancelOnePending(row.interactionId)"
            >
              取消
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!pendingLoading && pendingList.length === 0" description="当前无挂起中的测试交互" />
      <template #footer>
        <el-button type="primary" @click="pendingDialogVisible = false">关闭</el-button>
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
import { List, Plus, Refresh } from '@element-plus/icons-vue'
import ParameterTable from '@/components/ParameterTable.vue'
import InteractiveFormSpecEditor from '@/components/skill/InteractiveFormSpecEditor.vue'
import DynamicInteraction from '@/components/interaction/DynamicInteraction.vue'
import {
  cancelAdminTestPendingInteraction,
  cancelAllAdminTestPendingInteractions,
  createSkill,
  deleteSkill,
  getAdminTestPendingInteractions,
  getSkillMetrics,
  getSkills,
  testSkill,
  testSkillResume,
  toggleSkill,
  updateSkill,
} from '@/api/skill'
import { getTools } from '@/api/tool'
import type {
  InteractiveFormSpec,
  SkillAdminTestPendingItem,
  SkillInfo,
  SkillMetrics,
  SkillUpsertRequest,
  SubAgentSpec,
} from '@/types/skill'
import type { UiRequestPayload } from '@/types/interaction'
import {
  SIDE_EFFECT_OPTIONS,
  SKILL_KIND_OPTIONS,
  defaultInteractiveFormSpec,
  normalizeInteractiveFormSpec,
  validateInteractiveFormSpec,
} from '@/types/skill'
import type { ToolInfo } from '@/types/tool'

const skills = ref<SkillInfo[]>([])
const total = ref(0)
const loading = ref(false)
const filters = reactive({
  keyword: '',
  enabled: undefined as boolean | undefined,
  draft: undefined as boolean | undefined,
})
const pagination = reactive({ current: 1, size: 20 })
const saving = ref(false)

const toolOptions = ref<ToolInfo[]>([])

const formDialogVisible = ref(false)
const editingName = ref<string | null>(null)
/** 打开编辑对话框时该行是否为草稿（控制「暂存」与表单内「启用」） */
const editingIsDraft = ref(false)
const form = reactive<SkillUpsertRequest>(createEmptyForm())
/** INTERACTIVE_FORM 形态下编辑的结构化 spec */
const interactiveSpec = ref<InteractiveFormSpec>(defaultInteractiveFormSpec())
const isEditMode = computed(() => editingName.value !== null)
const formDialogTitle = computed(() =>
  isEditMode.value ? `编辑 Skill — ${form.name}` : '新建 Skill',
)

const testDialogVisible = ref(false)
const testingSkill = ref<SkillInfo | null>(null)
const testArgs = reactive<Record<string, string>>({})
const testResult = ref<{
  success: boolean
  result: string
  errorMessage?: string
  durationMs: number
  interactionPending?: boolean
  interactionId?: string | null
  uiRequest?: Record<string, unknown> | null
} | null>(null)
const testRunning = ref(false)
const testResumeRunning = ref(false)
const testResumeValuesJson = ref('{}')
const metricsDialogVisible = ref(false)
const metricsSkillName = ref('')
const metricsData = ref<SkillMetrics | null>(null)

const pendingDialogVisible = ref(false)
const pendingList = ref<SkillAdminTestPendingItem[]>([])
const pendingLoading = ref(false)
const pendingRowDeleting = ref<string | null>(null)
const pendingCancelAllRunning = ref(false)

function onSkillKindChange(kind: string) {
  if (kind === 'INTERACTIVE_FORM') {
    interactiveSpec.value = defaultInteractiveFormSpec()
  }
}

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
    ...(filters.draft !== undefined ? { draft: filters.draft } : {}),
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
  filters.draft = undefined
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
  if (data.skillKind === 'INTERACTIVE_FORM') {
    const s = data.spec as Record<string, unknown> | undefined
    interactiveSpec.value =
      s && typeof s === 'object' && 'targetTool' in s
        ? normalizeInteractiveFormSpec(s)
        : defaultInteractiveFormSpec()
    form.spec = {
      systemPrompt: '',
      toolWhitelist: [],
      llmProvider: '',
      llmModel: '',
      maxSteps: 8,
      useMultiAgentModel: false,
    }
  } else {
    const s = data.spec as SubAgentSpec
    form.spec = {
      systemPrompt: s?.systemPrompt || '',
      toolWhitelist: [...(s?.toolWhitelist || [])],
      llmProvider: s?.llmProvider || '',
      llmModel: s?.llmModel || '',
      maxSteps: s?.maxSteps ?? 8,
      useMultiAgentModel: s?.useMultiAgentModel ?? false,
    }
    interactiveSpec.value = defaultInteractiveFormSpec()
  }
}

function openCreateDialog() {
  editingName.value = null
  editingIsDraft.value = false
  applyForm(createEmptyForm())
  formDialogVisible.value = true
}

function openEditDialog(skill: SkillInfo) {
  editingName.value = skill.name
  editingIsDraft.value = Boolean(skill.draft)
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

function buildSpecPayload(): Record<string, unknown> | SubAgentSpec {
  return form.skillKind === 'INTERACTIVE_FORM'
    ? (JSON.parse(JSON.stringify(interactiveSpec.value)) as unknown as Record<string, unknown>)
    : { ...form.spec, toolWhitelist: [...(form.spec as SubAgentSpec).toolWhitelist] }
}

function buildUpsertPayload(draft: boolean): SkillUpsertRequest {
  return {
    ...form,
    parameters: [...form.parameters],
    spec: buildSpecPayload(),
    draft,
  }
}

async function handleSaveDraft() {
  if (!form.name.trim()) {
    ElMessage.warning('请填写 Skill 名')
    return
  }
  saving.value = true
  try {
    const payload = buildUpsertPayload(true)
    if (isEditMode.value && editingName.value) {
      await updateSkill(editingName.value, payload)
      ElMessage.success('已暂存')
    } else {
      await createSkill(payload)
      ElMessage.success('已暂存')
    }
    formDialogVisible.value = false
    await fetchSkills()
  } catch (err) {
    const msg = err as { response?: { data?: { message?: string } }; message?: string }
    ElMessage.error(msg.response?.data?.message || msg.message || '暂存失败')
  } finally {
    saving.value = false
  }
}

async function handleSave() {
  if (!form.name.trim() || !form.description.trim()) {
    ElMessage.warning('请填写 Skill 名与描述')
    return
  }
  if (form.skillKind !== 'INTERACTIVE_FORM' && !(form.spec as SubAgentSpec).systemPrompt?.trim()) {
    ElMessage.warning('请填写子 Agent 系统提示词')
    return
  }
  if (form.skillKind === 'INTERACTIVE_FORM') {
    const err = validateInteractiveFormSpec(interactiveSpec.value)
    if (err) {
      ElMessage.warning(err)
      return
    }
  }
  saving.value = true
  try {
    const payload = buildUpsertPayload(false)
    if (isEditMode.value && editingName.value) {
      await updateSkill(editingName.value, payload)
      ElMessage.success(editingIsDraft.value ? '已发布' : 'Skill 更新成功')
    } else {
      await createSkill(payload)
      ElMessage.success('Skill 创建成功')
    }
    formDialogVisible.value = false
    await fetchSkills()
  } catch (err) {
    const msg = err as { response?: { data?: { message?: string } }; message?: string }
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

function axiosMessage(err: unknown): string {
  const e = err as { response?: { data?: { message?: string } }; message?: string }
  return e.response?.data?.message || e.message || '请求失败'
}

async function handleEnabledChange(skill: SkillInfo, enabled: boolean) {
  if (skill.draft && enabled) {
    ElMessage.warning('草稿请先「发布」后再启用')
    await fetchSkills()
    return
  }
  try {
    await toggleSkill(skill.name, enabled)
    ElMessage.success(`已${enabled ? '启用' : '禁用'} ${skill.name}`)
    await fetchSkills()
  } catch (err) {
    ElMessage.error(axiosMessage(err))
    await fetchSkills()
  }
}

async function handleVisibleChange(skill: SkillInfo, agentVisible: boolean) {
  try {
    const specBody: SubAgentSpec | Record<string, unknown> =
      skill.skillKind === 'INTERACTIVE_FORM'
        ? (normalizeInteractiveFormSpec(skill.spec ?? {}) as unknown as Record<string, unknown>)
        : ((skill.spec as SubAgentSpec) || {
            systemPrompt: '',
            toolWhitelist: [],
            llmProvider: '',
            llmModel: '',
            maxSteps: 8,
            useMultiAgentModel: false,
          })
    await updateSkill(skill.name, {
      name: skill.name,
      description: skill.description,
      parameters: skill.parameters || [],
      skillKind: skill.skillKind || 'SUB_AGENT',
      sideEffect: skill.sideEffect || 'WRITE',
      enabled: skill.enabled,
      agentVisible,
      spec: specBody,
      draft: Boolean(skill.draft),
    })
    ElMessage.success('配置已更新')
    await fetchSkills()
  } catch (err) {
    ElMessage.error((err as Error).message || '更新失败')
  }
}

function formatUiRequestPreview(ui: Record<string, unknown>) {
  try {
    return JSON.stringify(ui, null, 2)
  } catch {
    return String(ui)
  }
}

function uiRequestComponent(
  ui: Record<string, unknown> | null | undefined,
): string | undefined {
  const c = ui?.component
  return typeof c === 'string' ? c : undefined
}

/** 与后端 InteractiveFormSkillExecutor 抛出文案一致 */
function isPendingQuotaBlockError(msg: string | undefined): boolean {
  if (!msg) return false
  return msg.includes('未完成') && msg.includes('交互') && msg.includes('上限')
}

function formatPendingTime(iso: string | null | undefined): string {
  if (!iso) return '-'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return d.toLocaleString()
}

function openPendingInteractionsDialog() {
  pendingDialogVisible.value = true
}

async function loadPendingInteractions() {
  pendingLoading.value = true
  try {
    const { data } = await getAdminTestPendingInteractions()
    pendingList.value = Array.isArray(data) ? data : []
  } catch {
    pendingList.value = []
    ElMessage.error('加载挂起交互列表失败')
  } finally {
    pendingLoading.value = false
  }
}

async function handleCancelOnePending(interactionId: string) {
  pendingRowDeleting.value = interactionId
  try {
    await cancelAdminTestPendingInteraction(interactionId)
    ElMessage.success('已取消该交互')
    await loadPendingInteractions()
  } catch (err) {
    ElMessage.error(axiosMessage(err))
  } finally {
    pendingRowDeleting.value = null
  }
}

async function handleCancelAllPending() {
  try {
    await ElMessageBox.confirm(
      '确认取消当前列表中的全部挂起交互？（不影响真实用户会话）',
      '全部取消',
      { type: 'warning' },
    )
  } catch {
    return
  }
  pendingCancelAllRunning.value = true
  try {
    const { data } = await cancelAllAdminTestPendingInteractions()
    const n = data?.cancelled ?? 0
    ElMessage.success(n > 0 ? `已取消 ${n} 条` : '没有待取消项')
    await loadPendingInteractions()
  } catch (err) {
    ElMessage.error(axiosMessage(err))
  } finally {
    pendingCancelAllRunning.value = false
  }
}

function openTest(skill: SkillInfo) {
  if (skill.draft) {
    ElMessage.warning('草稿 Skill 不可测试，请先发布')
    return
  }
  testingSkill.value = skill
  testResult.value = null
  testResumeValuesJson.value = '{}'
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
  testResumeValuesJson.value = '{}'
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

/** 与 AgentDebug 中 DynamicInteraction 一致：action + values 调用 test/resume */
async function handleTestUiAction(action: string, values: Record<string, unknown>) {
  if (!testingSkill.value || !testResult.value?.interactionId) return
  testResumeRunning.value = true
  try {
    const { data } = await testSkillResume(testingSkill.value.name, {
      interactionId: testResult.value.interactionId,
      action,
      values: values ?? {},
    })
    testResult.value = data as never
    if (!data.interactionPending) {
      testResumeValuesJson.value = '{}'
    }
  } catch (err) {
    testResult.value = {
      success: false,
      result: '',
      errorMessage: (err as Error).message || '继续失败',
      durationMs: 0,
    }
  } finally {
    testResumeRunning.value = false
  }
}

async function handleTestResumeJsonSubmit() {
  if (!testingSkill.value || !testResult.value?.interactionId) return
  let values: Record<string, unknown> = {}
  try {
    const raw = testResumeValuesJson.value?.trim() || '{}'
    values = JSON.parse(raw) as Record<string, unknown>
  } catch {
    ElMessage.error('本批字段 JSON 格式无效')
    return
  }
  await handleTestUiAction('submit', values)
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

.switch-inline {
  display: inline-flex;
  align-items: center;
  gap: 8px;
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

.test-resume-actions {
  margin-top: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.test-resume-btn {
  align-self: flex-start;
  margin-top: 8px;
}

.skill-test-interaction-hint {
  margin-bottom: 8px;
}

.skill-test-raw-json {
  margin-top: 12px;
}

.ui-payload {
  max-height: 200px;
}

.metric-cards {
  margin-bottom: 8px;
}
</style>
