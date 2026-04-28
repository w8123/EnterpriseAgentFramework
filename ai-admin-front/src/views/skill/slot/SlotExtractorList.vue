<template>
  <div class="page-container">
    <div class="page-header">
      <h2>槽位提取器（SlotExtractor SPI）</h2>
      <div class="header-actions">
        <el-button :icon="Refresh" @click="reload" :loading="loading">刷新</el-button>
      </div>
    </div>

    <el-alert
      type="info"
      show-icon
      :closable="false"
      title="按 priority 顺序尝试，命中即停；置信度低于 0.5 视作未命中"
      description="提取器对 LLM 透明，InteractiveFormSkill 在 LLM 兜底前会先依次问每个适用提取器；命中则跳过 LLM 抽取，节省 token、缩短延迟。"
      style="margin-bottom: 12px"
    />

    <el-row :gutter="16">
      <el-col :span="10">
        <el-card shadow="never">
          <template #header>已注册提取器</template>
          <el-table :data="extractors" v-loading="loading" size="default" stripe>
            <el-table-column prop="priority" label="P" width="60" align="center" />
            <el-table-column prop="name" label="name" width="110">
              <template #default="{ row }"><code>{{ row.name }}</code></template>
            </el-table-column>
            <el-table-column prop="displayName" label="名称" min-width="120" />
            <el-table-column label="7 日命中率" width="120">
              <template #default="{ row }">
                <el-tooltip
                  v-if="metricMap[row.name]"
                  :content="`命中 ${metricMap[row.name].hit}/${metricMap[row.name].total} · 置信度 ${(metricMap[row.name].avgConfidence ?? 0).toFixed(2)} · P95 ${metricMap[row.name].p95LatencyMs}ms`"
                >
                  <el-progress
                    :percentage="Math.round((metricMap[row.name].hitRate ?? 0) * 100)"
                    :stroke-width="8"
                    style="width: 100%"
                  />
                </el-tooltip>
                <span v-else class="dim">无数据</span>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>

      <el-col :span="14">
        <el-card shadow="never">
          <template #header>测试台</template>
          <el-form :model="testForm" label-width="100px">
            <el-form-item label="用户原文" required>
              <el-input
                v-model="testForm.userText"
                type="textarea"
                :rows="3"
                placeholder="例：上周三给研发部张三 100 元报销，13800138000"
              />
            </el-form-item>
            <el-row :gutter="12">
              <el-col :span="8">
                <el-form-item label="字段 key">
                  <el-input v-model="testForm.fieldKey" placeholder="如 startDate / dept / user" />
                </el-form-item>
              </el-col>
              <el-col :span="8">
                <el-form-item label="字段 label">
                  <el-input v-model="testForm.fieldLabel" placeholder="如 起始时间 / 部门 / 申请人" />
                </el-form-item>
              </el-col>
              <el-col :span="8">
                <el-form-item label="字段 type">
                  <el-select v-model="testForm.fieldType" placeholder="text / number / date">
                    <el-option label="text" value="text" />
                    <el-option label="number" value="number" />
                    <el-option label="date" value="date" />
                    <el-option label="select" value="select" />
                  </el-select>
                </el-form-item>
              </el-col>
            </el-row>
            <el-row :gutter="12">
              <el-col :span="12">
                <el-form-item label="userId">
                  <el-input v-model="testForm.userId" placeholder="可选，作为 ExtractContext.userId" />
                </el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item label="userDeptId">
                  <el-input v-model="testForm.userDeptId" placeholder="可选，用于人员同名消歧" />
                </el-form-item>
              </el-col>
            </el-row>
            <el-form-item>
              <el-button type="primary" :loading="running" @click="runTest">运行</el-button>
            </el-form-item>
          </el-form>

          <el-divider />

          <el-table v-if="testResult" :data="testResult.results" size="small" stripe>
            <el-table-column prop="extractorName" label="提取器" width="120">
              <template #default="{ row }"><code>{{ row.extractorName }}</code></template>
            </el-table-column>
            <el-table-column label="适用" width="80" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="row.accepts ? 'success' : 'info'">{{ row.accepts ? '是' : '否' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="命中" width="80" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="row.hit ? 'success' : 'info'">{{ row.hit ? '是' : '否' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="值" min-width="120">
              <template #default="{ row }">
                <code v-if="row.value !== undefined && row.value !== null">{{ row.value }}</code>
              </template>
            </el-table-column>
            <el-table-column label="置信度" width="90" align="center">
              <template #default="{ row }">
                <span v-if="row.confidence !== undefined">{{ row.confidence.toFixed(2) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="证据" min-width="220" show-overflow-tooltip>
              <template #default="{ row }">{{ row.evidence }}</template>
            </el-table-column>
            <el-table-column label="耗时" width="80" align="center">
              <template #default="{ row }">
                <span v-if="row.latencyMs !== undefined">{{ row.latencyMs }} ms</span>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'

import {
  getSlotExtractorMetrics,
  listSlotExtractors,
  testSlotExtractor,
} from '@/api/slotExtractor'
import type {
  SlotExtractorInfo,
  SlotExtractorMetric,
  SlotExtractorTestResponse,
} from '@/types/slotExtractor'

const loading = ref(false)
const running = ref(false)
const extractors = ref<SlotExtractorInfo[]>([])
const metricMap = ref<Record<string, SlotExtractorMetric>>({})

const testForm = reactive({
  userText: '上周三给研发部张三 100 元报销，13800138000',
  fieldKey: 'startDate',
  fieldLabel: '起始时间',
  fieldType: 'date',
  llmExtractHint: '',
  userId: '',
  userDeptId: '',
})

const testResult = ref<SlotExtractorTestResponse | null>(null)

async function reload() {
  loading.value = true
  try {
    const [list, metrics] = await Promise.all([
      listSlotExtractors(),
      getSlotExtractorMetrics(7),
    ])
    extractors.value = list.data ?? []
    const arr = metrics.data ?? []
    const m: Record<string, SlotExtractorMetric> = {}
    for (const r of arr) m[r.extractorName] = r
    metricMap.value = m
  } finally {
    loading.value = false
  }
}

async function runTest() {
  if (!testForm.userText.trim()) {
    ElMessage.warning('请输入用户原文')
    return
  }
  running.value = true
  try {
    const resp = await testSlotExtractor({
      userText: testForm.userText,
      fieldKey: testForm.fieldKey || undefined,
      fieldLabel: testForm.fieldLabel || undefined,
      fieldType: testForm.fieldType || undefined,
      llmExtractHint: testForm.llmExtractHint || undefined,
      userId: testForm.userId || undefined,
      userDeptId: testForm.userDeptId || undefined,
    })
    testResult.value = resp.data
  } finally {
    running.value = false
  }
}

onMounted(reload)
</script>

<style scoped lang="scss">
.page-container {
  padding: 16px;
}
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
  h2 { margin: 0; font-size: 18px; }
}
.dim { color: #999; font-size: 12px; }
</style>
