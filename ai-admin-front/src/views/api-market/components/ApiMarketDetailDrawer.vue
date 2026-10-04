<template>
  <AppDrawer
    :model-value="modelValue"
    title="API 资产详情"
    description="核对能力、Operation、版本和质量证据，再决定是否接入项目。"
    size="min(760px, 92vw)"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div v-loading="loading" class="detail-drawer">
      <template v-if="detail">
        <section class="detail-identity">
          <div class="detail-identity__mark"><Connection /></div>
          <div class="detail-identity__content">
            <p>
              {{ detail.entry.provider?.name || '社区提供者' }}
              <span>·</span>
              {{ detail.entry.source?.name || '人工维护' }}
            </p>
            <h2>{{ detail.entry.title }}</h2>
            <span class="detail-identity__summary">{{ detail.entry.summary }}</span>
            <div class="detail-badges">
              <StatusTag
                :label="formatApiMarketVerification(detail.entry.verificationStatus)"
                :tone="apiMarketVerificationTone(detail.entry.verificationStatus)"
              />
              <el-tag size="small" effect="plain">{{ formatApiMarketAuth(detail.entry.authType) }}</el-tag>
              <el-tag size="small" effect="plain">{{ formatApiMarketPricing(detail.entry.pricingType) }}</el-tag>
              <el-tag size="small" effect="plain">{{ formatApiMarketSpec(detail.entry.specStatus) }}</el-tag>
            </div>
          </div>
        </section>

        <el-tabs v-model="activeTab" class="detail-tabs">
          <el-tab-pane label="概览" name="overview">
            <section class="detail-section">
              <h3>它能做什么</h3>
              <p>{{ detail.description || detail.entry.summary }}</p>
            </section>

            <section class="detail-grid">
              <div>
                <span>分类</span>
                <strong>{{ formatApiMarketCategory(detail.entry.categoryCode) }}</strong>
              </div>
              <div>
                <span>HTTPS</span>
                <strong>{{ detail.entry.httpsSupported ? '支持' : '未确认' }}</strong>
              </div>
              <div>
                <span>CORS</span>
                <strong>{{ detail.entry.corsPolicy || '未知' }}</strong>
              </div>
              <div>
                <span>最近验证</span>
                <strong>{{ formatDate(detail.entry.lastVerifiedAt) }}</strong>
              </div>
            </section>

            <section class="detail-section">
              <h3>来源与责任边界</h3>
              <p>
                条目来源于 {{ detail.entry.source?.name || '人工维护' }}。市场中的价格、限流和可用性是带时间戳的目录信息，
                实际使用仍以供应商官方条款和运行时测试为准。
              </p>
              <div class="detail-links">
                <el-link v-if="detail.docsUrl" :href="detail.docsUrl" target="_blank" rel="noopener noreferrer" type="primary">
                  官方文档 <el-icon><TopRight /></el-icon>
                </el-link>
                <el-link v-if="detail.termsUrl" :href="detail.termsUrl" target="_blank" rel="noopener noreferrer">
                  服务条款 <el-icon><TopRight /></el-icon>
                </el-link>
                <el-link
                  v-if="detail.entry.source?.sourceUrl"
                  :href="detail.entry.source.sourceUrl"
                  target="_blank"
                  rel="noopener noreferrer"
                >
                  查看来源 <el-icon><TopRight /></el-icon>
                </el-link>
              </div>
            </section>
          </el-tab-pane>

          <el-tab-pane :label="`Operations · ${currentVersion?.operations.length || 0}`" name="operations">
            <div class="version-row">
              <span>API 版本</span>
              <el-select v-model="activeVersionId" style="width: 220px">
                <el-option
                  v-for="version in detail.versions"
                  :key="version.id"
                  :label="version.versionKey"
                  :value="version.id"
                />
              </el-select>
            </div>

            <el-empty v-if="!currentVersion?.operations.length" description="当前版本没有可接入的 Operation" />
            <div v-else class="operation-list">
              <article v-for="operation in currentVersion.operations" :key="operation.id" class="operation-card">
                <div class="operation-card__main">
                  <div class="operation-card__title">
                    <el-tag size="small" :type="methodTagType(operation.httpMethod)" effect="dark">
                      {{ operation.httpMethod }}
                    </el-tag>
                    <strong>{{ operation.title }}</strong>
                    <StatusTag
                      :label="formatApiMarketSideEffect(operation.sideEffect)"
                      :tone="apiMarketSideEffectTone(operation.sideEffect)"
                    />
                  </div>
                  <code>{{ operation.path }}</code>
                  <p>{{ operation.description || '暂无补充说明' }}</p>
                  <p>来源建议地址：{{ currentVersion.baseUrl }}；不自动配置连接或授权出口。</p>
                  <el-table v-if="schemaFields(operation.requestSchema).length" :data="schemaFields(operation.requestSchema)" size="small">
                    <el-table-column prop="name" label="参数" min-width="120" />
                    <el-table-column prop="location" label="位置" width="90" />
                    <el-table-column prop="type" label="类型" width="90" />
                    <el-table-column prop="required" label="必填" width="70" />
                  </el-table>
                  <p v-else>参数声明为空或不可读取；不从示例猜测参数及位置。</p>
                  <p>返回：HTTP {{ operation.responseStatus ?? '未声明' }} · {{ operation.responseContentType || '媒体未声明' }}</p>
                  <p>声明返回字段：{{ schemaFields(operation.responseSchema).map(field => `${field.name} (${field.type})`).join('、') || '当前不可读取，不视为没有返回字段' }}</p>
                  <p v-if="operationBlocker(operation)" role="status">{{ operationBlocker(operation) }}</p>
                </div>
                <el-button type="primary" :disabled="Boolean(operationBlocker(operation))" @click="emit('integrate', currentVersion, operation)">
                  接入此 Operation
                </el-button>
              </article>
            </div>
          </el-tab-pane>

          <el-tab-pane label="版本与治理" name="governance">
            <section class="detail-section governance-copy">
              <el-alert
                title="版本固定，不自动升级"
                description="项目接入固定来源版本及 Operation。所属 API 经接纳、连接与真实 Console 验证后，Workflow 使用稳定 API 引用；发布固定可信修订，目录新版本不自动替换选择或旧 pin。"
                type="info"
                :closable="false"
                show-icon
              />
              <section class="evidence-section">
                <div class="evidence-section__heading">
                  <div>
                    <h3>最近验证证据</h3>
                    <p>这里是全局目录质量证据，不代表当前项目绑定已验证；项目验证请到所属 API 查看 Runtime Run/Trace。</p>
                  </div>
                  <el-tag size="small" effect="plain">{{ detail.verifications.length }} 条</el-tag>
                </div>
                <el-empty v-if="!detail.verifications.length" description="尚无验证证据" :image-size="64" />
                <article
                  v-for="verification in detail.verifications"
                  :key="verification.id"
                  class="verification-card"
                >
                  <header>
                    <strong>{{ verification.verificationType }}</strong>
                    <StatusTag
                      :label="formatApiMarketVerification(verification.status)"
                      :tone="apiMarketVerificationTone(verification.status)"
                    />
                  </header>
                  <p>{{ verification.evidenceSummary || '未提供补充摘要' }}</p>
                  <div class="verification-card__facts">
                    <span>HTTP {{ verification.httpStatus ?? '—' }}</span>
                    <span>{{ verification.latencyMs == null ? '时延未知' : `${verification.latencyMs} ms` }}</span>
                    <span>{{ formatDate(verification.checkedAt) }}</span>
                  </div>
                  <el-link
                    v-if="verification.checkedUrl"
                    :href="verification.checkedUrl"
                    target="_blank"
                    rel="noopener noreferrer"
                    type="primary"
                  >
                    {{ verification.checkedUrl }} <el-icon><TopRight /></el-icon>
                  </el-link>
                </article>
              </section>
              <div v-for="version in detail.versions" :key="version.id" class="version-card">
                <div>
                  <strong>{{ version.versionKey }}</strong>
                  <p>{{ version.baseUrl }}</p>
                </div>
                <div class="version-card__meta">
                  <span>{{ version.operations.length }} Operations</span>
                  <span>{{ version.specHash ? `Spec ${version.specHash.slice(0, 16)}…` : '无 Spec Hash' }}</span>
                </div>
              </div>
            </section>
          </el-tab-pane>
        </el-tabs>
      </template>
    </div>
  </AppDrawer>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Connection, TopRight } from '@element-plus/icons-vue'
