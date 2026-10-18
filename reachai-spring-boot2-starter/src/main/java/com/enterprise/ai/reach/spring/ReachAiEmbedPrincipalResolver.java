package com.enterprise.ai.reach.spring;

/**
 * Resolves the business user behind the current request for an Embed Token exchange.
 *
 * <p>When the host application provides this bean, the Starter exposes
 * {@code POST {context-path}/reachai/embed/token}. The resolver runs inside the
 * host's own authenticated request, so it can read the host's security context.
 * Return {@code null} when nobody is logged in; the endpoint then answers 401.</p>
 */
public interface ReachAiEmbedPrincipalResolver {

    /**
     * @param agentId the Agent the browser wants to open, as sent by the browser SDK
     * @return the logged-in business user, or {@code null} when the request is anonymous
     */
    ReachAiEmbedPrincipal resolve(String agentId);
}
