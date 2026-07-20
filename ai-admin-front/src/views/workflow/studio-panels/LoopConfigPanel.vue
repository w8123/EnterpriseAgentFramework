<template>
  <div class="node-specific-panel">
    <el-divider>FOREACH 循环（有界串行）</el-divider>
    <el-form-item label="集合表达式">
      <el-input v-model="config.collection" placeholder="例如：var.items / nodeOutput.assign" />
    </el-form-item>
    <el-form-item label="item 别名">
      <el-input v-model="config.itemAlias" placeholder="item" />
    </el-form-item>
    <el-form-item label="index 别名">
      <el-input v-model="config.indexAlias" placeholder="index" />
    </el-form-item>
    <el-form-item label="输出别名">
      <el-input v-model="config.outputAlias" placeholder="loop_results → var.loop_results" />
    </el-form-item>
    <el-form-item label="循环体输出">
      <el-input v-model="config.bodyOutput" placeholder="lastOutput / var.rendered" />
    </el-form-item>
    <el-form-item label="最大次数">
      <el-input-number v-model="config.maxIterations" :min="1" :max="1000" />
    </el-form-item>
    <el-form-item label="循环体节点">
      <el-select
        v-model="config.bodyNodeIds"
        multiple
        filterable
        clearable
        placeholder="选择属于本 LOOP 的画布节点"
        style="width: 100%"
      >
        <el-option
          v-for="item in candidateNodes"
          :key="item.id"
          :label="item.label"
          :value="item.id"
        />
      </el-select>
      <div class="hint">候选已排除 START/END、其他 LOOP、INTERACTION、HUMAN_APPROVAL 以及已被其他 LOOP 占用的节点。</div>
    </el-form-item>
    <el-form-item label="循环体入口">
      <el-select v-model="config.bodyEntry" clearable filterable placeholder="从循环体节点中选择" style="width: 100%">
        <el-option
          v-for="item in selectedBodyOptions"
          :key="item.id"
          :label="item.label"
          :value="item.id"
        />
      </el-select>
    </el-form-item>
    <el-form-item label="循环体出口">
      <el-select v-model="config.bodyExit" clearable filterable placeholder="从循环体节点中选择" style="width: 100%">
        <el-option
          v-for="item in selectedBodyOptions"
          :key="item.id"
          :label="item.label"
          :value="item.id"
        />
      </el-select>
    </el-form-item>
  </div>
</template>

<script setup lang="ts">
import { computed, watch } from 'vue'
import type { CanvasNode, CanvasNodeData, LoopNodeConfig } from '@/types/studio'
import { listLoopBodyCandidates, syncLoopBodyMembership } from '@/utils/studioLoop'

const props = defineProps<{
  data: CanvasNodeData
  nodeId?: string
  canvasNodes?: CanvasNode[]
}>()

const config = computed<LoopNodeConfig>(() => {
  props.data.loopConfig ||= {
    mode: 'FOREACH',
    collection: '',
    itemAlias: 'item',
    indexAlias: 'index',
    outputAlias: 'loop_results',
    bodyOutput: 'lastOutput',
    maxIterations: 100,
    bodyEntry: '',
    bodyExit: '',
    bodyNodeIds: [],
  }
  const loop = props.data.loopConfig
  loop.mode = 'FOREACH'
  loop.itemAlias ||= 'item'
  loop.indexAlias ||= 'index'
  loop.outputAlias ||= 'loop_results'
  loop.bodyOutput ||= 'lastOutput'
  if (!loop.maxIterations || loop.maxIterations < 1) loop.maxIterations = 100
  if (loop.maxIterations > 1000) loop.maxIterations = 1000
  if (!Array.isArray(loop.bodyNodeIds)) loop.bodyNodeIds = []
  if (!loop.collection && loop.itemExpression) {
    loop.collection = loop.itemExpression
  }
  return loop
})

const candidateNodes = computed(() => {
  const base = listLoopBodyCandidates({
    loopNodeId: props.nodeId || '',
    nodes: props.canvasNodes || [],
  })
  const byId = new Map(base.map((item) => [item.id, item]))
  for (const id of config.value.bodyNodeIds || []) {
    if (byId.has(id)) continue
    const canvasNode = (props.canvasNodes || []).find((item) => item.id === id)
    if (!canvasNode) continue
    byId.set(id, {
      id,
      kind: canvasNode.data.kind,
      label: `${canvasNode.data.label || id} · ${canvasNode.data.kind} · ${id}`,
    })
  }
  return Array.from(byId.values())
})

const selectedBodyOptions = computed(() => {
  const selected = new Set(config.value.bodyNodeIds || [])
  return candidateNodes.value.filter((item) => selected.has(item.id))
})

watch(
  () => [props.nodeId, props.canvasNodes, config.value.bodyNodeIds, config.value.bodyEntry, config.value.bodyExit] as const,
  () => {
    const synced = syncLoopBodyMembership({
      loopNodeId: props.nodeId || '',
      nodes: props.canvasNodes || [],
      loopConfig: config.value,
    })
    Object.assign(config.value, synced)
  },
  { deep: true, immediate: true },
)
</script>

<style scoped>
.hint {
  margin-top: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.4;
}
</style>
