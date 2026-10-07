import { computed, reactive, watch } from 'vue'
import { useRoute, useRouter, type LocationQueryRaw } from 'vue-router'

/** Each catalog owns its URL fields; switching tabs never overwrites its peer's filters. */
export function useCatalogQuery<const Field extends string>(namespace: string, fields: readonly Field[]) {
  const route = useRoute()
  const router = useRouter()
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
  watch(filters, next => Object.assign(draft, next), { immediate: true })
  const hasFilters = computed(() => fields.some(field => Boolean(filters.value[field])))
  const requestKey = computed(() => JSON.stringify([page.value, size.value, filters.value]))

  function replace(values: Record<string, string | number | undefined>) {
    const query: LocationQueryRaw = { ...route.query }
    for (const [field, next] of Object.entries(values)) query[key(field)] = next
    return router.replace({ query })
  }
  function apply() {
    return replace({ ...Object.fromEntries(fields.map(field => [field, draft[field].trim() || undefined])), page: 1 })
  }
  function reset() {
    Object.assign(draft, Object.fromEntries(fields.map(field => [field, ''])))
    return replace({ ...Object.fromEntries(fields.map(field => [field, undefined])), page: 1 })
  }
  return { page, size, filters, draft, hasFilters, requestKey, apply, reset,
    changePage: (next: number) => replace({ page: next }),
    changeSize: (next: number) => replace({ size: next, page: 1 }) }
}
