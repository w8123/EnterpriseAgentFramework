import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(resolve(__dirname, './AgentEdit.vue'), 'utf8')
const routerSource = readFileSync(resolve(__dirname, '../../router/index.ts'), 'utf8')
const template = source.split('<script setup')[0]

describe('AgentEdit user-facing copy', () => {
  it('uses task-oriented names for the primary configuration areas', () => {
    expect(template).toContain('eyebrow="Agent 配置"')
    expect(template).toContain('title="对话与决策"')
    expect(template).toContain('title="可调用 Workflow"')
    expect(template).toContain('title="Agent 协作"')
    expect(template).toContain('title="Skill"')
    expect(source).toContain('已填写工作要求（${text.length} 字），点击查看或修改')
  })

  it('keeps internal architecture wording out of primary instructions', () => {
    expect(template).not.toContain('发布 Supervisor 配置')
    expect(template).not.toContain('Workflow 白名单')
    expect(template).not.toContain('title="A2A 远程 Agent"')
    expect(template).not.toContain('ACTIVE LOCAL_AGENT Principal')
    expect(template).not.toContain('Agent Skills / 岗位工作手册')
    expect(template).not.toContain('远程协作')
    expect(template).not.toContain('岗位工作指南')
    expect(template).not.toContain('allowed-tools')
  })

  it('explains editable technical fields in plain language', () => {
    expect(template).toContain('label="唯一标识"')
    expect(template).toContain('label="调用标识"')
    expect(template).toContain('label="操作类型"')
    expect(template).toContain('label="所需权限"')
    expect(template).toContain('placeholder="通常无需修改"')
    expect(template).toContain('placeholder="请选择协作身份"')
    expect(template).toContain('label="最长等待（秒）"')
    expect(template).not.toContain('label="最长等待（毫秒）"')
  })

  it('uses an explicit list breadcrumb instead of resolving a dynamic non-route parent', () => {
    const agentEditRoute = routerSource.match(
      /path: 'agent\/:id\/edit',[\s\S]*?(?=path: 'agent\/:id\/debug')/,
    )?.[0]

    expect(agentEditRoute).toBeTruthy()
    expect(agentEditRoute).toContain("{ title: '智能体与编排', to: { path: '/agent' } }")
    expect(agentEditRoute).toContain("{ title: 'Agent 编辑' }")
  })
})
