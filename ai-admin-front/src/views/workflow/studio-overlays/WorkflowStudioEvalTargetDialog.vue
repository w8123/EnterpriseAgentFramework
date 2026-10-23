<template>
  <AppDialog
    :model-value="open"
    title="到运行中心评测"
    description="评测集按 Agent 保存在运行中心，可以反复运行、对比版本。选择要评测的 Agent。"
    width="520px"
    @update:model-value="(value: boolean) => emit('update:open', value)"
  >
    <LiquidGlassNotice
      v-if="loadError"
      type="error"
      title="没能读取引用这个 Workflow 的 Agent，请稍后重试。"
      source="backdrop"
    />
    <div v-else-if="targets.length" class="eval-target-list">
      <button
        v-for="agent in targets"
        :key="agent.agentId"
        class="eval-target"
        type="button"
        @click="emit('select', agent.agentId)"
      >
        <strong>{{ agent.agentName || agent.keySlug || agent.agentId }}</strong>
        <span>
          {{ agent.projectCode || '平台' }}
          <template v-if="agent.pinnedWorkflowVersion"> · 使用 {{ agent.pinnedWorkflowVersion }}</template>
          <template v-if="agent.stale"> · 不是最新版本</template>
        </span>
      </button>
    </div>
    <el-empty
      v-else
      :image-size="64"
      description="还没有已发布的 Agent 使用这个 Workflow。先在 Agent 里把它加为可调用 Workflow 并发布，再到运行中心建评测集。"
    />
    <p v-if="hiddenCount" class="eval-target-hidden">另有 {{ hiddenCount }} 个 Agent 在你无权查看的项目里。</p>
    <template #footer>
      <LiquidGlassButton @click="emit('update:open', false)">关闭</LiquidGlassButton>
      <LiquidGlassButton type="primary" plain @click="emit('open-overview')">打开评测总览</LiquidGlassButton>
    </template>
  </AppDialog>
</template>

<script setup lang="ts">
import AppDialog from '@/components/common/AppDialog.vue'
import type { WorkflowAgentReference } from '@/types/agent'
import { LiquidGlassNotice, LiquidGlassButton } from '@/components/common/liquid-glass'

defineProps<{
  open: boolean
  targets: WorkflowAgentReference[]
  hiddenCount: number
  loadError: boolean
}>()

const emit = defineEmits<{
  'update:open': [value: boolean]
  select: [agentId: string]
  'open-overview': []
}>()
</script>

<style scoped>
.eval-target-list {
  display: grid;
  gap: 8px;
}

.eval-target {
  display: grid;
  gap: 4px;
  padding: 12px 14px;
  border: 1px solid var(--border-glass-edge);
  border-radius: 10px;
  background: var(--surface-glass-inset);
  color: inherit;
  cursor: pointer;
  text-align: left;
}

.eval-target:hover {
  border-color: var(--el-color-primary-light-5);
}

.eval-target strong {
  color: var(--el-text-color-primary);
  font-size: 14px;
}

.eval-target span,
.eval-target-hidden {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
