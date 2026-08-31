package com.enterprise.ai.control.client.capability;

/** Shared Control-to-Capability request contract for AI Coding access. */
public record CapabilityAiCodingAccessUpdateRequest(
        Boolean enabled,
        String accessKey) {
}
