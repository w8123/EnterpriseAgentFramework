package com.enterprise.ai.control.agentskill;

public interface AgentSkillArtifactStore {

    StoredArtifact put(String expectedSha256, byte[] archive);

    byte[] get(String artifactKey, String expectedSha256);

    record StoredArtifact(String artifactKey, String sha256, long size) {
    }
}
