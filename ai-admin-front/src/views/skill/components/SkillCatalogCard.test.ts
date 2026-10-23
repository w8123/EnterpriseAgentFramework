import { afterEach, describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import SkillCatalogCard from './SkillCatalogCard.vue'
import type { AgentSkillSummary } from '@/types/skill'

const skill: AgentSkillSummary = { id: 1, publisher: 'community', name: 'writing-guidelines', displayName: 'Writing guidelines',
  description: 'Review docs and prose.', visibility: 'PROJECT', projectCode: 'bzjs21', status: 'ACTIVE',
  latestVersionStatus: 'REVIEW_PENDING', latestVersion: '1.2.0', defaultVersion: null, versionCount: 3 }
let wrapper: ReturnType<typeof mount> | undefined
afterEach(() => { wrapper?.unmount(); wrapper = undefined })

describe('Skill catalog card', () => {
  it('keeps default version, latest status and publisher trust distinct', () => {
    wrapper = mount(SkillCatalogCard, { props: { skill }, global: { plugins: [ElementPlus] } })
    expect(wrapper.get('.skill-card__versions').text()).toContain('默认未设置')
    expect(wrapper.get('.skill-card__versions').text()).toContain('最新1.2.0')
    expect(wrapper.get('.skill-card__context').text()).toContain('待评审')
    expect(wrapper.text()).toContain('发布者未验证')
    expect(wrapper.text()).toContain('项目 · bzjs21')
    expect(wrapper.text()).not.toContain('可绑定')
  })

  it('opens once from its native title button or card surface', async () => {
    wrapper = mount(SkillCatalogCard, { props: { skill }, global: { plugins: [ElementPlus] } })
    const button = wrapper.get('button')
    expect(button.attributes('type')).toBe('button')
    expect(button.attributes('aria-label')).toBe('查看 Writing guidelines 详情')
    await button.trigger('click')
    expect(wrapper.emitted('open')).toEqual([[skill]])
    await wrapper.get('article').trigger('click')
    expect(wrapper.emitted('open')).toEqual([[skill], [skill]])
  })
})
