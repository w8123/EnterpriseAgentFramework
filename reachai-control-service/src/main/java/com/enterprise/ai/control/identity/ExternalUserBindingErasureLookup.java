package com.enterprise.ai.control.identity;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

/** Identity-owned read used by erasure: does any business binding (active or not) name this user? */
@Component
public class ExternalUserBindingErasureLookup {
    private final ExternalUserBindingMapper bindings;

    public ExternalUserBindingErasureLookup(ExternalUserBindingMapper bindings) {
        this.bindings = bindings;
    }

    /** Includes disabled bindings: Runtime data can outlive their active status. */
    public boolean anyBinding(String tenant, String externalUserId) {
        return bindings.selectCount(Wrappers.<ExternalUserBindingEntity>lambdaQuery()
                .eq(ExternalUserBindingEntity::getTenantId, tenant)
                .eq(ExternalUserBindingEntity::getExternalUserId, externalUserId)) > 0;
    }
}
