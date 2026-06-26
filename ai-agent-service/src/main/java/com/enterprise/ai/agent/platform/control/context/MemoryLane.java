package com.enterprise.ai.agent.platform.control.context;

/**
 * Project Dev Memory and Runtime User Memory must stay logically isolated even when sharing tables.
 */
public enum MemoryLane {
    PROJECT_DEV,
    RUNTIME_USER
}
