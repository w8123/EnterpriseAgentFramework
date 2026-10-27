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
    /** Projects and their business capabilities (Java methods, HTTP APIs, change review). */
    public static final String PLATFORM_READ = "platform:read";
    public static final String PLATFORM_WRITE = "platform:write";
    public static final String PLATFORM_ADMIN = "platform:admin";
    public static final String CAPABILITY_INVOKE = "capability:invoke";

    public static final String KNOWLEDGE_READ = "knowledge:read";
    public static final String KNOWLEDGE_WRITE = "knowledge:write";

    public static final String MODEL_READ = "model:read";
    public static final String MODEL_MANAGE = "model:manage";

    public static final String TOOL_ACL_READ = "tool-acl:read";
    public static final String TOOL_ACL_MANAGE = "tool-acl:manage";

    public static final String EMBED_READ = "embed:read";
    public static final String EMBED_MANAGE = "embed:manage";

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

    public static final String RUNTIME_USER_MAPPING_MANAGE = "context:runtime-user:mapping:manage";
    public static final String MEMORY_ERASURE_MANAGE = "context:memory:erasure:manage";
    public static final String SESSION_RETENTION_MANAGE = "runtime:session:retention:manage";

    public static final String SKILL_READ = "skill:read";
    public static final String MCP_HUB_READ = "mcp-hub:read";
    /** Review and disconnect employees' personal AI-tool connections (managers, operators). */
    public static final String MCP_EMPLOYEE_CONNECTION_MANAGE = "mcp-hub:employee-connection:manage";
    /** Approve or reject write calls from employees' own AI tools (managers, operators). */
    public static final String MCP_WRITE_APPROVAL_MANAGE = "mcp-hub:write-approval:manage";
    public static final String A2A_HUB_READ = "a2a-hub:read";

    private PlatformPermissions() {
    }
}
