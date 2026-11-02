<template>
  <div class="ai-credentials">
    <p v-if="error" class="ai-credentials__error" role="alert">{{ error }}</p>
    <p v-if="loading && !rows.length" class="ai-credentials__muted" role="status">正在加载凭据…</p>
    <LiquidGlassEmptyState
      v-else-if="!loading && !error && !rows.length"
      title="还没有访问凭据"
      description="在「接入与体验」里选好 AI 工具，获取安装提示词后，这里会多出一条凭据。"
    />
    <div v-else-if="rows.length" class="ai-credentials__scroll">
      <table class="ai-credentials__table">
        <thead>
          <tr>
            <th>凭据名称</th>
            <th>AI 工具</th>
            <th>KEY</th>
            <th>能操作</th>
            <th>有效期至</th>
            <th>最近使用</th>
            <th>状态</th>
            <th class="ai-credentials__ops">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in rows" :key="row.id" data-testid="credential-row">
            <td><strong>{{ row.name }}</strong></td>
            <td>{{ aiToolLabel(row.toolKind) }}</td>
            <td><code>{{ row.keyHint }}</code></td>
            <td class="ai-credentials__scope">{{ row.scope }}</td>
            <td>{{ formatAccessTime(row.expiresAt) }}</td>
            <td>{{ row.lastUsedAt ? formatAccessTime(row.lastUsedAt) : '还未使用' }}</td>
            <td>
              <span class="ai-credentials__status" :class="row.status === 'ACTIVE' ? 'is-active' : 'is-off'">
                {{ credentialStatusLabel(row.status) }}
              </span>
            </td>
            <td class="ai-credentials__ops">
              <LiquidGlassButton
                v-if="row.status === 'ACTIVE'"
                link
                type="danger"
                :loading="busyId === row.id"
                :disabled="busyId !== null && busyId !== row.id"
                @click="emit('revoke', row)"
              >停用</LiquidGlassButton>
              <span v-else class="ai-credentials__muted">-</span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { LiquidGlassButton, LiquidGlassEmptyState } from '@/components/common/liquid-glass'
import { aiToolLabel, credentialStatusLabel, formatAccessTime, type AiCredentialRow } from './aiAccess'

withDefaults(defineProps<{
  rows: AiCredentialRow[]
  loading?: boolean
  error?: string
  busyId?: number | null
}>(), { loading: false, error: '', busyId: null })

const emit = defineEmits<{ (event: 'revoke', row: AiCredentialRow): void }>()
</script>

<style scoped lang="scss">
.ai-credentials__muted { margin: 0; color: var(--text-secondary); font-size: 13px; }
.ai-credentials__error { margin: 0 0 8px; color: var(--status-danger); font-size: 13px; }
.ai-credentials__scroll { overflow-x: auto; }

.ai-credentials__table {
  width: 100%;
  min-width: 760px;
  border-collapse: collapse;
  font-size: 13px;

  th, td { padding: 10px 12px; border-bottom: 1px solid var(--border-divider); text-align: left; white-space: nowrap; }
  th { color: var(--text-secondary); font-weight: 500; background: var(--surface-glass-inset); }
  td { color: var(--text-primary); }
  code { font-size: 12px; }
}

.ai-credentials__scope { max-width: 220px; overflow: hidden; text-overflow: ellipsis; }
.ai-credentials__ops { text-align: right !important; }

.ai-credentials__status {
  padding: 1px 8px;
  border: 1px solid currentColor;
  border-radius: var(--radius-sm);
  font-size: 12px;

  &.is-active { color: var(--status-success); }
  &.is-off { color: var(--text-secondary); }
}
</style>
