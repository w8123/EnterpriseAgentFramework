import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent, nextTick, ref } from 'vue'
import LiquidGlassLayer from './LiquidGlassLayer.vue'

const optics = vi.hoisted(() => ({ build: vi.fn(), supported: true }))
const glassRefraction = ref(true)
const reduceTransparency = ref(false)
vi.mock('@/composables/useTheme', () => ({ useTheme: () => ({ glassRefraction, reduceTransparency }) }))
vi.mock('@/composables/liquidGlass', () => ({
  createLiquidGlassMap: optics.build,
  supportsLiquidGlassRefraction: () => optics.supported,
  prefersReducedTransparency: () => false,
}))

let wrapper: VueWrapper | undefined
let frames: FrameRequestCallback[] = []
const disconnect = vi.fn()
async function flushOptics() {
  await nextTick()
  frames.splice(0).forEach((callback) => callback(0))
  await nextTick()
}

beforeEach(() => {
  glassRefraction.value = true
  reduceTransparency.value = false
  optics.supported = true
  frames = []
  optics.build.mockReset().mockImplementation(({ width, height }) => ({
    width, height, url: 'data:image/png;base64,AA==', scale: 64,
    specular: 'data:image/png;base64,AA==', specularDark: 'data:image/png;base64,AA==',
  }))
  vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => frames.push(callback))
  vi.stubGlobal('cancelAnimationFrame', vi.fn())
  vi.stubGlobal('ResizeObserver', class { observe() {} disconnect = disconnect })
  vi.spyOn(HTMLElement.prototype, 'offsetWidth', 'get').mockReturnValue(640)
  vi.spyOn(HTMLElement.prototype, 'offsetHeight', 'get').mockReturnValue(320)
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  document.body.innerHTML = ''
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('LiquidGlassLayer lifecycle', () => {
  it('puts popup optics on its shell and keeps the dialog body outside the filter', async () => {
    wrapper = mount(defineComponent({
      components: { LiquidGlassLayer },
      template: '<section class="dialog-shell"><div class="dialog-body"><LiquidGlassLayer host-selector=".dialog-shell" /><button>Save</button></div></section>',
    }), { attachTo: document.body })
    await flushOptics()
    const host = document.querySelector('.dialog-shell')!
    expect(host.classList.contains('is-liquid-refracting')).toBe(true)
    expect(host.querySelector('.liquid-glass-layer')?.parentElement).toBe(host)
    expect(host.querySelector('.dialog-body .liquid-glass-layer')).toBeNull()
    expect(host.querySelector('.dialog-body button')?.textContent).toBe('Save')
    expect(optics.build).toHaveBeenCalledWith(expect.objectContaining({ width: 640, height: 320 }))

    reduceTransparency.value = true
    await flushOptics()
    expect(host.classList.contains('is-liquid-refracting')).toBe(false)
    expect(host.querySelector('.liquid-glass-layer')).toBeNull()
    reduceTransparency.value = false
    await flushOptics()
    expect(host.querySelector('.liquid-glass-layer')).not.toBeNull()
    wrapper.unmount()
    wrapper = undefined
    expect(disconnect).toHaveBeenCalled()
    expect(document.querySelector('.liquid-glass-layer')).toBeNull()
  })

  it('falls back without building maps when refraction is unsupported', async () => {
    optics.supported = false
    wrapper = mount(LiquidGlassLayer, { attachTo: document.body })
    await flushOptics()
    expect(optics.build).not.toHaveBeenCalled()
    expect(document.querySelector('.liquid-glass-layer')).toBeNull()
  })

  it('updates geometry without requiring the popup to resize', async () => {
    wrapper = mount(LiquidGlassLayer, { attachTo: document.body })
    await flushOptics()
    await wrapper.setProps({ bezel: 18, depth: 48 })
    await flushOptics()
    expect(optics.build).toHaveBeenLastCalledWith(expect.objectContaining({ bezel: 18, depth: 48 }))
    glassRefraction.value = false
    await flushOptics()
    expect(document.body.classList.contains('is-liquid-refracting')).toBe(false)
    expect(document.querySelector('.liquid-glass-layer')).toBeNull()
  })

  it('keeps a nested wallpaper lens aligned while scrolling without rebuilding its map', async () => {
    let top = 240
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(() => ({
      left: 100, top, right: 740, bottom: top + 320, width: 640, height: 320,
      x: 100, y: top, toJSON() { return {} },
    }))
    wrapper = mount(defineComponent({
      components: { LiquidGlassLayer },
      template: '<section><LiquidGlassLayer source="wallpaper" /><button>Current page</button></section>',
    }), { attachTo: document.body })
    await flushOptics()

    const source = () => document.querySelector<HTMLElement>('.liquid-glass-wallpaper')
    const lens = document.querySelector<HTMLElement>('.liquid-glass-layer')!
    expect(source()!.style.left).toBe('-100px')
    expect(source()!.style.top).toBe('-240px')
    expect(source()!.style.width).toBe(`${window.innerWidth}px`)
    expect(lens.style.filter).toContain('url(#reachai-liquid-glass-')
    expect(lens.style.backdropFilter).toBe('none')
    expect(lens.querySelector('button')).toBeNull()

    top = 200
    window.dispatchEvent(new Event('scroll'))
    await flushOptics()
    expect(source()!.style.top).toBe('-200px')
    expect(optics.build).toHaveBeenCalledTimes(1)

    reduceTransparency.value = true
    await flushOptics()
    expect(source()).toBeNull()
    wrapper.unmount()
    wrapper = undefined
    window.dispatchEvent(new Event('scroll'))
    expect(frames).toHaveLength(0)
  })
})
