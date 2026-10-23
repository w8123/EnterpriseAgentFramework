import { ref, watch } from 'vue'

export type Theme = 'dark' | 'light'
export type ThemeMode = Theme | 'system'
export type Density = 'compact' | 'comfortable' | 'spacious'
export type BrandTheme =
  | 'tech-purple'
  | 'metro-green'
  | 'aurora-cyan'
  | 'nebula-violet'
  | 'coral-rose'
  | 'solar-gold'
  | 'deep-ocean'

const brandOptions: Array<{ value: BrandTheme; label: string }> = [
  { value: 'tech-purple', label: '科技紫' },
  { value: 'aurora-cyan', label: '极光青' },
  { value: 'nebula-violet', label: '星云紫' },
  { value: 'coral-rose', label: '珊瑚玫' },
  { value: 'metro-green', label: '翡翠绿' },
  { value: 'solar-gold', label: '日冕金' },
  { value: 'deep-ocean', label: '深海蓝' },
]

const themeModeOptions: Array<{ value: ThemeMode; label: string }> = [
  { value: 'light', label: '浅色' },
  { value: 'dark', label: '深色' },
  { value: 'system', label: '跟随系统' },
]

const densityOptions: Array<{ value: Density; label: string }> = [
  { value: 'compact', label: '紧凑' },
  { value: 'comfortable', label: '标准' },
  { value: 'spacious', label: '宽松' },
]

// 存储键与 index.html 首屏脚本保持一致，避免刷新时闪色。
const STORAGE_KEYS = {
  theme: 'theme',
  themeMode: 'themeMode',
  brand: 'brandTheme',
  density: 'density',
  reduceTransparency: 'reduceTransparency',
  glassRefraction: 'glassRefraction',
} as const

function isBrandTheme(value: string | null): value is BrandTheme {
  return brandOptions.some((option) => option.value === value)
}

function isThemeMode(value: string | null): value is ThemeMode {
  return value === 'light' || value === 'dark' || value === 'system'
}

function isDensity(value: string | null): value is Density {
  return value === 'compact' || value === 'comfortable' || value === 'spacious'
}

function readStorage(key: string): string | null {
  try {
    return localStorage.getItem(key)
  } catch {
    return null
  }
}

function writeStorage(key: string, value: string) {
  try {
    localStorage.setItem(key, value)
  } catch {
    // 隐私模式或存储被禁用时只影响偏好持久化。
  }
}

function systemPrefersDark(): boolean {
  return typeof window !== 'undefined'
    && typeof window.matchMedia === 'function'
    && window.matchMedia('(prefers-color-scheme: dark)').matches
}

function resolveTheme(mode: ThemeMode): Theme {
  if (mode === 'system') return systemPrefersDark() ? 'dark' : 'light'
  return mode
}

const storedMode = readStorage(STORAGE_KEYS.themeMode)
const storedBrand = readStorage(STORAGE_KEYS.brand)
const storedDensity = readStorage(STORAGE_KEYS.density)

// 浅色是默认主体验；没有保存过模式时始终从浅色开始。
const themeMode = ref<ThemeMode>(isThemeMode(storedMode) ? storedMode : 'light')
const theme = ref<Theme>(resolveTheme(themeMode.value))
const brand = ref<BrandTheme>(isBrandTheme(storedBrand) ? storedBrand : 'tech-purple')
const density = ref<Density>(isDensity(storedDensity) ? storedDensity : 'comfortable')
const reduceTransparency = ref(readStorage(STORAGE_KEYS.reduceTransparency) === 'true')
const glassRefraction = ref(readStorage(STORAGE_KEYS.glassRefraction) !== 'false')

function applyTheme(t: Theme) {
  const html = document.documentElement
  html.setAttribute('data-theme', t)
  if (t === 'dark') {
    html.classList.add('dark')
  } else {
    html.classList.remove('dark')
  }
  writeStorage(STORAGE_KEYS.theme, t)
}

function applyBrand(value: BrandTheme) {
  document.documentElement.setAttribute('data-brand', value)
  writeStorage(STORAGE_KEYS.brand, value)
}

function applyDensity(value: Density) {
  document.documentElement.setAttribute('data-density', value)
  writeStorage(STORAGE_KEYS.density, value)
}

function applyReduceTransparency(value: boolean) {
  document.documentElement.setAttribute('data-reduce-transparency', String(value))
  writeStorage(STORAGE_KEYS.reduceTransparency, String(value))
}

function applyGlassRefraction(value: boolean) {
  document.documentElement.setAttribute('data-glass-refraction', value ? 'on' : 'off')
  writeStorage(STORAGE_KEYS.glassRefraction, String(value))
}

function syncThemeFromDom() {
  const domTheme = document.documentElement.getAttribute('data-theme')
  if (domTheme === 'light' || domTheme === 'dark') {
    theme.value = domTheme
  }
}

// Apply on load
applyTheme(theme.value)
applyBrand(brand.value)
applyDensity(density.value)
applyReduceTransparency(reduceTransparency.value)
applyGlassRefraction(glassRefraction.value)

watch(theme, (t) => applyTheme(t))
watch(themeMode, (mode) => {
  writeStorage(STORAGE_KEYS.themeMode, mode)
  theme.value = resolveTheme(mode)
})
watch(brand, (value) => applyBrand(value))
watch(density, (value) => applyDensity(value))
watch(reduceTransparency, (value) => applyReduceTransparency(value))
watch(glassRefraction, (value) => applyGlassRefraction(value))

const themeAttributeObserver = new MutationObserver(syncThemeFromDom)
themeAttributeObserver.observe(document.documentElement, {
  attributes: true,
  attributeFilter: ['data-theme'],
})

if (typeof window !== 'undefined' && typeof window.matchMedia === 'function') {
  const systemScheme = window.matchMedia('(prefers-color-scheme: dark)')
  const onSystemSchemeChange = () => {
    if (themeMode.value === 'system') theme.value = resolveTheme('system')
  }
  if (typeof systemScheme.addEventListener === 'function') {
    systemScheme.addEventListener('change', onSystemSchemeChange)
  }
}

export function useTheme() {
  function toggleTheme() {
    themeMode.value = theme.value === 'dark' ? 'light' : 'dark'
  }

  function setThemeMode(value: ThemeMode) {
    themeMode.value = value
  }

  function setBrand(value: BrandTheme) {
    brand.value = value
  }

  function setDensity(value: Density) {
    density.value = value
  }

  function setReduceTransparency(value: boolean) {
    reduceTransparency.value = value
  }

  function setGlassRefraction(value: boolean) {
    glassRefraction.value = value
  }

  return {
    theme,
    themeMode,
    brand,
    density,
    reduceTransparency,
    glassRefraction,
    brandOptions,
    themeModeOptions,
    densityOptions,
    toggleTheme,
    setThemeMode,
    setBrand,
    setDensity,
    setReduceTransparency,
    setGlassRefraction,
  }
}

/**
 * 深色模式入口：全站页面完成 token 迁移并通过深色走查后开放（见 docs/plans/液态玻璃UI改造实施记录.md）。
 * 默认仍为浅色；若后续新页面未适配深色，可临时置回 false 关闭外观面板中的明暗选项。
 */
export const DARK_THEME_ENTRY_READY = true
