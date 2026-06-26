package com.enterprise.ai.agent.platform.control.context.runtime;

import com.enterprise.ai.agent.platform.control.context.ContextPackageResponse;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RuntimeContextInjectionResult {

    private boolean enabled;
    private String skippedReason;
    private RuntimeContextIdentity identity;
    private ContextPackageResponse packageResponse;
    private String promptSection;
    private int itemCount;
    private int truncatedCount;
}
