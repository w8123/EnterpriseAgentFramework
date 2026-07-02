import { reactive, ref, type Ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  testScanProjectTool,
  toggleScanProjectTool,
  updateScanProjectTool,
} from '@/api/scanProject'
import type { ProjectToolInfo } from '@/types/scanProject'
import type {
  ToolParameter,
  ToolTestResult,
  ToolUpsertRequest,
} from '@/types/tool'

export interface UseScanProjectToolEditorDeps {
  projectId: Readonly<Ref<number>>
  tools: Ref<ProjectToolInfo[]>
  ensureToolDetail: (tool: ProjectToolInfo) => Promise<ProjectToolInfo>
  refreshAll: () => Promise<void>
}

interface ParameterRow extends ToolParameter {
  _key: string
  children?: ParameterRow[]
}

function createEmptyForm(): ToolUpsertRequest {
  return {
    name: '',
    description: '',
    parameters: [],
    source: 'scanner',
    sourceLocation: '',
    httpMethod: 'GET',
    baseUrl: '',
    contextPath: '',
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
    contextPath: tool.contextPath ?? '',
    endpointPath: tool.endpointPath || '',
    requestBodyType: tool.requestBodyType || '',
    responseType: tool.responseType || '',
    projectId: tool.projectId ?? null,
    enabled: tool.enabled,
    agentVisible: tool.agentVisible,
    lightweightEnabled: tool.lightweightEnabled,
  }
}

export function parameterRows(parameters: ToolParameter[] | null | undefined, prefix = ''): ParameterRow[] {
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

export function useScanProjectToolEditor(deps: UseScanProjectToolEditorDeps) {
  const saving = ref(false)
  const formDialogVisible = ref(false)
  const editingScanToolId = ref<number | null>(null)
  const form = reactive<ToolUpsertRequest>(createEmptyForm())
  const httpMethods = ['GET', 'POST', 'PUT', 'DELETE', 'PATCH']
  const parameterLocations = ['QUERY', 'PATH', 'BODY']

  const testDialogVisible = ref(false)
  const testingTool = ref<ProjectToolInfo | null>(null)
  const testArgs = reactive<Record<string, string>>({})
  const testResult = ref<ToolTestResult | null>(null)
  const testRunning = ref(false)

  function applyForm(data: ToolUpsertRequest) {
    form.name = data.name
    form.description = data.description
    form.parameters = cloneParameters(data.parameters)
    form.source = data.source
    form.sourceLocation = data.sourceLocation || ''
    form.httpMethod = data.httpMethod || 'GET'
    form.baseUrl = data.baseUrl || ''
    form.contextPath = data.contextPath ?? ''
    form.endpointPath = data.endpointPath || ''
    form.requestBodyType = data.requestBodyType || ''
    form.responseType = data.responseType || ''
    form.projectId = data.projectId ?? null
    form.enabled = data.enabled
    form.agentVisible = data.agentVisible
    form.lightweightEnabled = data.lightweightEnabled
  }

  async function openEditDialog(tool: ProjectToolInfo) {
    const detail = await deps.ensureToolDetail(tool)
    editingScanToolId.value = detail.scanToolId
    applyForm(toUpsertRequest(detail))
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
    if (editingScanToolId.value == null || !form.name.trim() || !form.description.trim()) {
      ElMessage.warning('请填写工具名和描述')
      return
    }
    saving.value = true
    try {
      await updateScanProjectTool(deps.projectId.value, editingScanToolId.value, {
        ...form,
        parameters: cloneParameters(form.parameters),
      })
      ElMessage.success('扫描接口已更新')
      formDialogVisible.value = false
      await deps.refreshAll()
    } catch (error) {
      ElMessage.error((error as Error).message || '保存失败')
    } finally {
      saving.value = false
    }
  }

  async function handleEnabledChange(tool: ProjectToolInfo, enabled: boolean) {
    try {
      await toggleScanProjectTool(deps.projectId.value, tool.scanToolId, enabled)
      ElMessage.success(`已${enabled ? '启用' : '禁用'} ${tool.name}`)
      await deps.refreshAll()
    } catch (error) {
      ElMessage.error((error as Error).message || '状态更新失败')
    }
  }

  async function handleFlagChange(tool: ProjectToolInfo, field: 'agentVisible' | 'lightweightEnabled', value: boolean) {
    try {
      const detail = await deps.ensureToolDetail(tool)
      const payload = toUpsertRequest({
        ...detail,
        [field]: value,
      })
      await updateScanProjectTool(deps.projectId.value, detail.scanToolId, payload)
      ElMessage.success('配置已更新')
      await deps.refreshAll()
    } catch (error) {
      ElMessage.error((error as Error).message || '配置更新失败')
    }
  }

  async function batchToggle(enabled: boolean) {
    try {
      await Promise.all(
        deps.tools.value.map((tool) => toggleScanProjectTool(deps.projectId.value, tool.scanToolId, enabled)),
      )
      ElMessage.success(enabled ? '已批量启用' : '已批量禁用')
      await deps.refreshAll()
    } catch (error) {
      ElMessage.error((error as Error).message || '批量操作失败')
    }
  }

  async function openTest(tool: ProjectToolInfo) {
    const detail = await deps.ensureToolDetail(tool)
    testingTool.value = detail
    testResult.value = null
    Object.keys(testArgs).forEach((key) => delete testArgs[key])
    for (const parameter of detail.parameters || []) {
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
      const { data } = await testScanProjectTool(deps.projectId.value, testingTool.value.scanToolId, args)
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

  return {
    saving,
    formDialogVisible,
    form,
    httpMethods,
    parameterLocations,
    testDialogVisible,
    testingTool,
    testArgs,
    testResult,
    testRunning,
    parameterRows,
    openEditDialog,
    addParameter,
    removeParameter,
    handleSave,
    handleEnabledChange,
    handleFlagChange,
    batchToggle,
    openTest,
    handleTest,
  }
}
