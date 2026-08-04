<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { MagicStick, Search } from '@element-plus/icons-vue'
import type {
  AnalysisFindingStatus,
  PageAnalysisFinding,
  ProjectPage,
} from '@/types/pageWorkbench'
import {
  pageWorkbenchHumanText,
  pageWorkbenchPageName,
} from '@/utils/pageWorkbenchPresentation'

type FindingFilter = 'ALL' | AnalysisFindingStatus

const props = defineProps<{
  findings: PageAnalysisFinding[]
  pages: ProjectPage[]
  selectedPageId?: number | null
  loading?: boolean
}>()

const emit = defineEmits<{
  analyze: [page: ProjectPage]
  status: [finding: PageAnalysisFinding, status: AnalysisFindingStatus]
  detail: [finding: PageAnalysisFinding]
  createTask: [finding: PageAnalysisFinding]
}>()

const activeFilter = ref<FindingFilter>('ALL')
const analysisPageId = ref<number | null>(props.selectedPageId || props.pages[0]?.id || null)
const keyword = ref('')

watch(
  () => props.selectedPageId,
  (pageId) => {
    if (pageId) analysisPageId.value = pageId
  },
)

watch(
  () => props.pages,
  (pages) => {
    if (!pages.some((page) => page.id === analysisPageId.value)) {
      analysisPageId.value = pages[0]?.id || null
    }
  },
)

const filters = computed(() => [
  { key: 'ALL' as const, label: '全部', count: props.findings.length },
  {
    key: 'UNREAD' as const,
    label: '未查看',
    count: props.findings.filter((item) => item.status === 'UNREAD').length,
  },
  {
    key: 'KEPT' as const,
    label: '已加入',
    count: props.findings.filter((item) => item.status === 'KEPT').length,
  },
  {
    key: 'IGNORED' as const,
    label: '已忽略',
    count: props.findings.filter((item) => item.status === 'IGNORED').length,
  },
])

const filteredFindings = computed(() => {
  const normalizedKeyword = keyword.value.trim().toLowerCase()
  return props.findings.filter((finding) => {
    const statusMatched =
      activeFilter.value === 'ALL' || finding.status === activeFilter.value
    const keywordMatched =
      !normalizedKeyword ||
      [finding.title, finding.confirmedFact, finding.pageKey, finding.category]
        .filter(Boolean)
        .some((value) => String(value).toLowerCase().includes(normalizedKeyword))
    return statusMatched && keywordMatched
  })
})

const targetFindings = computed(() =>
  props.findings.filter((finding) => finding.status === 'KEPT'),
)

const locatedFindingId = ref<number | null>(null)
let locateTimer: number | undefined

async function locateFinding(finding: PageAnalysisFinding) {
  activeFilter.value = 'KEPT'
  keyword.value = ''
  locatedFindingId.value = finding.id
  await nextTick()
  document
    .getElementById(`analysis-finding-${finding.id}`)
    ?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  if (locateTimer) window.clearTimeout(locateTimer)
  locateTimer = window.setTimeout(() => {
    locatedFindingId.value = null
  }, 1800)
}

onBeforeUnmount(() => {
  if (locateTimer) window.clearTimeout(locateTimer)
})

function selectedAnalysisPage() {
  return props.pages.find((page) => page.id === analysisPageId.value)
}

function statusLabel(status: AnalysisFindingStatus) {
  return {
    UNREAD: '待确认',
    KEPT: '已加入',
    IGNORED: '已忽略',
  }[status]
}
</script>

