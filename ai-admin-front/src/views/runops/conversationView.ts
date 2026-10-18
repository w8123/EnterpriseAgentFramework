import type { ConversationRun, ConversationSummary, ConversationTurn } from '@/types/runops'

export type TurnTone = 'ok' | 'failed' | 'waiting' | 'running' | 'unknown'

export interface TurnState {
  tone: TurnTone
  label: string
}

/** 一轮对话的结论：以运行状态为准，没有运行时按是否有回答判断。 */
export function turnState(turn: ConversationTurn, runsAvailable: boolean): TurnState {
  const status = turn.run?.status
  if (status === 'FAILED') return { tone: 'failed', label: '运行失败' }
  if (status === 'TIMED_OUT') return { tone: 'failed', label: '运行超时' }
  if (status === 'CANCELLED') return { tone: 'failed', label: '已取消' }
  if (status === 'SUSPENDED') {
    return { tone: 'waiting', label: turn.run?.suspensionReason === 'APPROVAL' ? '等待审批' : '等待用户补充' }
  }
  if (status === 'RUNNING') return { tone: 'running', label: '运行中' }
  if (status === 'COMPLETED') return { tone: 'ok', label: '已完成' }
  if (turn.answer) return { tone: 'ok', label: '已回答' }
  if (!runsAvailable) return { tone: 'unknown', label: '运行状态暂不可用' }
  return { tone: 'failed', label: '没有回答' }
}

export function conversationHasProblem(conversation: ConversationSummary): boolean {
  return (conversation.failedRunCount ?? 0) > 0 || (conversation.openRunCount ?? 0) > 0
}

/** 对话列表里的状态徽标。 */
export function conversationBadge(conversation: ConversationSummary): { label: string; type: 'danger' | 'warning' | 'success' | 'info' } {
  if ((conversation.failedRunCount ?? 0) > 0) return { label: `${conversation.failedRunCount} 轮失败`, type: 'danger' }
  if ((conversation.openRunCount ?? 0) > 0) return { label: '有未完成', type: 'warning' }
  if (conversation.runCount == null) return { label: '状态未知', type: 'info' }
  return { label: '正常', type: 'success' }
}

/** 第一轮出问题的对话，进入详情时自动定位。 */
export function firstProblemTurn(turns: ConversationTurn[], runsAvailable: boolean): ConversationTurn | undefined {
  return turns.find(turn => {
    const tone = turnState(turn, runsAvailable).tone
    return tone === 'failed' || tone === 'waiting'
  })
}

/** 运行记录出于脱敏不保存原始错误正文（显示为 [omitted]），这里按错误码给运营一句能看懂的说明。 */
const RUN_ERROR_HINTS: Record<string, string> = {
  MODEL_PROVIDER_REQUEST_FAILED: '模型服务请求失败（网络中断或模型供应商异常），可稍后重试',
  MODEL_PROVIDER_BALANCE_INSUFFICIENT: '模型供应商账户余额不足',
  TIMED_OUT: '运行超时',
  SUPERVISOR_WORKFLOW_TIMEOUT: '调用的 Workflow 超时',
  SUPERVISOR_PLAN_INCOMPLETE: 'Agent 没能完成规划',
  SUPERVISOR_FINAL_ANSWER_REQUIRED: 'Agent 没有给出最终回答',
  SUPERVISOR_ACTION_REJECTED: '操作被拒绝',
  SUPERVISOR_CANCELLED: '运行被取消',
  WORKFLOW_FAILED: '调用的 Workflow 执行失败',
  RUNTIME_AGENT_CONFIG_NOT_PUBLISHED: 'Agent 没有已发布的配置版本',
  AGENT_CONFIG_VERSION_MISSING: 'Agent 配置版本不存在',
}

export function runErrorHint(code?: string | null): string {
  if (!code) return ''
  if (RUN_ERROR_HINTS[code]) return RUN_ERROR_HINTS[code]
  if (code.startsWith('MODEL_')) return '模型调用失败'
  if (code.startsWith('GUARD_') || code.includes('DENIED')) return '被调用权限或安全策略拦截'
  return ''
}

function readableErrorMessage(message?: string | null): string {
  const text = message?.trim()
  if (!text || text === '[omitted]' || /^\[rejected:[^\]]*\]$/.test(text)) return ''
  return text
}

export function runErrorText(run?: ConversationRun | null): string {
  if (!run) return ''
  const detail = readableErrorMessage(run.errorMessage) || runErrorHint(run.errorCode)
  return [run.errorCode, detail].filter(Boolean).join('：')
}

/** 业务用户显示：有昵称时「昵称（业务系统用户 ID）」，否则只显示 ID。 */
export function conversationUserLabel(conversation: Pick<ConversationSummary, 'externalUserId' | 'externalUserName'>): string {
  const id = conversation.externalUserId?.trim()
  const name = conversation.externalUserName?.trim()
  if (name && id && name !== id) return `${name}（${id}）`
  return name || id || '未知用户'
}
