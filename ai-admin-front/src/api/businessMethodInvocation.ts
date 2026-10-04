import { controlRequest } from './request'
import type {
  BusinessMethodInvocationContext,
  BusinessMethodInvocationOutcome,
  BusinessMethodInvocationRequest,
  BusinessMethodInvocationUnconfirmed,
} from '@/types/businessMethodInvocation'

export type BusinessMethodInvocationPostResponse =
  | BusinessMethodInvocationOutcome
  | BusinessMethodInvocationUnconfirmed

/** The browser only talks to Control; it never receives a Runtime target or project credential. */
export function getBusinessMethodInvocationContext(name: string) {
  return controlRequest.get<BusinessMethodInvocationContext>(
    `/api/business-methods/${encodeURIComponent(name)}/invocation-context`,
  )
}

export function invokeBusinessMethod(name: string, request: BusinessMethodInvocationRequest) {
  return controlRequest.post<BusinessMethodInvocationPostResponse>(
    `/api/business-methods/${encodeURIComponent(name)}/invocations`,
    request,
  )
}

/** A read-only investigation request. It never retries the original POST. */
export function getBusinessMethodInvocation(invocationId: string) {
  return controlRequest.get<BusinessMethodInvocationOutcome>(
    `/api/business-method-invocations/${encodeURIComponent(invocationId)}`,
  )
}
