package com.enterprise.ai.control.compliance;

import com.enterprise.ai.control.identity.ExternalUserBindingErasureLookup;
import com.enterprise.ai.control.platform.PlatformEmbedSessionErasureLookup;
import org.springframework.stereotype.Service;

/** Conservative evidence that an unscoped Runtime user id may refer to a business account. */
@Service
public class BusinessIdentityErasureGuard {
    private final ExternalUserBindingErasureLookup bindings;
    private final PlatformEmbedSessionErasureLookup sessions;

    public BusinessIdentityErasureGuard(ExternalUserBindingErasureLookup bindings,
                                        PlatformEmbedSessionErasureLookup sessions) {
        this.bindings = bindings;
        this.sessions = sessions;
    }

    public boolean hasPotentialBusinessIdentity(String tenant, String userId) {
        // Include disabled bindings and expired sessions: Runtime data can outlive their active status.
        return bindings.anyBinding(tenant, userId) || sessions.anySession(tenant, userId);
    }
}
