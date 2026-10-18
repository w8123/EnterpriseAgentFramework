import type { EvalOpsDatasetSummary, EvalOpsExperimentSummary } from '@/types/agentEval'
import type { Agent } from '@/types/workflow'

export interface EvalAgentRow {
  agentId: string
  agentName: string
  projectCode: string
  datasetCount: number
  experimentCount: number
  latestExperiment?: EvalOpsExperimentSummary
}

type Tone = 'success' | 'warning' | 'danger' | 'info' | 'primary'

const EXPERIMENT_STATUS: Record<string, { label: string; tone: Tone }> = {
  QUEUED: { label: '排队中', tone: 'info' },
  RUNNING: { label: '运行中', tone: 'primary' },
  COMPLETED: { label: '已完成', tone: 'success' },
  PARTIAL: { label: '部分完成', tone: 'warning' },
  CANCEL_REQUESTED: { label: '取消中', tone: 'warning' },
  CANCELLED: { label: '已取消', tone: 'info' },
}

const GATE_STATUS: Record<string, { label: string; tone: Tone }> = {
  PASSED: { label: '可发布', tone: 'success' },
  FAILED: { label: '不建议发布', tone: 'danger' },
  PENDING: { label: '待出结论', tone: 'info' },
  NOT_EVALUATED: { label: '未评估', tone: 'info' },
}

export function experimentStatusView(status: string | null | undefined) {
  return EXPERIMENT_STATUS[status ?? ''] ?? { label: status || '-', tone: 'info' as Tone }
}

export function gateStatusView(status: string | null | undefined) {
  return GATE_STATUS[status ?? ''] ?? { label: status || '-', tone: 'info' as Tone }
}

function experimentTime(experiment: EvalOpsExperimentSummary): string {
  return experiment.createdAt || experiment.startedAt || ''
}

export function sortExperimentsByRecent(experiments: EvalOpsExperimentSummary[]): EvalOpsExperimentSummary[] {
  return [...experiments].sort((left, right) => (
    experimentTime(right).localeCompare(experimentTime(left)) || right.id - left.id
  ))
}

/**
 * 按 Agent 汇总评测资产。只列出已有数据集或实验的 Agent；
 * 没有评测的 Agent 通过「新建评测」进入。
 */
export function buildEvalAgentRows(
  agents: Agent[],
  datasets: EvalOpsDatasetSummary[],
  experiments: EvalOpsExperimentSummary[],
): EvalAgentRow[] {
  const agentById = new Map(agents.map(agent => [agent.id, agent]))
  const rows = new Map<string, EvalAgentRow>()
  const rowFor = (agentId: string, projectCode?: string | null): EvalAgentRow => {
    let row = rows.get(agentId)
    if (!row) {
      const agent = agentById.get(agentId)
      row = {
        agentId,
        agentName: agent?.name || agent?.keySlug || agentId,
        projectCode: agent?.projectCode || projectCode || '',
        datasetCount: 0,
        experimentCount: 0,
      }
      rows.set(agentId, row)
    }
    return row
  }
  for (const dataset of datasets) {
    if (dataset.targetType !== 'AGENT' || !dataset.targetId) continue
    rowFor(dataset.targetId, dataset.projectCode).datasetCount += 1
  }
  for (const experiment of sortExperimentsByRecent(experiments)) {
    if (experiment.targetType !== 'AGENT' || !experiment.targetId) continue
    const row = rowFor(experiment.targetId, experiment.projectCode)
    row.experimentCount += 1
    row.latestExperiment ??= experiment
  }
  return [...rows.values()].sort((left, right) => (
    (right.latestExperiment ? experimentTime(right.latestExperiment) : '')
      .localeCompare(left.latestExperiment ? experimentTime(left.latestExperiment) : '')
    || left.agentName.localeCompare(right.agentName)
  ))
}