<template>
  <section class="page-workbench-section analysis-section" v-loading="loading">
    <div class="page-workbench-section__header analysis-section__header">
      <div>
        <h2>分析结果</h2>
        <p>查看每条结果的已确认事实、技术推断、待确认事项和实现依据。</p>
      </div>
      <div class="analysis-launcher">
        <el-select
          v-model="analysisPageId"
          filterable
          placeholder="选择页面"
          :disabled="!pages.length"
        >
          <el-option
            v-for="page in pages"
            :key="page.id"
            :label="pageWorkbenchPageName(page)"
            :value="page.id"
          />
        </el-select>
        <el-button
          type="primary"
          :icon="MagicStick"
          :disabled="!analysisPageId"
          @click="selectedAnalysisPage() && emit('analyze', selectedAnalysisPage()!)"
        >
          分析页面
        </el-button>
      </div>
    </div>

    <div class="analysis-toolbar">
      <div class="analysis-filters" role="group" aria-label="分析结果状态筛选">
        <button
          v-for="filter in filters"
          :key="filter.key"
          :class="['analysis-filter', { 'is-active': activeFilter === filter.key }]"
          type="button"
          @click="activeFilter = filter.key"
        >
          {{ filter.label }}
          <span>{{ filter.count }}</span>
        </button>
      </div>
      <el-input v-model="keyword" clearable placeholder="搜索结果">
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>
    </div>

    <div class="analysis-workspace">
      <div class="analysis-workspace__results">
        <div v-if="filteredFindings.length" class="analysis-card-grid">
          <article
            v-for="finding in filteredFindings"
            :id="`analysis-finding-${finding.id}`"
            :key="finding.id"
            :class="[
              'analysis-card',
              `is-${finding.status.toLowerCase()}`,
              { 'is-located': locatedFindingId === finding.id },
            ]"
          >
        <header class="analysis-card__header">
          <div>
            <small>
              {{ finding.pageKey }}
              <template v-if="finding.category">
                · {{ pageWorkbenchHumanText(finding.category, '页面分析') }}
              </template>
            </small>
            <h3>{{ pageWorkbenchHumanText(finding.title, '页面分析结果') }}</h3>
          </div>
          <el-tag effect="plain">{{ statusLabel(finding.status) }}</el-tag>
        </header>

        <dl class="analysis-card__content">
          <div>
            <dt>已确认事实</dt>
            <dd>
              {{
                pageWorkbenchHumanText(
                  finding.confirmedFact,
                  '当前结果未提供中文事实说明，请重新发起页面分析。',
                )
              }}
            </dd>
          </div>
          <div v-if="finding.technicalInference">
            <dt>技术推断</dt>
            <dd>
              {{
                pageWorkbenchHumanText(
                  finding.technicalInference,
                  '当前结果未提供中文技术推断。',
                )
              }}
            </dd>
          </div>
          <div v-if="finding.openQuestion">
            <dt>仍需确认</dt>
            <dd>
              {{
                pageWorkbenchHumanText(
                  finding.openQuestion,
                  '当前结果未提供中文待确认事项。',
                )
              }}
            </dd>
          </div>
        </dl>

        <div class="analysis-card__scope">
          <span>
            <strong>只读范围</strong>
            {{
              pageWorkbenchHumanText(
                finding.readScope,
                '当前页面及其直接依赖',
              )
            }}
          </span>
          <span v-if="finding.writeScope">
            <strong>写入范围</strong>
            {{ pageWorkbenchHumanText(finding.writeScope, '写入范围待确认') }}
          </span>
        </div>

        <div v-if="finding.relatedPages.length" class="analysis-card__related">
          <strong>关联页面</strong>
          <span v-for="pageKey in finding.relatedPages" :key="pageKey">{{ pageKey }}</span>
        </div>

        <footer class="analysis-card__actions">
          <div>
            <el-button
              v-if="finding.status !== 'IGNORED'"
              text
              @click="emit('status', finding, 'IGNORED')"
            >
              忽略
            </el-button>
            <el-button
              v-if="finding.status !== 'KEPT'"
              text
              @click="emit('status', finding, 'KEPT')"
            >
              加入目标
            </el-button>
          </div>
          <div>
            <el-button @click="emit('detail', finding)">查看依据</el-button>
            <el-button
              v-if="finding.status === 'KEPT'"
              type="primary"
              @click="emit('createTask', finding)"
            >
              新建任务
            </el-button>
          </div>
        </footer>
          </article>
        </div>

        <el-empty
          v-else
          :image-size="108"
          :description="
            findings.length
              ? '当前筛选条件下没有分析结果'
              : '选择具体页面发起只读分析后，结果会回到这里。'
          "
        />
      </div>

      <aside class="analysis-targets" aria-label="接入目标">
        <header class="analysis-targets__header">
          <div>
            <h3>接入目标</h3>
            <p>已选动作 {{ targetFindings.length }} 项</p>
          </div>
          <span>{{ targetFindings.length }}</span>
        </header>

        <div v-if="targetFindings.length" class="analysis-target-list">
          <article v-for="finding in targetFindings" :key="finding.id" class="analysis-target">
            <button
              class="analysis-target__locator"
              type="button"
              @click="locateFinding(finding)"
            >
              <small>
                {{ finding.pageKey }}
                <template v-if="finding.category">
                  · {{ pageWorkbenchHumanText(finding.category, '页面分析') }}
                </template>
              </small>
              <strong>{{ pageWorkbenchHumanText(finding.title, '待接入动作') }}</strong>
              <span>
                {{
                  pageWorkbenchHumanText(
                    finding.useCase || finding.writeScope || finding.confirmedFact,
                    '接入范围待确认',
                  )
                }}
              </span>
            </button>
            <footer>
              <el-button link @click="emit('status', finding, 'UNREAD')">移除</el-button>
              <el-button link type="primary" @click="emit('createTask', finding)">
                新建任务
              </el-button>
            </footer>
          </article>
        </div>

        <div v-else class="analysis-targets__empty">
          <strong>还没有接入目标</strong>
          <p>从左侧建议中选择“加入目标”，这里会形成待接入动作清单。</p>
        </div>
      </aside>
    </div>
  </section>
</template>
