<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { DataLine, MagicStick, Setting } from '@element-plus/icons-vue'
import AppDrawer from '@/components/common/AppDrawer.vue'
import {
  LiquidGlassButton,
  LiquidGlassEmptyState,
  LiquidGlassInput,
  LiquidGlassNotice,
  LiquidGlassSelect,
  LiquidGlassTable,
  LiquidGlassTabs,
} from '@/components/common/liquid-glass'
import {
  generateActivityInsights,
  getActivityInsights,
  getActivityReport,
  updateActivityInsight,
  updateActivitySetting,
} from '@/api/mcp'
import { getModelInstances } from '@/api/model'
import { normalizeActiveModelInstances } from '@/utils/modelSelection'
import { usePlatformCan } from '@/auth/usePlatformCan'
import type { ActivityInsight, ActivityInsightStatus, ActivityReport, ActivitySetting } from '@/types/mcp'
import type { ModelInstance } from '@/types/model'
import { formatCount, formatDateTime } from './aiUsageView'
import {
  activityViews,
  failureRate,
  insightEvidence,
  insightStatusLabels,
  operationLabel,
  pickDefaultModel,
  type ActivityView,
} from './activityView'

const props = defineProps<{
  days: number
  projectCode?: string
  systems: { projectCode: string; name: string }[]
}>()

const { canAny } = usePlatformCan()
const canManage = computed(() =>
  canAny('mcp-hub:write-approval:manage', 'mcp-hub:employee-connection:manage'),
)

const view = ref<ActivityView>('operations')
const loading = ref(false)
const loadError = ref('')
const report = ref<ActivityReport | null>(null)
let sequence = 0
// Insight responses for a system the manager has since left must not land on the new one.
let insightSequence = 0
let controller: AbortController | undefined

const insights = ref<ActivityInsight[]>([])
const insightsLoading = ref(false)
const insightError = ref('')
const generating = ref(false)
const generationNotice = ref('')
const models = ref<ModelInstance[]>([])
const modelId = ref('')
const drafts = ref<Record<number, string>>({})
const savingId = ref<number | null>(null)

const settingsOpen = ref(false)
const settingDraft = ref<ActivitySetting | null>(null)
const savingSetting = ref(false)

const summary = computed(() => report.value?.summary ?? {})
const hasData = computed(() => Boolean(summary.value.total))
const systemName = (code: string) => props.systems.find((system) => system.projectCode === code)?.name || code
const flows = computed(() => insights.value.filter((item) => item.kind === 'FLOW'))
const pitfalls = computed(() => insights.value.filter((item) => item.kind === 'PITFALL'))
const recommendedCount = computed(() => insights.value.filter((item) => item.status === 'RECOMMENDED').length)
const retentionOptions = [30, 90, 180, 365]

async function load() {
  const current = ++sequence
  controller?.abort()
  controller = new AbortController()
  loading.value = true
  loadError.value = ''
  try {
    const { data } = await getActivityReport(
      { days: props.days, projectCode: props.projectCode || undefined },
      controller.signal,
    )
    if (current !== sequence) return
    report.value = data
  } catch (error) {
    if (current !== sequence) return
    report.value = null
    loadError.value = message(error)
  } finally {
    if (current === sequence) loading.value = false
  }
}

async function loadInsights() {
  const current = ++insightSequence
  insights.value = []
  insightError.value = ''
  generationNotice.value = ''
  if (!props.projectCode) {
    insightsLoading.value = false
    generating.value = false
    return
  }
  insightsLoading.value = true
  generating.value = false
  try {
    const { data } = await getActivityInsights(props.projectCode)
    if (current !== insightSequence) return
    insights.value = data
    resetDrafts()
  } catch (error) {
    if (current === insightSequence) insightError.value = message(error)
  } finally {
    if (current === insightSequence) insightsLoading.value = false
  }
}

async function loadModels() {
  if (models.value.length) return
  try {
    const { data } = await getModelInstances({ modelType: 'LLM' })
    models.value = normalizeActiveModelInstances(data, 'LLM')
    if (!modelId.value) modelId.value = pickDefaultModel(models.value)?.id ?? ''
  } catch {
    models.value = []
  }
}

function resetDrafts() {
  drafts.value = Object.fromEntries(insights.value.map((item) => [item.id, item.shortcutPrompt ?? '']))
}

