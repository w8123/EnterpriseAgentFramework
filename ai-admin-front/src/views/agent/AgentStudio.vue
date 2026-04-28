<template>
  <div class="studio-page">
    <!-- 顶部工具条 -->
    <div class="studio-header">
      <div class="header-left">
        <el-button @click="router.push('/agent')" :icon="ArrowLeft" text>返回</el-button>
        <h2>Agent Studio — {{ form.name || '未命名' }}</h2>
        <el-tag v-if="form.keySlug" size="small" type="info">{{ form.keySlug }}</el-tag>
      </div>
      <div class="header-right">
        <el-button @click="handleSwitchToForm" :icon="DocumentCopy">表单视图</el-button>
        <el-button @click="handleDebug" :icon="VideoPlay">调试</el-button>
        <el-button type="success" @click="handleSave" :loading="saving">保存草稿</el-button>
        <el-button type="primary" @click="publishDialogOpen = true">发布 / 灰度</el-button>
      </div>
    </div>

    <div class="studio-body">
      <!-- 左侧节点调色板 -->
      <aside class="palette">
        <div class="palette-title">节点</div>
        <div
          v-for="item in paletteItems"
          :key="item.kind"
          class="palette-item"
          :style="{ borderLeftColor: kindColor(item.kind).border }"
          draggable="true"
          @dragstart="onDragStart($event, item.kind)"
        >
          <div class="palette-item-title">{{ item.label }}</div>
          <div class="palette-item-desc">{{ item.hint }}</div>
        </div>

        <el-divider>拖拽加入画布</el-divider>
        <div class="palette-tips">
          · 双击节点打开属性<br />
          · 按住 Delete 删除所选<br />
          · Ctrl+滚轮缩放
        </div>
      </aside>

      <!-- 中间画布 -->
      <section class="canvas-wrap" @dragover.prevent @drop="onDrop">
        <VueFlow
          v-model:nodes="nodes"
          v-model:edges="edges"
          :default-viewport="{ zoom: 0.9 }"
          :delete-key-code="['Delete', 'Backspace']"
          @node-double-click="onNodeDoubleClick"
          @pane-click="selectedNodeId = null"
          @node-click="onNodeClick"
          class="studio-canvas"
        >
          <Background />
          <MiniMap />
          <Controls />

          <template #node-start="nodeProps">
            <div class="studio-node start-node">
              <div class="node-label">{{ nodeProps.data.label }}</div>
            </div>
          </template>
          <template #node-end="nodeProps">
            <div class="studio-node end-node">
              <div class="node-label">{{ nodeProps.data.label }}</div>
            </div>
          </template>
          <template #node-skill="nodeProps">
            <div class="studio-node skill-node">
              <div class="node-kind">SKILL</div>
              <div class="node-label">{{ nodeProps.data.ref || '未选择 Skill' }}</div>
              <div class="node-desc">{{ nodeProps.data.description }}</div>
            </div>
          </template>
          <template #node-tool="nodeProps">
            <div class="studio-node tool-node">
              <div class="node-kind">TOOL</div>
              <div class="node-label">{{ nodeProps.data.ref || '未选择 Tool' }}</div>
              <div class="node-desc">{{ nodeProps.data.description }}</div>
            </div>
          </template>
          <template #node-knowledge="nodeProps">
            <div class="studio-node knowledge-node">
              <div class="node-kind">KNOWLEDGE</div>
              <div class="node-label">{{ nodeProps.data.groupId || '未设置 groupId' }}</div>
            </div>
          </template>
        </VueFlow>
      </section>

      <!-- 右侧属性面板 -->
      <aside class="property-panel">
        <div v-if="!selectedNode">
          <el-alert title="选中节点以编辑属性" type="info" :closable="false" />
          <el-divider>Agent 元数据</el-divider>
          <el-form label-width="100px" size="small">
            <el-form-item label="名称">
              <el-input v-model="form.name" />
            </el-form-item>
            <el-form-item label="keySlug">
              <el-input v-model="form.keySlug" placeholder="默认自动生成" />
            </el-form-item>
            <el-form-item label="意图">
              <el-input v-model="form.intentType" />
            </el-form-item>
            <el-form-item label="最大步数">
              <el-input-number v-model="form.maxSteps" :min="1" :max="20" />
            </el-form-item>
            <el-form-item label="System Prompt">
              <el-input
                v-model="form.systemPrompt"
                type="textarea"
                :rows="8"
                placeholder="Agent 的角色和工作流程..."
              />
            </el-form-item>
            <el-form-item label="允许 IRREVERSIBLE" :title="'允许该 Agent 调用 DELETE 等不可逆工具'">
              <el-switch v-model="form.allowIrreversible" />
            </el-form-item>
          </el-form>
        </div>

        <div v-else>
          <el-divider>{{ selectedNode.data.kind.toUpperCase() }} 节点属性</el-divider>
          <el-form label-width="100px" size="small">
            <el-form-item label="ID">
              <el-input v-model="selectedNode.id" disabled />
            </el-form-item>
            <el-form-item label="标签">
              <el-input v-model="selectedNode.data.label" />
            </el-form-item>

            <el-form-item v-if="selectedNode.data.kind === 'tool'" label="引用 Tool">
              <el-select
                v-model="selectedNode.data.ref"
                filterable
                placeholder="选择 Tool"
                style="width: 100%"
              >
                <el-option
                  v-for="t in availableTools"
                  :key="t.name"
                  :label="t.name"
                  :value="t.name"
                />
              </el-select>
            </el-form-item>
            <el-form-item v-if="selectedNode.data.kind === 'skill'" label="引用 Skill">
              <el-select
                v-model="selectedNode.data.ref"
                filterable
                placeholder="选择 Skill"
                style="width: 100%"
              >
                <el-option
                  v-for="s in availableSkills"
                  :key="s.name"
                  :label="s.name"
                  :value="s.name"
                />
              </el-select>
            </el-form-item>

            <el-form-item v-if="selectedNode.data.kind === 'knowledge'" label="knowledgeGroup">
              <el-input v-model="selectedNode.data.groupId" placeholder="kb_general / kb_contract" />
            </el-form-item>

            <el-form-item label="描述">
              <el-input
                v-model="selectedNode.data.description"
                type="textarea"
                :rows="3"
              />
            </el-form-item>
          </el-form>

          <el-button
            type="danger"
            size="small"
            :icon="Delete"
            @click="deleteSelectedNode"
            :disabled="selectedNode.data.kind === 'start' || selectedNode.data.kind === 'end'"
          >删除节点</el-button>
        </div>
      </aside>
    </div>

    <!-- 发布弹窗 -->
    <el-dialog v-model="publishDialogOpen" title="发布 Agent 版本" width="480px">
      <el-alert
        v-if="publishWarnings.length"
        type="warning"
        :closable="false"
        class="publish-warning"
        title="发布前检查"
      >
        <ul>
          <li v-for="item in publishWarnings" :key="item">{{ item }}</li>
        </ul>
      </el-alert>
      <el-form :model="publishForm" label-width="120px">
        <el-form-item label="版本号" required>
          <el-input v-model="publishForm.version" placeholder="v1.0.0" />
        </el-form-item>
        <el-form-item label="灰度比例">
          <el-slider v-model="publishForm.rolloutPercent" :min="0" :max="100" show-input />
        </el-form-item>
        <el-form-item label="发布说明">
          <el-input v-model="publishForm.note" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="发布者">
          <el-input v-model="publishForm.publishedBy" placeholder="运营账号" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="publishDialogOpen = false">取消</el-button>
        <el-button type="primary" @click="handlePublish" :loading="publishing">确认发布</el-button>
      </template>
    </el-dialog>

    <!-- 调试抽屉：发布端点执行 + TraceTimeline 详情 -->
    <el-drawer v-model="debugOpen" title="Agent 调试（发布端点）" size="55%" direction="rtl">
      <div class="debug-body">
        <el-input
          v-model="debugMessage"
          type="textarea"
          :rows="3"
          placeholder="输入测试消息..."
        />
        <div class="debug-actions">
          <el-button type="primary" :loading="debugLoading" @click="handleRunDebug">
            通过发布端点执行
          </el-button>
        </div>
        <el-divider>结果</el-divider>
        <div class="debug-result">
          <div v-if="debugResult">
            <div class="result-section">
              <strong>回答：</strong>
              <div>{{ debugResult.answer }}</div>
            </div>
            <div class="result-section" v-if="debugResult.toolCalls?.length">
              <strong>Tool 调用：</strong>
              <el-tag
                v-for="t in debugResult.toolCalls"
                :key="t"
                size="small"
                class="tool-tag"
              >{{ t }}</el-tag>
            </div>
            <div class="result-section" v-if="debugResult.metadata">
              <strong>metadata：</strong>
              <pre>{{ JSON.stringify(debugResult.metadata, null, 2) }}</pre>
            </div>
            <div v-if="currentTraceId" class="result-section">
              <el-divider>Trace 详情（traceId: {{ currentTraceId }}）</el-divider>
              <div class="trace-toolbar">
                <el-select
                  v-model="skillPickTools"
                  multiple
                  filterable
                  placeholder="选中若干 tool 作为 Skill 序列（留空=全量）"
                  style="width: 100%"
                >
                  <el-option
                    v-for="name in traceToolNames"
                    :key="name"
                    :label="name"
                    :value="name"
                  />
                </el-select>
                <el-button
                  type="warning"
                  size="small"
                  :icon="Collection"
                  :disabled="!traceToolNames.length"
                  @click="handleExtractSkill"
                  :loading="extracting"
                >抽取为 Skill 草稿</el-button>
              </div>
              <TraceTimeline :nodes="traceNodes" />
            </div>
          </div>
          <el-empty v-else description="尚未执行" />
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, DocumentCopy, Delete, VideoPlay, Collection } from '@element-plus/icons-vue'

