<template>
  <div class="trace-timeline" :class="{ 'is-loading': loading }">
    <div v-if="loading" class="trace-loading" aria-busy="true" aria-live="polite">
      <div v-for="index in 3" :key="index" class="trace-skeleton-card">
        <div class="trace-skeleton-line is-title" />
        <div class="trace-skeleton-line is-meta" />
        <div class="trace-skeleton-line is-meta short" />
      </div>
    </div>

    <div v-else-if="!nodes.length" class="trace-empty">
      <strong>暂无 Trace 节点</strong>
      <span>本轮执行尚未产生可回放的结构化链路数据。</span>
    </div>

    <template v-else>
      <div class="trace-overview" aria-label="Trace 概览">
        <div class="trace-overview__item">
          <span class="trace-overview__label">节点</span>
          <strong class="trace-overview__value">{{ overview.nodeCount }}</strong>
        </div>
        <div class="trace-overview__item">
          <span class="trace-overview__label">成功</span>
          <strong class="trace-overview__value is-success">{{ overview.successCount }}</strong>
        </div>
        <div class="trace-overview__item">
          <span class="trace-overview__label">异常</span>
          <strong
            class="trace-overview__value"
            :class="{ 'is-danger': overview.errorCount > 0 }"
          >
            {{ overview.errorCount }}
          </strong>
        </div>
        <div class="trace-overview__item">
          <span class="trace-overview__label">Token 合计</span>
          <strong class="trace-overview__value">{{ overview.tokenTotal }}</strong>
        </div>
        <div class="trace-overview__item">
          <span class="trace-overview__label">最长耗时</span>
          <strong class="trace-overview__value">{{ formatMs(overview.maxElapsedMs) }}</strong>
        </div>
      </div>

      <ol class="trace-node-list">
        <li
          v-for="group in groupedNodes"
          :key="nodeKey(group.parent)"
          class="trace-node-group"
        >
          <article
            class="trace-card"
            :class="{
              'is-success': group.parent.success,
              'is-error': !group.parent.success,
              'is-expanded': isExpanded(nodeKey(group.parent)),
            }"
          >
            <div class="trace-card__main">
              <div
                class="trace-card__status"
                :class="group.parent.success ? 'is-success' : 'is-error'"
                :aria-label="group.parent.success ? '成功' : '失败'"
                :title="group.parent.success ? '成功' : '失败'"
              >
                <el-icon>
                  <CircleCheckFilled v-if="group.parent.success" />
                  <CircleCloseFilled v-else />
                </el-icon>
              </div>

              <div class="trace-card__body">
                <div class="trace-card__topline">
                  <div class="trace-card__titles">
                    <h3 class="trace-card__title" :title="traceNodeTitle(group.parent.toolName)">
                      {{ traceNodeTitle(group.parent.toolName) }}
                    </h3>
                    <code
                      v-if="traceNodeTitle(group.parent.toolName) !== group.parent.toolName"
                      class="trace-card__tool"
                      :title="group.parent.toolName"
                    >
                      {{ group.parent.toolName }}
                    </code>
                  </div>
                  <div class="trace-card__metrics">
                    <span class="trace-metric is-elapsed">{{ formatMs(group.parent.elapsedMs) }}</span>
                    <span class="trace-metric is-token">token {{ group.parent.tokenCost || 0 }}</span>
                  </div>
                </div>

                <div v-if="metaChips(group.parent).length" class="trace-card__meta">
                  <el-tooltip
                    v-for="chip in metaChips(group.parent)"
                    :key="chip.key"
                    :content="chip.label"
                    placement="top"
                    :show-after="400"
                  >
                    <span class="trace-chip" :class="{ 'is-mono': chip.mono }">{{ chip.label }}</span>
                  </el-tooltip>
                </div>

                <div v-if="!group.parent.success && group.parent.errorCode" class="trace-card__error">
                  <span class="trace-card__error-label">errorCode</span>
                  <code>{{ group.parent.errorCode }}</code>
                </div>

                <div v-if="group.parent.createdAt" class="trace-card__time">
                  {{ formatTimestamp(group.parent.createdAt) }}
                </div>

                <div class="trace-card__actions">
                  <button
                    type="button"
                    class="trace-detail-toggle"
                    :aria-expanded="isExpanded(nodeKey(group.parent)) ? 'true' : 'false'"
                    @click="toggleExpanded(nodeKey(group.parent))"
                  >
                    <el-icon class="trace-detail-toggle__chevron"><ArrowRight /></el-icon>
                    <span>{{ collapseDetailTitle(group.parent) }}</span>
                    <span class="trace-detail-toggle__hint">
                      {{ isExpanded(nodeKey(group.parent)) ? '收起' : '展开' }}
                    </span>
                  </button>
                </div>
              </div>
            </div>

            <div v-if="isExpanded(nodeKey(group.parent))" class="trace-card__details">
              <section class="trace-detail-block">
                <div class="trace-detail-block__head">
                  <strong>{{ argLabel(group.parent) }}</strong>
                  <el-button
                    size="small"
                    text
                    :icon="DocumentCopy"
                    @click="copyText(prettyJson(group.parent.argsJson), '输入')"
                  >
                    复制
                  </el-button>
                </div>
                <pre
                  class="trace-json"
                  :class="{ 'pre-trace-span': isInternalTraceSpan(group.parent.toolName) }"
                >{{ prettyJson(group.parent.argsJson) }}</pre>
              </section>

              <section class="trace-detail-block">
                <div class="trace-detail-block__head">
                  <strong>{{ resultLabel(group.parent) }}</strong>
                  <el-button
                    size="small"
                    text
                    :icon="DocumentCopy"
                    @click="copyText(prettyJson(group.parent.resultSummary), '输出')"
                  >
                    复制
                  </el-button>
                </div>
                <pre
                  class="trace-json"
                  :class="{ 'pre-trace-span': isInternalTraceSpan(group.parent.toolName) }"
                >{{ prettyJson(group.parent.resultSummary) }}</pre>
              </section>

              <section v-if="!isInternalTraceSpan(group.parent.toolName)" class="trace-detail-block">
                <div class="trace-detail-block__head">
                  <strong>召回结果</strong>
                  <el-button
                    size="small"
                    text
                    :icon="DocumentCopy"
                    @click="copyText(JSON.stringify(group.parent.retrievalCandidates || [], null, 2), '召回结果')"
                  >
                    复制
                  </el-button>
                </div>
                <pre class="trace-json">{{ JSON.stringify(group.parent.retrievalCandidates || [], null, 2) }}</pre>
              </section>
            </div>
          </article>

          <div
            v-if="group.children.length"
            class="trace-children"
            role="group"
            :aria-label="`子调用 ${group.children.length}`"
          >
            <div class="trace-children__label">
              子调用链
              <span>{{ group.children.length }}</span>
            </div>
            <ol class="trace-children__list">
              <li
                v-for="child in group.children"
                :key="nodeKey(child)"
                class="trace-children__item"
              >
                <article
                  class="trace-card is-nested"
                  :class="{
                    'is-success': child.success,
                    'is-error': !child.success,
                    'is-expanded': isExpanded(nodeKey(child)),
                  }"
                >
                  <div class="trace-card__main">
                    <div
                      class="trace-card__status"
                      :class="child.success ? 'is-success' : 'is-error'"
                      :aria-label="child.success ? '成功' : '失败'"
                      :title="child.success ? '成功' : '失败'"
                    >
                      <el-icon>
                        <CircleCheckFilled v-if="child.success" />
                        <CircleCloseFilled v-else />
                      </el-icon>
                    </div>

                    <div class="trace-card__body">
                      <div class="trace-card__topline">
                        <div class="trace-card__titles">
                          <h3 class="trace-card__title" :title="traceNodeTitle(child.toolName)">
                            {{ traceNodeTitle(child.toolName) }}
                          </h3>
                          <code
                            v-if="traceNodeTitle(child.toolName) !== child.toolName"
                            class="trace-card__tool"
                            :title="child.toolName"
                          >
                            {{ child.toolName }}
                          </code>
                        </div>
                        <div class="trace-card__metrics">
                          <span class="trace-metric is-elapsed">{{ formatMs(child.elapsedMs) }}</span>
                          <span class="trace-metric is-token">token {{ child.tokenCost || 0 }}</span>
                        </div>
                      </div>

                      <div v-if="metaChips(child).length" class="trace-card__meta">
                        <el-tooltip
                          v-for="chip in metaChips(child)"
                          :key="chip.key"
                          :content="chip.label"
                          placement="top"
                          :show-after="400"
                        >
                          <span class="trace-chip" :class="{ 'is-mono': chip.mono }">{{ chip.label }}</span>
                        </el-tooltip>
                      </div>

                      <div v-if="!child.success && child.errorCode" class="trace-card__error">
                        <span class="trace-card__error-label">errorCode</span>
                        <code>{{ child.errorCode }}</code>
                      </div>

                      <div v-if="child.createdAt" class="trace-card__time">
                        {{ formatTimestamp(child.createdAt) }}
                      </div>

                      <div class="trace-card__actions">
                        <button
                          type="button"
                          class="trace-detail-toggle"
                          :aria-expanded="isExpanded(nodeKey(child)) ? 'true' : 'false'"
                          @click="toggleExpanded(nodeKey(child))"
                        >
                          <el-icon class="trace-detail-toggle__chevron"><ArrowRight /></el-icon>
                          <span>{{ collapseDetailTitle(child) }}</span>
                          <span class="trace-detail-toggle__hint">
                            {{ isExpanded(nodeKey(child)) ? '收起' : '展开' }}
                          </span>
                        </button>
                      </div>
                    </div>
                  </div>

                  <div v-if="isExpanded(nodeKey(child))" class="trace-card__details">
                    <section class="trace-detail-block">
                      <div class="trace-detail-block__head">
                        <strong>{{ argLabel(child) }}</strong>
                        <el-button
                          size="small"
                          text
                          :icon="DocumentCopy"
                          @click="copyText(prettyJson(child.argsJson), '输入')"
                        >
                          复制
                        </el-button>
                      </div>
                      <pre
                        class="trace-json"
                        :class="{ 'pre-trace-span': isInternalTraceSpan(child.toolName) }"
                      >{{ prettyJson(child.argsJson) }}</pre>
                    </section>

                    <section class="trace-detail-block">
                      <div class="trace-detail-block__head">
                        <strong>{{ resultLabel(child) }}</strong>
                        <el-button
                          size="small"
                          text
                          :icon="DocumentCopy"
                          @click="copyText(prettyJson(child.resultSummary), '输出')"
                        >
                          复制
                        </el-button>
                      </div>
                      <pre
                        class="trace-json"
                        :class="{ 'pre-trace-span': isInternalTraceSpan(child.toolName) }"
                      >{{ prettyJson(child.resultSummary) }}</pre>
                    </section>

                    <section v-if="!isInternalTraceSpan(child.toolName)" class="trace-detail-block">
                      <div class="trace-detail-block__head">
                        <strong>召回结果</strong>
                        <el-button
                          size="small"
                          text
                          :icon="DocumentCopy"
                          @click="copyText(JSON.stringify(child.retrievalCandidates || [], null, 2), '召回结果')"
                        >
                          复制
                        </el-button>
                      </div>
                      <pre class="trace-json">{{ JSON.stringify(child.retrievalCandidates || [], null, 2) }}</pre>
                    </section>
                  </div>
                </article>
              </li>
            </ol>
          </div>
        </li>
      </ol>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  ArrowRight,
  CircleCheckFilled,
  CircleCloseFilled,
  DocumentCopy,
} from '@element-plus/icons-vue'
import type { TraceNode } from '@/types/trace'
import { isInternalTraceSpan, traceNodeTitle } from '@/utils/traceLabels'

