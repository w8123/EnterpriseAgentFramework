-- 2026-11-08 费用统计：Runtime 记录每次调用模型的 Token，模型服务保存每个模型的价格。
-- 两张新表，不改已有表；可重复执行。新 Runtime / 模型服务在表缺失时只记日志、不影响运行，
-- 但先执行本脚本再换 jar，才不会漏记。
-- owner: runtime_model_usage → reachai-runtime-service；model_instance_price → reachai-model-service。
USE reach_ai;

CREATE TABLE IF NOT EXISTS `runtime_model_usage` (
    `id`                BIGINT      NOT NULL AUTO_INCREMENT,
    `trace_id`          VARCHAR(64) DEFAULT NULL COMMENT '根运行 runtime_run.trace_id；运行之外的调用（如 Studio AI 编辑）为空',
    `usage_source`      VARCHAR(32) NOT NULL COMMENT 'AGENT / AGENT_COMPACTION / WORKFLOW_NODE / WORKFLOW_AUTHORING',
    `model_instance_id` VARCHAR(64) NOT NULL COMMENT 'model_instance.id',
    `input_tokens`      INT         NOT NULL DEFAULT 0,
    `output_tokens`     INT         NOT NULL DEFAULT 0,
    `created_at`        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_runtime_model_usage_time` (`created_at`),
    KEY `idx_runtime_model_usage_trace` (`trace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='每次调用模型的 Token 用量（不含对话内容），用于估算费用';

CREATE TABLE IF NOT EXISTS `model_instance_price` (
    `model_instance_id`        VARCHAR(64)   NOT NULL COMMENT 'model_instance.id',
    `input_price_per_million`  DECIMAL(12,4) DEFAULT NULL COMMENT '每百万输入 Token 价格',
    `output_price_per_million` DECIMAL(12,4) DEFAULT NULL COMMENT '每百万输出 Token 价格',
    `currency`                 VARCHAR(8)    NOT NULL DEFAULT 'CNY' COMMENT 'CNY / USD',
    `updated_at`               DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`model_instance_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型价格（估算费用用）';