import { VueFlow, useVueFlow } from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import { MiniMap } from '@vue-flow/minimap'
import { Controls } from '@vue-flow/controls'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import '@vue-flow/controls/dist/style.css'
import '@vue-flow/minimap/dist/style.css'

import type { AgentForm } from '@/types/agent'
import type { CanvasNode, CanvasEdge, CanvasNodeKind } from '@/types/studio'
import { getAgent, updateAgent, publishAgentVersion, gatewayChat } from '@/api/agent'
import { getTools } from '@/api/tool'
import { getSkills } from '@/api/skill'
import type { ToolInfo } from '@/types/tool'
import type { SkillInfo } from '@/types/skill'
import type { ChatResponse } from '@/types/chat'
import { canvasToDefinition, definitionToCanvas, kindColor } from '@/utils/studio'
import TraceTimeline from '@/components/TraceTimeline.vue'
import { getTraceDetail } from '@/api/trace'
import type { TraceNode } from '@/types/trace'
import { extractDraftFromTrace } from '@/api/skillMining'

const route = useRoute()
const router = useRouter()
const agentId = route.params.id as string
const isNew = agentId === 'new'

const { screenToFlowCoordinate } = useVueFlow()

const saving = ref(false)
const publishing = ref(false)
const publishDialogOpen = ref(false)
const debugOpen = ref(false)
const debugLoading = ref(false)
const debugMessage = ref('这是一条测试消息')
const debugResult = ref<ChatResponse | null>(null)
const currentTraceId = ref<string>('')
const traceNodes = ref<TraceNode[]>([])
const skillPickTools = ref<string[]>([])
const extracting = ref(false)
const traceToolNames = computed(() => {
  const names = traceNodes.value
    .map((n) => (n.toolName || '').trim())
    .filter((n) => !!n)
  return Array.from(new Set(names))
})

