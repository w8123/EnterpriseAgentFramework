<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowRight, Connection, Promotion, Refresh } from '@element-plus/icons-vue'
import { getMcpHubOverview } from '@/api/mcp'
import mcpInterconnectionOnboardingIllustration from '@/assets/illustrations/mcp-interconnection-onboarding-flat.webp'
import type { McpHubOverview } from '@/types/mcp'

const router = useRouter()
const loading = ref(false)
const error = ref('')
const days = ref(7)
const overview = ref<McpHubOverview | null>(null)

const isFirstUse = computed(() => overview.value !== null && overview.value.publications === 0)

const metrics = computed(() => {
  const value = overview.value
  const outboundCalls = value?.outboundCalls ?? 0
  const successRate = outboundCalls > 0 && value
    ? `${(value.outboundSuccessRate * 100).toFixed(1)}%`
    : '—'
  const p95 = outboundCalls > 0 && value?.outboundP95Ms != null
    ? `P95 ${value.outboundP95Ms} ms`
    : 'P95 暂无样本'

  return [
    {
      label: '发布中',
      value: value?.publishedPublications ?? 0,
      meta: `${value?.publications ?? 0} 个发布单元`,
      route: '/mcp-hub/publications',
      tone: 'primary',
    },
    {
      label: '活跃凭证',
      value: value?.activeClients ?? 0,
      meta: value?.expiringCredentials
        ? `${value.expiringCredentials} 个即将过期`
        : '暂无临期凭证',
      route: '/mcp-hub/publications',
      tone: value?.expiringCredentials ? 'attention' : 'default',
    },
    {
      label: `近 ${days.value} 天调用`,
      value: new Intl.NumberFormat('zh-CN').format(outboundCalls),
      meta: '外部 Client 调用',
      route: '/mcp-hub/call-logs',
      tone: 'default',
    },
    {
      label: '调用成功率',
      value: successRate,
      meta: p95,
      route: '/mcp-hub/call-logs',
      tone: 'default',
    },
  ]
})

const nextStep = computed(() => {
  const value = overview.value
  if (!value || value.publications === 0) return null
  if (value.publishedPublications === 0) {
    return {
      title: '继续完成第一个发布',
      description: '添加能力或 Workflow，并通过预检后发布。',
      action: '继续配置',
    }
  }
  if (value.activeClients === 0) {
    return {
      title: '发布已生效，下一步创建凭证',
      description: '在发布详情中创建 Client，API Key 只显示一次。',
      action: '管理凭证',
    }
  }
  if (value.outboundCalls === 0) {
    return {
      title: '接入已就绪，等待首次调用',
      description: '使用发布详情中的端点和 API Key 发起 tools/list。',
      action: '查看接入配置',
    }
  }
  return null
})

async function reload() {
  loading.value = true
  error.value = ''
  try {
    const { data } = await getMcpHubOverview(days.value)
    overview.value = data
  } catch (err) {
    error.value = err instanceof Error ? err.message : '加载 MCP 互联中心总览失败'
  } finally {
    loading.value = false
  }
}

function switchDays(value: number) {
  days.value = value
  reload()
}

function openPublications() {
  router.push('/mcp-hub/publications')
}

onMounted(reload)
</script>

