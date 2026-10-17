-- 2026-10-17 个人记忆独立化（记忆模块重设计 M2）
--
-- 问题：个人记忆寄存在“上下文治理内核”（control_context_namespace / item / binding / evidence / audit_event /
-- memory_candidate / memory_outbox）里，靠 namespace 行锁和多层 lane/visibility 字段表达“这是我的记忆”，
-- 上下文治理功能已于 PR #4 下线，剩余结构只服务个人记忆，难以理解和维护。
--
-- 影响：
--   1. 新建 control_personal_memory / control_personal_memory_candidate / control_personal_memory_audit /
--      control_personal_memory_outbox。记忆按 (tenant_id, owner_id) 归属，跨 Agent 共享，记录来源 Agent。
--   2. 删除 control_context_* 7 张旧表。旧个人记忆、候选、审计和未投递 outbox 数据直接丢弃，不做迁移
--      （当前只有开发/测试数据）；用户需要重新添加记忆。
--   3. 清空 knowledge_personal_memory_index 及其消费账本：其中 memory_id 指向已删除的旧表，保留会召回孤儿记忆。
--   4. control_context_runtime_user_mapping 改名为 control_runtime_user_mapping（保留数据），索引同步改名。
--   5. control_memory_erasure_request / control_memory_erasure_domain 保留不变。
-- 可重复执行，兼容 MySQL 5.7/8。需在部署新版 Control 之前执行。

