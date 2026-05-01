<template>
  <div class="api-graph-canvas">
    <div class="toolbar">
      <el-button size="small" :loading="loading" @click="refresh">刷新</el-button>
      <el-button size="small" :loading="rebuilding" @click="rebuild">重建图谱</el-button>
      <el-button size="small" :loading="inferring" @click="inferModels">推断模型边</el-button>
      <el-button size="small" :loading="inferRR" @click="inferRequestResponse">推断请求/响应边</el-button>
      <el-divider direction="vertical" />
      <el-switch v-model="linkMode" active-text="连线模式" inactive-text="" size="small" />
      <el-switch v-model="showCandidates" active-text="候选边" inactive-text="" size="small" style="margin-left: 8px" />
      <span v-if="linkMode && linkSource" class="link-hint">已选源节点：{{ linkSource.label }}，点击目标节点完成连线</span>
      <span v-if="linkMode && !linkSource" class="link-hint">请点击源节点开始连线</span>
    </div>
    <div ref="containerRef" class="graph-container" />
    <div v-if="selectedEdge" class="edge-detail">
      <div class="edge-detail-header">
        <b>边详情</b>
        <el-button link size="small" @click="selectedEdge = null">关闭</el-button>
      </div>
      <p>类型：{{ selectedEdge.kind }}</p>
      <p>来源：{{ selectedEdge.source }}</p>
      <p>置信度：{{ selectedEdge.confidence ?? '-' }}</p>
      <p v-if="selectedEdge.note">备注：{{ selectedEdge.note }}</p>
      <p v-if="selectedEdge.status">状态：{{ selectedEdge.status }}</p>
      <div v-if="selectedEdge.status === 'CANDIDATE'" style="margin-top: 8px">
        <el-button size="small" type="success" @click="confirmEdge(selectedEdge!)">确认</el-button>
        <el-button size="small" type="danger" @click="rejectEdge(selectedEdge!)">拒绝</el-button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Graph, register, ExtensionCategory, DragCanvas, ZoomCanvas, ClickSelect, DragElement, Tooltip } from '@antv/g6'
import {
  confirmApiGraphCandidate,
  getApiGraphSnapshot,
  inferApiGraphModelEdges,
  inferApiGraphRequestResponseEdges,
  listApiGraphCandidates,
  rebuildApiGraph,
  rejectApiGraphCandidate,
  upsertApiGraphEdge,
  type ApiGraphEdge,
  type ApiGraphNode,
  type ApiGraphSnapshot,
} from '@/api/apiGraph'

const props = defineProps<{ projectId: number }>()

const containerRef = ref<HTMLDivElement>()
const loading = ref(false)
const rebuilding = ref(false)
const inferring = ref(false)
const inferRR = ref(false)
const linkMode = ref(false)
const showCandidates = ref(false)
const linkSource = ref<ApiGraphNode | null>(null)
const selectedEdge = ref<ApiGraphEdge | null>(null)

let graph: Graph | null = null
let registered = false

const EDGE_COLORS: Record<string, string> = {
  REQUEST_REF: '#3b82f6',
  RESPONSE_REF: '#22c55e',
  MODEL_REF: '#a855f7',
  BELONGS_TO: '#64748b',
}

const NODE_COLORS: Record<string, string> = {
  API: '#6366f1',
  FIELD_IN: '#3b82f6',
  FIELD_OUT: '#22c55e',
  DTO: '#a855f7',
  MODULE: '#64748b',
}

function ensureRegistered() {
  if (registered) return
  register(ExtensionCategory.BEHAVIOR, 'drag-canvas', DragCanvas)
  register(ExtensionCategory.BEHAVIOR, 'zoom-canvas', ZoomCanvas)
  register(ExtensionCategory.BEHAVIOR, 'click-select', ClickSelect)
  register(ExtensionCategory.BEHAVIOR, 'drag-element', DragElement)
  register(ExtensionCategory.PLUGIN, 'tooltip', Tooltip)
  registered = true
}