const props = withDefaults(defineProps<{
  nodes: TraceNode[]
  loading?: boolean
}>(), {
  loading: false,
})

interface NodeGroup {
  parent: TraceNode
  children: TraceNode[]
}

interface MetaChip {
  key: string
  label: string
  mono?: boolean
}

const expandedKeys = ref<Record<string, boolean>>({})

/**
 * 按 runtime_trace_span 的 parentSpanId 折叠子 span；其余节点按时间顺序平铺。
 */
const groupedNodes = computed<NodeGroup[]>(() => {
  const groups: NodeGroup[] = []
  const spanGroupIndex = new Map<string, NodeGroup>()
  for (const node of props.nodes) {
    if (node.source === 'runtime_trace_span') {
      if (node.parentSpanId && spanGroupIndex.has(node.parentSpanId)) {
        spanGroupIndex.get(node.parentSpanId)!.children.push(node)
        continue
      }
      const group = { parent: node, children: [] as TraceNode[] }
      groups.push(group)
      if (node.spanId) {
        spanGroupIndex.set(node.spanId, group)
      }
      continue
    }
    groups.push({ parent: node, children: [] })
  }
  return groups
})

const overview = computed(() => {
  const nodes = props.nodes
  let successCount = 0
  let errorCount = 0
  let tokenTotal = 0
  let maxElapsedMs = 0
  for (const node of nodes) {
    if (node.success) successCount += 1
    else errorCount += 1
    tokenTotal += Number(node.tokenCost || 0)
    maxElapsedMs = Math.max(maxElapsedMs, Number(node.elapsedMs || 0))
  }
  return {
    nodeCount: nodes.length,
    successCount,
    errorCount,
    tokenTotal,
    maxElapsedMs,
  }
})

