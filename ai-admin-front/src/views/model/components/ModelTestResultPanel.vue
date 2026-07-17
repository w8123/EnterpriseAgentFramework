<script setup lang="ts">
import StatusTag from '@/components/common/StatusTag.vue'
import type { ModelInstanceTestResult } from '@/types/model'
import { formatDateTime, formatLatency, testStatusLabel, testStatusTone } from '../modelCenterUi'

defineProps<{
  result?: ModelInstanceTestResult | null
  stale?: boolean
  testedAt?: string | null
}>()
</script>

<template>
  <div v-if="result || stale" class="model-test-result" :class="{ 'is-stale': stale }">
    <div class="model-test-result__header">
      <StatusTag
        v-if="result"
        :label="result.success ? '测试通过' : '测试失败'"
        :tone="result.success ? 'success' : 'danger'"
      />
      <StatusTag v-else label="测试结果已失效" tone="warning" />
      <span v-if="stale" class="model-test-result__stale">配置已变化，请重新测试</span>
    </div>
    <template v-if="result && !stale">
      <p class="model-test-result__message">{{ result.message || (result.success ? '连接正常' : '测试失败') }}</p>
      <dl class="model-test-result__meta">
        <div>
          <dt>耗时</dt>
          <dd>{{ formatLatency(result.latencyMs) }}</dd>
        </div>
        <div v-if="result.dimension != null">
          <dt>Embedding 维度</dt>
          <dd>{{ result.dimension }}</dd>
        </div>
        <div v-if="testedAt">
          <dt>测试时间</dt>
          <dd>{{ formatDateTime(testedAt) }}</dd>
        </div>
        <div v-if="result.lastTestStatus">
          <dt>状态</dt>
          <dd>
            <StatusTag
              :label="testStatusLabel(result.lastTestStatus)"
              :tone="testStatusTone(result.lastTestStatus)"
              size="small"
            />
          </dd>
        </div>
      </dl>
    </template>
  </div>
</template>

<style scoped lang="scss">
.model-test-result {
  display: grid;
  gap: 10px;
  padding: 12px 14px;
  border: 1px solid var(--border-glass);
  border-radius: var(--radius-md);
  background: var(--surface-glass-panel);
}

.model-test-result.is-stale {
  border-color: color-mix(in srgb, var(--status-warning) 45%, var(--border-glass));
}

.model-test-result__header {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.model-test-result__stale,
.model-test-result__message {
  margin: 0;
  color: var(--text-secondary);
  line-height: 1.5;
}

.model-test-result__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 18px;
  margin: 0;
}

.model-test-result__meta > div {
  display: inline-flex;
  gap: 6px;
  align-items: center;
}

.model-test-result__meta dt {
  margin: 0;
  color: var(--text-muted);
}

.model-test-result__meta dd {
  margin: 0;
  color: var(--text-primary);
  font-weight: 600;
}
</style>
