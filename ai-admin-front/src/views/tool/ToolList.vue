<template>
  <div class="page-container">
    <div class="page-header">
      <h2>Tool 管理</h2>
      <div class="header-actions">
        <el-button type="primary" @click="openCreateDialog">
          <el-icon><Plus /></el-icon>新建 Tool
        </el-button>
        <el-button @click="onRefresh" :loading="loading">
          <el-icon><Refresh /></el-icon>刷新
        </el-button>
      </div>
    </div>

    <el-card shadow="never">
      <el-form :inline="true" class="tool-filter" @submit.prevent="handleSearch">
        <el-form-item label="关键词">
          <el-input
            v-model="filters.keyword"
            clearable
            placeholder="工具名或描述"
            style="width: 200px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>
        <el-form-item label="来源">
          <el-select v-model="filters.source" clearable placeholder="全部" style="width: 130px">
            <el-option label="code" value="code" />
            <el-option label="scanner" value="scanner" />
            <el-option label="manual" value="manual" />
          </el-select>
        </el-form-item>
        <el-form-item label="启用">
          <el-select v-model="filters.enabled" clearable placeholder="全部" style="width: 120px">
            <el-option label="是" :value="true" />
            <el-option label="否" :value="false" />
          </el-select>
        </el-form-item>
        <el-form-item label="来源项目">
          <el-select
            v-model="filters.projectId"
            clearable
            filterable
            placeholder="全部"
            style="width: 200px"
          >
            <el-option
              v-for="p in scanProjects"
              :key="p.id"
              :label="`${p.name} (ID ${p.id})`"
              :value="p.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleSearch">查询</el-button>
          <el-button @click="resetFilters">重置</el-button>
        </el-form-item>
      </el-form>

      <el-table :data="tools" v-loading="loading" stripe @expand-change="onToolExpandChange">
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="expand-content">
              <h4>参数定义</h4>
              <el-table
                class="param-definition-table"
                :data="parameterRows(row.parameters)"
                size="small"
                border
                row-key="_key"
                :tree-props="{ children: 'children' }"
                default-expand-all
                table-layout="fixed"
              >
                <el-table-column prop="name" label="参数名" min-width="120" show-overflow-tooltip />
                <el-table-column prop="type" label="类型" width="100" show-overflow-tooltip />
                <el-table-column prop="location" label="位置" width="88" show-overflow-tooltip>
                  <template #default="{ row: param }">
                    {{ param.location || '-' }}
                  </template>
                </el-table-column>
                <el-table-column
                  prop="description"
                  label="描述"
                  min-width="120"
                  show-overflow-tooltip
                />
                <el-table-column prop="required" label="必填" width="72" align="center">
                  <template #default="{ row: param }">
                    <el-tag :type="param.required ? 'danger' : 'info'" size="small">
                      {{ param.required ? '是' : '否' }}
                    </el-tag>
                  </template>
                </el-table-column>
              </el-table>
              <div class="tool-meta">
                <div><b>来源：</b>{{ row.source }}</div>
                <div><b>来源项目：</b>{{ sourceProjectLabel(row) }}</div>
                <div><b>HTTP：</b>{{ row.httpMethod || '-' }} {{ row.contextPath || '' }}{{ row.endpointPath || '' }}</div>
                <div><b>Base URL：</b>{{ row.baseUrl || '-' }}</div>
                <div><b>来源定位：</b>{{ row.sourceLocation || '-' }}</div>
                <div><b>请求体类型：</b>{{ row.requestBodyType || '-' }}</div>
                <div><b>响应类型：</b>{{ row.responseType || '-' }}</div>
              </div>
              <div class="tool-ai-semantic-block">
                <div v-if="semanticLoadState[row.name] === 'loading'" class="tool-meta-ai-loading">正在加载完整语义文档…</div>
                <template v-else-if="fullSemanticMd[row.name]">
                  <h4 class="expand-ai-doc-title">完整 AI 语义文档</h4>
                  <div class="markdown-body tool-semantic-md" v-html="renderMd(fullSemanticMd[row.name])" />
                </template>
                <div v-else-if="row.aiDescription" class="tool-meta-ai">
                  <b>AI 理解（摘要）：</b>
                  <span class="ai-desc-text">{{ row.aiDescription }}</span>
                  <p v-if="semanticLoadState[row.name] === 'none'" class="ai-doc-miss-hint">
                    无独立语义文档记录；摘要来自生成结果中的「一句话语义」片段。
                  </p>
                </div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="name" label="工具名" width="200">
          <template #default="{ row }">
            <el-text type="primary" tag="b">{{ row.name }}</el-text>
          </template>
        </el-table-column>
        <el-table-column label="来源" width="110" align="center">
          <template #default="{ row }">
            <el-tag :type="sourceTagType(row.source)" size="small">{{ row.source }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="来源项目" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">
            {{ sourceProjectLabel(row) }}
          </template>
        </el-table-column>
        <el-table-column label="端点" min-width="220">
          <template #default="{ row }">
            <span>{{ row.httpMethod || '-' }} {{ row.contextPath || '' }}{{ row.endpointPath || '' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="描述" min-width="240">
          <template #default="{ row }">
            <el-tooltip
              placement="top-start"
              :show-after="200"
              :hide-after="0"
              :teleported="true"
              popper-class="tool-description-tooltip"
            >
              <template #content>
                <div class="tool-description-tooltip-content">
                  {{ row.description || '-' }}
                </div>
              </template>
              <div class="tool-description-cell">
                {{ row.description || '-' }}
              </div>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column min-width="200" show-overflow-tooltip>
          <template #header>
            <span title="列表为摘要（一句话语义）；展开行可加载完整 Markdown 文档">AI 理解</span>
          </template>
          <template #default="{ row }">
            <span class="ai-table-cell">{{ row.aiDescription || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="参数数量" width="100" align="center">
          <template #default="{ row }">
            {{ (row.parameters || []).length }}
          </template>
        </el-table-column>
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
              @change="handleFlagChange(row, 'agentVisible', $event as boolean)"
            />
          </template>
        </el-table-column>
        <el-table-column label="轻量调用" width="110" align="center">
          <template #default="{ row }">
            <el-switch
              :model-value="row.lightweightEnabled"
              @change="handleFlagChange(row, 'lightweightEnabled', $event as boolean)"
            />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEditDialog(row)">编辑</el-button>
            <el-button link type="primary" size="small" @click="openTest(row)">测试</el-button>
            <el-button
              link
              type="danger"
              size="small"
              :disabled="row.source === 'code'"
              @click="handleDelete(row)"
            >删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-empty
        v-if="!loading && tools.length === 0"
        description="暂无数据，请调整条件或先在后端注册 Tool"
      />
      <div v-if="total > 0" class="pagination-wrap">
        <el-pagination
          v-model:current-page="pagination.current"
          v-model:page-size="pagination.size"
          :page-sizes="[10, 20, 50, 100]"
          :total="total"
          layout="total, sizes, prev, pager, next, jumper"
          @current-change="fetchTools"
          @size-change="handlePageSizeChange"
        />
      </div>
    </el-card>

    <el-dialog v-model="formDialogVisible" :title="formDialogTitle" width="760px">
      <el-form label-width="120px">
        <el-form-item label="工具名">
          <el-input v-model="form.name" :disabled="isCodeTool || isEditMode" placeholder="snake_case" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" :disabled="isCodeTool" type="textarea" :rows="2" />
        </el-form-item>
        <el-row :gutter="16">
          <el-col :span="8">
            <el-form-item label="来源">
              <el-input :model-value="form.source" disabled />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="HTTP 方法">
              <el-select v-model="form.httpMethod" :disabled="isCodeTool" style="width: 100%">
                <el-option v-for="method in httpMethods" :key="method" :label="method" :value="method" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="请求体类型">
              <el-input v-model="form.requestBodyType" :disabled="isCodeTool" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="Base URL">
              <el-input v-model="form.baseUrl" :disabled="isCodeTool" placeholder="http://localhost:8602" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="Context Path">
              <el-input v-model="form.contextPath" :disabled="isCodeTool" placeholder="/api" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="Endpoint Path">
              <el-input v-model="form.endpointPath" :disabled="isCodeTool" placeholder="/customer/search" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="响应类型">
              <el-input v-model="form.responseType" :disabled="isCodeTool" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="来源定位">
          <el-input v-model="form.sourceLocation" :disabled="isCodeTool" placeholder="类名/扫描位置" />
        </el-form-item>

        <el-form-item label="参数定义">
          <ParameterTable
            v-model="form.parameters as any"
            :disabled="isCodeTool"
            :show-location="true"
          />
        </el-form-item>

        <el-form-item label="运行控制">
          <div class="switch-group">
            <el-switch v-model="form.enabled" />
            <span>启用</span>
            <el-switch v-model="form.agentVisible" />
            <span>Agent 可见</span>
            <el-switch v-model="form.lightweightEnabled" />
            <span>轻量调用可见</span>
          </div>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="formDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>

    <!-- 测试弹窗 -->
    <el-dialog v-model="testDialogVisible" :title="`测试工具 — ${testingTool?.name}`" width="600px">
      <el-form v-if="testingTool" label-width="120px">
        <el-form-item
          v-for="param in testingTool.parameters"
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
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { marked } from 'marked'
import ParameterTable from '@/components/ParameterTable.vue'
import type { ToolInfo, ToolParameter, ToolTestResult, ToolUpsertRequest } from '@/types/tool'
import type { ScanProject } from '@/types/scanProject'
import { getScanProjects } from '@/api/scanProject'
import { findSemanticDoc } from '@/api/semanticDoc'
import { createTool, deleteTool, getTools, testTool, toggleTool, updateTool } from '@/api/tool'

const tools = ref<ToolInfo[]>([])
const scanProjects = ref<ScanProject[]>([])
const total = ref(0)
const loading = ref(false)
const filters = reactive({
  keyword: '',
  source: undefined as string | undefined,
  enabled: undefined as boolean | undefined,
  projectId: undefined as number | undefined,
})
const pagination = reactive({ current: 1, size: 20 })
const saving = ref(false)

const formDialogVisible = ref(false)
const editingName = ref<string | null>(null)
const form = reactive<ToolUpsertRequest>(createEmptyForm())
const httpMethods = ['GET', 'POST', 'PUT', 'DELETE', 'PATCH']
const isEditMode = computed(() => editingName.value !== null)
const isCodeTool = computed(() => form.source === 'code')
const formDialogTitle = computed(() => (isEditMode.value ? `编辑 Tool — ${form.name}` : '新建 Tool'))

const testDialogVisible = ref(false)
const testingTool = ref<ToolInfo | null>(null)
const testArgs = reactive<Record<string, string>>({})
const testResult = ref<ToolTestResult | null>(null)
const testRunning = ref(false)

/** 展开行时懒加载的 tool 级语义 Markdown（完整文档）；key 为工具名 */
const fullSemanticMd = reactive<Record<string, string>>({})
/** idle | loading | done | none */
const semanticLoadState = reactive<Record<string, 'idle' | 'loading' | 'done' | 'none'>>({})

function renderMd(content: string | null | undefined): string {
  if (!content) return ''
  return marked.parse(content, { async: false }) as string
}

async function ensureToolSemanticLoaded(row: ToolInfo) {
  const k = row.name
  const st = semanticLoadState[k]
  if (st === 'done' || st === 'none' || st === 'loading') return
  semanticLoadState[k] = 'loading'
  try {
    const { data } = await findSemanticDoc({ level: 'tool', toolName: row.name })
    const md = data?.contentMd?.trim()
    if (md) {
      fullSemanticMd[k] = md
      semanticLoadState[k] = 'done'
    } else {
      semanticLoadState[k] = 'none'
    }
  } catch {
    semanticLoadState[k] = 'none'
  }
}

function onToolExpandChange(row: ToolInfo, expandedRows: ToolInfo[]) {
  if (expandedRows.some((r) => r.name === row.name)) {
    void ensureToolSemanticLoaded(row)
  }
}

function clearSemanticExpandCache() {
  Object.keys(fullSemanticMd).forEach((k) => delete fullSemanticMd[k])
  Object.keys(semanticLoadState).forEach((k) => delete semanticLoadState[k])
}

function createEmptyForm(): ToolUpsertRequest {
  return {
    name: '',
    description: '',
    parameters: [],
    source: 'manual',
    sourceLocation: '',
    httpMethod: 'GET',
    baseUrl: '',
    contextPath: '/api',
    endpointPath: '',
    requestBodyType: '',
    responseType: '',
    projectId: null,
    enabled: true,
    agentVisible: true,
    lightweightEnabled: false,
  }
}

function sourceTagType(source: ToolInfo['source']) {
  if (source === 'code') return 'success'
  if (source === 'scanner') return 'warning'
  return 'info'
}

/** 来源项目列：展示后端返回的名称；缺失时用本地扫描项目列表补救 */
function sourceProjectLabel(row: ToolInfo) {
  if (row.sourceProjectName) {
    return row.sourceProjectName
  }
  const pid = row.projectId
  if (pid == null) {
    return '-'
  }
  const found = scanProjects.value.find((p) => p.id === pid)
  if (found) {
    return `${found.name} (ID ${found.id})`
  }
  return `ID ${pid}`
}

function cloneParameters(parameters: ToolParameter[] = []): ToolParameter[] {
  return parameters.map((parameter) => ({ ...parameter }))
}

/** 与扫描项目详情一致：为 el-table 树形行分配稳定 _key，递归挂 children */
interface ParameterRow extends ToolParameter {
  _key: string
  children?: ParameterRow[]
}

function parameterRows(parameters: ToolParameter[] | null | undefined, prefix = ''): ParameterRow[] {
  if (!parameters || parameters.length === 0) return []
  return parameters.map((parameter, index) => {
    const keyBase = `${parameter.location || 'ROOT'}:${parameter.name || `#${index}`}`
    const key = prefix ? `${prefix}>${keyBase}` : keyBase
    const { children, ...rest } = parameter
    const nested = children && children.length > 0 ? parameterRows(children, key) : undefined
    const row: ParameterRow = { ...rest, _key: key }
    if (nested) row.children = nested
    return row
  })
}

function toUpsertRequest(tool: ToolInfo): ToolUpsertRequest {
  return {
    name: tool.name,
    description: tool.description,
    parameters: cloneParameters(tool.parameters),
    source: tool.source,
    sourceLocation: tool.sourceLocation || '',
    httpMethod: tool.httpMethod || 'GET',
    baseUrl: tool.baseUrl || '',
    contextPath: tool.contextPath || '/api',
    endpointPath: tool.endpointPath || '',
    requestBodyType: tool.requestBodyType || '',
    responseType: tool.responseType || '',
    projectId: tool.projectId ?? null,
    enabled: tool.enabled,
    agentVisible: tool.agentVisible,
    lightweightEnabled: tool.lightweightEnabled,
  }
}

function applyForm(data: ToolUpsertRequest) {
  form.name = data.name
  form.description = data.description
  form.parameters = cloneParameters(data.parameters)
  form.source = data.source
  form.sourceLocation = data.sourceLocation || ''
  form.httpMethod = data.httpMethod || 'GET'
  form.baseUrl = data.baseUrl || ''
  form.contextPath = data.contextPath || '/api'
  form.endpointPath = data.endpointPath || ''
  form.requestBodyType = data.requestBodyType || ''
  form.responseType = data.responseType || ''
  form.projectId = data.projectId ?? null
  form.enabled = data.enabled
  form.agentVisible = data.agentVisible
  form.lightweightEnabled = data.lightweightEnabled
}

function buildListParams() {
  return {
    current: pagination.current,
    size: pagination.size,
    ...(filters.keyword.trim() ? { keyword: filters.keyword.trim() } : {}),
    ...(filters.source ? { source: filters.source } : {}),
    ...(filters.enabled !== undefined ? { enabled: filters.enabled } : {}),
    ...(filters.projectId !== undefined ? { projectId: filters.projectId } : {}),
  }
}

async function fetchTools() {
  loading.value = true
  try {
    const { data } = await getTools(buildListParams())
    if (data && 'records' in data) {
      tools.value = Array.isArray(data.records) ? data.records : []
      total.value = typeof data.total === 'number' ? data.total : 0
      clearSemanticExpandCache()
    } else {
      tools.value = []
      total.value = 0
      clearSemanticExpandCache()
    }
  } catch {
    tools.value = []
    total.value = 0
    ElMessage.error('加载 Tool 列表失败')
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  pagination.current = 1
  return fetchTools()
}

function handlePageSizeChange() {
  pagination.current = 1
  return fetchTools()
}

function resetFilters() {
  filters.keyword = ''
  filters.source = undefined
  filters.enabled = undefined
  filters.projectId = undefined
  pagination.current = 1
  return fetchTools()
}

async function loadScanProjects() {
  try {
    const { data } = await getScanProjects()
    scanProjects.value = Array.isArray(data) ? data : []
  } catch {
    scanProjects.value = []
  }
}

async function onRefresh() {
  await loadScanProjects()
  return fetchTools()
}

function openCreateDialog() {
  editingName.value = null
  applyForm(createEmptyForm())
  formDialogVisible.value = true
}

function openEditDialog(tool: ToolInfo) {
  editingName.value = tool.name
  applyForm(toUpsertRequest(tool))
  formDialogVisible.value = true
}

async function handleSave() {
  if (!form.name.trim() || !form.description.trim()) {
    ElMessage.warning('请填写工具名和描述')
    return
  }
  saving.value = true
  try {
    if (isEditMode.value && editingName.value) {
      await updateTool(editingName.value, { ...form, parameters: cloneParameters(form.parameters) })
      ElMessage.success('Tool 更新成功')
    } else {
      await createTool({ ...form, parameters: cloneParameters(form.parameters) })
      ElMessage.success('Tool 创建成功')
    }
    formDialogVisible.value = false
    await fetchTools()
  } catch (error) {
    ElMessage.error((error as Error).message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function handleDelete(tool: ToolInfo) {
  try {
    await ElMessageBox.confirm(`确认删除工具 ${tool.name} 吗？`, '删除确认', {
      type: 'warning',
    })
    await deleteTool(tool.name)
    ElMessage.success('Tool 删除成功')
    await fetchTools()
  } catch (error) {
    if ((error as Error).message !== 'cancel') {
      ElMessage.error((error as Error).message || '删除失败')
    }
  }
}

async function handleEnabledChange(tool: ToolInfo, enabled: boolean) {
  try {
    await toggleTool(tool.name, enabled)
    ElMessage.success(`已${enabled ? '启用' : '禁用'} ${tool.name}`)
    await fetchTools()
  } catch (error) {
    ElMessage.error((error as Error).message || '状态更新失败')
  }
}

async function handleFlagChange(tool: ToolInfo, field: 'agentVisible' | 'lightweightEnabled', value: boolean) {
  try {
    const payload = toUpsertRequest({
      ...tool,
      [field]: value,
    })
    await updateTool(tool.name, payload)
    ElMessage.success('配置已更新')
    await fetchTools()
  } catch (error) {
    ElMessage.error((error as Error).message || '配置更新失败')
  }
}

function openTest(tool: ToolInfo) {
  testingTool.value = tool
  testResult.value = null
  Object.keys(testArgs).forEach((k) => delete testArgs[k])
  for (const p of tool.parameters || []) {
    testArgs[p.name] = ''
  }
  testDialogVisible.value = true
}

async function handleTest() {
  if (!testingTool.value) return
  testRunning.value = true
  testResult.value = null
  try {
    const args: Record<string, unknown> = {}
    for (const [k, v] of Object.entries(testArgs)) {
      if (v !== '') args[k] = v
    }
    const { data } = await testTool(testingTool.value.name, args)
    testResult.value = data as unknown as ToolTestResult
  } catch (err: unknown) {
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

onMounted(() => {
  loadScanProjects()
  fetchTools()
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
  width: 100%;
  max-width: 100%;
  min-width: 0;
  box-sizing: border-box;

  h4 {
    font-size: 13px;
    color: #909399;
    margin-bottom: 8px;
  }
}

/* 参数定义内嵌表：固定表布局 + 可收缩单元格，避免「描述」列撑满后把类型/位置/必填挤出可视区 */
.param-definition-table {
  width: 100%;
  max-width: 100%;
  min-width: 0;
}

.param-definition-table :deep(.el-table__cell .cell) {
  min-width: 0;
}

/* 展开格默认 .cell 常为 flex，会把大块 Markdown 挤到一侧；改为块级占满宽度 */
:deep(.el-table__expanded-cell) {
  padding: 0 16px 16px;
}

:deep(.el-table__expanded-cell .cell) {
  display: block;
  width: 100%;
  max-width: 100%;
  min-width: 0;
  overflow-x: auto;
}

.tool-ai-semantic-block {
  width: 100%;
  max-width: 100%;
  min-width: 0;
  box-sizing: border-box;
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px solid #ebeef5;
}

.tool-meta {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px 16px;
  margin-top: 12px;
  font-size: 13px;
  color: #606266;
}

.tool-description-cell {
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;
  overflow: hidden;
  line-height: 20px;
  max-height: 60px;
  white-space: normal;
  word-break: break-word;
  color: #606266;
}

.tool-description-tooltip-content {
  max-width: 420px;
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 20px;
}

.param-hint {
  font-size: 12px;
  color: #909399;
  margin-top: 2px;
}

.parameter-editor {
  width: 100%;
}

.add-parameter-button {
  margin-top: 8px;
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
  max-height: 200px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
}

.result-duration {
  font-size: 12px;
  color: #909399;
  margin-top: 8px;
}

:deep(.tool-description-tooltip) {
  max-width: 460px;
}

.tool-meta-ai {
  margin: 6px 0;
  line-height: 1.5;
}

.ai-desc-text {
  color: #606266;
  font-size: 13px;
}

.ai-table-cell {
  font-size: 13px;
  color: #606266;
}

.tool-meta-ai-loading {
  margin: 8px 0;
  font-size: 13px;
  color: #909399;
}

.expand-ai-doc-title {
  margin: 0 0 10px;
  font-size: 13px;
  color: #909399;
}

.ai-doc-miss-hint {
  margin: 6px 0 0;
  font-size: 12px;
  color: #909399;
  line-height: 1.5;
}

.tool-semantic-md {
  width: 100%;
  max-width: 100%;
  min-width: 0;
  box-sizing: border-box;
  font-size: 13px;
  line-height: 1.65;
  max-height: 480px;
  overflow: auto;
  padding: 8px 0;
  word-break: break-word;

  :deep(h1), :deep(h2), :deep(h3) {
    margin-top: 10px;
    margin-bottom: 6px;
  }

  :deep(pre) {
    background: #f6f7f9;
    border-radius: 4px;
    padding: 8px;
    overflow: auto;
    max-width: 100%;
  }

  :deep(table) {
    border-collapse: collapse;
    max-width: 100%;
    table-layout: auto;

    th, td {
      border: 1px solid #dcdfe6;
      padding: 4px 8px;
      word-break: break-word;
    }
  }
}
</style>
