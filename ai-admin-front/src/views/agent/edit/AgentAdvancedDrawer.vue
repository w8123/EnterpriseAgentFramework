<template>
  <AppDrawer
    v-model="advancedSettingsVisible"
    title="高级运行设置"
    description="大多数 Agent 保持默认值即可。修改后会和当前配置一起保存。"
    size="720px"
    append-to-body
  >
    <div class="form-grid two">
      <el-form-item label="运行时">
        <el-input model-value="AgentScope Java 2.0.0 GA" disabled />
      </el-form-item>
      <el-form-item label="可调用 Workflow">
        <el-input model-value="仅限当前已添加的 Workflow" disabled />
      </el-form-item>
      <el-form-item label="权限与风险策略" class="wide">
        <el-select v-model="supervisor.policyProfile">
          <el-option label="标准策略" value="STANDARD" />
          <el-option label="严格策略" value="STRICT" />
          <el-option label="研发模式（放宽租户与权限检查）" value="DEV_ALLOW_ALL" />
        </el-select>
      </el-form-item>
      <el-form-item label="最多执行步骤">
        <el-input-number v-model="supervisor.maxPlanSteps" :min="1" :max="20" />
      </el-form-item>
      <el-form-item label="最多调用 Workflow">
        <el-input-number v-model="supervisor.maxWorkflowCalls" :min="1" :max="20" />
      </el-form-item>
      <el-form-item label="最多重新规划">
        <el-input-number v-model="supervisor.maxReplans" :min="0" :max="10" />
      </el-form-item>
      <el-form-item label="总超时（秒）">
        <el-input-number v-model="totalTimeoutSeconds" :min="10" :max="600" />
      </el-form-item>
      <el-form-item label="Workflow 超时（秒）">
        <el-input-number v-model="workflowTimeoutSeconds" :min="5" :max="300" />
      </el-form-item>
      <el-form-item label="页面操作超时（秒）">
        <el-input-number v-model="pageBridgeTimeoutSeconds" :min="5" :max="120" />
      </el-form-item>
      <el-form-item label="可同时执行只读 Workflow" class="wide">
        <el-switch v-model="supervisor.parallelReadOnly" active-text="允许" inactive-text="串行" />
      </el-form-item>
    </div>
    <LiquidGlassNotice
      class="advanced-settings-note"
      :type="supervisor.policyProfile === 'DEV_ALLOW_ALL' ? 'warning' : 'success'"
      :title="policyProfileHint"
      source="backdrop"
    />
    <LiquidGlassNotice
      class="advanced-settings-note"
      type="info"
      title="总超时是 Agent 完成整次任务的时间上限，应大于单个 Workflow 超时；等待用户确认的时间不计入页面操作超时。"
      source="backdrop"
    />
    <el-form-item v-if="showCustomPolicy" label="自定义策略（JSON）">
      <el-input
        v-model="entryConfigText"
        type="textarea"
        :rows="6"
        placeholder='策略 JSON，如 {"policy":{"allowedTenantIds":["default"],"permissionRoles":{"team:read":["operator"]},"irreversibleMode":"DENY"}}'
      />
    </el-form-item>
    <template #footer>
      <LiquidGlassButton type="primary" @click="advancedSettingsVisible = false">完成</LiquidGlassButton>
    </template>
  </AppDrawer>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import AppDrawer from '@/components/common/AppDrawer.vue'
import { useAgentEditorContext } from './useAgentEditor'
import { LiquidGlassNotice, LiquidGlassButton } from '@/components/common/liquid-glass'

const {
  advancedSettingsVisible,
  entryConfigText,
  supervisor,
  totalTimeoutSeconds,
  workflowTimeoutSeconds,
  pageBridgeTimeoutSeconds,
  policyProfileHint,
  isDevelopmentPolicy,
} = useAgentEditorContext()

/** 自定义策略只在研发模式或已有内容时显示，避免业务配置时误填。 */
const showCustomPolicy = computed(() => isDevelopmentPolicy.value
  || !['', '{}'].includes((entryConfigText.value || '').replace(/\s+/g, '')))
</script>

<style scoped lang="scss">
.agent-workbench__main,
.form-grid {
  min-width: 0;
}

.form-grid {
  display: grid;
  gap: var(--section-gap);
}

.form-grid.two {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.wide {
  grid-column: span 2;
}

.advanced-settings-note {
  margin-bottom: var(--section-gap);
}

.config-asset-card__actions .el-button {
  padding: 6px;
}

.workflow-picker-searchbar .el-input {
  flex: 1;
  min-width: 0;
}

@media (max-width: 960px) {
  .form-grid.two {
      grid-template-columns: 1fr;
    }

  .wide {
      grid-column: auto;
    }

  .workflow-tool-zero-state .el-button {
      width: 100%;
    }
}

@media (max-width: 720px) {
  .config-card-settings-grid .wide {
      grid-column: auto;
    }
}
</style>
