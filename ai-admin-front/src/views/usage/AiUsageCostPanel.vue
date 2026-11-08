<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Coin } from '@element-plus/icons-vue'
import {
  LiquidGlassButton,
  LiquidGlassEmptyState,
  LiquidGlassNotice,
  LiquidGlassTable,
  LiquidGlassTabs,
} from '@/components/common/liquid-glass'
import { getAiCostReport } from '@/api/mcp'
import type { AiCostLine, AiCostReport } from '@/types/mcp'
import { costDimensions, formatCost, formatCount, formatTokens, type CostDimension } from './aiUsageView'

const props = defineProps<{ days: number; projectCode?: string }>()

const router = useRouter()
const loading = ref(false)
const loadError = ref('')
const report = ref<AiCostReport | null>(null)
const dimension = ref<CostDimension>('people')
let sequence = 0
let controller: AbortController | undefined

const rows = computed<AiCostLine[]>(() => {
  const value = report.value
  if (!value) return []
  if (dimension.value === 'byDay') return [...value.byDay].reverse()
  return value[dimension.value]
})
const total = computed(() => report.value?.total)
const currency = computed(() => report.value?.currency ?? 'CNY')
const dimensionHint = computed(
  () => costDimensions.find((option) => option.value === dimension.value)?.hint ?? '',
)
const hasUsage = computed(() => Boolean(total.value?.calls))

async function load() {
  const current = ++sequence
  controller?.abort()
  controller = new AbortController()
  loading.value = true
  loadError.value = ''
  try {
    const { data } = await getAiCostReport(
      { days: props.days, projectCode: props.projectCode || undefined },
      controller.signal,
    )
    if (current !== sequence) return
    report.value = data
  } catch (error) {
    if (current !== sequence) return
    report.value = null
    const failure = error as { response?: { data?: { message?: string } }; message?: string }
    loadError.value = failure?.response?.data?.message || failure?.message || '请检查网络后重试'
  } finally {
    if (current === sequence) loading.value = false
  }
}

watch(() => [props.days, props.projectCode], load, { immediate: true })
onBeforeUnmount(() => {
  ++sequence
  controller?.abort()
})
</script>

