import { controlRequest } from './request'

/** 每个业务系统的自动评测设置。 */
export interface ProjectEvalSetting {
  projectCode: string
  tenantId?: string | null
  agentId?: string | null
  evalUserId?: string | null
  enabled: boolean
  runHour: number
  questionCount: number
  datasetId?: number | null
  lastGeneratedAt?: string | null
  lastExperimentId?: number | null
  lastRunAt?: string | null
  autoEnableReadonlyFlows: boolean
  updatedBy?: string | null
  updatedAt?: string | null
  /** 只在出题接口返回：这次出了几道题、多少道引用了真实数据。 */
  generationNote?: string | null
}

export interface ProjectEvalRun {
  experimentId: number
  status: string
  taskCount?: number | null
  completedTaskCount?: number | null
  passRate?: number | null
  averageScore?: number | null
  p50LatencyMs?: number | null
  p95LatencyMs?: number | null
  createdBy?: string | null
  createdAt?: string | null
  finishedAt?: string | null
}

export interface ProjectEvalFailure {
  question?: string | null
  reason?: string | null
  elapsedMs?: number | null
  traceId?: string | null
  answer?: string | null
}

export interface ProjectEvalSummary {
  setting: ProjectEvalSetting
  runs: ProjectEvalRun[]
  failures: ProjectEvalFailure[]
}

export interface ProjectEvalOverview {
  workerEnabled: boolean
  projects: ProjectEvalSummary[]
}

const ROOT = '/api/runtime/project-evals'

export function getProjectEvalOverview(projectCode?: string) {
  return controlRequest.get<ProjectEvalOverview>(ROOT, { params: { projectCode }, errorFeedback: 'local' })
}

export function getProjectEvalSetting(projectCode: string) {
  return controlRequest.get<ProjectEvalSetting>(`${ROOT}/${encodeURIComponent(projectCode)}/setting`, {
    errorFeedback: 'local',
  })
}

export function saveProjectEvalSetting(projectCode: string, data: Partial<ProjectEvalSetting>) {
  return controlRequest.put<ProjectEvalSetting>(`${ROOT}/${encodeURIComponent(projectCode)}/setting`, data, {
    errorFeedback: 'local',
  })
}

/** 给这个系统配上平台自带的只读评测账号（不依赖业务系统登录，只能调用只读能力），并设为评测账号。 */
export function applyPlatformEvalAccount(projectCode: string) {
  return controlRequest.post<ProjectEvalSetting>(`${ROOT}/${encodeURIComponent(projectCode)}/platform-account`, {}, {
    errorFeedback: 'local',
  })
}

/** 让助手的模型按系统的只读能力重新出题，几十秒。 */
export function generateProjectEvalQuestions(projectCode: string) {
  return controlRequest.post<ProjectEvalSetting>(`${ROOT}/${encodeURIComponent(projectCode)}/questions`, {}, {
    errorFeedback: 'local',
    timeout: 180000,
  })
}

export function runProjectEval(projectCode: string) {
  return controlRequest.post<ProjectEvalSetting>(`${ROOT}/${encodeURIComponent(projectCode)}/runs`, {}, {
    errorFeedback: 'local',
    timeout: 180000,
  })
}
