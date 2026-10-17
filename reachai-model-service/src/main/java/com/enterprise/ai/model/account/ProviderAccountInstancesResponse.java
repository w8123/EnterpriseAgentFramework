package com.enterprise.ai.model.account;

import com.enterprise.ai.model.instance.ModelInstanceResponse;

import java.util.List;

public record ProviderAccountInstancesResponse(List<ModelInstanceResponse> created, List<Skipped> skipped) {

    public record Skipped(String modelName, String reason) {
    }
}
