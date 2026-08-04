package com.enterprise.ai.agent.capability.catalog.scan;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ScanDefaultFlags {

    private boolean enabled;

    public ScanDefaultFlags() {
    }

    public ScanDefaultFlags(boolean enabled) {
        this.enabled = enabled;
    }

    public static ScanDefaultFlags defaults() {
        return new ScanDefaultFlags(false);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

}
