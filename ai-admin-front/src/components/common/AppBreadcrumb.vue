<template>
  <div class="app-breadcrumb">
    <el-breadcrumb :separator="separator">
      <el-breadcrumb-item
        v-for="(item, index) in breadcrumbItems"
        :key="`${resolveCrumbTitle(item)}-${index}`"
        :to="resolveCrumbTo(item)"
      >
        {{ resolveCrumbTitle(item) }}
      </el-breadcrumb-item>
    </el-breadcrumb>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { RouteLocationNormalizedLoaded, RouteLocationRaw } from 'vue-router'

interface AppBreadcrumbItem {
  title: string | ((route: RouteLocationNormalizedLoaded) => string)
  to?: RouteLocationRaw | ((route: RouteLocationNormalizedLoaded) => RouteLocationRaw)
}

const props = withDefaults(
  defineProps<{
    items?: AppBreadcrumbItem[]
    homeLabel?: string
    homeTo?: RouteLocationRaw
    separator?: string
    showHome?: boolean
  }>(),
  {
    homeLabel: '首页',
    separator: '/',
    showHome: true,
  },
)

const route = useRoute()
const router = useRouter()

function findAutoParentTarget(): RouteLocationRaw | undefined {
  const segments = route.path.split('/').filter(Boolean)
  for (let length = segments.length - 1; length > 0; length -= 1) {
    const candidate = `/${segments.slice(0, length).join('/')}`
    const resolved = router.resolve({ path: candidate })
    if (resolved.matched.length > 1 && resolved.name !== route.name) {
      return { path: candidate }
    }
  }
  return undefined
}

const autoParentCrumbTarget = computed(() => findAutoParentTarget())

function resolveCrumbTitle(item: AppBreadcrumbItem): string {
  return typeof item.title === 'function' ? item.title(route) : item.title
}

const breadcrumbItems = computed<AppBreadcrumbItem[]>(() => {
  if (props.items?.length) return props.items
  const configured = route.meta.breadcrumb as AppBreadcrumbItem[] | undefined
  if (configured?.length) return configured
  const title = route.meta.title as string | undefined
  if (!title) return []

  const parentTarget = autoParentCrumbTarget.value
  if (!parentTarget) return [{ title }]

  const parentRoute = router.resolve(parentTarget)
  const parentTitle = parentRoute.meta.title as string | undefined
  return parentTitle ? [{ title: parentTitle, to: parentTarget }, { title }] : [{ title }]
})

function resolveCrumbTo(item: AppBreadcrumbItem): RouteLocationRaw | undefined {
  return typeof item.to === 'function' ? item.to(route) : item.to
}

</script>

<style scoped lang="scss">
.app-breadcrumb {
  display: flex;
  align-items: center;
  min-width: 0;
  text-shadow: var(--text-on-wallpaper-shadow);

  :deep(.el-breadcrumb) {
    display: flex;
    flex-wrap: wrap;
    row-gap: var(--layout-control-gap);
    min-width: 0;
    font: inherit;
  }

  :deep(.el-breadcrumb__item) {
    min-width: 0;
    max-width: 100%;
    overflow-wrap: anywhere;
  }

  :deep(.el-breadcrumb__inner),
  :deep(.el-breadcrumb__inner.is-link),
  :deep(.el-breadcrumb__inner a),
  :deep(.el-breadcrumb__separator) {
    color: var(--text-on-wallpaper-secondary);
    font-family: inherit;
    font-size: var(--layout-breadcrumb-font-size);
    font-weight: var(--layout-breadcrumb-font-weight);
    line-height: var(--layout-breadcrumb-line-height);
    text-decoration: none;
  }

  :deep(.el-breadcrumb__separator) {
    margin-inline: var(--layout-breadcrumb-separator-gap);
  }

  :deep(.el-breadcrumb__inner.is-link:hover) {
    color: var(--text-on-wallpaper);
  }

  :deep(.el-breadcrumb__item:last-child .el-breadcrumb__inner) {
    color: var(--text-on-wallpaper);
  }
}

</style>
