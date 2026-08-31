import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const agentEditSource = readFileSync(resolve(__dirname, './AgentEdit.vue'), 'utf8')
const overviewSource = readFileSync(resolve(__dirname, '../../components/agent/AgentDetailOverview.vue'), 'utf8')
const agentEditTemplate = agentEditSource.split('<script setup')[0]
const overviewTemplate = overviewSource.split('<style scoped')[0]

describe('Agent detail information hierarchy', () => {
  it('uses a view-first layout with overview, configuration and version sections', () => {
    expect(agentEditTemplate).toContain('AgentDetailOverview')
    expect(agentEditTemplate).toContain("activeSection === 'overview'")
    expect(agentEditTemplate).toContain("activeSection === 'config'")
    expect(agentEditTemplate).toContain("activeSection === 'versions'")
    expect(agentEditTemplate).toContain('编辑配置')
  })

  it('places dialogue and decision before callable workflows in the overview', () => {
    const decisionIndex = overviewTemplate.indexOf('title="对话与决策"')
    const workflowIndex = overviewTemplate.indexOf('title="可调用 Workflow"')

    expect(decisionIndex).toBeGreaterThan(-1)
    expect(workflowIndex).toBeGreaterThan(decisionIndex)
  })

  it('keeps every editable capability reachable from the configuration page', () => {
    expect(agentEditTemplate).toContain('id="agent-config-basic"')
    expect(agentEditTemplate).toContain('id="agent-config-decision"')
    expect(agentEditTemplate).toContain('id="agent-config-workflows"')
    expect(agentEditTemplate).toContain('id="agent-config-remote"')
    expect(agentEditTemplate).toContain('id="agent-config-skills"')
    expect(agentEditTemplate).toContain('高级运行设置')
    expect(agentEditTemplate).toContain('添加可调用 Workflow')
    expect(agentEditTemplate).toContain('添加 Agent')
    expect(agentEditTemplate).toContain('添加 Skill')
    expect(agentEditTemplate).toContain('复制为草稿')
  })

  it('shows one configuration area at a time and keeps technical fields behind progressive disclosure', () => {
    expect(agentEditTemplate).toContain('v-show="isNew || activeConfigSection === \'workflows\'"')
    expect(agentEditTemplate).toContain('v-show="isNew || activeConfigSection === \'remote\'"')
    expect(agentEditTemplate).toContain('v-show="isNew || activeConfigSection === \'skills\'"')
    expect(agentEditTemplate).toContain('<summary>调用设置</summary>')
    expect(agentEditTemplate).toContain('<summary>协作设置</summary>')
    expect(agentEditTemplate).not.toContain('scrollIntoView')
  })
})
