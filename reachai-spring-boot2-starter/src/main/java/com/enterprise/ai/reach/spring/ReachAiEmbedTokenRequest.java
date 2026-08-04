package com.enterprise.ai.reach.spring;

/**
 * Page and identity context used for a server-side Embed Token exchange.
 *
 * <p>The page identity must come from the browser SDK token-provider context
 * and must be forwarded unchanged by the business token broker.</p>
 */
public class ReachAiEmbedTokenRequest {

    private final String agentId;
    private final String pageKey;
    private final String pageInstanceId;
    private final String route;
    private final String origin;
    private final ReachAiEmbedPrincipal principal;

    public ReachAiEmbedTokenRequest(
            String agentId,
            String pageKey,
            String pageInstanceId,
            String route,
            String origin,
            ReachAiEmbedPrincipal principal) {
        this.agentId = agentId;
        this.pageKey = pageKey;
        this.pageInstanceId = pageInstanceId;
        this.route = route;
        this.origin = origin;
        this.principal = principal;
    }

    public String getAgentId() {
        return agentId;
    }

    public String getPageKey() {
        return pageKey;
    }

    public String getPageInstanceId() {
        return pageInstanceId;
    }

    public String getRoute() {
        return route;
    }

    public String getOrigin() {
        return origin;
    }

    public ReachAiEmbedPrincipal getPrincipal() {
        return principal;
    }
}
