<script setup lang="ts">
import { computed } from 'vue'
import {
  CircleCheck,
  CircleClose,
  InfoFilled,
  Monitor,
  Refresh,
  Warning,
} from '@element-plus/icons-vue'
import type {
  PageIntegrationReadiness,
  PageIntegrationReadinessItem,
  ProjectPage,
} from '@/types/pageWorkbench'
import { pageWorkbenchReadinessStatusLabel } from '@/utils/pageWorkbenchPresentation'

const props = defineProps<{
  page: ProjectPage
  readiness?: PageIntegrationReadiness | null
  readinessLoading?: boolean
}>()

const emit = defineEmits<{
  check: [page: ProjectPage]
}>()

const statusCounts = computed(() => {
  const counts = { PASS: 0, PENDING: 0, FAIL: 0, WARN: 0 }
  for (const item of props.readiness?.items || []) {
    counts[item.status] += 1
  }
  return counts
})

function readinessType(item: PageIntegrationReadinessItem) {
  if (item.status === 'PASS') return 'success'
  if (item.status === 'FAIL') return 'danger'
  if (item.status === 'WARN') return 'warning'
  return 'info'
}

function readinessIcon(status: PageIntegrationReadinessItem['status']) {
  if (status === 'PASS') return CircleCheck
  if (status === 'FAIL') return CircleClose
  if (status === 'WARN') return Warning
  return InfoFilled
}

function formatDateTime(value?: string) {
  if (!value) return '尚未记录'
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}
</script>

<template>
  <section
    v-loading="readinessLoading"
    class="page-diagnostics-inline"
    role="region"
    aria-label="接入诊断"
  >
    <header class="page-diagnostics-inline__hero">
      <span class="page-diagnostics-inline__hero-icon" aria-hidden="true">
        <el-icon><Monitor /></el-icon>
      </span>
      <div class="page-diagnostics-inline__hero-copy">
        <small>接入诊断</small>
        <h2>页面接入状态</h2>
        <p>以 ReachAI 平台观测到的事实为准，异常只影响对应能力。</p>
      </div>
      <el-button
        :icon="Refresh"
        :loading="readinessLoading"
        @click="emit('check', page)"
      >
        重新检查
      </el-button>
    </header>

    <template v-if="readiness">
      <div
        class="embedded-readiness-summary"
        :class="`is-${readiness.status.toLowerCase()}`"
      >
        <div class="embedded-readiness-summary__main">
          <strong>{{ readiness.message }}</strong>
          <small>检查于 {{ formatDateTime(readiness.checkedAt) }}</small>
        </div>
        <dl class="embedded-readiness-metrics" aria-label="检查项统计">
          <div>
            <dt>通过</dt>
            <dd>{{ statusCounts.PASS }}</dd>
          </div>
          <div>
            <dt>待确认</dt>
            <dd>{{ statusCounts.PENDING }}</dd>
          </div>
          <div>
            <dt>需注意</dt>
            <dd>{{ statusCounts.WARN }}</dd>
          </div>
          <div>
            <dt>未通过</dt>
            <dd>{{ statusCounts.FAIL }}</dd>
          </div>
        </dl>
      </div>

      <div class="embedded-readiness-grid">
        <article
          v-for="item in readiness.items"
          :key="item.key"
          :class="`is-${item.status.toLowerCase()}`"
        >
          <span class="embedded-readiness-mark" aria-hidden="true">
            <el-icon>
              <component :is="readinessIcon(item.status)" />
            </el-icon>
          </span>
          <span class="embedded-readiness-copy">
            <header>
              <strong>{{ item.label }}</strong>
              <el-tag
                :type="readinessType(item)"
                effect="light"
                size="small"
                round
              >
                {{ pageWorkbenchReadinessStatusLabel(item.status) }}
              </el-tag>
            </header>
            <p>{{ item.message }}</p>
          </span>
        </article>
      </div>

      <p class="page-diagnostics-inline__note">
        <el-icon><InfoFilled /></el-icon>
        <span>异常只影响对应能力，不会伪造其他区域状态。</span>
      </p>
    </template>

    <div
      v-else-if="!readinessLoading"
      class="page-diagnostics-inline__empty"
    >
      <el-icon><Monitor /></el-icon>
      <strong>还没有诊断结果</strong>
      <p>重新检查以读取页面接入状态</p>
      <el-button
        type="primary"
        :icon="Refresh"
        @click="emit('check', page)"
      >
        重新检查
      </el-button>
    </div>
  </section>
</template>
