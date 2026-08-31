import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

describe('Dashboard 编辑态组件外壳布局契约', () => {
  it('把组件限制在网格占位内，避免标题栏把外壳撑到下一排', () => {
    const source = readFileSync(
      resolve(__dirname, 'components/DashboardWidgetFrame.vue'),
      'utf8',
    )
    const editingRule = source.match(/\.dash-frame\.is-editing\s*\{([^}]*)\}/)?.[1] ?? ''

    expect(editingRule).toMatch(/^\s*height:\s*100%;/m)
    expect(editingRule).toMatch(/^\s*min-height:\s*0;/m)
    expect(editingRule).toMatch(/^\s*max-height:\s*100%;/m)
    expect(editingRule).toMatch(/^\s*overflow:\s*hidden;/m)
    expect(editingRule).not.toMatch(/^\s*min-height:\s*100%;/m)
  })
})
