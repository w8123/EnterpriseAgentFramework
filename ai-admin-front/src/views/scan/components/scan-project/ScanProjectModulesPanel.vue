<script setup lang="ts">
import type { ScanModule, SemanticDoc } from '@/types/semanticDoc'

defineProps<{
  modules: ScanModule[]
  selectedModuleIds: number[]
  moduleDocMap: Record<number, SemanticDoc>
}>()

const emit = defineEmits<{
  moduleSelectionChange: [selection: ScanModule[]]
  openMergeDialog: []
  regenerateModule: [module: ScanModule]
  openEditDoc: [doc: SemanticDoc | undefined]
  openRenameDialog: [module: ScanModule]
}>()
</script>

<template>
  <el-collapse-item class="scan-detail-top-item semantic-inline-collapse" name="modules">
    <template #title>
      <div class="ai-card-header">
        <span>模块列表（{{ modules.length }}）</span>
        <div @click.stop>
          <el-button size="small" :disabled="selectedModuleIds.length < 2" @click="emit('openMergeDialog')">
            合并选中（{{ selectedModuleIds.length }}）
          </el-button>
        </div>
      </div>
    </template>
    <el-table :data="modules" stripe @selection-change="emit('moduleSelectionChange', $event)">
      <el-table-column type="selection" width="48" />
      <el-table-column prop="displayName" label="展示名" min-width="200" />
      <el-table-column prop="name" label="原始类名" min-width="220" />
      <el-table-column label="聚合类" min-width="260">
        <template #default="{ row }">
          <el-tag v-for="c in row.sourceClasses" :key="c" size="small" class="source-class-tag">{{ c }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="AI 文档" width="160">
        <template #default="{ row }">
          <el-tag v-if="moduleDocMap[row.id]" :type="moduleDocMap[row.id].status === 'edited' ? 'success' : 'info'" size="small">
            {{ moduleDocMap[row.id].status }}
          </el-tag>
          <el-tag v-else size="small" type="warning">未生成</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="260">
        <template #default="{ row }">
          <el-button link size="small" type="primary" @click="emit('regenerateModule', row)">重新生成</el-button>
          <el-button link size="small" :disabled="!moduleDocMap[row.id]" @click="emit('openEditDoc', moduleDocMap[row.id])">编辑</el-button>
          <el-button link size="small" @click="emit('openRenameDialog', row)">重命名</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-collapse-item>
</template>