const toolOptions = ref<ToolInfo[]>([])
const skillOptions = ref<SkillInfo[]>([])
const availableTools = computed(() =>
  toolOptions.value.filter((t) => t.enabled && t.agentVisible),
)
const availableSkills = computed(() =>
  skillOptions.value.filter((s) => s.enabled && s.agentVisible && !s.draft),
)

const form = reactive<AgentForm>({
  keySlug: '',
  name: '',
  description: '',
  intentType: 'GENERAL_CHAT',
  systemPrompt: '',
  tools: [],
  skills: [],
  modelName: '',
  maxSteps: 5,
  enabled: true,
  type: 'single',
  pipelineAgentIds: [],
  knowledgeBaseGroupId: '',
  promptTemplateId: '',
  outputSchemaType: '',
  triggerMode: 'all',
  useMultiAgentModel: false,
  extra: {},
  canvasJson: '',
  allowIrreversible: false,
})

const nodes = ref<CanvasNode[]>([])
const edges = ref<CanvasEdge[]>([])
const selectedNodeId = ref<string | null>(null)
const selectedNode = computed(() =>
  nodes.value.find((n) => n.id === selectedNodeId.value) ?? null,
)

const publishForm = reactive({
  version: 'v1.0.0',
  rolloutPercent: 100,
  note: '',
  publishedBy: '',
})