function nodeKey(node: TraceNode) {
  return `${node.source || 'tool'}:${node.id}:${node.spanId || ''}`
}

function isExpanded(key: string) {
  return !!expandedKeys.value[key]
}

function toggleExpanded(key: string) {
  expandedKeys.value = {
    ...expandedKeys.value,
    [key]: !expandedKeys.value[key],
  }
}

function collapseDetailTitle(node: TraceNode): string {
  if (isInternalTraceSpan(node.toolName)) return '入参 / 出参'
  return '入参 / 出参 / 召回'
}

function metaChips(node: TraceNode): MetaChip[] {
  const chips: MetaChip[] = []
  if (node.agentName) chips.push({ key: 'agent', label: node.agentName })
  if (node.runtimeType) chips.push({ key: 'runtime', label: `runtime: ${node.runtimeType}`, mono: true })
  if (node.spanType) chips.push({ key: 'spanType', label: node.spanType, mono: true })
  if (node.nodeId) chips.push({ key: 'nodeId', label: `node: ${node.nodeId}`, mono: true })
  return chips
}

function formatMs(value?: number | null) {
  const ms = Number(value || 0)
  if (!Number.isFinite(ms) || ms <= 0) return '0ms'
  if (ms >= 1000) {
    const seconds = ms / 1000
    return `${seconds >= 10 ? seconds.toFixed(0) : seconds.toFixed(1)}s`
  }
  return `${Math.round(ms)}ms`
}

