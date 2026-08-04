<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import {
  ArrowRight,
  Grid,
  MagicStick,
  Menu,
  Refresh,
  Search,
} from '@element-plus/icons-vue'
import pageMapAiCodingIllustration from '@/assets/illustrations/page-map-ai-coding.webp'
import type {
  PageAccessCenterSummary,
  PageAccessJourney,
  ProjectPage,
} from '@/types/pageWorkbench'
import {
  pageWorkbenchModuleName,
  pageWorkbenchPageDescription,
  pageWorkbenchPageName,
  pageWorkbenchSourceLabel,
} from '@/utils/pageWorkbenchPresentation'
import type { PageModuleView } from '@/views/registry/composables/useBusinessPageWorkbench'
import PageWorkbenchPageCover from './PageWorkbenchPageCover.vue'

type StatusFilter = 'ALL' | 'WAITING' | 'ACTIVE' | 'ACCEPTANCE' | 'COMPLETE' | 'ERROR'
type ViewMode = 'list' | 'card'

const props = defineProps<{
  pages: ProjectPage[]
  journeys: PageAccessJourney[]
  summary: PageAccessCenterSummary
  modules: PageModuleView[]
  loading?: boolean
}>()

const emit = defineEmits<{
  scan: []
  detail: [page: ProjectPage]
  action: [page: ProjectPage, journey: PageAccessJourney]
  refresh: []
}>()

const activeModule = ref('__all__')
const activeStatus = ref<StatusFilter>('ALL')
const keyword = ref('')
const viewMode = ref<ViewMode>('list')

const journeyMap = computed(() => new Map(props.journeys.map((item) => [item.pageKey, item])))
const journeyOrder = computed(() => new Map(props.journeys.map((item, index) => [item.pageKey, index])))

const statusOptions = computed<Array<{ key: StatusFilter; label: string; count: number }>>(() => {
  const options: Array<{ key: StatusFilter; label: string; count: number }> = [
    { key: 'ALL', label: '全部页面', count: props.summary.discoveredCount },
    { key: 'WAITING', label: '等待接入', count: props.summary.waitingCount },
    { key: 'ACTIVE', label: '正在进行', count: props.summary.activeCount },
    { key: 'ACCEPTANCE', label: '等待验收', count: props.summary.awaitingAcceptanceCount },
    { key: 'COMPLETE', label: '接入完成', count: props.summary.completedCount },
  ]
  const errorCount = props.journeys.filter(
    (item) => ['ACTION_REQUIRED', 'ERROR', 'UNAVAILABLE'].includes(item.status),
  ).length
  if (errorCount > 0) {
    options.push({ key: 'ERROR', label: '需要处理', count: errorCount })
  }
  return options
})

const filteredPages = computed(() => {
  const normalizedKeyword = keyword.value.trim().toLowerCase()
  return props.pages
    .filter((page) => {
      const journey = journeyMap.value.get(page.pageKey)
      const moduleMatched = activeModule.value === '__all__'
        || (page.moduleKey || '__ungrouped__') === activeModule.value
      const keywordMatched = !normalizedKeyword
        || [page.name, page.pageKey, page.routePattern, page.componentPath]
          .filter(Boolean)
          .some((value) => String(value).toLowerCase().includes(normalizedKeyword))
      return moduleMatched && keywordMatched && statusMatched(journey)
    })
    .sort((left, right) => (journeyOrder.value.get(left.pageKey) ?? 999)
      - (journeyOrder.value.get(right.pageKey) ?? 999))
})

const activeScopeLabel = computed(() => {
  const status = statusOptions.value.find((item) => item.key === activeStatus.value)?.label
    || '全部页面'
  if (activeModule.value === '__all__') return status
  const module = props.modules.find((item) => item.key === activeModule.value)
  return `${status} · ${module ? pageWorkbenchModuleName(module.name, module.key) : '未分组'}`
})

watch(
  () => props.modules,
  (modules) => {
    if (activeModule.value !== '__all__'
      && !modules.some((module) => module.key === activeModule.value)) {
      activeModule.value = '__all__'
    }
  },
)

watch(statusOptions, (options) => {
  if (!options.some((option) => option.key === activeStatus.value)) {
    activeStatus.value = 'ALL'
  }
})

