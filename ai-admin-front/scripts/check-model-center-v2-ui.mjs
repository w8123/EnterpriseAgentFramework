import fs from 'node:fs'
import path from 'node:path'

const root = process.cwd()
const failures = []

const KNOWN_ICON_KEYS = [
  'anthropic',
  'azure-openai',
  'deepseek',
  'gemini',
  'kimi',
  'ollama',
  'openai',
  'qianfan',
  'siliconflow',
  'tencent-hunyuan',
  'tongyi',
  'vllm',
  'volcengine',
]

function read(relPath) {
  return fs.readFileSync(path.join(root, relPath), 'utf8')
}

function exists(relPath) {
  return fs.existsSync(path.join(root, relPath))
}

function mustInclude(source, needle, message) {
  if (!source.includes(needle)) failures.push(message)
}

function mustNotInclude(source, needle, message) {
  if (source.includes(needle)) failures.push(message)
}

function findProviderAsset(key) {
  for (const ext of ['png', 'webp', 'svg']) {
    const rel = path.join('src/assets/model-providers', `${key}.${ext}`)
    if (exists(rel)) return rel
  }
  return null
}

function isLetterPlaceholderSvg(filePath) {
  if (!filePath.endsWith('.svg')) return false
  const source = fs.readFileSync(path.join(root, filePath), 'utf8')
  // Current rejected pattern: colored rect + short letter mark text (OA/DS/GE...).
  const hasRect = /<rect\b/i.test(source)
  const letterText = source.match(/<text\b[^>]*>\s*([A-Z0-9]{1,3})\s*<\/text>/i)
  return Boolean(hasRect && letterText)
}

const types = read('src/types/model.ts')
const api = read('src/api/model.ts')
const listPage = read('src/views/model/ModelInstances.vue')
const detailPage = read('src/views/model/ModelInstanceDetail.vue')
const router = read('src/router/index.ts')
const vite = read('vite.config.ts')
const providerIcon = read('src/views/model/components/ModelProviderIcon.vue')

mustNotInclude(types, 'EndpointType', 'types/model.ts must not define EndpointType')
mustNotInclude(types, "'ERROR'", 'types/model.ts must not keep ERROR status')
mustNotInclude(types, "'STT'", 'types/model.ts must not keep STT model type')
mustNotInclude(types, "'TTS'", 'types/model.ts must not keep TTS model type')
mustNotInclude(types, "'IMAGE'", 'types/model.ts must not keep IMAGE model type')
mustNotInclude(types, 'VIDEO', 'types/model.ts must not keep VIDEO model type')
mustInclude(types, "export type ModelType = 'LLM' | 'EMBEDDING' | 'RERANKER'", 'types/model.ts must define V2 ModelType')
mustInclude(types, "export type ModelTestStatus = 'UNKNOWN' | 'SUCCESS' | 'FAILED'", 'types/model.ts must define ModelTestStatus')

mustInclude(api, "/templates", 'api/model.ts must expose templates')
mustInclude(api, 'from-template', 'api/model.ts must expose from-template')
mustInclude(api, 'test-draft', 'api/model.ts must expose test-draft')
mustInclude(api, '/archive', 'api/model.ts must expose archive')
mustNotInclude(api, 'deleteModelInstance', 'api/model.ts must not export deleteModelInstance')
mustNotInclude(api, ".delete<", 'api/model.ts must not DELETE model instances')

mustNotInclude(listPage, '<el-table', 'ModelInstances.vue must not keep el-table')
mustNotInclude(listPage, 'ViewMode', 'ModelInstances.vue must not keep ViewMode')
mustNotInclude(listPage, 'google.com/s2/favicons', 'ModelInstances.vue must not use Google favicon')
mustNotInclude(listPage, 'workspaceId', 'ModelInstances.vue must not use workspaceId')
mustNotInclude(listPage, 'credential', 'ModelInstances.vue must not use credential field')
mustNotInclude(listPage, 'endpointType', 'ModelInstances.vue must not use endpointType')
mustInclude(listPage, 'model-center-filter-bar', 'ModelInstances.vue must use dedicated filter bar class')
mustInclude(listPage, 'grid-template-rows: auto minmax(0, 1fr)', 'ModelInstances shell must pin toolbar row to auto height')
mustInclude(listPage, 'align-content: start', 'ModelInstances shell must avoid grid stretch blank space')
mustInclude(listPage, 'height: 36px', 'ModelInstances type tabs must use compact pill height')

