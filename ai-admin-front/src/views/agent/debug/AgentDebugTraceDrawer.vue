<template>
  <AppDrawer
    v-model="visible"
    class="agent-debug-trace-drawer"
    title="本轮运行详情"
    size="min(760px, 94vw)"
    :show-close="false"
  >
    <template #header="{ close, titleId, titleClass }">
      <div class="trace-drawer-header">
        <div class="trace-drawer-header__copy">
          <h2 :id="titleId" :class="[titleClass, 'trace-drawer-header__title']">本轮运行详情</h2>
          <div v-if="traceId" class="trace-drawer-header__id-row">
            <el-tooltip :content="traceId" placement="bottom" :show-after="300">
              <code class="trace-drawer-header__id" :title="traceId">{{ traceId }}</code>
            </el-tooltip>
            <el-button
              class="trace-drawer-header__copy-btn"
              text
              size="small"
              :icon="CopyDocument"
              aria-label="复制 Trace ID"
              @click="copyTraceId"
            >
              复制
            </el-button>
          </div>
          <p class="trace-drawer-header__desc">查看本轮执行链路、节点耗时与原始运行数据。</p>
        </div>
        <button
          type="button"
          class="trace-drawer-header__close"
          aria-label="关闭本轮运行详情"
          @click="close"
        >
          <el-icon><Close /></el-icon>
        </button>
      </div>
    </template>

    <LiquidGlassTabs v-model="detailTab" class="trace-detail-tabs">
      <el-tab-pane label="执行链路" name="timeline">
        <TraceTimeline :nodes="nodes" :loading="loading" />
      </el-tab-pane>
      <el-tab-pane label="原始响应" name="raw">
        <div class="raw-response-panel">
          <template v-if="rawMode === 'show'">
            <div class="raw-response-toolbar">
              <span>本轮 lastAgentResult.metadata</span>
              <el-button
                text
                size="small"
                :icon="CopyDocument"
                aria-label="复制 JSON"
                @click="copyRawResponseJson"
              >
                复制 JSON
              </el-button>
            </div>
            <pre class="metadata-json">{{ rawJson }}</pre>
          </template>
          <div v-else-if="rawMode === 'mismatch'" class="empty-detail compact raw-response-empty">
            <el-icon><DataLine /></el-icon>
            <p>当前页面未保留该 Trace 对应的原始响应，请前往 RunOps 查看持久化运行数据。</p>
          </div>
          <div v-else class="empty-detail compact raw-response-empty">
            <el-icon><DataLine /></el-icon>
            <p>暂无本轮原始响应</p>
          </div>
        </div>
      </el-tab-pane>
    </LiquidGlassTabs>

    <template #footer>
      <LiquidGlassButton @click="visible = false">关闭</LiquidGlassButton>
      <LiquidGlassButton
        type="primary"
        :icon="DataLine"
        :disabled="!traceId"
        @click="emit('open-runops')"
      >
        前往 RunOps 深度分析
      </LiquidGlassButton>
    </template>
  </AppDrawer>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Close, CopyDocument, DataLine } from '@element-plus/icons-vue'
import AppDrawer from '@/components/common/AppDrawer.vue'
import { LiquidGlassButton, LiquidGlassTabs } from '@/components/common/liquid-glass'
import TraceTimeline from '@/components/TraceTimeline.vue'
import type { TraceNode } from '@/types/trace'
import type { RawResponseMode } from './agentDebugFormat'

const props = defineProps<{
  traceId: string
  nodes: TraceNode[]
  loading: boolean
  rawMode: RawResponseMode
  rawJson: string
}>()

const emit = defineEmits<{ 'open-runops': [] }>()

const visible = defineModel<boolean>({ default: false })
const detailTab = ref<'timeline' | 'raw'>('timeline')

watch(() => props.traceId, () => {
  detailTab.value = 'timeline'
})
watch(visible, (open) => {
  if (open) detailTab.value = 'timeline'
})

async function copyText(value: string, success: string) {
  if (!value) return
  try {
    await navigator.clipboard.writeText(value)
    ElMessage.success(success)
  } catch {
    ElMessage.error('复制失败，请手动复制')
  }
}

function copyTraceId() {
  void copyText(props.traceId, 'Trace ID 已复制')
}

function copyRawResponseJson() {
  if (props.rawMode !== 'show') return
  void copyText(props.rawJson, 'JSON 已复制')
}
</script>

<style scoped lang="scss">
.empty-detail {
  display: flex;
  min-height: 210px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: var(--text-muted);
  text-align: center;

  &.compact {
    min-height: 145px;
  }

  .el-icon {
    margin-bottom: 9px;
    font-size: 30px;
  }

  p {
    margin: 0;
    font-size: 12px;
    font-weight: 650;
  }
}

.trace-drawer-header {
  display: flex;
  width: 100%;
  min-width: 0;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding-inline-end: 4px;
}

.trace-drawer-header__copy {
  display: flex;
  min-width: 0;
  flex: 1 1 auto;
  flex-direction: column;
  gap: 6px;
}

.trace-drawer-header__title {
  margin: 0;
  color: var(--text-primary);
  font-size: 18px;
  font-weight: 750;
  letter-spacing: 0.01em;
  line-height: 1.3;
}

.trace-drawer-header__id-row {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 6px;
}

.trace-drawer-header__id {
  display: block;
  min-width: 0;
  overflow: hidden;
  color: var(--text-muted);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.4;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.trace-drawer-header__copy-btn {
  flex: 0 0 auto;
}

.trace-drawer-header__desc {
  margin: 0;
  color: var(--text-secondary);
  font-size: 13px;
  line-height: 1.5;
}

.trace-drawer-header__close {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  margin: 0;
  padding: 0;
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
  transition:
    color var(--motion-duration-fast) var(--motion-easing-standard),
    background-color var(--motion-duration-fast) var(--motion-easing-standard),
    border-color var(--motion-duration-fast) var(--motion-easing-standard);

  &:hover {
    color: var(--text-primary);
    background: color-mix(in srgb, var(--surface-solid-control) 88%, transparent);
    border-color: var(--border-subtle);
  }

  &:focus-visible {
    outline: 2px solid var(--border-focus);
    outline-offset: 2px;
  }
}

.metadata-json {
  max-height: min(62vh, 640px);
  margin: 0;
  overflow: auto;
  padding: 14px;
  border: 1px solid color-mix(in srgb, var(--border-subtle) 80%, transparent);
  border-radius: var(--radius-md);
  color: var(--text-primary);
  background: color-mix(in srgb, var(--surface-solid-control) 78%, #0b1220);
  font-family: 'JetBrains Mono', 'Fira Code', Consolas, monospace;
  font-size: 12px;
  line-height: 1.7;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  word-break: break-word;
}

.trace-detail-tabs {
  min-width: 0;

  :deep(.el-tabs__header) {
    margin: 0 0 12px;
  }

  :deep(.el-tabs__nav-wrap) {
    min-width: 0;
  }

  :deep(.el-tabs__item) {
    font-weight: 750;
  }

  :deep(.el-tabs__item:focus-visible) {
    outline: 2px solid var(--border-focus);
    outline-offset: 2px;
  }

  :deep(.el-tab-pane) {
    min-width: 0;
  }
}

.raw-response-panel {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 10px;
}

.raw-response-toolbar {
  display: flex;
  min-width: 0;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  color: var(--text-muted);
  font-size: 12px;
  font-weight: 700;
}

.raw-response-empty {
  margin-top: 8px;
}
</style>