import AppDrawer from '@/components/common/AppDrawer.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import type { ApiMarketEntryDetail, ApiMarketOperation, ApiMarketVersion } from '@/types/apiMarket'
import {
  apiMarketSideEffectTone,
  apiMarketVerificationTone,
  formatApiMarketAuth,
  formatApiMarketCategory,
  formatApiMarketPricing,
  formatApiMarketSideEffect,
  formatApiMarketSpec,
  formatApiMarketVerification,
} from '@/utils/apiMarketLabels'

const props = defineProps<{
  modelValue: boolean
  detail: ApiMarketEntryDetail | null
  loading?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  integrate: [version: ApiMarketVersion, operation: ApiMarketOperation]
}>()

const activeTab = ref('overview')
const activeVersionId = ref<number | null>(null)
const currentVersion = computed(() => (
  props.detail?.versions.find(version => version.id === activeVersionId.value)
  || null
))

watch(
  () => props.detail?.entry.entryKey,
  () => {
    activeTab.value = 'overview'
    activeVersionId.value = props.detail?.versions[0]?.id || null
  },
  { immediate: true },
)

function methodTagType(method: string) {
  if (method === 'GET') return 'success'
  if (method === 'DELETE') return 'danger'
  if (method === 'POST') return 'warning'
  return 'info'
}

