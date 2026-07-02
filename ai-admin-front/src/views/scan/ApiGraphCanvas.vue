<template>
  <div class="api-graph-canvas">
    <div class="graph-toolbar">
      <div>
        <div class="toolbar-title">接口知识图谱</div>
        <div class="toolbar-subtitle">展示入参、出参和 DTO/VO 之间的引用关系</div>
      </div>
      <div class="toolbar-actions">
        <el-button size="small" :loading="loading" @click="refresh">刷新</el-button>
        <el-button size="small" :loading="rebuilding" @click="rebuild">重建图谱</el-button>
        <el-button size="small" type="warning" plain :loading="regenerating" @click="regenerate">重新生成</el-button>
        <el-button size="small" :loading="inferring" @click="inferModels">推断模型边</el-button>
        <el-button size="small" :loading="inferRR" @click="inferRequestResponse">推断请求/响应边</el-button>
        <el-switch v-model="linkMode" active-text="连线模式" inactive-text="" size="small" />
        <el-switch v-model="showCandidates" active-text="候选边" inactive-text="" size="small" />
        <el-switch v-model="showDtoPanel" active-text="数据模型" inactive-text="" size="small" />
      </div>
    </div>

    <div class="graph-hint">
      <span v-if="linkMode && linkSource">已选源节点：{{ linkSource.label }}，点击目标节点完成连线</span>
      <span v-else-if="linkMode">请点击一个接口或参数作为源节点</span>
      <span v-else>点击接口、参数或连线可查看右侧详情；开启「数据模型」可在画布右侧展示 DTO/VO 面板；开启连线模式可人工补充引用关系。</span>
    </div>

    <div
      ref="graphScrollRef"
      v-loading="loading"
      class="graph-container"
      @scroll="onGraphScroll"
      @wheel="onGraphWheel"
    >
      <div v-if="!graphLayout.apiCards.length && !graphLayout.dtoCards.length" class="empty-state">
        <el-empty description="暂无接口图谱数据，请先扫描或重建图谱" :image-size="88" />
      </div>
      <div
        v-else
        class="graph-stage"
        :style="{ width: `${graphLayout.width}px`, height: `${graphLayout.height}px` }"
      >
        <div class="legend-panel">
          <div class="legend-title">图例</div>
          <div class="legend-line">
            <span class="edge-sample request" />请求引用（入参引用其它接口的出参）
          </div>
          <div class="legend-line">
            <span class="edge-sample response" />响应引用（出参引用其它接口的出参）
          </div>
          <div class="legend-line">
            <span class="edge-sample model" />数据模型引用（共用数据结构）
          </div>
          <div class="legend-node"><span class="node-badge api">API</span>接口</div>
          <div class="legend-node"><span class="field-icon in">入</span>入参（请求）</div>
          <div class="legend-node"><span class="field-icon out">出</span>出参（响应）</div>
          <div class="legend-node"><span class="dto-icon">◇</span>数据模型（DTO/VO）</div>
        </div>

        <svg class="edge-layer" :width="graphLayout.width" :height="graphLayout.height">
          <defs>
            <marker
              v-for="kind in edgeKinds"
              :id="`api-graph-arrow-${kind}`"
              :key="kind"
              markerWidth="8"
              markerHeight="8"
              refX="7"
              refY="4"
              orient="auto"
              markerUnits="strokeWidth"
            >
              <path d="M 0 0 L 8 4 L 0 8 z" :class="['arrow-head', kind]" />
            </marker>
          </defs>
          <g
            v-for="edge in graphLayout.edges"
            :key="edge.id"
            class="edge-group"
            :class="{ candidate: edge.raw.status === 'CANDIDATE' }"
            @click.stop="selectEdge(edge.raw)"
          >
            <path class="edge-hit" :d="edge.path" />
            <path
              class="edge-path"
              :class="edge.visualKind"
              :d="edge.path"
              :marker-end="`url(#api-graph-arrow-${edge.visualKind})`"
            />
            <text
              v-if="edge.label"
              class="edge-label"
              :x="edge.labelX"
              :y="edge.labelY"
            >
              {{ edge.label }}
            </text>
          </g>
        </svg>

        <div
          v-for="card in graphLayout.apiCards"
          :key="card.node.id"
          class="api-card"
          :class="{ active: selectedNode?.id === card.node.id, dragging: draggingApiId === card.node.id }"
          :style="nodeStyle(card)"
          role="button"
          tabindex="0"
          @click="handleNodeClick(card.node)"
          @pointerdown="startApiDrag($event, card)"
        >
          <div class="api-card-header">
            <span class="node-badge api">API</span>
            <div class="api-title-block">
              <strong>{{ card.node.label }}</strong>
              <span>{{ card.method }} {{ card.path }}</span>
            </div>
          </div>
          <div class="api-fields">
            <div class="field-column">
              <div class="field-title">入参</div>
              <el-tooltip
                v-for="field in card.inFields"
                :key="field.node.id"
                placement="top"
                :show-after="280"
                :disabled="!field.description"
                :content="field.description"
                popper-class="api-graph-field-desc-tooltip"
              >
                <button
                  class="field-row"
                  type="button"
                  @click.stop="handleNodeClick(field.node)"
                >
                  <span class="field-icon in">入</span>
                  <span class="field-name">{{ field.name }}</span>
                  <span class="field-type">{{ field.type }}</span>
                </button>
              </el-tooltip>
              <div v-if="!card.inFields.length" class="field-empty">无</div>
            </div>
            <div class="field-column">
              <div class="field-title">出参</div>
              <el-tooltip
                v-for="field in card.outFields"
                :key="field.node.id"
                placement="top"
                :show-after="280"
                :disabled="!field.description"
                :content="field.description"
                popper-class="api-graph-field-desc-tooltip"
              >
                <button
                  class="field-row"
                  type="button"
                  @click.stop="handleNodeClick(field.node)"
                >
                  <span class="field-icon out">出</span>
                  <span class="field-name">{{ field.name }}</span>
                  <span class="field-type">{{ field.type }}</span>
                </button>
              </el-tooltip>
              <div v-if="!card.outFields.length" class="field-empty">无</div>
            </div>
          </div>
        </div>

        <section v-if="showDtoPanel && graphLayout.dtoCards.length" class="dto-panel" :style="dtoPanelStyle">
          <div class="dto-panel-title">数据模型（DTO/VO）</div>
          <button
            v-for="card in graphLayout.dtoCards"
            :key="card.node.id"
            class="dto-card"
            :class="{ active: selectedNode?.id === card.node.id }"
            type="button"
            @click="handleNodeClick(card.node)"
          >
            <div class="dto-title"><span class="dto-icon">◇</span>{{ card.node.label }}</div>
            <div v-for="field in card.fields" :key="field.key" class="dto-field">
              <span>{{ field.name }}</span>
              <span>{{ field.type }}</span>
            </div>
            <div v-if="!card.fields.length" class="dto-field dim">
              <span>rawType</span>
              <span>{{ shortType(card.node.typeName || parseProps(card.node).rawType || '-') }}</span>
            </div>
          </button>
        </section>

        <div class="relationship-note">
          <strong>关系说明</strong>
          <span>蓝色表示请求参数依赖，绿色表示响应字段流向，紫色虚线表示接口共用 DTO/VO 数据结构。</span>
        </div>
      </div>
    </div>

    <el-drawer v-model="detailVisible" :title="detailTitle" size="360px" direction="rtl">
      <template v-if="selectedNode">
        <div class="detail-section">
          <el-tag size="small" :type="nodeTagType(selectedNode.kind)">{{ nodeKindLabel(selectedNode.kind) }}</el-tag>
          <h3>{{ selectedNode.label }}</h3>
          <p v-if="selectedNode.typeName" class="detail-muted">{{ selectedNode.typeName }}</p>
        </div>
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item v-for="item in selectedNodeDetails" :key="item.label" :label="item.label">
            {{ item.value }}
          </el-descriptions-item>
        </el-descriptions>
        <div v-if="selectedNode.kind === 'API'" class="api-relation-panel">
          <div class="api-relation-header">
            <span class="api-relation-title">接口关联图谱</span>
            <span class="api-relation-zoom-actions">
              <el-button
                text
                type="primary"
                size="small"
                :icon="ZoomIn"
                aria-label="放大查看关联图谱"
                @click="openRelationZoom"
              />
            </span>
          </div>
          <p class="detail-muted">仅展示与当前接口已有连线关系的接口、DTO/VO。</p>
          <div v-if="!selectedApiMiniGraph.cards.length" class="relation-empty">暂无关联关系</div>
          <div
            v-else
            class="relation-mini-graph"
            :style="{ height: `${selectedApiMiniGraph.height}px` }"
          >
            <svg class="relation-mini-edges" :width="selectedApiMiniGraph.width" :height="selectedApiMiniGraph.height">
              <g v-for="edge in selectedApiMiniGraph.edges" :key="edge.id">
                <path class="relation-mini-edge" :class="edge.kind" :d="edge.path" />
                <text class="relation-mini-label" :x="edge.labelX" :y="edge.labelY">{{ edge.label }}</text>
              </g>
            </svg>
            <button
              v-for="card in selectedApiMiniGraph.cards"
              :key="`${card.kind}-${card.node.id}`"
              class="relation-mini-card"
              :class="[card.kind.toLowerCase(), { current: card.current }]"
              :style="{ left: `${card.x}px`, top: `${card.y}px`, width: `${card.width}px` }"
              type="button"
              @click="selectRelatedNode(card.node)"
            >
              <span class="relation-mini-title">
                <span v-if="card.kind === 'API'" class="node-badge api">API</span>
                <span v-else class="dto-icon">◇</span>
                <strong>{{ card.node.label }}</strong>
              </span>
              <span v-if="card.kind === 'API'" class="relation-mini-subtitle">
                {{ parseProps(card.node).httpMethod || 'API' }} {{ parseProps(card.node).endpointPath || parseProps(card.node).contextPath || '-' }}
              </span>
              <span v-else class="relation-mini-subtitle">{{ shortType(card.node.typeName || parseProps(card.node).rawType || '-') }}</span>
            </button>
          </div>
        </div>
        <div v-if="selectedNode.kind === 'FIELD_IN'" class="param-link-panel">
          <el-divider content-position="left">参数来源</el-divider>
          <p class="detail-muted">先选模块、再选接口与其它出参（可多层级子参），会创建“出参 → 当前入参”的请求引用线。</p>
          <el-cascader
            v-model="selectedSourceOutputPath"
            class="param-cascader"
            :popper-class="paramCascaderPopperClass"
            :popper-options="paramCascaderPopperOptions"
            placement="bottom-end"
            filterable
            clearable
            placeholder="选择来源出参"
            :options="sourceOutputOptions"
            :props="paramCascaderProps"
            :show-all-levels="true"
            :disabled="creatingRelationEdge"
            @change="createSelectedOutputToInputEdge"
          />
        </div>
        <div v-else-if="selectedNode.kind === 'FIELD_OUT'" class="param-link-panel">
          <el-divider content-position="left">连接到入参</el-divider>
          <p class="detail-muted">先选模块、再选接口与其它入参后，会创建“当前出参 → 入参”的请求引用线。</p>
          <el-cascader
            v-model="selectedTargetInputPath"
            class="param-cascader"
            :popper-class="paramCascaderPopperClass"
            :popper-options="paramCascaderPopperOptions"
            placement="bottom-end"
            filterable
            clearable
            placeholder="选择目标入参"
            :options="targetInputOptions"
            :props="paramCascaderProps"
            :show-all-levels="true"
            :disabled="creatingRelationEdge"
            @change="createOutputToSelectedInputEdge"
          />
        </div>
      </template>
      <template v-else-if="selectedEdge">
        <div class="detail-section">
          <el-tag size="small" :type="edgeTagType(selectedEdge.kind)">{{ edgeKindLabel(selectedEdge.kind) }}</el-tag>
          <h3>{{ edgeKindLabel(selectedEdge.kind) }}</h3>
          <p class="detail-muted">{{ edgeNodeLabel(selectedEdge.sourceNodeId) }} → {{ edgeNodeLabel(selectedEdge.targetNodeId) }}</p>
        </div>
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="来源">{{ selectedEdge.source }}</el-descriptions-item>
          <el-descriptions-item label="置信度">{{ selectedEdge.confidence ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ selectedEdge.status ?? '-' }}</el-descriptions-item>
          <el-descriptions-item v-if="selectedEdge.inferStrategy" label="推断策略">
            {{ selectedEdge.inferStrategy }}
          </el-descriptions-item>
          <el-descriptions-item v-if="selectedEdge.note" label="备注">{{ selectedEdge.note }}</el-descriptions-item>
        </el-descriptions>
        <div v-if="selectedEdge.status === 'CANDIDATE'" class="detail-actions">
          <el-button size="small" type="success" @click="confirmEdge(selectedEdge)">确认候选边</el-button>
          <el-button size="small" type="danger" @click="rejectEdge(selectedEdge)">拒绝</el-button>
        </div>
      </template>
    </el-drawer>

    <el-dialog v-model="edgeDialogVisible" title="选择引用关系" width="360px">
      <el-radio-group v-model="pendingEdgeKind" class="edge-kind-options">
        <el-radio value="REQUEST_REF">请求引用（蓝色）</el-radio>
        <el-radio value="RESPONSE_REF">响应引用（绿色）</el-radio>
        <el-radio value="MODEL_REF">数据模型引用（紫色虚线）</el-radio>
      </el-radio-group>
      <template #footer>
        <el-button @click="cancelPendingEdge">取消</el-button>
        <el-button type="primary" :loading="creatingEdge" @click="submitPendingEdge">确定连线</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="relationZoomVisible"
      class="relation-zoom-dialog"
      :title="relationZoomTitle"
      width="min(92vw, 920px)"
      align-center
      :z-index="3200"
      append-to-body
      destroy-on-close
      @closed="onRelationZoomClosed"
    >
      <div
        v-if="selectedApiMiniGraph.cards.length"
        class="relation-zoom-viewport"
        :style="{
          width: `${Math.ceil(selectedApiMiniGraph.width * relationZoomScale) + 32}px`,
          height: `${Math.ceil(selectedApiMiniGraph.height * relationZoomScale) + 32}px`,
        }"
      >
        <div
          class="relation-zoom-scaler"
          :style="{
            width: `${selectedApiMiniGraph.width}px`,
            height: `${selectedApiMiniGraph.height}px`,
            transform: `scale(${relationZoomScale})`,
          }"
        >
          <svg class="relation-mini-edges" :width="selectedApiMiniGraph.width" :height="selectedApiMiniGraph.height">
            <g v-for="edge in selectedApiMiniGraph.edges" :key="`zoom-${edge.id}`">
              <path class="relation-mini-edge" :class="edge.kind" :d="edge.path" />
              <text class="relation-mini-label" :x="edge.labelX" :y="edge.labelY">{{ edge.label }}</text>
            </g>
          </svg>
          <button
            v-for="card in selectedApiMiniGraph.cards"
            :key="`zoom-${card.kind}-${card.node.id}`"
            class="relation-mini-card"
            :class="[card.kind.toLowerCase(), { current: card.current }]"
            :style="{ left: `${card.x}px`, top: `${card.y}px`, width: `${card.width}px` }"
            type="button"
            @click="selectRelatedNodeFromZoom(card.node)"
          >
            <span class="relation-mini-title">
              <span v-if="card.kind === 'API'" class="node-badge api">API</span>
              <span v-else class="dto-icon">◇</span>
              <strong>{{ card.node.label }}</strong>
            </span>
            <span v-if="card.kind === 'API'" class="relation-mini-subtitle">
              {{ parseProps(card.node).httpMethod || 'API' }} {{ parseProps(card.node).endpointPath || parseProps(card.node).contextPath || '-' }}
            </span>
            <span v-else class="relation-mini-subtitle">{{ shortType(card.node.typeName || parseProps(card.node).rawType || '-') }}</span>
          </button>
        </div>
      </div>
      <el-empty v-else description="暂无关联关系" :image-size="72" />
    </el-dialog>

    <Teleport to="body">
      <div
        v-show="hScrollDockVisible"
        class="api-graph-hscroll-dock"
        :style="hScrollDockPositionStyle"
        aria-hidden="true"
      >
        <div
          ref="hScrollTrackRef"
          class="api-graph-hscroll-track"
          @scroll.passive="onHScrollDockScroll"
        >
          <div class="api-graph-hscroll-spacer" :style="{ width: `${hScrollContentWidth}px` }" />
        </div>
      </div>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { ZoomIn } from '@element-plus/icons-vue'
