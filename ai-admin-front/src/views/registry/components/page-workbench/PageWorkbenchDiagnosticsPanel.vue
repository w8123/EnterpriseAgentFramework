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
  const counts: Record<PageIntegrationReadinessItem['status'], number> = {
    PASS: 0,
    PENDING: 0,
    FAIL: 0,
    WARN: 0,
    NOT_REQUIRED: 0,
  }
  for (const item of props.readiness?.items || []) {
    counts[item.status] += 1
  }
  return counts
})

const recommendedNextStep = computed(() => {
  const readiness = props.readiness
  if (!readiness) return null

  const firstAttentionItem = readiness.items.find(
    (item) => !['PASS', 'NOT_REQUIRED'].includes(item.status),
  )
  if (!firstAttentionItem) {
    return {
      status: 'PASS' as const,
      title: '接入检查已通过',
      message: '页面信息、操作注册和最近一次真实执行均已验证。若当前页面已完成真实浏览器验收，无需重复操作；可在“接入历程”查看发布版本与证据，或继续接入下一个页面。',
    }
  }

  const guidanceByKey: Record<string, { title: string; message: string }> = {
    PAGE_DEFINITION_READY: {
      title: '先补齐页面定位信息',
      message: '回到页面资源，补充路由或组件路径；保存后重新检查。ReachAI 需要这一信息将页面操作、浏览器会话和验收证据归到同一个业务页面。',
    },
    PAGE_ACTION_CATALOG_READY: {
      title: '先建立可调用的页面操作',
      message: '通过 AI Coding 生成页面操作，或由 SDK 上报 @ReachCapability；至少提供一个可验证的只读操作，再回到这里重新检查。',
    },
    PAGE_BRIDGE_SESSION_READY: {
      title: '打开目标业务页面建立会话',
      message: '确认业务页面已挂载 ReachAI Embed SDK 和对话入口，然后在该页面刷新一次。平台观测到真实 Page Bridge 会话后，会自动更新此项。',
    },
    PAGE_ACTION_BINDING_READY: {
      title: '对齐页面操作的 actionKey',
      message: '在业务页面的 registerAction(...) 中注册目录里的每个页面操作，并确保 actionKey 完全一致；刷新业务页面后重新检查。',
    },
    PAGE_ACTION_RUNTIME_READY: {
      title: '执行一次真实页面操作',
      message: '在业务页面的嵌入对话中发起一个已发布的只读查询，例如“查询课程列表”。收到真实结果后重新检查，平台不会把模拟结果当作验收证据。',
    },
    PAGE_BROWSER_E2E_READY: {
      title: '完成浏览器端真实对话验收',
      message: '在当前业务页面通过 ReachAI 对话入口发送一条真实消息并收到回复，再刷新验收任务。只有浏览器侧实际会话会让这项通过。',
    },
  }

  const guidance = guidanceByKey[firstAttentionItem.key]
  return {
    status: firstAttentionItem.status,
    title: guidance?.title || '处理当前待确认项',
    message: guidance?.message
      || `${firstAttentionItem.message} 处理完成后重新检查，以平台观测到的事实为准。`,
  }
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
          <div>
            <dt>无需验收</dt>
            <dd>{{ statusCounts.NOT_REQUIRED }}</dd>
          </div>
        </dl>
      </div>

      <section
        v-if="recommendedNextStep"
        class="page-diagnostics-next-step"
        :class="`is-${recommendedNextStep.status.toLowerCase()}`"
        aria-live="polite"
      >
        <el-icon aria-hidden="true">
          <CircleCheck v-if="recommendedNextStep.status === 'PASS'" />
          <InfoFilled v-else />
        </el-icon>
        <div>
          <small>建议下一步</small>
          <strong>{{ recommendedNextStep.title }}</strong>
          <p>{{ recommendedNextStep.message }}</p>
        </div>
      </section>

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
