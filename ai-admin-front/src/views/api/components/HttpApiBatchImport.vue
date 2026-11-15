<template>
  <div class="batch-import">
    <template v-if="!preview && mode === 'platform'">
      <p class="batch-lead">把已经在 Dify 或 n8n 上跑着的工作流接进来：保存后它就是一个业务 API，智能体、员工的 AI 工具和工作流的工具节点都能直接调用。</p>
      <el-radio-group v-model="platform" size="small" class="batch-source" aria-label="平台">
        <el-radio-button value="DIFY">Dify 应用</el-radio-button>
        <el-radio-button value="N8N">n8n 工作流</el-radio-button>
      </el-radio-group>
      <template v-if="platform === 'DIFY'">
        <label class="batch-field">
          <span>API 服务器地址</span>
          <el-input v-model="platformUrl" placeholder="如 https://api.dify.ai/v1 或 https://dify.company.com/v1" aria-label="Dify API 地址" />
        </label>
        <label class="batch-field">
          <span>应用 API 密钥</span>
          <el-input v-model="platformKey" type="password" show-password autocomplete="new-password" placeholder="app- 开头，在 Dify 应用「访问 API」→「API 密钥」里创建"
            aria-label="Dify 应用 API 密钥" />
        </label>
        <small class="batch-hint">一个密钥对应一个 Dify 应用；要接多个应用就分别导入。支持「工作流」「Chatflow」「聊天助手」「Agent」「文本生成」。</small>
      </template>
      <template v-else>
        <label class="batch-field">
          <span>n8n 地址（可选）</span>
          <el-input v-model="platformUrl" placeholder="如 https://n8n.company.com" aria-label="n8n 地址" />
        </label>
        <label class="batch-field">
          <span>n8n API Key（可选）</span>
          <el-input v-model="platformKey" type="password" show-password autocomplete="new-password" placeholder="n8n「Settings → n8n API」里创建，只用来读取工作流列表，不保存"
            aria-label="n8n API Key" />
        </label>
        <label class="batch-field">
          <span>或者直接粘贴 Webhook 地址</span>
          <el-input v-model="webhookUrls" type="textarea" :rows="4" aria-label="Webhook 地址"
            placeholder="每行一个，如&#10;https://n8n.company.com/webhook/ticket&#10;GET https://n8n.company.com/webhook/report" />
        </label>
        <small class="batch-hint">填地址和 API Key 会列出所有用 Webhook 触发的工作流；不方便给 Key 就直接贴 Webhook 地址（默认 POST，可在前面写方法）。</small>
      </template>
      <small class="batch-hint">由 ReachAI 服务器去访问，所以要填服务器能访问到的地址。</small>
      <div class="batch-row">
        <el-button type="primary" :loading="parsing" :disabled="!canParse" @click="parse">读取</el-button>
        <span v-if="error" class="batch-error" role="alert">{{ error }}</span>
      </div>
    </template>

    <template v-else-if="!preview">
      <p class="batch-lead">一次导入整份接口文档：支持 OpenAPI 3、Swagger 2.0（JSON / YAML）、Postman 集合（v2.x），以及浏览器开发者工具里导出的 HAR 抓包。先预览，再勾选要导入的接口。</p>
      <el-radio-group v-model="sourceMode" size="small" class="batch-source" aria-label="文档来源">
        <el-radio-button value="file">上传文件</el-radio-button>
        <el-radio-button value="url">文档地址</el-radio-button>
        <el-radio-button value="text">粘贴内容</el-radio-button>
      </el-radio-group>

      <div v-if="sourceMode === 'file'" class="batch-row">
        <input ref="fileInput" type="file" class="batch-file-input" :accept="ACCEPT" aria-label="选择接口文档" @change="pickFile" />
        <el-button @click="fileInput?.click()">选择文件</el-button>
        <span class="batch-hint">{{ fileName || '.json / .yaml / .har，最大 5MB' }}</span>
      </div>
      <div v-else-if="sourceMode === 'url'" class="batch-url">
        <el-input v-model="url" placeholder="如 https://erp.example.com/admin-api/v3/api-docs" aria-label="文档地址" />
        <small class="batch-hint">由 ReachAI 服务器去下载，所以要填服务器能访问到的地址。贴 Swagger 页面地址也行，会自动找 /v3/api-docs、/v2/api-docs。</small>
        <el-checkbox v-model="needsLogin">文档需要登录才能打开</el-checkbox>
        <el-input v-if="needsLogin" v-model="authorization" type="password" show-password placeholder="Authorization 请求头的值，如 Bearer xxx（只用这一次，不保存）"
          aria-label="Authorization" />
      </div>
      <el-input v-else v-model="content" type="textarea" :rows="7" placeholder="把文档内容（JSON 或 YAML）粘贴到这里" aria-label="接口文档内容" />

      <div class="batch-row">
        <span class="batch-label">去掉路径前缀</span>
        <el-input v-model="stripPrefix" class="batch-prefix" placeholder="如 /admin-api" aria-label="去掉路径前缀" />
        <small class="batch-hint">项目调用时会自动加上项目前缀{{ target.contextPath && target.contextPath !== '/' ? ` ${target.contextPath}` : '' }}，所以导入的路径要把它去掉。</small>
      </div>

      <div class="batch-row">
        <el-button type="primary" :loading="parsing" :disabled="!canParse" @click="parse">解析文档</el-button>
        <span v-if="error" class="batch-error" role="alert">{{ error }}</span>
      </div>
    </template>

    <template v-else-if="!outcome">
      <div class="batch-summary">
        <div>
          <strong>{{ FORMAT_LABELS[preview.format] }}</strong>
          <span v-if="preview.title"> · {{ preview.title }}</span>
          <span v-if="preview.version">（{{ preview.version }}）</span>
          <span class="batch-hint"> · 共 {{ preview.candidates.length }} 个接口，{{ importableCount }} 个可导入{{ existingCount ? `，${existingCount} 个已存在（导入会更新）` : '' }}{{ preview.skipped ? `，跳过 ${preview.skipped} 个` : '' }}</span>
        </div>
        <el-button link type="primary" @click="reset">{{ mode === 'platform' ? '重新填写' : '换一份文档' }}</el-button>
      </div>
      <LiquidGlassNotice v-if="preview.warnings.length" type="warning" source="backdrop" title="注意" :description="preview.warnings.join('；')" />

      <div class="batch-filters">
        <el-input v-model="filter.keyword" clearable placeholder="搜索路径或名称" aria-label="搜索接口" class="batch-filter-keyword" />
        <el-select v-if="groups.length" v-model="filter.group" clearable placeholder="全部分组" aria-label="按分组筛选">
          <el-option v-for="group in groups" :key="group" :label="group" :value="group" />
        </el-select>
        <el-select v-model="filter.method" clearable placeholder="全部方法" aria-label="按方法筛选" class="batch-filter-method">
          <el-option v-for="method in methods" :key="method" :label="method" :value="method" />
        </el-select>
        <el-select v-if="hosts.length > 1" v-model="filter.host" clearable placeholder="全部来源地址" aria-label="按来源地址筛选">
          <el-option v-for="host in hosts" :key="host" :label="host" :value="host" />
        </el-select>
        <el-checkbox v-if="existingCount" v-model="filter.onlyNew">只看新接口</el-checkbox>
      </div>

      <div class="batch-select-bar">
        <span>已选 <strong>{{ selected.size }}</strong> 个</span>
        <el-button link type="primary" @click="selectVisible(true)">全选当前列表</el-button>
        <el-button link @click="selectVisible(false)">取消当前列表</el-button>
      </div>

      <div class="batch-table" role="table" aria-label="文档里的接口">
        <div v-for="item in visibleRows" :key="item.key" class="batch-table-row" :class="{ 'is-disabled': !!item.unsupported }" role="row">
          <el-checkbox :model-value="selected.has(item.key)" :disabled="!!item.unsupported" :aria-label="`选择 ${item.key}`"
            @update:model-value="toggle(item.key, !!$event)" />
          <span class="batch-method" :class="`is-${item.request.httpMethod.toLowerCase()}`">{{ item.request.httpMethod }}</span>
          <div class="batch-api">
            <code class="batch-path" :title="item.request.path">{{ item.request.path }}</code>
            <span class="batch-name">{{ item.request.summary || '（文档没写名称）' }}</span>
            <span v-if="item.unsupported" class="batch-error">不能导入：{{ item.unsupported }}</span>
            <span v-for="warning in item.warnings" :key="warning" class="batch-warn">{{ warning }}</span>
          </div>
          <span class="batch-group" :title="item.request.businessGroups?.[0] || ''">{{ item.request.businessGroups?.[0] || '—' }}</span>
          <el-select :model-value="settings.sideEffects[item.key] || item.request.sideEffect" size="small" class="batch-effect"
            :disabled="!!item.unsupported" :aria-label="`${item.key} 的操作性质`" @update:model-value="settings.sideEffects[item.key] = $event">
            <el-option v-for="(label, value) in SIDE_EFFECT_LABELS" :key="value" :label="label" :value="value" />
          </el-select>
          <span class="batch-tags">
            <el-tag v-if="item.existing" size="small" type="info" effect="plain">已存在</el-tag>
            <el-tag v-else-if="!item.unsupported" size="small" type="success" effect="plain">新</el-tag>
            <el-tag v-if="item.deprecated" size="small" type="warning" effect="plain">已废弃</el-tag>
          </span>
        </div>
        <p v-if="!visibleRows.length" class="batch-hint batch-empty">没有符合条件的接口</p>
        <p v-if="hiddenRows" class="batch-hint batch-empty">还有 {{ hiddenRows }} 个没显示，请用上面的搜索或筛选缩小范围</p>
      </div>

      <section class="batch-settings" aria-label="统一设置">
        <strong>统一设置</strong>
        <div class="batch-settings-grid">
          <label class="batch-field">
            <span>所属系统</span>
            <el-select v-if="targets.length > 1" :model-value="targetId" aria-label="所属系统" @update:model-value="changeTarget">
              <el-option v-for="item in targets" :key="item.id" :label="item.name" :value="item.id" />
            </el-select>
            <span v-else class="batch-static">{{ target.name }}</span>
          </label>
          <label class="batch-field">
            <span>接口鉴权</span>
            <el-select v-model="settings.authMode" aria-label="统一接口鉴权">
              <el-option label="按文档" value="DOCUMENT" />
              <el-option label="不需要" value="NONE" />
              <el-option label="Bearer 令牌（Authorization）" value="BEARER" />
              <el-option label="请求头 API Key" value="API_KEY_HEADER" />
              <el-option label="不确定" value="UNKNOWN" />
            </el-select>
          </label>
          <label v-if="settings.authMode === 'API_KEY_HEADER'" class="batch-field">
            <span>API Key 请求头</span>
            <el-input v-model="settings.authHeaderName" placeholder="如 X-Api-Key" aria-label="API Key 请求头名称" />
          </label>
          <label class="batch-field">
            <span>业务分组</span>
            <el-select v-model="settings.groupMode" aria-label="业务分组方式">
              <el-option label="按文档里的标签 / 目录" value="DOCUMENT" />
              <el-option label="全部放进一个分组" value="CUSTOM" />
              <el-option label="不分组" value="NONE" />
            </el-select>
          </label>
          <label v-if="settings.groupMode === 'CUSTOM'" class="batch-field">
            <span>分组名称</span>
            <el-input v-model="settings.groupName" maxlength="64" placeholder="如 ERP 库存" aria-label="分组名称" />
          </label>
        </div>
        <el-checkbox v-if="preview.format === 'DIFY'" v-model="saveDifyKey" :disabled="!canSaveDifyKey">
          保存 API 密钥并配置好调用（把密钥加密存成本项目的凭据，给这些接口配上 Dify 地址；需要同时勾选「导入后直接接纳」）
        </el-checkbox>
        <small v-if="preview.format === 'DIFY' && !canSaveDifyKey" class="batch-hint">你没有这个项目的凭据管理权限，导入后请让有权限的同事在接口详情里配置凭据。</small>
        <el-checkbox v-model="settings.acceptReadOnly" :disabled="settings.acceptAfterImport">只读查询接口导入后直接接纳（查询不改数据，接进来助手就能用）</el-checkbox>
        <el-checkbox v-model="settings.acceptAfterImport">所有接口导入后直接接纳（包括写入类；不勾选时写入类是「待接纳」，逐个核对后再接纳）</el-checkbox>
        <p v-if="mode !== 'platform'" class="batch-hint">令牌、密码这些取值不会导入；调用时用的服务地址和令牌，导入后在「接口鉴权」里统一配置一次就行。</p>
      </section>

      <div class="batch-row batch-actions">
        <span v-if="error" class="batch-error" role="alert">{{ error }}</span>
        <span v-if="progress" class="batch-hint">{{ progress.stage === 'saving' ? '正在导入' : '正在接纳' }} {{ progress.done }}/{{ progress.total }}</span>
        <el-button type="primary" :loading="importing" :disabled="!canWrite || !selected.size" @click="runImport">导入选中的 {{ selected.size }} 个接口</el-button>
      </div>
    </template>

    <template v-else>
      <LiquidGlassNotice :type="outcome.failures.length ? 'warning' : 'success'" source="backdrop"
        :title="`已导入 ${outcome.saved} 个接口到「${target.name}」`"
        :description="resultText" />
      <LiquidGlassNotice v-if="connectionText" :type="connectionError ? 'warning' : 'success'" source="backdrop" :title="connectionText" />
      <div v-if="outcome.failures.length" class="batch-failures">
        <strong>没成功的 {{ outcome.failures.length }} 个</strong>
        <ul>
          <li v-for="failure in outcome.failures.slice(0, 50)" :key="failure.key"><code>{{ failure.key }}</code> {{ failure.message }}</li>
        </ul>
      </div>
      <div class="batch-row batch-actions">
        <el-button @click="reset">{{ mode === 'platform' ? '再接一个' : '再导入一份' }}</el-button>
        <el-button v-if="canConfigureAuth" @click="emit('configure-auth', targetId)">去配置接口鉴权</el-button>
        <el-button type="primary" @click="emit('done', { imported: true, projectId: targetId })">完成</el-button>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { LiquidGlassNotice } from '@/components/common/liquid-glass'