async function generate() {
  if (!props.projectCode) return
  const current = ++insightSequence
  generating.value = true
  insightError.value = ''
  try {
    const { data } = await generateActivityInsights(props.projectCode, {
      days: Math.max(props.days, 30),
      modelInstanceId: modelId.value || undefined,
    })
    if (current !== insightSequence) return
    insights.value = data.insights
    resetDrafts()
    generationNotice.value =
      data.notice ||
      (data.flows + data.pitfalls
        ? `分析了 ${formatCount(data.events)} 条操作、${formatCount(data.sessions)} 段使用过程，找到 ${data.flows} 个常用流程、${data.pitfalls} 个易错步骤。`
        : '操作记录还不够多，没有找到重复出现的流程或经常失败的步骤。')
  } catch (error) {
    if (current === insightSequence) insightError.value = message(error)
  } finally {
    if (current === insightSequence) generating.value = false
  }
}

async function setStatus(item: ActivityInsight, status: ActivityInsightStatus) {
  if (!props.projectCode) return
  const project = props.projectCode
  savingId.value = item.id
  try {
    const { data } = await updateActivityInsight(project, item.id, {
      status,
      shortcutPrompt: drafts.value[item.id] ?? item.shortcutPrompt ?? '',
    })
    if (project !== props.projectCode) return
    insights.value = insights.value.map((row) => (row.id === item.id ? data : row))
    drafts.value[item.id] = data.shortcutPrompt ?? ''
    ElMessage.success(
      status === 'RECOMMENDED' ? '已推荐给员工，员工在对应页面打开 AI 助手就能看到' : '已更新',
    )
  } catch (error) {
    ElMessage.error(message(error))
  } finally {
    savingId.value = null
  }
}

function openSettings() {
  const setting = report.value?.setting
  if (!setting) return
  settingDraft.value = { ...setting }
  settingsOpen.value = true
}

async function saveSettings() {
  if (!props.projectCode || !settingDraft.value) return
  savingSetting.value = true
  try {
    const { enabled, captureSearch, hideUserNames, retentionDays } = settingDraft.value
    const { data } = await updateActivitySetting(props.projectCode, {
      enabled,
      captureSearch,
      hideUserNames,
      retentionDays,
    })
    if (report.value) report.value = { ...report.value, setting: data }
    settingsOpen.value = false
    ElMessage.success('采集设置已保存，业务系统下次上报时生效')
  } catch (error) {
    ElMessage.error(message(error))
  } finally {
    savingSetting.value = false
  }
}

function message(error: unknown) {
  const failure = error as { response?: { data?: { message?: string } }; message?: string }
  return failure?.response?.data?.message || failure?.message || '请检查网络后重试'
}

watch(() => [props.days, props.projectCode], load, { immediate: true })
watch(
  () => props.projectCode,
  () => void loadInsights(),
  { immediate: true },
)
watch(view, (value) => {
  if (value === 'insights') void loadModels()
})
onBeforeUnmount(() => {
  ++sequence
  controller?.abort()
})
</script>