mustInclude(router, "path: 'model/instances/:id'", 'router must include model instance detail route')
mustInclude(detailPage, 'editing', 'ModelInstanceDetail.vue must support edit mode')
mustInclude(detailPage, 'ARCHIVE_CONFIRM_MESSAGE', 'archive confirmation must live on detail page')
mustNotInclude(listPage, 'archiveModelInstance', 'archive action must not live on list page cards')

mustInclude(vite, 'templates|instances|chat', 'vite proxy must cover templates')
mustNotInclude(vite, 'providers|instances', 'vite proxy must not keep removed providers route')

if (!exists('src/views/model/components/ModelProviderIcon.vue')) {
  failures.push('ModelProviderIcon component must exist')
}
mustNotInclude(providerIcon, 'google.com/s2/favicons', 'provider icon must not use Google favicon')
mustNotInclude(providerIcon, 'https://', 'ModelProviderIcon must not use external https URLs')
mustNotInclude(providerIcon, 'http://', 'ModelProviderIcon must not use external http URLs')
mustInclude(providerIcon, 'assets/model-providers', 'provider icon must use local assets')
mustInclude(providerIcon, 'object-fit: contain', 'provider icon images must use object-fit contain')
mustInclude(providerIcon, '*.{svg,png,webp}', 'provider icon glob must allow png/webp/svg')

for (const key of KNOWN_ICON_KEYS) {
  const asset = findProviderAsset(key)
  if (!asset) {
    failures.push(`known provider iconKey "${key}" is missing a local asset`)
    continue
  }
  if (isLetterPlaceholderSvg(asset)) {
    failures.push(`known provider icon "${asset}" is still a letter-placeholder SVG`)
  }
  // Prefer raster/official assets for known vendors; letter SVG is never acceptable.
  if (asset.endsWith('.svg')) {
    const source = read(asset)
    if (/<text\b/i.test(source) && /[A-Z]{2}/.test(source)) {
      failures.push(`known provider icon "${asset}" still embeds letter mark text`)
    }
  }
}

const picker = read('src/views/model/components/ModelTemplatePicker.vue')
const onboarding = read('src/views/model/components/ModelOnboardingDialog.vue')
const modelUi = read('src/views/model/modelCenterUi.ts')

mustInclude(modelUi, 'export function buildProviderGroups', 'modelCenterUi must group templates by provider')
mustInclude(modelUi, 'export function filterProviderGroups', 'modelCenterUi must filter provider groups')
mustInclude(modelUi, 'export function filterProviderModels', 'modelCenterUi must filter provider models')

mustInclude(picker, "pickerStep === 'provider'", 'ModelTemplatePicker must have provider step')
mustInclude(picker, "pickerStep === 'model'", 'ModelTemplatePicker must have model step')
mustInclude(picker, '选择模型厂商', 'ModelTemplatePicker must show provider-step title')
mustInclude(picker, '其他接入方式', 'ModelTemplatePicker must keep custom entry section')
mustInclude(picker, '自定义 OpenAI 兼容模型', 'ModelTemplatePicker must keep custom OpenAI entry')
mustInclude(picker, '返回选择厂商', 'ModelTemplatePicker must provide back-to-provider action')
mustNotInclude(picker, 'FilterBar', 'ModelTemplatePicker must not keep FilterBar')
mustNotInclude(picker, 'templateId', 'ModelTemplatePicker must not expose templateId to users')
mustNotInclude(picker, '模板目录', 'ModelTemplatePicker must not use 模板目录 copy')
mustNotInclude(picker, 'google.com/s2/favicons', 'ModelTemplatePicker must not use Google favicon')