function formatTimestamp(value?: string) {
  if (!value) return ''
  return value.replace('T', ' ').replace(/\.\d+Z?$/, '')
}

function argLabel(node: TraceNode): string {
  if (node.toolName === '_trace:embedding.encode') return '向量化请求摘要'
  if (node.toolName.startsWith('_trace:llm.stream#')) return '大模型请求'
  if (node.toolName === '_trace:milvus.tool_search') return 'Milvus 检索条件'
  if (node.toolName === 'runtime.agent.run') return 'Runtime 调用输入'
  if (node.toolName === '_trace:agentscope.run') return 'AgentScope 调用输入'
  return '输入'
}

function resultLabel(node: TraceNode): string {
  if (node.toolName === '_trace:embedding.encode') return '向量化结果'
  if (node.toolName.startsWith('_trace:llm.stream#')) return '大模型输出'
  if (node.toolName === '_trace:milvus.tool_search') return 'Milvus 命中结果'
  if (node.toolName === 'runtime.agent.run') return '最终输出与 Runtime 元数据'
  if (node.toolName === '_trace:agentscope.run') return '最终输出与元数据'
  return '输出'
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

async function copyText(text: string, label: string) {
  if (!text || text === '-') {
    ElMessage.info(`${label}为空，无可复制内容`)
    return
  }
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success(`${label}已复制`)
  } catch {
    ElMessage.error('复制失败，请手动复制')
  }
}
</script>

<style scoped lang="scss">
.trace-timeline {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 14px;
}

.trace-loading,
.trace-empty,
.trace-overview,
.trace-node-list,
.trace-children__list {
  min-width: 0;
}

.trace-loading {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.trace-skeleton-card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  background: var(--surface-solid-panel);
}

