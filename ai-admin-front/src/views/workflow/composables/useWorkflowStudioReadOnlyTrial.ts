import { computed, onScopeDispose, reactive, ref, watch, type Ref } from 'vue'
import { getHttpApi, getHttpApiConnection } from '@/api/httpApi'
import { getBusinessMethodInvocationContext } from '@/api/businessMethodInvocation'
import type { BusinessMethodInvocationContext } from '@/types/businessMethodInvocation'
import { runWorkflowReadOnlyTrial } from '@/api/workflow'
import type { HttpApiConnection, HttpApiDetail } from '@/types/httpApi'
import type { WorkflowReadOnlyTrialResult, WorkflowWorkingCopyState } from '@/types/workflow'
import { httpApiInputTargets, httpApiReadinessReason } from '@/views/workflow/httpApiWorkflow'

interface TrialTarget {
  nodeId: string
  apiId: number
  assetType: 'HTTP_API' | 'BUSINESS_METHOD'
  qualifiedName: string
  mapping: Record<string, string>
  supported: boolean
}

export function savedReadOnlyTrialTarget(graphJson: string): TrialTarget | null {
  try {
    const graph = JSON.parse(graphJson)
    const nodes = Array.isArray(graph.nodes) ? graph.nodes : []
    const api = nodes.find((node: any) => node.type === 'TOOL' && node.ref?.qualifiedName)
    if (!api) return null
    const variable = nodes.find((node: any) => node.type === 'VARIABLE_ASSIGN')
    const mapping = api.config.inputMapping || {}
    const assetType = api.config?.httpApiAssetId || api.ref.qualifiedName.startsWith('http-api:') ? 'HTTP_API' : 'BUSINESS_METHOD'
    const method = assetType === 'BUSINESS_METHOD'
    const supported = nodes.length === 2 && variable && graph.entryNodeId === api.id
      && nodes.every((node: any) => ['', 'TERMINATE'].includes(node.errorPolicy?.strategy || ''))
      && graph.exitNodeIds?.length === 1 && graph.exitNodeIds[0] === variable.id
      && graph.edges?.length === 1 && graph.edges[0].from === api.id && graph.edges[0].to === variable.id
      && api.ref?.kind === 'TOOL' && !api.ref.definitionId && !api.ref.contractHash
      && ['', 'always'].includes(graph.edges[0].condition || '')
      && Object.keys(mapping).length > 0
      && Object.entries(mapping).every(([key, value]) => (method ? /^[A-Za-z][A-Za-z0-9_-]{0,127}$/ : /^(pathParams|queryParams)\.[A-Za-z][A-Za-z0-9_-]{0,127}$/).test(key)
        && typeof value === 'string' && /^params\.[A-Za-z][A-Za-z0-9_-]{0,127}$/.test(value))
      && (!method || !Object.keys(api.config?.args || {}).length)
      && Object.keys(variable.config?.assignments || {}).length > 0
      && Object.entries(variable.config.assignments).every(([key, value]) => /^[A-Za-z][A-Za-z0-9_-]{0,127}$/.test(key)
        && typeof value === 'string' && value.startsWith(`nodeOutput.${api.id}.`)
        && (method ? /^data$/ : /^[A-Za-z][A-Za-z0-9_.-]{0,127}$/).test(value.slice(`nodeOutput.${api.id}.`.length)))
    return { nodeId: api.id, apiId: Number(api.config.httpApiAssetId || 0), assetType,
      qualifiedName: api.ref?.qualifiedName || '', mapping, supported: Boolean(supported) }
  } catch { return null }
}

export function trialFailureHint(code: string): string {
  if (/AFTER_DISPATCH|RESULT_UNCONFIRMED|TIMEOUT|HTTP_FAILED/.test(code)) return '结果未确认，请先核对运行记录和目标系统，勿直接重试。'
  if (/DRAFT|REVISION|GRAPH|TARGET/.test(code)) return '草稿或节点已变化，请保存或重新加载后检查。'
  if (/BUSINESS_METHOD.*BUSINESS_IDENTITY/.test(code)) return '此方法要求业务用户身份，项目测试身份不可调用；请联系方法接入开发者核对。'
  if (/BUSINESS_METHOD.*CREDENTIAL/.test(code)) return '项目签名凭据不可用，请到业务方法所属项目检查接入状态。'
  if (/BUSINESS_METHOD.*(SOURCE|OWNER|FACTS|READ_ONLY|DISABLED|UNAVAILABLE)/.test(code)) return '业务方法来源、只读声明或已接纳契约已变化，请到方法详情核对。'
  if (/ACL|PERMISSION|IDENTITY|PROJECT|AUTH_REQUIRED/.test(code)) return '项目或业务方法/API 调用授权不足，请检查当前账号权限。'
  if (/CONNECTION|CREDENTIAL|AUTH_MISMATCH|EGRESS/.test(code)) return '项目连接或凭据不可用，请到 API 详情检查连接配置。'
  if (/SOURCE|OWNER|FACTS|READ_ONLY/.test(code)) return 'API 来源、只读声明或已接纳契约已变化，请到 API 详情核对。'
  if (/INPUT|PARAM/.test(code)) return '测试参数无效，请检查必填项与参数类型。'
  return '结果未确认，请先核对运行记录和目标系统，再决定是否发起新试运行。'
}