<template>
  <section class="hub-overview" v-loading="loading">
    <el-alert
      v-if="error"
      type="error"
      show-icon
      :closable="false"
      :title="`总览加载失败：${error}`"
      description="请检查 reachai-control-service 是否可用，然后重试。"
    >
      <el-button type="primary" size="small" @click="reload">重试</el-button>
    </el-alert>

    <section v-else-if="loading && !overview" class="loading-shell" aria-label="正在加载 MCP 总览">
      <el-skeleton animated :rows="4" />
    </section>

    <section v-else-if="isFirstUse" class="onboarding-card" aria-labelledby="mcp-first-use-title">
      <div class="onboarding-visual">
        <img
          :src="mcpInterconnectionOnboardingIllustration"
          alt="能力和 Workflow 通过安全 MCP 网关发布给外部 AI Client 的引导插画"
          width="1254"
          height="1254"
          decoding="async"
        />
      </div>

      <div class="onboarding-content">
        <h2 id="mcp-first-use-title">发布第一个 MCP 服务</h2>
        <p>选择能力或已发布 Workflow，完成预检并创建 Client 凭证。</p>

        <div class="onboarding-actions">
          <el-button type="primary" :icon="Promotion" @click="openPublications">创建第一个发布</el-button>
          <el-button link :icon="Refresh" :loading="loading" @click="reload">刷新状态</el-button>
        </div>
      </div>
    </section>

    <section v-else-if="overview" class="operational-overview">
      <div class="overview-toolbar">
        <h2>运行概览</h2>
        <div class="toolbar-actions">
          <el-radio-group :model-value="days" size="small" @update:model-value="switchDays">
            <el-radio-button :value="7">近 7 天</el-radio-button>
            <el-radio-button :value="14">近 14 天</el-radio-button>
            <el-radio-button :value="30">近 30 天</el-radio-button>
          </el-radio-group>
          <el-button :icon="Refresh" :loading="loading" aria-label="刷新总览" @click="reload" />
        </div>
      </div>

      <section class="metric-grid" aria-label="MCP 互联核心指标">
        <button
          v-for="item in metrics"
          :key="item.label"
          class="metric-card"
          :class="[`is-${item.tone}`]"
          type="button"
          @click="router.push(item.route)"
        >
          <span>{{ item.label }}</span>
          <strong>{{ item.value }}</strong>
          <small>{{ item.meta }}</small>
        </button>
      </section>

      <section v-if="nextStep" class="next-step-panel" aria-live="polite">
        <div class="next-step-panel__icon"><Connection /></div>
        <div>
          <h3>{{ nextStep.title }}</h3>
          <p>{{ nextStep.description }}</p>
        </div>
        <el-button type="primary" :icon="ArrowRight" @click="openPublications">
          {{ nextStep.action }}
        </el-button>
      </section>
    </section>
  </section>
</template>

<style scoped lang="scss">
.hub-overview,
.operational-overview {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: var(--layout-page-gap);
}

.hub-overview {
  min-height: 320px;
}

.loading-shell {
  min-height: 280px;
  padding: 28px;
  border: 1px solid var(--border-divider);
  border-radius: var(--radius-xl);
  background: var(--surface-glass-panel);
}

.onboarding-card {
  position: relative;
  isolation: isolate;
  display: grid;
  min-height: clamp(320px, 38vh, 390px);
  grid-template-columns: minmax(220px, 290px) minmax(0, 560px);
  align-items: center;
  justify-content: center;
  gap: clamp(36px, 5vw, 72px);
  overflow: hidden;
  padding: clamp(26px, 3.5vw, 42px);
  border: 1px dashed var(--border-readable);
  border-radius: var(--radius-xl);
  background:
    linear-gradient(
      135deg,
      color-mix(in srgb, var(--brand-primary) 6%, var(--surface-solid-panel)),
      color-mix(in srgb, var(--surface-solid-control) 82%, transparent)
    );
  box-shadow: var(--inner-highlight);
}

.onboarding-card::after {
  content: '';
  position: absolute;
  right: -90px;
  bottom: -130px;
  z-index: -1;
  width: 360px;
  height: 360px;
  border: 1px solid rgb(var(--brand-primary-rgb) / 0.12);
  border-radius: 50%;
}

.onboarding-visual {
  position: relative;
  z-index: 1;
  width: 100%;
  aspect-ratio: 1;
  overflow: hidden;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-lg);
  background: var(--surface-solid-panel);
  box-shadow: var(--shadow-card), var(--inner-highlight);
}