import { previewHttpApiDocument, previewWorkflowPlatform, type HttpApiImportPreview } from '@/api/httpApi'
import { MAX_OPENAPI_DOCUMENT_CHARS } from '@/utils/openapiDocument'
import { configureDifyConnections, defaultBatchSettings, defaultSelection, filterCandidates, FORMAT_LABELS, originOf, runBatchImport, SIDE_EFFECT_LABELS,
  validateSettings, type BatchImportOutcome, type BatchImportProgress } from '../httpApiBatchImport'

interface ImportTarget { id: number; name: string; contextPath?: string | null; projectCode?: string | null; canManageCredential?: boolean }

const props = withDefaults(defineProps<{
  project: ImportTarget
  /** 当前用户能写入的项目；多于一个时可以切换「所属系统」 */
  targets?: ImportTarget[]
  canWrite: boolean
  canConfigureAuth?: boolean
  /** document：整份接口文档；platform：Dify 应用 / n8n Webhook 工作流 */
  mode?: 'document' | 'platform'
}>(), { targets: () => [], canConfigureAuth: false, mode: 'document' })
const emit = defineEmits<{
  done: [result: { imported: boolean; projectId: number }]
  'configure-auth': [projectId: number]
}>()

const ACCEPT = '.json,.yaml,.yml,.har'
const MAX_ROWS = 300
const sourceMode = ref<'file' | 'url' | 'text'>('file')
const platform = ref<'DIFY' | 'N8N'>('DIFY')
const platformUrl = ref('')
const platformKey = ref('')
const webhookUrls = ref('')
const saveDifyKey = ref(true)
const connectionText = ref('')
const connectionError = ref(false)
/** Dify 的密钥要等导入后存成凭据，预览后先留在内存里，导入或重置后清掉。 */
let difyKey = ''
const fileInput = ref<HTMLInputElement | null>(null)
const fileName = ref('')
const content = ref('')
const url = ref('')
const needsLogin = ref(false)
const authorization = ref('')
const targetId = ref(props.project.id)
const stripPrefix = ref(props.project.contextPath && props.project.contextPath !== '/' ? props.project.contextPath : '')
const parsing = ref(false)
const importing = ref(false)
const error = ref('')
const preview = ref<HttpApiImportPreview | null>(null)
const selected = ref(new Set<string>())
const settings = reactive(defaultBatchSettings())
const filter = reactive({ keyword: '', group: '', method: '', host: '', onlyNew: false })
const progress = ref<BatchImportProgress | null>(null)
const outcome = ref<BatchImportOutcome | null>(null)