function buildGraphData(snapshot: ApiGraphSnapshot, candidates: ApiGraphEdge[]) {
  const posMap = new Map(snapshot.layouts.map((l) => [String(l.nodeId), { x: l.x, y: l.y }]))
  const nodeById = new Map(snapshot.nodes.map((n) => [n.id, n]))
  const allEdgesForLayout = [...snapshot.edges, ...candidates]

  // Separate nodes by kind for layout
  const apiNodes = snapshot.nodes.filter((n) => n.kind === 'API' || n.kind === 'MODULE')
  const fieldNodes = snapshot.nodes.filter((n) => n.kind === 'FIELD_IN' || n.kind === 'FIELD_OUT')
  const dtoNodes = snapshot.nodes.filter((n) => n.kind === 'DTO')

  // Build adjacency: which fields reference which DTOs
  const fieldToDtos = new Map<number, Set<number>>() // fieldId -> set of dtoIds
  const dtoToFields = new Map<number, Set<number>>() // dtoId -> set of fieldIds
  allEdgesForLayout.forEach((e) => {
    const sn = nodeById.get(e.sourceNodeId)
    const tn = nodeById.get(e.targetNodeId)
    if (!sn || !tn) return
    // field -> DTO or DTO -> field
    if ((sn.kind === 'FIELD_IN' || sn.kind === 'FIELD_OUT') && tn.kind === 'DTO') {
      if (!fieldToDtos.has(sn.id)) fieldToDtos.set(sn.id, new Set())
      fieldToDtos.get(sn.id)!.add(tn.id)
      if (!dtoToFields.has(tn.id)) dtoToFields.set(tn.id, new Set())
      dtoToFields.get(tn.id)!.add(sn.id)
    }
    if (sn.kind === 'DTO' && (tn.kind === 'FIELD_IN' || tn.kind === 'FIELD_OUT')) {
      if (!fieldToDtos.has(tn.id)) fieldToDtos.set(tn.id, new Set())
      fieldToDtos.get(tn.id)!.add(sn.id)
      if (!dtoToFields.has(sn.id)) dtoToFields.set(sn.id, new Set())
      dtoToFields.get(sn.id)!.add(tn.id)
    }
  })

  // Assign positions to API/MODULE nodes without saved layout
  let apiIdx = 0
  const assignedPos = new Map<string, { x: number; y: number }>()
  apiNodes.forEach((n) => {
    const pos = posMap.get(String(n.id))
    if (pos) {
      assignedPos.set(String(n.id), pos)
    } else {
      const col = apiIdx % 4
      const row = Math.floor(apiIdx / 4)
      assignedPos.set(String(n.id), { x: 200 + col * 280, y: 120 + row * 240 })
      apiIdx++
    }
  })

  // Position field nodes around their parent API/DTO node
  const parentChildCount = new Map<number, number>()
  fieldNodes.forEach((n) => {
    const pos = posMap.get(String(n.id))
    if (pos) {
      assignedPos.set(String(n.id), pos)
      return
    }
    const parentId = n.parentId || n.refId
    if (!parentId) {
      assignedPos.set(String(n.id), { x: 0, y: 0 })
      return
    }
    const parentPos = assignedPos.get(String(parentId))
    if (!parentPos) {
      assignedPos.set(String(n.id), { x: 0, y: 0 })
      return
    }
    const idx = parentChildCount.get(parentId) || 0
    parentChildCount.set(parentId, idx + 1)
    const isInput = n.kind === 'FIELD_IN'
    const offsetX = isInput ? -120 : 120
    const offsetY = -60 + idx * 36
    assignedPos.set(String(n.id), { x: parentPos.x + offsetX, y: parentPos.y + offsetY })
  })

  // Position DTO nodes near the fields that reference them
  const dtoAssigned = new Set<number>()
  dtoNodes.forEach((n) => {
    const pos = posMap.get(String(n.id))
    if (pos) {
      assignedPos.set(String(n.id), pos)
      dtoAssigned.add(n.id)
      return
    }
    const linkedFields = dtoToFields.get(n.id)
    if (linkedFields && linkedFields.size > 0) {
      // Average position of linked fields, then offset
      let sumX = 0, sumY = 0, count = 0
      linkedFields.forEach((fid) => {
        const fp = assignedPos.get(String(fid))
        if (fp) { sumX += fp.x; sumY += fp.y; count++ }
      })
      if (count > 0) {
        assignedPos.set(String(n.id), { x: sumX / count + 160, y: sumY / count + 20 })
        dtoAssigned.add(n.id)
        return
      }
    }
    // Fallback: place near a random API node
    const fallback = assignedPos.get(String(apiNodes[0]?.id)) || { x: 400, y: 300 }
    assignedPos.set(String(n.id), { x: fallback.x + 200, y: fallback.y + 100 })
  })

  const nodes: any[] = snapshot.nodes.map((n) => {
    const pos = assignedPos.get(String(n.id))
    return {
      id: String(n.id),
      data: { ...n },
      style: {
        x: pos?.x,
        y: pos?.y,
        labelText: n.label,
        iconText: n.kind === 'API' ? 'API' : n.kind === 'DTO' ? 'DTO' : n.kind === 'MODULE' ? 'M' : n.kind === 'FIELD_IN' ? 'IN' : 'OUT',
        iconFill: '#fff',
        iconFontSize: 10,
        fill: NODE_COLORS[n.kind] || '#64748b',
        radius: n.kind === 'API' ? 8 : 4,
        size: n.kind === 'API' ? 48 : n.kind === 'MODULE' ? 40 : n.kind === 'DTO' ? 36 : 24,
        labelFontSize: 11,
        labelPlacement: 'bottom',
      },
    }
  })

  const allEdges = [...snapshot.edges, ...candidates]

  // Auto-generate BELONGS_TO edges for field nodes that have a parent but no edge
  const existingEdgePairs = new Set(allEdges.map((e) => `${e.sourceNodeId}->${e.targetNodeId}`))
  fieldNodes.forEach((n) => {
    const parentId = n.parentId || n.refId
    if (parentId && !existingEdgePairs.has(`${n.id}->${parentId}`) && !existingEdgePairs.has(`${parentId}->${n.id}`)) {
      allEdges.push({
        id: -n.id, // synthetic id
        projectId: props.projectId,
        sourceNodeId: n.id,
        targetNodeId: parentId,
        kind: 'BELONGS_TO',
        source: 'auto',
        confidence: 1,
        status: 'CONFIRMED',
        inferStrategy: null,
        confirmedBy: null,
        confirmedAt: null,
        rejectReason: null,
        evidenceJson: null,
        note: null,
        enabled: true,
      })
    }
  })

  const edges: any[] = allEdges.map((e) => ({
    id: String(e.id),
    source: String(e.sourceNodeId),
    target: String(e.targetNodeId),
    data: { ...e },
    style: {
      stroke: EDGE_COLORS[e.kind] || '#64748b',
      lineWidth: e.status === 'CANDIDATE' ? 1 : 2,
      lineDash: e.kind === 'MODEL_REF' || e.status === 'CANDIDATE' ? [6, 4] : undefined,
      opacity: e.status === 'CANDIDATE' ? 0.5 : e.enabled ? 1 : 0.3,
      endArrow: true,
    },
  }))

  return { nodes, edges, nodeMap: new Map(snapshot.nodes.map((n) => [n.id, n])) }
}