const publishWarnings = computed(() => {
  const warnings: string[] = []
  const toolCount = nodes.value.filter((n) => n.data.kind === 'tool' && n.data.ref).length
  const skillCount = nodes.value.filter((n) => n.data.kind === 'skill' && n.data.ref).length
  if (!form.keySlug) {
    warnings.push('未配置 keySlug，业务系统可能无法通过发布端点稳定访问。')
  }
  if (!form.systemPrompt || form.systemPrompt.length < 20) {
    warnings.push('System Prompt 较短，建议补充角色、边界和失败处理策略。')
  }
  if (toolCount + skillCount === 0) {
    warnings.push('画布中没有可调用 Tool/Skill，本版本只能进行纯对话。')
  }
  if (form.allowIrreversible) {
    warnings.push('已允许 IRREVERSIBLE 工具调用，请确认 Tool ACL 与限流已配置。')
  }
  if (publishForm.rolloutPercent === 100) {
    warnings.push('本次为全量发布，会替换该 Agent 的历史 ACTIVE 全量版本。')
  }
  return warnings
})

const paletteItems: { kind: CanvasNodeKind; label: string; hint: string }[] = [
  { kind: 'skill', label: 'Skill 节点', hint: '引用已注册的 SubAgentSkill' },
  { kind: 'tool', label: 'Tool 节点', hint: '引用原子工具（HTTP/Code）' },
  { kind: 'knowledge', label: 'Knowledge 节点', hint: '关联知识库组（RAG）' },
]

function onDragStart(event: DragEvent, kind: CanvasNodeKind) {
  event.dataTransfer?.setData('application/vueflow', kind)
  if (event.dataTransfer) event.dataTransfer.effectAllowed = 'move'
}

function onDrop(event: DragEvent) {
  const kind = event.dataTransfer?.getData('application/vueflow') as CanvasNodeKind | undefined
  if (!kind) return
  const position = screenToFlowCoordinate({ x: event.clientX, y: event.clientY })
  const id = `${kind}-${Date.now()}`
  nodes.value.push({
    id,
    type: kind,
    position,
    data: {
      label: kind === 'knowledge' ? '知识库' : `新${kind}`,
      kind,
      ref: '',
      groupId: '',
      description: '',
    },
  })
}

function onNodeClick(evt: { node: { id: string } }) {
  selectedNodeId.value = evt.node.id
}

function onNodeDoubleClick(evt: { node: { id: string } }) {
  selectedNodeId.value = evt.node.id
}

function deleteSelectedNode() {
  if (!selectedNode.value) return
  if (selectedNode.value.data.kind === 'start' || selectedNode.value.data.kind === 'end') {
    ElMessage.warning('开始/结束节点不可删除')
    return
  }
  const id = selectedNode.value.id
  nodes.value = nodes.value.filter((n) => n.id !== id)
  edges.value = edges.value.filter((e) => e.source !== id && e.target !== id)
  selectedNodeId.value = null
}

