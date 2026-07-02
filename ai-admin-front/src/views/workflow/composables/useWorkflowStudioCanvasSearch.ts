import type { ComputedRef, Ref } from 'vue'
import { computed } from 'vue'
import type { CanvasNode } from '@/types/studio'

type SetCenterFn = (x: number, y: number, opts: { zoom: number; duration: number }) => boolean | void | Promise<boolean | void>

export interface UseWorkflowStudioCanvasSearchDeps {
  canvasSearchOpen: Ref<boolean>
  canvasSearchKeyword: Ref<string>
  canvasSearchIndex: Ref<number>
  canvasSearchInputRef: Ref<{ focus?: () => void } | undefined>
  nodes: Ref<CanvasNode[]>
  selectedNodeId: Ref<string | null>
  selectedEdgeId: Ref<string | null>
  setCenter: SetCenterFn
}

export interface UseWorkflowStudioCanvasSearchResult {
  canvasSearchMatches: ComputedRef<CanvasNode[]>
  openCanvasSearch: () => void
  closeCanvasSearch: () => void
  focusCanvasSearchMatch: (index?: number) => void
  focusNextCanvasSearch: () => void
  focusPrevCanvasSearch: () => void
}

export function useWorkflowStudioCanvasSearch(
  deps: UseWorkflowStudioCanvasSearchDeps,
  nextTick: (cb: () => void) => void,
): UseWorkflowStudioCanvasSearchResult {
  const {
    canvasSearchOpen,
    canvasSearchKeyword,
    canvasSearchIndex,
    canvasSearchInputRef,
    nodes,
    selectedNodeId,
    selectedEdgeId,
    setCenter,
  } = deps

  const canvasSearchMatches = computed(() => {
    const keyword = canvasSearchKeyword.value.trim().toLowerCase()
    if (!keyword) return []
    return nodes.value.filter((node) => {
      const data = node.data
      const haystack = [
        node.id,
        data.label,
        data.kind,
        data.description,
        data.outputAlias,
        data.toolConfig?.ref,
        data.toolConfig?.qualifiedName,
        data.httpConfig?.url,
        data.knowledgeConfig?.knowledgeBaseCodes?.join(' '),
        data.pageActionConfig?.pageKey,
        data.pageActionConfig?.actionKey,
      ]
        .filter(Boolean)
        .join(' ')
        .toLowerCase()
      return haystack.includes(keyword)
    })
  })

  function openCanvasSearch() {
    canvasSearchOpen.value = true
    nextTick(() => {
      canvasSearchInputRef.value?.focus?.()
    })
  }

  function closeCanvasSearch() {
    canvasSearchOpen.value = false
    canvasSearchKeyword.value = ''
    canvasSearchIndex.value = 0
  }

  function focusCanvasSearchMatch(index = canvasSearchIndex.value) {
    const matches = canvasSearchMatches.value
    if (!matches.length) return
    const normalized = ((index % matches.length) + matches.length) % matches.length
    canvasSearchIndex.value = normalized
    const node = matches[normalized]
    selectedNodeId.value = node.id
    selectedEdgeId.value = null
    setCenter(node.position.x + 110, node.position.y + 70, { zoom: 1, duration: 260 })
  }

  function focusNextCanvasSearch() {
    focusCanvasSearchMatch(canvasSearchIndex.value + 1)
  }

  function focusPrevCanvasSearch() {
    focusCanvasSearchMatch(canvasSearchIndex.value - 1)
  }

  return {
    canvasSearchMatches,
    openCanvasSearch,
    closeCanvasSearch,
    focusCanvasSearchMatch,
    focusNextCanvasSearch,
    focusPrevCanvasSearch,
  }
}