CREATE TABLE IF NOT EXISTS `control_personal_memory` (
    `id`                BIGINT        NOT NULL AUTO_INCREMENT,
    `memory_key`        VARCHAR(128)  NOT NULL COMMENT '幂等键；客户端请求 ID 派生或随机',
    `tenant_id`         VARCHAR(96)   NOT NULL,
    `owner_id`          VARCHAR(128)  NOT NULL COMMENT 'Runtime user ID（记忆所有者）',
    `memory_type`       VARCHAR(16)   NOT NULL COMMENT 'FACT/PREFERENCE/RULE/NOTE',
    `title`             VARCHAR(256)  DEFAULT NULL,
    `content`           TEXT          NOT NULL,
    `content_sha256`    CHAR(64)      NOT NULL COMMENT '规范化正文摘要；同一所有者内去重',
    `semantic_key`      VARCHAR(80)   DEFAULT NULL COMMENT '语义键哈希（如 profile:location），同键覆盖',
    `tags_json`         VARCHAR(2000) DEFAULT NULL,
    `source_type`       VARCHAR(24)   NOT NULL COMMENT 'MANUAL/CONVERSATION/CANDIDATE',
    `source_agent_id`   VARCHAR(128)  DEFAULT NULL,
    `source_session_id` VARCHAR(128)  DEFAULT NULL,
    `expires_at`        DATETIME      DEFAULT NULL,
    `created_at`        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_personal_memory_key` (`memory_key`),
    UNIQUE KEY `uk_personal_memory_content` (`tenant_id`, `owner_id`, `content_sha256`),
    UNIQUE KEY `uk_personal_memory_semantic` (`tenant_id`, `owner_id`, `semantic_key`),
    KEY `idx_personal_memory_owner` (`tenant_id`, `owner_id`, `updated_at`),
    KEY `idx_personal_memory_agent` (`tenant_id`, `owner_id`, `source_agent_id`),
    KEY `idx_personal_memory_expiry` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='个人长期记忆；删除即物理删除';
CREATE TABLE IF NOT EXISTS `control_personal_memory_candidate` (
    `id`                 BIGINT        NOT NULL AUTO_INCREMENT,
    `candidate_key`      VARCHAR(128)  NOT NULL,
    `tenant_id`          VARCHAR(96)   NOT NULL,
    `owner_id`           VARCHAR(128)  NOT NULL,
    `memory_type`        VARCHAR(16)   NOT NULL COMMENT 'FACT/PREFERENCE/RULE/NOTE',
    `title`              VARCHAR(256)  DEFAULT NULL,
    `content`            TEXT          NOT NULL,
    `content_sha256`     CHAR(64)      NOT NULL,
    `dedupe_key`         VARCHAR(128)  DEFAULT NULL COMMENT '仅 PENDING 阶段持有的去重键；审核后清空',
    `semantic_key`       VARCHAR(128)  DEFAULT NULL COMMENT '提取器给出的语义键（明文，如 profile:location）',
    `reason`             VARCHAR(512)  DEFAULT NULL,
    `confidence`         DECIMAL(5,4)  NOT NULL DEFAULT 0.7000,
    `status`             VARCHAR(16)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED',
    `source_agent_id`    VARCHAR(128)  DEFAULT NULL,
    `source_session_id`  VARCHAR(128)  DEFAULT NULL,
    `source_trace_id`    VARCHAR(128)  DEFAULT NULL,
    `conflict_memory_id` BIGINT        DEFAULT NULL COMMENT '同语义键下将被替换的既有记忆',
    `approved_memory_id` BIGINT        DEFAULT NULL,
    `occurrence_count`   INT           NOT NULL DEFAULT 1,
    `last_seen_at`       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `reviewed_at`        DATETIME      DEFAULT NULL,
    `extraction_version` VARCHAR(32)   DEFAULT NULL,
    `expires_at`         DATETIME      DEFAULT NULL,
    `created_at`         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_personal_memory_candidate_key` (`candidate_key`),
    UNIQUE KEY `uk_personal_memory_candidate_dedupe` (`dedupe_key`),
    KEY `idx_personal_memory_candidate_owner` (`tenant_id`, `owner_id`, `status`, `last_seen_at`),
    KEY `idx_personal_memory_candidate_memory` (`approved_memory_id`),
    KEY `idx_personal_memory_candidate_conflict` (`conflict_memory_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='个人记忆候选（用户确认前缓冲）';
CREATE TABLE IF NOT EXISTS `control_personal_memory_audit` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `tenant_id`   VARCHAR(96)  NOT NULL,
    `owner_id`    VARCHAR(128) NOT NULL,
    `memory_id`   BIGINT       DEFAULT NULL,
    `event_type`  VARCHAR(32)  NOT NULL COMMENT 'CREATE/UPDATE/DELETE/ERASE_ALL/EXPIRE/SEARCH/CANDIDATE_*',
    `actor_type`  VARCHAR(24)  NOT NULL COMMENT 'USER/SYSTEM',
    `agent_id`    VARCHAR(128) DEFAULT NULL,
    `session_id`  VARCHAR(128) DEFAULT NULL,
    `trace_id`    VARCHAR(128) DEFAULT NULL,
    `decision`    VARCHAR(16)  DEFAULT NULL COMMENT 'ALLOW/SKIP',
    `reason`      VARCHAR(64)  DEFAULT NULL,
    `metadata_json` VARCHAR(2000) DEFAULT NULL COMMENT '只存摘要和计数，不存记忆正文',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_personal_memory_audit_owner` (`tenant_id`, `owner_id`, `created_at`),
    KEY `idx_personal_memory_audit_memory` (`memory_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='个人记忆审计（不存正文）';
CREATE TABLE IF NOT EXISTS `control_personal_memory_outbox` (
    `id`              BIGINT        NOT NULL AUTO_INCREMENT,
    `event_id`        VARCHAR(64)   NOT NULL COMMENT '跨服务幂等事件 ID',
    `aggregate_type`  VARCHAR(32)   NOT NULL COMMENT 'PERSONAL_MEMORY',
    `aggregate_id`    VARCHAR(128)  NOT NULL COMMENT 'control_personal_memory.id',
    `event_type`      VARCHAR(48)   NOT NULL COMMENT 'PERSONAL_MEMORY_UPSERT / PERSONAL_MEMORY_DELETE',
    `correlation_id`  VARCHAR(64)   DEFAULT NULL COMMENT '跨域擦除请求关联号；不包含 owner 或正文',
    `payload_json`    MEDIUMTEXT    NOT NULL COMMENT '最小搜索投影事件；删除事件不含原文',
    `status`          VARCHAR(24)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / PUBLISHING / PUBLISHED / DEAD / SUPERSEDED',
    `attempt_count`   INT           NOT NULL DEFAULT 0,
    `next_attempt_at` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `locked_at`       DATETIME      DEFAULT NULL,
    `last_error`      VARCHAR(1000) DEFAULT NULL COMMENT '应用仅写最长 64 字符机器失败码；禁止异常消息、响应正文或记忆内容',
    `created_at`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `published_at`    DATETIME      DEFAULT NULL,
    `updated_at`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_personal_memory_outbox_event` (`event_id`),
    KEY `idx_personal_memory_outbox_dispatch` (`status`, `next_attempt_at`, `id`),
    KEY `idx_personal_memory_outbox_aggregate` (`aggregate_type`, `aggregate_id`, `id`),
    KEY `idx_personal_memory_outbox_correlation` (`correlation_id`, `status`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Control 个人记忆到 Knowledge 搜索投影的事务 outbox';
-- 运行时用户映射：改名并保留数据
SET @rename_mapping = IF(
    EXISTS (SELECT 1 FROM information_schema.tables
            WHERE table_schema = DATABASE() AND table_name = 'control_context_runtime_user_mapping')
    AND NOT EXISTS (SELECT 1 FROM information_schema.tables
            WHERE table_schema = DATABASE() AND table_name = 'control_runtime_user_mapping'),
    'RENAME TABLE `control_context_runtime_user_mapping` TO `control_runtime_user_mapping`',
    'SELECT 1'
);
PREPARE stmt FROM @rename_mapping;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS `control_runtime_user_mapping` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT,
    `tenant_id`        VARCHAR(96)  NOT NULL DEFAULT 'default',
    `platform_user_id` BIGINT       NOT NULL COMMENT '平台管理端用户 ID',
    `runtime_user_id`  VARCHAR(128) NOT NULL COMMENT '个人记忆所有者 runtime user ID（global/external/user 归一值）',
    `global_user_id`   VARCHAR(128) DEFAULT NULL,
    `external_user_id` VARCHAR(128) DEFAULT NULL,
    `project_id`       BIGINT       DEFAULT NULL,
    `project_code`     VARCHAR(96)  DEFAULT NULL,
    `status`           VARCHAR(24)  NOT NULL DEFAULT 'ACTIVE',
    `active_marker`    TINYINT(1)   DEFAULT 1 COMMENT 'ACTIVE 时为 1，删除后为 NULL；用于唯一活跃 owner 槽位',
    `created_by`       VARCHAR(128) DEFAULT NULL,
    `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted_at`       DATETIME     DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_runtime_user_mapping_active` (`tenant_id`, `platform_user_id`, `active_marker`),
    KEY `idx_runtime_user_mapping_actor` (`tenant_id`, `platform_user_id`, `runtime_user_id`, `status`),
    KEY `idx_runtime_user_mapping_project` (`tenant_id`, `project_code`, `project_id`, `status`),
    KEY `idx_runtime_user_mapping_runtime` (`tenant_id`, `runtime_user_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台用户与运行时用户的映射（管理端查看自己的记忆用）';
SET @rename_uk_runtime_user_mapping_active = IF(
    EXISTS (SELECT 1 FROM information_schema.statistics
            WHERE table_schema = DATABASE() AND table_name = 'control_runtime_user_mapping'
              AND index_name = 'uk_context_runtime_user_mapping_active'),
    'ALTER TABLE `control_runtime_user_mapping` RENAME INDEX `uk_context_runtime_user_mapping_active` TO `uk_runtime_user_mapping_active`',
    'SELECT 1'
);
PREPARE stmt FROM @rename_uk_runtime_user_mapping_active;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @rename_idx_runtime_user_mapping_actor = IF(
    EXISTS (SELECT 1 FROM information_schema.statistics
            WHERE table_schema = DATABASE() AND table_name = 'control_runtime_user_mapping'
              AND index_name = 'idx_context_runtime_user_mapping_actor'),
    'ALTER TABLE `control_runtime_user_mapping` RENAME INDEX `idx_context_runtime_user_mapping_actor` TO `idx_runtime_user_mapping_actor`',
    'SELECT 1'
);
PREPARE stmt FROM @rename_idx_runtime_user_mapping_actor;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @rename_idx_runtime_user_mapping_project = IF(
    EXISTS (SELECT 1 FROM information_schema.statistics
            WHERE table_schema = DATABASE() AND table_name = 'control_runtime_user_mapping'
              AND index_name = 'idx_context_runtime_user_mapping_project'),
    'ALTER TABLE `control_runtime_user_mapping` RENAME INDEX `idx_context_runtime_user_mapping_project` TO `idx_runtime_user_mapping_project`',
    'SELECT 1'
);
PREPARE stmt FROM @rename_idx_runtime_user_mapping_project;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @rename_idx_runtime_user_mapping_runtime = IF(
    EXISTS (SELECT 1 FROM information_schema.statistics
            WHERE table_schema = DATABASE() AND table_name = 'control_runtime_user_mapping'
              AND index_name = 'idx_context_runtime_user_mapping_runtime'),
    'ALTER TABLE `control_runtime_user_mapping` RENAME INDEX `idx_context_runtime_user_mapping_runtime` TO `idx_runtime_user_mapping_runtime`',
    'SELECT 1'
);
PREPARE stmt FROM @rename_idx_runtime_user_mapping_runtime;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

ALTER TABLE `control_runtime_user_mapping` COMMENT = '平台用户与运行时用户的映射（管理端查看自己的记忆用）';

-- 旧个人记忆的搜索投影指向已删除的数据
SET @clear_projection = IF(
    EXISTS (SELECT 1 FROM information_schema.tables
            WHERE table_schema = DATABASE() AND table_name = 'knowledge_personal_memory_index')
    AND EXISTS (SELECT 1 FROM information_schema.tables
            WHERE table_schema = DATABASE() AND table_name = 'control_context_item'),
    'DELETE FROM `knowledge_personal_memory_index`',
    'SELECT 1'
);
PREPARE stmt FROM @clear_projection;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @clear_projection_events = IF(
    EXISTS (SELECT 1 FROM information_schema.tables
            WHERE table_schema = DATABASE() AND table_name = 'knowledge_personal_memory_index_event')
    AND EXISTS (SELECT 1 FROM information_schema.tables
            WHERE table_schema = DATABASE() AND table_name = 'control_context_item'),
    'DELETE FROM `knowledge_personal_memory_index_event`',
    'SELECT 1'
);
PREPARE stmt FROM @clear_projection_events;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 删除上下文治理内核旧表
DROP TABLE IF EXISTS `control_context_memory_outbox`;
DROP TABLE IF EXISTS `control_context_memory_candidate`;
DROP TABLE IF EXISTS `control_context_audit_event`;
DROP TABLE IF EXISTS `control_context_evidence`;
DROP TABLE IF EXISTS `control_context_binding`;
DROP TABLE IF EXISTS `control_context_item`;
DROP TABLE IF EXISTS `control_context_namespace`;
