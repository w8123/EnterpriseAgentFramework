package com.enterprise.ai.model.account;

import lombok.Data;

import java.util.Map;

@Data
public class ModelProviderAccountRequest {
    private String name;
    private String provider;
    /** baseUrl / apiKey / authHeader / authPrefix；编辑时 apiKey 传掩码表示保持不变 */
    private Map<String, Object> connection;
    private String remark;
}
