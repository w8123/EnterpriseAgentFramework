package com.enterprise.ai.agent.platform.control.context.runtime;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "context.runtime")
public class RuntimeContextProperties {

    private boolean enabled = true;
    private int maxItems = 8;
    private int tokenBudget = 1200;
    private boolean injectUserMemory = true;
    private boolean injectPageContext = true;
    private boolean injectWorkflowContext = true;
    private boolean injectApiContext = true;
    private boolean injectRules = true;
}
