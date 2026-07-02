<script setup lang="ts">
import type { ScanProject } from '@/types/scanProject'

defineProps<{
  project: ScanProject | null
  rescanLoading: boolean
  rebuildEmbeddingLoading: boolean
  scanSettingsSaving: boolean
}>()

const visible = defineModel<boolean>('visible', { required: true })

const emit = defineEmits<{
  rescan: []
  rebuildEmbeddings: []
  saveScanSettings: []
  saveAiGenerationSettings: []
}>()
</script>

<template>
  <el-drawer
    v-model="visible"
    size="520px"
    title="运维动作"
    destroy-on-close
    append-to-body
  >
    <div class="ops-action-list">
      <div class="ops-action-item">
        <div>
          <strong>重新扫描</strong>
          <span>使用最近一次保存的扫描解析规则刷新接口目录。</span>
        </div>
        <el-button
          type="warning"
          plain
          :disabled="project?.projectKind === 'REGISTERED'"
          :loading="rescanLoading"
          @click="emit('rescan')"
        >
          重新扫描
        </el-button>
      </div>
      <div class="ops-action-item">
        <div>
          <strong>重建向量索引</strong>
          <span>重建当前项目接口语义检索索引。</span>
        </div>
        <el-button type="info" plain :loading="rebuildEmbeddingLoading" @click="emit('rebuildEmbeddings')">
          重建向量索引
        </el-button>
      </div>
      <div class="ops-action-item">
        <div>
          <strong>保存扫描设置</strong>
          <span>保存扫描解析规则，SDK 项目在下次同步能力时生效。</span>
        </div>
        <el-button :loading="scanSettingsSaving" @click="emit('saveScanSettings')">保存扫描设置</el-button>
      </div>
      <div class="ops-action-item">
        <div>
          <strong>保存 AI 设置</strong>
          <span>保存 LLM 模型实例与生成策略。</span>
        </div>
        <el-button type="primary" @click="emit('saveAiGenerationSettings')">保存 AI 设置</el-button>
      </div>
    </div>
    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
    </template>
  </el-drawer>
</template>
