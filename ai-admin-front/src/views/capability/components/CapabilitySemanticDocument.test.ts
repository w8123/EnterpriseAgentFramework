import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import CapabilitySemanticDocument from './CapabilitySemanticDocument.vue'

describe('shared semantic document reader', () => {
  it('keeps source prose and headings while stripping executable HTML and unsafe URLs', () => {
    const contentMd = '# 业务说明\n\n辅助说明不授予调用权限。<script>execute()</script>\n\n[不可执行](javascript:execute())'
    const view = mount(CapabilitySemanticDocument, { props: { contentMd } })
    expect(view.get('h1').text()).toBe('业务说明')
    expect(view.text()).toContain('辅助说明不授予调用权限')
    expect(view.find('script').exists()).toBe(false)
    expect(view.get('a').attributes('href')).toBeUndefined()
    expect(view.props('contentMd')).toBe(contentMd)
    view.unmount()
  })

  it('makes long code and tables keyboard-focusable without truncating their contents', () => {
    const contentMd = '```text\n' + '完整代码'.repeat(30) + '\n```\n\n|字段|说明|\n|---|---|\n|字段A|完整字段说明|'
    const view = mount(CapabilitySemanticDocument, { props: { contentMd } })
    expect(view.get('pre').attributes('tabindex')).toBe('0')
    expect(view.get('pre').attributes('aria-label')).toBe('代码，可横向滚动')
    expect(view.get('pre code').text()).toContain('完整代码'.repeat(30))
    expect(view.get('table').attributes('tabindex')).toBe('0')
    expect(view.get('table').attributes('aria-label')).toBe('说明表格，可横向滚动')
    expect(view.get('table').text()).toContain('完整字段说明')
    view.unmount()
  })

  it('reads refreshed content and removes a prior document when it becomes empty', async () => {
    const view = mount(CapabilitySemanticDocument, { props: { contentMd: '原来正文' } })
    await view.setProps({ contentMd: '当前正文' })
    expect(view.text()).toBe('当前正文')
    expect(view.text()).not.toContain('原来正文')
    await view.setProps({ contentMd: null })
    expect(view.text()).toBe('')
    view.unmount()
  })
})