const targets = computed(() => props.targets.some(item => item.id === props.project.id) ? props.targets : [props.project, ...props.targets])
const target = computed(() => targets.value.find(item => item.id === targetId.value) || props.project)
const canParse = computed(() => {
  if (!props.canWrite || parsing.value) return false
  if (props.mode === 'platform') {
    return platform.value === 'DIFY' ? !!platformUrl.value.trim() && !!platformKey.value.trim()
      : (!!platformUrl.value.trim() && !!platformKey.value.trim()) || !!webhookUrls.value.trim()
  }
  return sourceMode.value === 'url' ? !!url.value.trim() : !!content.value.trim()
})
const canSaveDifyKey = computed(() => !!target.value.projectCode && (target.value.canManageCredential ?? false))
const candidates = computed(() => preview.value?.candidates || [])
const importableCount = computed(() => candidates.value.filter(item => !item.unsupported).length)
const existingCount = computed(() => candidates.value.filter(item => item.existing).length)
const groups = computed(() => Array.from(new Set(candidates.value.map(item => item.request.businessGroups?.[0]).filter((g): g is string => !!g))))
const methods = computed(() => Array.from(new Set(candidates.value.map(item => item.request.httpMethod))))
const hosts = computed(() => Array.from(new Set(candidates.value.map(item => item.host).filter((h): h is string => !!h))))
const filtered = computed(() => filterCandidates(candidates.value, filter))
const visibleRows = computed(() => filtered.value.slice(0, MAX_ROWS))
const hiddenRows = computed(() => Math.max(0, filtered.value.length - MAX_ROWS))
const resultText = computed(() => {
  const value = outcome.value
  if (!value) return ''
  const parts = [`新增 ${value.created} 个`, `更新 ${value.updated} 个`]
  if (value.accepted) parts.push(`接纳 ${value.accepted} 个`)
  else parts.push('都在「待接纳」，核对契约后点「接纳」即可使用')
  if (preview.value?.candidates.some(item => item.request.summary)) parts.push('文档里的接口说明已写进还没有语义文档的接口')
  return parts.join('，') + '。'
})

