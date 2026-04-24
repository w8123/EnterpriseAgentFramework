<template>
  <div class="page-container">
    <div class="page-header">
      <h2>扫描详情</h2>
      <div class="header-actions">
        <el-button @click="goBack">返回列表</el-button>
        <el-button :loading="loading" @click="refreshAll">
          <el-icon><Refresh /></el-icon>刷新
        </el-button>
        <el-button type="warning" :loading="rescanLoading" @click="handleRescan">重新扫描</el-button>
      </div>
    </div>

    <el-card shadow="never" class="section-card">
      <template #header>项目概览</template>
      <div v-if="project" class="project-summary">
        <div><b>项目名称：</b>{{ project.name }}</div>
        <div><b>项目域名：</b>{{ project.baseUrl }}</div>
        <div><b>Context Path：</b>{{ project.contextPath || '-' }}</div>
        <div><b>扫描路径：</b>{{ project.scanPath }}</div>
        <div><b>扫描方式：</b>{{ project.scanType }}</div>
        <div><b>状态：</b><el-tag :type="statusTagType(project.status)">{{ project.status }}</el-tag></div>
        <div><b>接口数：</b>{{ project.toolCount }}</div>
        <div><b>错误信息：</b>{{ project.errorMessage || '-' }}</div>
      </div>
    </el-card>

    <el-tabs v-model="activeTab" class="detail-tabs">
      <el-tab-pane label="扫描结果" name="scan">
    <el-card shadow="never">
      <template #header>
        <div class="tools-header">
          <span>扫描到的接口</span>
          <div class="tools-actions">
            <el-button size="small" @click="batchToggle(false)">全部禁用</el-button>
            <el-button size="small" type="primary" @click="batchToggle(true)">全部启用</el-button>
          </div>
        </div>
      </template>

      <el-table :data="tools" v-loading="loading" stripe>
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="expand-content">
              <h4>参数定义</h4>
              <el-table :data="row.parameters || []" size="small" border>
                <el-table-column prop="name" label="参数名" width="160" />
                <el-table-column prop="type" label="类型" width="100" />
                <el-table-column prop="location" label="位置" width="100">
                  <template #default="{ row: param }">
                    {{ param.location || '-' }}
                  </template>
                </el-table-column>
                <el-table-column prop="description" label="描述" />
                <el-table-column prop="required" label="必填" width="80" align="center">
                  <template #default="{ row: param }">
                    <el-tag :type="param.required ? 'danger' : 'info'" size="small">
                      {{ param.required ? '是' : '否' }}
                    </el-tag>
                  </template>
                </el-table-column>
              </el-table>
              <div class="tool-meta">
                <div><b>HTTP：</b>{{ row.httpMethod || '-' }} {{ row.contextPath || '' }}{{ row.endpointPath || '' }}</div>
                <div><b>Base URL：</b>{{ row.baseUrl || '-' }}</div>
                <div><b>来源定位：</b>{{ row.sourceLocation || '-' }}</div>
                <div><b>请求体类型：</b>{{ row.requestBodyType || '-' }}</div>
                <div><b>响应类型：</b>{{ row.responseType || '-' }}</div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="name" label="工具名" min-width="220" />
        <el-table-column label="端点" min-width="220">
          <template #default="{ row }">
            <span>{{ row.httpMethod || '-' }} {{ row.contextPath || '' }}{{ row.endpointPath || '' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="描述" min-width="280" />
        <el-table-column label="参数数" width="90" align="center">
          <template #default="{ row }">
            {{ (row.parameters || []).length }}
          </template>
        </el-table-column>
        <el-table-column label="启用" width="90" align="center">
          <template #default="{ row }">
            <el-switch :model-value="row.enabled" @change="handleEnabledChange(row, $event as boolean)" />
          </template>
        </el-table-column>
        <el-table-column label="Agent 可见" width="110" align="center">
          <template #default="{ row }">
            <el-switch :model-value="row.agentVisible" @change="handleFlagChange(row, 'agentVisible', $event as boolean)" />
          </template>
        </el-table-column>
        <el-table-column label="轻量调用" width="110" align="center">
          <template #default="{ row }">
            <el-switch :model-value="row.lightweightEnabled" @change="handleFlagChange(row, 'lightweightEnabled', $event as boolean)" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEditDialog(row)">编辑</el-button>
            <el-button link type="primary" size="small" @click="openTest(row)">测试</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-if="!loading && tools.length === 0" description="这个项目还没有扫描出任何接口" />
    </el-card>
      </el-tab-pane>

      <el-tab-pane label="AI 理解" name="ai" lazy>
        <div class="ai-tab">
          <div class="ai-toolbar">
            <el-button type="primary" :loading="batchStarting" @click="startBatchGenerate(false)">
              一键生成 AI 理解
            </el-button>
            <el-button :loading="batchStarting" @click="startBatchGenerate(true)">强制重生成（覆盖已编辑）</el-button>
            <el-button @click="reloadAiTab">刷新</el-button>
            <el-select
              v-model="semanticProvider"
              placeholder="Provider"
              clearable
              filterable
              class="semantic-llm-select"
              @change="onSemanticProviderChange"
            >
              <el-option v-for="p in semanticProviders" :key="p.name" :label="p.name" :value="p.name" />
            </el-select>
            <el-select
              v-model="semanticModel"
              placeholder="模型"
              clearable
              filterable
              class="semantic-llm-select semantic-model-select"
              :disabled="!semanticProvider"
            >
              <el-option v-for="m in semanticModelsForSelect" :key="m" :label="m" :value="m" />
            </el-select>
            <el-tag v-if="task" :type="taskTagType(task.stage)" style="margin-left: 12px">
              {{ task.stage }} · {{ task.completedSteps }}/{{ task.totalSteps }}
            </el-tag>
            <span v-if="task" class="token-sum">累计 token：{{ task.totalTokens }}</span>
          </div>
          <el-progress
            v-if="task && (task.stage === 'QUEUED' || task.stage === 'RUNNING')"
            :percentage="taskPercent"
            :text-inside="true"
            :stroke-width="18"
            class="task-progress"
          />
          <el-alert
            v-if="task && task.stage === 'FAILED'"
            type="error"
            :title="`批量生成失败：${task.errorMessage || '未知错误'}`"
            :closable="false"
            show-icon
          />

          <el-card shadow="never" class="section-card">
            <template #header>
              <div class="ai-card-header">
                <span>项目级摘要</span>
                <div>
                  <el-button size="small" :loading="projectGenLoading" @click="regenerateProject">重新生成</el-button>
                  <el-button size="small" :disabled="!projectDoc" @click="openEditDoc(projectDoc)">编辑</el-button>
                </div>
              </div>
            </template>
            <div v-if="projectDoc" class="markdown-body" v-html="renderMd(projectDoc.contentMd)" />
            <el-empty v-else description="项目级文档尚未生成" />
          </el-card>

          <el-card shadow="never" class="section-card">
            <template #header>
              <div class="ai-card-header">
                <span>模块列表（{{ modules.length }}）</span>
                <div>
                  <el-button size="small" :disabled="selectedModuleIds.length < 2" @click="openMergeDialog">
                    合并选中（{{ selectedModuleIds.length }}）
                  </el-button>
                </div>
              </div>
            </template>
            <el-table :data="modules" stripe @selection-change="onModuleSelectionChange">
              <el-table-column type="selection" width="48" />
              <el-table-column prop="displayName" label="展示名" min-width="200" />
              <el-table-column prop="name" label="原始类名" min-width="220" />
              <el-table-column label="聚合类" min-width="260">
                <template #default="{ row }">
                  <el-tag v-for="c in row.sourceClasses" :key="c" size="small" class="source-class-tag">{{ c }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="AI 文档" width="160">
                <template #default="{ row }">
                  <el-tag v-if="moduleDocMap[row.id]" :type="moduleDocMap[row.id].status === 'edited' ? 'success' : 'info'" size="small">
                    {{ moduleDocMap[row.id].status }}
                  </el-tag>
                  <el-tag v-else size="small" type="warning">未生成</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="260">
                <template #default="{ row }">
                  <el-button link size="small" type="primary" @click="regenerateModule(row)">重新生成</el-button>
                  <el-button link size="small" :disabled="!moduleDocMap[row.id]" @click="openEditDoc(moduleDocMap[row.id])">编辑</el-button>
                  <el-button link size="small" @click="openRenameDialog(row)">重命名</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-card>

          <el-card shadow="never" class="section-card">
            <template #header>接口语义 AI 描述</template>
            <el-table :data="tools" stripe>
              <el-table-column type="expand">
                <template #default="{ row }">
                  <div v-if="toolDocMap[row.name]" class="markdown-body expand-md" v-html="renderMd(toolDocMap[row.name].contentMd)" />
                  <el-empty v-else description="该接口还没有 AI 描述" :image-size="60" />
                </template>
              </el-table-column>
              <el-table-column prop="name" label="工具名" min-width="220" />
              <el-table-column label="AI 描述" min-width="400">
                <template #default="{ row }">
                  <div class="ai-desc-ellipsis">
                    {{ toolDocSummary(row) }}
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="120">
                <template #default="{ row }">
                  <el-tag v-if="toolDocMap[row.name]" :type="toolDocMap[row.name].status === 'edited' ? 'success' : 'info'" size="small">
                    {{ toolDocMap[row.name].status }}
                  </el-tag>
                  <el-tag v-else size="small" type="warning">未生成</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="200">
                <template #default="{ row }">
                  <el-button link size="small" type="primary" @click="regenerateTool(row)">重新生成</el-button>
                  <el-button link size="small" :disabled="!toolDocMap[row.name]" @click="openEditDoc(toolDocMap[row.name])">编辑</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-card>
        </div>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="docEditVisible" title="编辑 AI 文档（保存后标记为 edited，不会被重新生成覆盖，除非强制）" width="720px">
      <el-input v-model="docEditContent" type="textarea" :rows="18" placeholder="Markdown 内容" />
      <template #footer>
        <el-button @click="docEditVisible = false">取消</el-button>
        <el-button type="primary" :loading="docEditSaving" @click="submitDocEdit">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="mergeDialogVisible" title="合并模块" width="520px">
      <el-form label-width="100px">
        <el-form-item label="合并目标">
          <el-select v-model="mergeTargetId" style="width: 100%">
            <el-option
              v-for="m in mergeSelectedModules"
              :key="m.id"
              :value="m.id"
              :label="`${m.displayName}（${m.name}）`"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="合并后名称">
          <el-input v-model="mergeDisplayName" placeholder="可选，留空则沿用目标模块名" />
        </el-form-item>
        <el-form-item label="被合并">
          <div>
            <el-tag
              v-for="m in mergeSourceModules"
              :key="m.id"
              class="source-class-tag"
            >{{ m.displayName }}</el-tag>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="mergeDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="mergeSaving" @click="submitMerge">确认合并</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="renameDialogVisible" title="重命名模块" width="420px">
      <el-form label-width="100px">
        <el-form-item label="展示名">
          <el-input v-model="renameValue" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="renameDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="renameSaving" @click="submitRename">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="formDialogVisible" :title="form.name ? `编辑 Tool - ${form.name}` : '编辑 Tool'" width="760px">
      <el-form label-width="120px">
        <el-form-item label="工具名">
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-row :gutter="16">
          <el-col :span="8">
            <el-form-item label="来源">
              <el-input :model-value="form.source" disabled />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="HTTP 方法">
              <el-select v-model="form.httpMethod" style="width: 100%">
                <el-option v-for="method in httpMethods" :key="method" :label="method" :value="method" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="请求体类型">
              <el-input v-model="form.requestBodyType" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="Base URL">
              <el-input v-model="form.baseUrl" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="Context Path">
              <el-input v-model="form.contextPath" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="Endpoint Path">
              <el-input v-model="form.endpointPath" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="响应类型">
              <el-input v-model="form.responseType" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="来源定位">
          <el-input v-model="form.sourceLocation" />
        </el-form-item>
        <el-form-item label="参数定义">
          <div class="parameter-editor">
            <el-table :data="form.parameters" size="small" border>
              <el-table-column label="参数名" min-width="120">
                <template #default="{ row }">
                  <el-input v-model="row.name" />
                </template>
              </el-table-column>
              <el-table-column label="类型" width="120">
                <template #default="{ row }">
                  <el-input v-model="row.type" />
                </template>
              </el-table-column>
              <el-table-column label="位置" width="120">
                <template #default="{ row }">
                  <el-select v-model="row.location" style="width: 100%">
                    <el-option v-for="location in parameterLocations" :key="location" :label="location" :value="location" />
                  </el-select>
                </template>
              </el-table-column>
              <el-table-column label="描述" min-width="180">
                <template #default="{ row }">
                  <el-input v-model="row.description" />
                </template>
              </el-table-column>
              <el-table-column label="必填" width="80" align="center">
                <template #default="{ row }">
                  <el-switch v-model="row.required" />
                </template>
              </el-table-column>
              <el-table-column width="80" align="center">
                <template #default="{ $index }">
                  <el-button link type="danger" @click="removeParameter($index)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
            <el-button class="add-parameter-button" @click="addParameter">+ 添加参数</el-button>
          </div>
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

    <el-dialog v-model="testDialogVisible" :title="`测试工具 - ${testingTool?.name}`" width="600px">
      <el-form v-if="testingTool" label-width="120px">
        <el-form-item v-for="param in testingTool.parameters" :key="param.name" :label="param.name" :required="param.required">
          <el-input v-model="testArgs[param.name]" :placeholder="param.description || param.type" />
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
        <el-button type="primary" :loading="testRunning" @click="handleTest">执行</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { marked } from 'marked'
import type { ProviderInfo } from '@/types/model'
import type { ProjectToolInfo, ScanProject } from '@/types/scanProject'
import type { ToolInfo, ToolParameter, ToolTestResult, ToolUpsertRequest } from '@/types/tool'
import type { ScanModule, SemanticDoc, SemanticTask } from '@/types/semanticDoc'
import { getScanProjectDetail, getScanProjectTools, triggerRescan } from '@/api/scanProject'
import { testTool, toggleTool, updateTool } from '@/api/tool'
import {
  editSemanticDoc,
  generateModuleDoc,
  generateProjectDoc,
  generateToolDoc,
  getProjectBatchStatus,
  listProjectSemanticDocs,
  listScanModules,
  mergeScanModules,
  renameScanModule,
  startProjectBatchGenerate,
  type SemanticLlmParams,
} from '@/api/semanticDoc'
import { getProviders } from '@/api/model'

const route = useRoute()
const router = useRouter()
const projectId = computed(() => Number(route.params.id))

const project = ref<ScanProject | null>(null)
const tools = ref<ProjectToolInfo[]>([])
const loading = ref(false)
const saving = ref(false)
const rescanLoading = ref(false)

const formDialogVisible = ref(false)
const editingName = ref<string | null>(null)
const form = reactive<ToolUpsertRequest>(createEmptyForm())
const httpMethods = ['GET', 'POST', 'PUT', 'DELETE', 'PATCH']
const parameterLocations = ['QUERY', 'PATH', 'BODY']

const testDialogVisible = ref(false)
const testingTool = ref<ToolInfo | null>(null)
const testArgs = reactive<Record<string, string>>({})
const testResult = ref<ToolTestResult | null>(null)
const testRunning = ref(false)

function createEmptyForm(): ToolUpsertRequest {
  return {
    name: '',
    description: '',
    parameters: [],
    source: 'scanner',
    sourceLocation: '',
    httpMethod: 'GET',
    baseUrl: '',
    contextPath: '/api',
    endpointPath: '',
    requestBodyType: '',
    responseType: '',
    projectId: null,
    enabled: false,
    agentVisible: false,
    lightweightEnabled: false,
  }
}

function cloneParameters(parameters: ToolParameter[] = []): ToolParameter[] {
  return parameters.map((parameter) => ({ ...parameter }))
}

function toUpsertRequest(tool: ProjectToolInfo): ToolUpsertRequest {
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

function statusTagType(status: ScanProject['status']) {
  if (status === 'scanned') return 'success'
  if (status === 'failed') return 'danger'
  if (status === 'scanning') return 'warning'
  return 'info'
}

async function refreshAll() {
  loading.value = true
  try {
    const [projectResponse, toolResponse] = await Promise.all([
      getScanProjectDetail(projectId.value),
      getScanProjectTools(projectId.value),
    ])
    project.value = projectResponse.data
    tools.value = Array.isArray(toolResponse.data) ? toolResponse.data : []
  } catch {
    project.value = null
    tools.value = []
    ElMessage.error('加载扫描详情失败')
  } finally {
    loading.value = false
  }
}

async function handleRescan() {
  rescanLoading.value = true
  try {
    const { data } = await triggerRescan(projectId.value)
    ElMessage.success(`重新扫描完成，发现 ${data.toolCount} 个接口`)
    await refreshAll()
  } catch (error) {
    ElMessage.error((error as Error).message || '重新扫描失败')
    await refreshAll()
  } finally {
    rescanLoading.value = false
  }
}

function openEditDialog(tool: ProjectToolInfo) {
  editingName.value = tool.name
  applyForm(toUpsertRequest(tool))
  formDialogVisible.value = true
}

function addParameter() {
  form.parameters.push({
    name: '',
    type: 'string',
    description: '',
    required: false,
    location: 'QUERY',
  })
}

function removeParameter(index: number) {
  form.parameters.splice(index, 1)
}

async function handleSave() {
  if (!editingName.value || !form.name.trim() || !form.description.trim()) {
    ElMessage.warning('请填写工具名和描述')
    return
  }
  saving.value = true
  try {
    await updateTool(editingName.value, { ...form, parameters: cloneParameters(form.parameters) })
    ElMessage.success('Tool 更新成功')
    formDialogVisible.value = false
    await refreshAll()
  } catch (error) {
    ElMessage.error((error as Error).message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function handleEnabledChange(tool: ProjectToolInfo, enabled: boolean) {
  try {
    await toggleTool(tool.name, enabled)
    ElMessage.success(`已${enabled ? '启用' : '禁用'} ${tool.name}`)
    await refreshAll()
  } catch (error) {
    ElMessage.error((error as Error).message || '状态更新失败')
  }
}

async function handleFlagChange(tool: ProjectToolInfo, field: 'agentVisible' | 'lightweightEnabled', value: boolean) {
  try {
    const payload = toUpsertRequest({
      ...tool,
      [field]: value,
    })
    await updateTool(tool.name, payload)
    ElMessage.success('配置已更新')
    await refreshAll()
  } catch (error) {
    ElMessage.error((error as Error).message || '配置更新失败')
  }
}

async function batchToggle(enabled: boolean) {
  try {
    await Promise.all(tools.value.map((tool) => toggleTool(tool.name, enabled)))
    ElMessage.success(enabled ? '已批量启用' : '已批量禁用')
    await refreshAll()
  } catch (error) {
    ElMessage.error((error as Error).message || '批量操作失败')
  }
}

function openTest(tool: ToolInfo) {
  testingTool.value = tool
  testResult.value = null
  Object.keys(testArgs).forEach((key) => delete testArgs[key])
  for (const parameter of tool.parameters || []) {
    testArgs[parameter.name] = ''
  }
  testDialogVisible.value = true
}

async function handleTest() {
  if (!testingTool.value) return
  testRunning.value = true
  testResult.value = null
  try {
    const args: Record<string, unknown> = {}
    for (const [key, value] of Object.entries(testArgs)) {
      if (value !== '') {
        args[key] = value
      }
    }
    const { data } = await testTool(testingTool.value.name, args)
    testResult.value = data as unknown as ToolTestResult
  } catch (error) {
    testResult.value = {
      success: false,
      result: '',
      errorMessage: (error as Error).message || '执行失败',
      durationMs: 0,
    }
  } finally {
    testRunning.value = false
  }
}

function goBack() {
  router.push('/scan-project')
}

// ==================== AI 理解 Tab ====================

const activeTab = ref<'scan' | 'ai'>('scan')
const modules = ref<ScanModule[]>([])
const projectDoc = ref<SemanticDoc | null>(null)
const moduleDocMap = ref<Record<number, SemanticDoc>>({})
const toolDocMap = ref<Record<string, SemanticDoc>>({})
const selectedModuleIds = ref<number[]>([])

const batchStarting = ref(false)
const task = ref<SemanticTask | null>(null)
const projectGenLoading = ref(false)
let pollTimer: ReturnType<typeof setInterval> | null = null

const semanticProviders = ref<ProviderInfo[]>([])
const semanticProvider = ref('')
const semanticModel = ref('')
const semanticModelsForSelect = computed(() => {
  const p = semanticProviders.value.find((x) => x.name === semanticProvider.value)
  return p?.models ?? []
})

const docEditVisible = ref(false)
const docEditContent = ref('')
const docEditingId = ref<number | null>(null)
const docEditSaving = ref(false)

const mergeDialogVisible = ref(false)
const mergeSelectedModules = ref<ScanModule[]>([])
const mergeSourceModules = ref<ScanModule[]>([])
const mergeTargetId = ref<number | null>(null)
const mergeDisplayName = ref('')
const mergeSaving = ref(false)

const renameDialogVisible = ref(false)
const renameTarget = ref<ScanModule | null>(null)
const renameValue = ref('')
const renameSaving = ref(false)

const taskPercent = computed(() => {
  if (!task.value || task.value.totalSteps <= 0) return 0
  return Math.min(100, Math.round((task.value.completedSteps / task.value.totalSteps) * 100))
})

function renderMd(content: string | null | undefined): string {
  if (!content) return ''
  return marked.parse(content, { async: false }) as string
}

function toolDocSummary(tool: ProjectToolInfo): string {
  const doc = toolDocMap.value[tool.name]
  if (!doc || !doc.contentMd) return tool.description || '（无 AI 描述）'
  const marker = '## 一句话语义'
  const idx = doc.contentMd.indexOf(marker)
  if (idx < 0) return doc.contentMd.slice(0, 120)
  const rest = doc.contentMd.slice(idx + marker.length).trim()
  const next = rest.indexOf('\n##')
  const section = next > 0 ? rest.slice(0, next) : rest
  return section.trim().slice(0, 140)
}

function taskTagType(stage: SemanticTask['stage']) {
  switch (stage) {
    case 'DONE': return 'success'
    case 'FAILED': return 'danger'
    case 'RUNNING': return 'warning'
    default: return 'info'
  }
}

function syncSemanticModelToProvider() {
  const models = semanticModelsForSelect.value
  if (models.length === 0) {
    semanticModel.value = ''
    return
  }
  if (!semanticModel.value || !models.includes(semanticModel.value)) {
    semanticModel.value = models[0]
  }
}

function onSemanticProviderChange() {
  if (!semanticProvider.value) {
    semanticModel.value = ''
    return
  }
  syncSemanticModelToProvider()
}

/** 传给语义生成接口；未选 Provider/模型时由后端走默认网关配置 */
function semanticLlmParams(): SemanticLlmParams | undefined {
  const p = semanticProvider.value?.trim()
  const m = semanticModel.value?.trim()
  if (!p && !m) return undefined
  return {
    ...(p ? { provider: p } : {}),
    ...(m ? { model: m } : {}),
  }
}

async function loadSemanticProviders() {
  try {
    const { data } = await getProviders()
    const list = (data?.data ?? (Array.isArray(data) ? data : [])) as ProviderInfo[]
    semanticProviders.value = list
    if (list.length === 0) {
      semanticProvider.value = ''
      semanticModel.value = ''
      return
    }
    if (!semanticProvider.value || !list.some((x) => x.name === semanticProvider.value)) {
      semanticProvider.value = list[0].name
    }
    syncSemanticModelToProvider()
  } catch {
    semanticProviders.value = []
  }
}

async function reloadAiTab() {
  try {
    const [moduleResp, docResp] = await Promise.all([
      listScanModules(projectId.value),
      listProjectSemanticDocs(projectId.value),
    ])
    modules.value = Array.isArray(moduleResp.data) ? moduleResp.data : []
    const docs = Array.isArray(docResp.data) ? docResp.data : []
    projectDoc.value = docs.find((d) => d.level === 'project') || null
    const mMap: Record<number, SemanticDoc> = {}
    docs.filter((d) => d.level === 'module' && d.moduleId != null).forEach((d) => { mMap[d.moduleId as number] = d })
    moduleDocMap.value = mMap
    const tMap: Record<string, SemanticDoc> = {}
    for (const d of docs.filter((x) => x.level === 'tool')) {
      if (d.toolName) tMap[d.toolName] = d
    }
    toolDocMap.value = tMap
  } catch (error) {
    ElMessage.error((error as Error).message || '加载 AI 理解数据失败')
  }
}

async function startBatchGenerate(force: boolean) {
  batchStarting.value = true
  try {
    const { data } = await startProjectBatchGenerate(projectId.value, force, semanticLlmParams())
    ElMessage.success('已提交批量生成任务')
    startPollingTask(data.taskId)
  } catch (error) {
    ElMessage.error((error as Error).message || '启动批量生成失败')
  } finally {
    batchStarting.value = false
  }
}

function startPollingTask(taskId: string) {
  stopPollingTask()
  const poll = async () => {
    try {
      const { data } = await getProjectBatchStatus(projectId.value, taskId)
      if (data == null) {
        task.value = null
        stopPollingTask()
        return
      }
      task.value = data
      if (data.stage === 'DONE' || data.stage === 'FAILED') {
        stopPollingTask()
        await reloadAiTab()
        await refreshAll()
      }
    } catch {
      stopPollingTask()
    }
  }
  poll()
  pollTimer = setInterval(poll, 2500)
}

function stopPollingTask() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

async function regenerateProject() {
  projectGenLoading.value = true
  try {
    const { data } = await generateProjectDoc(projectId.value, true, semanticLlmParams())
    projectDoc.value = data
    ElMessage.success('项目级 AI 摘要已更新')
  } catch (error) {
    ElMessage.error((error as Error).message || '生成失败')
  } finally {
    projectGenLoading.value = false
  }
}

async function regenerateModule(row: ScanModule) {
  try {
    const { data } = await generateModuleDoc(row.id, true, semanticLlmParams())
    moduleDocMap.value = { ...moduleDocMap.value, [row.id]: data }
    ElMessage.success(`已更新模块 ${row.displayName}`)
  } catch (error) {
    ElMessage.error((error as Error).message || '生成失败')
  }
}

async function regenerateTool(row: ProjectToolInfo) {
  try {
    const { data } = await generateToolDoc(row.name, true, semanticLlmParams())
    toolDocMap.value = { ...toolDocMap.value, [row.name]: data }
    ElMessage.success(`已更新接口 ${row.name}`)
    await refreshAll()
  } catch (error) {
    ElMessage.error((error as Error).message || '生成失败')
  }
}

function openEditDoc(doc: SemanticDoc | null | undefined) {
  if (!doc) return
  docEditingId.value = doc.id
  docEditContent.value = doc.contentMd || ''
  docEditVisible.value = true
}

async function submitDocEdit() {
  if (!docEditingId.value) return
  docEditSaving.value = true
  try {
    const { data } = await editSemanticDoc(docEditingId.value, { contentMd: docEditContent.value })
    if (data.level === 'project') projectDoc.value = data
    else if (data.level === 'module' && data.moduleId != null)
      moduleDocMap.value = { ...moduleDocMap.value, [data.moduleId]: data }
    else if (data.level === 'tool' && data.toolId != null) {
      const name = Object.keys(toolDocMap.value).find((key) => toolDocMap.value[key].id === data.id)
      if (name) toolDocMap.value = { ...toolDocMap.value, [name]: data }
    }
    docEditVisible.value = false
    ElMessage.success('已保存')
  } catch (error) {
    ElMessage.error((error as Error).message || '保存失败')
  } finally {
    docEditSaving.value = false
  }
}

function onModuleSelectionChange(rows: ScanModule[]) {
  selectedModuleIds.value = rows.map((r) => r.id)
}

function openMergeDialog() {
  const selected = modules.value.filter((m) => selectedModuleIds.value.includes(m.id))
  if (selected.length < 2) {
    ElMessage.warning('至少选中 2 个模块才能合并')
    return
  }
  mergeSelectedModules.value = selected
  mergeTargetId.value = selected[0].id
  mergeDisplayName.value = ''
  mergeSourceModules.value = selected
  mergeDialogVisible.value = true
}

watch(mergeTargetId, (target) => {
  mergeSourceModules.value = mergeSelectedModules.value.filter((m) => m.id !== target)
})

async function submitMerge() {
  if (!mergeTargetId.value) return
  const sourceIds = mergeSourceModules.value.map((m) => m.id)
  mergeSaving.value = true
  try {
    await mergeScanModules({
      targetId: mergeTargetId.value,
      sourceIds,
      displayName: mergeDisplayName.value || null,
    })
    mergeDialogVisible.value = false
    ElMessage.success('合并成功')
    await reloadAiTab()
  } catch (error) {
    ElMessage.error((error as Error).message || '合并失败')
  } finally {
    mergeSaving.value = false
  }
}

function openRenameDialog(row: ScanModule) {
  renameTarget.value = row
  renameValue.value = row.displayName
  renameDialogVisible.value = true
}

async function submitRename() {
  if (!renameTarget.value) return
  renameSaving.value = true
  try {
    await renameScanModule(renameTarget.value.id, renameValue.value.trim())
    renameDialogVisible.value = false
    ElMessage.success('已重命名')
    await reloadAiTab()
  } catch (error) {
    ElMessage.error((error as Error).message || '重命名失败')
  } finally {
    renameSaving.value = false
  }
}

watch(activeTab, (tab) => {
  if (tab === 'ai') {
    void loadSemanticProviders()
    void reloadAiTab()
    if (!task.value) {
      getProjectBatchStatus(projectId.value).then(({ data }) => {
        task.value = data ?? null
        if (data && (data.stage === 'RUNNING' || data.stage === 'QUEUED')) {
          startPollingTask(data.taskId)
        }
      }).catch(() => {
        task.value = null
      })
    }
  }
})

onMounted(refreshAll)
onUnmounted(stopPollingTask)
</script>

<style scoped lang="scss">
.header-actions {
  display: flex;
  gap: 8px;
}

.project-summary {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px 20px;
  font-size: 14px;
}

.tools-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.tools-actions {
  display: flex;
  gap: 8px;
}

.expand-content {
  padding: 12px 20px;

  h4 {
    font-size: 13px;
    color: #909399;
    margin-bottom: 8px;
  }
}

.tool-meta {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px 16px;
  margin-top: 12px;
  font-size: 13px;
  color: #606266;
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

.param-hint {
  font-size: 12px;
  color: #909399;
  margin-top: 2px;
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

.detail-tabs {
  margin-top: 16px;
}

.ai-tab {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.ai-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.semantic-llm-select {
  width: 140px;
}

.semantic-model-select {
  width: 200px;
}

.token-sum {
  color: #909399;
  font-size: 12px;
  margin-left: auto;
}

.task-progress {
  margin-top: 4px;
}

.ai-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.markdown-body {
  font-size: 13px;
  line-height: 1.7;
  padding: 4px 4px 8px;

  :deep(h1), :deep(h2), :deep(h3) {
    margin-top: 12px;
    margin-bottom: 6px;
  }

  :deep(pre) {
    background: #f6f7f9;
    border-radius: 4px;
    padding: 10px;
    overflow: auto;
  }

  :deep(table) {
    border-collapse: collapse;

    th, td {
      border: 1px solid #dcdfe6;
      padding: 4px 8px;
    }
  }
}

.expand-md {
  padding: 8px 16px 16px;
}

.ai-desc-ellipsis {
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.source-class-tag {
  margin-right: 6px;
  margin-bottom: 4px;
}
</style>
