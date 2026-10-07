import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { ref, type Ref } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ProjectSourceChanges from './ProjectSourceChanges.vue'
import { platformSessionId, platformSessionUser } from '@/auth/platformSession'
import type { ScanProject } from '@/types/scanProject'
import type { PlatformUserProfile } from '@/utils/platformAuth'

const mocks = vi.hoisted(() => ({ read: vi.fn(), push: vi.fn(), unmounted: vi.fn() }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: mocks.push }) }))
vi.mock('@/auth/platformSession', async () => {
  const { ref } = await import('vue')
  return { platformSessionId: ref('account-a'), platformSessionUser: ref(null) }
})
vi.mock('./CapabilityReviewPanel.vue', async () => {
  const { defineComponent, onUnmounted, ref } = await import('vue')
  return { default: defineComponent({ props: ['projectCode'], setup(props) {
    const text = ref('loading')
    void mocks.read(props.projectCode, platformSessionId.value).then((result: string) => { text.value = result })
    onUnmounted(mocks.unmounted)
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
function start() { wrapper = mount(ProjectSourceChanges, { props: { project: project() }, global: { plugins: [ElementPlus] } }); return wrapper }
describe('project source changes context', () => {
  it('keeps diagnostics behind project write permission and uses the owning project', async () => {
    const view = start()
    await flushPromises()
    expect(view.text()).not.toContain('同步诊断')
    ;(platformSessionUser as Ref<PlatformUserProfile | null>).value!.permissionGrants!.push(
      { permissionCode: 'platform:write', scopeType: 'PROJECT', scopeValue: 'orders' })
    await flushPromises()
    await view.get('button').trigger('click')
    expect(mocks.push).toHaveBeenCalledWith({ name: 'RegistrySyncDiagnostics', params: { projectCode: 'orders' } })
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
