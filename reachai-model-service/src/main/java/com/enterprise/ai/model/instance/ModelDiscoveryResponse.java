package com.enterprise.ai.model.instance;

import com.enterprise.ai.model.runtime.UpstreamModel;

import java.util.List;

/**
 * 供应商实时模型列表。{@code success=false} 时 {@code message} 给出已脱敏的失败原因。
 */
public record ModelDiscoveryResponse(boolean success,
                                     String message,
                                     String provider,
                                     int total,
                                     List<UpstreamModel> models) {
}
