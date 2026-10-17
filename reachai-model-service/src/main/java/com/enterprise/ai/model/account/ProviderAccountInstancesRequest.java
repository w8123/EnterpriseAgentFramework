package com.enterprise.ai.model.account;

import java.util.List;

/** 在某个厂商账号下批量接入模型。 */
public record ProviderAccountInstancesRequest(List<Item> models) {

    public record Item(String modelName, String modelType, String name) {
    }
}