function operationBlocker(operation: ApiMarketOperation) {
  if (props.detail?.entry.authType !== 'NONE' || operation.authRequired !== false) return '结构化认证契约未支持，不能降为无认证'
  if (operation.httpMethod !== 'GET' || !['READ_ONLY', 'NONE'].includes(operation.sideEffect)) return '当前仅支持明确只读 GET'
  if (!operation.responseContentType || !operation.responseStatus) return '响应媒体/成功状态缺失，不能猜测可执行契约'
  if (!currentVersion.value || currentVersion.value.publicationStatus !== 'PUBLISHED' || operation.status !== 'ACTIVE') return '所选目录版本或 Operation 不可用，请显式重新选择'
  return ''
}

function schemaFields(schema?: Record<string, unknown> | null) {
  if (!schema || schema.type !== 'object' || !schema.properties || typeof schema.properties !== 'object' || Array.isArray(schema.properties)) return []
  const required = new Set(Array.isArray(schema.required) ? schema.required : [])
  return Object.entries(schema.properties).slice(0, 64).map(([name, value]) => {
    const property = value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {}
    return { name, location: String(property.location || '未声明'), type: String(property.type || '未知'),
      required: required.has(name) || property.required === true || property.location === 'path' ? '是' : '否' }
  })
}

function formatDate(value?: string | null) {
  if (!value) return '尚未验证'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN')
}
</script>

<style scoped lang="scss">
.detail-drawer {
  --detail-hero-start: #121c35;
  --detail-hero-end: #1c5368;
  min-height: 420px;
}

.detail-identity,
.detail-badges,
.detail-links,
.version-row,
.operation-card,
.operation-card__title,
.version-card,
.version-card__meta {
  display: flex;
  align-items: center;
}

.detail-identity {
  position: relative;
  isolation: isolate;
  gap: 15px;
  overflow: hidden;
  padding: 18px;
  border: 1px solid rgb(255 255 255 / 0.12);
  border-radius: 16px;
  background:
    radial-gradient(circle at 90% 0, rgb(100 218 233 / 0.2), transparent 36%),
    linear-gradient(135deg, var(--detail-hero-start), var(--detail-hero-end));
  box-shadow: 0 18px 36px -27px rgb(11 26 54 / 0.82);

  &::after {
    content: '';
    position: absolute;
    top: -70px;
    right: -35px;
    z-index: -1;
    width: 180px;
    height: 180px;
    border: 1px solid rgb(255 255 255 / 0.08);
    border-radius: 50%;
    box-shadow: 0 0 0 30px rgb(255 255 255 / 0.025);
  }

  p {
    margin: 0;
    color: rgb(226 236 255 / 0.62);
    font-size: 11px;
  }

  h2 {
    margin: 3px 0 0;
    color: #fff;
    font-size: 21px;
    letter-spacing: -0.025em;
  }
}

.detail-identity__content {
  position: relative;
  z-index: 1;
  min-width: 0;
  flex: 1;
}

.detail-identity__summary {
  display: block;
  margin-top: 5px;
  color: rgb(226 236 255 / 0.76);
  font-size: 12px;
  line-height: 1.5;
}

