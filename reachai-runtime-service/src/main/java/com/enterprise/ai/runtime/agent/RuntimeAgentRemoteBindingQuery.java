package com.enterprise.ai.runtime.agent;

import java.util.List;

public interface RuntimeAgentRemoteBindingQuery {
    /** 校验配置归属、激活状态及绑定启用条件，再返回固定修订。 */
    RuntimeAgentRemoteBindingView requireActiveBinding(String agentId, long configVersionId, long bindingId);

    List<RuntimeAgentRemoteBindingView> enabledBindings(String agentId, Long configVersionId);
}
