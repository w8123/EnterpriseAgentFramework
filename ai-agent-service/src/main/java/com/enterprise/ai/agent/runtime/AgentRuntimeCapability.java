package com.enterprise.ai.agent.runtime;

import lombok.Builder;
import lombok.Data;
import lombok.Singular;

import java.util.List;

@Data
@Builder
public class AgentRuntimeCapability {

    private String runtimeType;

    private String displayName;

    private String description;

    /** Product-facing shape: AUTONOMOUS / WORKFLOW / CODE / EXTERNAL. */
    private String agentMode;

    /** Preferred editor surface: FORM / STUDIO / CODE_WORKSPACE / EXTERNAL_CONSOLE. */
    private String configurationSurface;

    /** Short label for the primary action in the admin UI. */
    private String primaryAction;

    /** Human-readable resource ownership hint, e.g. AGENT_DEFAULTS or NODE_LEVEL. */
    private String resourcePolicy;

    private boolean available;

    private String unavailableReason;

    @Singular
    private List<String> supportedModelTypes;

    private boolean supportsStreaming;

    private boolean supportsTools;

    private boolean supportsHandoff;

    private boolean supportsGraph;

    private boolean supportsHumanInterrupt;

    private boolean supportsArtifacts;

    private boolean supportsCodeWorkspace;

    private boolean supportsCloudExecution;

    private String securityLevel;
}
