import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import type { DocumentImportJob } from '@/types/import'
import DocumentImportJobCard from './DocumentImportJobCard.vue'

function job(partial: Partial<DocumentImportJob> = {}): DocumentImportJob {
  return {
    jobId: 'dij-card',
    fileId: 'file-card',
    knowledgeBaseCode: 'kb-card',
    fileName: '扫描合同.pdf',
    fileType: 'pdf',
    providerType: 'DOCLING',
    providerVersion: '1.30.0',
    status: 'PARSING',
    stage: 'PARSING',
    attemptCount: 1,
    maxAttempts: 3,
    autoCommit: false,
    ...partial,
  }
}

function mountCard(value: DocumentImportJob) {
  return mount(DocumentImportJobCard, {
    props: { job: value, dismissible: true },
    global: {
      stubs: {
        'el-card': { template: '<section><slot name="header"/><slot/></section>' },
        'el-tag': { template: '<span><slot/></span>' },
        'el-steps': { template: '<div><slot/></div>' },
        'el-step': { props: ['title'], template: '<span>{{ title }}</span>' },
        'el-alert': { props: ['title', 'description'], template: '<div>{{ title }} {{ description }}</div>' },
        'el-button': {
          emits: ['click'],
          template: '<button @click="$emit(\'click\')"><slot/></button>',
        },
      },
    },
  })
}

describe('DocumentImportJobCard', () => {
  it('shows the provider, version, stage and a cancel action while parsing', async () => {
    const wrapper = mountCard(job())
    expect(wrapper.text()).toContain('扫描合同.pdf')
    expect(wrapper.text()).toContain('Docling')
    expect(wrapper.text()).toContain('1.30.0')
    expect(wrapper.text()).toContain('取消任务')
    await wrapper.findAll('button').find((button) => button.text().includes('取消任务'))!.trigger('click')
    expect(wrapper.emitted('cancel')).toHaveLength(1)
  })

  it('keeps a failed task visible with error code, retry and dismiss actions', async () => {
    const wrapper = mountCard(job({
      status: 'FAILED',
      stage: 'PARSING',
      errorCode: 'DOCLING_TIMEOUT',
      errorMessage: 'Docling 解析超时',
    }))
    expect(wrapper.text()).toContain('DOCLING_TIMEOUT')
    expect(wrapper.text()).toContain('Docling 解析超时')
    expect(wrapper.text()).toContain('重试任务')
    expect(wrapper.text()).toContain('关闭记录')
    await wrapper.findAll('button').find((button) => button.text().includes('重试任务'))!.trigger('click')
    expect(wrapper.emitted('retry')).toHaveLength(1)
  })
})
