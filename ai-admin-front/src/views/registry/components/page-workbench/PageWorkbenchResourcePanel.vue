<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import { ArrowDown, CopyDocument } from '@element-plus/icons-vue'
import type { ProjectPage } from '@/types/pageWorkbench'
import {
  pageWorkbenchResourceName,
} from '@/utils/pageWorkbenchPresentation'

const props = defineProps<{
  page: ProjectPage
}>()

const expandedByType = reactive<Record<string, boolean>>({})

const resourceGroups = computed(() => {
  const groups = new Map<string, ProjectPage['resources']>()
  for (const resource of props.page.resources) {
    const current = groups.get(resource.resourceType) || []
    current.push(resource)
    groups.set(resource.resourceType, current)
  }
  const order = ['API', 'COMPONENT', 'ROUTE', 'STORE', 'CONFIG', 'PERMISSION', 'STYLE', 'TEST']
  return [...groups.entries()].sort(([left], [right]) => {
    const leftIndex = order.indexOf(left)
    const rightIndex = order.indexOf(right)
    return (leftIndex < 0 ? order.length : leftIndex)
      - (rightIndex < 0 ? order.length : rightIndex)
  })
})

watch(
  () => props.page.id,
  () => {
    for (const key of Object.keys(expandedByType)) {
      delete expandedByType[key]
    }
  },
)

function isGroupExpanded(type: string) {
  return expandedByType[type] === true
}

function toggleGroup(type: string) {
  expandedByType[type] = !isGroupExpanded(type)
}

function resourceTypeLabel(type: string) {
  return ({
    ROUTE: '路由',
    COMPONENT: '组件',
    API: 'API',
    CONFIG: '配置',
    PERMISSION: '权限',
    STORE: 'Store',
    STYLE: '样式',
    TEST: '测试',
  }[type] || type)
}

function resourceLocationText(resource: ProjectPage['resources'][number]) {
  return resource.location || resource.resourceKey
}

function httpMethodTone(method?: string | null) {
  const normalized = method?.trim().toUpperCase()
  if (!normalized) return ''
  if (normalized === 'GET' || normalized === 'HEAD' || normalized === 'OPTIONS') return 'is-read'
  if (normalized === 'DELETE') return 'is-danger'
  return 'is-write'
}
</script>

<template>
  <section
    class="page-resource-inline foundation-section-card"
    aria-labelledby="page-resource-inline-title"
  >
    <header class="foundation-section-heading">
      <span class="foundation-section-heading__icon"><el-icon><CopyDocument /></el-icon></span>
      <div>
        <small>页面实现依据</small>
        <h3 id="page-resource-inline-title">页面资源</h3>
        <p>集中查看页面实现所依赖的接口、组件、路由和其他代码资源。</p>
      </div>
      <span class="foundation-section-count"><strong>{{ page.resources.length }}</strong> 项资源</span>
    </header>

    <div v-if="resourceGroups.length" class="resource-directory-list">
      <article
        v-for="[type, resources] in resourceGroups"
        :key="type"
        :class="[
          'resource-directory-group',
          `is-type-${type.toLowerCase()}`,
          { 'is-collapsed': !isGroupExpanded(type) },
        ]"
      >
        <button
          type="button"
          class="resource-directory-group__toggle"
          :aria-expanded="isGroupExpanded(type)"
          :aria-controls="`resource-group-${type}`"
          :title="isGroupExpanded(type) ? '收起' : '展开'"
          @click="toggleGroup(type)"
        >
          <b>{{ resourceTypeLabel(type) }}</b>
          <small>{{ resources.length }} 项</small>
          <el-icon class="resource-directory-group__chevron"><ArrowDown /></el-icon>
        </button>
        <div
          v-show="isGroupExpanded(type)"
          :id="`resource-group-${type}`"
          class="resource-directory-items"
        >
          <span v-for="resource in resources" :key="resource.id" class="resource-directory-item">
            <span>
              <strong>
                <code
                  v-if="resource.httpMethod"
                  :class="['resource-http-method', httpMethodTone(resource.httpMethod)]"
                >{{ resource.httpMethod }}</code>
                {{ pageWorkbenchResourceName(resource, resourceTypeLabel(type)) }}
              </strong>
              <small :title="resourceLocationText(resource)">
                {{ resourceLocationText(resource) }}
              </small>
            </span>
            <em :class="{ 'is-write': resource.accessMode === 'READ_WRITE' }">
              {{ resource.accessMode === 'READ_WRITE' ? '可写' : '只读' }}
            </em>
          </span>
        </div>
      </article>
    </div>
    <div v-else class="foundation-section-empty">
      <span><el-icon><CopyDocument /></el-icon></span>
      <div><strong>当前页面还没有同步实现资源</strong><p>完成页面扫描或手动登记后，资源会显示在这里。</p></div>
    </div>
  </section>
</template>
