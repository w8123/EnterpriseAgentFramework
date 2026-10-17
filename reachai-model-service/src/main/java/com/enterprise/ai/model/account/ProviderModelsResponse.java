package com.enterprise.ai.model.account;

import com.enterprise.ai.model.runtime.UpstreamModel;

import java.util.List;

/**
 * 厂商当前可用模型。
 *
 * @param recommendedModelNames 平台目录中该厂商的推荐模型 ID
 * @param connected             该账号下已接入（未归档）的模型，格式 {@code modelName} + 类型
 */
public record ProviderModelsResponse(boolean success,
                                     String message,
                                     String provider,
                                     int total,
                                     List<UpstreamModel> models,
                                     List<String> recommendedModelNames,
                                     List<ConnectedModel> connected) {

    public record ConnectedModel(String modelName, String modelType, String instanceId, String instanceName) {
    }
}
