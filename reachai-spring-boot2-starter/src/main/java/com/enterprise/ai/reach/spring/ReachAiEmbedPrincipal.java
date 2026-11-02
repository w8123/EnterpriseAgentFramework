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
    private final String businessSessionId;
    private final Long businessSessionExpiresAt;

    public ReachAiEmbedPrincipal(
            String externalUserId,
            String globalUserId,
            String userName,
            List<String> roles,
            Map<String, Object> attributes) {
        this(externalUserId, globalUserId, userName, roles, attributes, null);
    }

    /** businessSessionId must be an opaque, high-entropy identifier unique to one host login. */
    public ReachAiEmbedPrincipal(String externalUserId, String globalUserId, String userName,
                                 List<String> roles, Map<String, Object> attributes,
                                 String businessSessionId) {
        this(externalUserId, globalUserId, userName, roles, attributes, businessSessionId, null);
    }

    /** Expiry is a trusted host access-token expiry, in Unix seconds. */
    public ReachAiEmbedPrincipal(String externalUserId, String globalUserId, String userName,
                                 List<String> roles, Map<String, Object> attributes,
                                 String businessSessionId, Long businessSessionExpiresAt) {
        this.externalUserId = externalUserId;
        this.globalUserId = globalUserId;
        this.userName = userName;
        this.roles = roles == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(roles));
        this.attributes = attributes == null
                ? Collections.<String, Object>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(attributes));
        this.businessSessionId = businessSessionId;
        this.businessSessionExpiresAt = businessSessionExpiresAt;
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

    public String getBusinessSessionId() {
        return businessSessionId;
    }

    public Long getBusinessSessionExpiresAt() {
        return businessSessionExpiresAt;
    }
}
