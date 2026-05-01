<template>
  <div class="trace-timeline">
    <el-empty v-if="!nodes.length" description="暂无 Trace 节点" />
    <el-timeline v-else>
      <el-timeline-item
        v-for="group in groupedNodes"
        :key="group.parent.id"
        :timestamp="group.parent.createdAt || ''"
        placement="top"
      >
        <div class="node-header">
          <el-tag
            size="small"
            :type="tagTypeForNode(group.parent)"
          >
            {{ traceNodeTitle(group.parent.toolName) }}
          </el-tag>
          <el-tag v-if="isInternalTraceSpan(group.parent.toolName)" size="small" type="info" effect="plain">
            {{ group.parent.toolName }}
          </el-tag>
          <span class="meta">{{ group.parent.agentName || '-' }}</span>
          <span class="meta">{{ group.parent.elapsedMs || 0 }}ms</span>
          <span class="meta">token: {{ group.parent.tokenCost || 0 }}</span>
          <el-tag v-if="group.children.length" size="small" type="info">
            子调用 {{ group.children.length }}
          </el-tag>
        </div>
        <el-collapse>
          <el-collapse-item :title="collapseDetailTitle(group.parent)">
            <div class="block">
              <b>{{ argLabel(group.parent) }}</b>
              <pre :class="{ 'pre-trace-span': isInternalTraceSpan(group.parent.toolName) }">{{ prettyJson(group.parent.argsJson) }}</pre>
            </div>
            <div class="block">
              <b>{{ resultLabel(group.parent) }}</b>
              <pre :class="{ 'pre-trace-span': isInternalTraceSpan(group.parent.toolName) }">{{ prettyJson(group.parent.resultSummary) }}</pre>
            </div>
            <div v-if="!isInternalTraceSpan(group.parent.toolName)" class="block">
              <b>召回 top-k（与本 trace 共享快照）：</b>
              <pre>{{ JSON.stringify(group.parent.retrievalCandidates || [], null, 2) }}</pre>
            </div>
          </el-collapse-item>
          <el-collapse-item
            v-if="group.children.length"
            :title="`子 Agent 调用链（${group.children[0].agentName}）`"
          >
            <div
              v-for="child in group.children"
              :key="child.id"
              class="child-node"
            >
              <div class="node-header">
                <el-tag size="small" :type="tagTypeForNode(child)">
                  {{ traceNodeTitle(child.toolName) }}
                </el-tag>
                <el-tag v-if="isInternalTraceSpan(child.toolName)" size="small" type="info" effect="plain">
                  {{ child.toolName }}
                </el-tag>
                <span class="meta">{{ child.elapsedMs || 0 }}ms</span>
                <span class="meta">token: {{ child.tokenCost || 0 }}</span>
                <span class="meta">{{ child.createdAt }}</span>
              </div>
              <el-collapse>
                <el-collapse-item :title="collapseDetailTitle(child)">
                  <div class="block">
                    <b>{{ argLabel(child) }}</b>
                    <pre :class="{ 'pre-trace-span': isInternalTraceSpan(child.toolName) }">{{ prettyJson(child.argsJson) }}</pre>
                  </div>
                  <div class="block">
                    <b>{{ resultLabel(child) }}</b>
                    <pre :class="{ 'pre-trace-span': isInternalTraceSpan(child.toolName) }">{{ prettyJson(child.resultSummary) }}</pre>
                  </div>
                  <div v-if="!isInternalTraceSpan(child.toolName)" class="block">
                    <b>召回 top-k（与本 trace 共享快照）：</b>
                    <pre>{{ JSON.stringify(child.retrievalCandidates || [], null, 2) }}</pre>
                  </div>
                </el-collapse-item>
              </el-collapse>
            </div>
          </el-collapse-item>
        </el-collapse>
      </el-timeline-item>
    </el-timeline>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { TraceNode } from '@/types/trace'
import { isInternalTraceSpan, traceNodeTitle } from '@/utils/traceLabels'

const props = defineProps<{
  nodes: TraceNode[]
}>()

interface NodeGroup {
  parent: TraceNode
  children: TraceNode[]
}

/**
 * 折叠规则：
 * - agentName 以 "skill:" 开头的节点视为子 Agent 调用，归到最近一个非 skill: 父节点下；
 * - 第一条节点一定是父级（哪怕它自己是 skill:xxx，也没有更早的父可以归）；
 * - 父节点保留 tool / agent / 延迟 / token 头信息，子链单独一个折叠块展示。
 *
 * 设计动机：SubAgentSkillExecutor 里把子 context.agentName 强制设成
 * "skill:" + skillName，同一个 traceId 下父子节点只差前缀，这里用前缀判定足够。
 */
const groupedNodes = computed<NodeGroup[]>(() => {
  const groups: NodeGroup[] = []
  for (const node of props.nodes) {
    const isChild = (node.agentName || '').startsWith('skill:')
    if (isChild && groups.length > 0) {
      groups[groups.length - 1].children.push(node)
    } else {
      groups.push({ parent: node, children: [] })
    }
  }
  return groups
})

/**
 * 后端 args_json/result_summary 是紧凑 JSON 字符串或普通文本；
 * 能解析成 JSON 就 pretty-print，否则原样返回，避免抽屉里一行长文本。
 */
function tagTypeForNode(node: TraceNode): 'success' | 'danger' {
  return node.success ? 'success' : 'danger'
}

function collapseDetailTitle(node: TraceNode): string {
  if (isInternalTraceSpan(node.toolName)) return '入参 / 出参（详细）'
  return 'Tool 入参 / 出参 / 召回'
}

function argLabel(node: TraceNode): string {
  if (node.toolName === '_trace:embedding.encode') return '向量化请求摘要（query、provider、model）：'
  if (node.toolName.startsWith('_trace:llm.stream#')) return '大模型请求（消息历史摘要、可用 tools、generateOptions）：'
  if (node.toolName === '_trace:milvus.tool_search') return 'Milvus 检索条件：'
  if (node.toolName === '_trace:agentscope.run') return '本次 AgentScope 调用输入：'
  return '入参（args）：'
}

function resultLabel(node: TraceNode): string {
  if (node.toolName === '_trace:embedding.encode') return '向量化结果（维度、向量预览）：'
  if (node.toolName.startsWith('_trace:llm.stream#')) return '大模型输出（合并流式片段：assistant 文本、toolCalls、finishReason）：'
  if (node.toolName === '_trace:milvus.tool_search') return 'Milvus 命中结果：'
  if (node.toolName === '_trace:agentscope.run') return '最终输出与元数据：'
  return '出参（result）：'
}

function prettyJson(raw?: string | null): string {
  if (raw == null || raw === '') return '-'
  const trimmed = raw.trim()
  if (!(trimmed.startsWith('{') || trimmed.startsWith('['))) return raw
  try {
    return JSON.stringify(JSON.parse(trimmed), null, 2)
  } catch {
    return raw
  }
}
</script>

<style scoped lang="scss">
.node-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  flex-wrap: wrap;
}
.meta {
  font-size: 12px;
  color: var(--text-muted);
}
.block {
  margin-bottom: 8px;
}
.child-node {
  border-left: 2px solid var(--border-glass);
  padding-left: 12px;
  margin-bottom: 10px;
}
pre {
  margin-top: 4px;
  background: var(--bg-tertiary);
  border: 1px solid var(--border-glass);
  border-radius: 4px;
  padding: 8px;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 200px;
  overflow: auto;
  color: var(--text-primary);
}
.pre-trace-span {
  max-height: 480px;
}
</style>
