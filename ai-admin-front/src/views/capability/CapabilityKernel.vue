<template>
  <div class="capability-kernel page-container">
    <div class="page-header">
      <div>
        <h2>能力内核</h2>
        <p>Capability / Composition / Tool / Interaction</p>
      </div>
      <div class="header-actions">
        <el-button :icon="Refresh" :loading="loading" @click="loadModules">刷新</el-button>
        <el-button type="primary" :icon="Plus" @click="openModuleDialog()">新建模块</el-button>
      </div>
    </div>

    <section class="kernel-layout">
      <aside class="module-pane">
        <el-table
          v-loading="loading"
          :data="modules"
          row-key="code"
          highlight-current-row
          class="module-table"
          @current-change="selectModule"
        >
          <el-table-column label="能力模块" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">
              <div class="module-cell">
                <strong>{{ row.name || row.code }}</strong>
                <span>{{ row.code }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="86" align="center">
            <template #default="{ row }">
              <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
                {{ row.enabled ? '启用' : '停用' }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
      </aside>

      <main class="asset-pane">
        <el-empty v-if="!selectedModule" description="请选择能力模块" />
        <template v-else>
          <div class="module-summary">
            <div>
              <h3>{{ selectedModule.name }}</h3>
              <span>{{ selectedModule.code }} / {{ selectedModule.version || '1.0.0' }}</span>
            </div>
            <div class="summary-actions">
              <el-switch
                v-model="selectedModule.enabled"
                active-text="启用"
                inactive-text="停用"
                @change="saveSelectedModule"
              />
              <el-button :icon="Edit" @click="openModuleDialog(selectedModule)">编辑</el-button>
            </div>
          </div>

          <el-tabs v-model="activeTab" class="asset-tabs">
            <el-tab-pane label="工具" name="tools">
              <div class="tab-toolbar">
                <el-button type="primary" :icon="Plus" @click="openToolDialog()">新建工具</el-button>
              </div>
              <el-table :data="tools" v-loading="assetLoading" row-key="qualifiedName" stripe>
                <el-table-column prop="name" label="工具" min-width="160" show-overflow-tooltip />
                <el-table-column prop="qualifiedName" label="Qualified Name" min-width="220" show-overflow-tooltip />
                <el-table-column prop="executorType" label="执行器" width="110" />
                <el-table-column label="可见" width="90" align="center">
                  <template #default="{ row }">
                    <el-tag :type="row.agentVisible ? 'success' : 'info'" size="small">
                      {{ row.agentVisible ? 'Agent' : '隐藏' }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="170" fixed="right">
                  <template #default="{ row }">
                    <el-button link type="primary" :icon="Edit" @click="openToolDialog(row)">编辑</el-button>
                    <el-button link type="primary" :icon="VideoPlay" @click="openRunDialog('tool', row)">测试</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </el-tab-pane>

            <el-tab-pane label="组合" name="compositions">
              <div class="tab-toolbar">
                <el-button type="primary" :icon="Plus" @click="openCompositionDialog()">新建组合</el-button>
              </div>
              <el-table :data="compositions" v-loading="assetLoading" row-key="qualifiedName" stripe>
                <el-table-column prop="name" label="组合" min-width="160" show-overflow-tooltip />
                <el-table-column prop="qualifiedName" label="Qualified Name" min-width="220" show-overflow-tooltip />
                <el-table-column label="可见" width="90" align="center">
                  <template #default="{ row }">
                    <el-tag :type="row.agentVisible ? 'success' : 'info'" size="small">
                      {{ row.agentVisible ? 'Agent' : '隐藏' }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="170" fixed="right">
                  <template #default="{ row }">
                    <el-button link type="primary" :icon="Edit" @click="openCompositionDialog(row)">编辑</el-button>
                    <el-button link type="primary" :icon="VideoPlay" @click="openRunDialog('composition', row)">测试</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </el-tab-pane>

            <el-tab-pane label="交互" name="interactions">
              <div class="tab-toolbar">
                <el-button type="primary" :icon="Plus" @click="openInteractionDialog()">新建交互</el-button>
              </div>
              <el-table :data="interactions" v-loading="assetLoading" row-key="qualifiedName" stripe>
                <el-table-column prop="name" label="交互定义" min-width="160" show-overflow-tooltip />
                <el-table-column prop="qualifiedName" label="Qualified Name" min-width="220" show-overflow-tooltip />
                <el-table-column prop="interactionType" label="类型" width="150" />
                <el-table-column label="可见" width="90" align="center">
                  <template #default="{ row }">
                    <el-tag :type="row.agentVisible ? 'success' : 'info'" size="small">
                      {{ row.agentVisible ? 'Agent' : '隐藏' }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="100" fixed="right">
                  <template #default="{ row }">
                    <el-button link type="primary" :icon="Edit" @click="openInteractionDialog(row)">编辑</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </el-tab-pane>
          </el-tabs>
        </template>
      </main>
    </section>

    <el-dialog v-model="moduleDialogVisible" title="能力模块" width="560px">
      <el-form label-width="110px">
        <el-form-item label="模块编码" required>
          <el-input v-model="moduleForm.code" :disabled="Boolean(moduleEditingCode)" />
        </el-form-item>
        <el-form-item label="模块名称" required>
          <el-input v-model="moduleForm.name" />
        </el-form-item>
        <el-form-item label="版本">
          <el-input v-model="moduleForm.version" />
        </el-form-item>
        <el-form-item label="来源">
          <el-select v-model="moduleForm.sourceType" style="width: 100%">
            <el-option label="BUILTIN" value="BUILTIN" />
            <el-option label="SDK" value="SDK" />
            <el-option label="PLUGIN" value="PLUGIN" />
          </el-select>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="moduleForm.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="moduleDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitModule">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="toolDialogVisible" title="工具资产" width="720px">
      <el-form label-width="130px">
        <el-form-item label="工具编码" required>
          <el-input v-model="toolForm.toolCode" :disabled="Boolean(toolEditingCode)" />
        </el-form-item>
        <el-form-item label="工具名称" required>
          <el-input v-model="toolForm.name" />
        </el-form-item>
        <el-form-item label="执行器类型">
          <el-select v-model="toolForm.executorType" style="width: 100%">
            <el-option label="BEAN" value="BEAN" />
            <el-option label="ECHO" value="ECHO" />
            <el-option label="HTTP" value="HTTP" />
          </el-select>
        </el-form-item>
        <el-form-item label="执行器引用">
          <el-input v-model="toolForm.executorRef" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="toolForm.description" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="开关">
          <el-switch v-model="toolForm.enabled" active-text="启用" inactive-text="停用" />
          <el-switch v-model="toolForm.agentVisible" class="inline-switch" active-text="Agent 可见" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="toolDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitTool">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="compositionDialogVisible" title="组合资产" width="860px">
      <el-form label-width="130px">
        <el-form-item label="组合编码" required>
          <el-input v-model="compositionForm.compositionCode" :disabled="Boolean(compositionEditingCode)" />
        </el-form-item>
        <el-form-item label="组合名称" required>
          <el-input v-model="compositionForm.name" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="compositionForm.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="GraphSpec JSON">
          <el-input v-model="compositionForm.graphSpecJson" type="textarea" :rows="12" />
        </el-form-item>
        <el-form-item label="开关">
          <el-switch v-model="compositionForm.enabled" active-text="启用" inactive-text="停用" />
          <el-switch v-model="compositionForm.agentVisible" class="inline-switch" active-text="Agent 可见" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="compositionDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitComposition">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="interactionDialogVisible" title="交互定义" width="860px">
      <el-form label-width="130px">
        <el-form-item label="交互编码" required>
          <el-input v-model="interactionForm.interactionCode" :disabled="Boolean(interactionEditingCode)" />
        </el-form-item>
        <el-form-item label="交互名称" required>
          <el-input v-model="interactionForm.name" />
        </el-form-item>
        <el-form-item label="交互类型">
          <el-select v-model="interactionForm.interactionType" style="width: 100%">
            <el-option label="采集输入 COLLECT_INPUT" value="COLLECT_INPUT" />
            <el-option label="展示输出 PRESENT_OUTPUT" value="PRESENT_OUTPUT" />
            <el-option label="用户选择 USER_CHOICE" value="USER_CHOICE" />
            <el-option label="确认操作 CONFIRM_ACTION" value="CONFIRM_ACTION" />
          </el-select>
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="interactionForm.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="InteractionSpec">
          <el-input v-model="interactionForm.specJson" type="textarea" :rows="12" />
        </el-form-item>
        <el-form-item label="开关">
          <el-switch v-model="interactionForm.enabled" active-text="启用" inactive-text="停用" />
          <el-switch v-model="interactionForm.agentVisible" class="inline-switch" active-text="Agent 可见" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="interactionDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitInteraction">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="runDialogVisible" :title="runTitle" width="760px">
      <el-form label-width="120px">
        <el-form-item label="Qualified Name">
          <el-input :model-value="runTarget?.qualifiedName" readonly />
        </el-form-item>
        <el-form-item label="入参 JSON">
          <el-input v-model="runParamsJson" type="textarea" :rows="7" />
        </el-form-item>
        <el-form-item v-if="currentUiRequest" label="交互请求">
          <InteractionRenderer
            :ui-request="currentUiRequest"
            @submit="resumeCurrentInteraction"
            @cancel="currentUiRequest = null"
          />
        </el-form-item>
        <el-form-item v-if="runResult" label="执行结果">
          <pre class="json-result">{{ prettyJson(runResult) }}</pre>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="runDialogVisible = false">关闭</el-button>
        <el-button type="primary" :icon="VideoPlay" :loading="running" @click="executeRun">执行</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Edit, Plus, Refresh, VideoPlay } from '@element-plus/icons-vue'
import InteractionRenderer from '@/components/interaction/InteractionRenderer.vue'
import type { UiRequestPayload } from '@/types/interaction'
import {
  executeComposition,
  executeToolAsset,
  listCapabilityModules,
  listModuleCompositions,
  listModuleInteractions,
  listModuleTools,
  resumeInteraction,
  saveCapabilityModule,
  saveModuleComposition,
  saveModuleInteraction,
  saveModuleTool,
  type CapabilityModule,
  type CompositionDefinition,
  type InteractionDefinition,
  type RuntimeExecuteResult,
  type ToolAsset,
} from '@/api/capabilityKernel'

type RunKind = 'tool' | 'composition'
type AssetTab = 'tools' | 'compositions' | 'interactions'

const route = useRoute()
const loading = ref(false)
const assetLoading = ref(false)
const saving = ref(false)
const running = ref(false)
const modules = ref<CapabilityModule[]>([])
const tools = ref<ToolAsset[]>([])
const compositions = ref<CompositionDefinition[]>([])
const interactions = ref<InteractionDefinition[]>([])
const selectedModule = ref<CapabilityModule | null>(null)
const activeTab = ref<AssetTab>('tools')

const moduleDialogVisible = ref(false)
const toolDialogVisible = ref(false)
const compositionDialogVisible = ref(false)
const interactionDialogVisible = ref(false)
const runDialogVisible = ref(false)
const moduleEditingCode = ref('')
const toolEditingCode = ref('')
const compositionEditingCode = ref('')
const interactionEditingCode = ref('')
const runKind = ref<RunKind>('tool')
const runTarget = ref<ToolAsset | CompositionDefinition | null>(null)
const runParamsJson = ref('{\n  "message": "hello"\n}')
const runResult = ref<RuntimeExecuteResult | null>(null)
const currentUiRequest = ref<UiRequestPayload | null>(null)
const currentInteractionSessionId = ref('')

const moduleForm = reactive<CapabilityModule>(emptyModule())
const toolForm = reactive<ToolAsset>(emptyTool())
const compositionForm = reactive<CompositionDefinition>(emptyComposition())
const interactionForm = reactive<InteractionDefinition>(emptyInteraction())

const runTitle = computed(() => `${runKind.value === 'tool' ? '工具' : '组合'}测试`)

watch(
  () => route.path,
  (path) => {
    if (path.endsWith('/compositions')) activeTab.value = 'compositions'
    else if (path.endsWith('/interactions')) activeTab.value = 'interactions'
    else activeTab.value = 'tools'
  },
  { immediate: true },
)

watch(activeTab, () => {
  if (selectedModule.value) loadAssets(selectedModule.value.code)
})

onMounted(loadModules)

async function loadModules() {
  loading.value = true
  try {
    const { data } = await listCapabilityModules()
    modules.value = data || []
    if (!selectedModule.value && modules.value.length > 0) {
      await selectModule(modules.value[0])
    } else if (selectedModule.value) {
      const next = modules.value.find((item) => item.code === selectedModule.value?.code) || null
      selectedModule.value = next
      if (next) await loadAssets(next.code)
    }
  } finally {
    loading.value = false
  }
}

async function selectModule(row: CapabilityModule | null) {
  selectedModule.value = row
  tools.value = []
  compositions.value = []
  interactions.value = []
  if (row) await loadAssets(row.code)
}

async function loadAssets(code: string) {
  assetLoading.value = true
  try {
    if (activeTab.value === 'tools') {
      const { data } = await listModuleTools(code)
      tools.value = data || []
    } else if (activeTab.value === 'compositions') {
      const { data } = await listModuleCompositions(code)
      compositions.value = data || []
    } else {
      const { data } = await listModuleInteractions(code)
      interactions.value = data || []
    }
  } finally {
    assetLoading.value = false
  }
}

function openModuleDialog(row?: CapabilityModule) {
  Object.assign(moduleForm, row ? { ...row } : emptyModule())
  moduleEditingCode.value = row?.code || ''
  moduleDialogVisible.value = true
}

function openToolDialog(row?: ToolAsset) {
  Object.assign(toolForm, row ? { ...row } : emptyTool())
  toolEditingCode.value = row?.toolCode || ''
  toolDialogVisible.value = true
}

function openCompositionDialog(row?: CompositionDefinition) {
  Object.assign(compositionForm, row ? { ...row } : emptyComposition())
  compositionEditingCode.value = row?.compositionCode || ''
  compositionDialogVisible.value = true
}

function openInteractionDialog(row?: InteractionDefinition) {
  Object.assign(interactionForm, row ? { ...row } : emptyInteraction())
  interactionEditingCode.value = row?.interactionCode || ''
  interactionDialogVisible.value = true
}

async function submitModule() {
  if (!moduleForm.code || !moduleForm.name) {
    ElMessage.warning('请填写模块编码和名称')
    return
  }
  saving.value = true
  try {
    await saveCapabilityModule({ ...moduleForm })
    moduleDialogVisible.value = false
    await loadModules()
    ElMessage.success('模块已保存')
  } finally {
    saving.value = false
  }
}

async function saveSelectedModule() {
  if (!selectedModule.value) return
  await saveCapabilityModule({ ...selectedModule.value })
  ElMessage.success('状态已更新')
}

async function submitTool() {
  if (!selectedModule.value) return
  if (!toolForm.toolCode || !toolForm.name) {
    ElMessage.warning('请填写工具编码和名称')
    return
  }
  saving.value = true
  try {
    await saveModuleTool(selectedModule.value.code, { ...toolForm })
    toolDialogVisible.value = false
    await loadAssets(selectedModule.value.code)
    ElMessage.success('工具已保存')
  } finally {
    saving.value = false
  }
}

async function submitComposition() {
  if (!selectedModule.value) return
  if (!compositionForm.compositionCode || !compositionForm.name) {
    ElMessage.warning('请填写组合编码和名称')
    return
  }
  tryParseJson(compositionForm.graphSpecJson || '{}')
  saving.value = true
  try {
    await saveModuleComposition(selectedModule.value.code, { ...compositionForm })
    compositionDialogVisible.value = false
    await loadAssets(selectedModule.value.code)
    ElMessage.success('组合已保存')
  } finally {
    saving.value = false
  }
}

async function submitInteraction() {
  if (!selectedModule.value) return
  if (!interactionForm.interactionCode || !interactionForm.name) {
    ElMessage.warning('请填写交互编码和名称')
    return
  }
  tryParseJson(interactionForm.specJson || '{}')
  saving.value = true
  try {
    await saveModuleInteraction(selectedModule.value.code, { ...interactionForm })
    interactionDialogVisible.value = false
    await loadAssets(selectedModule.value.code)
    ElMessage.success('交互定义已保存')
  } finally {
    saving.value = false
  }
}

function openRunDialog(kind: RunKind, row: ToolAsset | CompositionDefinition) {
  runKind.value = kind
  runTarget.value = row
  runResult.value = null
  currentUiRequest.value = null
  currentInteractionSessionId.value = ''
  runDialogVisible.value = true
}

async function executeRun() {
  if (!runTarget.value?.qualifiedName) return
  const params = tryParseJson(runParamsJson.value)
  running.value = true
  try {
    const { data } = runKind.value === 'tool'
      ? await executeToolAsset(runTarget.value.qualifiedName, params)
      : await executeComposition(runTarget.value.qualifiedName, params)
    applyRunResult(data)
  } finally {
    running.value = false
  }
}

async function resumeCurrentInteraction(values: Record<string, unknown>) {
  if (!currentInteractionSessionId.value) return
  running.value = true
  try {
    const { data } = await resumeInteraction(currentInteractionSessionId.value, values)
    currentUiRequest.value = null
    currentInteractionSessionId.value = ''
    applyRunResult(data)
  } finally {
    running.value = false
  }
}

function applyRunResult(data: RuntimeExecuteResult) {
  runResult.value = data
  const uiRequest = data.metadata?.uiRequest as UiRequestPayload | undefined
  const sessionId = data.metadata?.interactionSessionId
  if (data.status === 'WAITING_USER' && uiRequest && typeof sessionId === 'string') {
    currentUiRequest.value = uiRequest
    currentInteractionSessionId.value = sessionId
  }
}

function tryParseJson(raw: string): Record<string, unknown> {
  try {
    const value = JSON.parse(raw || '{}')
    return value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {}
  } catch {
    ElMessage.warning('JSON 格式不正确')
    throw new Error('invalid json')
  }
}

function prettyJson(value: unknown) {
  return JSON.stringify(value, null, 2)
}

function emptyModule(): CapabilityModule {
  return {
    code: '',
    name: '',
    version: '1.0.0',
    sourceType: 'BUILTIN',
    status: 'ACTIVE',
    enabled: true,
  }
}

function emptyTool(): ToolAsset {
  return {
    toolCode: '',
    name: '',
    executorType: 'BEAN',
    executorRef: '',
    sideEffect: 'WRITE',
    enabled: true,
    agentVisible: true,
  }
}

function emptyComposition(): CompositionDefinition {
  return {
    compositionCode: '',
    name: '',
    graphSpecJson: '{\n  "entry": "collect",\n  "nodes": [\n    {\n      "id": "collect",\n      "type": "INTERACTION",\n      "config": {\n        "interactionType": "COLLECT_INPUT",\n        "fields": [\n          { "key": "message", "label": "消息", "type": "string", "required": true }\n        ],\n        "outputAlias": "params"\n      }\n    }\n  ],\n  "edges": [\n    { "from": "START", "to": "collect", "condition": "always" },\n    { "from": "collect", "to": "END", "condition": "always" }\n  ]\n}',
    sideEffect: 'WRITE',
    enabled: true,
    agentVisible: true,
  }
}

function emptyInteraction(): InteractionDefinition {
  return {
    interactionCode: '',
    name: '',
    interactionType: 'COLLECT_INPUT',
    specJson: '{\n  "interactionType": "COLLECT_INPUT",\n  "title": "信息采集",\n  "fields": [\n    { "key": "message", "name": "message", "label": "消息", "type": "string", "required": true }\n  ],\n  "behavior": { "askPolicy": "MISSING_ONLY" }\n}',
    enabled: true,
    agentVisible: true,
  }
}
</script>

<style scoped lang="scss">
.capability-kernel {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.page-header {
  align-items: flex-end;

  p {
    margin: 6px 0 0;
    color: var(--text-secondary);
    font-size: 13px;
  }
}

.kernel-layout {
  display: grid;
  grid-template-columns: minmax(260px, 34%) minmax(0, 1fr);
  gap: 16px;
  min-height: 560px;
}

.module-pane,
.asset-pane {
  min-width: 0;
  border: 1px solid var(--border-light);
  border-radius: 8px;
  background: var(--bg-card);
}

.module-pane {
  padding: 10px;
}

.asset-pane {
  padding: 16px;
}

.module-cell {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 3px;

  strong,
  span {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  span {
    color: var(--text-tertiary);
    font-size: 12px;
  }
}

.module-summary,
.tab-toolbar,
.header-actions,
.summary-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.module-summary {
  margin-bottom: 12px;

  h3 {
    margin: 0 0 4px;
    font-size: 18px;
  }

  span {
    color: var(--text-secondary);
    font-size: 13px;
  }
}

.tab-toolbar {
  justify-content: flex-end;
  margin-bottom: 10px;
}

.inline-switch {
  margin-left: 16px;
}

.json-result {
  max-height: 320px;
  width: 100%;
  overflow: auto;
  margin: 0;
  padding: 12px;
  border-radius: 6px;
  background: var(--fill-muted);
  font-size: 12px;
  line-height: 1.6;
}

@media (max-width: 900px) {
  .kernel-layout {
    grid-template-columns: 1fr;
  }
}
</style>
