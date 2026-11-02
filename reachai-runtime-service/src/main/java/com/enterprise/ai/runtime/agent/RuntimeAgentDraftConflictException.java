package com.enterprise.ai.runtime.agent;

/** The draft or its owning project changed after the caller read it. */
public final class RuntimeAgentDraftConflictException extends RuntimeException {
    public static final String CODE = "AGENT_DRAFT_CHANGED";
    public RuntimeAgentDraftConflictException() { super("Agent 草稿或所属项目已变化，请重新读取后再保存"); }
}
