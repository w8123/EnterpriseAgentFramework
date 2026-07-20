import { describe, expect, it } from 'vitest'
import { buildAssignmentsFromRows } from '@/views/workflow/studio-panels/variableAssignValue'
import { useWorkflowStudioPanelValidation } from './useWorkflowStudioPanelValidation'

describe('useWorkflowStudioPanelValidation', () => {
  it('blocks save gate when VARIABLE_ASSIGN JSON is invalid and clears after fix', () => {
    const {
      setPanelValidation,
      hasPanelValidationErrors,
      firstPanelValidationMessage,
      ensurePanelValidationClear,
    } = useWorkflowStudioPanelValidation()

    const invalid = buildAssignmentsFromRows([
      {
        target: 'var.order',
        valueKind: 'json',
        textValue: '',
        numberValue: undefined,
        boolValue: false,
        jsonText: '{bad',
      },
    ])
    expect(invalid.ok).toBe(false)
    if (!invalid.ok) {
      setPanelValidation('assign_1', { valid: false, message: `第 ${invalid.index + 1} 项：${invalid.error}` })
    }

    expect(hasPanelValidationErrors.value).toBe(true)
    expect(firstPanelValidationMessage()).toContain('assign_1')
    expect(firstPanelValidationMessage()).toContain('JSON')

    expect(ensurePanelValidationClear()).toBe(false)

    const valid = buildAssignmentsFromRows([
      {
        target: 'var.order',
        valueKind: 'json',
        textValue: '',
        numberValue: undefined,
        boolValue: false,
        jsonText: '{"a":1}',
      },
    ])
    expect(valid.ok).toBe(true)
    setPanelValidation('assign_1', { valid: true })
    expect(hasPanelValidationErrors.value).toBe(false)
    expect(ensurePanelValidationClear()).toBe(true)
  })

  it('prunes a deleted illegal node and clears a switched workflow document', () => {
    const {
      setPanelValidation,
      clearPanelValidation,
      prunePanelValidation,
      ensurePanelValidationClear,
    } = useWorkflowStudioPanelValidation()

    setPanelValidation('assign_1', { valid: false, message: 'JSON 无效' })
    setPanelValidation('assign_2', { valid: false, message: 'JSON 无效' })
    prunePanelValidation(['assign_2'])
    expect(ensurePanelValidationClear()).toBe(false)

    prunePanelValidation([])
    expect(ensurePanelValidationClear()).toBe(true)

    setPanelValidation('assign_next_workflow', { valid: false, message: 'JSON 无效' })
    clearPanelValidation()
    expect(ensurePanelValidationClear()).toBe(true)
  })
})
