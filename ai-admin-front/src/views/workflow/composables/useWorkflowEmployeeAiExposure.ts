import { computed, reactive, ref, watch, type Ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  addMcpPublicationItem,
  getMcpPublication,
  listMcpPublications,
  precheckMcpPublication,
  publishMcpPublication,
} from '@/api/mcp'
import type { McpPublication, McpRiskLevel } from '@/types/mcp'
import type { CanvasNode } from '@/types/studio'

export type ExposureRiskLevel = Exclude<McpRiskLevel, 'UNKNOWN'>

export interface EmployeeAiExposureForm {
  enabled: boolean
  publicationId: number | null
  riskLevel: ExposureRiskLevel
  description: string
}

/**
 * Suggested risk for a workflow exposed over MCP. Anything that can change a business system counts as WRITE,
 * so employee calls go through write approval unless the author knowingly lowers it.
 */
export function suggestWorkflowRiskLevel(nodes: CanvasNode[]): ExposureRiskLevel {
  const kinds = new Set(nodes.map((node) => node.data.kind))
  if (kinds.has('pageAction')) return 'PAGE_ACTION'
  if (kinds.has('tool') || kinds.has('http') || kinds.has('knowledgeWrite') || kinds.has('subWorkflow') || kinds.has('approval')) {
    return 'WRITE'
  }
  return 'READ'
}

/** Tool description employees' AI sees: workflow description plus the start form fields. */
export function workflowToolDescription(name: string, description: string | null | undefined, nodes: CanvasNode[]) {
  const input = nodes.find((node) => node.data.kind === 'userInput')
  const fields = (input?.data.userInputConfig?.fields || []).filter((field) => field.name?.trim())
  const lines = [description?.trim() || name]
  if (fields.length) {
    lines.push('参数：' + fields.map((field) => {
      const parts = [field.name.trim()]
      if (field.description?.trim()) parts.push(field.description.trim())
      if (field.required) parts.push('必填')
      return parts.join('，')
    }).join('；'))
  }
  return lines.join('\n')
}

export function useWorkflowEmployeeAiExposure(deps: {
  workflowId: Readonly<Ref<string>>
  workflowName: () => string
  workflowDescription: () => string | null | undefined
  nodes: Ref<CanvasNode[]>
  publishDialogOpen: Ref<boolean>
}) {
  const form = reactive<EmployeeAiExposureForm>({
    enabled: false,
    publicationId: null,
    riskLevel: 'READ',
    description: '',
  })
  const publications = ref<McpPublication[]>([])
  const loading = ref(false)
  const suggestedRisk = computed(() => suggestWorkflowRiskLevel(deps.nodes.value))

  async function loadPublications() {
    loading.value = true
    try {
      const { data } = await listMcpPublications({ limit: 100 })
      publications.value = (data?.items || []).filter((item) => item.state !== 'ARCHIVED')
      if (!form.publicationId && publications.value.length === 1) form.publicationId = publications.value[0].id
    } catch {
      publications.value = []
    } finally {
      loading.value = false
    }
  }

  watch(deps.publishDialogOpen, (open) => {
    if (!open) return
    form.riskLevel = suggestedRisk.value
    form.description = workflowToolDescription(deps.workflowName(), deps.workflowDescription(), deps.nodes.value)
    if (!publications.value.length) void loadPublications()
  })

  /** Runs after a workflow version is published. Never throws; failures become a message. */
  async function exposeAfterPublish(workflowId: string) {
    if (!form.enabled || !form.publicationId) return
    const publicationId = form.publicationId
    const name = publications.value.find((item) => item.id === publicationId)?.name || 'MCP 服务'
    try {
      const { data: detail } = await getMcpPublication(publicationId)
      const already = (detail?.items || []).some((item) => item.sourceKind === 'WORKFLOW' && item.sourceRef === workflowId)
      if (!already) {
        await addMcpPublicationItem(publicationId, {
          sourceKind: 'WORKFLOW',
          sourceRef: workflowId,
          descriptionOverride: form.description.trim() || null,
          riskLevelOverride: form.riskLevel,
        })
      }
      const { data: precheck } = await precheckMcpPublication(publicationId)
      if (!precheck?.ok) {
        ElMessage.warning(`已加入「${name}」，但发布前检查没通过，请到 MCP 服务里处理后再发布`)
        return
      }
      if (precheck.irreversibleToolCount > 0) {
        ElMessage.warning(`已加入「${name}」。这个服务里有不可逆工具，需要到 MCP 服务里确认后发布`)
        return
      }
      await publishMcpPublication(publicationId)
      ElMessage.success(`已开放给员工 AI 工具：「${name}」已发布新版本`)
    } catch (error) {
      ElMessage.error(`工作流已发布，但开放给员工 AI 工具失败：${(error as Error).message || '请到 MCP 服务里手动添加'}`)
    }
  }

  return { form, publications, loading, suggestedRisk, loadPublications, exposeAfterPublish }
}
