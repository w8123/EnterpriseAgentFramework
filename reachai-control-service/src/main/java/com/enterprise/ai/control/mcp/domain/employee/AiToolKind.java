package com.enterprise.ai.control.mcp.domain.employee;

/** The employee's own AI tool a personal connection is set up for. Only changes the setup snippet. */
public enum AiToolKind {
    CLAUDE_CODE,
    CODEX,
    OTHER,
    /** The assistant embedded in the business system; ReachAI keeps this connection itself, with no key to hand out. */
    ASSISTANT
}
