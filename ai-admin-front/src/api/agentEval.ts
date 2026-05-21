import { agentRequest } from './request'
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
  return agentRequest.get<AgentEvalDataset[]>('/api/agent/evals/datasets', { params })
}

export function createEvalDataset(payload: AgentEvalDatasetImportRequest) {
  return agentRequest.post<AgentEvalDataset>('/api/agent/evals/datasets', payload)
}

export function importEvalCases(datasetId: number, payload: AgentEvalDatasetImportRequest) {
  return agentRequest.post<AgentEvalDataset>(`/api/agent/evals/datasets/${datasetId}/cases/import`, payload)
}

export function listEvalCases(datasetId: number) {
  return agentRequest.get<AgentEvalCase[]>(`/api/agent/evals/datasets/${datasetId}/cases`)
}

export function startEvalRun(payload: AgentEvalRunRequest) {
  return agentRequest.post<AgentEvalRunView>('/api/agent/evals/runs', payload)
}

export function getEvalRun(runId: number) {
  return agentRequest.get<AgentEvalRun>(`/api/agent/evals/runs/${runId}`)
}

export function listEvalRunResults(runId: number) {
  return agentRequest.get<AgentEvalCaseResult[]>(`/api/agent/evals/runs/${runId}/results`)
}
