import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import {
  businessSsoPresets,
  headerLines,
  parseHeaderLines,
} from '@/views/settings/components/businessSsoPresets'
import { businessLoginStartUrl } from '@/api/platformAuth'

describe('business-system one-click login', () => {
  it('fills the 芋道 OAuth2 paths from the system address', () => {
    const values = businessSsoPresets.yudao.fill('https://yudao-test.w8123.com/')
    expect(values.authorizeUrl).toBe('https://yudao-test.w8123.com/sso')
    expect(values.tokenUrl).toBe('https://yudao-test.w8123.com/admin-api/system/oauth2/token')
    expect(values.userInfoUrl).toBe('https://yudao-test.w8123.com/admin-api/system/oauth2/user/get')
    expect(values.userIdPath).toBe('data.id')
    expect(values.projectCode).toBe('yudao-erp')
  })

  it('round-trips extra headers and rejects malformed lines', () => {
    const parsed = parseHeaderLines('tenant-id: 1\n\nX-Env:  test ')
    expect(parsed).toEqual({ 'tenant-id': '1', 'X-Env': 'test' })
    expect(parseHeaderLines(headerLines(parsed))).toEqual(parsed)
    expect(() => parseHeaderLines('no colon')).toThrow()
  })

  it('starts the sign-in through a full-page navigation to Control', () => {
    expect(businessLoginStartUrl('yudao')).toBe('/api/platform/auth/sso/yudao/start')
    const login = readFileSync(resolve(process.cwd(), 'src/views/Login.vue'), 'utf8')
    expect(login).toContain(':href="businessLoginStartUrl(item.code)"')
    expect(login).toContain('sso_error')
  })
})