import {
  confirmApiGraphCandidate,
  rejectApiGraphCandidate,
  upsertApiGraphEdge,
  type ApiGraphEdge,
  type ApiGraphEdgeKind,
  type ApiGraphNode,
  type ApiGraphSnapshot,
} from '@/api/apiGraph'
import { useApiGraphCardDrag } from '@/views/scan/composables/useApiGraphCardDrag'
import { useApiGraphDataActions } from '@/views/scan/composables/useApiGraphDataActions'
import { useApiGraphHorizontalDock } from '@/views/scan/composables/useApiGraphHorizontalDock'
import {
  composeGraphEdgePath,
  type Point,
} from './apiGraphCanvasGeometry'
import {
  buildApiMiniGraph,
  buildDtoFieldBuckets,
  edgeKindLabel,
  edgeKinds,
  edgeLabel,
  edgeTagType,
  groupBy,
  inferDefaultEdgeKind,
  nodeKindLabel,
  nodeStyle,
  nodeTagType,
  paramCascaderPopperClass,
  paramCascaderPopperOptions,
  paramCascaderProps,
  parseProps,
  selectedCascaderNodeId,
  shortType,
  simpleType,
  toFieldVM,
  type ApiCardVM,
  type ApiRelationItem,
  type CascaderPath,
  type DetailItem,
  type DtoCardVM,
  type EdgeVM,
  type EdgeVisualKind,
  type MiniGraphLayout,
  type ParamCascaderOption,
  type ParamPathTreeNode,
} from './apiGraphCanvasViewModel'

