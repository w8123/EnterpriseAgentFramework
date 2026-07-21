/**
 * ReachAI Embed Chat — 官方 7 色主题预设。
 * 与管理端 data-brand / --brand-primary 对齐；光谱由 conversation-tokens 内 color-mix 派生。
 * 接入方或 AI Coding 工具对照业务系统主色后选择最接近的 preset，禁止扫描 DOM 猜色。
 */

export const EAF_CHAT_THEME_PRESETS = {
  'tech-purple': { primary: '#6366f1', rgb: '99 102 241' },
  'metro-green': { primary: '#0b7a59', rgb: '11 122 89' },
  'aurora-cyan': { primary: '#0891b2', rgb: '8 145 178' },
  'nebula-violet': { primary: '#7c3aed', rgb: '124 58 237' },
  'coral-rose': { primary: '#db2777', rgb: '219 39 119' },
  'solar-gold': { primary: '#b45309', rgb: '180 83 9' },
  'deep-ocean': { primary: '#1d4ed8', rgb: '29 78 216' },
} as const

/** 与管理端 data-brand 对齐的官方 7 色预设名 */
export type EafChatThemePreset =
  | 'tech-purple'
  | 'metro-green'
  | 'aurora-cyan'
  | 'nebula-violet'
  | 'coral-rose'
  | 'solar-gold'
  | 'deep-ocean'
export const EAF_CHAT_DEFAULT_THEME_PRESET: EafChatThemePreset = 'tech-purple'

export function resolveEafChatThemePrimary(input?: {
  preset?: EafChatThemePreset | string | null
  primaryColor?: string | null
}): { primary: string; rgb: string; source: 'primaryColor' | 'preset' | 'default' } {
  const explicit = String(input?.primaryColor || '').trim()
  if (explicit) {
    return {
      primary: explicit,
      rgb: parseCssColorToRgbTriplet(explicit) || EAF_CHAT_THEME_PRESETS[EAF_CHAT_DEFAULT_THEME_PRESET].rgb,
      source: 'primaryColor',
    }
  }

  const presetKey = String(input?.preset || '').trim() as EafChatThemePreset
  if (presetKey && presetKey in EAF_CHAT_THEME_PRESETS) {
    const preset = EAF_CHAT_THEME_PRESETS[presetKey]
    return { primary: preset.primary, rgb: preset.rgb, source: 'preset' }
  }

  const fallback = EAF_CHAT_THEME_PRESETS[EAF_CHAT_DEFAULT_THEME_PRESET]
  return { primary: fallback.primary, rgb: fallback.rgb, source: 'default' }
}

export function parseCssColorToRgbTriplet(input: string): string | null {
  const hex = input.match(/^#([0-9a-f]{3}|[0-9a-f]{6})$/i)
  if (hex) {
    const raw = hex[1]
    if (raw.length === 3) {
      return [
        parseInt(raw[0] + raw[0], 16),
        parseInt(raw[1] + raw[1], 16),
        parseInt(raw[2] + raw[2], 16),
      ].join(' ')
    }
    return [
      parseInt(raw.slice(0, 2), 16),
      parseInt(raw.slice(2, 4), 16),
      parseInt(raw.slice(4, 6), 16),
    ].join(' ')
  }
  const rgb = input.match(/^rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)/i)
  if (rgb) {
    return `${Number(rgb[1])} ${Number(rgb[2])} ${Number(rgb[3])}`
  }
  return null
}