<template>
  <div class="activity-panel">
    <div class="activity-head">
      <p class="view-description">
        员工在已接入的业务系统里做了哪些操作、搜了什么、在哪一步出错。只记录接口路径、搜索词和错误信息，不记录填写的内容。
      </p>
      <LiquidGlassButton
        v-if="projectCode && report?.setting"
        :icon="Setting"
        :disabled="!canManage"
        :title="canManage ? '' : '需要管理人员权限'"
        @click="openSettings"
        >采集设置</LiquidGlassButton
      >
    </div>

    <LiquidGlassNotice
      v-if="loadError"
      type="error"
      title="员工操作加载失败"
      :description="loadError"
      ><template #actions
        ><LiquidGlassButton :disabled="loading" @click="load">重试</LiquidGlassButton></template
      ></LiquidGlassNotice
    >
    <LiquidGlassNotice
      v-else-if="report?.setting && !report.setting.enabled"
      type="warning"
      title="这个业务系统的采集已关闭"
      description="业务系统上报的操作会被忽略。需要时在「采集设置」里重新打开。"
    />

    <div v-if="hasData" class="activity-summary" role="group" aria-label="员工操作合计">
      <div class="activity-summary__item">
        <span>操作次数</span><strong>{{ formatCount(summary.total) }}</strong>
        <small>{{ formatCount(summary.operations) }} 种操作</small>
      </div>
      <div class="activity-summary__item">
        <span>员工</span><strong>{{ formatCount(summary.people) }} 人</strong>
        <small>最近一次 {{ formatDateTime(summary.lastAt ?? null) }}</small>
      </div>
      <div class="activity-summary__item">
        <span>失败</span><strong>{{ formatCount(summary.failures) }}</strong>
        <small>失败率 {{ failureRate(summary.total ?? 0, summary.failures ?? 0) }}</small>
      </div>
      <div class="activity-summary__item">
        <span>搜索</span><strong>{{ formatCount(summary.searches) }}</strong>
        <small>带搜索词的查询</small>
      </div>
    </div>

    <LiquidGlassTabs v-model="view" presentation="underline" class="activity-tabs" aria-label="员工操作视图">
      <el-tab-pane v-for="option in activityViews" :key="option.value" :name="option.value" :label="option.label" />
    </LiquidGlassTabs>

    <!-- AI 归纳 -->
    <template v-if="view === 'insights'">
      <LiquidGlassEmptyState
        v-if="!projectCode"
        compact
        :icon="MagicStick"
        title="先选择一个业务系统"
        description="AI 归纳按业务系统进行。在页面右上角选择业务系统后，可以归纳常用流程和易错步骤，并把快捷操作推荐给员工。"
      />
      <template v-else>
        <div class="insight-toolbar">
          <LiquidGlassSelect
            v-model="modelId"
            clearable
            filterable
            class="insight-model"
            placeholder="不用模型（只用程序统计）"
            dropdown-title="归纳用的模型"
            aria-label="归纳用的模型"
          >
            <el-option v-for="model in models" :key="model.id" :label="model.name" :value="model.id" />
          </LiquidGlassSelect>
          <LiquidGlassButton
            type="primary"
            :icon="MagicStick"
            :loading="generating"
            :disabled="!canManage"
            :title="canManage ? '' : '需要管理人员权限'"
            @click="generate"
            >{{ insights.length ? '重新归纳' : '开始归纳' }}</LiquidGlassButton
          >
          <span class="insight-hint"
            >分析近 {{ Math.max(days, 30) }} 天的操作。已推荐 {{ recommendedCount }} 条，员工在对应页面打开 AI
            助手时会看到。</span
          >
        </div>
        <LiquidGlassNotice v-if="insightError" type="error" title="归纳失败" :description="insightError" />
        <LiquidGlassNotice v-else-if="generationNotice" type="info" title="归纳完成" :description="generationNotice" />
        <div v-if="insightsLoading" v-loading="true" class="activity-loading" role="status" aria-label="正在加载归纳结果" />
        <LiquidGlassEmptyState
          v-else-if="!insights.length"
          compact
          :icon="MagicStick"
          title="还没有归纳结果"
          description="有了一段时间的操作记录后，点「开始归纳」。程序先找出员工反复做的几步操作和经常失败的操作，再由模型写成好懂的说明。"
        />
        <div v-else class="insight-groups">
          <section v-for="group in [
            { key: 'FLOW', title: '常用流程', items: flows, hint: '员工经常连着做的几步操作。推荐后，员工可以一键让助手代办。' },
            { key: 'PITFALL', title: '易错步骤', items: pitfalls, hint: '经常失败的操作。推荐后，员工在这个页面会看到提醒。' },
          ]" :key="group.key" class="insight-group">
            <header>
              <h3>{{ group.title }} <span class="tab-count">{{ group.items.length }}</span></h3>
              <p>{{ group.hint }}</p>
            </header>
            <p v-if="!group.items.length" class="muted">暂时没有。</p>
            <article
              v-for="item in group.items"
              :key="item.id"
              class="insight-card"
              :class="`is-${item.status.toLowerCase()}`"
            >
              <div class="insight-card__title">
                <strong>{{ item.title }}</strong>
                <span class="insight-status">{{ insightStatusLabels[item.status] }}</span>
                <span v-if="item.generatedBy === 'AI'" class="insight-source">AI 写的说明</span>
              </div>
              <p v-if="item.summary" class="insight-summary">{{ item.summary }}</p>
              <ol v-if="item.kind === 'FLOW'" class="insight-steps">
                <li v-for="step in item.steps" :key="step.operationKey" :title="step.operationKey">
                  {{ step.name || step.operationKey }}
                </li>
              </ol>
              <p v-if="item.advice" class="insight-advice"><span>建议</span>{{ item.advice }}</p>
              <p class="insight-evidence">
                {{ insightEvidence(item) }}<template v-if="item.pagePaths.length">
                  · 页面 {{ item.pagePaths.join('、') }}</template
                ><template v-if="item.status === 'RECOMMENDED'"> · 员工点过 {{ item.usedCount }} 次</template>
              </p>
              <label class="insight-prompt">
                <span>员工点快捷操作时发给助手的话</span>
                <LiquidGlassInput
                  v-model="drafts[item.id]"
                  :disabled="!canManage || savingId === item.id"
                  maxlength="200"
                  placeholder="例如：帮我新增一个客户（填写客户名称）"
                />
              </label>
              <div v-if="canManage" class="insight-actions">
                <LiquidGlassButton
                  v-if="item.status !== 'RECOMMENDED'"
                  type="primary"
                  :loading="savingId === item.id"
                  @click="setStatus(item, 'RECOMMENDED')"
                  >推荐给员工</LiquidGlassButton
                >
                <LiquidGlassButton
                  v-else-if="drafts[item.id] !== (item.shortcutPrompt ?? '')"
                  type="primary"
                  :loading="savingId === item.id"
                  @click="setStatus(item, 'RECOMMENDED')"
                  >保存修改</LiquidGlassButton
                >
                <LiquidGlassButton
                  v-if="item.status !== 'DISMISSED'"
                  :loading="savingId === item.id"
                  @click="setStatus(item, 'DISMISSED')"
                  >{{ item.status === 'RECOMMENDED' ? '停止推荐' : '不推荐' }}</LiquidGlassButton
                >
                <LiquidGlassButton
                  v-else
                  :loading="savingId === item.id"
                  @click="setStatus(item, 'SUGGESTED')"
                  >恢复待定</LiquidGlassButton
                >
              </div>
            </article>
          </section>
        </div>
      </template>
    </template>

    <!-- 统计 -->
    <template v-else>
      <div v-if="loading && !report" v-loading="true" class="activity-loading" role="status" aria-label="正在加载员工操作" />
      <div v-else-if="report && !hasData" class="activity-onboarding">
        <LiquidGlassEmptyState
          compact
          :icon="DataLine"
          title="还没有收到员工操作"
          description="业务系统打开采集后，员工在系统里查询、提交、搜索的记录会出现在这里。默认不采集，需要在业务系统里加一行配置。"
        />
        <div class="activity-setup">
          <div>
            <strong>Java（Spring Boot starter）</strong>
            <span>升级到最新的 reachai-spring-boot2-starter，在配置里加上：</span>
            <code>reachai.activity.enabled: true</code>
          </div>
          <div>
            <strong>PHP（Laravel / ThinkPHP）</strong>
            <span>注册 RecordActivity 中间件，并设置环境变量：</span>
            <code>REACHAI_ACTIVITY_ENABLED=true</code>
          </div>
        </div>
      </div>
      <LiquidGlassTable
        v-else-if="report"
        :loading="loading"
        :empty="false"
        source="backdrop"
        presentation="collection"
        class="usage-data-table"
      >
        <el-table v-if="view === 'operations'" :data="report.operations" empty-text="暂无" row-key="operationKey">
          <el-table-column label="操作" min-width="240"
            ><template #default="{ row }"
              ><div class="cell-stack">
                <strong>{{ operationLabel(row) }}</strong>
                <small>{{ row.operationKey }}<template v-if="!projectCode"> · {{ systemName(row.projectCode) }}</template></small>
              </div></template
            ></el-table-column
          >
          <el-table-column label="次数" min-width="90" align="right"
            ><template #default="{ row }">{{ formatCount(row.total) }}</template></el-table-column
          >
          <el-table-column label="人数" min-width="80" align="right"
            ><template #default="{ row }">{{ formatCount(row.people) }}</template></el-table-column
          >
          <el-table-column label="失败率" min-width="90" align="right"
            ><template #default="{ row }"
              ><span :class="{ danger: row.failures > 0 }">{{ failureRate(row.total, row.failures) }}</span></template
            ></el-table-column
          >
          <el-table-column label="平均耗时" min-width="100" align="right"
            ><template #default="{ row }">{{ row.avgDurationMs == null ? '—' : `${formatCount(row.avgDurationMs)} ms` }}</template></el-table-column
          >
        </el-table>
        <el-table v-else-if="view === 'keywords'" :data="report.keywords" empty-text="暂无搜索记录" row-key="keyword">
          <el-table-column label="搜索词" min-width="200"
            ><template #default="{ row }"
              ><div class="cell-stack">
                <strong>{{ row.keyword }}</strong>
                <small>{{ row.operationName || '' }}<template v-if="!projectCode"> · {{ systemName(row.projectCode) }}</template></small>
              </div></template
            ></el-table-column
          >
          <el-table-column label="次数" min-width="90" align="right"
            ><template #default="{ row }">{{ formatCount(row.total) }}</template></el-table-column
          >
          <el-table-column label="人数" min-width="80" align="right"
            ><template #default="{ row }">{{ formatCount(row.people) }}</template></el-table-column
          >
          <el-table-column label="最近" min-width="150"
            ><template #default="{ row }">{{ formatDateTime(row.lastAt) }}</template></el-table-column
          >
        </el-table>
        <el-table v-else-if="view === 'problems'" :data="report.problems" empty-text="最近没有失败的操作" row-key="operationKey">
          <el-table-column label="哪一步出错" min-width="200"
            ><template #default="{ row }"
              ><div class="cell-stack">
                <strong>{{ operationLabel(row) }}</strong>
                <small>{{ row.pagePath ? `页面 ${row.pagePath}` : row.operationKey }}<template v-if="!projectCode"> · {{ systemName(row.projectCode) }}</template></small>
              </div></template
            ></el-table-column
          >
          <el-table-column label="错误信息" min-width="240"
            ><template #default="{ row }"><span class="error-text">{{ row.errorMessage || '（没有错误信息）' }}</span></template></el-table-column
          >
          <el-table-column label="次数" min-width="80" align="right"
            ><template #default="{ row }">{{ formatCount(row.total) }}</template></el-table-column
          >
          <el-table-column label="人数" min-width="80" align="right"
            ><template #default="{ row }">{{ formatCount(row.people) }}</template></el-table-column
          >
        </el-table>
        <el-table v-else :data="report.people" empty-text="暂无" row-key="userId">
          <el-table-column label="员工" min-width="180"
            ><template #default="{ row }"
              ><div class="cell-stack">
                <strong>{{ row.userName || row.userId }}</strong>
                <small>{{ row.userName ? row.userId : '' }}<template v-if="!projectCode"> · {{ systemName(row.projectCode) }}</template></small>
              </div></template
            ></el-table-column
          >
          <el-table-column label="操作" min-width="90" align="right"
            ><template #default="{ row }">{{ formatCount(row.total) }}</template></el-table-column
          >
          <el-table-column label="用过的功能" min-width="100" align="right"
            ><template #default="{ row }">{{ formatCount(row.operations) }}</template></el-table-column
          >
          <el-table-column label="失败" min-width="80" align="right"
            ><template #default="{ row }"
              ><span :class="{ danger: row.failures > 0 }">{{ formatCount(row.failures) }}</span></template
            ></el-table-column
          >
          <el-table-column label="最近" min-width="150"
            ><template #default="{ row }">{{ formatDateTime(row.lastAt) }}</template></el-table-column
          >
        </el-table>
      </LiquidGlassTable>
    </template>

    <AppDrawer v-model="settingsOpen" title="采集设置" :description="projectCode ? systemName(projectCode) : ''" size="420px">
      <div v-if="settingDraft" class="setting-form">
        <label class="setting-row">
          <span><strong>接收操作记录</strong><small>关闭后业务系统会自动停止上报。</small></span>
          <el-switch v-model="settingDraft.enabled" />
        </label>
        <label class="setting-row">
          <span><strong>记录搜索词</strong><small>手机号、邮箱、证件号等会打码。</small></span>
          <el-switch v-model="settingDraft.captureSearch" />
        </label>
        <label class="setting-row">
          <span><strong>隐藏员工姓名</strong><small>只保存无法还原的员工编号，之后收到的记录生效。</small></span>
          <el-switch v-model="settingDraft.hideUserNames" />
        </label>
        <label class="setting-row">
          <span><strong>保留天数</strong><small>过期的记录每天凌晨自动删除。</small></span>
          <LiquidGlassSelect v-model="settingDraft.retentionDays" class="setting-retention" aria-label="保留天数">
            <el-option v-for="value in retentionOptions" :key="value" :label="`${value} 天`" :value="value" />
          </LiquidGlassSelect>
        </label>
        <div class="setting-actions">
          <LiquidGlassButton @click="settingsOpen = false">取消</LiquidGlassButton>
          <LiquidGlassButton type="primary" :loading="savingSetting" @click="saveSettings">保存</LiquidGlassButton>
        </div>
      </div>
    </AppDrawer>
  </div>