.trace-skeleton-line {
  height: 12px;
  border-radius: 999px;
  background: linear-gradient(
    90deg,
    color-mix(in srgb, var(--surface-solid-control) 70%, transparent),
    color-mix(in srgb, var(--border-subtle) 80%, transparent),
    color-mix(in srgb, var(--surface-solid-control) 70%, transparent)
  );
  background-size: 200% 100%;
  animation: trace-skeleton-shimmer var(--motion-duration-slow) ease-in-out infinite;
}

.trace-skeleton-line.is-title {
  width: min(52%, 280px);
  height: 14px;
}

.trace-skeleton-line.is-meta {
  width: min(78%, 420px);
}

.trace-skeleton-line.short {
  width: min(42%, 220px);
}

.trace-empty {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 18px 16px;
  border: 1px dashed var(--border-subtle);
  border-radius: var(--radius-md);
  background: color-mix(in srgb, var(--surface-solid-control) 72%, transparent);
  color: var(--text-secondary);

  strong {
    color: var(--text-primary);
    font-size: 14px;
  }

  span {
    font-size: 12px;
    color: var(--text-muted);
  }
}

.trace-overview {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 8px;
  padding: 10px 12px;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  background: color-mix(in srgb, var(--surface-solid-control) 88%, transparent);
}

.trace-overview__item {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
}

.trace-overview__label {
  color: var(--text-muted);
  font-size: 11px;
  letter-spacing: 0.02em;
}

.trace-overview__value {
  overflow: hidden;
  color: var(--text-primary);
  font-size: 15px;
  font-variant-numeric: tabular-nums;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;

  &.is-success {
    color: var(--status-success);
  }

  &.is-danger {
    color: var(--status-danger);
  }
}

.trace-node-list,
.trace-children__list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.trace-node-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.trace-node-group {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 8px;
}

.trace-children {
  position: relative;
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 8px;
  margin-inline-start: 14px;
  padding-inline-start: 14px;
  border-inline-start: 2px solid color-mix(in srgb, var(--border-divider) 88%, var(--brand-primary));
}

.trace-children__label {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--text-muted);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.04em;
  text-transform: uppercase;

  span {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    min-width: 18px;
    height: 18px;
    padding: 0 6px;
    border-radius: 999px;
    background: color-mix(in srgb, var(--surface-solid-control) 88%, transparent);
    border: 1px solid var(--border-subtle);
    color: var(--text-secondary);
    font-size: 11px;
    font-variant-numeric: tabular-nums;
    text-transform: none;
  }
}

.trace-children__list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.trace-children__item {
  position: relative;
  min-width: 0;

  &::before {
    content: '';
    position: absolute;
    top: 18px;
    left: -14px;
    width: 12px;
    height: 1px;
    background: color-mix(in srgb, var(--border-divider) 88%, var(--brand-primary));
  }
}

.trace-card {
  min-width: 0;
  overflow: hidden;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  background: var(--surface-solid-panel);
  transition:
    border-color var(--motion-duration-fast) var(--motion-easing-standard),
    background-color var(--motion-duration-fast) var(--motion-easing-standard);

  &:hover {
    border-color: color-mix(in srgb, var(--brand-primary) 28%, var(--border-subtle));
  }

  &.is-expanded {
    border-color: color-mix(in srgb, var(--brand-primary) 34%, var(--border-subtle));
    background: color-mix(in srgb, var(--surface-solid-panel) 92%, var(--surface-solid-control));
  }

  &.is-nested {
    background: color-mix(in srgb, var(--surface-solid-control) 90%, transparent);
  }

  &.is-error {
    border-color: color-mix(in srgb, var(--status-danger) 28%, var(--border-subtle));
  }
}

.trace-card__main {
  display: grid;
  grid-template-columns: 20px minmax(0, 1fr);
  gap: 10px;
  padding: 12px 12px 10px;
}

.trace-card__status {
  display: inline-flex;
  width: 18px;
  height: 18px;
  margin-block-start: 2px;
  align-items: center;
  justify-content: center;
  color: var(--status-success);

  &.is-error {
    color: var(--status-danger);
  }

  .el-icon {
    font-size: 16px;
  }
}

.trace-card__body {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 8px;
}

.trace-card__topline {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  justify-content: space-between;
}

