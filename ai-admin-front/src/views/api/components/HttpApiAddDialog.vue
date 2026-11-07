<template>
  <AppDialog :model-value="modelValue" title="添加 API" width="860px" class="http-api-add-dialog" destroy-on-close
    @update:model-value="emit('update:modelValue', $event)">
    <template #header="{ titleId }">
      <div class="add-api-header">
        <div>
          <span :id="titleId" class="add-api-title">添加 API</span>
          <span class="add-api-subtitle">添加到「{{ project.name }}」。添加后是「待接纳」，确认契约并接纳后才能被智能体调用。</span>
        </div>
        <el-button link type="primary" class="add-api-agent-link" @click="openAgentAccess">如何连接我的智能体工具</el-button>
      </div>
    </template>

    <LiquidGlassTabs v-model="tab" class="add-api-tabs">
      <el-tab-pane label="让智能体扫描" name="agent">
        <div class="add-api-pane">
          <p class="add-api-lead">最省事的方式：在你的智能体工具（Codex、Claude Code、WorkBuddy 等）里打开这个业务系统的代码，把下面这段话发给它。它会读代码，把接口通过 MCP 上传到这里。</p>
          <el-input :model-value="prompt" type="textarea" :rows="9" readonly aria-label="发给智能体的提示词" />
          <div class="add-api-actions">
            <el-button type="primary" @click="copyPrompt">复制提示词</el-button>
            <span class="add-api-hint">还没连接？点右上角「如何连接我的智能体工具」，按步骤装好 ReachAI 的 MCP。</span>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="导入 OpenAPI / Swagger" name="openapi">
        <div class="add-api-pane">
          <LiquidGlassNotice v-if="sdkOnly" type="info" source="backdrop"
            title="这个项目通过 SDK 自动同步接口"
            description="SDK 项目不需要上传文档。SDK 没覆盖到的接口，可以用「粘贴 curl」「手动填写」或「让智能体扫描」补上。" />
          <template v-else>
            <p class="add-api-lead">支持 OpenAPI 3（.json / .yaml）和 Swagger 2.0（.json，会自动转成 OpenAPI 3）。一般在 <code>/v3/api-docs</code> 或 <code>/v2/api-docs</code> 下载。</p>
            <div class="add-api-file-row">
              <input ref="fileInput" type="file" class="add-api-file-input" accept=".json,.yaml,.yml" aria-label="选择 OpenAPI 文档" @change="pickFile" />
              <el-button @click="fileInput?.click()">选择文件</el-button>
              <span class="add-api-hint">{{ documentName || '未选择，最大 5MB' }}</span>
            </div>
            <el-input v-model="documentText" type="textarea" :rows="6" placeholder="也可以直接把文档内容（JSON 或 YAML）粘贴到这里" aria-label="OpenAPI 文档内容"
              @input="documentName = ''" />
            <p v-if="documentNote" class="add-api-ok">{{ documentNote }}</p>
            <LiquidGlassNotice type="warning" source="backdrop" title="会替换这个项目之前上传的文档"
              description="新文档里没有的、之前从文档导入的接口会变成「来源已缺失」。手动添加和 SDK 同步的接口不受影响。" />
          </template>
        </div>
      </el-tab-pane>

      <el-tab-pane label="粘贴 curl" name="curl">
        <div class="add-api-pane">
          <p class="add-api-lead">在浏览器开发者工具（F12）的「网络」里，右键一个请求，选「复制 → 复制为 cURL（bash）」，粘贴到下面。Token、Cookie 不会保存。</p>
          <el-input v-model="curlText" type="textarea" :rows="6" placeholder="curl 'https://erp.example.com/admin-api/erp/stock/page?pageNo=1' -H 'Authorization: Bearer ...'"
            aria-label="curl 命令" />
          <div class="add-api-actions">
            <el-button type="primary" :disabled="!curlText.trim()" @click="parseCurlText">解析</el-button>
            <span v-if="curlError" class="add-api-error" role="alert">{{ curlError }}</span>
          </div>
          <p v-if="curlNote" class="add-api-ok">{{ curlNote }}</p>
          <p v-if="originWarning" class="add-api-warn">{{ originWarning }}</p>
        </div>
      </el-tab-pane>

      <el-tab-pane label="手动填写" name="form">
        <div class="add-api-pane" />
      </el-tab-pane>
    </LiquidGlassTabs>

    <el-form v-if="tab === 'form' || (tab === 'curl' && curlParsed)" novalidate label-position="top" class="add-api-form" @submit.prevent>
      <div class="add-api-grid">
        <el-form-item label="请求方法" class="add-api-method">
          <el-select v-model="draft.httpMethod" aria-label="请求方法" @change="methodChanged">
            <el-option v-for="method in MANUAL_METHODS" :key="method" :label="method" :value="method" />
          </el-select>
        </el-form-item>
        <el-form-item label="接口路径" class="add-api-path">
          <el-input v-model="draft.path" :placeholder="pathPlaceholder" aria-label="接口路径" @blur="pathChanged" />
          <small>不带域名{{ project.contextPath && project.contextPath !== '/' ? `，也不带项目前缀 ${project.contextPath}` : '' }}；路径参数写成 {id}。</small>
        </el-form-item>
        <el-form-item label="操作性质">
          <el-select v-model="draft.sideEffect" aria-label="操作性质">
            <el-option label="只读查询" value="READ_ONLY" />
            <el-option label="写入（可重复执行）" value="IDEMPOTENT_WRITE" />
            <el-option label="写入" value="WRITE" />
            <el-option label="不可撤销（删除等）" value="IRREVERSIBLE" />
          </el-select>
        </el-form-item>
        <el-form-item label="接口鉴权">
          <el-select v-model="draft.authMode" aria-label="接口鉴权">
            <el-option label="不确定" value="UNKNOWN" />
            <el-option label="不需要" value="NONE" />
            <el-option label="Bearer 令牌（Authorization）" value="BEARER" />
            <el-option label="请求头 API Key" value="API_KEY_HEADER" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="draft.authMode === 'API_KEY_HEADER'" label="API Key 请求头名称">
          <el-input v-model="draft.authHeaderName" placeholder="如 X-Api-Key" aria-label="API Key 请求头名称" />
        </el-form-item>
        <el-form-item label="响应">
          <el-select v-model="draft.responseType" aria-label="响应类型">
            <el-option label="JSON 对象" value="object" />
            <el-option label="JSON 数组" value="array" />
          </el-select>
        </el-form-item>
      </div>

      <div class="add-api-section-head">
        <strong>参数</strong>
        <el-button size="small" @click="addQuery">添加查询参数</el-button>
      </div>
      <p v-if="!draft.parameters.length" class="add-api-hint">没有参数。路径里的 {占位符} 会自动变成路径参数。</p>
      <div v-for="(parameter, index) in draft.parameters" :key="`${parameter.location}-${index}`" class="add-api-param-row">
        <el-tag size="small" effect="plain">{{ parameter.location === 'PATH' ? '路径' : '查询' }}</el-tag>
        <el-input v-model="parameter.name" :disabled="parameter.location === 'PATH'" placeholder="参数名" aria-label="参数名" />
        <el-select v-model="parameter.type" aria-label="参数类型">
          <el-option v-for="type in ['string', 'integer', 'number', 'boolean']" :key="type" :label="type" :value="type" />
        </el-select>
        <el-input v-model="parameter.description" placeholder="说明（可选）" aria-label="参数说明" />
        <el-checkbox v-model="parameter.required" :disabled="parameter.location === 'PATH'">必填</el-checkbox>
        <el-button v-if="parameter.location === 'QUERY'" link type="danger" @click="draft.parameters.splice(index, 1)">删除</el-button>
      </div>

      <template v-if="draft.httpMethod !== 'GET'">
        <div class="add-api-section-head">
          <strong>请求体</strong>
          <el-radio-group v-model="draft.bodyContentType" size="small">
            <el-radio-button value="application/json">JSON</el-radio-button>
            <el-radio-button value="text/plain">纯文本</el-radio-button>
          </el-radio-group>
        </div>
        <el-input v-if="draft.bodyContentType === 'application/json'" v-model="draft.bodyText" type="textarea" :rows="6"
          placeholder='粘贴一个 JSON 示例（如 {"name":"张三","mobile":"138..."}），会自动整理成结构；也可以直接写 JSON Schema。留空表示没有请求体。'
          aria-label="请求体" />
      </template>
    </el-form>

    <template #footer>
      <div class="add-api-footer">
        <span v-if="submitError" class="add-api-error" role="alert">{{ submitError }}</span>
        <el-button :disabled="saving" @click="emit('update:modelValue', false)">取消</el-button>
        <el-button v-if="tab === 'openapi' && !sdkOnly" type="primary" :loading="saving" :disabled="!canWrite || !documentText.trim()"
          @click="importDocument">上传并导入</el-button>
        <el-button v-else-if="tab === 'form' || (tab === 'curl' && curlParsed)" type="primary" :loading="saving" :disabled="!canWrite"
          @click="saveManual">保存 API</el-button>
      </div>
    </template>
  </AppDialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import AppDialog from '@/components/common/AppDialog.vue'
