package com.enterprise.ai.agent.platform.control.context;

public enum ContextTrustLevel {
    LOW,
    MEDIUM,
    HIGH,
    VERIFIED;

    public double weight() {
        return switch (this) {
            case LOW -> 0.5;
            case MEDIUM -> 0.75;
            case HIGH -> 0.9;
            case VERIFIED -> 1.0;
        };
    }
}