<template>
  <div class="cost-panel">
    <p class="view-description">
      ReachAI 自己调用大模型花的 Token 和估算费用（按模型中心里填的价格计算）。员工自己的 Claude Code、Codex
      由员工的工具计费，这里看不到。
    </p>

    <LiquidGlassNotice
      v-if="loadError"
      type="error"
      title="费用加载失败"
      :description="loadError"
      ><template #actions
        ><LiquidGlassButton :disabled="loading" @click="load">重试</LiquidGlassButton></template
      ></LiquidGlassNotice
    >
    <LiquidGlassNotice v-else-if="report?.notice" type="info" title="提示" :description="report.notice" />
    <LiquidGlassNotice
      v-if="report?.unpricedModels.length"
      type="warning"
      title="有模型还没填价格"
      :description="`${report.unpricedModels.map((model) => model.name).join('、')} 只统计了 Token，没算进费用。到模型中心打开模型，在「价格」里填写每百万 Token 的价格。`"
      ><template #actions
        ><LiquidGlassButton @click="router.push('/model/instances')">去填价格</LiquidGlassButton></template
      ></LiquidGlassNotice
    >

    <div v-if="report && report.available" class="cost-summary" role="group" aria-label="费用合计">
      <div class="cost-summary__item">
        <span>估算费用</span>
        <strong
          >{{ formatCost(total?.cost ?? null, currency) }}<small v-if="total?.partialCost"> 起</small></strong
        >
      </div>
      <div class="cost-summary__item">
        <span>Token</span>
        <strong>{{ formatTokens((total?.inputTokens ?? 0) + (total?.outputTokens ?? 0)) }}</strong>
        <small
          >输入 {{ formatTokens(total?.inputTokens ?? 0) }} · 输出 {{ formatTokens(total?.outputTokens ?? 0) }}</small
        >
      </div>
      <div class="cost-summary__item">
        <span>模型调用</span>
        <strong>{{ formatCount(total?.calls) }} 次</strong>
        <small>涉及 {{ formatCount(total?.runs) }} 次运行</small>
      </div>
    </div>

    <LiquidGlassTabs
      v-if="report && report.available && hasUsage"
      v-model="dimension"
      presentation="underline"
      class="cost-tabs"
      aria-label="费用统计维度"
    >
      <el-tab-pane v-for="option in costDimensions" :key="option.value" :name="option.value" :label="option.label" />
    </LiquidGlassTabs>
    <p v-if="report && report.available && hasUsage" class="view-description">{{ dimensionHint }}</p>

    <div
      v-if="loading && !report"
      v-loading="true"
      class="cost-loading"
      element-loading-text="正在加载费用"
      role="status"
      aria-label="正在加载费用"
    />
    <LiquidGlassEmptyState
      v-else-if="report && (!report.available || !hasUsage)"
      compact
      :icon="Coin"
      :title="report.available ? '当前范围还没有模型用量' : '暂时看不到费用'"
      :description="
        report.available
          ? '员工在业务系统里对话、自动化任务运行或调用工作流后，这里会按人、系统和模型统计 Token 与费用。'
          : report.notice || ''
      "
    />
    <LiquidGlassTable
      v-else-if="report"
      :loading="loading"
      :empty="!rows.length"
      source="backdrop"
      presentation="collection"
      class="usage-data-table"
    >
      <el-table :data="rows" empty-text=" " row-key="key">
        <el-table-column :label="costDimensions.find((option) => option.value === dimension)?.column" min-width="180"
          ><template #default="{ row }"
            ><div class="cell-stack">
              <strong>{{ row.name }}</strong>
            </div></template
          ></el-table-column
        >
        <el-table-column label="估算费用" min-width="130" align="right"
          ><template #default="{ row }"
            ><span :class="{ muted: row.cost == null }"
              >{{ formatCost(row.cost, currency) }}<small v-if="row.partialCost && row.cost != null"> 起</small></span
            ></template
          ></el-table-column
        >
        <el-table-column label="输入 Token" min-width="120" align="right"
          ><template #default="{ row }">{{ formatTokens(row.inputTokens) }}</template></el-table-column
        >
        <el-table-column label="输出 Token" min-width="120" align="right"
          ><template #default="{ row }">{{ formatTokens(row.outputTokens) }}</template></el-table-column
        >
        <el-table-column label="模型调用" min-width="100" align="right"
          ><template #default="{ row }">{{ formatCount(row.calls) }}</template></el-table-column
        >
      </el-table>
    </LiquidGlassTable>
  </div>
</template>

<style scoped lang="scss">
.cost-panel {
  display: grid;
  gap: 12px;
  min-width: 0;
}
.view-description {
  margin: 0;
  color: var(--text-secondary);
  font-size: 13px;
  line-height: 1.6;
}
.cost-summary {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 12px;
}
.cost-summary__item {
  display: grid;
  gap: 4px;
  padding: 12px 14px;
  border: 1px solid var(--border-subtle, rgba(127, 127, 127, 0.18));
  border-radius: 12px;
  min-width: 0;
}
.cost-summary__item span {
  color: var(--text-secondary);
  font-size: 12px;
}
.cost-summary__item strong {
  color: var(--text-primary);
  font-size: 20px;
  font-weight: 650;
  font-variant-numeric: tabular-nums;
}
.cost-summary__item small {
  color: var(--text-muted);
  font-size: 12px;
}
.cost-loading {
  min-height: 160px;
}
.cell-stack strong {
  color: var(--text-primary);
  font-weight: 600;
  overflow-wrap: anywhere;
}
.muted {
  color: var(--text-muted);
}
</style>