export function useWorkflowStudioReadOnlyTrial(deps: {
  studio: Ref<WorkflowWorkingCopyState | null>
  open: Readonly<Ref<boolean>>
  dirty: Readonly<Ref<boolean>>
  authorized: Readonly<Ref<boolean>>
  sessionScope: Readonly<Ref<string>>
}) {
  const detail = ref<HttpApiDetail | null>(null)
  const connection = ref<HttpApiConnection | null>(null)
  const methodDetail = ref<BusinessMethodInvocationContext | null>(null)
  const previewLoading = ref(false)
  const previewFailure = ref('')
  const running = ref(false)
  const error = ref('')
  const invalidField = ref('')
  const result = ref<WorkflowReadOnlyTrialResult | null>(null)
  const inputParams = reactive<Record<string, string>>({})
  const target = computed(() => savedReadOnlyTrialTarget(deps.studio.value?.graphSpecJson || ''))
  const scope = computed(() => [deps.sessionScope.value, deps.studio.value?.workflowId || '',
    deps.studio.value?.revision || '', deps.open.value].join('\u0000'))
  let generation = 0
  let disposed = false
  const current = (token: number, key: string) => !disposed && token === generation && scope.value === key
  const isMethod = computed(() => target.value?.assetType === 'BUSINESS_METHOD')
  const declaredMethodInputs = computed(() => (methodDetail.value?.parameters || []).filter((field) => !['RETURN', 'OUTPUT', 'RESPONSE'].includes(field.location || '')))
  const fields = computed(() => (isMethod.value ? declaredMethodInputs.value.map((field) => ({
    key: field.name, name: field.name, required: field.required === true, type: methodScalarType(field.type) || 'unsupported',
  })) : httpApiInputTargets(detail.value?.acceptedContract)).flatMap((field) => {
    const source = target.value?.mapping[field.key]
    return source?.startsWith('params.') ? [{ ...field, inputName: source.slice(7) }] : []
  }))
  const reason = computed(() => {
    if (deps.dirty.value) return '有未保存编辑，请先保存，再试运行该修订。'
    if (!deps.studio.value?.revision || deps.studio.value.status !== 'DRAFT') return '需要已保存且未发布的草稿。'
    if (!target.value?.supported) return '本次只支持 START → 一个只读业务方法或 API → 变量 → END 草稿。'
    if (!deps.authorized.value) return '当前账号缺少本项目的调试或业务方法/API 调用权限。'
    if (previewLoading.value) return '正在读取来源和项目调用条件。'
    if (previewFailure.value) return previewFailure.value
    if (isMethod.value) {
      const owner = methodDetail.value
      if (!owner) return '业务方法来源暂不可读，请刷新试运行条件。'
      if (owner.assetType !== 'BUSINESS_METHOD' || owner.qualifiedName !== target.value.qualifiedName
          || owner.sourceQualifiedName !== target.value.qualifiedName || owner.projectId !== deps.studio.value.projectId
          || owner.projectCode !== deps.studio.value.projectCode) return '业务方法身份或所属项目已变化，请重新选择并保存。'
      if (!owner.enabled || owner.sourceAvailability !== 'READY' || !owner.currentContractHash
          || owner.currentContractHash !== owner.acceptedContractHash || owner.currentContractHash !== owner.sourceContractHash) return '业务方法来源或已接纳契约已变化，请到方法详情核对。'
      if (owner.sideEffect !== 'READ_ONLY') return '业务方法未明确声明 READ_ONLY，不可进行只读试运行。'
      if (!owner.credentialAvailable) return '项目签名凭据不可用，请检查业务系统接入状态。'
      if (owner.businessIdentityRequired) return '业务方法要求业务用户身份，项目测试身份不可调用。'
      if (!owner.executable) return '业务方法当前不可试运行，请到方法详情核对。'
      if (!methodScalarType(owner.responseType)) return '本批仅支持已声明的标量返回，当前复杂或未知返回契约不可试运行。'
      if (declaredMethodInputs.value.some((field) => field.children?.length || !methodScalarType(field.type) || field.metadata?.sensitive === true)) return '本批仅支持非敏感标量参数，当前复杂或敏感契约不可试运行。'
      if (Object.keys(target.value.mapping).some((name) => !declaredMethodInputs.value.some((field) => field.name === name))
          || declaredMethodInputs.value.some((field) => field.required && !target.value!.mapping[field.name])) return '业务方法参数映射不完整，请检查并保存。'
      return ''
    }
    if (!detail.value || !connection.value) return 'API 来源或项目连接暂不可读，请刷新试运行条件。'
    if (detail.value.summary.httpMethod !== 'GET' || !['READ_ONLY', 'NONE'].includes(detail.value.acceptedContract?.sideEffect || '')) {
      return '此 API 会写入业务数据，不可进行 Studio 只读试运行；请保存并通过正式入口显式发布。'
    }
    if (detail.value.summary.id !== target.value.apiId
        || detail.value.summary.qualifiedName !== target.value.qualifiedName) return 'API 身份已变化，请重新选择并保存。'
    return httpApiReadinessReason(detail.value.summary, detail.value, connection.value, {
      projectId: deps.studio.value.projectId, projectCode: deps.studio.value.projectCode,
      environment: detail.value.summary.environment,
    })
  })

  async function refresh() {
    if (running.value) return
    const token = ++generation
    const key = scope.value
    detail.value = null; connection.value = null; methodDetail.value = null; error.value = ''; previewLoading.value = false; previewFailure.value = ''
    if (!deps.open.value || !target.value) return
    previewLoading.value = true
    try {
      if (isMethod.value) {
        const owner = await getBusinessMethodInvocationContext(target.value.qualifiedName)
        if (!current(token, key)) return
        methodDetail.value = owner.data
        fields.value.forEach((field) => { inputParams[field.inputName] ??= '' })
        return
      }
      const [owner, configured] = await Promise.all([
        getHttpApi(target.value.apiId), getHttpApiConnection(target.value.apiId),
      ])
      if (!current(token, key)) return
      detail.value = owner.data; connection.value = configured.data
      fields.value.forEach((field) => { inputParams[field.inputName] ??= '' })
    } catch (failure: any) {
      if (current(token, key)) {
        const status = failure?.response?.status
        previewFailure.value = status === 403
          ? '缺少该目标的调用权限（ACL），请联系项目管理员授权后刷新。'
          : status === 401 ? '登录状态已失效，请重新登录后再试运行。' : ''
        error.value = previewFailure.value || '试运行条件暂不可读；当前草稿和输入已保留。'
      }
    } finally {
      if (current(token, key)) previewLoading.value = false
    }
  }

  async function run() {
    if (running.value) return
    error.value = ''; invalidField.value = ''
    if (reason.value) { error.value = reason.value; return }
    const body: Record<string, string | number | boolean> = {}
    for (const field of fields.value) {
      const value = inputParams[field.inputName] || ''
      if (!value && field.required) {
        invalidField.value = field.inputName; error.value = `请填写 ${field.name}。`; return
      }
      if (!value) continue
      if (['number', 'integer'].includes(field.type)) {
        const number = Number(value)
        if (!Number.isFinite(number) || (field.type === 'integer' && !Number.isInteger(number))) {
          invalidField.value = field.inputName; error.value = `${field.name} 需要${field.type === 'integer' ? '整数' : '数值'}。`; return
        }
        body[field.inputName] = number
      } else if (field.type === 'boolean') {
        if (!['true', 'false'].includes(value)) {
          invalidField.value = field.inputName; error.value = `${field.name} 需要 true 或 false。`; return
        }
        body[field.inputName] = value === 'true'
      } else body[field.inputName] = value
    }
    const saved = deps.studio.value!
    const token = ++generation; const key = scope.value
    running.value = true; result.value = null
    try {
      const response = await runWorkflowReadOnlyTrial({ workflowId: saved.workflowId,
        expectedRevision: saved.revision!, inputParams: body })
      if (!current(token, key)) return
      result.value = response.data
      if (!response.data.success) error.value = `${response.data.errorCode || 'HTTP_API_TRIAL_FAILED'}：${trialFailureHint(response.data.errorCode || '')}`
    } catch (failure: any) {
      if (!current(token, key)) return
      const code = failure?.response?.data?.errorCode || (failure?.response?.status === 403
        ? 'HTTP_API_TRIAL_PERMISSION_DENIED' : failure?.response?.status === 401
          ? 'RUNTIME_INTERNAL_AUTH_REQUIRED' : 'HTTP_API_TRIAL_RESULT_UNCONFIRMED')
      error.value = `${code}：${trialFailureHint(code)}`
    } finally {
      running.value = false
      if (!disposed && deps.open.value && !current(token, key)) void refresh()
    }
  }

  watch(scope, () => {
    generation++; result.value = null; invalidField.value = ''
    if (!deps.open.value) return
    void refresh()
  }, { immediate: true })
  watch(deps.sessionScope, () => {
    Object.keys(inputParams).forEach((key) => delete inputParams[key])
  })
  onScopeDispose(() => { disposed = true; generation++ })
  return { target, detail, methodDetail, isMethod, connection, fields, reason, previewLoading, running, error,
    invalidField, result, inputParams, refresh, run }
}

function methodScalarType(raw?: string | null): string | null {
  const type = (raw || '').replace('java.lang.', '').toLowerCase()
  if (['string', 'char', 'character'].includes(type)) return 'string'
  if (['boolean', 'bool'].includes(type)) return 'boolean'
  if (['byte', 'short', 'int', 'integer', 'long'].includes(type)) return 'integer'
  if (['number', 'float', 'double', 'java.math.bigdecimal'].includes(type)) return 'number'
  return null
}
