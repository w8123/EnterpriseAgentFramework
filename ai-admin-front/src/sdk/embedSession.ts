import type { EafPageBridge } from './eafPageBridge'

const SDK_VERSION = '1.0.0'

export interface EafPageDescriptor {
  pageKey: string
  name?: string
  routePattern?: string
  origin?: string
  metadata?: Record<string, unknown>
}

export interface EafChatSessionPayload {
  pageKey?: string
  pageInstanceId: string
  route: string
  bridgeActions: string[]
  sdkVersion: string
}

export type EafChatPageSessionPayload = EafChatSessionPayload & {
  pageKey: string
}

export function buildEafChatSessionPayload(bridge: EafPageBridge, page: EafPageDescriptor): EafChatPageSessionPayload
export function buildEafChatSessionPayload(bridge: EafPageBridge, page?: undefined): EafChatSessionPayload
export function buildEafChatSessionPayload(bridge: EafPageBridge, page?: EafPageDescriptor): EafChatSessionPayload
export function buildEafChatSessionPayload(bridge: EafPageBridge, page?: EafPageDescriptor): EafChatSessionPayload {
  return {
    pageKey: page?.pageKey,
    pageInstanceId: bridge.pageInstanceId,
    route: bridge.route || (typeof location !== 'undefined' ? location.pathname : '/'),
    bridgeActions: bridge.registeredActions,
    sdkVersion: SDK_VERSION,
  }
}

export { SDK_VERSION }
