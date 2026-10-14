import { describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import VariableAssignConfigPanel from './VariableAssignConfigPanel.vue'

describe('VariableAssignConfigPanel variable choices', () => {
  it('keeps human-readable labels while assigning the canonical expression value', async () => {
    const data = {
      label: '接收返回值', kind: 'variable' as const, configVersion: 2 as const,
      assignments: { scalar_result: 'lastOutput' },
    }
    const wrapper = mount(VariableAssignConfigPanel, {
      props: {
        data,
        variableOptions: [
          'lastOutput',
          { value: 'nodeOutput.scalar', label: '标量只读方法 · 标量返回值', group: '节点输出' },
        ],
      },
      global: { plugins: [ElementPlus] },
    })
    const selects = wrapper.findAllComponents({ name: 'ElSelect' })
    expect(selects[1]!.props('fitInputWidth')).toBe(true)
    const options = selects[1]!.findAllComponents({ name: 'ElOption' })
    expect(selects[1]!.props('popperClass')).toBe('workflow-variable-assign-options')
    expect(options.map((option) => option.props('label'))).toContain('标量只读方法 · 标量返回值')
    expect(options.map((option) => option.props('label'))).toContain('lastOutput')
    selects[1]!.vm.$emit('update:modelValue', 'nodeOutput.scalar')
    selects[1]!.vm.$emit('change', 'nodeOutput.scalar')
    await flushPromises()
    expect(data.assignments.scalar_result).toBe('nodeOutput.scalar')
    wrapper.unmount()
  })
})
