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

  it('teleports overview-launched overlays outside the hidden configuration form', () => {
    const configFormStart = agentEditTemplate.indexOf('<el-form\n      v-show="isNew || activeSection === \'config\'"')
    const configFormEnd = agentEditTemplate.lastIndexOf('</el-form>')
    const configFormTemplate = agentEditTemplate.slice(configFormStart, configFormEnd)
    const overlays = [...configFormTemplate.matchAll(/<App(?:Dialog|Drawer)\b[\s\S]*?>/g)]
      .map((match) => match[0])

    expect(configFormStart).toBeGreaterThan(-1)
    expect(configFormEnd).toBeGreaterThan(configFormStart)
    expect(overlays).toHaveLength(5)
    overlays.forEach((overlay) => expect(overlay).toContain('append-to-body'))
    expect(agentEditTemplate).toContain('@add-remote="openA2aBindingDialog"')
  })

  it('keeps the collaboration dialog task-focused across setup and ready states', () => {
    const dialogStart = agentEditTemplate.indexOf('v-model="a2aBindingDialogVisible"')
    const dialogEnd = agentEditTemplate.indexOf('</AppDialog>', dialogStart)
    const dialogTemplate = agentEditTemplate.slice(dialogStart, dialogEnd)

    expect(dialogTemplate).toContain('title="添加协作 Agent"')
    expect(dialogTemplate).toContain('把任务交给可信的外部 Agent')
    expect(dialogTemplate).toContain('v-if="!localAgentPrincipals.length"')
    expect(dialogTemplate).toContain('v-else-if="!trustedRemoteAgents.length"')
    expect(dialogTemplate).toContain('为当前 Agent 创建协作身份')
    expect(dialogTemplate).toContain('打开信任与策略')
    expect(dialogTemplate).toContain('打开远程 Agent 目录')
    expect(dialogTemplate).toContain('<details class="a2a-dialog-settings">')
    expect(dialogTemplate).toContain('添加到当前 Agent')
  })
})
