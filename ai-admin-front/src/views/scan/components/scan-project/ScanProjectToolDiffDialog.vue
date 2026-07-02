<script setup lang="ts">
import type { ProjectToolInfo } from '@/types/scanProject'

defineProps<{
  diffDialogRow: ProjectToolInfo | null
}>()

const visible = defineModel<boolean>('visible', { required: true })
</script>

<template>
  <el-dialog v-model="visible" title="API 与全局 Tool 字段差异" width="560px" destroy-on-close>
    <template v-if="diffDialogRow">
      <p v-if="diffDialogRow.toolLinkMessage" class="diff-dialog-msg">{{ diffDialogRow.toolLinkMessage }}</p>
      <p class="diff-dialog-sub">以下字段在「项目 API 目录行」与「全局 Tool」之间不一致：</p>
      <div v-if="(diffDialogRow.toolSyncDiffFields?.length || 0) > 0" class="diff-field-tags">
        <el-tag v-for="f in diffDialogRow.toolSyncDiffFields" :key="f" class="diff-field-tag" type="warning">{{ f }}</el-tag>
      </div>
      <el-empty v-else description="无结构化差异字段列表" :image-size="72" />
    </template>
    <template #footer>
      <el-button type="primary" @click="visible = false">知道了</el-button>
    </template>
  </el-dialog>
</template>
