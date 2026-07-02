<template>
  <div class="node-specific-panel">
    <el-divider>页面动作</el-divider>
    <el-alert
      title="页面动作只会请求宿主业务页面执行已注册的 actionKey，不会下发任意 JS。"
      type="info"
      :closable="false"
    />
    <el-form label-position="top" class="page-action-form">
      <el-form-item label="项目编码">
        <div class="catalog-picker-row">
          <el-select
            v-model="config.projectCode"
            filterable
            allow-create
            default-first-option
            clearable
            placeholder="选择或输入项目编码"
            style="width: 100%"
            @change="handleProjectCodeChange"
            @visible-change="ensureProjectsLoaded"
          >
            <el-option
              v-for="project in projectStore.projects"
              :key="project.id"
              :label="projectStore.projectLabel(project)"
              :value="project.projectCode || ''"
              :disabled="!project.projectCode"
            />
          </el-select>
          <el-button :loading="catalogLoading" @click="loadCatalog">加载目录</el-button>
        </div>
      </el-form-item>
      <el-form-item label="已注册页面">
        <el-select
          v-model="config.pageKey"
          filterable
          clearable
          placeholder="先选择前端 SDK 上报的页面"
          style="width: 100%"
          @change="selectCatalogPage"
        >
          <el-option
            v-for="item in catalogPages"
            :key="item.pageKey"
            :label="`${item.name || item.pageKey} · ${item.pageKey}`"
            :value="item.pageKey"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="已注册动作">
        <el-select
          v-model="selectedCatalogActionKey"
          filterable
          clearable
          placeholder="选择该页面下的动作"
          style="width: 100%"
          @change="applyCatalogAction"
        >
          <el-option
            v-for="item in filteredCatalogActions"
            :key="`${item.pageKey}:${item.actionKey}`"
            :label="`${item.title || item.actionKey} · ${item.actionKey}`"
            :value="item.actionKey"
          />
        </el-select>
      </el-form-item>
      <el-alert
        v-if="catalogLoaded && !catalogActions.length"
        title="当前项目还没有前端 SDK 上报的页面动作。请先在项目详情复制前端 SDK 配置，在业务前端开发环境启动后自动注册页面动作目录。"
        type="warning"
        :closable="false"
        show-icon
      />
      <div v-if="selectedCatalogAction" class="catalog-action-summary">
        <div class="summary-title">
          <span>{{ selectedCatalogAction.title || selectedCatalogAction.actionKey }}</span>
          <el-tag size="small" effect="plain">{{ selectedCatalogAction.status }}</el-tag>
        </div>
        <div class="summary-line">
          <span>页面：{{ selectedCatalogAction.pageKey }}</span>
          <span v-if="selectedCatalogPage?.routePattern">路由：{{ selectedCatalogPage.routePattern }}</span>
          <span>最近上报：{{ selectedCatalogAction.lastSeenAt || '-' }}</span>
        </div>
        <div class="summary-line">
          <span>入参：{{ schemaFieldNames(selectedCatalogAction.inputSchemaJson).join('、') || '未声明' }}</span>
          <span>输出：{{ schemaFieldNames(selectedCatalogAction.outputSchemaJson).join('、') || '未声明' }}</span>
        </div>
      </div>
      <el-form-item label="动作标识 actionKey">
        <el-input v-model="config.actionKey" placeholder="例如 team.openDetail / order.refreshList" @change="sync" />
      </el-form-item>
      <el-form-item label="展示标题">
        <el-input v-model="config.title" placeholder="例如 打开班组详情" @change="sync" />
      </el-form-item>
      <el-form-item label="执行前确认">
        <el-switch v-model="config.confirm" @change="sync" />
      </el-form-item>
      <el-form-item label="输出别名">
        <el-input v-model="config.outputAlias" placeholder="page_action_result" @change="sync" />
      </el-form-item>
      <el-form-item label="动作参数映射">
        <el-table :data="argRows" border size="small" class="arg-mapping-table" empty-text="选择动作后自动生成参数行">
          <el-table-column prop="name" label="参数" min-width="150">
            <template #default="{ row }">
              <el-input v-model="row.name" placeholder="参数名" @change="syncArgRows" />
            </template>
          </el-table-column>
          <el-table-column prop="type" label="类型" width="96">
            <template #default="{ row }">{{ row.type || '-' }}</template>
          </el-table-column>
          <el-table-column prop="required" label="必填" width="72">
            <template #default="{ row }">
              <el-tag v-if="row.required" size="small" type="danger" effect="plain">是</el-tag>
              <span v-else>-</span>
            </template>
          </el-table-column>
          <el-table-column prop="description" label="说明" min-width="150" show-overflow-tooltip>
            <template #default="{ row }">{{ row.description || '-' }}</template>
          </el-table-column>
          <el-table-column label="取值表达式" min-width="220">
            <template #default="{ row }">
              <el-input
                v-model="row.expression"
                placeholder="例如 lastOutput.teamName"
                @change="syncArgRows"
              />
            </template>
          </el-table-column>
        </el-table>
        <div class="mapping-actions">
          <el-button size="small" @click="addArgRow">添加参数</el-button>
          <el-button size="small" @click="rebuildArgRowsFromCurrentAction">按目录重置</el-button>
        </div>
      </el-form-item>
      <el-form-item label="高级 JSON">
        <el-input
          v-model="argsText"
          type="textarea"
          :rows="4"
          placeholder='例如 { "teamId": "lastOutput.id" }'
          @change="syncArgs"
        />
      </el-form-item>
      <el-alert
        v-if="argsError"
        :title="argsError"
        type="warning"
        :closable="false"
      />
    </el-form>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  listPageActionCatalog,
  listPageRegistry,
  type PageActionRegistryView,
  type PageRegistryView,
} from '@/api/embedOps'
import { useProjectStore } from '@/store/project'
import type { CanvasNodeData, PageActionNodeConfig } from '@/types/studio'

