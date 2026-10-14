import type { LocationQueryRaw } from 'vue-router'
import { withProjectScopeQuery, type ResolvedProjectScopeSuccess } from '@/utils/projectScope'

export type ApiMarketTab = 'discover' | 'integrations' | 'changes' | 'sources'

export function apiMarketTab(value: unknown): ApiMarketTab {
  return typeof value === 'string' && ['integrations', 'changes', 'sources'].includes(value)
    ? value as ApiMarketTab : 'discover'
}

export function apiMarketLocation(tab: ApiMarketTab, query: LocationQueryRaw = {},
  scope?: ResolvedProjectScopeSuccess, hash?: string) {
  return { path: '/api-market',
    query: { ...(scope ? withProjectScopeQuery(query, scope) : query), marketTab: tab } as LocationQueryRaw,
    ...(hash ? { hash } : {}) }
}
