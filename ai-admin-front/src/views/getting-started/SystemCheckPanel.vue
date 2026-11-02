<template>
  <LiquidGlassCard as="section" source="wallpaper" class="system-check" aria-labelledby="system-check-title" :aria-busy="loading">
    <header class="system-check__head">
      <div>
        <h2 id="system-check-title">系统体检</h2>
        <p role="status" aria-live="polite">{{ headline }}</p>
      </div>
      <LiquidGlassButton
        link
        :loading="loading"
        aria-label="重新检查"
        title="重新检查"
        data-testid="system-check-refresh"
        @click="load"
      ><el-icon v-if="!loading"><Refresh /></el-icon></LiquidGlassButton>
    </header>

    <div v-if="report" class="system-check__summary" aria-label="检查结果汇总">
      <span v-for="entry in summaryEntries" :key="entry.status" class="system-check__pill" :class="`is-${entry.status}`">
        <i aria-hidden="true" />{{ entry.label }} {{ entry.count }}
      </span>
    </div>

    <p v-if="error" class="system-check__error" role="alert">{{ error }}</p>
    <p v-if="error && report" class="system-check__stale">以下为上次检查结果。</p>

    <div v-for="group in groups" :key="group.key" class="system-check__group">
      <h3>{{ group.title }}</h3>
      <button
        v-if="group.quiet"
        type="button"
        class="system-check__collapsed"
        :data-testid="`system-check-group-${group.key}`"
        :aria-expanded="Boolean(expanded[group.key])"
        :aria-controls="`system-check-items-${group.key}`"
        @click="expanded[group.key] = !expanded[group.key]"
      >
        <span class="system-check__dot" :class="group.offCount ? 'is-OFF' : 'is-OK'" aria-hidden="true" />
        <span>{{ group.offCount ? `${group.offCount} 项未开启 · ${group.items.length - group.offCount} 项正常` : `${group.items.length} 项检查正常` }}</span>
        <em>{{ expanded[group.key] ? '收起' : '查看' }}<el-icon aria-hidden="true"><ArrowUp v-if="expanded[group.key]" /><ArrowDown v-else /></el-icon></em>
      </button>
      <ul v-show="!group.quiet || expanded[group.key]" :id="`system-check-items-${group.key}`">
        <li
          v-for="item in group.items"
          :key="item.key"
          class="system-check__item"
          :class="`is-${item.status}`"
          :data-testid="`system-check-${item.key}`"
        >
          <span class="system-check__dot" :aria-label="STATUS_LABELS[item.status]" role="img" />
          <div class="system-check__body">
            <div class="system-check__line">
              <strong>{{ item.title }}</strong>
              <span class="system-check__status">{{ STATUS_LABELS[item.status] }}</span>
            </div>
            <p v-if="item.status !== 'OK' || item.fix" class="system-check__detail">{{ item.detail }}</p>
            <p v-if="item.fix" class="system-check__fix">{{ item.fix }}</p>
            <LiquidGlassButton v-if="item.link" link class="system-check__link" @click="router.push(item.link)">
              去处理 →
            </LiquidGlassButton>
          </div>
        </li>
      </ul>
    </div>
  </LiquidGlassCard>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowDown, ArrowUp, Refresh } from '@element-plus/icons-vue'
import { LiquidGlassButton, LiquidGlassCard } from '@/components/common/liquid-glass'
import {
  getSystemCheck,
  SYSTEM_CHECK_GROUPS,
  type SystemCheckItem,
  type SystemCheckReport,
  type SystemCheckStatus,
} from './gettingStarted'

const STATUS_LABELS: Record<SystemCheckStatus, string> = {
  OK: '正常',
  WARN: '需留意',
  ERROR: '异常',
  OFF: '未开启',
}

const STATUS_ORDER: SystemCheckStatus[] = ['ERROR', 'WARN', 'OFF', 'OK']

const router = useRouter()
const loading = ref(false)
const error = ref('')
const report = ref<SystemCheckReport | null>(null)
const expanded = reactive<Record<string, boolean>>({})

const groups = computed(() => {
  const items = report.value?.items ?? []
  return SYSTEM_CHECK_GROUPS
    .map((group) => ({
      ...group,
      items: items
        .filter((item) => item.group === group.key)
        .sort((left, right) => rank(left) - rank(right)),
    }))
    .map((group) => ({
      ...group,
      // 可选组件的未开启状态按需展开；任何异常和警告都始终展示。
      quiet: group.items.every((item) => item.status === 'OK' && !item.fix)
        || (group.key === 'optional' && group.items.every((item) => item.status === 'OK' || item.status === 'OFF')),
      offCount: group.items.filter((item) => item.status === 'OFF').length,
    }))
    .filter((group) => group.items.length > 0)
})

