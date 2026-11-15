import type { InjectionKey } from 'vue'
import type { ApprovalStatusView } from '../transports/transportTypes'

export type ApprovalStatusFetcher = (interactionId: string) => Promise<ApprovalStatusView | null>

/** ConversationView 提供、等待审批卡片使用：查询「指定审批人」的审批进度。 */
export const APPROVAL_STATUS_FETCHER: InjectionKey<() => ApprovalStatusFetcher | undefined> = Symbol('reachai-approval-status')
