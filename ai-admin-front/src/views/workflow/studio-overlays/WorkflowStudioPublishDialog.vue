<template>
  <AppDialog
    v-model="open"
    title="发布 Workflow 版本"
    width="640px"
    :close-on-click-modal="!publishing"
    :close-on-press-escape="!publishing"
    :show-close="!publishing"
    :before-close="beforeClose"
  >
    <el-alert
      v-if="releaseChecking"
      type="info"
      :closable="false"
      show-icon
      class="publish-warning"
      title="正在校验当前画布草稿，而不是上一次保存的版本…"
    />
    <div v-else-if="releaseErrors.length || releaseWarnings.length" class="release-check-panel">
      <div class="release-check-head">
        <div>
          <strong>Workflow 发布门禁</strong>
          <span>{{ releaseErrors.length }} 个阻断项 / {{ releaseWarnings.length }} 个提醒项</span>
        </div>
        <el-tag :type="releaseErrors.length ? 'danger' : 'success'" size="small">
          {{ releaseErrors.length ? '未通过' : '已通过' }}
        </el-tag>
      </div>
      <el-collapse model-value="errors" class="release-check-collapse">
        <el-collapse-item v-if="releaseErrors.length" title="阻断项" name="errors">
          <div v-for="item in releaseErrors" :key="releaseValidationKey(item)" class="check-item error">
            <el-tag size="small" type="danger">{{ item.code }}</el-tag>
            <span v-if="item.nodeId" class="check-node">{{ item.nodeId }}</span>
            <span>{{ item.message }}</span>
          </div>
        </el-collapse-item>
        <el-collapse-item v-if="releaseWarnings.length" title="提醒项" name="warnings">
          <div v-for="item in releaseWarnings" :key="releaseValidationKey(item)" class="check-item warn">
            <el-tag size="small" type="warning">{{ item.code }}</el-tag>
            <span v-if="item.nodeId" class="check-node">{{ item.nodeId }}</span>
            <span>{{ item.message }}</span>
          </div>
        </el-collapse-item>
      </el-collapse>
    </div>
    <el-alert
      v-else-if="releaseValidationReady"
      type="success"
      :closable="false"
      show-icon
      class="publish-warning"
      title="当前画布草稿已通过发布门禁"
    />
    <el-alert
      v-if="publishWarnings.length"
      type="warning"
      :closable="false"
      class="publish-warning"
      title="发布前检查"
    >
      <ul>
        <li v-for="item in publishWarnings" :key="item">{{ item }}</li>
      </ul>
    </el-alert>
    <el-form :model="form" label-width="120px" :disabled="publishing" novalidate>
      <el-form-item label="版本号" required>
        <el-input v-model="form.version" placeholder="v1.0.0" />
      </el-form-item>
      <el-form-item label="生效方式">
        <span>全量发布</span>
      </el-form-item>
      <el-form-item label="发布说明">
        <el-input v-model="form.note" type="textarea" :rows="3" resize="none" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button :disabled="publishing" @click="open = false">取消</el-button>
      <el-button
        type="primary"
        :loading="publishing"
        :disabled="releaseChecking || !releaseValidationReady || !!releaseErrors.length"
        @click="$emit('publish')"
      >
        确认发布
      </el-button>
    </template>
  </AppDialog>
</template>

<script setup lang="ts">
import AppDialog from '@/components/common/AppDialog.vue'
import type {
  WorkflowPublishRequest,
  WorkflowReleaseValidationItem,
} from '@/types/workflow'

defineProps<{
  publishing: boolean
  releaseChecking: boolean
  releaseValidationReady: boolean
  releaseErrors: WorkflowReleaseValidationItem[]
  releaseWarnings: WorkflowReleaseValidationItem[]
  publishWarnings: string[]
  form: WorkflowPublishRequest
  beforeClose: (done: () => void) => void
  releaseValidationKey: (item: WorkflowReleaseValidationItem) => string
}>()

defineEmits<{
  (event: 'publish'): void
}>()

const open = defineModel<boolean>('open', { required: true })
</script>

<style scoped lang="scss">
.publish-warning {
  margin-bottom: 14px;
}

.publish-warning ul {
  margin: 0;
  padding-left: 18px;
}

.release-check-panel {
  display: grid;
  gap: 10px;
  margin-bottom: 14px;
  padding: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-fill-color-lighter);
}

.release-check-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.release-check-head strong,
.release-check-head span {
  display: block;
}

.release-check-head span {
  margin-top: 4px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.release-check-collapse {
  --el-collapse-header-bg-color: transparent;
  --el-collapse-content-bg-color: transparent;
}

.check-item {
  display: grid;
  grid-template-columns: auto auto 1fr;
  align-items: center;
  gap: 8px;
  padding: 6px 0;
  font-size: 12px;
}

.check-node {
  padding: 2px 6px;
  border-radius: 4px;
  background: var(--el-fill-color);
  color: var(--el-text-color-secondary);
  font-family: Consolas, Monaco, 'Courier New', monospace;
}
</style>
