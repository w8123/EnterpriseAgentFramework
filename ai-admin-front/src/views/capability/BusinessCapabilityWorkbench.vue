<template>
  <WorkbenchPage layout="list" class="business-capability-workbench" :class="{ 'is-catalog': activeWorkspace === 'catalog', 'is-graph': activeWorkspace === 'graph' }">
    <PageHeader variant="standard" compact :artwork="false" presentation="plain" domain="platform" title="业务能力">
      <template #tags><el-tag type="info" effect="plain">{{ scope?.currentScopeLabel.value || '范围未确认' }}</el-tag></template>
      <template #actions><div class="business-capability-navigation">
      <nav v-if="!classificationMode.active.value" class="business-capability-workspaces" aria-label="业务能力工作视图">
        <router-link v-for="view in workspaces" :key="view.value" :to="workspaceLocation(view.value)"
          :class="{ 'is-active': activeSection === view.value }" :aria-current="activeSection === view.value ? 'page' : undefined">
          {{ view.label }}
        </router-link>
      </nav>
      </div></template>
    </PageHeader>
    <section class="business-capability-content workbench-list-surface" :aria-label="workspaceLabel">
      <el-tabs v-if="activeWorkspace === 'catalog'" :model-value="activeTab" :before-leave="selectTab"
        class="business-capability-tabs business-capability-catalog">
        <div class="business-capability-catalog-actions" aria-label="能力目录操作">
          <el-button v-if="classificationMode.active.value" class="catalog-tool-trigger" type="primary" plain :icon="Close"
            :loading="classificationMode.busy.value" @click="exitClassificationMode">退出调整</el-button>
          <el-dropdown v-if="activeWorkspace === 'catalog' && !classificationMode.active.value" trigger="click" placement="bottom-end" @command="openTool">
            <el-button ref="toolsTrigger" class="catalog-tool-trigger" :icon="SetUp">整理<el-icon class="el-icon--right" aria-hidden="true"><ArrowDown /></el-icon></el-button>
            <template #dropdown><el-dropdown-menu>
              <el-dropdown-item command="classification" :icon="Folder" :disabled="!classificationMode.canAdjust.value || classificationMode.busy.value || classificationMode.active.value"
                :title="!classificationMode.canAdjust.value ? '请先选择具有维护权限的项目' : undefined">调整分类</el-dropdown-item>
              <el-dropdown-item command="semantics" :icon="EditPen">批量整理说明</el-dropdown-item>
              <el-dropdown-item command="graph" :icon="Share">关联分析</el-dropdown-item>
            </el-dropdown-menu></template>
          </el-dropdown>
        </div>
        <el-tab-pane v-for="tab in tabs" :key="tab.value" :name="tab.value" :label="tab.label">
          <router-view v-if="activeTab === tab.value" />
        </el-tab-pane>
      </el-tabs>
      <router-view v-else />
    </section>
  </WorkbenchPage>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { ArrowDown, Close, EditPen, Folder, SetUp, Share } from '@element-plus/icons-vue'
import { isNavigationFailure, useRoute, useRouter } from 'vue-router'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import PageHeader from '@/components/common/PageHeader.vue'
import { usePageProjectScope } from '@/composables/usePageProjectScope'
import { platformSessionId } from '@/auth/platformSession'
import { provideCatalogClassificationMode } from './composables/useCatalogClassificationMode'
import { BUSINESS_METHOD_CATALOG_PATH, HTTP_API_CATALOG_PATH, businessCapabilityWorkspaceLocation,
  type BusinessCapabilityCategory, type BusinessCapabilityWorkspace } from './businessCapabilityRoutes'

const route = useRoute()
const router = useRouter()
const scope = usePageProjectScope()
const classificationMode = provideCatalogClassificationMode()
const toolsTrigger = ref<{ $el: HTMLElement }>()
const tabs = [
  { value: 'java-methods', label: 'Java 业务方法', path: BUSINESS_METHOD_CATALOG_PATH },
  { value: 'http-apis', label: 'HTTP API', path: HTTP_API_CATALOG_PATH },
]
const workspaces = [{ value: 'catalog', label: '能力目录' }, { value: 'sources', label: '接入与变更' }] as const
const workspaceLabels: Record<string, string> = { catalog: '能力目录', sources: '接入与变更',
  semantics: '批量整理说明', graph: '关联分析' }
const activeTab = computed<BusinessCapabilityCategory>(() => route.path.startsWith(HTTP_API_CATALOG_PATH) ? 'http-apis' : 'java-methods')
const activeWorkspace = computed(() => String(route.params.workspace || 'catalog'))
const activeSection = computed(() => activeWorkspace.value === 'sources' ? 'sources' : 'catalog')
const workspaceLabel = computed(() => workspaceLabels[activeWorkspace.value] || '能力目录')
watch([activeTab, activeWorkspace, () => route.query.projectId, () => route.query.projectCode,
  () => route.query.scope, platformSessionId], classificationMode.reset)
