<template>
  <div class="app-breadcrumb">
    <template v-if="canGoBack">
      <el-button link class="app-breadcrumb__back" :icon="ArrowLeft" @click="goBack">返回</el-button>
      <span class="app-breadcrumb__divider" aria-hidden="true" />
    </template>
    <el-breadcrumb :separator="separator">
      <el-breadcrumb-item v-if="showHome" :to="homeRoute">{{ homeLabel }}</el-breadcrumb-item>
      <el-breadcrumb-item
        v-for="(item, index) in breadcrumbItems"
        :key="`${item.title}-${index}`"
        :to="resolveCrumbTo(item)"
      >
        {{ item.title }}
      </el-breadcrumb-item>
    </el-breadcrumb>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { RouteLocationNormalizedLoaded, RouteLocationRaw } from 'vue-router'
import { ArrowLeft } from '@element-plus/icons-vue'

interface AppBreadcrumbItem {
  title: string
  to?: RouteLocationRaw | ((route: RouteLocationNormalizedLoaded) => RouteLocationRaw)
}

const props = withDefaults(
  defineProps<{
    items?: AppBreadcrumbItem[]
    homeLabel?: string
    homeTo?: RouteLocationRaw
    separator?: string
    showBack?: boolean
    showHome?: boolean
  }>(),
  {
    homeLabel: '首页',
    separator: '/',
    showBack: true,
    showHome: true,
  },
)

const route = useRoute()
const router = useRouter()
const homeRoute = computed<RouteLocationRaw>(() => props.homeTo ?? { path: '/' })

const breadcrumbItems = computed<AppBreadcrumbItem[]>(() => {
  if (props.items?.length) return props.items
  const configured = route.meta.breadcrumb as AppBreadcrumbItem[] | undefined
  if (configured?.length) return configured
  const title = route.meta.title as string | undefined
  return title ? [{ title }] : []
})

function resolveCrumbTo(item: AppBreadcrumbItem): RouteLocationRaw | undefined {
  return typeof item.to === 'function' ? item.to(route) : item.to
}

const parentCrumbTarget = computed<RouteLocationRaw | undefined>(() => {
  const crumbs = breadcrumbItems.value
  for (let i = crumbs.length - 2; i >= 0; i -= 1) {
    const target = resolveCrumbTo(crumbs[i])
    if (target) return target
  }
  return undefined
})

const canGoBack = computed(() => props.showBack && Boolean(parentCrumbTarget.value))

function goBack() {
  if (window.history.state?.back) {
    router.back()
    return
  }
  if (parentCrumbTarget.value) {
    router.push(parentCrumbTarget.value)
  }
}
</script>

<style scoped lang="scss">
.app-breadcrumb {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;

  :deep(.el-breadcrumb) {
    min-width: 0;
  }

  :deep(.el-breadcrumb__inner),
  :deep(.el-breadcrumb__separator) {
    color: #5e7290;
    font-size: 14px;
    font-weight: 500;
    line-height: 18px;
  }

  :deep(.el-breadcrumb__inner.is-link:hover) {
    color: var(--brand-primary);
  }

  :deep(.el-breadcrumb__item:last-child .el-breadcrumb__inner) {
    color: #1e293b;
  }
}

.app-breadcrumb__back {
  padding: 0;
  font-weight: 600;
  color: #64748b;

  &:hover {
    color: var(--brand-primary);
  }
}

.app-breadcrumb__divider {
  width: 1px;
  height: 16px;
  background: rgba(100, 116, 139, 0.24);
}

:global([data-theme='dark']) .app-breadcrumb {
  :deep(.el-breadcrumb__inner),
  :deep(.el-breadcrumb__separator) {
    color: #94a3b8;
  }

  :deep(.el-breadcrumb__item:last-child .el-breadcrumb__inner) {
    color: #e2e8f0;
  }
}

:global([data-theme='dark']) .app-breadcrumb__back {
  color: #94a3b8;

  &:hover {
    color: var(--brand-hover);
  }
}

:global([data-theme='dark']) .app-breadcrumb__divider {
  background: rgba(148, 163, 184, 0.28);
}
</style>
