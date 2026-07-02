import { controlRequest } from './request'
import type {
  AgentEvalCase,
  AgentEvalCaseResult,
  AgentEvalDataset,
  AgentEvalDatasetImportRequest,
  AgentEvalRun,
  AgentEvalRunRequest,
  AgentEvalRunView,
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