watch(sourceMode, () => { error.value = '' })

async function pickFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (file.size > MAX_OPENAPI_DOCUMENT_CHARS) { error.value = '文档不能超过 5MB'; return }
  content.value = await file.text()
  fileName.value = file.name
  error.value = ''
}

function message(cause: unknown, fallback: string) {
  const data = (cause as { response?: { data?: { message?: unknown } } })?.response?.data
  return typeof data?.message === 'string' && data.message.trim() ? data.message : fallback
}

async function parse() {
  if (!canParse.value) return
  error.value = ''
  parsing.value = true
  try {
    let data: HttpApiImportPreview
    if (props.mode === 'platform') {
      const key = platformKey.value.trim()
      ;({ data } = await previewWorkflowPlatform(targetId.value, {
        platform: platform.value,
        baseUrl: platformUrl.value.trim() || undefined,
        apiKey: key || undefined,
        webhookUrls: platform.value === 'N8N' ? webhookUrls.value.trim() || undefined : undefined,
      }))
      difyKey = platform.value === 'DIFY' ? key : ''
      platformKey.value = ''
    } else {
      const body = sourceMode.value === 'url'
        ? { url: url.value.trim(), ...(needsLogin.value && authorization.value.trim() ? { authorization: authorization.value.trim() } : {}) }
        : { content: content.value, fileName: fileName.value || undefined }
      ;({ data } = await previewHttpApiDocument(targetId.value, { ...body, stripPrefix: stripPrefix.value.trim() }))
    }
    if (data.format === 'DIFY') {
      settings.authMode = 'DOCUMENT'
      settings.acceptAfterImport = true
      saveDifyKey.value = canSaveDifyKey.value
    }
    preview.value = data
    selected.value = new Set(defaultSelection(data.candidates))
    Object.assign(filter, { keyword: '', group: '', method: '', host: '', onlyNew: false })
    settings.sideEffects = {}
    authorization.value = ''
  } catch (cause) {
    error.value = message(cause, '解析失败，请检查文档后重试')
  } finally { parsing.value = false }
}

