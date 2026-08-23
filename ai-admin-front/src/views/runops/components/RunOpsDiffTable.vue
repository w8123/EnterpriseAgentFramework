<template>
  <div class="diff-table">
    <el-table v-if="rows.length" :data="rows" stripe>
      <el-table-column type="expand">
        <template #default="{ row }">
          <div class="diff-detail-grid">
            <div>
              <strong>原运行</strong>
              <pre>{{ pretty(row.baseline) }}</pre>
            </div>
            <div>
              <strong>本次运行</strong>
              <pre>{{ pretty(row.candidate) }}</pre>
            </div>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="key" :label="keyLabel" min-width="180" show-overflow-tooltip />
      <el-table-column label="原运行" min-width="220" show-overflow-tooltip>
        <template #default="{ row }">{{ digest(row.baseline) }}</template>
      </el-table-column>
      <el-table-column label="本次运行" min-width="220" show-overflow-tooltip>
        <template #default="{ row }">{{ digest(row.candidate) }}</template>
      </el-table-column>
    </el-table>
    <el-empty v-else :description="emptyText" />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

interface DiffRow {
  key: string
  baseline?: unknown
  candidate?: unknown
}

const props = defineProps<{
  kind: 'span' | 'tool' | 'guard'
  rows: DiffRow[]
  digest: (...args: any[]) => string
  emptyText: string
}>()

const keyLabel = computed(() => ({
  span: '链路片段',
  tool: '工具',
  guard: '策略对象',
}[props.kind]))

function pretty(value: unknown) {
  if (value == null) return '-'
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}
</script>

<style scoped lang="scss">
.diff-detail-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  padding: 12px 16px;
}

.diff-detail-grid > div {
  min-width: 0;
}

.diff-detail-grid strong {
  display: block;
  margin-bottom: 6px;
  color: var(--text-primary);
  font-size: 12px;
}

.diff-detail-grid pre {
  max-height: 260px;
  margin: 0;
  overflow: auto;
  padding: 10px;
  border-radius: 8px;
  background: var(--surface-solid-control, var(--page-bg));
  color: var(--text-secondary);
  font-family: var(--font-mono, Consolas, monospace);
  font-size: 10px;
  line-height: 1.5;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

@media (max-width: 720px) {
  .diff-detail-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
