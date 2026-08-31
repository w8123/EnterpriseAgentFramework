import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const loginSource = readFileSync(
  resolve(process.cwd(), 'src/views/Login.vue'),
  'utf8',
)

describe('Login local development defaults', () => {
  it('prefills the built-in local administrator credentials', () => {
    expect(loginSource).toContain("username: 'admin'")
    expect(loginSource).toContain("password: 'admin123'")
    expect(loginSource).toContain('本地开发默认账号 <code>admin</code> / <code>admin123</code>，已为你预填。')
  })
})