async function changeTarget(id: number) {
  targetId.value = id
  const next = targets.value.find(item => item.id === id)
  stripPrefix.value = next?.contextPath && next.contextPath !== '/' ? next.contextPath : ''
  if (preview.value) {
    // 重新解析，刷新「已存在」标记；URL 来源时 content 为空，会重新下载。
    if (props.mode === 'platform') {
      // 平台来源的密钥读完就清掉了，换系统后请重新读取。
      if (platform.value === 'DIFY' && difyKey) platformKey.value = difyKey
      if (!canParse.value) { reset(); return }
    } else if (preview.value.fetchedFrom && !content.value) { sourceMode.value = 'url'; url.value = preview.value.fetchedFrom }
    await parse()
  }
}

function toggle(key: string, on: boolean) {
  const next = new Set(selected.value)
  if (on) next.add(key); else next.delete(key)
  selected.value = next
}

function selectVisible(on: boolean) {
  const next = new Set(selected.value)
  for (const item of filtered.value) {
    if (item.unsupported) continue
    if (on) next.add(item.key); else next.delete(item.key)
  }
  selected.value = next
}

async function runImport() {
  if (!preview.value || importing.value || !props.canWrite) return
  const invalid = validateSettings(settings)
  if (invalid) { error.value = invalid; return }
  const chosen = preview.value.candidates.filter(item => selected.value.has(item.key) && !item.unsupported)
  if (!chosen.length) return
  error.value = ''
  importing.value = true
  progress.value = { done: 0, total: chosen.length, stage: 'saving' }
  try {
    const result = await runBatchImport(targetId.value, chosen, settings, originOf(preview.value.format), value => { progress.value = value })
    connectionText.value = ''
    connectionError.value = false
    if (preview.value.format === 'DIFY' && saveDifyKey.value && canSaveDifyKey.value && difyKey) {
      const apiIds = result.savedApis.filter(item => item.accepted).map(item => item.apiId)
      if (!apiIds.length) {
        connectionError.value = true
        connectionText.value = '接口还没接纳，没法配置调用；接纳后到接口详情里选凭据'
      } else {
        const fetched = preview.value.fetchedFrom || ''
        const connection = await configureDifyConnections({ projectId: targetId.value, projectCode: target.value.projectCode || '',
          title: preview.value.title, apiKey: difyKey, origin: fetched ? new URL(fetched).origin : '', apiIds })
        connectionError.value = !!connection.error
        connectionText.value = connection.error || `已保存 API 密钥，${connection.configured} 个接口配好了 Dify 地址，可以直接调用`
      }
    }
    difyKey = ''
    outcome.value = result
  } catch (cause) {
    error.value = message(cause, '导入失败，请稍后重试')
  } finally {
    importing.value = false
    progress.value = null
  }
}