.detail-identity__mark {
  position: relative;
  z-index: 1;
  display: grid;
  width: 54px;
  height: 54px;
  flex: 0 0 54px;
  place-items: center;
  border: 1px solid rgb(255 255 255 / 0.16);
  border-radius: 15px;
  background: rgb(255 255 255 / 0.1);
  color: #8ce4ef;
  box-shadow: inset 0 1px 0 rgb(255 255 255 / 0.1);
  font-size: 23px;
  font-weight: 800;
}

.detail-badges,
.detail-links {
  flex-wrap: wrap;
  gap: 8px;
}

.detail-badges {
  margin-top: 10px;

  :deep(.el-tag) {
    border-color: rgb(255 255 255 / 0.13);
    border-radius: 999px;
    background: rgb(255 255 255 / 0.08);
    color: rgb(243 247 255 / 0.84);
  }
}

.detail-tabs {
  margin-top: 8px;

  :deep(.el-tabs__item) {
    font-weight: 650;
  }
}

.detail-section {
  padding: 18px 0;

  h3 {
    margin: 0 0 10px;
    color: var(--text-primary);
    font-size: 15px;
  }

  > p {
    margin: 0 0 14px;
    color: var(--text-secondary);
    line-height: 1.7;
  }
}

.detail-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;

  div {
    display: flex;
    flex-direction: column;
    gap: 5px;
    padding: 11px;
    border: 1px solid var(--border-subtle);
    border-radius: var(--radius-md);
    background: var(--surface-solid-control);
  }

  span {
    color: var(--text-muted);
    font-size: 12px;
  }

  strong {
    color: var(--text-primary);
    font-size: 13px;
  }
}

.version-row {
  justify-content: space-between;
  gap: 16px;
  padding: 4px 0 16px;
  color: var(--text-secondary);
  font-size: 13px;
}

.operation-list,
.governance-copy {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.evidence-section {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 16px;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  background: var(--surface-solid-control);
}

.evidence-section__heading,
.verification-card header,
.verification-card__facts {
  display: flex;
  align-items: center;
}

.evidence-section__heading {
  justify-content: space-between;
  gap: 12px;

  h3,
  p {
    margin: 0;
  }

  h3 {
    color: var(--text-primary);
    font-size: 14px;
  }

  p {
    margin-top: 4px;
    color: var(--text-muted);
    font-size: 12px;
  }
}

.verification-card {
  padding: 13px;
  border: 1px solid var(--border-divider);
  border-radius: var(--radius-sm);
  background: var(--surface-glass-panel);

  header {
    justify-content: space-between;
    gap: 12px;
  }

  strong {
    color: var(--text-primary);
    font-size: 13px;
  }

  p {
    margin: 9px 0;
    color: var(--text-secondary);
    font-size: 12px;
    line-height: 1.55;
  }

  :deep(.el-link) {
    display: inline-flex;
    max-width: 100%;
    margin-top: 9px;
    overflow: hidden;
    font-size: 11px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.verification-card__facts {
  flex-wrap: wrap;
  gap: 6px 14px;
  color: var(--text-muted);
  font-size: 11px;
}

.operation-card {
  align-items: flex-start;
  gap: 18px;
  justify-content: space-between;
  padding: 16px;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  background: var(--surface-solid-control);
}

.operation-card__main {
  min-width: 0;
  flex: 1;

  code {
    display: block;
    margin: 11px 0 8px;
    overflow: hidden;
    color: var(--brand-active);
    font-size: 12px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  p {
    margin: 0;
    color: var(--text-secondary);
    font-size: 13px;
    line-height: 1.55;
  }
}

.operation-card__title {
  flex-wrap: wrap;
  gap: 8px;

  strong {
    color: var(--text-primary);
  }
}

.version-card {
  justify-content: space-between;
  gap: 18px;
  padding: 15px;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);

  strong {
    color: var(--text-primary);
  }

  p {
    margin: 5px 0 0;
    color: var(--text-muted);
    font-family: var(--font-mono, monospace);
    font-size: 12px;
  }
}

.version-card__meta {
  align-items: flex-end;
  flex-direction: column;
  gap: 5px;
  color: var(--text-muted);
  font-size: 12px;
}

@media (max-width: 640px) {
  .detail-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .operation-card,
  .version-card {
    align-items: stretch;
    flex-direction: column;
  }

  .version-card__meta {
    align-items: flex-start;
  }
}

@media (max-width: 420px) {
  .detail-grid {
    grid-template-columns: 1fr;
  }
}
</style>
