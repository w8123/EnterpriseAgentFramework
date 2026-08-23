import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ChunkPreview from './ChunkPreview.vue'

describe('ChunkPreview structured provenance', () => {
  it('renders element, section and page anchors returned by Docling', () => {
    const wrapper = mount(ChunkPreview, {
      props: {
        totalChunks: 1,
        loading: false,
        chunks: [{
          index: 0,
          content: '合同正文',
          length: 4,
          elementType: 'PARAGRAPH',
          sectionPath: '付款条款',
          sourceLocatorJson: JSON.stringify({ pageStart: 3, pageEnd: 3 }),
        }],
      },
      global: {
        stubs: {
          'el-tag': { template: '<span><slot/></span>' },
          'el-empty': true,
          'el-skeleton': true,
        },
      },
    })
    expect(wrapper.text()).toContain('段落')
    expect(wrapper.text()).toContain('付款条款')
    expect(wrapper.text()).toContain('第 3 页')
    expect(wrapper.text()).toContain('合同正文')
  })
})