import { LiquidGlassNotice, LiquidGlassTabs } from '@/components/common/liquid-glass'
import { saveManualHttpApi } from '@/api/httpApi'
import { getScanProjectDetail, triggerRescan, updateScanProject } from '@/api/scanProject'
import type { ScanProject, ScanProjectUpsertRequest } from '@/types/scanProject'
import { MAX_OPENAPI_DOCUMENT_CHARS } from '@/utils/openapiDocument'
import { agentScanPrompt, buildManualPayload, defaultSideEffect, emptyManualDraft, MANUAL_METHODS, normalizeApiPath,
  parseCurl, prepareOpenApiDocument, syncPathParameters, type ManualHttpApiDraft } from '../manualHttpApi'

const props = defineProps<{
  modelValue: boolean
  project: Pick<ScanProject, 'id' | 'name' | 'projectCode' | 'projectKind' | 'contextPath' | 'baseUrl'>
  canWrite: boolean
}>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  saved: [result: { apiId?: number; imported?: boolean }]
}>()

const router = useRouter()
const tab = ref<'agent' | 'openapi' | 'curl' | 'form'>('agent')
const draft = reactive<ManualHttpApiDraft>(emptyManualDraft())
const saving = ref(false)
const submitError = ref('')
const curlText = ref('')
const curlError = ref('')
const curlNote = ref('')
const curlParsed = ref(false)
const originWarning = ref('')
const fileInput = ref<HTMLInputElement | null>(null)
const documentName = ref('')
const documentText = ref('')
const documentNote = ref('')

