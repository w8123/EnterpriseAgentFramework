<template>
  <LiquidGlassCard as="article" source="wallpaper" interactive
    :class="['api-card', `api-card--${categoryTone}`]"
    tabindex="0"
    @click="emit('open', entry)"
    @keydown.enter.self="emit('open', entry)"
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
      <el-tag v-for="tag in filteredTags" :key="tag" size="small" effect="plain" type="info">
        {{ tag }}
      </el-tag>
    </div>

    <dl class="api-card__facts">
      <div class="api-card__fact">
        <dt><el-icon><Key /></el-icon>认证</dt>
        <dd>{{ formatApiMarketAuth(entry.authType) }}</dd>
      </div>
      <div class="api-card__fact">
        <dt><el-icon><Money /></el-icon>费用</dt>
        <dd>{{ formatApiMarketPricing(entry.pricingType) }}</dd>
      </div>
      <div class="api-card__fact">
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
        <LiquidGlassButton size="small" :icon="ArrowRight" @click.stop="emit('open', entry)">查看</LiquidGlassButton>
        <LiquidGlassButton size="small" type="primary" @click.stop="emit('integrate', entry)">接入 API</LiquidGlassButton>
      </div>
    </footer>
  </LiquidGlassCard>
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
import { LiquidGlassCard, LiquidGlassButton } from '@/components/common/liquid-glass'
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

const filteredTags = computed(() => {
  const categoryLabel = formatApiMarketCategory(props.entry.categoryCode)?.trim().toLowerCase()
  return (props.entry.tags || [])
    .filter(tag => tag && tag.trim().toLowerCase() !== categoryLabel)
    .slice(0, 2)
})

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
  flex-direction: column;
  gap: 10px;
  padding: 14px 16px;
  overflow: hidden;
  border-radius: var(--radius-lg);
  border: 1px solid var(--border-glass-edge);
  background: var(--surface-glass-frost);
  box-shadow: var(--shadow-glass-frost);
  -webkit-backdrop-filter: blur(var(--glass-blur-frost)) saturate(var(--glass-saturation));
  backdrop-filter: blur(var(--glass-blur-frost)) saturate(var(--glass-saturation));
  cursor: pointer;
  transition:
    transform var(--motion-duration-fast) var(--motion-easing-standard),
    border-color var(--motion-duration-fast) ease,
    box-shadow var(--motion-duration-fast) ease;

  &::before {
    content: '';
    position: absolute;
    top: -50px;
    right: -30px;
    width: 120px;
    height: 120px;
    border-radius: 999px;
    background: radial-gradient(circle, rgb(var(--api-tone-rgb) / 0.12), transparent 70%);
    pointer-events: none;
  }

  &::after {
    content: '';
    position: absolute;
    top: 0;
    left: 12px;
    right: 12px;
    height: 1px;
    background: linear-gradient(90deg, transparent, rgb(255 255 255 / 0.5), transparent);
    pointer-events: none;
  }

  &:hover {
    transform: translateY(-2px);
    border-color: rgb(var(--brand-primary-rgb) / 0.4);
    box-shadow: 0 8px 20px -6px color-mix(in srgb, var(--accent-color) 20%, transparent), var(--shadow-glass-frost);
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

.api-card__header,
.api-card__footer,
.api-card__provider,
.api-card__actions,
.api-card__source {
  display: flex;
  align-items: center;
}

.api-card__header {
  gap: 10px;
  align-items: flex-start;
}

.api-card__mark {
  display: grid;
  width: 32px;
  height: 32px;
  flex: 0 0 32px;
  place-items: center;
  border: 1px solid var(--border-glass-edge);
  border-radius: 8px;
  background: color-mix(in srgb, rgb(var(--api-tone-rgb)) 10%, var(--surface-glass-inset));
  box-shadow: var(--glass-rim-double);
  color: rgb(var(--api-tone-rgb));
  font-size: 16px;
  font-weight: 700;
}

.api-card__identity {
  min-width: 0;
  flex: 1;

  h3 {
    margin: 1px 0 0;
    overflow: hidden;
    color: var(--text-primary);
    font-size: 14px;
    font-weight: 650;
    letter-spacing: -0.01em;
    line-height: 1.35;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.api-card__provider {
  gap: 4px;
  overflow: hidden;
  color: var(--text-muted);
  font-size: 11px;
  line-height: 1.2;

  span {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .el-icon {
    flex: 0 0 auto;
    color: var(--status-success);
    font-size: 12px;
  }
}

.api-card__summary {
  display: -webkit-box;
  min-height: 36px;
  margin: 0;
  overflow: hidden;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.55;
  letter-spacing: 0.01em;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.api-card__tags {
  display: flex;
  min-height: 18px;
  flex-wrap: wrap;
  gap: 5px;

  :deep(.el-tag) {
    height: 18px;
    padding: 0 6px;
    border-radius: 999px;
    border: 1px solid var(--border-glass-edge);
    background: var(--surface-glass-subtle);
    color: var(--text-secondary);
    font-size: 10.5px;
    line-height: 16px;
  }
}

.api-card__facts {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 0;
  margin: 0;
  padding: 4px 6px;
  border: 1px solid var(--border-glass-edge);
  border-radius: var(--radius-sm);
  background: var(--surface-glass-inset);

  .api-card__fact {
    display: flex;
    min-width: 0;
    flex-direction: column;
    gap: 2px;
    padding: 2px 6px;
    border: none;
    border-radius: 0;
    background: transparent;

    &:not(:first-child) {
      border-left: 1px solid var(--border-subtle);
    }
  }

  dt {
    display: flex;
    align-items: center;
    gap: 3px;
    color: var(--text-muted);
    font-size: 10px;

    .el-icon {
      font-size: 10px;
    }
  }

  dd {
    margin: 0;
    overflow: hidden;
    color: var(--text-primary);
    font-size: 11px;
    font-weight: 600;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.api-card__footer {
  justify-content: space-between;
  gap: 10px;
  margin-top: auto;
  padding-top: 8px;
  border-top: 1px solid var(--border-subtle);
}

.api-card__source {
  min-width: 0;
  gap: 4px;
  overflow: hidden;
  color: var(--text-muted);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.api-card__actions {
  flex: 0 0 auto;
  gap: 6px;

  :deep(.el-button + .el-button) {
    margin-left: 0;
  }
}

@media (max-width: 520px) {
  .api-card__facts {
    grid-template-columns: 1fr;
    gap: 4px;

    .api-card__fact {
      padding: 3px 0;

      &:not(:first-child) {
        border-left: 0;
        border-top: 1px solid var(--border-subtle);
      }
    }
  }

  .api-card__footer {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
