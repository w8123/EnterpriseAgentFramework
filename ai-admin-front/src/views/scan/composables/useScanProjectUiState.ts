import { ref, watch } from 'vue'

export type ScanWorkbenchTab = 'tools' | 'modules' | 'apiGraph'

export function useScanProjectUiState() {
  const activeWorkbenchTab = ref<ScanWorkbenchTab>('tools')
  /** 扫描详情各区块折叠；空数组=全部折叠 */
  const detailPanelActive = ref<string[]>(['tools', 'modules', 'apiGraph'])
  /** 接口图谱懒加载：首次展开折叠卡时再 mount 画布实例 */
  const apiGraphMounted = ref(false)
  const interfaceCollapseActive = ref<string[]>([])

  watch(detailPanelActive, (panels) => {
    if (!apiGraphMounted.value && panels.includes('apiGraph')) {
      apiGraphMounted.value = true
    }
  })

  watch(activeWorkbenchTab, (tab) => {
    if (!apiGraphMounted.value && tab === 'apiGraph') {
      apiGraphMounted.value = true
    }
  })

  return {
    activeWorkbenchTab,
    detailPanelActive,
    apiGraphMounted,
    interfaceCollapseActive,
  }
}