const props = defineProps<{
  data: CanvasNodeData
}>()

const projectStore = useProjectStore()
const argsText = ref('{}')
const argsError = ref('')
const catalogLoading = ref(false)
const catalogPages = ref<PageRegistryView[]>([])
const catalogActions = ref<PageActionRegistryView[]>([])
const selectedCatalogActionKey = ref('')
const selectedCatalogAction = ref<PageActionRegistryView | null>(null)
const argRows = ref<PageActionArgRow[]>([])
const catalogLoaded = ref(false)

interface PageActionArgRow {
  name: string
  type: string
  required: boolean
  description: string
  expression: string
}

const filteredCatalogActions = computed(() => {
  const pageKey = config.value.pageKey?.trim()
  return catalogActions.value.filter((item) => !pageKey || item.pageKey === pageKey)
})

const selectedCatalogPage = computed(() => {
  const pageKey = selectedCatalogAction.value?.pageKey || config.value.pageKey
  return catalogPages.value.find((item) => item.pageKey === pageKey) || null
})

const config = computed<PageActionNodeConfig>(() => {
  props.data.pageActionConfig ||= {
    actionKey: '',
    title: props.data.label || '页面动作',
    confirm: true,
    args: {},
    outputAlias: props.data.outputAlias || 'page_action_result',
    metadata: {},
  }
  props.data.pageActionConfig.outputAlias ||= props.data.outputAlias || 'page_action_result'
  props.data.outputAlias = props.data.pageActionConfig.outputAlias
  return props.data.pageActionConfig
})

watch(
  () => config.value.args,
  (args) => {
    argsText.value = JSON.stringify(args || {}, null, 2)
    rebuildArgRows(undefined, args || {})
  },
  { immediate: true, deep: true },
)

onMounted(async () => {
  await ensureProjectsLoaded(true)
  if (!config.value.projectCode && projectStore.currentProjectCode) {
    config.value.projectCode = projectStore.currentProjectCode
    sync()
  }
  if (config.value.projectCode) {
    await loadCatalog()
  }
})

function sync() {
  config.value.outputAlias = config.value.outputAlias || 'page_action_result'
  props.data.outputAlias = config.value.outputAlias
  props.data.outputs = [{ id: config.value.outputAlias, name: config.value.outputAlias, type: 'object' }]
}

async function ensureProjectsLoaded(visible?: boolean) {
  if (visible === false) return
  if (!projectStore.projects.length && !projectStore.loading) {
    await projectStore.fetchProjects()
  }
}

function handleProjectCodeChange() {
  catalogPages.value = []
  catalogActions.value = []
  selectedCatalogActionKey.value = ''
  selectedCatalogAction.value = null
  catalogLoaded.value = false
  config.value.pageKey = ''
  config.value.actionKey = ''
  sync()
  if (config.value.projectCode) {
    void loadCatalog()
  }
}

async function loadCatalog() {
  const code = config.value.projectCode?.trim()
  if (!code) {
    ElMessage.warning('请先填写项目编码')
    return
  }
  catalogLoading.value = true
  try {
    const [pages, actions] = await Promise.all([
      listPageRegistry({ projectCode: code, status: 'ACTIVE', limit: 200 }),
      listPageActionCatalog({ projectCode: code, status: 'ACTIVE', limit: 500 }),
    ])
    catalogPages.value = pages.data
    catalogActions.value = actions.data
    catalogLoaded.value = true
    if (!pages.data.length && !actions.data.length) {
      ElMessage.info('当前项目还没有前端 SDK 上报的页面动作')
    }
  } catch (error) {
    ElMessage.error((error as Error).message || '加载页面动作目录失败')
  } finally {
    catalogLoading.value = false
  }
}

function selectCatalogPage(pageKey: string) {
  const page = catalogPages.value.find((item) => item.pageKey === pageKey)
  config.value.pageKey = pageKey
  config.value.routePattern = page?.routePattern || config.value.routePattern
  selectedCatalogActionKey.value = ''
  config.value.actionKey = ''
  sync()
}

