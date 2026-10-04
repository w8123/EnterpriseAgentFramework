import { afterEach, describe, expect, it } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import WorkflowStudioPublishDialog from './WorkflowStudioPublishDialog.vue'
import type { WorkflowReleaseValidationItem } from '@/types/workflow'

describe('WorkflowStudioPublishDialog release evidence', () => {
  let wrapper: VueWrapper | undefined
  afterEach(() => wrapper?.unmount())

  const mountDialog = (errors: WorkflowReleaseValidationItem[] = []) => {
    wrapper = mount(WorkflowStudioPublishDialog, {
      props: {
        open: true, publishing: false, releaseChecking: false, releaseValidationReady: true,
        releaseErrors: errors,
        releaseWarnings: [{ code: 'GRAPH_ANSWER_PATH_MISSING', level: 'WARN',
          nodeId: 'userInput_1790961255263',
          message: 'No reachable ANSWER node; workflow may finish without a canonical user answer' }],
        publishWarnings: ['本次为全量发布，会替换该 Workflow 的历史 ACTIVE 全量版本。'],
        form: { version: 'v1.0.0', note: '保留完整发布证据' },
        beforeClose: (done: () => void) => done(),
        releaseValidationKey: item => `${item.code}:${item.nodeId ?? ''}`,
      },
      global: { plugins: [ElementPlus], stubs: { AppDialog: {
        props: ['modelValue', 'title'],
        template: '<section v-if="modelValue" role="dialog" :aria-label="title"><slot /><slot name="footer" /></section>',
      } } },
    })
    return wrapper
  }

  it('keeps code, node and message separate for responsive wrapping without dropping evidence', () => {
    const dialog = mountDialog([{ code: 'HTTP_API_SOURCE_CHANGED', level: 'ERROR',
      nodeId: 'tool_1790958728276', message: '来源契约已变化，请回到 API 详情处理后重新校验。' }])
    const rows = dialog.findAll('.check-item')
    expect(rows).toHaveLength(2)
    expect(rows.map(row => row.find('.check-code').text())).toEqual(['HTTP_API_SOURCE_CHANGED', 'GRAPH_ANSWER_PATH_MISSING'])
    expect(rows.map(row => row.find('.check-node').text())).toEqual(['tool_1790958728276', 'userInput_1790961255263'])
    expect(rows.map(row => row.find('.check-message').text())).toEqual([
      '来源契约已变化，请回到 API 详情处理后重新校验。',
      'No reachable ANSWER node; workflow may finish without a canonical user answer',
    ])
    expect(dialog.text()).toContain('1 个阻断项 / 1 个提醒项')
  })

  it('preserves the existing error, checking and pending publication gates', async () => {
    const dialog = mountDialog([{ code: 'HTTP_API_SOURCE_CHANGED', level: 'ERROR', message: '来源变化' }])
    const publish = () => dialog.findAll('button').find(button => button.text() === '确认发布')!
    expect(publish().attributes('disabled')).toBeDefined()
    await dialog.setProps({ releaseErrors: [], releaseChecking: true })
    expect(publish().attributes('disabled')).toBeDefined()
    await dialog.setProps({ releaseChecking: false })
    expect(publish().attributes('disabled')).toBeUndefined()
    await publish().trigger('click')
    expect(dialog.emitted('publish')).toHaveLength(1)
    await dialog.setProps({ publishing: true })
    expect(publish().attributes('disabled')).toBeDefined()
    expect(dialog.findAll('button').find(button => button.text() === '取消')!.attributes('disabled')).toBeDefined()
  })
})
