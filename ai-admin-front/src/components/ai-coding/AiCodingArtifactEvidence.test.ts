import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import AiCodingArtifactEvidence from './AiCodingArtifactEvidence.vue'

describe('AiCodingArtifactEvidence', () => {
  it('renders the business entry and browser acceptance material', () => {
    const wrapper = mount(AiCodingArtifactEvidence, {
      props: {
        applicationResult: {
          codingReport: {
            summary: 'ReachAI 接入完成',
            steps: [{
              files: ['frontend/src/reachai-entry.ts'],
            }],
            tests: [{
              name: 'frontend build',
              status: 'PASS',
              command: 'npm run build',
              evidence: 'Build succeeded',
            }],
            browserVerification: {
              passed: true,
              browser: 'Chromium',
              url: 'http://localhost:4200/orders',
              scenarios: ['订单页面显示 ReachAI 入口'],
              screenshots: ['output/reachai-entry.png'],
              evidence: '入口和助手回复均可见',
            },
          },
        },
      },
      global: {
        stubs: {
          'el-tag': {
            template: '<span><slot /></span>',
          },
        },
      },
    })

    expect(wrapper.text()).toContain('ReachAI 接入完成')
    expect(wrapper.text()).toContain('frontend/src/reachai-entry.ts')
    expect(wrapper.text()).toContain('http://localhost:4200/orders')
    expect(wrapper.text()).toContain('订单页面显示 ReachAI 入口')
    expect(wrapper.text()).toContain('output/reachai-entry.png')
    expect(wrapper.text()).toContain('AI 报告通过')
  })

  it('states that a missing browser report is not completion evidence', () => {
    const wrapper = mount(AiCodingArtifactEvidence, {
      props: {
        applicationResult: {
          implementation: {
            summary: 'Only code was changed',
            changedFiles: ['src/page.ts'],
            tests: [],
            browserVerification: null,
          },
        },
      },
      global: {
        stubs: {
          'el-tag': {
            template: '<span><slot /></span>',
          },
        },
      },
    })

    expect(wrapper.text()).toContain(
      '不能据此判断页面入口或交互已完成',
    )
  })
})
