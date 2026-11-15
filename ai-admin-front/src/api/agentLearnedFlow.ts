import { controlRequest } from './request'

/** 助手多次通过推理完成的同一类只读请求，可以沉淀为直达流程。 */
export interface LearnedFlowCandidate {
  sourceRef: string
  title: string
  runCount: number
  avgLatencyMs: number
  lastAt?: string | null
  workflowId?: string | null
  workflowStatus?: string | null
  attached: boolean
}

export interface LearnedFlowResult {
  workflowId: string
  workflowKeySlug: string
  workflowName: string
  workflowStatus: string
  created: boolean
  attached: boolean
  toolName?: string | null
  agentConfigVersion?: number | null
  title: string
}

export function listLearnedFlows(agentId: string, days = 30) {
  return controlRequest.get<LearnedFlowCandidate[]>(
    `/api/agents/${encodeURIComponent(agentId)}/learned-flows`, { params: { days } })
}

/** 生成流程草稿：只生成，不对员工生效。 */
export function draftLearnedFlow(agentId: string, sourceRef: string) {
  return controlRequest.post<LearnedFlowResult>(
    `/api/agents/${encodeURIComponent(agentId)}/learned-flows/draft`, { sourceRef }, { timeout: 60000 })
}

/** 人工确认启用：发布流程并加入助手。 */
export function enableLearnedFlow(agentId: string, sourceRef: string, publishedBy?: string) {
  return controlRequest.post<LearnedFlowResult>(
    `/api/agents/${encodeURIComponent(agentId)}/learned-flows/enable`, { sourceRef, publishedBy }, { timeout: 60000 })
}

export interface PendingLearnedFlow {
  agentId: string
  agentName: string
  projectCode: string
  candidate: LearnedFlowCandidate
}

/** 待确认流程：所有助手里重复出现、还没变成流程的只读请求。 */
export function listPendingLearnedFlows(params: { projectCode?: string; days?: number }) {
  return controlRequest.get<PendingLearnedFlow[]>('/api/learned-flows/pending', { params, errorFeedback: 'local' })
}
