<template>
  <WorkbenchPage layout="list" class="business-capability-workbench" :class="{ 'is-catalog': activeWorkspace === 'catalog', 'is-graph': activeWorkspace === 'graph' }">
    <PageHeader variant="standard" compact :artwork="false" presentation="plain" action-presentation="inline" domain="platform" title="业务能力"
      description="查找和维护 Java 业务方法与 HTTP API。">
      <template #actions><div class="business-capability-navigation">
      <LiquidGlassNav v-if="!classificationMode.active.value" source="wallpaper" class="business-capability-workspaces" aria-label="业务能力工作视图">
        <router-link v-for="view in workspaces" :key="view.value" :to="workspaceLocation(view.value)"
          :class="{ 'is-active': activeSection === view.value }" :aria-current="activeSection === view.value ? 'page' : undefined">
          {{ view.label }}
        </router-link>
      </LiquidGlassNav>
      <div v-if="activeWorkspace === 'catalog'" class="business-capability-catalog-actions" aria-label="能力目录操作">
        <LiquidGlassButton v-if="classificationMode.active.value" source="wallpaper" class="catalog-tool-trigger" type="primary" :icon="Close"
          :loading="classificationMode.busy.value" @click="exitClassificationMode">退出调整</LiquidGlassButton>
        <LiquidGlassDropdown v-if="activeWorkspace === 'catalog' && !classificationMode.active.value" source="wallpaper" title="整理能力目录" trigger="click" placement="bottom-end" @command="openTool">
          <LiquidGlassButton ref="toolsTrigger" source="wallpaper" class="catalog-tool-trigger" :icon="SetUp">整理<el-icon class="el-icon--right" aria-hidden="true"><ArrowDown /></el-icon></LiquidGlassButton>
          <template #dropdown><el-dropdown-menu>
            <el-dropdown-item command="classification" :icon="Folder" :disabled="!classificationMode.canAdjust.value || classificationMode.busy.value || classificationMode.active.value"
              :title="!classificationMode.canAdjust.value ? '请先选择具有维护权限的项目' : undefined">调整分类</el-dropdown-item>
            <el-dropdown-item command="semantics" :icon="EditPen">批量整理说明</el-dropdown-item>
            <el-dropdown-item command="graph" :icon="Share">关联分析</el-dropdown-item>
          </el-dropdown-menu></template>
        </LiquidGlassDropdown>
      </div>
      </div></template>
    </PageHeader>
    <section class="business-capability-content workbench-list-surface" :aria-label="workspaceLabel">
      <LiquidGlassTabs v-if="activeWorkspace === 'catalog'" source="wallpaper" :model-value="activeTab" :before-leave="selectTab"
        class="business-capability-tabs business-capability-catalog">
        <el-tab-pane v-for="tab in tabs" :key="tab.value" :name="tab.value" :label="tab.label">
          <router-view v-if="activeTab === tab.value" />
        </el-tab-pane>
      </LiquidGlassTabs>
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
import { LiquidGlassButton, LiquidGlassDropdown, LiquidGlassNav, LiquidGlassTabs } from '@/components/common/liquid-glass'
import { platformSessionId } from '@/auth/platformSession'
import { provideCatalogClassificationMode } from './composables/useCatalogClassificationMode'
import { BUSINESS_METHOD_CATALOG_PATH, HTTP_API_CATALOG_PATH, businessCapabilityWorkspaceLocation,
  type BusinessCapabilityCategory, type BusinessCapabilityWorkspace } from './businessCapabilityRoutes'

const route = useRoute()
const router = useRouter()
const classificationMode = provideCatalogClassificationMode()
const toolsTrigger = ref<{ focus: () => void }>()
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
    toolsTrigger.value?.focus()
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
.business-capability-navigation { display: flex; min-width: 0; align-items: center; flex-wrap: wrap; gap: var(--layout-page-header-action-gap); }
.business-capability-content { display: flex; min-width: 0; flex-direction: column; }
.business-capability-catalog { display: flex; min-width: 0; flex-direction: column; gap: var(--layout-heading-spacing); }
// The native tabs retain their keyboard and panel lifecycle behavior.
.business-capability-tabs :deep(.el-tabs__content) { display: contents; }
.business-capability-tabs :deep(.el-tabs__header) { flex: 0 0 auto; min-width: 0; margin: 0; align-self: flex-start; }
.business-capability-catalog-actions { display: flex; align-items: center; gap: var(--layout-page-header-action-gap); }
.catalog-tool-trigger { min-width: 112px; }
.business-capability-tabs :deep(.el-tab-pane) { min-width: 0; overflow: visible; }
@media (min-width: 961px) {
  .business-capability-workbench:is(.is-graph, .is-catalog) { height: 100%; }
  .business-capability-workbench:is(.is-graph, .is-catalog) > .business-capability-content { flex: 1; min-height: 0; }
  .business-capability-catalog { flex: 1; min-height: 0; }
  .business-capability-tabs :deep(.el-tab-pane) { display: flex; flex: 1; min-height: 0; flex-direction: column; }
}
</style>
