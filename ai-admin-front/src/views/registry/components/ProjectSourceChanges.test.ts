import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { type Ref } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ProjectSourceChanges from './ProjectSourceChanges.vue'
import { platformSessionId, platformSessionUser } from '@/auth/platformSession'
import type { ScanProject } from '@/types/scanProject'
import type { PlatformUserProfile } from '@/utils/platformAuth'

const mocks = vi.hoisted(() => ({ read: vi.fn(), unmounted: vi.fn(), refresh: vi.fn() }))
vi.mock('@/auth/platformSession', async () => {
  const { ref } = await import('vue')
  return { platformSessionId: ref('account-a'), platformSessionUser: ref(null) }
})
vi.mock('./CapabilityReviewPanel.vue', async () => {
  const { defineComponent, onUnmounted, ref } = await import('vue')
  return { default: defineComponent({ props: ['projectCode', 'density'], setup(props, { expose }) {
    const text = ref('loading')
    void mocks.read(props.projectCode, platformSessionId.value).then((result: string) => { text.value = result })
    onUnmounted(mocks.unmounted)
    expose({ refresh: mocks.refresh })
    return { text }
  }, template: '<div data-test="changes">{{ text }}</div>' }) }
})

function project(id = 7, projectCode = 'orders'): ScanProject { return { id, projectCode, name: projectCode } as ScanProject }
function deferred() {
  let resolve!: (value: string) => void
  const promise = new Promise<string>(done => { resolve = done })
  return { promise, resolve }
}
let wrapper: ReturnType<typeof mount> | undefined
beforeEach(() => {
  vi.clearAllMocks()
  ;(platformSessionId as Ref<string>).value = 'account-a'
  ;(platformSessionUser as Ref<PlatformUserProfile | null>).value = { userId: 1, username: 'reader', permissionGrants: [
    { permissionCode: 'platform:read', scopeType: 'GLOBAL', scopeValue: '*' },
  ] }
  mocks.read.mockResolvedValue('current')
})
afterEach(() => wrapper?.unmount())
function start(density: 'comfortable' | 'compact' = 'comfortable') { wrapper = mount(ProjectSourceChanges, { props: { project: project(), density }, global: { plugins: [ElementPlus] } }); return wrapper }
describe('project source changes context', () => {
  it('forwards a same-project refresh to the change owner and ignores refresh when access is removed', async () => {
    const view = start('compact'); await flushPromises()
    await (view.vm as unknown as { refresh: () => Promise<void> }).refresh()
    expect(mocks.refresh).toHaveBeenCalledOnce()
    ;(platformSessionUser as Ref<PlatformUserProfile | null>).value!.permissionGrants = []
    await flushPromises()
    await (view.vm as unknown as { refresh: () => Promise<void> }).refresh()
    expect(mocks.refresh).toHaveBeenCalledOnce()
  })
  it('uses concise copy only for compact workspaces and passes density to the review owner', async () => {
    const view = start('compact'); await flushPromises()
    expect(view.text()).toContain('可信 SDK 声明自动处理，只需确认例外变化。')
    expect(view.getComponent({ name: 'CapabilityReviewPanel' }).props('density')).toBe('compact')
    await view.setProps({ density: 'comfortable' })
    expect(view.text()).toContain('可信 SDK 同步按项目策略自动接纳声明')
    expect(view.getComponent({ name: 'CapabilityReviewPanel' }).props('density')).toBe('comfortable')
  })
  it('unmounts the read context when project access is removed', async () => {
    const view = start()
    await flushPromises()
    ;(platformSessionUser as Ref<PlatformUserProfile | null>).value!.permissionGrants = []
    await flushPromises()
    expect(view.text()).toContain('无权读取')
    expect(view.find('[data-test="changes"]').exists()).toBe(false)
    expect(mocks.unmounted).toHaveBeenCalledOnce()
  })
  it('remounts for an account away/back switch within one tick and discards the old response', async () => {
    const pending = deferred()
    mocks.read.mockReturnValueOnce(pending.promise)
    const view = start()
    ;(platformSessionId as Ref<string>).value = 'account-b'
    ;(platformSessionId as Ref<string>).value = 'account-a'
    await flushPromises()
    expect(mocks.read).toHaveBeenCalledTimes(2)
    expect(mocks.unmounted).toHaveBeenCalledOnce()
    pending.resolve('old account data')
    await flushPromises()
    expect(view.get('[data-test="changes"]').text()).toBe('current')
  })
  it('replaces the child for a project switch and ignores a late source response', async () => {
    const pending = deferred()
    mocks.read.mockReturnValueOnce(pending.promise)
    const view = start()
    await view.setProps({ project: project(8, 'billing') })
    await flushPromises()
    expect(mocks.read).toHaveBeenLastCalledWith('billing', 'account-a')
    pending.resolve('old orders data')
    await flushPromises()
    expect(view.get('[data-test="changes"]').text()).toBe('current')
  })
})