const props = withDefaults(
  defineProps<{ projectId: number, panelExpanded?: boolean }>(),
  { panelExpanded: true },
)

const {
  graphScrollRef,
  hScrollTrackRef,
  hScrollContentWidth,
  hScrollDockVisible,
  hScrollDockPositionStyle,
  bindDockScrollListeners,
  refreshHScrollDockMetrics,
  scheduleHScrollDockLayout,
  onGraphScroll,
  onHScrollDockScroll,
  onGraphWheel,
  mountHScrollDock,
  unmountHScrollDock,
} = useApiGraphHorizontalDock({
  panelExpanded: computed(() => props.panelExpanded),
})

const linkMode = ref(false)
const showCandidates = ref(false)
const showDtoPanel = ref(false)
const linkSource = ref<ApiGraphNode | null>(null)
const snapshot = ref<ApiGraphSnapshot>({ nodes: [], edges: [], layouts: [] })
const candidateEdges = ref<ApiGraphEdge[]>([])
const selectedNode = ref<ApiGraphNode | null>(null)
const selectedEdge = ref<ApiGraphEdge | null>(null)
const detailVisible = ref(false)
const relationZoomVisible = ref(false)
const relationZoomScale = ref(1.25)
const edgeDialogVisible = ref(false)
const creatingEdge = ref(false)
const creatingRelationEdge = ref(false)
const pendingEdgeKind = ref<ApiGraphEdgeKind>('REQUEST_REF')
const pendingEdge = ref<{ source: ApiGraphNode, target: ApiGraphNode } | null>(null)
const selectedSourceOutputPath = ref<CascaderPath>([])
const selectedTargetInputPath = ref<CascaderPath>([])
const manualApiPositions = ref<Record<number, Point>>({})

