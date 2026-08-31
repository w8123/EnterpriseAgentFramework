<template>
  <article
    :class="['api-card', `api-card--${categoryTone}`]"
    tabindex="0"
    @click="emit('open', entry)"
    @keydown.enter="emit('open', entry)"
  >
    <header class="api-card__header">
      <div class="api-card__mark" aria-hidden="true">
        <component :is="categoryIcon" />
      </div>
      <div class="api-card__identity">
        <div class="api-card__provider">
          <span>{{ entry.provider?.name || '社区提供者' }}</span>
          <el-icon v-if="entry.provider?.verified" title="供应商信息已核对"><CircleCheckFilled /></el-icon>
        </div>
        <h3>{{ entry.title }}</h3>
      </div>
      <StatusTag
        :label="formatApiMarketVerification(entry.verificationStatus)"
        :tone="apiMarketVerificationTone(entry.verificationStatus)"
        :pulse="entry.verificationStatus === 'VERIFIED'"
      />
    </header>

    <p class="api-card__summary">{{ entry.summary }}</p>

    <div class="api-card__tags">
      <el-tag size="small" effect="plain">{{ formatApiMarketCategory(entry.categoryCode) }}</el-tag>
      <el-tag v-for="tag in entry.tags.slice(0, 2)" :key="tag" size="small" effect="plain" type="info">
        {{ tag }}
      </el-tag>
    </div>

    <dl class="api-card__facts">
      <div>
        <dt><el-icon><Key /></el-icon>认证</dt>
        <dd>{{ formatApiMarketAuth(entry.authType) }}</dd>
      </div>
      <div>
        <dt><el-icon><Money /></el-icon>费用</dt>
        <dd>{{ formatApiMarketPricing(entry.pricingType) }}</dd>
      </div>
      <div>
        <dt><el-icon><DocumentChecked /></el-icon>契约</dt>
        <dd>{{ formatApiMarketSpec(entry.specStatus) }}</dd>
      </div>
    </dl>

    <footer class="api-card__footer">
      <span class="api-card__source">
        <el-icon><Link /></el-icon>
        {{ entry.source?.name || '人工维护' }}
      </span>
      <div class="api-card__actions">
        <el-button text :icon="ArrowRight" @click.stop="emit('open', entry)">查看</el-button>
        <el-button type="primary" @click.stop="emit('integrate', entry)">接入 API</el-button>
      </div>
    </footer>
  </article>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import {
  ArrowRight,
  ChatLineRound,
  CircleCheckFilled,
  Cloudy,
  Connection,
  DataAnalysis,
  DocumentChecked,
  Key,
  Link,
  LocationInformation,
  Money,
  Tools,
} from '@element-plus/icons-vue'
import StatusTag from '@/components/common/StatusTag.vue'
import type { ApiMarketEntrySummary } from '@/types/apiMarket'
import {
  apiMarketVerificationTone,
  formatApiMarketAuth,
  formatApiMarketCategory,
  formatApiMarketPricing,
  formatApiMarketSpec,
  formatApiMarketVerification,
} from '@/utils/apiMarketLabels'

const props = defineProps<{ entry: ApiMarketEntrySummary }>()

const categoryIcons = {
  AI: DataAnalysis,
  BUSINESS: Connection,
  DATA: DataAnalysis,
  DEVELOPER: Tools,
  FINANCE: Money,
  GEO: LocationInformation,
  MEDIA: DataAnalysis,
  SCIENCE: DataAnalysis,
  SOCIAL: Connection,
  TEXT: ChatLineRound,
  WEATHER: Cloudy,
  OTHER: Connection,
} as const

const categoryIcon = computed(() => (
  categoryIcons[props.entry.categoryCode as keyof typeof categoryIcons] || Connection
))
const categoryTone = computed(() => (props.entry.categoryCode || 'OTHER').toLowerCase())

const emit = defineEmits<{
  open: [entry: ApiMarketEntrySummary]
  integrate: [entry: ApiMarketEntrySummary]
}>()
</script>

<style scoped lang="scss">
.api-card {
  --api-tone-rgb: var(--brand-primary-rgb);
  position: relative;
  display: flex;
  min-width: 0;
  min-height: 270px;
  flex-direction: column;
  gap: 14px;
  overflow: hidden;
  padding: 18px;
  border: 1px solid var(--border-subtle);
  border-radius: 16px;
  outline: none;
  background:
    radial-gradient(circle at 100% 0, rgb(var(--api-tone-rgb) / 0.08), transparent 34%),
    var(--surface-glass-panel);
  box-shadow: var(--shadow-sm);
  cursor: pointer;
  transition:
    transform var(--motion-duration-fast) var(--motion-easing-standard),
    border-color var(--motion-duration-fast) ease,
    box-shadow var(--motion-duration-fast) ease;

  &::before {
    content: '';
    position: absolute;
    top: 0;
    right: 18px;
    left: 18px;
    height: 2px;
    border-radius: 0 0 999px 999px;
    background: linear-gradient(90deg, rgb(var(--api-tone-rgb) / 0.9), rgb(var(--api-tone-rgb) / 0));
    opacity: 0.6;
  }
}

