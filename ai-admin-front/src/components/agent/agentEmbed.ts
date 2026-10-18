import type { EmbedCredentialPolicyView } from '@/api/embedOps'

/** Where the ReachAI site serves the browser chat SDK (see scripts/copy-embed-chat-to-dist.mjs). */
export const EMBED_SDK_PUBLIC_PATH = '/sdk/embed-chat'

/** Business-backend endpoint the Spring Boot starter exposes for exchanging an Embed Token. */
export const STARTER_EMBED_TOKEN_PATH = '/reachai/embed/token'

export interface AgentIdentity {
  id: string
  keySlug?: string | null
}

export interface ProjectEmbedPolicy {
  id: number
  projectCode: string
  status: string
  origins: string[]
  agentIds: string[]
  tokenTtlSeconds: number
  /** No agent list: every Agent may be embedded in this business system. */
  allAgents: boolean
  agentAllowed: boolean
}

export function parseJsonList(json?: string | null): string[] {
  if (!json) return []
  try {
    const value = JSON.parse(json)
    return Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string' && item.length > 0) : []
  } catch {
    return []
  }
}

export function agentMatches(agent: AgentIdentity, candidate: string): boolean {
  return candidate === agent.id || (!!agent.keySlug && candidate === agent.keySlug)
}

export function toProjectEmbedPolicy(view: EmbedCredentialPolicyView, agent: AgentIdentity): ProjectEmbedPolicy {
  const agentIds = parseJsonList(view.allowedAgentIdsJson)
  return {
    id: view.id,
    projectCode: view.projectCode,
    status: view.status,
    origins: parseJsonList(view.allowedOriginsJson),
    agentIds,
    tokenTtlSeconds: view.tokenTtlSeconds || 600,
    allAgents: agentIds.length === 0,
    agentAllowed: agentIds.length === 0 || agentIds.some((candidate) => agentMatches(agent, candidate)),
  }
}

/** One credential per project is enough for the Agent page; prefer the ACTIVE one. */
export function policiesByProject(views: EmbedCredentialPolicyView[], agent: AgentIdentity): ProjectEmbedPolicy[] {
  const byProject = new Map<string, EmbedCredentialPolicyView>()
  for (const view of views) {
    const current = byProject.get(view.projectCode)
    if (!current || (current.status !== 'ACTIVE' && view.status === 'ACTIVE')) byProject.set(view.projectCode, view)
  }
  return [...byProject.values()]
    .map((view) => toProjectEmbedPolicy(view, agent))
    .sort((left, right) => Number(right.agentAllowed) - Number(left.agentAllowed)
      || left.projectCode.localeCompare(right.projectCode))
}

export type OriginParseResult = { origins: string[]; invalid: string[] }

/** Accepts one origin per line (or comma separated); keeps scheme + host + port, as browsers send it. */
export function parseOrigins(text: string): OriginParseResult {
  const origins: string[] = []
  const invalid: string[] = []
  for (const raw of text.split(/[\n,，\s]+/)) {
    const value = raw.trim()
    if (!value) continue
    const wildcard = /^(https?):\/\/\*\.([a-z0-9.-]+)(:\d+)?\/?$/i.exec(value)
    if (wildcard) {
      origins.push(`${wildcard[1].toLowerCase()}://*.${wildcard[2].toLowerCase()}${wildcard[3] || ''}`)
      continue
    }
    try {
      const url = new URL(value)
      if (url.protocol !== 'http:' && url.protocol !== 'https:') throw new Error('scheme')
      origins.push(url.origin)
    } catch {
      invalid.push(value)
    }
  }
  return { origins: [...new Set(origins)], invalid }
}

export type AllowChange =
  | { ok: true; agentIds: string[] }
  | { ok: false; reason: string }

/**
 * Computes the project's next allowed-agent list when this Agent is switched
 * on or off. An empty list means "all Agents", so removing the last listed
 * Agent, or narrowing an all-Agents project from here, would widen or hide
 * access unexpectedly and is refused.
 */
export function nextAllowedAgentIds(policy: ProjectEmbedPolicy, agent: AgentIdentity, allow: boolean): AllowChange {
  if (allow) {
    if (policy.agentAllowed) return { ok: true, agentIds: policy.agentIds }
    return { ok: true, agentIds: [...policy.agentIds, agent.id] }
  }
  if (policy.allAgents) {
    return { ok: false, reason: '该业务系统目前允许嵌入所有 Agent，不能只关闭这一个；请先在项目的嵌入授权策略里列出允许的 Agent。' }
  }
  const remaining = policy.agentIds.filter((candidate) => !agentMatches(agent, candidate))
  if (remaining.length === 0) {
    return { ok: false, reason: '这是该业务系统唯一允许的 Agent；清空列表会变成允许所有 Agent。如需停止嵌入，请停用该业务系统的接入凭据。' }
  }
  return { ok: true, agentIds: remaining }
}

export function frontendSnippet(options: { reachAiBase: string; agentId: string }): string {
  const base = options.reachAiBase.replace(/\/+$/, '')
  return `<link rel="stylesheet" href="${base}${EMBED_SDK_PUBLIC_PATH}/style.css">
<script src="${base}${EMBED_SDK_PUBLIC_PATH}/reachai-chat-embed.umd.js"></script>
<script>
  ReachAI.createEafChat({
    mount: document.body,
    agentId: '${options.agentId}',
    apiBase: '${base}',
    position: 'bottom-right',
    // 业务后端用当前登录用户换取嵌入令牌（Spring Boot starter 自动提供该接口）
    tokenProvider: async (context) => {
      const response = await fetch('${STARTER_EMBED_TOKEN_PATH}', {
        method: 'POST',
        credentials: 'include',
        // 业务系统用请求头传登录态时（如 Authorization），在这里补上
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ agentId: '${options.agentId}', ...context, signal: undefined }),
      })
      const payload = await response.json()
      if (!response.ok || !payload.token) throw new Error(payload.message || '获取嵌入令牌失败')
      return { token: payload.token, expiresIn: payload.expiresIn }
    },
  })
</script>`
}

export function backendSnippet(): string {
  return `// 业务系统已接入 reachai-spring-boot2-starter 时，只需提供这个 Bean：
// starter 会自动暴露 POST {context-path}${STARTER_EMBED_TOKEN_PATH}，用当前登录用户换取嵌入令牌。
@Bean
public ReachAiEmbedPrincipalResolver reachAiEmbedPrincipalResolver() {
    return agentId -> {
        LoginUser user = SecurityUtils.getLoginUser();   // 换成业务系统自己的登录态
        if (user == null) {
            return null;                                  // 未登录返回 null，starter 回 401
        }
        return new ReachAiEmbedPrincipal(
                String.valueOf(user.getId()),             // externalUserId
                String.valueOf(user.getId()),             // globalUserId
                user.getUsername(),
                user.getRoleCodes(),                      // 用于「调用权限」按角色控制
                Collections.singletonMap("tenantId", user.getTenantId()));
    };
}`
}
