<template>
  <WorkbenchPage class="slot-extract-logs-page" layout="list">
    <PageHeader
      variant="standard"
      domain="governance"
      eyebrow="Extraction Observability"
      title="SlotExtractor 调用日志"
      description="按提取器、能力、命中状态和时间范围追踪槽位提取质量。"
    >
      <template #actions>
        <el-tooltip content="刷新调用日志" placement="top">
          <el-button circle :icon="Refresh" :loading="loading" aria-label="刷新调用日志" @click="reload" />
        </el-tooltip>
      </template>
    </PageHeader>

    <FilterBar :loading="loading" :show-reset="false" query-label="查询" @query="reload">
        <el-select v-model="filterExtractor" placeholder="提取器" clearable style="width: 150px">
          <el-option v-for="e in extractors" :key="e" :label="e" :value="e" />
        </el-select>
        <el-input v-model="filterSkill" placeholder="能力名" clearable style="width: 180px" />
        <el-select v-model="filterHit" placeholder="命中状态" clearable style="width: 120px">
          <el-option label="命中" :value="true" />
          <el-option label="未命中" :value="false" />
        </el-select>
        <el-input-number v-model="days" :min="1" :max="90" :step="1" controls-position="right" />
    </FilterBar>

    <el-card class="workbench-list-surface" shadow="never">
      <el-table :data="rows" v-loading="loading" stripe size="default">
        <el-table-column prop="createTime" label="时间" width="170" />
        <el-table-column prop="extractorName" label="提取器" width="110">
          <template #default="{ row }"><code>{{ row.extractorName }}</code></template>
        </el-table-column>
        <el-table-column prop="skillName" label="能力" width="160" show-overflow-tooltip />
        <el-table-column prop="fieldKey" label="字段" width="120" />
        <el-table-column label="命中" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="row.hit ? 'success' : 'info'">{{ row.hit ? '是' : '否' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="value" label="值" min-width="120" show-overflow-tooltip />
        <el-table-column label="置信度" width="90" align="center">
          <template #default="{ row }">
            <span v-if="row.confidence !== undefined && row.confidence !== null">{{ row.confidence.toFixed(2) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="evidence" label="证据" min-width="220" show-overflow-tooltip />
        <el-table-column prop="userText" label="用户原文" min-width="240" show-overflow-tooltip />
        <el-table-column prop="latencyMs" label="耗时" width="80" align="center">
          <template #default="{ row }">{{ row.latencyMs }} ms</template>
        </el-table-column>
        <el-table-column prop="traceId" label="trace" width="100">
          <template #default="{ row }">
            <el-button v-if="row.traceId" size="small" link @click="copyTrace(row.traceId)">复制</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-model:current-page="pagination.current"
        v-model:page-size="pagination.size"
        :total="pagination.total"
        :page-sizes="[50, 100, 200]"
        layout="total, sizes, prev, pager, next"
        @current-change="reload"
        @size-change="reload"
        style="margin-top: 12px; justify-content: flex-end"
      />
    </el-card>
  </WorkbenchPage>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import PageHeader from '@/components/common/PageHeader.vue'
import FilterBar from '@/components/common/FilterBar.vue'

import { listSlotExtractors, pageSlotExtractLogs } from '@/api/slotExtractor'
import type { SlotExtractLogRow } from '@/types/slotExtractor'

const loading = ref(false)
const rows = ref<SlotExtractLogRow[]>([])
const extractors = ref<string[]>([])

const filterExtractor = ref<string>('')
const filterSkill = ref<string>('')
const filterHit = ref<boolean | undefined>(undefined)
const days = ref<number>(7)

const pagination = reactive({ current: 1, size: 50, total: 0 })

async function reload() {
  loading.value = true
  try {
    const { data } = await pageSlotExtractLogs({
      current: pagination.current,
      size: pagination.size,
      extractorName: filterExtractor.value || undefined,
      skillName: filterSkill.value || undefined,
      hit: filterHit.value,
      days: days.value,
    })
    rows.value = data?.records ?? []
    pagination.total = data?.total ?? 0
  } catch {
    rows.value = []
  } finally {
    loading.value = false
  }
}

async function loadExtractors() {
  try {
    const { data } = await listSlotExtractors()
    extractors.value = (data ?? []).map((x) => x.name)
  } catch {
    extractors.value = []
  }
}

function copyTrace(traceId: string) {
  navigator.clipboard.writeText(traceId)
  ElMessage.success('已复制 traceId')
}

onMounted(() => {
  loadExtractors()
  reload()
})
</script>
