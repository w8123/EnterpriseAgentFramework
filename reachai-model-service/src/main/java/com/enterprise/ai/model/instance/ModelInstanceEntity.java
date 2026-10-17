package com.enterprise.ai.model.instance;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("model_instance")
public class ModelInstanceEntity {

    @TableId(type = IdType.INPUT)
    private String id;

    private String name;

    private String provider;

    private String modelType;

    private String modelName;

    private String protocol;

    /** 可为空；NULL 表示全局通用 */
    private String projectCode;

    /**
     * 唯一约束辅助列：IFNULL(project_code,'')，由数据库生成列维护。
     * 禁止 insert/update 写入；读取与按 scope 查询仍可用。
     */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private String projectScopeKey;

    /** 引用的厂商账号；非空时地址与鉴权来自账号，connectionConfigJson 只存请求路径 */
    private String providerAccountId;

    private String connectionConfigJson;

    private String defaultOptionsJson;

    private String paramsSchemaJson;

    private String status;

    private String lastTestStatus;

    private LocalDateTime lastTestAt;

    private Long lastTestLatencyMs;

    private String lastTestError;

    private String remark;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
