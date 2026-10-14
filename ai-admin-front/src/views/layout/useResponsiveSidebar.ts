import { computed, onBeforeUnmount, ref, watch } from 'vue'

interface ResponsiveSidebarOptions {
  compactOnNarrow: () => boolean
  collapsedPreference: () => boolean
  togglePreference: () => void
}

/** Narrow-page interactions are temporary and never rewrite the desktop preference. */
export function useResponsiveSidebar(options: ResponsiveSidebarOptions) {
  const media = typeof window !== 'undefined' && typeof window.matchMedia === 'function'
    ? window.matchMedia('(max-width: 768px)') : null
  const narrow = ref(media?.matches ?? false)
  const compact = computed(() => options.compactOnNarrow() && narrow.value)
  const narrowCollapsed = ref(true)
  const isSidebarCollapsed = computed(() => compact.value ? narrowCollapsed.value : options.collapsedPreference())

  function updateViewport(event: MediaQueryListEvent) { narrow.value = event.matches }
  media?.addEventListener('change', updateViewport)
  onBeforeUnmount(() => media?.removeEventListener('change', updateViewport))
  watch(compact, active => { if (active) narrowCollapsed.value = true }, { flush: 'sync' })

  function toggleSidebar() {
    if (compact.value) narrowCollapsed.value = !narrowCollapsed.value
    else options.togglePreference()
  }

  return { isSidebarCollapsed, toggleSidebar }
}
