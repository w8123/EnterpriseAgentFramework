<template>
  <div class="page-container">
    <div class="page-header">
      <h2>分类器测试台</h2>
      <div class="header-actions">
        <el-button :icon="Refresh" @click="reload" :loading="loading">刷新覆盖度</el-button>
      </div>
    </div>

    <el-row :gutter="12">
      <el-col :span="12">
        <el-card shadow="never">
          <template #header>测试输入</template>
          <el-form label-width="100px">
            <el-form-item label="用户问题" required>
              <el-input
                v-model="text"
                type="textarea"
                :rows="3"
                placeholder="例：查询本月工资条 / 报销 100 元 / 客户跟进记录"
              />
            </el-form-item>
            <el-form-item label="topK">
              <el-input-number v-model="topK" :min="1" :max="10" :step="1" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="running" @click="runTest">分类</el-button>
            </el-form-item>
          </el-form>

          <el-divider />

          <el-empty v-if="!result" description="尚未运行分类" />
          <el-table v-else :data="result.results" size="small" stripe>
            <el-table-column prop="domainCode" label="domain" width="120">
              <template #default="{ row }"><code>{{ row.domainCode }}</code></template>
            </el-table-column>
            <el-table-column prop="name" label="名称" width="140" />
            <el-table-column prop="score" label="得分" width="100" align="center">
              <template #default="{ row }">{{ row.score?.toFixed(2) }}</template>
            </el-table-column>
            <el-table-column prop="toolCount" label="该域目标数" width="120" />
          </el-table>
        </el-card>
      </el-col>

      <el-col :span="12">
        <el-card shadow="never">
          <template #header>领域覆盖度</template>
          <el-table :data="coverage" v-loading="loading" size="small" stripe>
            <el-table-column prop="domainCode" label="domain" width="100">
              <template #default="{ row }"><code>{{ row.domainCode }}</code></template>
            </el-table-column>
            <el-table-column prop="name" label="名称" width="140" />
            <el-table-column prop="toolCount" label="TOOL" align="center" width="80" />
            <el-table-column prop="skillCount" label="SKILL" align="center" width="80" />
            <el-table-column prop="agentCount" label="AGENT" align="center" width="80" />
            <el-table-column prop="projectCount" label="PROJECT" align="center" width="90" />
          </el-table>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'

import { classifyDomain, getDomainCoverage } from '@/api/domain'
import type { DomainClassifyResponse, DomainCoverageRow } from '@/types/domain'

const loading = ref(false)
const running = ref(false)

const text = ref('查询本月工资条')
const topK = ref(5)
const result = ref<DomainClassifyResponse | null>(null)
const coverage = ref<DomainCoverageRow[]>([])

async function reload() {
  loading.value = true
  try {
    const { data } = await getDomainCoverage()
    coverage.value = data ?? []
  } finally {
    loading.value = false
  }
}

async function runTest() {
  if (!text.value.trim()) {
    ElMessage.warning('请输入用户问题')
    return
  }
  running.value = true
  try {
    const { data } = await classifyDomain(text.value, topK.value)
    result.value = data
  } finally {
    running.value = false
  }
}

onMounted(reload)
</script>

<style scoped lang="scss">
.page-container { padding: 16px; }
.page-header {
  display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px;
  h2 { margin: 0; font-size: 18px; }
}
.header-actions { display: flex; gap: 8px; }
</style>
