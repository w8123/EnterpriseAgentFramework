import { controlRequest } from './request'
import type {
  AgentEvalCase,
  AgentEvalCaseResult,
  AgentEvalDataset,
  AgentEvalDatasetImportRequest,
  AgentEvalRun,
  AgentEvalRunRequest,
  AgentEvalRunView,
  EvalOpsDatasetDetail,
  EvalOpsDatasetItemInput,
  EvalOpsDatasetSummary,
  EvalOpsDatasetVersion,
  EvalOpsExperimentCreateRequest,
  EvalOpsExperimentDetail,
  EvalOpsExperimentItemPage,
  EvalOpsExperimentSummary,
} from '@/types/agentEval'

export function listEvalDatasets(params?: { agentId?: string }) {
  return controlRequest.get<AgentEvalDataset[]>('/api/runtime/evals/datasets', { params })
}

export function createEvalDataset(payload: AgentEvalDatasetImportRequest) {
  return controlRequest.post<AgentEvalDataset>('/api/runtime/evals/datasets', payload)
}

export function importEvalCases(datasetId: number, payload: AgentEvalDatasetImportRequest) {
  return controlRequest.post<AgentEvalDataset>(`/api/runtime/evals/datasets/${datasetId}/cases/import`, payload)
}

export function listEvalCases(datasetId: number) {
  return controlRequest.get<AgentEvalCase[]>(`/api/runtime/evals/datasets/${datasetId}/cases`)
}

export function startEvalRun(payload: AgentEvalRunRequest) {
  return controlRequest.post<AgentEvalRunView>('/api/runtime/evals/runs', payload)
}

export function getEvalRun(runId: number) {
  return controlRequest.get<AgentEvalRun>(`/api/runtime/evals/runs/${runId}`)
}

export function listEvalRunResults(runId: number) {
  return controlRequest.get<AgentEvalCaseResult[]>(`/api/runtime/evals/runs/${runId}/results`)
}

export function listEvalOpsDatasets(params?: { targetId?: string }) {
  return controlRequest.get<EvalOpsDatasetSummary[]>('/api/runtime/evals/v2/datasets', { params })
}

export function createEvalOpsDataset(payload: {
  targetType: 'AGENT'
  targetId: string
  projectCode?: string
  name: string
  description?: string
  source?: string
  items: EvalOpsDatasetItemInput[]
}) {
  return controlRequest.post<EvalOpsDatasetDetail>('/api/runtime/evals/v2/datasets', payload)
}

export function getEvalOpsDataset(datasetId: number) {
  return controlRequest.get<EvalOpsDatasetDetail>(`/api/runtime/evals/v2/datasets/${datasetId}`)
}

export function createEvalOpsDatasetVersion(datasetId: number, payload: {
  changeNote?: string
  items: EvalOpsDatasetItemInput[]
}) {
  return controlRequest.post<EvalOpsDatasetVersion>(
    `/api/runtime/evals/v2/datasets/${datasetId}/versions`,
    payload,
  )
}

export function createEvalOpsDatasetVersionFromTrace(datasetId: number, payload: {
  traceId: string
  itemKey?: string
  message?: string
  input?: Record<string, unknown>
  expected?: Record<string, unknown>
  tags?: unknown
  changeNote?: string
}) {
  return controlRequest.post<EvalOpsDatasetVersion>(
    `/api/runtime/evals/v2/datasets/${datasetId}/versions/from-trace`,
    payload,
  )
}

export function listEvalOpsExperiments(params?: { targetId?: string }) {
  return controlRequest.get<EvalOpsExperimentSummary[]>('/api/runtime/evals/v2/experiments', { params })
}

export function createEvalOpsExperiment(payload: EvalOpsExperimentCreateRequest) {
  return controlRequest.post<EvalOpsExperimentDetail>('/api/runtime/evals/v2/experiments', payload)
}

export function getEvalOpsExperiment(experimentId: number) {
  return controlRequest.get<EvalOpsExperimentDetail>(`/api/runtime/evals/v2/experiments/${experimentId}`)
}

export function listEvalOpsExperimentItems(experimentId: number, params?: {
  variantId?: number
  page?: number
  pageSize?: number
}) {
  return controlRequest.get<EvalOpsExperimentItemPage>(
    `/api/runtime/evals/v2/experiments/${experimentId}/items`,
    { params },
  )
}

export function cancelEvalOpsExperiment(experimentId: number) {
  return controlRequest.post<EvalOpsExperimentDetail>(
    `/api/runtime/evals/v2/experiments/${experimentId}/cancel`,
  )
}
