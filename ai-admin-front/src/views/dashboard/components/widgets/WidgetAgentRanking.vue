<script setup lang="ts">
import type { DashboardAgentRankingItem, DashboardDomainStatus } from '@/types/operationsDashboard'
import DashboardPanelState from '../DashboardPanelState.vue'

defineProps<{
  status: DashboardDomainStatus
  items: DashboardAgentRankingItem[]
}>()

defineEmits<{
  open: [agent: DashboardAgentRankingItem]
  viewAll: []
  retry: []
}>()

function percentLabel(value: number | null) {
  return value == null ? '—' : `${value.toFixed(1)}%`
}
</script>

<template>
  <div class="agent-ranking widget-body">
    <div v-if="status === 'loading'" class="ops-ranking-state" aria-busy="true">Agent 排名加载中…</div>
    <DashboardPanelState
      v-else-if="status === 'error'"
      title="Agent 排名暂不可用"
      detail="运行数据源失败；没有把错误降级成空排行。"
      tone="error"
      @retry="$emit('retry')"
    />
    <DashboardPanelState
      v-else-if="!items.length"
      title="当前范围暂无 Agent 运行样本"
      detail="排名只来自真实 Agent 运行，不使用启用状态或示例数据补位。"
    />
    <table v-else class="ops-table">
      <colgroup>
        <col style="width: 38px" />
        <col />
        <col style="width: 58px" />
        <col style="width: 46px" />
        <col style="width: 58px" />
      </colgroup>
      <thead>
        <tr><th>排名</th><th>Agent / 项目</th><th>配置态</th><th>样本</th><th>完成率</th></tr>
      </thead>
      <tbody>
        <tr
          v-for="(agent, index) in items"
          :key="agent.key"
          tabindex="0"
          :aria-label="`查看 ${agent.name}`"
          @click="$emit('open', agent)"
          @keydown.enter="$emit('open', agent)"
          @keydown.space.prevent="$emit('open', agent)"
        >
          <td>
            <span class="ops-rank" :class="{ 'is-top': index < 3 }"><span>{{ index + 1 }}</span></span>
          </td>
          <td>
            <span class="ops-entity">
              <span class="ops-entity__icon">AI</span>
              <span class="ops-entity__text"><strong>{{ agent.name }}</strong><small>{{ agent.projectName }}</small></span>
            </span>
          </td>
          <td>
            <span
              class="ops-status"
              :class="agent.enabled === true ? 'is-enabled' : agent.enabled === false ? 'is-disabled' : ''"
            ><i />{{ agent.enabled === true ? '启用' : agent.enabled === false ? '停用' : '未知' }}</span>
          </td>
          <td class="ops-table-number">{{ agent.runs }}</td>
          <td>
            <span class="ops-rate">
              {{ percentLabel(agent.technicalCompletionRate) }}
              <span class="ops-rate__bar"><i :style="{ width: `${agent.technicalCompletionRate ?? 0}%` }" /></span>
            </span>
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<style scoped lang="scss">
.widget-body {
  flex: 1;
  display: flex;
  flex-direction: column;
}

.agent-ranking {
  container-type: inline-size;
}

// 3/12 列在 1440px 窗口下不足以同时容纳名称、配置态、样本和完成率。
// 配置态是次要列，小容器隐藏它，保留领导扫描时更重要的 Agent 名称与结果指标。
@container (max-width: 320px) {
  .ops-table col:nth-child(3),
  .ops-table th:nth-child(3),
  .ops-table td:nth-child(3) {
    display: none;
  }
}
</style>
