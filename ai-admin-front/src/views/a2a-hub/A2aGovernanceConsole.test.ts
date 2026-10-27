import { computed, defineComponent, h, inject, provide, type ComputedRef } from 'vue'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import A2aTrustWorkspace from './trust/A2aTrustWorkspace.vue'
import A2aDeveloperConsole from './developer/A2aDeveloperConsole.vue'
import type { A2aCredential, A2aPrincipal, A2aPublicationDetail, A2aTrustProfile } from '@/types/a2aHub'

const mocks = vi.hoisted(() => ({
  profiles: vi.fn(), principals: vi.fn(), credentials: vi.fn(), agents: vi.fn(), publications: vi.fn(), publication: vi.fn(),
  profileStatus: vi.fn(), principalStatus: vi.fn(), createKey: vi.fn(), createPrincipal: vi.fn(), createProfile: vi.fn(),
  revoke: vi.fn(), rotate: vi.fn(), rotateOutbound: vi.fn(), storeOutbound: vi.fn(), updateProfile: vi.fn(),
  confirm: vi.fn(), prompt: vi.fn(), copy: vi.fn(), permissions: ['*'],
}))
vi.mock('@/api/a2aHub', () => ({
  listA2aTrustProfiles: mocks.profiles, listA2aPrincipals: mocks.principals, listA2aCredentials: mocks.credentials,
  changeA2aTrustProfileStatus: mocks.profileStatus, changeA2aPrincipalStatus: mocks.principalStatus,
  createA2aApiKey: mocks.createKey, createA2aPrincipal: mocks.createPrincipal, createA2aTrustProfile: mocks.createProfile,
  revokeA2aCredential: mocks.revoke, rotateA2aCredential: mocks.rotate, rotateA2aOutboundSecret: mocks.rotateOutbound,
  storeA2aOutboundSecret: mocks.storeOutbound, updateA2aTrustProfile: mocks.updateProfile,
  listA2aPublications: mocks.publications, getA2aPublication: mocks.publication,
}))
vi.mock('@/api/agent', () => ({ listAgents: mocks.agents }))
vi.mock('@/auth/usePlatformCan', () => ({
  usePlatformCan: () => ({ can: (permission: string) => mocks.permissions.includes('*') || mocks.permissions.includes(permission) }),
}))
vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn() },
  ElMessageBox: { confirm: mocks.confirm, prompt: mocks.prompt },
}))