mustInclude(onboarding, 'width="960px"', 'onboarding dialog width should be compact')
mustInclude(detailPage, 'width="960px"', 'replace dialog width should be compact')
mustNotInclude(detailPage, '也不会保存 templateId', 'replace dialog must not mention templateId')
mustNotInclude(onboarding, '不会在实例上持久化 templateId', 'onboarding dialog must not mention templateId')

const playground = read('src/views/model/ModelPlayground.vue')
const modelApi = read('src/api/model.ts')
const dashboard = read('src/views/dashboard/Dashboard.vue')
const modelStreamHelper = read('src/views/model/modelStream.ts')
const useModelStreamSource = read('src/views/model/useModelStream.ts')
const modelTypes = read('src/types/model.ts')

mustInclude(playground, 'useModelStream', 'ModelPlayground must use structured stream composable')
mustInclude(playground, '思考过程', 'ModelPlayground must render reasoning separately')
mustInclude(playground, '停止生成', 'ModelPlayground must support stop generation')
if (/(['"`])\/model\/chat\/stream\1/.test(playground) || playground.includes("'/model/chat/stream'") || playground.includes('"/model/chat/stream"')) {
  failures.push('ModelPlayground must not call legacy /model/chat/stream')
}
mustNotInclude(modelApi, 'modelChatStream', 'api/model.ts must not keep modelChatStream')
if (/(['"`])\/model\/chat\/stream\1/.test(modelApi) || modelApi.includes("'/model/chat/stream'") || modelApi.includes('"/model/chat/stream"')) {
  failures.push('api/model.ts must not reference legacy /model/chat/stream')
}
mustInclude(modelTypes, 'ModelStreamEvent', 'types/model.ts must define ModelStreamEvent')
mustInclude(modelStreamHelper, 'reduceModelStreamEvent', 'modelStream.ts must provide reducer')
mustInclude(useModelStreamSource, '/model/chat/stream/events', 'useModelStream must call structured events endpoint')

mustInclude(dashboard, '/api/internal-services/health', 'Dashboard must use Control health aggregation')
mustNotInclude(dashboard, '/model/providers', 'Dashboard must not call /model/providers')
mustNotInclude(dashboard, '/ai/actuator/health', 'Dashboard must not directly call knowledge actuator')
mustNotInclude(dashboard, 'checkHttpService', 'Dashboard must not keep direct HTTP health probes')

const modelController = fs.readFileSync(
  path.join(root, '..', 'reachai-model-service/src/main/java/com/enterprise/ai/model/controller/ModelController.java'),
  'utf8',
)
mustInclude(modelController, '/chat/stream/events', 'ModelController must keep structured stream endpoint')
mustNotInclude(modelController, 'Flux<String>', 'ModelController must not keep Flux<String> text stream')
if (modelController.includes('public Flux<String> chatStream')) {
  failures.push('ModelController.chatStream must be deleted')
}

const healthController = fs.readFileSync(
  path.join(root, '..', 'reachai-control-service/src/main/java/com/enterprise/ai/control/internal/InternalServicesHealthController.java'),
  'utf8',
)
mustInclude(healthController, 'ModelHealthClient', 'health aggregation must include model')
mustInclude(healthController, 'KnowledgeHealthClient', 'health aggregation must include knowledge')
mustInclude(healthController, 'services.put("model"', 'health body must expose model')
mustInclude(healthController, 'services.put("knowledge"', 'health body must expose knowledge')

if (exists('src/composables/useSSE.ts')) {
  failures.push('legacy useSSE.ts must be deleted after structured stream migration')
}

const productionFiles = [
  'src/types/model.ts',
  'src/api/model.ts',
  'src/views/model/ModelInstances.vue',
  'src/views/model/ModelInstanceDetail.vue',
  'src/views/model/components/ModelProviderIcon.vue',
  'src/views/model/components/ModelOnboardingDialog.vue',
  'src/views/model/components/ModelTemplatePicker.vue',
]
for (const file of productionFiles) {
  const source = read(file)
  if (source.includes('google.com/s2/favicons')) {
    failures.push(`${file} still contains Google favicon URL`)
  }
}

if (failures.length) {
  console.error(failures.join('\n'))
  process.exit(1)
}

console.log('Model Center V2 UI contract checks passed.')