async function loadGraph() {
  if (!containerRef.value) return
  loading.value = true
  try {
    const [{ data: snapshot }, { data: candidates }] = await Promise.all([
      getApiGraphSnapshot(props.projectId),
      showCandidates.value ? listApiGraphCandidates(props.projectId) : Promise.resolve({ data: [] as ApiGraphEdge[] }),
    ])

    const { nodes, edges, nodeMap } = buildGraphData(snapshot, candidates)

    if (graph) {
      graph.setData({ nodes, edges })
      await graph.render()
    } else {
      ensureRegistered()
      graph = new Graph({
        container: containerRef.value,
        data: { nodes, edges },
        autoFit: 'view',
        padding: 60,
        behaviors: [
          'drag-canvas',
          'zoom-canvas',
          'drag-element',
          {
            type: 'click-select',
            key: 'node-click',
            enable: true,
          },
        ],
        plugins: [
          {
            type: 'tooltip',
            key: 'tooltip',
            getContent: (e: any) => {
              const d = e?.data
              if (!d) return ''
              if (d.label) {
                return `<div style="padding:4px 8px;font-size:12px"><b>${d.label}</b><br/>${d.kind || ''}${d.typeName ? ' · ' + d.typeName : ''}</div>`
              }
              return `<div style="padding:4px 8px;font-size:12px">${d.kind || ''} · ${d.source || ''} · conf: ${d.confidence ?? '-'}</div>`
            },
          },
        ],
      })

      graph.on('node:click', (e: any) => {
        const nodeId = e.target?.id
        if (!nodeId) return
        if (linkMode.value) {
          const node = nodeMap.get(Number(nodeId))
          if (!node) return
          if (!linkSource.value) {
            linkSource.value = node
          } else if (linkSource.value.id !== node.id) {
            createEdge(linkSource.value, node)
            linkSource.value = null
          }
        }
      })

      graph.on('edge:click', (e: any) => {
        const edgeData = edges.find((ed) => ed.id === e.target?.id)?.data as ApiGraphEdge | undefined
        if (edgeData) selectedEdge.value = edgeData
      })

      await graph.render()
    }
  } catch {
    ElMessage.error('加载接口图谱失败')
  } finally {
    loading.value = false
  }
}