async function exitClassificationMode() {
  if (await classificationMode.requestExit()) {
    await nextTick()
    toolsTrigger.value?.$el.focus()
  }
}
function openTool(tool: string) {
  if (tool === 'classification') { classificationMode.enter(); return }
  if (['semantics', 'graph'].includes(tool)) void router.push(workspaceLocation(tool as BusinessCapabilityWorkspace))
}
function workspaceLocation(view: 'catalog' | BusinessCapabilityWorkspace) {
  return view === 'catalog'
    ? { path: activeTab.value === 'http-apis' ? HTTP_API_CATALOG_PATH : BUSINESS_METHOD_CATALOG_PATH, query: route.query }
    : businessCapabilityWorkspaceLocation(activeTab.value, view, route.query)
}
async function selectTab(value: string | number) {
  const tab = tabs.find(item => item.value === value)
  if (!tab) return false
  if (tab.value === activeTab.value) return true
  try {
    const failure = await router.push({ path: tab.path, query: route.query })
    return !isNavigationFailure(failure) && activeTab.value === tab.value
  } catch { return false }
}
</script>

<style scoped lang="scss">
.business-capability-navigation { display: flex; min-width: 0; align-items: center; justify-content: space-between;
  flex-wrap: wrap; gap: 4px 16px; }
.business-capability-workspaces { display: flex; min-width: 0; flex-wrap: wrap; gap: 4px; }
.business-capability-workspaces a { display: inline-flex; min-height: 36px; align-items: center; box-sizing: border-box;
  padding: 6px 10px; border-bottom: 2px solid transparent; color: var(--text-secondary); font-size: 13px;
  font-weight: 500; text-decoration: none; cursor: pointer; }
.business-capability-workspaces a:hover { color: var(--brand-active); background: var(--surface-solid-control); }
.business-capability-workspaces a.is-active { border-bottom-color: var(--brand-primary); color: var(--brand-active); font-weight: 600; }
.business-capability-workspaces a:focus-visible { outline: 2px solid var(--border-focus); outline-offset: -2px; border-radius: var(--radius-sm); }
.business-capability-content { display: flex; min-width: 0; flex-direction: column; }
.business-capability-catalog { display: grid; min-width: 0; grid-template-columns: minmax(0, 1fr) auto;
  grid-template-rows: auto minmax(0, 1fr); gap: 12px 16px; }
// Flatten only the role-free content wrapper; keep header → actions → panel in keyboard order.
.business-capability-tabs :deep(.el-tabs__content) { display: contents; }
.business-capability-tabs :deep(.el-tabs__header) { grid-column: 1; grid-row: 1; min-width: 0; margin: 0; align-self: center; }
.business-capability-catalog-actions { display: flex; align-items: center; justify-self: end; grid-column: 2; grid-row: 1; }
.catalog-tool-trigger { min-width: 112px; height: 44px; margin: 0; border-radius: var(--radius-md); }
.catalog-tool-trigger:not(.el-button--primary) { border-color: var(--border-readable); background: var(--surface-glass-control);
  box-shadow: var(--inner-highlight); color: var(--text-secondary); }
.catalog-tool-trigger:not(.el-button--primary):hover { border-color: var(--border-focus); background: var(--surface-glass-selected); color: var(--brand-active); }
.catalog-tool-trigger:focus-visible { outline: 2px solid var(--border-focus); outline-offset: 3px; }
.business-capability-tabs :deep(.el-tabs__nav-wrap::after), .business-capability-tabs :deep(.el-tabs__active-bar) { display: none; }
.business-capability-tabs :deep(.el-tabs__nav) { padding: 4px; border: 1px solid var(--border-readable); border-radius: var(--radius-md); background: var(--surface-glass-control); box-shadow: var(--inner-highlight); }
.business-capability-tabs :deep(.el-tab-pane) { min-width: 0;
  grid-column: 1 / -1; grid-row: 2; overflow: visible; }
.business-capability-tabs :deep(.el-tabs__item) { box-sizing: border-box; height: 36px; padding: 0 16px !important; border: 1px solid transparent; border-radius: var(--radius-sm); color: var(--text-secondary); font-size: 13px; font-weight: 500; }
.business-capability-tabs :deep(.el-tabs__item.is-active) { border-color: var(--border-subtle); background: var(--surface-glass-selected); box-shadow: var(--inner-highlight); color: var(--brand-active); font-weight: 600; }
@media (min-width: 961px) {
  .business-capability-workbench.is-graph { height: 100%; }
  .business-capability-workbench.is-graph > .business-capability-content { flex: 1; min-height: 0; }
  .business-capability-workbench.is-catalog { height: 100%; }
  .business-capability-workbench.is-catalog > .business-capability-content { flex: 1; min-height: 0; }
  .business-capability-catalog { flex: 1; min-height: 0; }
  .business-capability-tabs :deep(.el-tab-pane) { display: flex; flex: 1; min-height: 0; flex-direction: column; }
}
@media (max-width: 640px) {
  .business-capability-catalog { grid-template-columns: minmax(0, 1fr); grid-template-rows: auto auto auto; gap: 8px; }
  .business-capability-catalog-actions { grid-column: 1; grid-row: 2; }
  .business-capability-tabs :deep(.el-tab-pane) { grid-row: 3; }
  .business-capability-workspaces { gap: 0; }
  .business-capability-workspaces a { padding-inline: 10px; }
}
</style>
