<template>
  <WorkbenchPage class="biz-index-detail-page">
    <PageHeader
      variant="entity"
      presentation="plain"
      action-presentation="inline"
      domain="knowledge"
      :title="indexDetail?.indexName || '索引详情'"
      show-back
      back-label="返回业务索引列表"
      @back="router.push('/biz-index')"
    >
      <template #tags>
        <el-tag effect="plain" size="small">{{ indexDetail?.indexCode || indexCode }}</el-tag>
        <StatusTag
          :label="indexDetail?.status === 'ACTIVE' ? '启用' : '停用'"
          :tone="indexDetail?.status === 'ACTIVE' ? 'success' : 'danger'"
        />
      </template>
      <template #meta>
        <MetricStrip presentation="inline" source="wallpaper" :items="statMetrics" aria-label="索引统计" />
      </template>
      <template #actions>
        <LiquidGlassButton source="wallpaper" :icon="Refresh" :loading="rebuildLoading" @click="handleRebuild">
          重建索引
        </LiquidGlassButton>
      </template>
    </PageHeader>

    <section class="biz-index-layout">
      <!-- 搜索测试：详情页的主操作，放在左侧主栏 -->
      <LiquidGlassCard source="wallpaper" class="biz-index-search" aria-labelledby="biz-index-search-title">
        <header class="section-head">
          <h2 id="biz-index-search-title">搜索测试</h2>
          <p>用一句自然语言检验这个索引能否召回想要的业务记录。</p>
        </header>
        <form class="search-form" @submit.prevent="handleSearch">
          <LiquidGlassInput
            v-model="searchQuery"
            class="search-form__input"
            :prefix-icon="Search"
            placeholder="输入语义搜索内容"
            aria-label="搜索内容"
            clearable
          />
          <LiquidGlassButton type="primary" native-type="submit" :loading="searchLoading">搜索</LiquidGlassButton>
        </form>

        <el-table v-if="searchResults.length > 0" :data="searchResults" stripe class="search-results">
          <el-table-column prop="bizId" label="业务ID" min-width="140" />
          <el-table-column prop="score" label="相似度" width="100" align="center">
            <template #default="{ row }">
              <el-tag :type="row.score >= 0.8 ? 'success' : row.score >= 0.6 ? 'warning' : 'info'" size="small">
                {{ (row.score * 100).toFixed(1) }}%
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="matchSource" label="匹配来源" width="110" align="center">
            <template #default="{ row }">
              <el-tag :type="row.matchSource === 'FIELD' ? 'primary' : 'success'" size="small" effect="plain">
                {{ row.matchSource === 'FIELD' ? '业务字段' : '附件' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="matchContent" label="匹配内容" min-width="300" show-overflow-tooltip />
          <el-table-column prop="matchFileName" label="附件名" min-width="120">
            <template #default="{ row }">{{ row.matchFileName || '-' }}</template>
          </el-table-column>
        </el-table>
        <p v-else class="search-hint" role="status">
          {{ searchExecuted ? '未找到匹配结果，换个说法或检查文本模板。' : '结果会按相似度从高到低列出，并标明命中的是业务字段还是附件。' }}
        </p>
      </LiquidGlassCard>

      <aside class="biz-index-aside">
        <LiquidGlassCard source="wallpaper" aria-labelledby="biz-index-info-title">
          <header class="section-head">
            <h2 id="biz-index-info-title">基本信息</h2>
          </header>
          <dl class="info-list">
            <div><dt>来源系统</dt><dd>{{ indexDetail?.sourceSystem || '-' }}</dd></div>
            <div><dt>Embedding 实例</dt><dd>{{ indexDetail?.embeddingModelInstanceId || '-' }}</dd></div>
            <div><dt>向量维度</dt><dd>{{ indexDetail?.dimension || '-' }}</dd></div>
            <div><dt>切分策略</dt><dd>{{ indexDetail?.splitType || '-' }}</dd></div>
            <div><dt>切分大小 / 重叠</dt><dd>{{ indexDetail?.chunkSize ?? '-' }} / {{ indexDetail?.chunkOverlap ?? '-' }}</dd></div>
            <div><dt>创建时间</dt><dd>{{ indexDetail?.createTime || '-' }}</dd></div>
            <div class="is-wide"><dt>文本模板</dt><dd><code class="template-code">{{ indexDetail?.textTemplate || '-' }}</code></dd></div>
            <div class="is-wide"><dt>备注</dt><dd>{{ indexDetail?.remark || '-' }}</dd></div>
          </dl>
        </LiquidGlassCard>

        <LiquidGlassCard source="wallpaper" aria-labelledby="biz-index-guide-title">
          <header class="section-head">
            <h2 id="biz-index-guide-title">接入指南</h2>
            <p>业务系统用项目凭证把数据推送到这个索引。</p>
          </header>
          <LiquidGlassTabs v-model="guideTab" class="guide-tabs">
            <el-tab-pane v-for="guide in guides" :key="guide.key" :label="guide.label" :name="guide.key">
              <div class="code-block">
                <div class="code-header">
                  <span>{{ guide.caption }}</span>
                  <el-button size="small" link @click="copyText(guide.code)">复制</el-button>
                </div>
                <pre><code>{{ guide.code }}</code></pre>
              </div>
            </el-tab-pane>
          </LiquidGlassTabs>
        </LiquidGlassCard>
      </aside>
    </section>
  </WorkbenchPage>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import PageHeader from '@/components/common/PageHeader.vue'
import MetricStrip from '@/components/common/MetricStrip.vue'
import type { MetricStripItem } from '@/components/common/glassWorkbench'
import { LiquidGlassButton, LiquidGlassCard, LiquidGlassInput, LiquidGlassTabs } from '@/components/common/liquid-glass'
import StatusTag from '@/components/common/StatusTag.vue'
import { getBizIndexDetail, getBizIndexStats, bizIndexRebuild, bizIndexSearch } from '@/api/bizIndex'
import type { BizIndex, BizIndexStats, BizSearchItem } from '@/types/bizIndex'

const route = useRoute()
const router = useRouter()
const indexCode = route.params.code as string

const indexDetail = ref<BizIndex | null>(null)
const stats = ref<BizIndexStats | null>(null)
const statsLoading = ref(false)
const rebuildLoading = ref(false)

const searchQuery = ref('')
const searchLoading = ref(false)
const searchResults = ref<BizSearchItem[]>([])
const searchExecuted = ref(false)

const statMetrics = computed<MetricStripItem[]>(() => [
  { key: 'records', label: '索引记录', value: statsLoading.value && !stats.value ? '…' : stats.value?.recordCount ?? 0 },
  { key: 'attachments', label: '含附件记录', value: stats.value?.attachmentRecordCount ?? 0 },
  { key: 'chunks', label: '附件 Chunk', value: stats.value?.attachmentChunkCount ?? 0 },
  { key: 'vectors', label: '总向量数', value: stats.value?.totalVectorCount ?? 0, tone: 'brand' },
])

const guideTab = ref('http')

async function loadDetail() {
  const { data } = await getBizIndexDetail(indexCode)
  indexDetail.value = data as unknown as BizIndex
}

async function loadStats() {
  statsLoading.value = true
  try {
    const { data } = await getBizIndexStats(indexCode)
    stats.value = data as unknown as BizIndexStats
  } finally {
    statsLoading.value = false
  }
}

async function handleRebuild() {
  rebuildLoading.value = true
  try {
    await bizIndexRebuild(indexCode)
    ElMessage.success('索引重建完成')
    await loadStats()
  } catch {
    // 错误已在拦截器中处理
  } finally {
    rebuildLoading.value = false
  }
}

async function handleSearch() {
  if (!searchQuery.value.trim()) return
  searchLoading.value = true
  searchExecuted.value = true
  try {
    const { data } = await bizIndexSearch(indexCode, {
      query: searchQuery.value,
      topK: 10,
      scoreThreshold: 0.3,
    })
    searchResults.value = (data as unknown as { results?: BizSearchItem[] })?.results ?? []
  } finally {
    searchLoading.value = false
  }
}

function copyText(text: string) {
  navigator.clipboard.writeText(text)
  ElMessage.success('已复制')
}

/** 根据字段定义生成示例 fields */
function buildExampleFields(): Record<string, string> {
  if (!indexDetail.value?.fieldSchema) return { field1: '示例值1', field2: '示例值2' }
  try {
    const schema = JSON.parse(indexDetail.value.fieldSchema)
    const fields: Record<string, string> = {}
    for (const f of schema.fields || []) {
      fields[f.name] = `示例${f.label || f.name}`
    }
    return fields
  } catch {
    return { field1: '示例值1', field2: '示例值2' }
  }
}

const curlExample = computed(() => {
  const fields = buildExampleFields()
  const data = {
    bizId: 'YOUR_BIZ_ID',
    bizType: 'YOUR_TYPE',
    fields,
    metadata: { key: 'value' },
    ownerUserId: 'user_001',
    ownerOrgId: 'org_001',
  }

  return `# 业务系统入口只接受结构化 JSON，并要求 REACHAI_PROJECT_REQUEST_V1。
# body SHA-256 和签名必须基于发送的精确字节；请优先使用 Java Starter 生成下列值。
# 项目凭证入口暂不支持附件，附件由已登录平台操作员在本页面上传。

curl -X POST "https://your-reachai-host/api/knowledge-ingress/projects/your-project-code/biz-index/${indexCode}/upsert" \\
  -H "Content-Type: application/json" \\
  -H "X-ReachAI-App-Key: <app-key>" \\
  -H "X-ReachAI-Timestamp: <epoch-millis>" \\
  -H "X-ReachAI-Nonce: <single-use-nonce>" \\
  -H "X-ReachAI-Body-SHA256: <sha256-of-exact-body>" \\
  -H "X-ReachAI-Signature: <hmac-signature>" \\
  --data-binary '${JSON.stringify(data)}'`
})

const javaExample = computed(() => {
  const fields = buildExampleFields()
  const fieldEntries = Object.entries(fields)
    .map(([k, v]) => `fields.put("${k}", "${v}");`)
    .join('\n')

  return `// ReachAiBusinessIndexClient 由 reachai-spring-boot2-starter 自动配置，兼容 JDK 8。
// registry.url、project.code、app-key 和 app-secret 复用项目注册配置。
@Autowired
private ReachAiBusinessIndexClient businessIndexClient;

Map<String, String> fields = new HashMap<>();
${fieldEntries}

Map<String, Object> data = new HashMap<>();
data.put("bizId", "YOUR_BIZ_ID");
data.put("bizType", "YOUR_TYPE");
data.put("fields", fields);
Map<String, Object> metadata = new HashMap<>();
metadata.put("key", "value");
data.put("metadata", metadata);
data.put("ownerUserId", "user_001");
data.put("ownerOrgId", "org_001");

// Starter 会对方法、公共路径、项目、时间、nonce 和精确 JSON body 统一入签。
businessIndexClient.upsert("${indexCode}", data);

// 删除同样使用 body-bound POST，避免 bizId 路径编码歧义。
// businessIndexClient.deleteRecord("${indexCode}", "YOUR_BIZ_ID");`
})

const searchExample = computed(() => {
  return `# 搜索是平台控制台能力，需要实时平台 session 和 platform:read 权限。
# 业务 Agent 不应调用该接口读取索引正文，而应走 Runtime 的权威回源链路。
curl -X POST "https://your-reachai-host/api/knowledge/biz-index/${indexCode}/search" \\
  -H "Authorization: Bearer <short-lived-platform-session>" \\
  -H "Content-Type: application/json" \\
  -d '{
    "query": "你的搜索内容",
    "topK": 10,
    "scoreThreshold": 0.5,
    "filters": {
      "owner_org_id": ["org_001"]
    },
    "includeAttachmentMatch": true
  }'`
})

const guides = computed(() => [
  { key: 'http', label: '签名 HTTP', caption: '项目凭证签名请求形态', code: curlExample.value },
  { key: 'java', label: 'Java', caption: '推送数据示例（Java / ReachAI Starter）', code: javaExample.value },
  { key: 'search', label: '控制台搜索', caption: '平台会话搜索示例（cURL）', code: searchExample.value },
])

onMounted(() => {
  loadDetail()
  loadStats()
})
</script>

<style scoped lang="scss">
.biz-index-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.6fr) minmax(320px, 1fr);
  gap: var(--layout-section-gap);
  align-items: start;
}