function resetAfterRegenerate() {
  selectedNode.value = null
  selectedEdge.value = null
  detailVisible.value = false
  relationZoomVisible.value = false
  linkSource.value = null
}

const {
  loading,
  rebuilding,
  regenerating,
  inferring,
  inferRR,
  loadGraph,
  refresh,
  rebuild,
  regenerate,
  inferModels,
  inferRequestResponse,
} = useApiGraphDataActions({
  projectId: () => props.projectId,
  showCandidates,
  snapshot,
  candidateEdges,
  manualApiPositions,
  refreshLayoutMetrics: refreshHScrollDockMetrics,
  resetAfterRegenerate,
})

const CARD_WIDTH = 268
const API_CARD_GAP_X = 118
const API_CARD_GAP_Y = 48
const API_CARD_TOP = 138
const API_CARD_LEFT = 330
const CARD_HEADER_HEIGHT = 60
const FIELD_TITLE_HEIGHT = 30
const FIELD_ROW_HEIGHT = 30
const FIELD_START_OFFSET = CARD_HEADER_HEIGHT + FIELD_TITLE_HEIGHT + 18
/** 入/出参侧线在行上略偏上时，锚点整体下移（与 FIELD_ROW_HEIGHT/2 一起表示行垂直中心） */
const FIELD_SIDE_ANCHOR_Y_BIAS = 6

function apiFieldEdgeAnchorY(cardTopY: number, fieldIndex: number): number {
  return (
    cardTopY
    + FIELD_START_OFFSET
    + fieldIndex * FIELD_ROW_HEIGHT
    + FIELD_ROW_HEIGHT / 2
    + FIELD_SIDE_ANCHOR_Y_BIAS
  )
}
const DTO_PANEL_WIDTH = 260
const DTO_CARD_HEIGHT = 128
const DTO_CARD_GAP = 16
const DTO_PANEL_TOP = 76
const LEGEND_WIDTH = 240
const {
  draggingApiId,
  consumeSuppressedCardClick,
  startApiDrag,
  unmountApiCardDrag,
} = useApiGraphCardDrag({
  projectId: () => props.projectId,
  manualApiPositions,
  minX: LEGEND_WIDTH + 48,
  minY: 24,
})

const nodeMap = computed(() => new Map(snapshot.value.nodes.map((node) => [node.id, node])))
const allEdges = computed(() => [...snapshot.value.edges, ...candidateEdges.value].filter((edge) => edge.enabled !== false))
const apiLabelMap = computed(() => {
  const map = new Map<number, string>()
  snapshot.value.nodes
    .filter((node) => node.kind === 'API')
    .forEach((node) => map.set(node.id, node.label))
  return map
})
const fieldNodes = computed(() => snapshot.value.nodes.filter((node) => node.kind === 'FIELD_IN' || node.kind === 'FIELD_OUT'))
const fieldChildrenByParent = computed(() => {
  const map = new Map<number, ApiGraphNode[]>()
  fieldNodes.value.forEach((node) => {
    if (node.parentId != null) {
      if (!map.has(node.parentId)) map.set(node.parentId, [])
      map.get(node.parentId)!.push(node)
    }
  })
  return map
})
const sourceOutputOptions = computed(() => {
  if (!selectedNode.value || selectedNode.value.kind !== 'FIELD_IN') return []
  return buildParamCascaderOptions('FIELD_OUT', selectedNode.value.refId)
})
const targetInputOptions = computed(() => {
  if (!selectedNode.value || selectedNode.value.kind !== 'FIELD_OUT') return []
  return buildParamCascaderOptions('FIELD_IN', selectedNode.value.refId)
})