.api-card--weather { --api-tone-rgb: 14 165 233; }
.api-card--developer { --api-tone-rgb: 124 58 237; }
.api-card--geo { --api-tone-rgb: 16 185 129; }
.api-card--text { --api-tone-rgb: 236 72 153; }
.api-card--finance,
.api-card--business { --api-tone-rgb: 245 158 11; }
.api-card--science,
.api-card--data,
.api-card--ai { --api-tone-rgb: 6 182 212; }

.api-card:hover,
.api-card:focus-visible {
  border-color: rgb(var(--api-tone-rgb) / 0.36);
  box-shadow: 0 18px 38px -26px rgb(var(--api-tone-rgb) / 0.55), var(--shadow-md);
  transform: translateY(-2px);

  &::before {
    opacity: 1;
  }
}

.api-card:focus-visible {
  outline: 2px solid var(--border-focus);
  outline-offset: 2px;
}

.api-card__header,
.api-card__footer,
.api-card__provider,
.api-card__actions,
.api-card__source {
  display: flex;
  align-items: center;
}

.api-card__header {
  gap: 12px;
}

.api-card__mark {
  display: grid;
  width: 42px;
  height: 42px;
  flex: 0 0 42px;
  place-items: center;
  border: 1px solid rgb(var(--api-tone-rgb) / 0.18);
  border-radius: 12px;
  background: rgb(var(--api-tone-rgb) / 0.1);
  color: rgb(var(--api-tone-rgb));
  font-size: 19px;
  font-weight: 800;
}

.api-card__identity {
  min-width: 0;
  flex: 1;

  h3 {
    margin: 3px 0 0;
    overflow: hidden;
    color: var(--text-primary);
    font-size: 17px;
    font-weight: 750;
    letter-spacing: -0.015em;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.api-card__provider {
  gap: 5px;
  overflow: hidden;
  color: var(--text-muted);
  font-size: 12px;

  span {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .el-icon {
    flex: 0 0 auto;
    color: var(--status-success);
  }
}

.api-card__summary {
  display: -webkit-box;
  min-height: 44px;
  margin: 0;
  overflow: hidden;
  color: var(--text-secondary);
  font-size: 13px;
  line-height: 1.58;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.api-card__tags {
  display: flex;
  min-height: 24px;
  flex-wrap: wrap;
  gap: 6px;

  :deep(.el-tag) {
    border-radius: 999px;
    background: var(--surface-solid-control);
    font-size: 10px;
  }
}

.api-card__facts {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 6px;
  margin: 0;
  padding: 0;

  div {
    display: flex;
    min-width: 0;
    flex-direction: column;
    gap: 3px;
    padding: 9px 8px;
    border: 1px solid var(--border-divider);
    border-radius: 9px;
    background: color-mix(in srgb, var(--surface-solid-control) 84%, transparent);
  }

  dt {
    display: flex;
    align-items: center;
    gap: 4px;
    color: var(--text-muted);
    font-size: 9px;

    .el-icon {
      font-size: 10px;
    }
  }

  dd {
    margin: 0;
    overflow: hidden;
    color: var(--text-primary);
    font-size: 11px;
    font-weight: 650;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.api-card__footer {
  justify-content: space-between;
  gap: 12px;
  margin-top: auto;
  padding-top: 12px;
  border-top: 1px solid var(--border-divider);
}

.api-card__source {
  min-width: 0;
  gap: 5px;
  overflow: hidden;
  color: var(--text-muted);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.api-card__actions {
  flex: 0 0 auto;
  gap: 4px;

  :deep(.el-button + .el-button) {
    margin-left: 0;
  }

  :deep(.el-button) {
    min-height: 32px;
    border-radius: 9px;
    font-size: 11px;
    font-weight: 680;
  }

  :deep(.el-button--primary) {
    border-color: rgb(var(--api-tone-rgb) / 0.78);
    background: rgb(var(--api-tone-rgb) / 0.9);
  }
}

@media (max-width: 520px) {
  .api-card__facts {
    grid-template-columns: 1fr;
    gap: 8px;

    div {
      padding: 0;
      border: 0;
    }
  }

  .api-card__footer {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