.onboarding-visual img {
  display: block;
  width: 100%;
  height: 100%;
  border-radius: inherit;
  object-fit: cover;
}

.onboarding-content {
  position: relative;
  z-index: 1;
  min-width: 0;
}

.onboarding-content h2,
.onboarding-content p {
  margin: 0;
}

.onboarding-content h2 {
  color: var(--text-primary);
  font-size: clamp(24px, 2.2vw, 32px);
  line-height: 1.2;
  letter-spacing: -0.025em;
}

.onboarding-content p {
  max-width: 620px;
  margin-top: 9px;
  color: var(--text-secondary);
  font-size: 14px;
  line-height: 1.6;
}

.onboarding-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 24px;
}

.overview-toolbar {
  display: flex;
  min-height: 38px;
  align-items: center;
  justify-content: space-between;
  gap: var(--section-gap);
}

.overview-toolbar h2 {
  margin: 0;
  color: var(--text-primary);
  font-size: 18px;
}

.toolbar-actions {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 10px;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}

.metric-card {
  position: relative;
  min-width: 0;
  min-height: 128px;
  padding: 18px;
  overflow: hidden;
  border: 1px solid var(--border-divider);
  border-radius: var(--radius-lg);
  color: var(--text-primary);
  text-align: left;
  background: var(--surface-glass-panel);
  box-shadow: var(--inner-highlight);
  cursor: pointer;
  transition: border-color var(--motion-duration-fast) ease, transform var(--motion-duration-fast) ease;
}

.metric-card::before {
  content: '';
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 3px;
  background: transparent;
}

.metric-card.is-primary::before {
  background: var(--brand-primary);
}

.metric-card.is-attention::before {
  background: var(--status-warning);
}

.metric-card:hover {
  border-color: rgb(var(--brand-primary-rgb) / 0.38);
  transform: translateY(-1px);
}

.metric-card span,
.metric-card small {
  display: block;
  color: var(--text-muted);
  font-size: 12px;
}

.metric-card strong {
  display: block;
  margin: 12px 0 8px;
  font-size: 28px;
  line-height: 1;
}

.metric-card.is-attention small {
  color: var(--status-warning);
}

.next-step-panel {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 14px;
  padding: 16px 18px;
  border: 1px solid rgb(var(--brand-primary-rgb) / 0.18);
  border-radius: var(--radius-lg);
  background: var(--surface-glass-control);
  box-shadow: var(--inner-highlight);
}

.next-step-panel__icon {
  display: inline-flex;
  width: 40px;
  height: 40px;
  align-items: center;
  justify-content: center;
  border-radius: 12px;
  color: var(--brand-active);
  background: var(--surface-glass-selected);
}

.next-step-panel h3,
.next-step-panel p {
  margin: 0;
}

.next-step-panel h3 {
  color: var(--text-primary);
  font-size: 14px;
}

.next-step-panel p {
  margin-top: 4px;
  color: var(--text-muted);
  font-size: 12px;
}

@media (max-width: 1180px) {
  .metric-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 900px) {
  .onboarding-card {
    grid-template-columns: minmax(180px, 220px) minmax(0, 1fr);
    justify-content: stretch;
    gap: 24px;
    padding: 24px;
  }
}

@media (max-width: 640px) {
  .onboarding-card {
    min-height: auto;
    grid-template-columns: minmax(0, 1fr);
    justify-items: center;
    padding: 24px 20px;
  }

  .onboarding-visual {
    width: min(200px, 100%);
  }

  .onboarding-content {
    display: flex;
    flex-direction: column;
    align-items: center;
    text-align: center;
  }

  .onboarding-actions,
  .overview-toolbar,
  .toolbar-actions {
    align-items: stretch;
    flex-direction: column;
  }

  .metric-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .next-step-panel {
    grid-template-columns: auto minmax(0, 1fr);
  }

  .next-step-panel > .el-button {
    grid-column: 1 / -1;
  }
}
</style>
