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
    <LiquidGlassNotice
      v-if="releaseChecking"
      type="info"
      class="publish-warning"
      title="正在校验当前画布草稿，而不是上一次保存的版本…"
      source="backdrop"
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
            <el-tag class="check-code" size="small" type="danger">{{ item.code }}</el-tag>
            <span v-if="item.nodeId" class="check-node">{{ item.nodeId }}</span>
            <span class="check-message">{{ item.message }}</span>
          </div>
        </el-collapse-item>
        <el-collapse-item v-if="releaseWarnings.length" title="提醒项" name="warnings">
          <div v-for="item in releaseWarnings" :key="releaseValidationKey(item)" class="check-item warn">
            <el-tag class="check-code" size="small" type="warning">{{ item.code }}</el-tag>
            <span v-if="item.nodeId" class="check-node">{{ item.nodeId }}</span>
            <span class="check-message">{{ item.message }}</span>
          </div>
        </el-collapse-item>
      </el-collapse>
    </div>
    <LiquidGlassNotice
      v-else-if="releaseValidationReady"
      type="success"
      class="publish-warning"
      title="当前画布草稿已通过发布门禁"
      source="backdrop"
    />
    <LiquidGlassNotice
      v-if="publishWarnings.length"
      type="warning"
      class="publish-warning"
      title="发布前检查"
      source="backdrop"
    >
      <span v-for="item in publishWarnings" :key="item" class="publish-warning-item">{{ item }}</span>
    </LiquidGlassNotice>
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
      <el-form-item v-if="exposure" label="员工 AI 工具">
        <div class="exposure-box">
          <el-checkbox v-model="exposure.enabled">发布后开放给员工 AI 工具</el-checkbox>
          <p class="exposure-hint">
            加入所选 MCP 服务并发布新版本，有权限的员工会在自己的 Codex、Claude Code 里看到这个工作流，按自己的身份调用。
          </p>
          <template v-if="exposure.enabled">
            <el-select
              v-model="exposure.publicationId"
              :loading="exposureLoading"
              placeholder="选择 MCP 服务"
              style="width: 100%"
            >
              <el-option v-for="item in exposurePublications" :key="item.id" :label="item.name" :value="item.id" />
            </el-select>
            <p v-if="!exposureLoading && !exposurePublications?.length" class="exposure-hint">
              还没有 MCP 服务，先到「MCP 服务」里建一个。
            </p>
            <el-select v-model="exposure.riskLevel" style="width: 100%">
              <el-option label="只读：查询类，员工直接调用" value="READ" />
              <el-option label="写入：会改业务数据，员工调用要先审批" value="WRITE" />
              <el-option label="页面操作" value="PAGE_ACTION" />
              <el-option label="不可逆：需要到 MCP 服务里单独确认" value="IRREVERSIBLE" />
            </el-select>
            <p v-if="exposure.riskLevel !== exposureSuggestedRisk" class="exposure-hint is-warn">
              按节点推荐的风险等级是 {{ exposureSuggestedRisk }}，调低前请确认这个工作流不会改业务数据。
            </p>
            <el-input
              v-model="exposure.description"
              type="textarea"
              :rows="3"
              resize="none"
              placeholder="员工的 AI 看到的工具说明"
            />
          </template>
        </div>
      </el-form-item>
    </el-form>
    <template #footer>
      <LiquidGlassButton :disabled="publishing" @click="open = false">取消</LiquidGlassButton>
      <LiquidGlassButton
        type="primary"
        :loading="publishing"
        :disabled="releaseChecking || !releaseValidationReady || !!releaseErrors.length"
        @click="$emit('publish')"
      >
        确认发布
      </LiquidGlassButton>
    </template>
  </AppDialog>
</template>

<script setup lang="ts">
import AppDialog from '@/components/common/AppDialog.vue'
import type {
  WorkflowPublishRequest,
  WorkflowReleaseValidationItem,
} from '@/types/workflow'
import { LiquidGlassNotice, LiquidGlassButton } from '@/components/common/liquid-glass'
import type { McpPublication } from '@/types/mcp'
import type { EmployeeAiExposureForm, ExposureRiskLevel } from '@/views/workflow/composables/useWorkflowEmployeeAiExposure'

defineProps<{
  exposure?: EmployeeAiExposureForm
  exposurePublications?: McpPublication[]
  exposureLoading?: boolean
  exposureSuggestedRisk?: ExposureRiskLevel
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

.publish-warning-item {
  display: list-item;
  margin-left: 18px;
  list-style: disc;
}

.release-check-panel {
  display: grid;
  min-width: 0;
  gap: 10px;
  margin-bottom: 14px;
  padding: 12px;
  border: 1px solid var(--border-glass-edge);
  border-radius: var(--radius-md);
  background: var(--surface-glass-inset);
  box-shadow: var(--glass-rim-double);
}

.release-check-head {
  display: flex;
  flex-wrap: wrap;
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
  min-width: 0;
  --el-collapse-header-bg-color: transparent;
  --el-collapse-content-bg-color: transparent;
}

.check-item {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  min-width: 0;
  gap: 8px;
  padding: 6px 0;
  font-size: 12px;
}

.check-code {
  max-width: 100%;
  height: auto;
  line-height: 1.5;
  white-space: normal;
}

.check-code :deep(.el-tag__content) {
  min-width: 0;
  overflow-wrap: anywhere;
}

.check-node {
  max-width: 100%;
  overflow-wrap: anywhere;
  padding: 2px 6px;
  border-radius: 4px;
  background: var(--surface-glass-inset);
  color: var(--el-text-color-secondary);
  font-family: Consolas, Monaco, 'Courier New', monospace;
}

.check-message {
  flex-basis: 100%;
  min-width: 0;
  overflow-wrap: anywhere;
}
.exposure-box {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
}

.exposure-hint {
  margin: 0;
  font-size: 12px;
  line-height: 1.5;
  color: var(--text-secondary);

  &.is-warn {
    color: var(--status-warning, #b45309);
  }
}
</style>
