import { mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent, h, nextTick, type Ref } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useCollapsiblePageHeader } from '../../composables/useCollapsiblePageHeader'

interface MatchMediaStubOptions {
  reducedMotion: boolean
}

function stubMatchMedia({ reducedMotion }: MatchMediaStubOptions) {
  vi.stubGlobal('matchMedia', vi.fn((query: string) => ({
    matches: reducedMotion && query === '(prefers-reduced-motion: reduce)',
    media: query,
    onchange: null,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn(),
  })))
}

describe('useCollapsiblePageHeader motion timing', () => {
  let wrapper: VueWrapper | undefined

  beforeEach(() => {
    vi.useFakeTimers()
    document.body.innerHTML = '<div class="main-layout"><main class="main-content"><div class="test-page"></div></main></div>'
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = undefined
    document.body.innerHTML = ''
    vi.unstubAllGlobals()
    vi.useRealTimers()
  })

  function mountHarness(reducedMotion: boolean) {
    stubMatchMedia({ reducedMotion })
    const onLayoutChange = vi.fn()
    const onScroll = vi.fn()
    let collapsed: Readonly<Ref<boolean>> | undefined
    let setCollapsed: ((value: boolean) => void) | undefined

    const Harness = defineComponent({
      setup() {
        const controller = useCollapsiblePageHeader({
          rootSelector: '.test-page',
          onLayoutChange,
          onScroll,
        })
        collapsed = controller.collapsed
        setCollapsed = controller.setCollapsed
        return () => h('div')
      },
    })

    wrapper = mount(Harness, {
      attachTo: document.querySelector('.test-page') as HTMLElement,
    })

    return {
      collapsed: () => collapsed?.value,
      setCollapsed: (value: boolean) => setCollapsed?.(value),
      onLayoutChange,
      onScroll,
    }
  }

  it('waits for the 240ms visual transition before recalculating normal-motion layout', async () => {
    const harness = mountHarness(false)

    harness.setCollapsed(true)
    await nextTick()

    expect(harness.collapsed()).toBe(true)
    expect(harness.onLayoutChange).not.toHaveBeenCalled()
    expect(harness.onScroll).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(239)
    expect(harness.onLayoutChange).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(1)
    expect(harness.onLayoutChange).toHaveBeenCalledWith(true)
    expect(harness.onScroll).toHaveBeenCalledOnce()
  })

  it('recalculates immediately when reduced motion removes the CSS transition', async () => {
    const harness = mountHarness(true)

    harness.setCollapsed(true)
    await nextTick()

    expect(harness.collapsed()).toBe(true)
    expect(harness.onLayoutChange).toHaveBeenCalledWith(true)
    expect(harness.onScroll).toHaveBeenCalledOnce()
    expect(vi.getTimerCount()).toBe(0)
  })
})
