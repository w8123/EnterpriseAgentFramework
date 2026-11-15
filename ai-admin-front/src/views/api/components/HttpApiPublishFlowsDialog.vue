<template>
  <AppDialog :model-value="modelValue" title="发布给员工和助手" width="860px" destroy-on-close
    description="员工的 AI 工具和内嵌助手只能使用业务方法和已发布的流程。这里把已接纳的 API 各包装成一条「调用一次接口」的流程（不经过模型，不消耗 token），并加入员工发布。写操作仍然走审批。"
    @update:model-value="emit('update:modelValue', $event)">
    <div v-if="loading" class="flow-loading">正在读取已接纳的 API…</div>
    <LiquidGlassNotice v-else-if="loadError" type="error" source="backdrop" :title="loadError">
      <template #actions><el-button link type="primary" @click="load">重新读取</el-button></template>
    </LiquidGlassNotice>
    <LiquidGlassNotice v-else-if="!apis.length" type="info" source="backdrop" title="还没有已接纳的 API"
      description="只有已接纳的 API 才能发布。请先在 API 详情里核对并接纳，再回来操作。" />
    <template v-else>
      <el-table ref="tableRef" :data="apis" row-key="id" max-height="340" size="small"
        @selection-change="selected = $event">
        <el-table-column type="selection" width="44" :selectable="() => !running" />
        <el-table-column label="API" min-width="300">
          <template #default="{ row }">
            <el-tag size="small" effect="light" :type="row.httpMethod === 'GET' ? 'success' : 'warning'">{{ row.httpMethod }}</el-tag>
            <span class="flow-route">{{ row.routeTemplate }}</span>
          </template>
        </el-table-column>
        <el-table-column label="给员工和助手看的名称（建议填中文）" min-width="240">
          <template #default="{ row }">
            <el-input v-model="titles[row.id]" size="small" maxlength="60" :disabled="running"
              :placeholder="`${row.httpMethod} ${row.routeTemplate}`" :aria-label="`${row.routeTemplate} 的名称`" />
          </template>
        </el-table-column>
        <el-table-column label="结果" width="200">
          <template #default="{ row }">
            <span v-if="results[row.id]?.error" class="flow-error">{{ results[row.id].error }}</span>
            <span v-else-if="results[row.id]" class="flow-ok">{{ resultLabel(results[row.id]) }}</span>
          </template>
        </el-table-column>
      </el-table>
      <div class="flow-options">
        <el-button size="small" :loading="naming" :disabled="running || !selected.length" @click="suggestNames">
          AI 起中文名（只填空白的）
        </el-button>
        <el-checkbox v-model="publish" :disabled="running">生成后直接发布流程</el-checkbox>
        <span class="flow-option-label">加入员工发布</span>
        <el-select v-model="publicationId" :disabled="running || !publish" clearable placeholder="不加入" style="width: 240px"
          aria-label="加入员工发布">
          <el-option v-for="item in publications" :key="item.id" :label="item.name" :value="item.id" />
        </el-select>
      </div>
      <p class="flow-hint">已默认勾选查询类（GET）接口：只读、可以直接给员工和助手用。写操作也能生成，调用时仍然走审批。</p>
      <p v-if="!publications.length" class="flow-hint">这个项目还没有员工发布。可以先只生成流程，之后在「MCP 开放」里创建员工发布并加入。</p>
      <p v-if="summary" class="flow-summary" role="status">{{ summary }}
        <el-button v-if="addedPublicationId" link type="primary" @click="openPublication">去重新发布，让新增能力生效</el-button>
      </p>
    </template>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">关闭</el-button>
      <el-button type="primary" :loading="running" :disabled="!selected.length" @click="run">
        生成{{ selected.length ? `（${selected.length} 个）` : '' }}
      </el-button>
    </template>
  </AppDialog>
</template>