const graphLayout = computed(() => {
  const showDto = showDtoPanel.value
  const apiNodes = snapshot.value.nodes.filter((node) => node.kind === 'API')
  const dtoNodes = showDto ? snapshot.value.nodes.filter((node) => node.kind === 'DTO') : []
  const fieldNodes = snapshot.value.nodes.filter((node) => node.kind === 'FIELD_IN' || node.kind === 'FIELD_OUT')
  const fieldsByParent = groupBy(fieldNodes, (node) => node.parentId ?? node.refId ?? 0)
  const apiCards: ApiCardVM[] = []
  const dtoCards: DtoCardVM[] = []
  const anchorMap = new Map<number, Point>()

  const columnCount = 3
  const rowHeights: number[] = []
  const preparedCards = apiNodes.map((node, index) => {
    const row = Math.floor(index / columnCount)
    const col = index % columnCount
    const apiFields = fieldsByParent.get(node.id) ?? []
    const inFields = apiFields.filter((field) => field.kind === 'FIELD_IN').map(toFieldVM)
    const outFields = apiFields.filter((field) => field.kind === 'FIELD_OUT').map(toFieldVM)
    const rowCount = Math.max(inFields.length, outFields.length, 1)
    const height = FIELD_START_OFFSET + rowCount * FIELD_ROW_HEIGHT + 18
    rowHeights[row] = Math.max(rowHeights[row] ?? 0, height)
    return {
      node,
      row,
      col,
      width: CARD_WIDTH,
      height,
      method: String(parseProps(node).httpMethod || 'API'),
      path: String(parseProps(node).endpointPath || parseProps(node).contextPath || '-'),
      inFields,
      outFields,
    }
  })
  const rowTops = rowHeights.reduce<number[]>((tops, height, index) => {
    tops[index] = index === 0 ? API_CARD_TOP : tops[index - 1] + rowHeights[index - 1] + API_CARD_GAP_Y
    return tops
  }, [])

  preparedCards.forEach((prepared) => {
    const manualPosition = manualApiPositions.value[prepared.node.id]
    const card: ApiCardVM = {
      node: prepared.node,
      x: manualPosition?.x ?? API_CARD_LEFT + prepared.col * (CARD_WIDTH + API_CARD_GAP_X),
      y: manualPosition?.y ?? rowTops[prepared.row],
      width: prepared.width,
      height: prepared.height,
      method: prepared.method,
      path: prepared.path,
      inFields: prepared.inFields,
      outFields: prepared.outFields,
    }
    apiCards.push(card)
    anchorMap.set(prepared.node.id, { x: card.x + card.width / 2, y: card.y + 34 })
    prepared.inFields.forEach((field, fieldIndex) => {
      anchorMap.set(field.node.id, { x: card.x + 18, y: apiFieldEdgeAnchorY(card.y, fieldIndex) })
    })
    prepared.outFields.forEach((field, fieldIndex) => {
      anchorMap.set(field.node.id, { x: card.x + card.width - 18, y: apiFieldEdgeAnchorY(card.y, fieldIndex) })
    })
  })

  const maxApiRight = Math.max(...apiCards.map((card) => card.x + card.width), API_CARD_LEFT + CARD_WIDTH)
  const dtoPanelX = maxApiRight + 72
  const dtoPanelHeight = dtoNodes.length * (DTO_CARD_HEIGHT + DTO_CARD_GAP) + 70
  const dtoFieldBuckets = buildDtoFieldBuckets(dtoNodes, fieldNodes, fieldsByParent)
  if (showDto) {
    dtoNodes.forEach((node, index) => {
      const card: DtoCardVM = {
        node,
        x: dtoPanelX + 18,
        y: DTO_PANEL_TOP + 56 + index * (DTO_CARD_HEIGHT + DTO_CARD_GAP),
        width: DTO_PANEL_WIDTH - 36,
        height: DTO_CARD_HEIGHT,
        fields: dtoFieldBuckets.get(node.label) ?? [],
      }
      dtoCards.push(card)
      anchorMap.set(node.id, { x: card.x, y: card.y + 30 })
    })
  }
  fieldNodes.forEach((node) => {
    if (anchorMap.has(node.id)) return
    const inherited = findNearestAnchor(node, anchorMap)
    if (inherited) {
      anchorMap.set(node.id, inherited)
    }
  })

  const width = showDto
    ? Math.max(dtoPanelX + DTO_PANEL_WIDTH + 34, maxApiRight + 220, 1120)
    : Math.max(maxApiRight + 220, 1120)
  const apiBottom = Math.max(...apiCards.map((card) => card.y + card.height), API_CARD_TOP + 300)
  const height = showDto
    ? Math.max(apiBottom + 92, DTO_PANEL_TOP + dtoPanelHeight + 28, 620)
    : Math.max(apiBottom + 92, 620)

  const edges = allEdges.value
    .map((edge) => toEdgeVM(edge, anchorMap))
    .filter((edge): edge is EdgeVM => Boolean(edge))

  return {
    width,
    height,
    apiCards,
    dtoCards,
    dtoPanel: showDto
      ? { x: dtoPanelX, y: DTO_PANEL_TOP, width: DTO_PANEL_WIDTH, height: dtoPanelHeight }
      : { x: 0, y: 0, width: 0, height: 0 },
    edges,
  }
})

const dtoPanelStyle = computed(() => ({
  left: `${graphLayout.value.dtoPanel.x}px`,
  top: `${graphLayout.value.dtoPanel.y}px`,
  width: `${graphLayout.value.dtoPanel.width}px`,
  minHeight: `${graphLayout.value.dtoPanel.height}px`,
}))

const detailTitle = computed(() => selectedNode.value ? '节点详情' : '关系详情')
const selectedNodeDetails = computed<DetailItem[]>(() => {
  if (!selectedNode.value) return []
  const node = selectedNode.value
  const props = parseProps(node)
  const base: DetailItem[] = [
    { label: '节点 ID', value: node.id },
    { label: '类型', value: nodeKindLabel(node.kind) },
  ]
  if (node.kind === 'API') {
    base.push(
      { label: 'HTTP 方法', value: String(props.httpMethod || '-') },
      { label: '接口路径', value: String(props.endpointPath || props.contextPath || '-') },
      { label: '描述', value: String(props.aiDescription || props.description || '-') },
    )
  } else if (node.kind === 'FIELD_IN' || node.kind === 'FIELD_OUT') {
    base.push(
      { label: '参数路径', value: String(props.paramPath || node.label) },
      { label: '参数类型', value: node.typeName || '-' },
      { label: '位置', value: String(props.location || '-') },
      { label: '必填', value: props.required === true ? '是' : '否' },
      { label: '描述', value: String(props.description || '-') },
    )
  } else if (node.kind === 'DTO') {
    base.push(
      { label: '模型类型', value: node.typeName || String(props.rawType || '-') },
    )
  }
  return base
})
const selectedApiRelations = computed<ApiRelationItem[]>(() => {
  if (!selectedNode.value || selectedNode.value.kind !== 'API') return []
  return buildApiRelations(selectedNode.value)
})
const selectedApiMiniGraph = computed<MiniGraphLayout>(() => {
  if (!selectedNode.value || selectedNode.value.kind !== 'API') {
    return { width: 420, height: 0, cards: [], edges: [] }
  }
  return buildApiMiniGraph(selectedNode.value, selectedApiRelations.value)
})

async function createSelectedOutputToInputEdge() {
  if (!selectedNode.value || selectedNode.value.kind !== 'FIELD_IN') return
  const sourceNodeId = selectedCascaderNodeId(selectedSourceOutputPath.value)
  if (!sourceNodeId) return
  const source = nodeMap.value.get(sourceNodeId)
  if (!source) return
  await createParamReferenceEdge(source, selectedNode.value)
  selectedSourceOutputPath.value = []
}