async function loadAgent() {
  if (isNew) {
    nodes.value = [
      {
        id: 'start',
        type: 'start',
        position: { x: 60, y: 220 },
        data: { label: '开始', kind: 'start' },
      },
      {
        id: 'end',
        type: 'end',
        position: { x: 500, y: 220 },
        data: { label: '结束', kind: 'end' },
      },
    ]
    edges.value = [{ id: 'e-start-end', source: 'start', target: 'end' }]
    return
  }
  try {
    const { data } = await getAgent(agentId)
    Object.assign(form, {
      keySlug: data.keySlug ?? '',
      name: data.name,
      description: data.description || '',
      intentType: data.intentType || '',
      systemPrompt: data.systemPrompt || '',
      tools: data.tools || [],
      skills: data.skills || [],
      modelName: data.modelName || '',
      maxSteps: data.maxSteps || 5,
      enabled: data.enabled ?? true,
      type: data.type || 'single',
      pipelineAgentIds: data.pipelineAgentIds || [],
      knowledgeBaseGroupId: data.knowledgeBaseGroupId || '',
      promptTemplateId: data.promptTemplateId || '',
      outputSchemaType: data.outputSchemaType || '',
      triggerMode: data.triggerMode || 'all',
      useMultiAgentModel: data.useMultiAgentModel ?? false,
      extra: data.extra || {},
      canvasJson: data.canvasJson || '',
      allowIrreversible: data.allowIrreversible ?? false,
    })
    const snap = definitionToCanvas(data)
    nodes.value = snap.nodes
    edges.value = snap.edges
  } catch {
    ElMessage.error('加载 Agent 失败')
  }
}

async function loadToolOptions() {
  try {
    const { data } = await getTools({ current: 1, size: 2000 })
    toolOptions.value = data?.records && Array.isArray(data.records) ? data.records : []
  } catch {
    toolOptions.value = []
  }
}

async function loadSkillOptions() {
  try {
    const { data } = await getSkills({ current: 1, size: 2000 })
    skillOptions.value = data?.records && Array.isArray(data.records) ? data.records : []
  } catch {
    skillOptions.value = []
  }
}

async function handleSave() {
  if (!form.name) {
    ElMessage.warning('请先填写 Agent 名称')
    return
  }
  saving.value = true
  try {
    const payload = canvasToDefinition(form, { nodes: nodes.value, edges: edges.value })
    if (isNew) {
      ElMessage.warning('请先在表单视图创建 Agent，再进入 Studio 编辑')
      return
    }
    await updateAgent(agentId, payload)
    ElMessage.success('已保存草稿')
  } catch {
    ElMessage.error('保存失败')
  } finally {
    saving.value = false
  }
}

async function handlePublish() {
  if (!publishForm.version) {
    ElMessage.warning('请先填写版本号')
    return
  }
  if (publishWarnings.value.length) {
    try {
      await ElMessageBox.confirm(
        publishWarnings.value.join('\n'),
        '确认继续发布？',
        { type: 'warning', confirmButtonText: '继续发布', cancelButtonText: '返回检查' },
      )
    } catch {
      return
    }
  }
  publishing.value = true
  try {
    await handleSave()
    await publishAgentVersion(agentId, {
      version: publishForm.version,
      rolloutPercent: publishForm.rolloutPercent,
      note: publishForm.note,
      publishedBy: publishForm.publishedBy,
    })
    ElMessage.success(`已发布 ${publishForm.version}（灰度 ${publishForm.rolloutPercent}%）`)
    publishDialogOpen.value = false
  } catch (err) {
    ElMessage.error('发布失败：' + (err as Error).message)
  } finally {
    publishing.value = false
  }
}

async function handleRunDebug() {
  const key = form.keySlug || agentId
  if (!debugMessage.value) {
    ElMessage.warning('请输入测试消息')
    return
  }
  debugLoading.value = true
  currentTraceId.value = ''
  traceNodes.value = []
  try {
    const { data } = await gatewayChat(key, {
      message: debugMessage.value,
      userId: 'studio-debug',
    })
    debugResult.value = data
    const traceId = (data.metadata?.traceId as string) || ''
    if (traceId) {
      currentTraceId.value = traceId
      try {
        const trace = await getTraceDetail(traceId)
        traceNodes.value = trace.data?.nodes ?? []
      } catch {
        // 如果 trace 还没写入（异步审计），给用户一个 retry 提示即可
      }
    }
  } catch (err) {
    ElMessage.error('调试失败：' + (err as Error).message)
  } finally {
    debugLoading.value = false
  }
}

function handleDebug() {
  debugOpen.value = true
}