const summaryEntries = computed(() => STATUS_ORDER
  .map((status) => ({ status, label: STATUS_LABELS[status], count: report.value?.summary?.[status] ?? 0 }))
  .filter((entry) => entry.count > 0))

const headline = computed(() => {
  if (loading.value) return '正在检查…'
  if (error.value) return '本次检查未完成'
  if (!report.value) return '检查服务、安全设置和可选组件'
  const summary = report.value.summary
  if (summary.ERROR > 0) return `有 ${summary.ERROR} 项异常，按下面的提示处理`
  if (summary.WARN > 0) return `有 ${summary.WARN} 项建议处理`
  if (summary.OFF > 0) return '核心检查正常，可选组件按需启用'
  return '一切正常'
})

function rank(item: SystemCheckItem) {
  return STATUS_ORDER.indexOf(item.status)
}

async function load() {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    report.value = (await getSystemCheck()).data
  } catch (failure) {
    error.value = failure instanceof Error && failure.message
      ? `暂时无法完成检查：${failure.message}`
      : '暂时无法完成检查，请确认管理服务正在运行'
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped lang="scss">
.system-check {
  min-width: 0;
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: var(--layout-panel-padding);
  padding: calc(var(--layout-panel-padding) + 4px);
}

.system-check__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;

  h2 {
    margin: 0;
    color: var(--text-primary);
    font-size: 16px;
    line-height: 24px;
  }

  p {
    margin: 2px 0 0;
    color: var(--text-secondary);
    font-size: 12px;
    line-height: 1.7;
  }
}

.system-check__summary {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

.system-check__pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 0;
  color: var(--text-secondary);
  font-size: 12px;
  font-variant-numeric: tabular-nums;

  i {
    width: 7px;
    height: 7px;
    border-radius: 50%;
    background: currentColor;
  }

  &.is-OK { color: var(--status-success); }
  &.is-WARN { color: var(--status-warning); }
  &.is-ERROR { color: var(--status-danger); }
  &.is-OFF { color: var(--status-neutral); }
}

.system-check__error {
  margin: 0;
  padding: 10px 12px;
  border-radius: var(--radius-md);
  background: var(--status-danger-soft);
  color: var(--status-danger);
  font-size: 13px;
}

.system-check__stale {
  margin: 0;
  color: var(--text-muted);
  font-size: 12px;
}

.system-check__group {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  min-width: 0;
  gap: 6px;
  padding-top: var(--layout-panel-padding);
  border-top: 1px solid var(--border-divider);

  h3 {
    margin: 0;
    color: var(--text-muted);
    font-size: 12px;
    font-weight: 600;
  }

  ul {
    display: grid;
    margin: 0;
    padding: 0;
    list-style: none;
    gap: 6px;
  }
}

.system-check__collapsed {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 8px 0;
  border: 0;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text-primary);
  font: inherit;
  font-size: 13px;
  text-align: left;
  cursor: pointer;

  .system-check__dot {
    margin-top: 0;
    flex: none;
  }

  > span:not(.system-check__dot) {
    flex: 1;
    min-width: 0;
  }

  em {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    flex: none;
    color: var(--text-link);
    font-size: 12px;
    font-style: normal;
  }

  &:hover { background: var(--surface-hover); }
  &:active { background: var(--surface-pressed); }

  &:focus-visible {
    outline: 2px solid var(--border-focus);
    outline-offset: 2px;
  }
}

.system-check__item {
  display: grid;
  grid-template-columns: 10px minmax(0, 1fr);
  gap: 10px;
  padding: 8px 0;

  &.is-ERROR {
    padding: 12px;
    border-radius: var(--radius-md);
    background: var(--status-danger-soft);
  }

  & + & {
    border-top: 1px solid var(--border-divider);
  }
}

.system-check__dot {
  width: 8px;
  height: 8px;
  margin-top: 7px;
  border-radius: 50%;
  background: var(--status-neutral);

  .is-OK &,
  &.is-OK { background: var(--status-success); }
  .is-WARN & {
    background: var(--status-warning);
    box-shadow: 0 0 0 3px var(--status-warning-soft);
  }
  .is-ERROR & {
    background: var(--status-danger);
    box-shadow: 0 0 0 3px var(--status-danger-soft);
  }
}

.system-check__body {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.system-check__line {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;

  strong {
    color: var(--text-primary);
    font-size: 13px;
    font-weight: 600;
  }
}

.system-check__status {
  flex: none;
  color: var(--text-muted);
  font-size: 12px;

  .is-WARN & { color: var(--status-warning); }
  .is-ERROR & { color: var(--status-danger); }
}

.system-check__detail {
  margin: 0;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.6;
  overflow-wrap: anywhere;
}

.system-check__fix {
  margin: 6px 0 0;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.7;
  overflow-wrap: anywhere;
}

.system-check__link {
  justify-self: start;
  margin-top: 4px;
}
</style>
