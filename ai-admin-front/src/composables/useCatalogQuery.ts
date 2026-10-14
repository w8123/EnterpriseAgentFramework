import { computed, inject, onBeforeUnmount, reactive, watch } from 'vue'
import { matchedRouteKey, onBeforeRouteLeave, useRoute, useRouter, type LocationQueryRaw } from 'vue-router'

/** Each catalog owns its URL fields; switching tabs never overwrites its peer's filters. */
export function useCatalogQuery<const Field extends string>(namespace: string, fields: readonly Field[]) {
  const route = useRoute()
  const router = useRouter()
  const ownerPath = route.path
  let disposed = false
  let leaving = false
  // A scope refresh or pagination event can finish while the next view is navigating.
  // Invalidate the old catalog before asynchronous global guards resolve.
  if (inject(matchedRouteKey, null)) {
    onBeforeRouteLeave(to => { leaving = to.path !== ownerPath })
  }
  const stopNavigationObserver = router.afterEach((_to, _from, failure) => {
    if (failure && route.path === ownerPath) leaving = false
  })
  onBeforeUnmount(() => { disposed = true; stopNavigationObserver() })
  const key = (field: string) => `${namespace}${field.charAt(0).toUpperCase()}${field.slice(1)}`
  const value = (field: string) => typeof route.query[key(field)] === 'string' ? String(route.query[key(field)]) : ''
  const positive = (field: string, fallback: number) => {
    const parsed = Number(value(field))
    return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : fallback
  }
  const page = computed(() => positive('page', 1))
  const size = computed(() => Math.min(100, positive('size', 20)))
  const filters = computed(() => Object.fromEntries(fields.map(field => [field, value(field)])) as Record<Field, string>)
  const draft = reactive(Object.fromEntries(fields.map(field => [field, '']))) as Record<Field, string>
  watch(filters, (next, previous) => {
    for (const field of fields) {
      if (!previous || next[field] !== previous[field]) draft[field] = next[field]
    }
  }, { immediate: true })
  const hasFilters = computed(() => fields.some(field => Boolean(filters.value[field])))
  const requestKey = computed(() => JSON.stringify([page.value, size.value, filters.value]))

  function replace(values: Record<string, string | number | undefined>, clearQueryKeys: readonly string[] = []) {
    if (disposed || leaving || route.path !== ownerPath) return Promise.resolve()
    const query: LocationQueryRaw = { ...route.query }
    for (const field of clearQueryKeys) delete query[field]
    for (const [field, next] of Object.entries(values)) query[key(field)] = next
    return router.replace({ path: ownerPath, query, hash: route.hash })
  }
  function apply() {
    return applyFields(fields)
  }
  function applyFields(selected: readonly Field[], clearQueryKeys: readonly string[] = []) {
    for (const field of selected) draft[field] = typeof draft[field] === 'string' ? draft[field].trim() : ''
    return replace({ ...Object.fromEntries(selected.map(field => [field, draft[field] || undefined])), page: 1 }, clearQueryKeys)
  }
  function applyField(field: Field, next = draft[field]) {
    draft[field] = next
    return applyFields([field])
  }
  function resetDraft() {
    Object.assign(draft, filters.value)
  }
  function clear(selected: readonly Field[], clearQueryKeys: readonly string[] = []) {
    for (const field of selected) draft[field] = ''
    return replace({ ...Object.fromEntries(selected.map(field => [field, undefined])), page: 1 }, clearQueryKeys)
  }
  return { page, size, filters, draft, hasFilters, requestKey, apply, applyField, applyFields, resetDraft, clear,
    changePage: (next: number) => replace({ page: next }),
    changeSize: (next: number) => replace({ size: next, page: 1 }) }
}
