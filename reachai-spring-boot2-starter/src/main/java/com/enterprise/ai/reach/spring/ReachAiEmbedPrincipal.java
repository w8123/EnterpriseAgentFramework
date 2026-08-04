package com.enterprise.ai.reach.spring;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Business identity forwarded to ReachAI when exchanging an Embed Token.
 *
 * <p>The business application remains responsible for resolving this identity
 * from its authenticated request. The Starter only signs and transports the
 * already-resolved principal.</p>
 */
public class ReachAiEmbedPrincipal {

    private final String externalUserId;
    private final String globalUserId;
    private final String userName;
    private final List<String> roles;
    private final Map<String, Object> attributes;

    public ReachAiEmbedPrincipal(
            String externalUserId,
            String globalUserId,
            String userName,
            List<String> roles,
            Map<String, Object> attributes) {
        this.externalUserId = externalUserId;
        this.globalUserId = globalUserId;
        this.userName = userName;
        this.roles = roles == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(roles));
        this.attributes = attributes == null
                ? Collections.<String, Object>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(attributes));
    }

    public String getExternalUserId() {
        return externalUserId;
    }

    public String getGlobalUserId() {
        return globalUserId;
    }

    public String getUserName() {
        return userName;
    }

    public List<String> getRoles() {
        return roles;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }
}
