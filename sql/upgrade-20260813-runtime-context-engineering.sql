-- ReachAI Runtime context engineering: encrypted, scoped, short-lived large Tool results.
-- Apply to an existing development/test database before enabling
-- RUNTIME_TOOL_RESULT_OFFLOAD_ENABLED=true. This script does not enable the feature.

CREATE TABLE IF NOT EXISTS `runtime_tool_result_artifact` (
    `id`                    BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `artifact_ref`          CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '仅供模型回读的不可猜测引用 tra_<uuid>',
    `owner_scope_hash`      CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Runtime 状态用户键的单向作用域摘要',
    `session_scope_hash`    CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Runtime 状态用户+会话键的单向作用域摘要',
    `agent_name`            VARCHAR(128) NOT NULL COMMENT '产生结果的 Agent keySlug 快照',
    `trace_id`              VARCHAR(64)  DEFAULT NULL COMMENT '关联 Runtime traceId',
    `tool_call_id`          VARCHAR(128) NOT NULL COMMENT 'AgentScope Tool call ID',
    `tool_name`             VARCHAR(128) DEFAULT NULL COMMENT 'Tool 名称快照',
    `content_hmac_sha256`   CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '密钥化正文完整性与去重摘要',
    `active_slot`           TINYINT UNSIGNED DEFAULT 1 COMMENT 'ACTIVE 去重槽；密文擦除时置空，允许未来重新卸载',
    `content_chars`         INT UNSIGNED NOT NULL COMMENT '明文 UTF-16 字符数',
    `content_bytes`         INT UNSIGNED NOT NULL COMMENT '明文 UTF-8 字节数',
    `encryption_key_id`     VARCHAR(64)  NOT NULL COMMENT '企业密钥版本标识，不保存密钥',
    `encryption_nonce`      VARBINARY(12) DEFAULT NULL COMMENT 'AES-GCM 96-bit nonce；擦除后为空',
    `content_ciphertext`    LONGBLOB      DEFAULT NULL COMMENT 'AES-256-GCM 密文；过期/清空后擦除',
    `status`                VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / EXPIRED / DELETED',
    `expires_at`            DATETIME     NOT NULL COMMENT '密文强制过期时间',
    `created_at`            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted_at`            DATETIME     DEFAULT NULL COMMENT '密文擦除时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_runtime_tool_result_artifact_ref` (`artifact_ref`),
    UNIQUE KEY `uk_runtime_tool_result_active_dedupe`
        (`owner_scope_hash`, `session_scope_hash`, `tool_call_id`, `content_hmac_sha256`, `active_slot`),
    KEY `idx_runtime_tool_result_expiry` (`status`, `expires_at`),
    KEY `idx_runtime_tool_result_scope` (`owner_scope_hash`, `session_scope_hash`, `status`),
    KEY `idx_runtime_tool_result_trace` (`trace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Runtime 大 Tool 结果短期加密工件；不是个人长期记忆';
