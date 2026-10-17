import { controlRequest } from './request'
import type {
  EvalOpsDatasetDetail,
  EvalOpsDatasetItemInput,
  EvalOpsDatasetSummary,
  EvalOpsDatasetVersion,
  EvalOpsExperimentCreateRequest,
  EvalOpsExperimentDetail,
  EvalOpsExperimentSummary,
} from '@/types/agentEval'
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

export function cancelEvalOpsExperiment(experimentId: number) {
  return controlRequest.post<EvalOpsExperimentDetail>(
    `/api/runtime/evals/v2/experiments/${experimentId}/cancel`,
  )
}
