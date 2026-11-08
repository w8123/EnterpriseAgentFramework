<template>
  <div class="node-specific-panel">
    <ConfigSection title="循环方式" description="逐项处理列表，或重复执行直到满足退出条件">
      <el-form-item label="方式">
        <el-radio-group v-model="loopMode">
          <el-radio value="FOREACH">逐项处理列表</el-radio>
          <el-radio value="WHILE">按条件重复</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item v-if="config.mode !== 'WHILE'" label="集合表达式">
        <el-input v-model="config.collection" placeholder="例如：var.items / nodeOutput.assign" />
      </el-form-item>
      <el-form-item label="循环体节点">
        <el-select
          v-model="config.bodyNodeIds"
          multiple
          filterable
          clearable
          placeholder="选择属于本循环的画布节点"
          style="width: 100%"
        >
          <el-option v-for="item in candidateNodes" :key="item.id" :label="item.label" :value="item.id" />
        </el-select>
        <div class="hint">
          候选已排除开始/结束、其他循环、并行分支、交互、人工确认以及已被其他循环占用的节点。
        </div>
      </el-form-item>
      <el-form-item label="循环体入口">
        <el-select v-model="config.bodyEntry" clearable filterable placeholder="从循环体节点中选择" style="width: 100%">
          <el-option v-for="item in selectedBodyOptions" :key="item.id" :label="item.label" :value="item.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="循环体出口">
        <el-select v-model="config.bodyExit" clearable filterable placeholder="从循环体节点中选择" style="width: 100%">
          <el-option v-for="item in selectedBodyOptions" :key="item.id" :label="item.label" :value="item.id" />
        </el-select>
      </el-form-item>
    </ConfigSection>
    <ConfigSection
      v-if="config.mode === 'WHILE'"
      title="循环变量与退出条件"
      description="每轮结束后检查退出条件；变量在各轮之间保留"
    >
      <div class="field-table-head">
        <strong>循环变量</strong>
        <el-button size="small" type="primary" plain @click="addVariable">添加变量</el-button>
      </div>
      <div v-for="(item, index) in config.loopVariables" :key="index" class="condition-row">
        <el-input v-model="item.name" placeholder="变量名，如 page（写入 var.page）" />
        <el-input v-model="item.value" placeholder="初始值，如 1 或 {{ params.start }}" />
        <el-button text type="danger" @click="config.loopVariables!.splice(index, 1)">删除</el-button>
      </div>
      <div class="hint">循环体里用「变量赋值」节点更新这些变量（目标写 var.变量名），每轮结束后再检查退出条件。</div>
      <div class="field-table-head">
        <strong>退出条件</strong>
        <el-select v-model="breakLogic" style="width: 120px">
          <el-option label="全部满足" value="AND" />
          <el-option label="任一满足" value="OR" />
        </el-select>
        <el-button size="small" type="primary" plain @click="addBreakCondition">添加条件</el-button>
      </div>
      <div v-for="(item, index) in config.breakConditions" :key="`b-${index}`" class="condition-row">
        <el-input v-model="item.left" placeholder="左值：var.done" />
        <el-select v-model="item.operator" style="width: 150px">
          <el-option v-for="op in operators" :key="op.value" :label="op.label" :value="op.value" />
        </el-select>
        <el-input v-model="item.right" placeholder="右值" />
        <el-button text type="danger" @click="config.breakConditions!.splice(index, 1)">删除</el-button>
      </div>
      <div v-if="!config.breakConditions?.length" class="panel-empty-row">
        还没有退出条件。按条件重复必须至少有一个退出条件，否则只会跑满最大轮数。
      </div>
    </ConfigSection>
    <ConfigSection title="执行与结果" description="设置并行、出错处理、输出和次数上限" collapsible>
      <el-form-item v-if="config.mode !== 'WHILE'" label="同时处理">
        <el-input-number v-model="parallelism" :min="1" :max="4" />
        <span class="hint-inline">项（1 为逐项串行，最多 4）</span>
      </el-form-item>
      <el-form-item v-if="config.mode !== 'WHILE'" label="某项出错时">
        <el-radio-group v-model="errorMode">
          <el-radio value="TERMINATE">停止循环</el-radio>
          <el-radio value="SKIP">跳过该项</el-radio>
          <el-radio value="KEEP">保留错误信息</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item v-if="config.mode !== 'WHILE'" label="当前项变量名">
        <el-input v-model="config.itemAlias" placeholder="item" />
      </el-form-item>
      <el-form-item :label="config.mode === 'WHILE' ? '轮次变量名' : '索引变量名'">
        <el-input v-model="config.indexAlias" placeholder="index" />
      </el-form-item>
      <el-form-item label="输出别名">
        <el-input v-model="config.outputAlias" placeholder="loop_results → var.loop_results" />
      </el-form-item>
      <el-form-item label="循环体输出">
        <el-input v-model="config.bodyOutput" placeholder="lastOutput / var.rendered" />
      </el-form-item>
      <el-form-item :label="config.mode === 'WHILE' ? '最多轮数' : '最大次数'">
        <el-input-number v-model="config.maxIterations" :min="1" :max="config.mode === 'WHILE' ? 50 : 1000" />
      </el-form-item>
    </ConfigSection>
  </div>
