package com.enterprise.ai.control.identity;

/**
 * Stable permission codes shared by Control authorization policies.
 *
 * <p>Roles are assignable permission bundles. Controllers and policies must
 * depend on permission codes rather than role names so a scoped custom role can
 * be introduced without changing application code.</p>
 */
public final class PlatformPermissions {

    public static final String ALL = "*";
    public static final String PLATFORM_READ = "platform:read";
    public static final String PLATFORM_WRITE = "platform:write";
    public static final String PLATFORM_ADMIN = "platform:admin";
    public static final String CAPABILITY_INVOKE = "capability:invoke";

    public static final String BUILD_WORKSPACE_ACCESS = "workspace:build:access";
    public static final String OPERATE_WORKSPACE_ACCESS = "workspace:operate:access";

    public static final String BUSINESS_USER_READ = "identity:business-user:read";
    public static final String BUSINESS_USER_MANAGE = "identity:business-user:manage";

    public static final String AUTOMATION_READ = "automation:read";
    public static final String AUTOMATION_WRITE = "automation:write";
    public static final String AUTOMATION_OPERATE = "automation:operate";

    public static final String AGENT_READ = "agent:read";
    public static final String AGENT_WRITE = "agent:write";
    public static final String AGENT_DEBUG = "agent:debug";
    public static final String AGENT_EVALUATE = "agent:evaluate";
    public static final String AGENT_PUBLISH = "agent:publish";

    public static final String WORKFLOW_READ = "workflow:read";
    public static final String WORKFLOW_WRITE = "workflow:write";
    public static final String WORKFLOW_DEBUG = "workflow:debug";
    public static final String WORKFLOW_PUBLISH = "workflow:publish";
    public static final String WORKFLOW_CREDENTIAL_MANAGE = "workflow:credential:manage";

    public static final String RUNOPS_READ = "runops:read";
    public static final String RUNOPS_OPERATE = "runops:operate";

    private PlatformPermissions() {
    }
}
