import { controlRequest, modelRequest } from '@/api/request'
import type { McpEmployeeConnectionPage, McpItemPageView, McpPublication } from '@/types/mcp'
import type { ModelInstance } from '@/types/model'
import type { ScanProject } from '@/types/scanProject'

/** 首次引导的四步：让员工能用自己的 AI 工具办业务。 */
export type GettingStartedStepKey = 'model' | 'system' | 'publish' | 'employee'

/** done=已完成，next=建议现在做，todo=还没做，unknown=当前账号看不到这项数据。 */
export type GettingStartedStepState = 'done' | 'next' | 'todo' | 'unknown'

export interface GettingStartedFacts {
  /** null 表示读取失败或没有权限。 */
  models: ModelInstance[] | null
  projects: ScanProject[] | null
  publications: McpPublication[] | null
  activeEmployeeConnections: number | null
}

export interface GettingStartedStep {
  key: GettingStartedStepKey
  order: number
  title: string
  description: string
  state: GettingStartedStepState
  /** 已完成时的一句话摘要，例如「已有 2 个可用模型：DeepSeek…」。 */
  summary: string
  actionLabel: string
  actionPath: string
}

export interface GettingStartedProgress {
  steps: GettingStartedStep[]
  doneCount: number
  total: number
  allDone: boolean
  nextStep: GettingStartedStep | null
}

const STEP_COPY: Record<GettingStartedStepKey, Omit<GettingStartedStep, 'state' | 'summary' | 'order'>> = {
  model: {
    key: 'model',
    title: '连接一个大模型',
    description: '填入 DeepSeek、通义千问、OpenAI 等厂商的 API Key，AI 助手才能思考和回答。',
    actionLabel: '去连接模型',
    actionPath: '/model/instances',
  },
  system: {
    key: 'system',
    title: '接入你的业务系统',
    description: 'Java 系统引入 SDK 后自动同步接口；其他系统上传 OpenAPI 文档即可。',
    actionLabel: '去接入系统',
    actionPath: '/registry/projects',
  },
  publish: {
    key: 'publish',
    title: '把业务能力开放给 AI 工具',
    description: '挑选允许 AI 调用的能力，发布成一个工具集，写操作默认需要审批。',
    actionLabel: '去发布工具集',
    actionPath: '/mcp-hub/publications',
  },
  employee: {
    key: 'employee',
    title: '让员工连上自己的 AI 工具',
    description: '员工在业务系统的 AI 助手里点「在我的 AI 工具中使用」，复制一行到 Claude Code 或 Codex。',
    actionLabel: '查看员工连接',
    actionPath: '/mcp-hub/employee-connections',
  },
}

const ORDER: GettingStartedStepKey[] = ['model', 'system', 'publish', 'employee']

function namesPreview(names: string[], limit = 2): string {
  const shown = names.slice(0, limit).join('、')
  return names.length > limit ? `${shown} 等` : shown
}

function stepFacts(key: GettingStartedStepKey, facts: GettingStartedFacts): { done: boolean | null; summary: string } {
  switch (key) {
    case 'model': {
      if (!facts.models) return { done: null, summary: '' }
      const usable = facts.models.filter((item) => item.status === 'ACTIVE' && item.modelType === 'LLM')
      return {
        done: usable.length > 0,
        summary: usable.length ? `已有 ${usable.length} 个可用模型：${namesPreview(usable.map((item) => item.name))}` : '',
      }
    }
    case 'system': {
      if (!facts.projects) return { done: null, summary: '' }
      return {
        done: facts.projects.length > 0,
        summary: facts.projects.length
          ? `已接入 ${facts.projects.length} 个系统：${namesPreview(facts.projects.map((item) => item.name))}`
          : '',
      }
    }
    case 'publish': {
      if (!facts.publications) return { done: null, summary: '' }
      const published = facts.publications.filter((item) => item.state === 'PUBLISHED')
      return {
        done: published.length > 0,
        summary: published.length ? `已发布 ${published.length} 个工具集：${namesPreview(published.map((item) => item.name))}` : '',
      }
    }
    case 'employee': {
      if (facts.activeEmployeeConnections === null) return { done: null, summary: '' }
      return {
        done: facts.activeEmployeeConnections > 0,
        summary: facts.activeEmployeeConnections > 0 ? `${facts.activeEmployeeConnections} 个员工连接正在使用` : '',
      }
    }
  }
}

export function buildGettingStartedProgress(facts: GettingStartedFacts): GettingStartedProgress {
  let nextAssigned = false
  const steps = ORDER.map((key, index) => {
    const { done, summary } = stepFacts(key, facts)
    let state: GettingStartedStepState
    if (done === null) state = 'unknown'
    else if (done) state = 'done'
    else if (!nextAssigned) {
      state = 'next'
      nextAssigned = true
    } else state = 'todo'
    return { ...STEP_COPY[key], order: index + 1, state, summary }
  })
  const doneCount = steps.filter((step) => step.state === 'done').length
  return {
    steps,
    doneCount,
    total: steps.length,
    allDone: doneCount === steps.length,
    nextStep: steps.find((step) => step.state === 'next') ?? null,
  }
}

function listPayload<T>(payload: unknown): T[] | null {
  if (Array.isArray(payload)) return payload as T[]
  if (payload && typeof payload === 'object') {
    const record = payload as { data?: unknown; items?: unknown }
    if (Array.isArray(record.items)) return record.items as T[]
    if (Array.isArray(record.data)) return record.data as T[]
  }
  return null
}

async function settle<T>(load: () => Promise<T>): Promise<T | null> {
  try {
    return await load()
  } catch {
    return null
  }
}

/** 并行读取四步需要的事实；某一项失败只让那一步显示「暂时看不到」，不影响其他步骤。 */
export async function loadGettingStartedFacts(): Promise<GettingStartedFacts> {
  const quiet = { errorFeedback: 'local' as const }
  const [models, projects, publications, connections] = await Promise.all([
    settle(async () => listPayload<ModelInstance>((await modelRequest.get('/instances', quiet)).data)),
    settle(async () => listPayload<ScanProject>((await controlRequest.get('/api/scan-projects', quiet)).data)),
    settle(async () => listPayload<McpPublication>(
      (await controlRequest.get<McpItemPageView<McpPublication>>('/api/mcp/publications', { ...quiet, params: { limit: 50 } })).data,
    )),
    settle(async () => {
      const page = (await controlRequest.get<McpEmployeeConnectionPage>('/api/mcp/employee-connections', {
        ...quiet,
        params: { state: 'ACTIVE', limit: 1 },
      })).data
      return typeof page?.total === 'number' ? page.total : null
    }),
  ])
  return { models, projects, publications, activeEmployeeConnections: connections }
}

export type SystemCheckStatus = 'OK' | 'WARN' | 'ERROR' | 'OFF'

export interface SystemCheckItem {
  key: string
  group: 'services' | 'security' | 'optional' | string
  title: string
  status: SystemCheckStatus
  detail: string
  fix?: string
  link?: string
}

export interface SystemCheckReport {
  checkedAt: string
  summary: Record<SystemCheckStatus, number>
  items: SystemCheckItem[]
}

export function getSystemCheck() {
  return controlRequest.get<SystemCheckReport>('/api/platform/system-check', { errorFeedback: 'local' })
}

export const SYSTEM_CHECK_GROUPS: { key: string; title: string }[] = [
  { key: 'services', title: '服务' },
  { key: 'security', title: '安全' },
  { key: 'optional', title: '可选组件' },
]
