import { describe, expect, it } from 'vitest'
import { renderSafeMarkdown } from './safeMarkdown'

describe('business documentation Markdown', () => {
  it('preserves headings, code, tables and ordinary links', () => {
    const html = renderSafeMarkdown('# 业务说明\n\n`字段`\n\n| 字段 | 含义 |\n| --- | --- |\n| id | 标识 |\n\n[说明](https://example.com/docs)')
    expect(html).toContain('<h1>业务说明</h1>')
    expect(html).toContain('<code>字段</code>')
    expect(html).toContain('<table>')
    expect(html).toContain('href="https://example.com/docs"')
  })
  it('rejects source/AI active HTML, event handlers and dangerous URL schemes', () => {
    const html = renderSafeMarkdown('<script>window.secret()</script><svg onload="run()"></svg>\n\n<img src="https://example.com/a.png" onerror="run()"><a href="javascript:run()" onclick="run()">链接</a>\n\n[危险](java&#x73;cript:run())')
    expect(html).not.toMatch(/<script|<svg|onerror|onclick|onload|href="javascript/i)
    expect(html).toContain('https://example.com/a.png')
    expect(html).toContain('链接')
  })
})
