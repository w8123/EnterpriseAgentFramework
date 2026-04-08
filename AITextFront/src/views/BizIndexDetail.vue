<template>
  <div class="page-container">
    <div class="page-header">
      <h2>{{ indexDetail?.indexName || '索引详情' }}</h2>
      <div>
        <el-button @click="router.push('/biz-index')">
          <el-icon><ArrowLeft /></el-icon>
          返回列表
        </el-button>
        <el-button type="warning" :loading="rebuildLoading" @click="handleRebuild">
          <el-icon><Refresh /></el-icon>
          重建索引
        </el-button>
      </div>
    </div>

    <el-row :gutter="16">
      <!-- 基本信息 -->
      <el-col :span="16">
        <el-card shadow="never" class="section-card">
          <template #header>基本信息</template>
          <el-descriptions :column="2" border>
            <el-descriptions-item label="索引编码">
              <el-tag effect="plain">{{ indexDetail?.indexCode }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="来源系统">{{ indexDetail?.sourceSystem }}</el-descriptions-item>
            <el-descriptions-item label="Embedding 模型">{{ indexDetail?.embeddingModel }}</el-descriptions-item>
            <el-descriptions-item label="向量维度">{{ indexDetail?.dimension }}</el-descriptions-item>
            <el-descriptions-item label="切分策略">{{ indexDetail?.splitType }}</el-descriptions-item>
            <el-descriptions-item label="切分大小 / 重叠">
              {{ indexDetail?.chunkSize }} / {{ indexDetail?.chunkOverlap }}
            </el-descriptions-item>
            <el-descriptions-item label="状态">
              <el-tag :type="indexDetail?.status === 'ACTIVE' ? 'success' : 'danger'" size="small">
                {{ indexDetail?.status === 'ACTIVE' ? '启用' : '停用' }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="创建时间">{{ indexDetail?.createTime }}</el-descriptions-item>
            <el-descriptions-item label="文本模板" :span="2">
              <code class="template-code">{{ indexDetail?.textTemplate }}</code>
            </el-descriptions-item>
            <el-descriptions-item label="备注" :span="2">{{ indexDetail?.remark || '-' }}</el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>

      <!-- 统计卡片 -->
      <el-col :span="8">
        <el-card shadow="never" class="section-card">
          <template #header>索引统计</template>
          <div class="stats-grid" v-loading="statsLoading">
            <div class="stat-item">
              <div class="stat-value">{{ stats?.recordCount ?? 0 }}</div>
              <div class="stat-label">索引记录</div>
            </div>
            <div class="stat-item">
              <div class="stat-value">{{ stats?.attachmentRecordCount ?? 0 }}</div>
              <div class="stat-label">含附件记录</div>
            </div>
            <div class="stat-item">
              <div class="stat-value">{{ stats?.attachmentChunkCount ?? 0 }}</div>
              <div class="stat-label">附件 Chunk</div>
            </div>
            <div class="stat-item">
              <div class="stat-value highlight">{{ stats?.totalVectorCount ?? 0 }}</div>
              <div class="stat-label">总向量数</div>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 接入指南 -->
    <el-card shadow="never" class="section-card">
      <template #header>
        <div style="display: flex; align-items: center; gap: 8px;">
          <el-icon><Document /></el-icon>
          接入指南
        </div>
      </template>

      <el-tabs>
        <el-tab-pane label="cURL">
          <div class="code-block">
            <div class="code-header">
              <span>推送数据示例（cURL）</span>
              <el-button size="small" link @click="copyText(curlExample)">复制</el-button>
            </div>
            <pre><code>{{ curlExample }}</code></pre>
          </div>
        </el-tab-pane>
        <el-tab-pane label="Java">
          <div class="code-block">
            <div class="code-header">
              <span>推送数据示例（Java / RestTemplate）</span>
              <el-button size="small" link @click="copyText(javaExample)">复制</el-button>
            </div>
            <pre><code>{{ javaExample }}</code></pre>
          </div>
        </el-tab-pane>
        <el-tab-pane label="搜索">
          <div class="code-block">
            <div class="code-header">
              <span>语义搜索示例（cURL）</span>
              <el-button size="small" link @click="copyText(searchExample)">复制</el-button>
            </div>
            <pre><code>{{ searchExample }}</code></pre>
          </div>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 搜索测试 -->
    <el-card shadow="never" class="section-card">
      <template #header>
        <div style="display: flex; align-items: center; gap: 8px;">
          <el-icon><Search /></el-icon>
          搜索测试
        </div>
      </template>
      <el-form :inline="true" @submit.prevent="handleSearch">
        <el-form-item label="搜索内容" style="flex: 1">
          <el-input
            v-model="searchQuery"
            placeholder="输入语义搜索内容"
            clearable
            @keyup.enter="handleSearch"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="searchLoading" @click="handleSearch">搜索</el-button>
        </el-form-item>
      </el-form>

      <el-table v-if="searchResults.length > 0" :data="searchResults" stripe style="width: 100%; margin-top: 16px;">
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

      <el-empty v-else-if="searchExecuted" description="未找到匹配结果" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, Refresh, Document, Search } from '@element-plus/icons-vue'
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

async function loadDetail() {
  const { data } = await getBizIndexDetail(indexCode)
  indexDetail.value = data.data
}

async function loadStats() {
  statsLoading.value = true
  try {
    const { data } = await getBizIndexStats(indexCode)
    stats.value = data.data
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
    searchResults.value = data.data?.results || []
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

  return `# 推送业务数据（带附件）
curl -X POST "http://your-host/ai/biz-index/${indexCode}/upsert" \\
  -F 'data=${JSON.stringify(data, null, 2)};type=application/json' \\
  -F 'attachments=@/path/to/attachment.pdf'

# 推送业务数据（不带附件）
curl -X POST "http://your-host/ai/biz-index/${indexCode}/upsert" \\
  -F 'data=${JSON.stringify(data, null, 2)};type=application/json'`
})

const javaExample = computed(() => {
  const fields = buildExampleFields()
  const fieldEntries = Object.entries(fields)
    .map(([k, v]) => `        fields.put("${k}", "${v}");`)
    .join('\n')

  return `// 1. 构建请求数据
Map<String, String> fields = new HashMap<>();
${fieldEntries}

Map<String, Object> data = new HashMap<>();
data.put("bizId", "YOUR_BIZ_ID");
data.put("bizType", "YOUR_TYPE");
data.put("fields", fields);
data.put("metadata", Map.of("key", "value"));
data.put("ownerUserId", "user_001");
data.put("ownerOrgId", "org_001");

// 2. 构建 Multipart 请求
MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
body.add("data", new HttpEntity<>(
    objectMapper.writeValueAsString(data),
    new HttpHeaders() {{ setContentType(MediaType.APPLICATION_JSON); }}
));
// 可选：添加附件
// body.add("attachments", new FileSystemResource(new File("/path/to/file.pdf")));

HttpHeaders headers = new HttpHeaders();
headers.setContentType(MediaType.MULTIPART_FORM_DATA);

restTemplate.postForObject(
    "http://your-host/ai/biz-index/${indexCode}/upsert",
    new HttpEntity<>(body, headers),
    ApiResult.class
);`
})

const searchExample = computed(() => {
  return `# 语义搜索
curl -X POST "http://your-host/ai/biz-index/${indexCode}/search" \\
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

onMounted(() => {
  loadDetail()
  loadStats()
})
</script>

<style scoped lang="scss">
.stats-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20px;
}

.stat-item {
  text-align: center;
  padding: 16px 0;
}

.stat-value {
  font-size: 28px;
  font-weight: 700;
  color: #303133;

  &.highlight {
    color: #409eff;
  }
}

.stat-label {
  font-size: 13px;
  color: #909399;
  margin-top: 4px;
}

.template-code {
  background: #f5f7fa;
  padding: 4px 8px;
  border-radius: 4px;
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 13px;
  color: #e6a23c;
  word-break: break-all;
}

.code-block {
  background: #1d2129;
  border-radius: 8px;
  overflow: hidden;

  .code-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 10px 16px;
    background: #2a2e36;
    color: #ffffffa6;
    font-size: 13px;
  }

  pre {
    padding: 16px;
    margin: 0;
    overflow-x: auto;

    code {
      color: #e6edf3;
      font-family: 'Consolas', 'Monaco', monospace;
      font-size: 13px;
      line-height: 1.6;
      white-space: pre-wrap;
      word-break: break-all;
    }
  }
}
</style>