async function createOutputToSelectedInputEdge() {
  if (!selectedNode.value || selectedNode.value.kind !== 'FIELD_OUT') return
  const targetNodeId = selectedCascaderNodeId(selectedTargetInputPath.value)
  if (!targetNodeId) return
  const target = nodeMap.value.get(targetNodeId)
  if (!target) return
  await createParamReferenceEdge(selectedNode.value, target)
  selectedTargetInputPath.value = []
}

async function createParamReferenceEdge(source: ApiGraphNode, target: ApiGraphNode) {
  creatingRelationEdge.value = true
  try {
    await upsertApiGraphEdge(props.projectId, {
      sourceNodeId: source.id,
      targetNodeId: target.id,
      kind: 'REQUEST_REF',
      note: 'manual-param-source',
    })
    ElMessage.success('参数引用关系已创建')
    await loadGraph()
  } catch {
    ElMessage.error('创建参数引用关系失败')
  } finally {
    creatingRelationEdge.value = false
  }
}

function handleNodeClick(node: ApiGraphNode) {
  if (consumeSuppressedCardClick()) {
    return
  }
  if (linkMode.value) {
    if (!linkSource.value) {
      linkSource.value = node
      return
    }
    if (linkSource.value.id === node.id) {
      linkSource.value = null
      return
    }
    const normalizedParamEdge = normalizeParamReferencePair(linkSource.value, node)
    if (normalizedParamEdge) {
      void createEdge(normalizedParamEdge.source, normalizedParamEdge.target, 'REQUEST_REF')
      return
    }

    pendingEdge.value = { source: linkSource.value, target: node }
    pendingEdgeKind.value = inferDefaultEdgeKind(linkSource.value, node)
    edgeDialogVisible.value = true
    return
  }
  selectedNode.value = node
  selectedEdge.value = null
  detailVisible.value = true
}

function normalizeParamReferencePair(source: ApiGraphNode, target: ApiGraphNode) {
  if (source.kind === 'FIELD_OUT' && target.kind === 'FIELD_IN') {
    return { source, target }
  }
  if (source.kind === 'FIELD_IN' && target.kind === 'FIELD_OUT') {
    return { source: target, target: source }
  }
  return null
}

function selectEdge(edge: ApiGraphEdge) {
  selectedEdge.value = edge
  selectedNode.value = null
  detailVisible.value = true
}

function selectRelatedNode(node: ApiGraphNode) {
  selectedNode.value = node
  selectedEdge.value = null
  detailVisible.value = true
}

const relationZoomTitle = computed(() => {
  const n = selectedNode.value
  if (n?.kind === 'API') return `接口关联图谱 · ${n.label}`
  return '接口关联图谱'
})

function openRelationZoom() {
  if (!selectedNode.value || selectedNode.value.kind !== 'API') return
  if (!selectedApiMiniGraph.value.cards.length) {
    ElMessage.info('暂无关联关系')
    return
  }
  relationZoomScale.value = 1.25
  relationZoomVisible.value = true
}

function onRelationZoomClosed() {
  relationZoomScale.value = 1.25
}

function selectRelatedNodeFromZoom(node: ApiGraphNode) {
  selectRelatedNode(node)
  relationZoomVisible.value = false
}

async function submitPendingEdge() {
  if (!pendingEdge.value) return
  await createEdge(pendingEdge.value.source, pendingEdge.value.target, pendingEdgeKind.value)
}

function cancelPendingEdge() {
  edgeDialogVisible.value = false
  pendingEdge.value = null
  linkSource.value = null
}

async function createEdge(source: ApiGraphNode, target: ApiGraphNode, kind: ApiGraphEdgeKind) {
  creatingEdge.value = true
  try {
    await upsertApiGraphEdge(props.projectId, {
      sourceNodeId: source.id,
      targetNodeId: target.id,
      kind,
      note: 'manual',
    })
    ElMessage.success('连线成功')
    edgeDialogVisible.value = false
    pendingEdge.value = null
    linkSource.value = null
    await loadGraph()
  } catch {
    ElMessage.error('连线失败')
  } finally {
    creatingEdge.value = false
  }
}

async function confirmEdge(edge: ApiGraphEdge) {
  try {
    await confirmApiGraphCandidate(props.projectId, edge.id)
    ElMessage.success('已确认')
    selectedEdge.value = null
    await loadGraph()
  } catch {
    ElMessage.error('确认失败')
  }
}

async function rejectEdge(edge: ApiGraphEdge) {
  try {
    await rejectApiGraphCandidate(props.projectId, edge.id, 'manual-reject')
    ElMessage.success('已拒绝')
    selectedEdge.value = null
    await loadGraph()
  } catch {
    ElMessage.error('拒绝失败')
  }
}

watch(() => props.projectId, loadGraph)
watch(showCandidates, loadGraph)
watch(linkMode, (enabled) => {
  if (!enabled) {
    linkSource.value = null
    pendingEdge.value = null
    edgeDialogVisible.value = false
  }
})
watch(() => selectedNode.value?.id, () => {
  selectedSourceOutputPath.value = []
  selectedTargetInputPath.value = []
})

watch(detailVisible, (open) => {
  if (!open) relationZoomVisible.value = false
})

watch(() => selectedNode.value?.kind, (k) => {
  if (k !== 'API') relationZoomVisible.value = false
})

watch(
  () => [
    graphLayout.value.width,
    graphLayout.value.height,
    graphLayout.value.apiCards.length,
    graphLayout.value.dtoCards.length,
  ],
  () => nextTick(() => refreshHScrollDockMetrics()),
)

watch(showDtoPanel, () => nextTick(() => refreshHScrollDockMetrics()))

watch(
  () => props.panelExpanded,
  () => {
    nextTick(() => {
      bindDockScrollListeners()
      refreshHScrollDockMetrics()
    })
  },
)

watch(draggingApiId, (id) => {
  if (id === null)
    nextTick(() => refreshHScrollDockMetrics())
})

onMounted(() => {
  loadGraph()
  nextTick(() => {
    mountHScrollDock()
  })
})
onBeforeUnmount(() => {
  unmountApiCardDrag()
  unmountHScrollDock()
})

