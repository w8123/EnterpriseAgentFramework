<template>
  <WorkbenchPage density="comfortable">
    <PageHeader
      variant="standard"
      domain="tool"
      eyebrow="Retrieval Lab"
      title="Tool 检索测试"
      description="按调用定义的名称、标题和说明进行关键词匹配，核对候选结果与匹配分数。"
      density="comfortable"
    />

    <WorkbenchPanel title="检索配置" density="comfortable">
      <el-form :inline="true" class="search-form" novalidate @submit.prevent="handleSearch">
        <el-form-item label="检索关键词">
          <el-input
            v-model="form.query"
            class="search-form__query"
            placeholder="例如：工单数量"
            clearable
            @keyup.enter="handleSearch"
          />
        </el-form-item>
        <el-form-item label="TopK">
          <el-input-number v-model="form.topK" :min="1" :max="50" />
        </el-form-item>
        <el-form-item label="仅启用">
          <el-switch v-model="form.enabledOnly" />
        </el-form-item>
        <el-form-item>
          <template #label>
            <span class="form-label">
              匹配分数下限
              <el-tooltip
                content="清空或设为 0 时不按匹配分数过滤"
                placement="top"
              >
                <el-icon class="form-label__tip" tabindex="0" aria-label="匹配分数下限说明">
                  <QuestionFilled />
                </el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-input-number
            v-model="form.minScore"
            :min="0"
            :max="1"
            :step="0.05"
            :precision="2"
            :value-on-clear="undefined"
            controls-position="right"
            class="search-form__score"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="searching" @click="handleSearch">检索</el-button>
        </el-form-item>
      </el-form>
    </WorkbenchPanel>

    <WorkbenchPanel :title="`匹配结果（${candidates.length}）`" density="comfortable">
      <DataTableShell
        density="compact"
        :loading="searching"
        :empty="candidates.length === 0"
        empty-description="无匹配结果"
      >
        <el-table :data="candidates" stripe empty-text=" ">
          <el-table-column label="#" type="index" width="60" />
          <el-table-column prop="toolTitle" label="工具名称" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">{{ row.toolTitle || row.toolName }}</template>
          </el-table-column>
          <el-table-column prop="toolName" label="工具标识" min-width="220" show-overflow-tooltip />
          <el-table-column label="分数" width="100">
            <template #default="{ row }">
              <StatusTag
                :label="row.score.toFixed(4)"
                :tone="scoreTag(row.score) || 'neutral'"
              />
            </template>
          </el-table-column>
          <el-table-column prop="projectId" label="项目 ID" width="100" />
          <el-table-column prop="moduleId" label="模块 ID" width="100" />
          <el-table-column label="匹配文本" min-width="320" show-overflow-tooltip>
            <template #default="{ row }">
              <span class="text-ellipsis">{{ row.text }}</span>
            </template>
          </el-table-column>
        </el-table>
      </DataTableShell>
    </WorkbenchPanel>
  </WorkbenchPage>
</template>

<script setup lang="ts">
import { onBeforeUnmount, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { QuestionFilled } from '@element-plus/icons-vue'
import { searchToolRetrieval } from '@/api/toolRetrieval'
import type { ToolCandidate, ToolRetrievalSearchRequest } from '@/types/toolRetrieval'
import PageHeader from '@/components/common/PageHeader.vue'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import WorkbenchPanel from '@/components/common/WorkbenchPanel.vue'
import DataTableShell from '@/components/common/DataTableShell.vue'
import StatusTag from '@/components/common/StatusTag.vue'

const form = reactive({
  query: '',
  topK: 10,
  enabledOnly: false,
  /** undefined：不传，不按匹配分数过滤 */
  minScore: undefined as number | undefined,
})

const searching = ref(false)
const candidates = ref<ToolCandidate[]>([])

let disposed = false
onBeforeUnmount(() => { disposed = true })

async function handleSearch() {
  if (disposed || searching.value) return
  if (!form.query.trim()) {
    ElMessage.warning('请输入检索关键词')
    return
  }
  searching.value = true
  try {
    const payload: ToolRetrievalSearchRequest = {
      query: form.query.trim(),
      topK: form.topK,
      enabledOnly: form.enabledOnly,
    }
    if (form.minScore !== undefined && form.minScore !== null) {
      payload.minScore = form.minScore
    }
    const { data } = await searchToolRetrieval(payload)
    if (disposed) return
    candidates.value = data?.candidates || []
    if (!candidates.value.length && data?.message) {
      ElMessage.info(data.message)
    }
  } catch (err) {
    if (!disposed) ElMessage.error((err as Error).message || '检索失败')
  } finally {
    if (!disposed) searching.value = false
  }
}

function scoreTag(score: number): 'success' | 'warning' | 'info' | 'danger' | '' {
  if (score >= 0.7) return 'success'
  if (score >= 0.5) return 'warning'
  return 'info'
}
</script>

<style scoped lang="scss">
.search-form {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: calc(var(--section-gap) / 2) var(--section-gap);
}

.search-form__query {
  width: min(420px, 100%);
}

.search-form__score {
  width: 160px;
}

.form-label {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.form-label__tip {
  color: var(--text-muted);
  cursor: help;
  font-size: 0.875rem;
  outline: none;
}

.text-ellipsis {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

@media (max-width: 720px) {
  .search-form__query,
  .search-form__score {
    width: 100%;
  }
}
</style>
