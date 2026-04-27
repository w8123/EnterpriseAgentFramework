package com.enterprise.ai.agent.scan;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 新扫描出的接口在项目内的默认开关（仅首次落库时生效，已存在行不覆盖）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ScanDefaultFlags {

    private boolean enabled;
    private boolean agentVisible;
    private boolean lightweightEnabled;

    public ScanDefaultFlags() {
    }

    public ScanDefaultFlags(boolean enabled, boolean agentVisible, boolean lightweightEnabled) {
        this.enabled = enabled;
        this.agentVisible = agentVisible;
        this.lightweightEnabled = lightweightEnabled;
    }

    public static ScanDefaultFlags defaults() {
        return new ScanDefaultFlags(false, false, false);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isAgentVisible() {
        return agentVisible;
    }

    public void setAgentVisible(boolean agentVisible) {
        this.agentVisible = agentVisible;
    }

    public boolean isLightweightEnabled() {
        return lightweightEnabled;
    }

    public void setLightweightEnabled(boolean lightweightEnabled) {
        this.lightweightEnabled = lightweightEnabled;
    }
}