async function handleExtractSkill() {
  if (!currentTraceId.value) {
    ElMessage.warning('请先执行调试获取 trace')
    return
  }
  const picks = skillPickTools.value.length
    ? skillPickTools.value
    : traceToolNames.value
  if (picks.length < 2) {
    ElMessage.warning('选中 tool 数量不足 2，无法抽取 Skill')
    return
  }
  extracting.value = true
  try {
    const { data } = await extractDraftFromTrace({
      traceId: currentTraceId.value,
      toolNames: picks,
    })
    ElMessage.success(`已生成 Skill 草稿：${data.name}（ID ${data.id}）`)
  } catch (err) {
    ElMessage.error('抽取失败：' + (err as Error).message)
  } finally {
    extracting.value = false
  }
}

function handleSwitchToForm() {
  ElMessageBox.confirm('切换到表单视图会丢弃未保存的画布变更，是否继续？', '提示')
    .then(() => router.push(`/agent/${agentId}/edit`))
    .catch(() => {})
}

onMounted(async () => {
  await Promise.all([loadToolOptions(), loadSkillOptions()])
  await loadAgent()
  await nextTick()
})

// 保留 watch 以便未来联动：若 tools 变化需要反映到画布，可在此同步
watch(
  () => form.tools,
  () => {},
)
</script>

<style scoped lang="scss">
.studio-page {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: #f5f7fa;
}

.publish-warning {
  margin-bottom: 12px;

  ul {
    margin: 4px 0 0;
    padding-left: 18px;
  }
}

.studio-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 20px;
  background: #fff;
  border-bottom: 1px solid #ebeef5;

  .header-left {
    display: flex;
    align-items: center;
    gap: 12px;

    h2 {
      margin: 0;
      font-size: 18px;
    }
  }

  .header-right {
    display: flex;
    gap: 8px;
  }
}

.studio-body {
  flex: 1;
  display: grid;
  grid-template-columns: 240px 1fr 320px;
  overflow: hidden;
}

.palette {
  background: #fff;
  border-right: 1px solid #ebeef5;
  padding: 16px;
  overflow-y: auto;

  .palette-title {
    font-weight: 600;
    margin-bottom: 12px;
  }

  .palette-item {
    border: 1px solid #e4e7ed;
    border-left: 4px solid #409eff;
    border-radius: 4px;
    padding: 10px 12px;
    margin-bottom: 8px;
    cursor: grab;
    background: #fafbfc;

    &:active {
      cursor: grabbing;
    }

    &-title {
      font-weight: 500;
      font-size: 13px;
    }

    &-desc {
      color: #909399;
      font-size: 12px;
      margin-top: 4px;
    }
  }

  .palette-tips {
    color: #909399;
    font-size: 12px;
    line-height: 1.8;
  }
}

.canvas-wrap {
  position: relative;
  background: #fafbfc;
}

.studio-canvas {
  width: 100%;
  height: 100%;
}

.property-panel {
  background: #fff;
  border-left: 1px solid #ebeef5;
  padding: 16px;
  overflow-y: auto;
}

.studio-node {
  padding: 10px 14px;
  border-radius: 6px;
  border: 2px solid;
  min-width: 140px;
  background: #fff;
  font-size: 13px;
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.06);

  .node-kind {
    font-size: 10px;
    font-weight: 700;
    color: #909399;
    margin-bottom: 2px;
    letter-spacing: 0.5px;
  }

  .node-label {
    font-weight: 500;
    word-break: break-all;
  }

  .node-desc {
    font-size: 11px;
    color: #909399;
    margin-top: 4px;
  }
}

.start-node {
  border-color: #409eff;
  background: #ecf5ff;
}

.end-node {
  border-color: #67c23a;
  background: #f0f9eb;
}

.skill-node {
  border-color: #e6a23c;
  background: #fdf6ec;
}

.tool-node {
  border-color: #909399;
}

.knowledge-node {
  border-color: #f56c6c;
  background: #fef0f0;
}

.debug-body {
  padding: 0 16px;
}

.debug-actions {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
}

.trace-toolbar {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  margin-bottom: 12px;

  .el-select {
    flex: 1;
  }
}

.result-section {
  margin-bottom: 12px;

  pre {
    background: #f4f4f5;
    padding: 8px;
    border-radius: 4px;
    font-size: 12px;
    white-space: pre-wrap;
    word-break: break-all;
  }
}
</style>