function buildParamCascaderOptions(
  kind: 'FIELD_IN' | 'FIELD_OUT',
  excludedApiNodeId: number | null,
): ParamCascaderOption[] {
  const fieldsByApi = new Map<number, ApiGraphNode[]>()
  fieldNodes.value
    .filter((node) => node.kind === kind)
    .forEach((node) => {
      const apiNodeId = resolveApiNodeId(node)
      if (!apiNodeId || apiNodeId === excludedApiNodeId) return
      if (!fieldsByApi.has(apiNodeId)) fieldsByApi.set(apiNodeId, [])
      fieldsByApi.get(apiNodeId)!.push(node)
    })

  const moduleNodesById = new Map<number, ApiGraphNode>()
  for (const n of snapshot.value.nodes) {
    if (n.kind === 'MODULE') moduleNodesById.set(n.id, n)
  }

  const apiOptions: ParamCascaderOption[] = snapshot.value.nodes
    .filter((node) => node.kind === 'API' && node.id !== excludedApiNodeId && fieldsByApi.has(node.id))
    .sort((a, b) => a.label.localeCompare(b.label, 'zh-Hans-CN'))
    .map((apiNode) => ({
      value: `api-${apiNode.id}`,
      label: apiNode.label || `接口 #${apiNode.id}`,
      children: buildParamPathTree(fieldsByApi.get(apiNode.id) ?? [], kind),
    }))
    .filter((option) => (option.children?.length ?? 0) > 0)

  type ModuleGroupKey = number | 'none'
  const grouped = new Map<ModuleGroupKey, ParamCascaderOption[]>()
  for (const opt of apiOptions) {
    const apiId = Number(String(opt.value).replace(/^api-/, ''))
    const apiNode = nodeMap.value.get(apiId)
    let modKey: ModuleGroupKey = 'none'
    if (apiNode?.parentId != null) {
      const parent = nodeMap.value.get(apiNode.parentId)
      if (parent?.kind === 'MODULE') modKey = parent.id
    }
    if (!grouped.has(modKey)) grouped.set(modKey, [])
    grouped.get(modKey)!.push(opt)
  }

  const rows: { modKey: ModuleGroupKey; label: string; children: ParamCascaderOption[] }[] = []
  for (const [modKey, children] of grouped) {
    const label =
      modKey === 'none'
        ? '未归属模块'
        : moduleNodesById.get(modKey)?.label || `模块 #${modKey}`
    rows.push({ modKey, label, children })
  }
  rows.sort((a, b) => {
    if (a.modKey === 'none') return 1
    if (b.modKey === 'none') return -1
    return a.label.localeCompare(b.label, 'zh-Hans-CN')
  })

  return rows.map((row) => ({
    value: row.modKey === 'none' ? 'mod-none' : `mod-${row.modKey}`,
    label: row.label,
    children: row.children,
  }))
}

function buildApiRelations(apiNode: ApiGraphNode): ApiRelationItem[] {
  const relationMap = new Map<string, ApiRelationItem>()

  allEdges.value.forEach((edge) => {
    const sourceNode = nodeMap.value.get(edge.sourceNodeId)
    const targetNode = nodeMap.value.get(edge.targetNodeId)
    if (!sourceNode || !targetNode) return

    const sourceBelongsToApi = isNodeInApi(sourceNode, apiNode.id)
    const targetBelongsToApi = isNodeInApi(targetNode, apiNode.id)
    if (!sourceBelongsToApi && !targetBelongsToApi) return

    const otherNode = sourceBelongsToApi ? targetNode : sourceNode
    collectRelationItem(relationMap, otherNode, edge.kind)

    if (edge.kind === 'MODEL_REF') {
      const selectedField = sourceBelongsToApi ? sourceNode : targetNode
      collectDtoRelationForField(relationMap, selectedField, edge.kind)
      collectDtoRelationForField(relationMap, otherNode, edge.kind)
    }
  })

  return Array.from(relationMap.values())
    .sort((a, b) => {
      if (a.kind !== b.kind) return a.kind === 'API' ? -1 : 1
      return a.node.label.localeCompare(b.node.label, 'zh-Hans-CN')
    })
}

function collectRelationItem(map: Map<string, ApiRelationItem>, node: ApiGraphNode, edgeKind: string) {
  const relationNode = relationNodeFromGraphNode(node)
  if (!relationNode) return
  const key = `${relationNode.kind}-${relationNode.node.id}`
  if (!map.has(key)) {
    map.set(key, { ...relationNode, edgeKinds: [] })
  }
  const item = map.get(key)!
  const label = edgeKindLabel(edgeKind)
  if (!item.edgeKinds.includes(label)) item.edgeKinds.push(label)
}

function relationNodeFromGraphNode(node: ApiGraphNode): Pick<ApiRelationItem, 'kind' | 'node'> | null {
  if (node.kind === 'API') return { kind: 'API', node }
  if (node.kind === 'DTO') return { kind: 'DTO', node }

  const apiNodeId = resolveApiNodeId(node)
  if (apiNodeId && selectedNode.value?.id !== apiNodeId) {
    const apiNode = nodeMap.value.get(apiNodeId)
    if (apiNode?.kind === 'API') return { kind: 'API', node: apiNode }
  }

  const dtoNode = dtoNodeFromField(node)
  return dtoNode ? { kind: 'DTO', node: dtoNode } : null
}

function collectDtoRelationForField(map: Map<string, ApiRelationItem>, node: ApiGraphNode, edgeKind: string) {
  const dtoNode = dtoNodeFromField(node)
  if (!dtoNode) return
  collectRelationItem(map, dtoNode, edgeKind)
}

function dtoNodeFromField(node: ApiGraphNode) {
  if (node.kind !== 'FIELD_IN' && node.kind !== 'FIELD_OUT') return null
  const dtoName = simpleType(node.typeName)
  if (!dtoName) return null
  return snapshot.value.nodes.find((candidate) => candidate.kind === 'DTO' && candidate.label === dtoName) ?? null
}

function isNodeInApi(node: ApiGraphNode, apiNodeId: number) {
  if (node.id === apiNodeId) return true
  return resolveApiNodeId(node) === apiNodeId
}

