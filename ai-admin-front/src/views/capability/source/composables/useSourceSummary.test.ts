import { ref } from 'vue'
import { describe, expect, it } from 'vitest'
import type { ProjectToolInfo, ScanProject } from '@/types/scanProject'
import { useSourceSummary } from './useSourceSummary'

describe('project source guidance', () => {
  it('uses whole-project statistics when the filtered page is empty', () => {
    const state = useSourceSummary({ project: ref({ id: 7, projectKind: 'REGISTERED' } as ScanProject),
      tools: ref([]), statistics: ref({ total: 500, removed: 40 }) })
    expect(state.stageAdvice.value.primaryAction).toBe('viewOwnerCatalog')
    expect(state.stageAdvice.value.description).toContain('40 个来源已移除')
  })
  it('uses the loaded source evidence instead of stale project counts for the empty-state next step', () => {
    const project = ref({ id: 7, projectKind: 'REGISTERED', apiCount: 24, toolCount: 24 } as ScanProject)
    const tools = ref<ProjectToolInfo[]>([])
    const state = useSourceSummary({ project, tools })
    expect(state.stageAdvice.value.primaryAction).toBe('importApi')
    tools.value = [{ scanToolId: 18 } as ProjectToolInfo]
    expect(state.stageAdvice.value.primaryAction).toBe('viewOwnerCatalog')
  })
})