</template>

<style scoped lang="scss">
.activity-panel {
  display: grid;
  gap: 12px;
  min-width: 0;
}
.activity-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.view-description,
.insight-hint,
.insight-group header p {
  margin: 0;
  color: var(--text-secondary);
  font-size: 13px;
  line-height: 1.6;
}
.activity-summary {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 12px;
}
.activity-summary__item {
  display: grid;
  gap: 4px;
  padding: 12px 14px;
  border: 1px solid var(--border-subtle, rgba(127, 127, 127, 0.18));
  border-radius: 12px;
  min-width: 0;
  span {
    color: var(--text-secondary);
    font-size: 12px;
  }
  strong {
    color: var(--text-primary);
    font-size: 20px;
    font-weight: 650;
    font-variant-numeric: tabular-nums;
  }
  small {
    color: var(--text-muted);
    font-size: 12px;
  }
}
.activity-loading {
  min-height: 160px;
}
.activity-setup {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 12px;
  margin-top: 12px;
  > div {
    display: grid;
    gap: 6px;
    padding: 12px 14px;
    border: 1px dashed var(--border-subtle, rgba(127, 127, 127, 0.25));
    border-radius: 12px;
  }
  span {
    color: var(--text-secondary);
    font-size: 13px;
  }
  code {
    font-size: 12px;
    padding: 6px 8px;
    border-radius: 8px;
    background: var(--fill-subtle, rgba(127, 127, 127, 0.1));
    overflow-wrap: anywhere;
  }
}
.cell-stack {
  display: grid;
  gap: 2px;
  min-width: 0;
  strong {
    color: var(--text-primary);
    font-weight: 600;
    overflow-wrap: anywhere;
  }
  small {
    color: var(--text-muted);
    font-size: 12px;
    overflow-wrap: anywhere;
  }
}
.danger,
.error-text {
  color: var(--el-color-danger);
}
.error-text {
  overflow-wrap: anywhere;
}
.muted {
  color: var(--text-muted);
  font-size: 13px;
}
.insight-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}
.insight-model {
  width: min(280px, 100%);
}
.insight-groups {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 16px;
  align-items: start;
}
.insight-group {
  display: grid;
  gap: 10px;
  min-width: 0;
  h3 {
    margin: 0 0 2px;
    font-size: 15px;
    color: var(--text-primary);
  }
}
.tab-count {
  color: var(--text-muted);
  font-size: 12px;
  font-weight: 500;
}
.insight-card {
  display: grid;
  gap: 8px;
  padding: 12px 14px;
  border: 1px solid var(--border-subtle, rgba(127, 127, 127, 0.18));
  border-radius: 12px;
  min-width: 0;
  &.is-recommended {
    border-color: var(--el-color-primary-light-5, rgba(64, 158, 255, 0.5));
  }
  &.is-dismissed {
    opacity: 0.65;
  }
}
.insight-card__title {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  strong {
    color: var(--text-primary);
    font-size: 14px;
  }
}
.insight-status,
.insight-source {
  font-size: 12px;
  padding: 1px 8px;
  border-radius: 999px;
  background: var(--fill-subtle, rgba(127, 127, 127, 0.12));
  color: var(--text-secondary);
}
.is-recommended .insight-status {
  background: var(--el-color-primary-light-9, rgba(64, 158, 255, 0.12));
  color: var(--el-color-primary);
}
.insight-summary,
.insight-evidence,
.insight-advice {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--text-secondary);
  overflow-wrap: anywhere;
}
.insight-evidence {
  color: var(--text-muted);
  font-size: 12px;
}
.insight-advice span {
  margin-right: 6px;
  color: var(--el-color-warning);
  font-weight: 600;
}
.insight-steps {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin: 0;
  padding: 0;
  list-style: none;
  counter-reset: step;
  li {
    counter-increment: step;
    font-size: 12px;
    padding: 3px 10px;
    border-radius: 999px;
    background: var(--fill-subtle, rgba(127, 127, 127, 0.1));
    color: var(--text-primary);
    &::before {
      content: counter(step) '. ';
      color: var(--text-muted);
    }
  }
}
.insight-prompt {
  display: grid;
  gap: 4px;
  span {
    font-size: 12px;
    color: var(--text-muted);
  }
}
.insight-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.setting-form {
  display: grid;
  gap: 16px;
}
.setting-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  span {
    display: grid;
    gap: 2px;
  }
  strong {
    color: var(--text-primary);
    font-size: 14px;
  }
  small {
    color: var(--text-muted);
    font-size: 12px;
  }
}
.setting-retention {
  width: 120px;
}
.setting-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