const sdkOnly = computed(() => props.project.projectKind === 'REGISTERED')
const prompt = computed(() => agentScanPrompt(props.project))
const pathPlaceholder = computed(() => '/erp/stock/page')

watch(() => props.modelValue, open => {
  if (!open) return
  Object.assign(draft, emptyManualDraft())
  tab.value = 'agent'; submitError.value = ''; curlText.value = ''; curlError.value = ''; curlNote.value = ''
  curlParsed.value = false; originWarning.value = ''; documentName.value = ''; documentText.value = ''; documentNote.value = ''
})
watch(tab, value => {
  submitError.value = ''
  if (value === 'form' && draft.origin === 'curl' && !curlParsed.value) Object.assign(draft, emptyManualDraft())
})

function openAgentAccess() {
  emit('update:modelValue', false)
  void router.push('/me/ai-connections')
}
async function copyPrompt() {
  try { await navigator.clipboard.writeText(prompt.value); ElMessage.success('已复制，发给你的智能体就行') }
  catch { ElMessage.warning('复制失败，请手动选中文字复制') }
}
function methodChanged() { draft.sideEffect = defaultSideEffect(draft.httpMethod) }
function pathChanged() {
  if (!draft.path.trim()) return
  draft.path = normalizeApiPath(draft.path, props.project.contextPath)
  draft.parameters = syncPathParameters(draft.path, draft.parameters)
}
function addQuery() {
  draft.parameters.push({ name: '', location: 'QUERY', type: 'string', required: false, description: '' })
}
function parseCurlText() {
  curlError.value = ''; curlNote.value = ''; originWarning.value = ''
  try {
    const result = parseCurl(curlText.value, props.project.contextPath)
    Object.assign(draft, result.draft)
    curlParsed.value = true
    curlNote.value = `已解析：${draft.httpMethod} ${draft.path}。请核对下面的内容再保存。${result.droppedSecrets.length
      ? `已丢弃 ${result.droppedSecrets.join('、')}，鉴权请到「接口鉴权」统一配置。` : ''}`
    const base = (props.project.baseUrl || '').replace(/\/+$/, '')
    if (result.origin && base && !base.toLowerCase().startsWith(result.origin.toLowerCase())) {
      originWarning.value = `注意：curl 里的地址是 ${result.origin}，项目配置的后端地址是 ${base}。调用时会用项目地址或「接口鉴权」里配置的服务地址。`
    }
  } catch (error) {
    curlParsed.value = false
    curlError.value = error instanceof Error ? error.message : 'curl 解析失败'
  }
}
async function pickFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (file.size > MAX_OPENAPI_DOCUMENT_CHARS) { ElMessage.warning('文档不能超过 5MB'); return }
  documentText.value = await file.text()
  documentName.value = file.name
  documentNote.value = ''
}