</template>

<script setup lang="ts">
import ConfigSection from './ConfigSection.vue'
import { computed, watch } from 'vue'
import type { CanvasNode, CanvasNodeData, LoopNodeConfig } from '@/types/studio'
import { listLoopBodyCandidates, syncLoopBodyMembership } from '@/utils/studioLoop'
import { CONDITION_OPERATORS } from './conditionOperators'

const props = defineProps<{
  data: CanvasNodeData
  nodeId?: string
  canvasNodes?: CanvasNode[]
}>()

const operators = CONDITION_OPERATORS

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
  if (loop.mode !== 'WHILE') loop.mode = 'FOREACH'
  loop.itemAlias ||= 'item'
  loop.indexAlias ||= 'index'
  loop.outputAlias ||= 'loop_results'
  loop.bodyOutput ||= 'lastOutput'
  const ceiling = loop.mode === 'WHILE' ? 50 : 1000
  if (!loop.maxIterations || loop.maxIterations < 1) loop.maxIterations = loop.mode === 'WHILE' ? 10 : 100
  if (loop.maxIterations > ceiling) loop.maxIterations = ceiling
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

const loopMode = computed({
  get: () => config.value.mode,
  set: (value: LoopNodeConfig['mode']) => {
    const loop = config.value
    loop.mode = value
    if (value === 'WHILE') {
      loop.parallelism = 1
      loop.errorMode = 'TERMINATE'
      loop.breakLogic ||= 'AND'
      if (loop.maxIterations > 50) loop.maxIterations = 10
    }
  },
})
const parallelism = computed({
  get: () => config.value.parallelism || 1,
  set: (value: number | undefined) => { config.value.parallelism = Math.max(1, Math.min(4, value || 1)) },
})
const errorMode = computed({
  get: () => config.value.errorMode || 'TERMINATE',
  set: (value: NonNullable<LoopNodeConfig['errorMode']>) => { config.value.errorMode = value },
})
const breakLogic = computed({
  get: () => config.value.breakLogic || 'AND',
  set: (value: NonNullable<LoopNodeConfig['breakLogic']>) => { config.value.breakLogic = value },
})

function addVariable() {
  config.value.loopVariables = [...(config.value.loopVariables || []), { name: '', value: '' }]
}

function addBreakCondition() {
  config.value.breakConditions = [
    ...(config.value.breakConditions || []),
    { left: 'var.done', operator: 'equals', right: 'true' },
  ]
}

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
    const current = config.value
    current.mode = synced.mode
    current.maxIterations = synced.maxIterations
    current.bodyEntry = synced.bodyEntry
    current.bodyExit = synced.bodyExit
    if (JSON.stringify(current.bodyNodeIds) !== JSON.stringify(synced.bodyNodeIds)) {
      current.bodyNodeIds = synced.bodyNodeIds
    }
  },
  { deep: true, immediate: true },
)
</script>

<style scoped>
.hint-inline {
  margin-left: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.hint {
  margin-top: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.4;
}
</style>
