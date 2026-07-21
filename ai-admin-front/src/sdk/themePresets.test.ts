import { describe, expect, it } from 'vitest'
import {
  EAF_CHAT_DEFAULT_THEME_PRESET,
  EAF_CHAT_THEME_PRESETS,
  resolveEafChatThemePrimary,
} from './themePresets'

describe('EAF chat theme presets', () => {
  it('exposes exactly the seven official brand presets aligned with admin data-brand', () => {
    expect(Object.keys(EAF_CHAT_THEME_PRESETS).sort()).toEqual([
      'aurora-cyan',
      'coral-rose',
      'deep-ocean',
      'metro-green',
      'nebula-violet',
      'solar-gold',
      'tech-purple',
    ])
    expect(EAF_CHAT_THEME_PRESETS['tech-purple'].primary).toBe('#6366f1')
    expect(EAF_CHAT_THEME_PRESETS['metro-green'].primary).toBe('#0b7a59')
    expect(EAF_CHAT_THEME_PRESETS['solar-gold'].primary).toBe('#b45309')
    expect(EAF_CHAT_DEFAULT_THEME_PRESET).toBe('tech-purple')
  })

  it('resolves primaryColor over preset over default', () => {
    expect(resolveEafChatThemePrimary({
      preset: 'metro-green',
      primaryColor: '#db2777',
    })).toEqual({
      primary: '#db2777',
      rgb: '219 39 119',
      source: 'primaryColor',
    })

    expect(resolveEafChatThemePrimary({ preset: 'metro-green' })).toEqual({
      primary: '#0b7a59',
      rgb: '11 122 89',
      source: 'preset',
    })

    expect(resolveEafChatThemePrimary({})).toEqual({
      primary: '#6366f1',
      rgb: '99 102 241',
      source: 'default',
    })

    expect(resolveEafChatThemePrimary({ preset: 'not-a-real-preset' as never }).source).toBe('default')
  })
})
