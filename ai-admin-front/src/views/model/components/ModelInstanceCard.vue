<script setup lang="ts">
import { computed } from 'vue'
import StatusTag from '@/components/common/StatusTag.vue'
import type { ModelInstance } from '@/types/model'
import {
  extractBaseUrlAuthority,
  formatDateTime,
  formatLatency,
  modelTestFailureHint,
  modelTypeLabel,
  providerDisplayName,
  runtimeStatusLabel,
  runtimeStatusTone,
  testStatusLabel,
  testStatusTone,
} from '../modelCenterUi'
import ModelProviderIcon from './ModelProviderIcon.vue'

const props = defineProps<{
  instance: ModelInstance
  testing?: boolean
  toggling?: boolean
}>()

const emit = defineEmits<{
  manage: [instance: ModelInstance]
  test: [instance: ModelInstance]
  toggle: [instance: ModelInstance]
}>()

const archived = computed(() => props.instance.status === 'ARCHIVED')
const authority = computed(() => extractBaseUrlAuthority(props.instance.connection?.baseUrl))
</script>

<template>
  <article class="model-instance-card" :class="{ 'is-archived': archived }">
    <header class="model-instance-card__header">
      <ModelProviderIcon :provider="instance.provider" :size="40" />
      <div class="model-instance-card__titles">
        <h3>{{ instance.name }}</h3>
        <p>{{ providerDisplayName(instance.provider) }} · {{ instance.modelName }}</p>
      </div>
    </header>

    <div class="model-instance-card__tags">
      <StatusTag :label="modelTypeLabel(instance.modelType)" tone="info" />
      <StatusTag
        :label="runtimeStatusLabel(instance.status)"
        :tone="runtimeStatusTone(instance.status)"
      />
      <StatusTag
        :label="testStatusLabel(instance.lastTestStatus)"
        :tone="testStatusTone(instance.lastTestStatus)"
      />
    </div>

    <dl class="model-instance-card__meta">
      <div>
        <dt>BaseURL</dt>
        <dd>{{ authority }}</dd>
      </div>
      <div>
        <dt>最近测试</dt>
        <dd>{{ formatDateTime(instance.lastTestAt) }}</dd>
      </div>
      <div>
        <dt>测试耗时</dt>
        <dd>{{ formatLatency(instance.lastTestLatencyMs) }}</dd>
      </div>
      <div>
        <dt>更新时间</dt>
        <dd>{{ formatDateTime(instance.updatedAt) }}</dd>
      </div>
    </dl>

    <div
      v-if="instance.lastTestStatus === 'FAILED'"
      class="model-instance-card__test-failure"
      role="status"
      aria-live="polite"
    >
      <strong>最近失败原因</strong>
      <span>{{ modelTestFailureHint(instance.lastTestError) }}</span>
    </div>

    <p v-if="instance.remark" class="model-instance-card__remark">{{ instance.remark }}</p>

    <footer class="model-instance-card__actions">
      <template v-if="archived">
        <el-button type="primary" plain @click="emit('manage', instance)">查看详情</el-button>
      </template>
      <template v-else>
        <el-button :loading="testing" @click="emit('test', instance)">测试</el-button>
        <el-button type="primary" plain @click="emit('manage', instance)">管理</el-button>
        <el-button :loading="toggling" @click="emit('toggle', instance)">
          {{ instance.status === 'ACTIVE' ? '停用' : '启用' }}
        </el-button>
      </template>
    </footer>
  </article>
</template>

<style scoped lang="scss">
.model-instance-card {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 14px;
  padding: 18px;
  border: 1px solid var(--border-glass);
  border-radius: var(--radius-lg);
  background: var(--bg-card, var(--surface-glass-panel));
  box-shadow: var(--shadow-panel);
  transition: border-color 0.18s ease, transform 0.18s ease;
}

.model-instance-card:hover {
  border-color: color-mix(in srgb, var(--el-color-primary) 35%, var(--border-glass));
  transform: translateY(-1px);
}

.model-instance-card.is-archived {
  opacity: 0.88;
}

.model-instance-card__header {
  display: flex;
  gap: 12px;
  align-items: flex-start;
}

.model-instance-card__titles {
  min-width: 0;
}

.model-instance-card__titles h3,
.model-instance-card__titles p,
.model-instance-card__remark {
  margin: 0;
}

.model-instance-card__titles h3 {
  color: var(--text-primary);
  font-size: 16px;
  line-height: 1.35;
  word-break: break-word;
}

.model-instance-card__titles p {
  margin-top: 4px;
  color: var(--text-secondary);
  font-size: 13px;
  word-break: break-word;
}

.model-instance-card__tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.model-instance-card__meta {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px 12px;
  margin: 0;
}

.model-instance-card__meta dt {
  margin: 0 0 2px;
  color: var(--text-muted);
  font-size: 12px;
}

.model-instance-card__meta dd {
  margin: 0;
  color: var(--text-primary);
  font-size: 13px;
  font-weight: 600;
  word-break: break-word;
}

.model-instance-card__remark {
  color: var(--text-muted);
  font-size: 12px;
  line-height: 1.45;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.model-instance-card__test-failure {
  display: grid;
  gap: 3px;
  padding: 10px 12px;
  border: 1px solid color-mix(in srgb, var(--el-color-danger) 28%, var(--border-glass));
  border-radius: var(--radius-sm, 10px);
  background: color-mix(in srgb, var(--el-color-danger) 7%, var(--bg-card));
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.5;
}

.model-instance-card__test-failure strong {
  color: var(--el-color-danger);
  font-size: 12px;
}

.model-instance-card__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: auto;
}

@media (prefers-reduced-motion: reduce) {
  .model-instance-card {
    transition: none;
  }

  .model-instance-card:hover {
    transform: none;
  }
}
</style>
