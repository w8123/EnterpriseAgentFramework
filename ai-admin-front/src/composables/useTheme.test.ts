import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'

async function loadTheme() {
  vi.resetModules()
  return import('./useTheme')
}

describe('useTheme appearance preferences', () => {
  beforeEach(() => {
    localStorage.clear()
    const html = document.documentElement
    for (const name of ['data-theme', 'data-brand', 'data-density', 'data-reduce-transparency', 'data-glass-refraction']) {
      html.removeAttribute(name)
    }
    html.classList.remove('dark')
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('starts in light mode with the default brand, comfortable density and refraction on', async () => {
    const { useTheme } = await loadTheme()
    const { theme, brand, density, reduceTransparency, glassRefraction } = useTheme()
    const html = document.documentElement

    expect(theme.value).toBe('light')
    expect(brand.value).toBe('tech-purple')
    expect(density.value).toBe('comfortable')
    expect(reduceTransparency.value).toBe(false)
    expect(glassRefraction.value).toBe(true)
    expect(html.getAttribute('data-theme')).toBe('light')
    expect(html.getAttribute('data-density')).toBe('comfortable')
    expect(html.getAttribute('data-reduce-transparency')).toBe('false')
    expect(html.getAttribute('data-glass-refraction')).toBe('on')
    expect(html.classList.contains('dark')).toBe(false)
  })

  it('restores saved preferences and ignores invalid stored values', async () => {
    localStorage.setItem('themeMode', 'dark')
    localStorage.setItem('brandTheme', 'metro-green')
    localStorage.setItem('density', 'compact')
    localStorage.setItem('reduceTransparency', 'true')
    localStorage.setItem('glassRefraction', 'false')
    const { useTheme } = await loadTheme()
    const { theme, brand, density, reduceTransparency, glassRefraction } = useTheme()

    expect(theme.value).toBe('dark')
    expect(document.documentElement.classList.contains('dark')).toBe(true)
    expect(brand.value).toBe('metro-green')
    expect(density.value).toBe('compact')
    expect(reduceTransparency.value).toBe(true)
    expect(glassRefraction.value).toBe(false)

    localStorage.setItem('brandTheme', 'not-a-brand')
    localStorage.setItem('density', 'huge')
    localStorage.setItem('themeMode', 'sepia')
    const reloaded = (await loadTheme()).useTheme()
    expect(reloaded.brand.value).toBe('tech-purple')
    expect(reloaded.density.value).toBe('comfortable')
    expect(reloaded.theme.value).toBe('light')
  })

  it('applies and persists changes through the shared setters', async () => {
    const { useTheme } = await loadTheme()
    const appearance = useTheme()
    const html = document.documentElement

    appearance.setDensity('spacious')
    appearance.setReduceTransparency(true)
    appearance.setGlassRefraction(false)
    appearance.setBrand('deep-ocean')
    appearance.setThemeMode('dark')
    await nextTick()

    expect(html.getAttribute('data-density')).toBe('spacious')
    expect(html.getAttribute('data-reduce-transparency')).toBe('true')
    expect(html.getAttribute('data-glass-refraction')).toBe('off')
    expect(html.getAttribute('data-brand')).toBe('deep-ocean')
    expect(html.getAttribute('data-theme')).toBe('dark')
    expect(html.classList.contains('dark')).toBe(true)
    expect(localStorage.getItem('density')).toBe('spacious')
    expect(localStorage.getItem('reduceTransparency')).toBe('true')
    expect(localStorage.getItem('glassRefraction')).toBe('false')
    expect(localStorage.getItem('brandTheme')).toBe('deep-ocean')
    expect(localStorage.getItem('themeMode')).toBe('dark')
    expect(localStorage.getItem('theme')).toBe('dark')

    appearance.setThemeMode('light')
    await nextTick()
    expect(html.getAttribute('data-theme')).toBe('light')
    expect(html.classList.contains('dark')).toBe(false)
  })

  it('opens the dark mode entry while keeping light as the default', async () => {
    const { DARK_THEME_ENTRY_READY, useTheme } = await loadTheme()
    expect(DARK_THEME_ENTRY_READY).toBe(true)
    expect(useTheme().themeMode.value).toBe('light')
  })
})
