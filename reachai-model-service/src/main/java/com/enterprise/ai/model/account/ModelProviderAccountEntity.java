package com.enterprise.ai.model.account;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 厂商账号：一个厂商的服务地址与 API Key，只存一次，供该厂商下的多个模型实例引用。 */
@Data
@TableName("model_provider_account")
public class ModelProviderAccountEntity {

    @TableId(type = IdType.INPUT)
    private String id;

    private String name;

    private String provider;

    /** 加密后的 {baseUrl, apiKey, authHeader, authPrefix} */
    private String connectionConfigJson;

    private String status;

    private String remark;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
