import { ref, watch } from 'vue'

export type Theme = 'dark' | 'light'
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

function isBrandTheme(value: string | null): value is BrandTheme {
  return brandOptions.some((option) => option.value === value)
}

const storedBrand = localStorage.getItem('brandTheme')
// 月隐模式尚未完成，光影模式暂时锁定在浅色。
const lockedTheme: Theme = 'light'
const theme = ref<Theme>(lockedTheme)
const brand = ref<BrandTheme>(isBrandTheme(storedBrand) ? storedBrand : 'tech-purple')

function applyTheme(t: Theme) {
  const html = document.documentElement
  html.setAttribute('data-theme', t)
  if (t === 'dark') {
    html.classList.add('dark')
  } else {
    html.classList.remove('dark')
  }
  localStorage.setItem('theme', t)
}

function applyBrand(value: BrandTheme) {
  const html = document.documentElement
  html.setAttribute('data-brand', value)
  localStorage.setItem('brandTheme', value)
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

watch(theme, (t) => applyTheme(t))
watch(brand, (value) => applyBrand(value))

const themeAttributeObserver = new MutationObserver(syncThemeFromDom)
themeAttributeObserver.observe(document.documentElement, {
  attributes: true,
  attributeFilter: ['data-theme'],
})

export function useTheme() {
  function toggleTheme() {
    theme.value = lockedTheme
  }

  function setBrand(value: BrandTheme) {
    brand.value = value
  }

  return { theme, brand, brandOptions, toggleTheme, setBrand }
}