function buildParamPathTree(fields: ApiGraphNode[], kind?: 'FIELD_IN' | 'FIELD_OUT'): ParamCascaderOption[] {
  const roots = new Map<string, ParamPathTreeNode>()
  fields
    .slice()
    .sort((a, b) => fieldPath(a).localeCompare(fieldPath(b), 'zh-Hans-CN'))
    .forEach((node) => {
      const segments = fieldPath(node).split('.').map((segment) => segment.trim()).filter(Boolean)
      const normalizedSegments = segments.length ? segments : [node.label]
      let cursor = roots
      let currentPath = ''
      normalizedSegments.forEach((segment, index) => {
        currentPath = currentPath ? `${currentPath}.${segment}` : segment
        if (!cursor.has(segment)) {
          cursor.set(segment, {
            segment,
            path: currentPath,
            children: new Map<string, ParamPathTreeNode>(),
          })
        }
        const treeNode = cursor.get(segment)!
        if (index === normalizedSegments.length - 1) {
          treeNode.node = node
        }
        cursor = treeNode.children
      })
    })
  return mapParamPathTreeToOptions(roots, kind)
}

function mapParamPathTreeToOptions(nodes: Map<string, ParamPathTreeNode>, kind?: 'FIELD_IN' | 'FIELD_OUT'): ParamCascaderOption[] {
  return Array.from(nodes.values())
    .sort((a, b) => a.segment.localeCompare(b.segment, 'zh-Hans-CN'))
    .map((treeNode) => {
      let children = mapParamPathTreeToOptions(treeNode.children, kind)

      // 如果 paramPath 树没有下级，但从 snapshot 的 parentId 关系中能找到子节点，也加入
      if (children.length === 0 && treeNode.node) {
        children = buildSnapshotChildrenOptions(treeNode.node.id, kind)
      }

      const type = treeNode.node ? shortType(treeNode.node.typeName || '-') : ''
      // 仅禁用「无图谱节点且无下级」的占位项。中间路径段若无节点但有子级，必须可展开，
      // 否则 el-cascader 在父级 disabled 时无法进入更深层（用户只能选到第一级 DTO/VO）。
      const disabled = !treeNode.node && children.length === 0
      return {
        value: treeNode.node?.id ?? `path-${treeNode.path}`,
        label: `${treeNode.segment}${type && type !== '-' ? `：${type}` : ''}`,
        disabled,
        ...(children.length ? { children } : {}),
      }
    })
}

/** 从 snapshot 的 parentId 关系中递归构建子级选项，支持任意深度 */
function buildSnapshotChildrenOptions(parentId: number, kind?: 'FIELD_IN' | 'FIELD_OUT'): ParamCascaderOption[] {
  const rawChildren = fieldChildrenByParent.value.get(parentId) ?? []
  const children = kind ? rawChildren.filter((c) => c.kind === kind) : rawChildren
  if (children.length === 0) return []
  return children
    .slice()
    .sort((a, b) => a.label.localeCompare(b.label, 'zh-Hans-CN'))
    .map((child) => {
      const grandChildren = buildSnapshotChildrenOptions(child.id, kind)
      return {
        value: child.id,
        label: `${child.label}${child.typeName && shortType(child.typeName) !== '-' ? `：${shortType(child.typeName)}` : ''}`,
        ...(grandChildren.length ? { children: grandChildren } : {}),
      }
    })
}

function resolveApiNodeId(node: ApiGraphNode) {
  if (node.refId && nodeMap.value.get(node.refId)?.kind === 'API') return node.refId

  let current: ApiGraphNode | undefined = node
  const visited = new Set<number>()
  while (current && !visited.has(current.id)) {
    visited.add(current.id)
    const parent: ApiGraphNode | undefined = current.parentId ? nodeMap.value.get(current.parentId) : undefined
    if (!parent) break
    if (parent.kind === 'API') return parent.id
    if (parent.refId && nodeMap.value.get(parent.refId)?.kind === 'API') return parent.refId
    current = parent
  }
  return null
}

function fieldPath(node: ApiGraphNode) {
  const props = parseProps(node)
  if (props.paramPath) return String(props.paramPath)

  const parts: string[] = [node.label]
  let current = node
  const visited = new Set<number>([node.id])
  while (current.parentId) {
    const parent = nodeMap.value.get(current.parentId)
    if (!parent || visited.has(parent.id) || parent.kind === 'API') break
    parts.unshift(parent.label)
    visited.add(parent.id)
    current = parent
  }
  return parts.join('.')
}

function findNearestAnchor(node: ApiGraphNode, anchorMap: Map<number, Point>) {
  let current: ApiGraphNode | undefined = node
  const visited = new Set<number>()
  while (current && !visited.has(current.id)) {
    visited.add(current.id)
    const parentId = current.parentId ?? current.refId
    if (!parentId) return null
    const anchored = anchorMap.get(parentId)
    if (anchored) {
      return {
        x: anchored.x + (node.kind === 'FIELD_IN' ? -8 : 8),
        y: anchored.y,
      }
    }
    current = nodeMap.value.get(parentId)
  }
  return null
}

function toEdgeVM(edge: ApiGraphEdge, anchorMap: Map<number, Point>): EdgeVM | null {
  const source = nodeMap.value.get(edge.sourceNodeId)
  const target = nodeMap.value.get(edge.targetNodeId)
  if (!source || !target) return null
  if (edge.kind === 'BELONGS_TO' && target.kind !== 'DTO') return null

  const sourcePoint = anchorMap.get(edge.sourceNodeId)
  const targetPoint = anchorMap.get(edge.targetNodeId)
  if (!sourcePoint || !targetPoint) return null

  const visualKind: EdgeVisualKind = edge.kind === 'BELONGS_TO' ? 'MODEL_REF' : edge.kind as EdgeVisualKind
  const { path, labelX, labelY } = composeGraphEdgePath(source, target, sourcePoint, targetPoint, {
    radius: 14,
    bendT: 0.5,
  })
  return {
    id: String(edge.id),
    raw: edge,
    visualKind,
    path,
    label: edgeLabel(edge, target),
    labelX,
    labelY,
  }
}

function edgeNodeLabel(nodeId: number) {
  const node = nodeMap.value.get(nodeId)
  return node ? node.label : `#${nodeId}`
}
</script>

<style scoped lang="scss">
@use './styles/ApiGraphCanvas.scss';
</style>

<style lang="scss">
@use './styles/ApiGraphCanvas.global.scss';
</style>
