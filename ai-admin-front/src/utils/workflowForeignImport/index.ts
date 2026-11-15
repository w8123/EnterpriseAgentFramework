import { parse as parseYaml } from 'yaml'
import { convertDifyDsl, isDifyDsl } from './dify'
import { convertN8nWorkflow, isN8nWorkflow } from './n8n'
import type { ForeignImportResult } from './shared'

export type { ForeignImportReport, ForeignImportResult, ImportNote, ImportNoteLevel } from './shared'
export { convertDifyDsl, convertN8nWorkflow }

const MAX_BYTES = 5 * 1024 * 1024

/**
 * Reads a file exported from another workflow platform. Returns null when the content is not a Dify DSL or an
 * n8n workflow (the caller then tries the ReachAI format); throws a Chinese message when it is one but unusable.
 */
export function convertForeignWorkflow(content: string, fileName = ''): ForeignImportResult | null {
  if (content.length > MAX_BYTES) throw new Error('文件太大（超过 5MB），不像是工作流文件')
  let parsed: unknown
  const trimmed = content.trim()
  if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
    try {
      parsed = JSON.parse(trimmed)
    } catch {
      return null
    }
  } else if (/\.ya?ml$/i.test(fileName) || /^(app|kind|version|workflow)\s*:/m.test(trimmed)) {
    try {
      parsed = parseYaml(trimmed, { maxAliasCount: 100 })
    } catch {
      throw new Error('YAML 文件格式有误，请从 Dify 重新「导出 DSL」')
    }
  } else {
    return null
  }
  // n8n "copy nodes" puts a bare workflow object; some exports wrap it in an array.
  if (Array.isArray(parsed) && parsed.length === 1) parsed = parsed[0]
  if (isDifyDsl(parsed)) return convertDifyDsl(parsed)
  if (isN8nWorkflow(parsed)) return convertN8nWorkflow(parsed)
  if (/\.ya?ml$/i.test(fileName)) throw new Error('这个 YAML 不是 Dify 工作流 DSL（缺少 workflow.graph）')
  return null
}
