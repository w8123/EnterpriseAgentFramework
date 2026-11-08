import { defineComponent, ref } from 'vue'
import { mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useSdkAccessWizardTaskRefresh } from './useSdkAccessWizardTaskRefresh'

describe('project onboarding background refresh', () => {
  const wrappers: VueWrapper[] = []

  beforeEach(() => { vi.useFakeTimers() })
  afterEach(() => {
    wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
    vi.restoreAllMocks()
    vi.useRealTimers()
  })

  it('refreshes every ten seconds and pauses while a dialog is open or manual mode is selected', async () => {
    vi.spyOn(document, 'hidden', 'get').mockReturnValue(false)
    const accessMode = ref<'manual' | 'ai-coding'>('ai-coding')
    const paused = ref(false)
    const refresh = vi.fn().mockResolvedValue(undefined)
    wrappers.push(mount(defineComponent({ setup() {
      useSdkAccessWizardTaskRefresh({ accessMode, projectCode: ref('demo'), activeProjectCode: ref('demo'), paused, refresh })
      return () => null
    } })))

    await vi.advanceTimersByTimeAsync(9_999)
    expect(refresh).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(10_001)
    expect(refresh).toHaveBeenCalledTimes(2)
    paused.value = true
    await vi.advanceTimersByTimeAsync(30_000)
    expect(refresh).toHaveBeenCalledTimes(2)
    paused.value = false
    accessMode.value = 'manual'
    await vi.advanceTimersByTimeAsync(30_000)
    expect(refresh).toHaveBeenCalledTimes(2)
    accessMode.value = 'ai-coding'
    await vi.advanceTimersByTimeAsync(10_000)
    expect(refresh).toHaveBeenCalledTimes(3)
  })

  it('wakes on visibility and focus without overlapping a slow request', async () => {
    const hidden = vi.spyOn(document, 'hidden', 'get').mockReturnValue(true)
    let resolveRefresh!: () => void
    const refresh = vi.fn().mockImplementationOnce(() => new Promise<void>((resolve) => { resolveRefresh = resolve }))
      .mockResolvedValue(undefined)
    wrappers.push(mount(defineComponent({ setup() {
      useSdkAccessWizardTaskRefresh({ accessMode: ref('ai-coding'), projectCode: ref('demo'),
        activeProjectCode: ref('demo'), paused: ref(false), refresh })
      return () => null
    } })))

    await vi.advanceTimersByTimeAsync(30_000)
    expect(refresh).not.toHaveBeenCalled()
    hidden.mockReturnValue(false)
    document.dispatchEvent(new Event('visibilitychange'))
    await vi.advanceTimersByTimeAsync(0)
    expect(refresh).toHaveBeenCalledTimes(1)
    window.dispatchEvent(new Event('focus'))
    await vi.advanceTimersByTimeAsync(30_000)
    expect(refresh).toHaveBeenCalledTimes(1)
    resolveRefresh()
    await vi.advanceTimersByTimeAsync(0)
    expect(refresh).toHaveBeenCalledTimes(2)
    window.dispatchEvent(new Event('focus'))
    await vi.advanceTimersByTimeAsync(0)
    expect(refresh).toHaveBeenCalledTimes(3)
  })

  it('invalidates pending results on project switch and stops after unmount', async () => {
    vi.spyOn(document, 'hidden', 'get').mockReturnValue(false)
    const projectCode = ref('first')
    const activeProjectCode = ref<string | undefined>('first')
    let resolveRefresh!: () => void
    let mayApply: (() => boolean) | undefined
    const refresh = vi.fn().mockImplementationOnce((canApply: () => boolean) => {
      mayApply = canApply
      return new Promise<void>((resolve) => { resolveRefresh = resolve })
    }).mockResolvedValue(undefined)
    const wrapper = mount(defineComponent({ setup() {
      useSdkAccessWizardTaskRefresh({ accessMode: ref('ai-coding'), projectCode, activeProjectCode, paused: ref(false), refresh })
      return () => null
    } }))
    wrappers.push(wrapper)

    await vi.advanceTimersByTimeAsync(10_000)
    expect(mayApply?.()).toBe(true)
    projectCode.value = 'second'
    expect(mayApply?.()).toBe(false)
    activeProjectCode.value = 'second'
    expect(mayApply?.()).toBe(false)
    resolveRefresh()
    await vi.advanceTimersByTimeAsync(10_000)
    expect(refresh).toHaveBeenCalledTimes(2)
    wrapper.unmount()
    window.dispatchEvent(new Event('focus'))
    await vi.advanceTimersByTimeAsync(30_000)
    expect(refresh).toHaveBeenCalledTimes(2)
  })

  it('retries after a failed background request', async () => {
    vi.spyOn(document, 'hidden', 'get').mockReturnValue(false)
    const refresh = vi.fn().mockRejectedValueOnce(new Error('network')).mockResolvedValue(undefined)
    wrappers.push(mount(defineComponent({ setup() {
      useSdkAccessWizardTaskRefresh({ accessMode: ref('ai-coding'), projectCode: ref('demo'),
        activeProjectCode: ref('demo'), paused: ref(false), refresh })
      return () => null
    } })))
    await vi.advanceTimersByTimeAsync(20_000)
    expect(refresh).toHaveBeenCalledTimes(2)
  })
})
