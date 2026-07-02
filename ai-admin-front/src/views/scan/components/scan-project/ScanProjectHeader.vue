<script setup lang="ts">
import { Refresh } from '@element-plus/icons-vue'
import type { ScanProject } from '@/types/scanProject'

defineProps<{
  project: ScanProject | null
  assetSummaryItems: string[]
  batchStarting: boolean
  reconcileLoading: boolean
  loading: boolean
}>()

const emit = defineEmits<{
  goBack: []
  startBatchGenerate: [force: boolean]
  openModelGeneratePanel: []
  openScanRulesPanel: []
  openOpsPanel: []
  reconcile: []
  refresh: []
}>()
</script>

<template>
  <section class="asset-directory-bar">
    <div class="asset-identity">
      <el-button link class="back-link" @click="emit('goBack')">返回</el-button>
      <strong>{{ project?.name || 'API 接口目录' }}</strong>
      <span v-for="item in assetSummaryItems" :key="item">{{ item }}</span>
    </div>
    <div class="asset-actions">
      <el-button type="primary" :loading="batchStarting" @click="emit('startBatchGenerate', false)">
        一键生成 AI 理解
      </el-button>
      <el-button :loading="batchStarting" @click="emit('startBatchGenerate', true)">
        强制重生成
      </el-button>
      <el-button plain @click="emit('openModelGeneratePanel')">模型设置</el-button>
      <el-button plain @click="emit('openScanRulesPanel')">扫描解析规则</el-button>
      <el-button plain @click="emit('openOpsPanel')">运维动作</el-button>
      <el-button type="primary" :loading="reconcileLoading" @click="emit('reconcile')">
        对账同步 API 与 Tool
      </el-button>
      <el-button :loading="loading" @click="emit('refresh')">
        <el-icon><Refresh /></el-icon>刷新
      </el-button>
    </div>
  </section>
</template>
