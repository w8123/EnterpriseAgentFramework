package com.enterprise.ai.agent.runtime;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Data
@Configuration
@ConfigurationProperties(prefix = "ai.agent-runtime")
public class AgentRuntimePolicyProperties {

    /** AgentRuntimeProfile.runtimeType 为空时使用的默认运行时。 */
    private String defaultRuntimeType = AgentRuntimeAdapter.DEFAULT_RUNTIME_TYPE;

    /** 平台允许出现在路由选择中的运行时。空列表表示只启用默认运行时。 */
    private List<String> enabledRuntimes = new ArrayList<>(List.of(
            AgentRuntimeAdapter.DEFAULT_RUNTIME_TYPE,
            AgentRuntimeAdapter.LANGGRAPH4J_RUNTIME_TYPE
    ));

    /** 是否允许云端 Runtime，例如 OpenAI Agents / Cursor cloud execution。 */
    private boolean allowCloudExecution = false;

    /** 允许云端 Runtime 的项目编码；空列表表示无项目放行。 */
    private List<String> cloudAllowedProjectCodes = new ArrayList<>();

    /** 是否允许代码工作区 Runtime，例如 Cursor Code Agent。 */
    private boolean allowCodeWorkspace = false;

    /** 允许代码工作区 Runtime 的项目编码；空列表表示无项目放行。 */
    private List<String> codeWorkspaceAllowedProjectCodes = new ArrayList<>();
}