function reset() {
  difyKey = ''
  connectionText.value = ''
  preview.value = null
  outcome.value = null
  error.value = ''
  selected.value = new Set()
}

defineExpose({ reset })
</script>

<style scoped>
.batch-import { display: flex; flex-direction: column; gap: 12px; padding-top: 4px; }
.batch-lead { margin: 0; color: var(--text-secondary); line-height: 1.6; }
.batch-source { align-self: flex-start; }
.batch-row { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.batch-url { display: flex; flex-direction: column; gap: 8px; }
.batch-file-input { display: none; }
.batch-label { font-size: 13px; color: var(--text-primary); }
.batch-prefix { width: 200px; }
.batch-hint { font-size: 12px; color: var(--text-secondary); line-height: 1.5; }
.batch-error { font-size: 12px; color: var(--el-color-danger); }
.batch-warn { font-size: 12px; color: var(--el-color-warning); }
.batch-summary { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.batch-filters { display: flex; gap: 8px; flex-wrap: wrap; }
.batch-filters .el-select { width: 160px; }
.batch-filter-keyword { width: 220px; }
.batch-filters .batch-filter-method { width: 120px; }
.batch-select-bar { display: flex; align-items: center; gap: 12px; font-size: 13px; }
.batch-table { max-height: 340px; overflow-y: auto; border: 1px solid var(--el-border-color-lighter); border-radius: 10px; }
.batch-table-row { display: grid; grid-template-columns: 24px 64px minmax(0, 1fr) 120px 136px 72px; gap: 10px; align-items: start;
  padding: 8px 12px; border-bottom: 1px solid var(--el-border-color-lighter); }
.batch-table-row:last-child { border-bottom: none; }
.batch-table-row.is-disabled { opacity: 0.65; }
.batch-method { font-size: 12px; font-weight: 600; font-family: var(--el-font-family-mono, monospace); padding-top: 4px; }
.batch-method.is-get { color: var(--el-color-success); }
.batch-method.is-post { color: var(--el-color-primary); }
.batch-method.is-put, .batch-method.is-patch { color: var(--el-color-warning); }
.batch-method.is-delete { color: var(--el-color-danger); }
.batch-api { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.batch-path { align-self: flex-start; max-width: 100%; font-size: 12px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.batch-name { font-size: 13px; color: var(--text-primary); }
.batch-group { font-size: 12px; color: var(--text-secondary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; padding-top: 4px; }
.batch-tags { display: flex; flex-direction: column; gap: 4px; align-items: flex-start; }
.batch-empty { padding: 12px; margin: 0; }
.batch-settings { display: flex; flex-direction: column; gap: 10px; padding: 12px; border-radius: 10px; background: var(--el-fill-color-light); }
.batch-settings p { margin: 0; }
.batch-settings-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px 16px; }
.batch-field { display: flex; flex-direction: column; gap: 4px; font-size: 12px; color: var(--text-secondary); }
.batch-static { font-size: 14px; color: var(--text-primary); line-height: 32px; }
.batch-actions { justify-content: flex-end; }
.batch-actions .batch-error { margin-right: auto; }
.batch-failures { font-size: 13px; max-height: 200px; overflow-y: auto; }
.batch-failures ul { margin: 6px 0 0; padding-left: 18px; }
.batch-failures li { margin-bottom: 4px; color: var(--text-secondary); }
@media (max-width: 720px) {
  .batch-table-row { grid-template-columns: 24px 56px minmax(0, 1fr); }
  .batch-group, .batch-effect, .batch-tags { grid-column: 3; }
  .batch-settings-grid { grid-template-columns: 1fr; }
}
</style>
