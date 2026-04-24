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
          <el-tag size="small" :type="group.parent.success ? 'success' : 'danger'">
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
          <el-collapse-item title="参数 / 结果 / 召回">
            <div class="block">
              <b>args:</b>
              <pre>{{ prettyJson(group.parent.argsJson) }}</pre>
            </div>
            <div class="block">
              <b>result:</b>
              <pre>{{ prettyJson(group.parent.resultSummary) }}</pre>
            </div>
            <div class="block">
              <b>retrieval top-k:</b>
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
                <el-tag size="small" :type="child.success ? 'success' : 'danger'">
                  {{ child.toolName }}
                </el-tag>
                <span class="meta">{{ child.elapsedMs || 0 }}ms</span>
                <span class="meta">token: {{ child.tokenCost || 0 }}</span>
                <span class="meta">{{ child.createdAt }}</span>
              </div>
              <el-collapse>
                <el-collapse-item title="参数 / 结果 / 召回">
                  <div class="block">
                    <b>args:</b>
                    <pre>{{ prettyJson(child.argsJson) }}</pre>
                  </div>
                  <div class="block">
                    <b>result:</b>
                    <pre>{{ prettyJson(child.resultSummary) }}</pre>
                  </div>
                  <div class="block">
                    <b>retrieval top-k:</b>
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
  color: #909399;
}
.block {
  margin-bottom: 8px;
}
.child-node {
  border-left: 2px solid #e4e7ed;
  padding-left: 12px;
  margin-bottom: 10px;
}
pre {
  margin-top: 4px;
  background: #fafafa;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  padding: 8px;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 200px;
  overflow: auto;
}
</style>