function applyCatalogAction(actionKey: string) {
  const item = filteredCatalogActions.value.find((candidate) => candidate.actionKey === actionKey)
  if (!item) return
  selectedCatalogAction.value = item
  selectedCatalogActionKey.value = actionKey
  config.value.projectCode = item.projectCode
  config.value.pageKey = item.pageKey
  config.value.routePattern = catalogPages.value.find((page) => page.pageKey === item.pageKey)?.routePattern || config.value.routePattern
  config.value.actionKey = item.actionKey
  config.value.title = item.title || item.actionKey
  config.value.confirm = item.confirmRequired ?? config.value.confirm
  const sampleArgs = parseJsonRecord(item.sampleArgsJson)
  if (sampleArgs) {
    config.value.args = Object.fromEntries(Object.entries(sampleArgs).map(([key, raw]) => [key, String(raw ?? '')]))
    argsText.value = JSON.stringify(config.value.args, null, 2)
  }
  config.value.metadata = {
    ...(config.value.metadata || {}),
    catalogActionId: item.id,
    inputSchemaJson: item.inputSchemaJson,
    outputSchemaJson: item.outputSchemaJson,
  }
  rebuildArgRows(item, config.value.args || {})
  sync()
}

function syncArgRows() {
  const next: Record<string, string> = {}
  argRows.value.forEach((row) => {
    const name = row.name.trim()
    if (name) {
      next[name] = row.expression
    }
  })
  config.value.args = next
  argsText.value = JSON.stringify(next, null, 2)
  argsError.value = ''
  sync()
}

function addArgRow() {
  argRows.value.push({
    name: `arg${argRows.value.length + 1}`,
    type: 'string',
    required: false,
    description: '',
    expression: '',
  })
  syncArgRows()
}

function rebuildArgRowsFromCurrentAction() {
  rebuildArgRows(selectedCatalogAction.value, config.value.args || {})
  syncArgRows()
}

function rebuildArgRows(action: PageActionRegistryView | null | undefined, args: Record<string, unknown>) {
  const actionFromMetadata = action || selectedCatalogAction.value
  const schema = parseJsonRecord(actionFromMetadata?.inputSchemaJson)
  const sampleArgs = parseJsonRecord(actionFromMetadata?.sampleArgsJson) || {}
  const fields = schemaFields(schema)
  const existing = new Map(argRows.value.map((row) => [row.name, row]))
  const keys = new Set<string>([
    ...fields.map((field) => field.name),
    ...Object.keys(sampleArgs),
    ...Object.keys(args || {}),
  ])
  argRows.value = Array.from(keys).map((key) => {
    const field = fields.find((item) => item.name === key)
    const previous = existing.get(key)
    return {
      name: key,
      type: field?.type || previous?.type || '',
      required: field?.required || false,
      description: field?.description || previous?.description || '',
      expression: String((args || {})[key] ?? sampleArgs[key] ?? previous?.expression ?? ''),
    }
  })
}

function schemaFields(schema: Record<string, unknown> | null): PageActionArgRow[] {
  const properties = schema?.properties
  if (!properties || typeof properties !== 'object' || Array.isArray(properties)) return []
  const required = Array.isArray(schema?.required) ? new Set(schema.required.map(String)) : new Set<string>()
  return Object.entries(properties as Record<string, unknown>).map(([name, raw]) => {
    const property = raw && typeof raw === 'object' && !Array.isArray(raw) ? raw as Record<string, unknown> : {}
    return {
      name,
      type: String(property.type || ''),
      required: required.has(name),
      description: String(property.description || property.title || ''),
      expression: '',
    }
  })
}

function parseJsonRecord(value?: string): Record<string, unknown> | null {
  if (!value) return null
  try {
    const parsed = JSON.parse(value)
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed as Record<string, unknown> : null
  } catch {
    return null
  }
}

function schemaFieldNames(value?: string): string[] {
  const schema = parseJsonRecord(value)
  const properties = schema?.properties
  if (!properties || typeof properties !== 'object' || Array.isArray(properties)) return []
  return Object.keys(properties).slice(0, 8)
}

function syncArgs() {
  try {
    const parsed = JSON.parse(argsText.value || '{}')
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
      throw new Error('动作参数必须是 JSON 对象')
    }
    const next: Record<string, string> = {}
    Object.entries(parsed as Record<string, unknown>).forEach(([key, value]) => {
      next[key] = String(value ?? '')
    })
    config.value.args = next
    rebuildArgRows(undefined, next)
    argsError.value = ''
    sync()
  } catch (error) {
    argsError.value = error instanceof Error ? error.message : '动作参数 JSON 格式不正确'
  }
}
</script>

<style scoped>
.page-action-form {
  display: grid;
  gap: 12px;
}

.catalog-picker-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 8px;
}

.arg-mapping-table {
  width: 100%;
}

.mapping-actions {
  display: flex;
  gap: 8px;
  margin-top: 8px;
}

.catalog-action-summary {
  display: grid;
  gap: 6px;
  padding: 10px 12px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
  background: var(--el-fill-color-lighter);
  color: var(--el-text-color-regular);
  font-size: 12px;
}

.summary-title,
.summary-line {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.summary-title {
  justify-content: space-between;
  color: var(--el-text-color-primary);
  font-weight: 600;
}
</style>