const profile: A2aTrustProfile = {
  schema: 'test', id: 1, profileKey: 'partner-inbound', name: '合作方入站策略', description: '只读查询',
  direction: 'INBOUND', environment: 'DEVELOPMENT', trustLevel: 'KNOWN', authenticationMethods: ['API_KEY'],
  allowedScopes: ['a2a:task:read'], authorizationPolicy: { publicationKeys: ['published-service'], remoteAgentKeys: [], protocolSkillIds: ['lookup'], operations: ['tasks:get'], tenantScopes: ['*'] },
  dataPolicy: { allowedDataClassifications: ['INTERNAL'], allowTextParts: true, allowFileParts: false, allowUrlParts: false, payloadRetentionDays: 30 },
  delegatedIdentityPolicy: 'DENY', personalMemoryPolicy: 'DISABLED', rateLimitPerMinute: 60, maxConcurrentTasks: 10,
  maxRequestBytes: 1048576, maxArtifactBytes: 10485760, taskTimeoutMs: 120000, allowAnonymous: false,
  status: 'ACTIVE', version: 2, createdBy: 'test', updatedBy: 'test', createdAt: '', updatedAt: '',
}
const principal: A2aPrincipal = {
  schema: 'test', id: 2, principalKey: 'partner.agent', principalType: 'REMOTE_AGENT', displayName: '合作方 Agent',
  tenantScope: '', authenticatedSubject: 'verified-partner', trustProfileId: 1, credentialId: 3, scopes: ['a2a:task:read'],
  attributes: {}, status: 'ACTIVE', version: 1, createdBy: 'test', updatedBy: 'test', createdAt: '', updatedAt: '',
}
const credential: A2aCredential = {
  schema: 'test', id: 3, credentialKey: 'partner-key', name: '合作方凭据', direction: 'INBOUND', credentialType: 'API_KEY',
  materialMode: 'HASH', fingerprint: 'fixture-fingerprint-0123456789abcdef', versionNo: 2, status: 'ACTIVE',
  expiresAt: '2026-12-31T00:00:00Z', lastUsedAt: '2026-10-26T01:00:00Z', createdBy: 'test', createdAt: '', updatedAt: '',
}
function publication(id = 1): A2aPublicationDetail {
  return {
    schema: 'test',
    publication: { schema: 'test', id, publicationKey: `service-${id}`, agentId: 'local-agent', environment: 'DEVELOPMENT', tenantScope: '', publicHost: `service-${id}.example.test`, trustProfileId: 1, currentRevisionId: id * 10, status: 'PUBLISHED', version: 1, createdBy: 'test', updatedBy: 'test', createdAt: '', updatedAt: '' },
    revisions: [{ schema: 'test', id: id * 10, publicationId: id, revisionNo: 1, agentConfigVersionId: 9, agentVersion: 'v1', name: `服务 ${id}`, description: '', publicOrigin: `https://service-${id}.example.test`, protocolBasePath: '/a2a/v1', protocolBinding: 'HTTP+JSON', protocolVersion: '1.0', streamingSupported: false, pushNotificationsSupported: false, extendedCardSupported: false, defaultInputModes: ['text'], defaultOutputModes: ['text'], protocolSkills: [], agentCardJson: '{}', agentCardSha256: 'fixture-sha256', signatureStatus: 'UNSIGNED', conformanceStatus: 'NOT_RUN', validationSummaryJson: '{}', status: 'PUBLISHED', createdBy: 'test', createdAt: '' }],
  }
}

const slot = { template: '<div><slot /></div>' }
const table = defineComponent({
  name: 'ElTable', props: { data: { type: Array, default: () => [] } },
  setup(props, { slots }) { provide('rows', computed(() => props.data)); return () => h('div', { class: 'table' }, slots.default?.()) },
})
const column = defineComponent({
  name: 'ElTableColumn', props: ['label'],
  setup(props, { slots }) {
    const rows = inject<ComputedRef<unknown[]>>('rows')!
    return () => h('div', { 'data-column': props.label }, rows.value.map(row => h('div', slots.default?.({ row }))))
  },
})
const wrappers: VueWrapper[] = []
function open(component: typeof A2aTrustWorkspace | typeof A2aDeveloperConsole) {
  const wrapper = mount(component, { global: {
    directives: { loading: () => {} },
    stubs: {
      LiquidGlassTabs: { name: 'LiquidGlassTabs', props: ['modelValue'], emits: ['update:modelValue'], template: '<nav><slot /></nav>' },
      LiquidGlassDropdown: { name: 'LiquidGlassDropdown', emits: ['command'], template: '<div class="dropdown"><slot /><div hidden><slot name="dropdown" /></div></div>' },
      LiquidGlassButton: { emits: ['click'], template: '<button @click="$emit(\'click\')"><slot /></button>' },
      LiquidGlassInput: { name: 'LiquidGlassInput', props: ['modelValue'], emits: ['update:modelValue', 'clear'], template: '<input />' },
      LiquidGlassSelect: { name: 'LiquidGlassSelect', props: ['modelValue'], emits: ['change'], template: '<select />' },
      LiquidGlassFilterBar: { emits: ['query'], template: '<form @submit.prevent="$emit(\'query\')"><slot /><slot name="actions" /></form>' },
      LiquidGlassTable: { template: '<section><slot name="toolbar" /><slot /></section>' },
      LiquidGlassNotice: { props: ['title'], template: '<aside>{{ title }}</aside>' },
      LiquidGlassEmptyState: { props: ['title'], template: '<p class="empty">{{ title }}</p>' },
      WorkbenchPanel: { props: ['title'], template: '<section><h2 v-if="title">{{ title }}</h2><slot name="actions" /><slot /></section>' },
      AppDialog: { props: ['modelValue', 'title'], template: '<section v-if="modelValue" class="dialog"><h3>{{ title }}</h3><slot /><slot name="footer" /></section>' },
      AppDrawer: { props: ['modelValue', 'title'], template: '<section v-if="modelValue" class="drawer"><h3>{{ title }}</h3><slot /></section>' },
      AgentCardPreview: { props: ['revision'], template: '<pre>{{ revision.agentCardJson }}</pre>' },
      ElButton: { emits: ['click'], template: '<button @click="$emit(\'click\')"><slot /></button>' },
      ElForm: { name: 'ElForm', props: ['disabled'], template: '<form><slot /></form>' },
      ElInput: { name: 'ElInput', props: ['modelValue'], emits: ['update:modelValue'], template: '<input />' },
      ElSelect: { name: 'ElSelect', props: ['modelValue', 'loading'], emits: ['update:modelValue', 'change'], template: '<select><slot /></select>' },
      ElDescriptions: slot, ElDescriptionsItem: slot, ElFormItem: slot, ElIcon: slot, ElDropdownMenu: slot, ElDropdownItem: slot, ElTag: slot,
      ElTable: table, ElTableColumn: column, ElTabPane: true, ElDivider: true, ElCheckbox: true, ElInputNumber: true, ElDatePicker: true, ElOption: true,
    },
  } })
  wrappers.push(wrapper)
  return wrapper
}
async function click(wrapper: VueWrapper, text: string) {
  const button = wrapper.findAll('button').find(item => item.text() === text)
  expect(button, `missing button: ${text}`).toBeDefined()
  await button!.trigger('click')
  await flushPromises()
}
async function tab(wrapper: VueWrapper, value: string) {
  wrapper.findComponent({ name: 'LiquidGlassTabs' }).vm.$emit('update:modelValue', value)
  await flushPromises()
}
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => { resolve = done })
  return { promise, resolve }
}