async function createEdge(source: ApiGraphNode, target: ApiGraphNode) {
  try {
    await upsertApiGraphEdge(props.projectId, {
      sourceNodeId: source.id,
      targetNodeId: target.id,
      kind: 'REQUEST_REF',
      note: 'manual',
    })
    ElMessage.success('连线成功')
    await loadGraph()
  } catch {
    ElMessage.error('连线失败')
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

async function refresh() {
  await loadGraph()
}

async function rebuild() {
  rebuilding.value = true
  try {
    await rebuildApiGraph(props.projectId)
    ElMessage.success('图谱重建完成')
    await loadGraph()
  } catch {
    ElMessage.error('重建失败')
  } finally {
    rebuilding.value = false
  }
}

async function inferModels() {
  inferring.value = true
  try {
    const { data } = await inferApiGraphModelEdges(props.projectId)
    ElMessage.success(`推断完成，生成 ${data.generated} 条模型边`)
    await loadGraph()
  } catch {
    ElMessage.error('推断失败')
  } finally {
    inferring.value = false
  }
}

async function inferRequestResponse() {
  inferRR.value = true
  try {
    const { data } = await inferApiGraphRequestResponseEdges(props.projectId)
    ElMessage.success(`推断完成，生成 ${data.generated} 条请求/响应边`)
    await loadGraph()
  } catch {
    ElMessage.error('推断失败')
  } finally {
    inferRR.value = false
  }
}

watch(() => props.projectId, loadGraph)
watch(showCandidates, loadGraph)

onMounted(loadGraph)
onBeforeUnmount(() => {
  graph?.destroy()
  graph = null
})
</script>

<style scoped lang="scss">
.api-graph-canvas {
  position: relative;
}
.toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  flex-wrap: wrap;
}
.link-hint {
  font-size: 12px;
  color: var(--text-secondary);
  margin-left: 8px;
}
.graph-container {
  width: 100%;
  height: 520px;
  border: 1px solid var(--border-glass);
  border-radius: var(--radius-sm);
  background: var(--bg-tertiary);
}
.edge-detail {
  position: absolute;
  top: 48px;
  right: 0;
  width: 240px;
  background: var(--bg-secondary);
  border: 1px solid var(--border-glass);
  border-radius: var(--radius-sm);
  padding: 12px;
  font-size: 13px;
  color: var(--text-primary);
  box-shadow: var(--shadow-card);
  z-index: 10;
}
.edge-detail-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}
</style>
