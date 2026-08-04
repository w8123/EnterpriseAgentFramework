import { describe, expect, it } from 'vitest'
import { aiCodingArtifactEvidence } from './aiCodingArtifactEvidence'

describe('aiCodingArtifactEvidence', () => {
  it('normalizes onboarding material from the provider wrapper', () => {
    const material = aiCodingArtifactEvidence({
      codingReport: {
        summary: '接入完成',
        steps: [
          { files: ['pom.xml', 'application.yml'] },
          { files: ['pom.xml', 'frontend/package.json'] },
        ],
        tests: [
          {
            name: 'frontend build',
            status: 'PASS',
            command: 'npm run build',
            evidence: 'Build succeeded',
          },
        ],
        browserVerification: {
          passed: true,
          browser: 'Chromium',
          url: 'http://localhost:4200/orders',
          scenarios: ['Open the business page'],
          screenshots: ['output/onboarding.png'],
          evidence: 'ReachAI entry and response are visible',
        },
      },
      platformCheck: {
        overallStatus: 'WARN',
      },
    })

    expect(material).toEqual({
      summary: '接入完成',
      files: ['pom.xml', 'application.yml', 'frontend/package.json'],
      checks: [{
        name: 'frontend build',
        status: 'PASS',
        command: 'npm run build',
        evidence: 'Build succeeded',
      }],
      browser: {
        passed: true,
        browser: 'Chromium',
        url: 'http://localhost:4200/orders',
        scenarios: ['Open the business page'],
        screenshots: ['output/onboarding.png'],
        evidence: 'ReachAI entry and response are visible',
      },
    })
  })

  it('keeps an honest missing-browser state for review', () => {
    expect(aiCodingArtifactEvidence({
      implementation: {
        summary: '代码已修改，但未运行浏览器',
        changedFiles: ['src/page.ts'],
        tests: [],
        browserVerification: null,
      },
    })).toEqual({
      summary: '代码已修改，但未运行浏览器',
      files: ['src/page.ts'],
      checks: [],
      browser: null,
    })
  })

  it('ignores non-delivery application results', () => {
    expect(aiCodingArtifactEvidence({
      pageKey: 'orders',
      appliedFindings: 2,
    })).toBeNull()
  })
})