beforeEach(() => {
  vi.resetAllMocks()
  mocks.permissions = ['*']
  mocks.profiles.mockResolvedValue({ data: { items: [structuredClone(profile)] } })
  mocks.principals.mockResolvedValue({ data: { items: [structuredClone(principal)] } })
  mocks.credentials.mockResolvedValue({ data: { items: [structuredClone(credential)] } })
  mocks.publications.mockResolvedValue({ data: { items: [1, 2, 3].map(id => publication(id).publication) } })
  mocks.publication.mockImplementation(async (id: number) => ({ data: publication(id) }))
  mocks.agents.mockResolvedValue({ data: [] })
  Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: mocks.copy } })
})
afterEach(() => wrappers.splice(0).forEach(wrapper => wrapper.unmount()))

describe('A2A governance workspace', () => {
  it('allows readers to inspect full policy, identity and credential details without write controls', async () => {
    mocks.permissions = ['a2a-hub:read']
    const wrapper = open(A2aTrustWorkspace)
    await flushPromises()
    expect(wrapper.text()).not.toContain('新建策略')
    expect(wrapper.find('.dropdown').exists()).toBe(false)
    expect(mocks.agents).not.toHaveBeenCalled()
    await click(wrapper, '查看')
    expect(wrapper.find('.dialog h3').text()).toBe('查看信任策略')
    expect(wrapper.findComponent({ name: 'ElForm' }).props('disabled')).toBe(true)
    expect(wrapper.text()).not.toContain('保存策略')
    await click(wrapper, '关闭')
    await tab(wrapper, 'principals')
    expect(wrapper.find('.table').text()).toContain(profile.name)
    expect(wrapper.find('.table').text()).not.toContain(principal.scopes[0])
    await click(wrapper, '查看')
    expect(wrapper.find('.drawer').text()).toContain(principal.scopes[0])
    expect(wrapper.find('.drawer').text()).toContain(credential.name)
    await tab(wrapper, 'credentials')
    expect(wrapper.find('.table').text()).not.toContain(credential.fingerprint)
    expect(wrapper.text()).not.toContain('新建凭据')
    await click(wrapper, '查看')
    const drawers = wrapper.findAll('.drawer')
    expect(drawers[drawers.length - 1].text()).toContain(credential.fingerprint)
    expect(mocks.profileStatus).not.toHaveBeenCalled()
    expect(mocks.revoke).not.toHaveBeenCalled()
  })

  it('keeps status changes behind confirmation and treats cancel as no change', async () => {
    const wrapper = open(A2aTrustWorkspace)
    await flushPromises()
    const menu = wrapper.findComponent({ name: 'LiquidGlassDropdown' })
    mocks.confirm.mockRejectedValueOnce('cancel')
    menu.vm.$emit('command', 'ARCHIVED')
    await flushPromises()
    expect(mocks.profileStatus).not.toHaveBeenCalled()
    mocks.confirm.mockResolvedValueOnce('confirm')
    menu.vm.$emit('command', 'DISABLED')
    await flushPromises()
    expect(mocks.profileStatus).toHaveBeenCalledWith(profile.id, 'DISABLED')
  })

  it('does not infer that an identity is anonymous from a missing credential', async () => {
    mocks.principals.mockResolvedValue({ data: { items: [{ ...principal, credentialId: null }] } })
    const wrapper = open(A2aTrustWorkspace)
    await flushPromises()
    await tab(wrapper, 'principals')
    await click(wrapper, '查看')
    expect(wrapper.find('.drawer').text()).toContain('未绑定凭据')
    expect(wrapper.find('.drawer').text()).not.toContain('匿名')
  })

  it('loads Runtime Agents only when creating a local Agent identity', async () => {
    const wrapper = open(A2aTrustWorkspace)
    await flushPromises()
    await tab(wrapper, 'principals')
    await click(wrapper, '新建身份')
    expect(mocks.agents).not.toHaveBeenCalled()
    const type = wrapper.find('.dialog').findComponent({ name: 'ElSelect' })
    type.vm.$emit('update:modelValue', 'LOCAL_AGENT')
    type.vm.$emit('change', 'LOCAL_AGENT')
    await flushPromises()
    expect(mocks.agents).toHaveBeenCalledTimes(1)
    expect(mocks.createPrincipal).not.toHaveBeenCalled()
  })

  it('allows retrying a failed local Agent lookup without losing the identity form', async () => {
    mocks.agents.mockRejectedValueOnce(new Error('unavailable')).mockResolvedValueOnce({ data: [] })
    const wrapper = open(A2aTrustWorkspace)
    await flushPromises()
    await tab(wrapper, 'principals')
    await click(wrapper, '新建身份')
    const type = wrapper.find('.dialog').findComponent({ name: 'ElSelect' })
    type.vm.$emit('update:modelValue', 'LOCAL_AGENT')
    type.vm.$emit('change', 'LOCAL_AGENT')
    await flushPromises()
    expect(wrapper.find('.dialog').text()).toContain('加载失败，重试')
    await click(wrapper, '加载失败，重试')
    expect(mocks.agents).toHaveBeenCalledTimes(2)
    expect(wrapper.find('.dialog').text()).not.toContain('加载失败，重试')
    expect(mocks.createPrincipal).not.toHaveBeenCalled()
  })

  it('limits search to the active asset and keeps related choices available', async () => {
    mocks.profiles.mockImplementation(async (query: { search?: string }) => ({ data: { items: query.search ? [] : [profile] } }))
    mocks.credentials.mockImplementation(async (query: { search?: string }) => ({ data: { items: query.search ? [] : [credential] } }))
    const wrapper = open(A2aTrustWorkspace)
    await flushPromises()
    await tab(wrapper, 'principals')
    wrapper.findComponent({ name: 'LiquidGlassInput' }).vm.$emit('update:modelValue', '合作方')
    wrapper.findComponent({ name: 'A2aListToolbar' }).vm.$emit('query')
    await flushPromises()
    expect(mocks.principals).toHaveBeenLastCalledWith(expect.objectContaining({ search: '合作方' }))
    expect(mocks.profiles).toHaveBeenLastCalledWith(expect.not.objectContaining({ search: '合作方' }))
    expect(mocks.credentials).toHaveBeenLastCalledWith(expect.not.objectContaining({ search: '合作方' }))
    expect(wrapper.find('.table').text()).toContain(profile.name)
    await click(wrapper, '查看')
    expect(wrapper.find('.drawer').text()).toContain(credential.name)
    await tab(wrapper, 'profiles')
    expect(mocks.profiles).toHaveBeenLastCalledWith(expect.objectContaining({ search: undefined }))
  })

  it('does not let an older search response overwrite the latest result', async () => {
    const wrapper = open(A2aTrustWorkspace)
    await flushPromises()
    await tab(wrapper, 'principals')
    const older = deferred<{ data: { items: A2aPrincipal[] } }>()
    const newer = deferred<{ data: { items: A2aPrincipal[] } }>()
    mocks.principals.mockReturnValueOnce(older.promise).mockReturnValueOnce(newer.promise)
    const input = wrapper.findComponent({ name: 'LiquidGlassInput' })
    const toolbar = wrapper.findComponent({ name: 'A2aListToolbar' })
    input.vm.$emit('update:modelValue', '旧搜索')
    toolbar.vm.$emit('query')
    input.vm.$emit('update:modelValue', '新搜索')
    toolbar.vm.$emit('query')
    newer.resolve({ data: { items: [principal] } })
    await flushPromises()
    older.resolve({ data: { items: [] } })
    await flushPromises()
    expect(wrapper.find('.table').text()).toContain(principal.displayName)
  })

  it('keeps an archived strategy editable for an authorized manager', async () => {
    mocks.profiles.mockResolvedValue({ data: { items: [{ ...profile, status: 'ARCHIVED' }] } })
    const wrapper = open(A2aTrustWorkspace)
    await flushPromises()
    await click(wrapper, '编辑')
    expect(wrapper.find('.dialog h3').text()).toBe('编辑信任策略')
    expect(wrapper.findComponent({ name: 'ElForm' }).props('disabled')).toBe(false)
  })

  it('keeps an issued key in its one-time dialog and clears it on close', async () => {
    const keyOnce = 'fixture-one-time-value-not-a-real-key'
    mocks.createKey.mockResolvedValue({ data: { credential, apiKeyOnce: keyOnce } })
    const wrapper = open(A2aTrustWorkspace)
    await flushPromises()
    await tab(wrapper, 'credentials')
    wrapper.findComponent({ name: 'LiquidGlassDropdown' }).vm.$emit('command', 'INBOUND')
    await flushPromises()
    const fields = wrapper.find('.dialog').findAllComponents({ name: 'ElInput' })
    fields[0].vm.$emit('update:modelValue', 'new-key')
    fields[1].vm.$emit('update:modelValue', '新凭据')
    await click(wrapper, '签发')
    expect(mocks.createKey).toHaveBeenCalledWith(expect.objectContaining({ credentialKey: 'new-key', name: '新凭据' }))
    expect(wrapper.find('.secret-box').text()).toContain(keyOnce)
    expect(wrapper.find('.table').text()).not.toContain(keyOnce)
    await click(wrapper, '我已安全保存，关闭')
    expect(wrapper.text()).not.toContain(keyOnce)
    wrapper.findComponent({ name: 'LiquidGlassDropdown' }).vm.$emit('command', 'INBOUND')
    await flushPromises()
    expect(wrapper.find('.secret-box').exists()).toBe(false)
  })
})

