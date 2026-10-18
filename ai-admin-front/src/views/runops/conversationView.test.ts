import { describe, expect, it } from 'vitest'
import type { ConversationSummary, ConversationTurn } from '@/types/runops'
import { conversationBadge, conversationUserLabel, firstProblemTurn, runErrorText, turnState } from './conversationView'

const turn = (patch: Partial<ConversationTurn>): ConversationTurn => ({ turnNo: 1, question: '库存多少？', ...patch })
const summary = (patch: Partial<ConversationSummary>): ConversationSummary => ({ sessionId: 's', questionCount: 1, ...patch })

describe('conversation view', () => {
  it('judges a turn by its run first', () => {
    expect(turnState(turn({ answer: '12 台', run: { traceId: 't', status: 'COMPLETED' } }), true).tone).toBe('ok')
    expect(turnState(turn({ run: { traceId: 't', status: 'FAILED' } }), true).label).toBe('运行失败')
    expect(turnState(turn({ run: { traceId: 't', status: 'SUSPENDED', suspensionReason: 'APPROVAL' } }), true).label).toBe('等待审批')
  })

  it('treats an unanswered question as failed only when run data is available', () => {
    expect(turnState(turn({}), true).tone).toBe('failed')
    expect(turnState(turn({}), false).tone).toBe('unknown')
    expect(turnState(turn({ answer: '你好' }), false).tone).toBe('ok')
  })

  it('finds the first turn to look at', () => {
    const turns = [
      turn({ turnNo: 1, answer: 'ok', run: { traceId: 'a', status: 'COMPLETED' } }),
      turn({ turnNo: 2, run: { traceId: 'b', status: 'FAILED' } }),
    ]
    expect(firstProblemTurn(turns, true)?.turnNo).toBe(2)
  })

  it('summarizes a conversation in the list', () => {
    expect(conversationBadge(summary({ runCount: 3, failedRunCount: 2 })).label).toBe('2 轮失败')
    expect(conversationBadge(summary({ runCount: 3, failedRunCount: 0, openRunCount: 1 })).type).toBe('warning')
    expect(conversationBadge(summary({})).label).toBe('状态未知')
    expect(runErrorText({ traceId: 't', errorCode: 'TOOL_ERROR', errorMessage: '超时' })).toBe('TOOL_ERROR：超时')
    expect(runErrorText({ traceId: 't', errorCode: 'MODEL_PROVIDER_REQUEST_FAILED', errorMessage: '[omitted]' }))
      .toBe('MODEL_PROVIDER_REQUEST_FAILED：模型服务请求失败（网络中断或模型供应商异常），可稍后重试')
    expect(runErrorText({ traceId: 't', errorCode: 'SOMETHING_ELSE', errorMessage: '[rejected:SOMETHING_ELSE]' })).toBe('SOMETHING_ELSE')
  })

  it('shows the business user name next to the external id', () => {
    expect(conversationUserLabel({ externalUserId: '1', externalUserName: '芋道管理员' })).toBe('芋道管理员（1）')
    expect(conversationUserLabel({ externalUserId: '1', externalUserName: null })).toBe('1')
    expect(conversationUserLabel({ externalUserId: null, externalUserName: null })).toBe('未知用户')
  })
})
