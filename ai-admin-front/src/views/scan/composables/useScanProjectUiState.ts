import { ref, watch } from 'vue'

export type ScanWorkbenchTab = 'tools' | 'modules' | 'apiGraph'

export function useScanProjectUiState() {
  const activeWorkbenchTab = ref<ScanWorkbenchTab>('modules')
  /** 接口图谱懒加载：首次切换到图谱 tab 时再 mount 画布实例 */
  const apiGraphMounted = ref(false)
  const interfaceCollapseActive = ref<string[]>([])

  watch(activeWorkbenchTab, (tab) => {
    if (!apiGraphMounted.value && tab === 'apiGraph') {
      apiGraphMounted.value = true
    }
  })

  return {
    activeWorkbenchTab,
    apiGraphMounted,
    interfaceCollapseActive,
  }
}