describe('A2A developer console', () => {
  it('copies the complete current endpoint while optional content starts collapsed', async () => {
    const wrapper = open(A2aDeveloperConsole)
    await flushPromises()
    expect(wrapper.findAll('.endpoint-list code').map(item => item.text())).toEqual([
      'https://service-1.example.test/.well-known/agent-card.json',
      'https://service-1.example.test/a2a/v1/message:send',
      'https://service-1.example.test/a2a/v1/tasks/{taskId}',
    ])
    expect(wrapper.findAll('details')).toHaveLength(3)
    expect(wrapper.find('details[open]').exists()).toBe(false)
    await click(wrapper, '复制')
    expect(mocks.copy).toHaveBeenCalledWith('https://service-1.example.test/.well-known/agent-card.json')
  })

  it('clears old endpoints during target changes and ignores out-of-order responses', async () => {
    const wrapper = open(A2aDeveloperConsole)
    await flushPromises()
    const second = deferred<{ data: A2aPublicationDetail }>()
    const third = deferred<{ data: A2aPublicationDetail }>()
    mocks.publication.mockReturnValueOnce(second.promise).mockReturnValueOnce(third.promise)
    const picker = wrapper.findComponent({ name: 'LiquidGlassSelect' })
    picker.vm.$emit('change', 2)
    await flushPromises()
    expect(wrapper.find('.endpoint-list').exists()).toBe(false)
    picker.vm.$emit('change', 3)
    second.resolve({ data: publication(2) })
    await flushPromises()
    expect(wrapper.find('.endpoint-list').exists()).toBe(false)
    third.resolve({ data: publication(3) })
    await flushPromises()
    expect(wrapper.find('.endpoint-list').text()).toContain('service-3.example.test')
    expect(wrapper.find('.endpoint-list').text()).not.toContain('service-2.example.test')
  })

  it('shows a failed detail request and can recover by refreshing the selected service', async () => {
    const wrapper = open(A2aDeveloperConsole)
    await flushPromises()
    mocks.publication.mockRejectedValueOnce(new Error('unavailable'))
    wrapper.findComponent({ name: 'LiquidGlassSelect' }).vm.$emit('change', 2)
    await flushPromises()
    expect(wrapper.find('aside').text()).toBe('接入信息加载失败，请重试。')
    expect(wrapper.find('.endpoint-list').exists()).toBe(false)
    await wrapper.find('[aria-label="刷新"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('.endpoint-list').text()).toContain('service-2.example.test')
  })

  it('clears a vanished publication on refresh without requesting its old detail', async () => {
    const wrapper = open(A2aDeveloperConsole)
    await flushPromises()
    mocks.publications.mockResolvedValueOnce({ data: { items: [] } })
    await wrapper.find('[aria-label="刷新"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('.empty').text()).toBe('还没有已发布的服务')
    expect(wrapper.find('.endpoint-list').exists()).toBe(false)
    expect(mocks.publication).toHaveBeenCalledTimes(1)
  })

  it('does not substitute a draft or historical revision for the current published revision', async () => {
    const data = publication()
    data.publication.currentRevisionId = null
    data.revisions[0].status = 'DRAFT'
    mocks.publication.mockResolvedValueOnce({ data })
    const wrapper = open(A2aDeveloperConsole)
    await flushPromises()
    expect(wrapper.find('.empty').text()).toBe('暂无可用的已发布修订')
    expect(wrapper.find('.endpoint-list').exists()).toBe(false)
  })

  it.each([['NOT_RUN', '未执行'], ['FAILED', '未通过'], ['PASSED', '已通过'], ['RUNNING', '执行中'], ['ERROR', '执行出错'], ['UNKNOWN', 'UNKNOWN']])('reflects TCK status %s without claiming end-to-end verification', async (status, label) => {
    const data = publication()
    data.revisions[0].conformanceStatus = status
    mocks.publication.mockResolvedValueOnce({ data })
    const wrapper = open(A2aDeveloperConsole)
    await flushPromises()
    const rows = wrapper.findAll('.readiness-list > div')
    expect(rows.find(row => row.text().includes('官方 TCK'))!.text()).toContain(label)
    expect(rows.find(row => row.text().includes('双系统'))!.text()).toContain('未查询')
  })
})
