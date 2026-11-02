<template>
  <div class="ai-calls">
    <div class="ai-calls__kpis">
      <div class="ai-calls__kpi"><span>操作总数</span><strong>{{ records.length }}</strong></div>
      <div class="ai-calls__kpi"><span>正常</span><strong class="is-ok">{{ okCount }}</strong></div>
      <div class="ai-calls__kpi"><span>异常</span><strong class="is-danger">{{ records.length - okCount }}</strong></div>
    </div>

    <div class="ai-calls__toolbar">
      <div class="ai-calls__filters" role="group" aria-label="按状态筛选">
        <button
          v-for="option in filters"
          :key="option.key"
          type="button"
          :class="{ active: filter === option.key }"
          :aria-pressed="filter === option.key"
          :data-call-filter="option.key"
          @click="filter = option.key; page = 1"
        >{{ option.label }}</button>
      </div>
      <LiquidGlassInput
        v-model="keyword"
        class="ai-calls__search"
        clearable
        placeholder="搜索凭据、操作或系统"
        aria-label="搜索任务记录"
        @input="page = 1"
      />
      <LiquidGlassButton :loading="loading" @click="emit('refresh')">刷新</LiquidGlassButton>
    </div>

    <p v-if="error" class="ai-calls__error" role="alert">{{ error }}</p>
    <p v-else-if="loading && !records.length" class="ai-calls__muted" role="status">正在加载…</p>
    <LiquidGlassEmptyState
      v-else-if="!filtered.length"
      :title="records.length ? '没有符合条件的记录' : '还没有任务记录'"
      :description="records.length ? '换个筛选条件试试。' : '接入后，AI 每次替你查询或办理都会记在这里。'"
    />
    <div v-else class="ai-calls__scroll">
      <table class="ai-calls__table">
        <thead>
          <tr>
            <th>时间</th>
            <th>凭据</th>
            <th>操作</th>
            <th v-if="showSystem">系统</th>
            <th>状态</th>
            <th>耗时</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="record in visible" :key="record.id" data-testid="call-record">
            <td>{{ formatAccessTime(record.createdAt) }}</td>
            <td>{{ record.credential }}</td>
            <td class="ai-calls__action" :title="record.action">{{ record.action }}</td>
            <td v-if="showSystem">{{ record.system || '-' }}</td>
            <td>
              <span :class="record.success ? 'is-ok' : 'is-danger'" :title="record.error || ''">
                {{ record.success ? '正常' : '异常' }}
              </span>
            </td>
            <td>{{ record.latencyMs == null ? '-' : `${record.latencyMs} ms` }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="filtered.length > pageSize" class="ai-calls__pager">
      <span>共 {{ filtered.length }} 条</span>
      <LiquidGlassButton :disabled="page <= 1" @click="page -= 1">上一页</LiquidGlassButton>
      <span>{{ page }} / {{ pageCount }}</span>
      <LiquidGlassButton :disabled="page >= pageCount" @click="page += 1">下一页</LiquidGlassButton>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { LiquidGlassButton, LiquidGlassEmptyState, LiquidGlassInput } from '@/components/common/liquid-glass'
import { formatAccessTime, type AiCallRecord } from './aiAccess'

const props = withDefaults(defineProps<{
  records: AiCallRecord[]
  loading?: boolean
  error?: string
  showSystem?: boolean
}>(), { loading: false, error: '', showSystem: false })

const emit = defineEmits<{ (event: 'refresh'): void }>()

const filters = [
  { key: 'all', label: '全部' },
  { key: 'ok', label: '正常' },
  { key: 'failed', label: '异常' },
] as const
const filter = ref<typeof filters[number]['key']>('all')
const keyword = ref('')
const page = ref(1)
const pageSize = 10

const okCount = computed(() => props.records.filter(record => record.success).length)
const filtered = computed(() => {
  const term = keyword.value.trim().toLowerCase()
  return props.records.filter((record) => {
    if (filter.value === 'ok' && !record.success) return false
    if (filter.value === 'failed' && record.success) return false
    if (!term) return true
    return [record.credential, record.action, record.system, record.error]
      .some(value => value && value.toLowerCase().includes(term))
  })
})
const pageCount = computed(() => Math.max(1, Math.ceil(filtered.value.length / pageSize)))
const visible = computed(() => {
  const current = Math.min(page.value, pageCount.value)
  return filtered.value.slice((current - 1) * pageSize, current * pageSize)
})
</script>

<style scoped lang="scss">
.ai-calls { display: flex; flex-direction: column; gap: 12px; }
.ai-calls__muted { margin: 0; color: var(--text-secondary); font-size: 13px; }
.ai-calls__error { margin: 0; color: var(--status-danger); font-size: 13px; }
.is-ok { color: var(--status-success); }
.is-danger { color: var(--status-danger); }

.ai-calls__kpis { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; }

.ai-calls__kpi {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 14px 16px;
  border: 1px solid var(--border-glass-edge);
  border-radius: var(--radius-md);
  background: var(--surface-glass-inset);

  span { color: var(--text-secondary); font-size: 13px; }
  strong { color: var(--text-primary); font-size: 24px; font-weight: 600; }
  strong.is-ok { color: var(--status-success); }
  strong.is-danger { color: var(--status-danger); }
}

.ai-calls__toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.ai-calls__search { flex: 1 1 220px; max-width: 360px; margin-left: auto; }

.ai-calls__filters {
  display: inline-flex;
  overflow: hidden;
  border: 1px solid var(--border-divider);
  border-radius: var(--radius-sm);

  button {
    padding: 6px 14px;
    border: 0;
    border-left: 1px solid var(--border-divider);
    color: var(--text-secondary);
    font-size: 13px;
    background: transparent;
    cursor: pointer;
  }
  button:first-child { border-left: 0; }
  button.active { color: var(--brand-primary); font-weight: 600; background: var(--surface-glass-inset); }
  button:focus-visible { outline: 2px solid var(--border-focus); outline-offset: -2px; }
}

.ai-calls__scroll { overflow-x: auto; }

.ai-calls__table {
  width: 100%;
  min-width: 640px;
  border-collapse: collapse;
  font-size: 13px;

  th, td { padding: 10px 12px; border-bottom: 1px solid var(--border-divider); text-align: left; white-space: nowrap; }
  th { color: var(--text-secondary); font-weight: 500; background: var(--surface-glass-inset); }
  td { color: var(--text-primary); }
}

.ai-calls__action { max-width: 280px; overflow: hidden; text-overflow: ellipsis; }

.ai-calls__pager {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  color: var(--text-secondary);
  font-size: 13px;
}
</style>