function ownerMessage(error: unknown, fallback: string) {
  const data = (error as { response?: { data?: { message?: unknown } } })?.response?.data
  return typeof data?.message === 'string' && data.message.trim() ? data.message : fallback
}

async function saveManual() {
  if (saving.value || !props.canWrite) return
  submitError.value = ''
  let payload
  try { payload = buildManualPayload(draft, props.project.contextPath) }
  catch (error) { submitError.value = error instanceof Error ? error.message : '请检查填写内容'; return }
  saving.value = true
  try {
    const { data } = await saveManualHttpApi(props.project.id, payload)
    ElMessage.success(data.created ? 'API 已添加，确认后点「接纳」即可使用' : '同一路径的 API 已更新')
    emit('saved', { apiId: data.apiId })
    emit('update:modelValue', false)
  } catch (error) {
    submitError.value = ownerMessage(error, '保存失败，请检查内容后重试')
  } finally { saving.value = false }
}

async function importDocument() {
  if (saving.value || !props.canWrite || sdkOnly.value) return
  submitError.value = ''
  let prepared
  try {
    if (documentText.value.length > MAX_OPENAPI_DOCUMENT_CHARS) throw new Error('文档不能超过 5MB')
    prepared = prepareOpenApiDocument(documentName.value || 'openapi.json', documentText.value)
  } catch (error) { submitError.value = error instanceof Error ? error.message : '文档无法识别'; return }
  documentNote.value = prepared.note
  saving.value = true
  try {
    const { data: project } = await getScanProjectDetail(props.project.id)
    const payload: ScanProjectUpsertRequest = {
      name: project.name, projectCode: project.projectCode, projectKind: project.projectKind,
      environment: project.environment, owner: project.owner || '', visibility: project.visibility,
      baseUrl: project.baseUrl, businessPageUrl: project.businessPageUrl ?? null, contextPath: project.contextPath || '',
      specFile: prepared.specFile,
      specContent: prepared.specContent,
    }
    await updateScanProject(project.id, payload)
    const { data: result } = await triggerRescan(project.id)
    ElMessage.success(`文档已导入${typeof result?.toolCount === 'number' ? `，识别出 ${result.toolCount} 个接口` : ''}。新接口确认后点「接纳」即可使用`)
    emit('saved', { imported: true })
    emit('update:modelValue', false)
  } catch (error) {
    submitError.value = ownerMessage(error, '导入失败：文档已保存时可以到「接入与变更」里重新导入，或检查文档后重试')
  } finally { saving.value = false }
}
</script>

<style scoped>
.add-api-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; padding-right: 28px; }
.add-api-title { display: block; font-size: 18px; font-weight: 600; color: var(--text-primary); }
.add-api-subtitle { display: block; margin-top: 4px; font-size: 13px; color: var(--text-secondary); }
.add-api-agent-link { flex-shrink: 0; }
.add-api-pane { display: flex; flex-direction: column; gap: 12px; padding-top: 4px; }
.add-api-lead { margin: 0; color: var(--text-secondary); line-height: 1.6; }
.add-api-lead code { font-size: 12px; }
.add-api-actions, .add-api-file-row { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.add-api-file-input { display: none; }
.add-api-hint { font-size: 12px; color: var(--text-secondary); }
.add-api-ok { margin: 0; font-size: 13px; color: var(--el-color-success); }
.add-api-warn { margin: 0; font-size: 13px; color: var(--el-color-warning); }
.add-api-error { font-size: 13px; color: var(--el-color-danger); }
.add-api-form { margin-top: 16px; }
.add-api-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 0 16px; }
.add-api-path { grid-column: span 2; }
.add-api-path small { display: block; font-size: 12px; color: var(--text-secondary); line-height: 1.5; }
.add-api-section-head { display: flex; align-items: center; justify-content: space-between; margin: 8px 0; }
.add-api-param-row { display: grid; grid-template-columns: 48px 1fr 120px 1.2fr auto auto; gap: 8px; align-items: center; margin-bottom: 8px; }
.add-api-footer { display: flex; align-items: center; justify-content: flex-end; gap: 12px; flex-wrap: wrap; }
.add-api-footer .add-api-error { margin-right: auto; }
@media (max-width: 720px) {
  .add-api-header { flex-direction: column; }
  .add-api-grid { grid-template-columns: 1fr; }
  .add-api-path { grid-column: auto; }
  .add-api-param-row { grid-template-columns: 1fr 1fr; }
}
</style>