function statusMatched(journey?: PageAccessJourney) {
  if (activeStatus.value === 'ALL') return true
  if (!journey) return activeStatus.value === 'ERROR'
  if (activeStatus.value === 'WAITING') {
    if (journey.status === 'WAITING') return true
    if (journey.status === 'ACTIVE') return false
    if (journey.stage === 'REAL_ACCEPTANCE') return false
    return !['COMPLETE', 'UNAVAILABLE'].includes(journey.status)
  }
  if (activeStatus.value === 'ACTIVE') {
    return journey.status === 'ACTIVE' && journey.stage !== 'REAL_ACCEPTANCE'
  }
  if (activeStatus.value === 'ACCEPTANCE') {
    return journey.stage === 'REAL_ACCEPTANCE'
      && !['COMPLETE', 'UNAVAILABLE'].includes(journey.status)
  }
  if (activeStatus.value === 'COMPLETE') return journey.status === 'COMPLETE'
  return ['ACTION_REQUIRED', 'ERROR', 'UNAVAILABLE'].includes(journey.status)
}

function statusClass(journey?: PageAccessJourney) {
  return journey ? `is-${journey.status.toLowerCase().replace('_', '-')}` : 'is-unavailable'
}

function completedSteps(journey?: PageAccessJourney) {
  return Math.max(0, Math.min(journey?.completedSteps || 0, 4))
}

function primaryAction(journey?: PageAccessJourney) {
  return journey && ['WAITING', 'ACTION_REQUIRED'].includes(journey.status)
}

function relatedContentLabel(page: ProjectPage) {
  const count = journeyMap.value.get(page.pageKey)?.resourceCount ?? page.resources.length
  return `${count} 项相关内容`
}

function actionMetaLabel(page: ProjectPage) {
  const count = journeyMap.value.get(page.pageKey)?.actionCount ?? page.actions.length
  return count > 0 ? `${count} 个页面操作` : '暂无页面操作'
}
</script>

