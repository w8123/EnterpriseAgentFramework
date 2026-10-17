import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(resolve(__dirname, './AgentEdit.vue'), 'utf8')
const [template, script] = source.split('<script setup')

describe('AgentEdit Workflow version freshness', () => {
  it('tells the user the published config still runs an older Workflow version', () => {
    expect(template).toContain('v-if="!isNew && staleWorkflowTools.length"')
    expect(template).toContain('个 Workflow 有新发布版本，线上配置')
    expect(template).toContain('需要重新发布 Agent 才会用到新版本')
    expect(template).toContain('当前 {{ formatWorkflowVersionLabel(tool.pinnedWorkflowVersion) }} · 最新 {{ formatWorkflowVersionLabel(tool.latestWorkflowVersion) }}')
  })

  it('marks each stale Workflow card with its current and latest version', () => {
    expect(template).toContain('v-if="newerWorkflowVersion(row)" class="is-stale"')
    expect(template).toContain('当前 {{ formatWorkflowVersionLabel(row.workflowVersion) }} · 最新')
  })

  it('offers the one-click update only to publishers and guards it with the seen ACTIVE config', () => {
    expect(template).toMatch(/v-if="canPublishAgent"[\s\S]*?@click="handlePublishLatestWorkflows"[\s\S]*?更新并发布/)
    expect(script).toContain('const expected = workflowFreshness.value?.activeConfigVersionId')
    expect(script).toContain("publishLatestAgentWorkflowVersions(agentId, expected, 'admin-ui', { errorFeedback: 'local' })")
    expect(script).toContain('ElMessageBox.confirm(')
    expect(script).toContain('未保存或未发布的草稿修改不会包含在内')
  })

  it('reloads freshness after loading and after any publish', () => {
    expect(script).toMatch(/async function loadAgentConfig\(\) \{[\s\S]*?void refreshWorkflowFreshness\(\)/)
    expect(script).toMatch(/publishAgentConfig\(agentId, draft\.id, 'admin-ui'\)[\s\S]*?void refreshWorkflowFreshness\(\)/)
  })
})