.trace-card__titles {
  display: flex;
  min-width: 0;
  flex: 1 1 auto;
  flex-direction: column;
  gap: 4px;
}

.trace-card__title {
  margin: 0;
  overflow: hidden;
  color: var(--text-primary);
  font-size: 14px;
  font-weight: 650;
  line-height: 1.35;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.trace-card__tool {
  display: block;
  overflow: hidden;
  color: var(--text-muted);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 11px;
  line-height: 1.4;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.trace-card__metrics {
  display: flex;
  flex: 0 0 auto;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
  min-width: 72px;
  font-variant-numeric: tabular-nums;
}

.trace-metric {
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;

  &.is-elapsed {
    color: var(--text-primary);
  }

  &.is-token {
    color: var(--text-muted);
    font-weight: 500;
  }
}

.trace-card__meta {
  display: flex;
  min-width: 0;
  flex-wrap: wrap;
  gap: 6px;
}

.trace-chip {
  display: inline-flex;
  max-width: 100%;
  overflow: hidden;
  align-items: center;
  padding: 2px 8px;
  border: 1px solid var(--border-subtle);
  border-radius: 999px;
  background: color-mix(in srgb, var(--surface-solid-control) 88%, transparent);
  color: var(--text-secondary);
  font-size: 11px;
  line-height: 1.4;
  text-overflow: ellipsis;
  white-space: nowrap;

  &.is-mono {
    font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  }
}

.trace-card__error {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border: 1px solid color-mix(in srgb, var(--status-danger) 24%, var(--border-subtle));
  border-radius: var(--radius-sm);
  background: var(--status-danger-soft);
  color: var(--status-danger);

  .trace-card__error-label {
    flex: 0 0 auto;
    font-size: 11px;
    font-weight: 650;
    letter-spacing: 0.02em;
    text-transform: uppercase;
  }

  code {
    min-width: 0;
    overflow: hidden;
    font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
    font-size: 12px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.trace-card__time {
  color: var(--text-muted);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}

.trace-card__actions {
  display: flex;
  min-width: 0;
}

.trace-detail-toggle {
  display: inline-flex;
  max-width: 100%;
  align-items: center;
  gap: 6px;
  margin: 0;
  padding: 4px 0;
  border: 0;
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
  font: inherit;
  font-size: 12px;
  font-weight: 600;

  &:hover {
    color: var(--text-primary);
  }

  &:focus-visible {
    outline: 2px solid var(--border-focus);
    outline-offset: 2px;
    border-radius: var(--radius-sm);
  }
}

.trace-detail-toggle__chevron {
  transition: transform var(--motion-duration-fast) var(--motion-easing-standard);
}

.trace-card.is-expanded .trace-detail-toggle__chevron {
  transform: rotate(90deg);
}

.trace-detail-toggle__hint {
  color: var(--text-muted);
  font-weight: 500;
}

.trace-card__details {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-block-start: 0;
  padding: 12px 12px 12px 42px;
  border-block-start: 1px solid var(--border-divider);
}

.trace-detail-block {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 6px;
}

.trace-detail-block__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  color: var(--text-secondary);
  font-size: 12px;

  strong {
    color: var(--text-primary);
    font-weight: 650;
  }
}

.trace-json {
  margin: 0;
  max-height: 200px;
  overflow: auto;
  padding: 10px 12px;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-sm);
  background: var(--surface-solid-control);
  color: var(--text-primary);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
  overflow-wrap: anywhere;
}

.pre-trace-span {
  max-height: 480px;
}

@keyframes trace-skeleton-shimmer {
  0% { background-position: 100% 0; }
  100% { background-position: -100% 0; }
}

@media (max-width: 720px) {
  .trace-overview {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .trace-children {
    margin-inline-start: 8px;
    padding-inline-start: 10px;
  }

  .trace-card__topline {
    flex-direction: column;
    gap: 8px;
  }

  .trace-card__metrics {
    flex-direction: row;
    align-items: center;
    justify-content: flex-start;
    gap: 10px;
    min-width: 0;
  }

  .trace-card__details {
    padding-inline-start: 12px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .trace-skeleton-line,
  .trace-card,
  .trace-detail-toggle__chevron {
    animation: none !important;
    transition: none !important;
  }
}
</style>