<script setup lang="ts">
import { nextTick, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import AppDialog from '@/components/common/AppDialog.vue'
import { LiquidGlassNotice } from '@/components/common/liquid-glass'
import { generateHttpApiFlows, listHttpApis, suggestHttpApiFlowNames, type HttpApiFlowResult } from '@/api/httpApi'
import { addMcpPublicationItem, getMcpEmployeeAccess, getMcpPublication, listMcpPublications } from '@/api/mcp'
import { platformSessionUser } from '@/auth/platformSession'
import type { HttpApiSummary } from '@/types/httpApi'
import type { McpPublication } from '@/types/mcp'

const props = defineProps<{ modelValue: boolean; projectId: number; projectCode: string }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()
const router = useRouter()

const apis = ref<HttpApiSummary[]>([])
const selected = ref<HttpApiSummary[]>([])
const titles = reactive<Record<number, string>>({})
const results = reactive<Record<number, HttpApiFlowResult>>({})
const publications = ref<McpPublication[]>([])
const publicationId = ref<number | null>(null)
const addedPublicationId = ref<number | null>(null)
const publish = ref(true)
const loading = ref(false)
const loadError = ref('')
const running = ref(false)
const naming = ref(false)
const tableRef = ref<{ toggleRowSelection: (row: HttpApiSummary, selected?: boolean) => void } | null>(null)
const summary = ref('')

async function load() {
  loading.value = true
  loadError.value = ''
  summary.value = ''
  addedPublicationId.value = null
  for (const key of Object.keys(results)) delete results[Number(key)]
  try {
    const all: HttpApiSummary[] = []
    for (let current = 1; current <= 10; current++) {
      const { data: page } = await listHttpApis({ projectId: props.projectId, sourceStatus: 'ACCEPTED', current, size: 100 })
      all.push(...(page.records || []))
      if (current >= (page.pages || 1)) break
    }
    apis.value = all.filter((api) => api.sourceConfirmed && ['GET', 'POST'].includes(api.httpMethod))
    publications.value = await projectPublications()
    publicationId.value = publications.value[0]?.id ?? null
    // 查询类接口默认勾选：只读，接入后马上就能给员工和助手用
    await nextTick()
    for (const api of apis.value) if (api.httpMethod === 'GET') tableRef.value?.toggleRowSelection(api, true)
  } catch (error) {
    loadError.value = error instanceof Error && error.message ? error.message : '读取失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

/** 员工发布按项目区分：只列出员工使用设置指向本项目的发布。 */
async function projectPublications() {
  try {
    const { data: page } = await listMcpPublications({ limit: 100 })
    const matched: McpPublication[] = []
    for (const publication of page.items || []) {
      if (publication.state === 'ARCHIVED') continue
      try {
        const { data: access } = await getMcpEmployeeAccess(publication.id)
        if (access.projectCode === props.projectCode) matched.push(publication)
      } catch {
        // 没有权限或没有配置员工使用的发布不列出
      }
    }
    return matched
  } catch {
    return []
  }
}

/** 让这个系统助手的模型给勾选里还没填名字的接口起中文名，管理员可以再改。 */
async function suggestNames() {
  const blank = selected.value.filter((api) => !(titles[api.id] || '').trim())
  if (!blank.length) {
    summary.value = '勾选的接口都已经有名字了'
    return
  }
  naming.value = true
  summary.value = ''
  let named = 0
  try {
    for (let start = 0; start < blank.length; start += 50) {
      const { data } = await suggestHttpApiFlowNames({
        projectId: props.projectId,
        projectCode: props.projectCode,
        apiIds: blank.slice(start, start + 50).map((api) => api.id),
      })
      for (const [id, name] of Object.entries(data || {})) {
        if (!(titles[Number(id)] || '').trim()) {
          titles[Number(id)] = name
          named++
        }
      }
    }
    summary.value = `已为 ${named} 个接口起好中文名，可以再改。`
  } catch (error) {
    const failure = error as { response?: { data?: { message?: string } }; message?: string }
    summary.value = `起名失败：${failure?.response?.data?.message || failure?.message || '请稍后再试'}`
  } finally {
    naming.value = false
  }
}

function resultLabel(result: HttpApiFlowResult) {
  if (result.workflowStatus === 'ACTIVE') return result.created ? '已生成并发布' : '已发布'
  return result.created ? '已生成草稿' : '草稿已存在'
}

async function run() {
  if (!selected.value.length || running.value) return
  running.value = true
  summary.value = ''
  addedPublicationId.value = null
  try {
    const chosen = selected.value.map((api) => ({ apiId: api.id, title: (titles[api.id] || '').trim() || undefined }))
    const list: HttpApiFlowResult[] = []
    // 分批提交，避免一次请求处理太久而超时
    for (let start = 0; start < chosen.length; start += 10) {
      const { data: part } = await generateHttpApiFlows({
        projectId: props.projectId,
        projectCode: props.projectCode,
        apis: chosen.slice(start, start + 10),
        publish: publish.value,
        publishedBy: platformSessionUser.value?.displayName || platformSessionUser.value?.username || undefined,
      })
      for (const result of part) results[result.apiId] = result
      list.push(...part)
      summary.value = `正在生成：已处理 ${list.length} / ${chosen.length}`
    }
    const failed = list.filter((result) => result.error).length
    const active = list.filter((result) => result.workflowStatus === 'ACTIVE' && result.workflowId)
    let added = 0
    let addFailed = 0
    if (publish.value && publicationId.value && active.length) {
      const { data: detail } = await getMcpPublication(publicationId.value)
      const existing = new Set(detail.items.filter((item) => item.sourceKind === 'WORKFLOW').map((item) => item.sourceRef))
      for (const result of active) {
        if (existing.has(result.workflowId as string)) continue
        try {
          await addMcpPublicationItem(publicationId.value, { sourceKind: 'WORKFLOW', sourceRef: result.workflowId as string })
          added++
        } catch {
          addFailed++
        }
      }
      if (added) addedPublicationId.value = publicationId.value
    }
    summary.value = `共 ${list.length} 个：成功 ${list.length - failed} 个，失败 ${failed} 个`
      + (added ? `；已把 ${added} 条流程加入员工发布，重新发布后生效。` : '。')
      + (addFailed ? `有 ${addFailed} 条没能加入员工发布，请到发布页手工添加。` : '')
  } catch (error) {
    summary.value = error instanceof Error && error.message ? error.message : '生成失败，请稍后重试'
  } finally {
    running.value = false
  }
}

function openPublication() {
  if (!addedPublicationId.value) return
  emit('update:modelValue', false)
  router.push(`/mcp-hub/publications/${addedPublicationId.value}`)
}

watch(() => props.modelValue, (open) => { if (open) load() }, { immediate: true })
</script>

<style scoped>
.flow-loading { padding: 24px 0; color: var(--el-text-color-secondary); }
.flow-route { margin-left: 8px; font-family: var(--el-font-family-monospace, monospace); font-size: 12px; word-break: break-all; }
.flow-options { display: flex; align-items: center; gap: 12px; margin-top: 14px; flex-wrap: wrap; }
.flow-option-label { margin-left: 12px; color: var(--el-text-color-regular); font-size: 13px; }
.flow-hint { margin: 8px 0 0; color: var(--el-text-color-secondary); font-size: 12px; }
.flow-summary { margin: 12px 0 0; font-size: 13px; }
.flow-error { color: var(--el-color-danger); font-size: 12px; }
.flow-ok { color: var(--el-color-success); font-size: 12px; }
</style>