.biz-index-aside {
  display: grid;
  gap: var(--layout-section-gap);
  min-width: 0;
}

.section-head {
  display: grid;
  gap: 2px;
  margin-bottom: 12px;

  h2 {
    margin: 0;
    color: var(--text-primary);
    font-size: 15px;
    font-weight: 650;
  }

  p {
    margin: 0;
    color: var(--text-secondary);
    font-size: 12px;
  }
}

.search-form {
  display: flex;
  gap: var(--layout-control-gap);

  &__input {
    flex: 1;
    min-width: 0;
  }
}

.search-results {
  margin-top: 14px;
}

.search-hint {
  margin: 14px 0 0;
  padding: 18px;
  border: 1px dashed var(--border-glass-edge);
  border-radius: var(--radius-lg);
  color: var(--text-secondary);
  font-size: 13px;
  text-align: center;
}

.info-list {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px 16px;
  margin: 0;

  > div {
    display: grid;
    gap: 2px;
    min-width: 0;
  }

  .is-wide {
    grid-column: 1 / -1;
  }

  dt {
    color: var(--text-muted);
    font-size: 12px;
  }

  dd {
    margin: 0;
    color: var(--text-primary);
    font-size: 13px;
    overflow-wrap: anywhere;
  }
}

.template-code {
  display: block;
  padding: 6px 10px;
  border: 1px solid var(--border-glass-edge);
  border-radius: var(--radius-md);
  background: var(--surface-glass-inset);
  color: var(--text-primary);
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 12.5px;
  word-break: break-all;
}

.code-block {
  overflow: hidden;
  border: 1px solid var(--border-glass-edge);
  border-radius: var(--radius-lg);
  background: var(--code-surface);

  .code-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 8px 14px;
    border-bottom: 1px solid var(--border-divider);
    color: var(--text-secondary);
    font-size: 12.5px;
  }

  pre {
    max-height: 360px;
    margin: 0;
    padding: 14px;
    overflow: auto;

    code {
      color: var(--code-text);
      font-family: 'Consolas', 'Monaco', monospace;
      font-size: 12.5px;
      line-height: 1.6;
      white-space: pre-wrap;
      word-break: break-all;
    }
  }
}

@media (max-width: 1100px) {
  .biz-index-layout {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 480px) {
  .info-list {
    grid-template-columns: minmax(0, 1fr);
  }

  .search-form {
    flex-wrap: wrap;

    :deep(.liquid-glass-button) {
      width: 100%;
    }
  }
}
</style>