<template>
  <section
    :class="['page-access-pages page-workbench-section', { 'is-empty': !pages.length }]"
    v-loading="loading"
  >
    <div v-if="pages.length" class="page-access-layout">
      <aside class="page-access-scope">
        <div class="page-access-scope__heading">
          <span>页面范围</span>
        </div>
        <div class="page-access-scope__list">
          <button
            v-for="option in statusOptions"
            :key="option.key"
            :class="[
              `is-status-${option.key.toLowerCase()}`,
              { 'is-active': activeStatus === option.key },
            ]"
            type="button"
            @click="activeStatus = option.key"
          >
            <span>{{ option.label }}</span>
            <small>{{ option.count }}</small>
          </button>
        </div>

        <p>业务模块</p>
        <div class="page-access-scope__list is-modules">
          <button
            :class="{ 'is-active': activeModule === '__all__' }"
            type="button"
            @click="activeModule = '__all__'"
          >
            <span>全部模块</span>
            <small>{{ pages.length }}</small>
          </button>
          <button
            v-for="module in modules"
            :key="module.key"
            :class="{ 'is-active': activeModule === module.key }"
            type="button"
            @click="activeModule = module.key"
          >
            <span>{{ pageWorkbenchModuleName(module.name, module.key) }}</span>
            <small>{{ module.count }}</small>
          </button>
        </div>
      </aside>

      <div class="page-access-list-area">
        <div class="page-access-list-toolbar">
          <div>
            <h2>{{ activeScopeLabel }}</h2>
          </div>
          <div class="page-access-view-toggle" role="group" aria-label="页面展示方式">
            <el-tooltip content="列表视图" placement="top">
              <button
                :class="{ 'is-active': viewMode === 'list' }"
                :aria-pressed="viewMode === 'list'"
                aria-label="列表视图"
                type="button"
                @click="viewMode = 'list'"
              >
                <el-icon><Menu /></el-icon>
              </button>
            </el-tooltip>
            <el-tooltip content="卡片视图" placement="top">
              <button
                :class="{ 'is-active': viewMode === 'card' }"
                :aria-pressed="viewMode === 'card'"
                aria-label="卡片视图"
                type="button"
                @click="viewMode = 'card'"
              >
                <el-icon><Grid /></el-icon>
              </button>
            </el-tooltip>
          </div>
          <el-input v-model="keyword" clearable placeholder="搜索页面、路由或组件">
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
          <el-button
            :icon="Refresh"
            aria-label="刷新状态"
            title="刷新状态"
            @click="emit('refresh')"
          />
        </div>

        <div v-if="filteredPages.length && viewMode === 'list'" class="page-access-card-list">
          <article
            v-for="page in filteredPages"
            :key="page.id"
            :class="['page-access-card', statusClass(journeyMap.get(page.pageKey))]"
          >
            <button class="page-access-card__main" type="button" @click="emit('detail', page)">
              <PageWorkbenchPageCover
                :title="pageWorkbenchPageName(page)"
                :route="page.routePattern || page.pageKey"
              />
              <span class="page-access-card__body">
                <span class="page-access-card__identity">
                  <span>
                    <small>{{ pageWorkbenchModuleName(page.moduleName, page.moduleKey) }}</small>
                    <em :class="`is-source-${page.sourceType.toLowerCase().replace('_', '-')}`">
                      {{ pageWorkbenchSourceLabel(page.sourceType) }}
                    </em>
                  </span>
                  <strong>{{ pageWorkbenchPageName(page) }}</strong>
                  <code>{{ page.routePattern || page.pageKey }}</code>
                  <span class="page-access-card__assets">
                    {{ journeyMap.get(page.pageKey)?.resourceCount ?? page.resources.length }} 项页面资源
                    ·
                    {{ journeyMap.get(page.pageKey)?.actionCount ?? page.actions.length }} 个页面操作
                  </span>
                </span>
                <span class="page-access-card__state">
                  <span>
                    <em>{{ journeyMap.get(page.pageKey)?.statusLabel || '状态不可用' }}</em>
                    <strong>{{ journeyMap.get(page.pageKey)?.title || '无法读取页面接入状态' }}</strong>
                  </span>
                  <p>
                    {{ journeyMap.get(page.pageKey)?.message || pageWorkbenchPageDescription(page) }}
                  </p>
                  <span class="page-access-progress">
                    <span aria-hidden="true">
                      <i
                        v-for="step in 4"
                        :key="step"
                        :class="{
                          'is-done': step <= completedSteps(journeyMap.get(page.pageKey)),
                          'is-current': step === completedSteps(journeyMap.get(page.pageKey)) + 1,
                        }"
                      />
                    </span>
                    <small v-if="completedSteps(journeyMap.get(page.pageKey)) === 4">已完成</small>
                    <small v-else>
                      {{ completedSteps(journeyMap.get(page.pageKey)) }} / 4
                    </small>
                  </span>
                </span>
              </span>
            </button>
            <footer>
              <el-button
                v-if="journeyMap.get(page.pageKey)"
                :type="primaryAction(journeyMap.get(page.pageKey)) ? 'primary' : 'default'"
                :disabled="!journeyMap.get(page.pageKey)?.nextAction.enabled"
                @click="emit('action', page, journeyMap.get(page.pageKey)!)"
              >
                {{ journeyMap.get(page.pageKey)?.nextAction.label }}
                <el-icon><ArrowRight /></el-icon>
              </el-button>
            </footer>
          </article>
        </div>

        <div v-else-if="filteredPages.length" class="page-map-page-grid">
          <button
            v-for="page in filteredPages"
            :key="page.id"
            class="page-map-card"
            type="button"
            @click="emit('detail', page)"
          >
            <PageWorkbenchPageCover
              :title="pageWorkbenchPageName(page)"
              :route="page.routePattern || page.pageKey"
            />
            <span class="page-map-card__body">
              <span class="page-map-card__heading">
                <strong>{{ pageWorkbenchPageName(page) }}</strong>
                <small>{{ pageWorkbenchSourceLabel(page.sourceType) }}</small>
              </span>
              <span class="page-map-card__route">{{ page.routePattern || page.pageKey }}</span>
              <span class="page-map-card__meta">
                <span>{{ relatedContentLabel(page) }}</span>
                <span>{{ actionMetaLabel(page) }}</span>
              </span>
              <span class="page-map-card__more">
                查看详情
                <el-icon><ArrowRight /></el-icon>
              </span>
            </span>
          </button>
        </div>

        <el-empty v-else :image-size="80" description="没有找到匹配的页面" />
      </div>
    </div>

    <div v-else class="page-map-empty">
      <div class="page-map-empty__visual">
        <img :src="pageMapAiCodingIllustration" alt="AI 编程工具扫描业务仓库并建立页面地图" />
      </div>
      <div class="page-map-empty__content">
        <small class="page-map-empty__eyebrow">页面接入中心</small>
        <h3>先发现第一个业务页面</h3>
        <p>扫描当前分支中的页面、路由和关联 API，或手动添加无法自动发现的特殊页面。</p>
        <button class="page-map-empty__action" type="button" @click="emit('scan')">
          <span class="page-map-empty__action-icon"><el-icon><MagicStick /></el-icon></span>
          <span><strong>扫描新页面</strong><small>AI 只读增量扫描，或手动添加</small></span>
          <el-icon class="page-map-empty__action-arrow"><ArrowRight /></el-icon>
        </button>
      </div>
    </div>
  </section>
</template>
