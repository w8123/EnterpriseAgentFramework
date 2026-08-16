-- Runtime Agent 企业会话状态与完整会话事件账本。
-- 影响：新增两张 Runtime-owned 表，不删除或重写既有数据；部署后生产环境需将状态存储配置为 Redis。

CREATE TABLE IF NOT EXISTS `runtime_conversation_session` (
    `id`                      BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `tenant_id`               VARCHAR(96)  NOT NULL COMMENT '经 Control HMAC 认证的租户；旧协议固定为 default',
    `session_id`              VARCHAR(128) NOT NULL COMMENT '对外会话 ID',
    `user_id`                 VARCHAR(128) NOT NULL COMMENT '经 Control HMAC 认证的 Runtime 用户 ID',
    `agent_id`                VARCHAR(64)  NOT NULL COMMENT '会话绑定 Agent ID',
    `agent_config_version_id` BIGINT       DEFAULT NULL COMMENT '最近使用的 Agent 配置版本',
    `project_code`            VARCHAR(96)  DEFAULT NULL COMMENT 'Agent 所属项目编码快照',
    `identity_source`         VARCHAR(32)  NOT NULL COMMENT 'AGENT / EMBED_SESSION',
    `state_user_key`          VARCHAR(80)  NOT NULL COMMENT 'AgentScope 状态用户键（哈希后）',
    `state_session_key`       VARCHAR(160) NOT NULL COMMENT 'AgentScope 状态会话键（Agent + session 哈希）',
    `status`                  VARCHAR(24)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / CLEARED / EXPIRED',
    `event_count`             INT          NOT NULL DEFAULT 0 COMMENT '已写入事件序号上界',
    `turn_lease_owner`        VARCHAR(64)  DEFAULT NULL COMMENT '跨 Runtime 实例的当前 turn 租约持有者',
    `turn_lease_expires_at`   DATETIME     DEFAULT NULL COMMENT 'turn 租约过期时间',
    `last_turn_at`            DATETIME     DEFAULT NULL COMMENT '最近一次完成或挂起的 turn',
    `cleared_at`              DATETIME     DEFAULT NULL COMMENT '会话清空时间',
    `created_at`              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_runtime_conversation_tenant_session` (`tenant_id`, `session_id`),
    UNIQUE KEY `uk_runtime_conversation_state_slot` (`state_user_key`, `state_session_key`),
    KEY `idx_runtime_conversation_user_time` (`tenant_id`, `user_id`, `last_turn_at`),
    KEY `idx_runtime_conversation_agent_time` (`agent_id`, `last_turn_at`),
    KEY `idx_runtime_conversation_lease` (`turn_lease_expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Runtime Agent 企业会话状态目录与所有权边界';

CREATE TABLE IF NOT EXISTS `runtime_conversation_event` (
    `id`                      BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `conversation_session_id` BIGINT      NOT NULL COMMENT 'runtime_conversation_session.id',
    `sequence_no`             INT         NOT NULL COMMENT '会话内单调事件序号',
    `trace_id`                VARCHAR(64) DEFAULT NULL COMMENT '关联 Runtime traceId',
    `turn_id`                 VARCHAR(128) NOT NULL COMMENT '同一 trace 内的用户 turn 唯一 ID，用于幂等写入',
    `event_type`              VARCHAR(24) NOT NULL COMMENT 'MESSAGE / WAITING / FAILED',
    `role`                    VARCHAR(24) NOT NULL COMMENT 'user / assistant / system / tool',
    `content`                 MEDIUMTEXT  DEFAULT NULL COMMENT '完整但按平台上限截断的会话内容；受企业留存策略治理',
    `content_sha256`          CHAR(64)    NOT NULL COMMENT '内容完整性与去重摘要',
    `payload_json`            MEDIUMTEXT  DEFAULT NULL COMMENT '不含 secret/token 的结构化结果元数据',
    `created_at`              DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_runtime_conversation_event_sequence` (`conversation_session_id`, `sequence_no`),
    UNIQUE KEY `uk_runtime_conversation_event_turn_role` (`conversation_session_id`, `turn_id`, `role`),
    KEY `idx_runtime_conversation_event_trace` (`trace_id`),
    KEY `idx_runtime_conversation_event_time` (`conversation_session_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Runtime Agent 完整会话事件账本；不作为长期个人记忆事实源';
