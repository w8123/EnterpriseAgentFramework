<template>
  <WorkbenchPage layout="list" class="business-capability-workbench">
    <PageHeader variant="overview" domain="platform" eyebrow="BUSINESS CAPABILITIES" title="业务能力"
      description="在当前项目中查找、理解和试用可调用的业务操作，并用于 Workflow。">
      <template #tags><el-tag effect="light">{{ scope?.currentScopeLabel.value || '范围未确认' }}</el-tag></template>
    </PageHeader>
    <el-tabs :model-value="activeTab" class="business-capability-tabs workbench-list-surface" @tab-change="selectTab">
      <el-tab-pane v-for="tab in tabs" :key="tab.value" :name="tab.value" :label="tab.label">
        <template v-if="activeTab === tab.value">
          <p class="business-capability-description">{{ tab.description }}</p>
          <router-view />
        </template>
      </el-tab-pane>
    </el-tabs>
  </WorkbenchPage>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import PageHeader from '@/components/common/PageHeader.vue'
import { usePageProjectScope } from '@/composables/usePageProjectScope'
import { BUSINESS_METHOD_CATALOG_PATH, HTTP_API_CATALOG_PATH } from './businessCapabilityRoutes'

const route = useRoute()
const router = useRouter()
const scope = usePageProjectScope()
const tabs = [
  { value: 'java-methods', label: 'Java 业务方法', path: BUSINESS_METHOD_CATALOG_PATH,
    description: '通过 SDK 明确开放的 Java 业务操作；查看输入返回、来源状态，并试调用或用于 Workflow。' },
  { value: 'http-apis', label: 'HTTP API', path: HTTP_API_CATALOG_PATH,
    description: '通过 HTTP 契约接入的业务操作；查看请求响应、来源与认证配置，并试调用或用于 Workflow。' },
]
const activeTab = computed(() => route.name === 'HttpApiCatalog' ? 'http-apis' : 'java-methods')
function selectTab(value: string | number) {
  const tab = tabs.find(item => item.value === value)
  if (tab && tab.value !== activeTab.value) void router.push({ path: tab.path, query: route.query })
}
</script>

<style scoped lang="scss">
.business-capability-tabs { display: flex; min-width: 0; flex-direction: column; }
.business-capability-tabs :deep(.el-tabs__content) { flex: 1 0 auto; overflow: visible; }
.business-capability-tabs :deep(.el-tabs__item) { font-weight: 600; }
.business-capability-description { margin: 0 0 var(--section-gap); color: var(--text-secondary); font-size: 13px; line-height: 1.6; }
</style>
