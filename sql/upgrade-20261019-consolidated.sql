-- ============================================================================
-- ReachAI 近期合并升级：2026-10-16 至 2026-10-18
-- 适用：已完成 2026-10-15 及以前全部变更的 reach_ai 开发/测试库。
-- 新库只执行 initV2.sql；本脚本不补齐更早版本的历史迁移。
-- 兼容 MySQL 5.7 / 8。10 段按日期和原文件名排序，保留原执行逻辑。
--
-- 执行：mysql --default-character-set=utf8mb4 -uroot -p reach_ai < sql/upgrade-20261019-consolidated.sql
-- 执行前：核对目标库、前置结构和可恢复备份，停止旧版相关服务及写入。
-- 执行后：核对各段回读及文末汇总，再部署配套 Control / Runtime / Knowledge / Model。
-- MySQL DDL 隐式提交，整份升级不能用事务整体回滚；遇错立即停止，不使用 --force。
--
-- 数据影响：
--   1. 旧个人记忆、候选、审计、outbox 及其 Knowledge 投影丢弃；运行时用户映射保留。
--   2. 旧 AGENT_DESIGNER / PROJECT_OWNER 绑定迁移后删除；四个内置角色权限重置。
--   3. 删除旧评测 v1、知识标签/问题表和失效的直接返回列。
--   4. 已有知识库 access_mode 默认为 ALL_CALLERS，部署后按需改为 FILE_GRANTS。
--   5. HTTP API 既有单 API 覆盖保留；旧连接修订引用需刷新或重新发布 Workflow。
-- 重跑保留原脚本行为：角色权限仍会重置；个人记忆旧投影仅在旧表存在时清空。
-- 不应把本脚本用作日常结构修复。详情见 sql/README.md。
-- 来源摘要按 UTF-8、LF 换行和一个结尾换行计算；每段保留完整来源内容。
-- ============================================================================

SET NAMES utf8mb4;

-- Section 01/10: HTTP API 项目默认连接
-- Consolidated from: sql/upgrade-20261016-http-api-project-connection.sql
-- Source SHA-256 (UTF-8 LF): 3d3a9114cbf0a938fd463a671eca94ade510dcc62080ff7bc12f817cba147669
-- Begin source 01
-- D27 HTTP API 项目默认连接 + 单 API 覆盖（MySQL 5.7 / 8，可重复执行）。
-- 影响：新增 Runtime 独占表 runtime_http_api_project_connection（project_id + environment 唯一）；
--       runtime_http_api_connection 增加 override_active（默认 1），既有每 API 连接行全部按“本 API 覆盖”继续生效。
-- 不回填项目默认连接，不删除或改写既有连接行，不授予任何角色新权限。新库只执行 initV2.sql。
-- 执行前备份 runtime_http_api_connection，暂停 Runtime 连接写入；执行后部署配套 Runtime / Control / 前端，
-- 回读 information_schema 确认新表、唯一键和新列存在。
-- 注意：既有连接行的生效修订从 revision 变为 revision*2+1；本脚本之前发起但尚未完成的试调用会按“连接已变化”拒绝，
--       已发布 Workflow 中 HTTP API 节点固定的连接修订同样失效，需要重新发布。
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `runtime_http_api_project_connection` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `project_id` BIGINT NOT NULL,
    `project_code` VARCHAR(96) NOT NULL,
    `environment` VARCHAR(32) NOT NULL,
    `origin` VARCHAR(512) NOT NULL COMMENT '仅协议、主机和端口；不含 route、秘密或查询',
    `auth_mode` VARCHAR(32) NOT NULL COMMENT 'NONE / API_KEY_HEADER / BEARER',
    `credential_ref` VARCHAR(128) DEFAULT NULL COMMENT '仅 PROJECT vault 引用',
    `revision` BIGINT NOT NULL COMMENT '乐观并发修订；生效修订=revision*2，覆盖连接为 revision*2+1',
    `saved_by` VARCHAR(128) NOT NULL,
    `created_at` DATETIME NOT NULL,
    `updated_at` DATETIME NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_runtime_http_api_project_connection_scope` (`project_id`, `environment`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Runtime 独占 HTTP API 项目默认连接（项目+环境，所有 API 继承）';

SET @reachai_project_connection_sql = IF(
  NOT EXISTS(SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'runtime_http_api_connection'
      AND COLUMN_NAME = 'override_active'),
  'ALTER TABLE `runtime_http_api_connection` ADD COLUMN `override_active` TINYINT(1) NOT NULL DEFAULT 1 COMMENT ''1=本 API 覆盖项目默认连接；0=已移除覆盖并继承项目默认，保留行以延续修订号'' AFTER `credential_ref`',
  'SELECT ''runtime_http_api_connection.override_active already present'' AS upgrade_status'
);
PREPARE reachai_project_connection_statement FROM @reachai_project_connection_sql;
EXECUTE reachai_project_connection_statement;
DEALLOCATE PREPARE reachai_project_connection_statement;

SET @reachai_project_connection_sql = IF(
  NOT EXISTS(SELECT 1 FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'runtime_http_api_project_connection'
      AND INDEX_NAME = 'uk_runtime_http_api_project_connection_scope'),
  'ALTER TABLE `runtime_http_api_project_connection` ADD UNIQUE KEY `uk_runtime_http_api_project_connection_scope` (`project_id`, `environment`)',
  'SELECT ''runtime_http_api_project_connection scope unique key already present'' AS upgrade_status'
);
PREPARE reachai_project_connection_statement FROM @reachai_project_connection_sql;
EXECUTE reachai_project_connection_statement;
DEALLOCATE PREPARE reachai_project_connection_statement;

ALTER TABLE `runtime_http_api_connection` COMMENT = 'Runtime 独占 HTTP API 单 API 覆盖连接修订';
-- End source 01

-- Section 02/10: 目录分析模型设置
-- Consolidated from: sql/upgrade-20261016-model-catalog-analyzer-setting.sql
-- Source SHA-256 (UTF-8 LF): bce7f84e7943db9501e3c62d14069c28543d32719463221b6542edecdebd098a
-- Begin source 02
-- 2026-10-16 目录分析模型改为可在「模型中心 → 目录设置」中选择
--
-- 问题：官方模型目录同步需要一个“目录分析模型”，此前只能通过部署环境变量
-- MODEL_CATALOG_ANALYZER_MODEL_INSTANCE_ID 指定，管理员在界面上无法启用目录同步，模板因此长期停留在种子版本。
--
-- 影响：给单例表 model_catalog_setting 新增可空列 analyzer_model_instance_id。
-- 界面保存的值优先；为 NULL 时继续回退到环境变量。不修改现有行的其他字段。
-- 可重复执行，兼容 MySQL 5.7/8。

SET @add_catalog_analyzer_column = IF(
    EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'model_catalog_setting'
          AND column_name = 'analyzer_model_instance_id'
    ),
    'SELECT 1',
    'ALTER TABLE `model_catalog_setting` ADD COLUMN `analyzer_model_instance_id` VARCHAR(64) DEFAULT NULL COMMENT ''Console-selected ACTIVE LLM instance for catalog analysis; NULL falls back to MODEL_CATALOG_ANALYZER_MODEL_INSTANCE_ID'' AFTER `auto_sync_enabled`'
);
PREPARE add_catalog_analyzer_column_stmt FROM @add_catalog_analyzer_column;
EXECUTE add_catalog_analyzer_column_stmt;
DEALLOCATE PREPARE add_catalog_analyzer_column_stmt;

-- 回读：应返回 1 行
SELECT column_name, column_type, is_nullable
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'model_catalog_setting'
  AND column_name = 'analyzer_model_instance_id';
-- End source 02

-- Section 03/10: 模型厂商账号
-- Consolidated from: sql/upgrade-20261016-model-provider-account.sql
-- Source SHA-256 (UTF-8 LF): e3aa7865a207eaea9b88698b2ce2302c784ab10d350285467d622d393cb94812
-- Begin source 03
-- 2026-10-16 模型中心「厂商账号」：同一厂商的地址和 API Key 只存一次
--
-- 问题：此前 API Key 存在每个模型实例里，同一厂商接多个模型要重复填写，换 Key 要逐个修改；
-- 也无法“先选厂商、再用 Key 获取该厂商的最新模型列表”。
--
-- 影响：
--   1. 新建表 model_provider_account（地址与 Key 加密存储）。
--   2. model_instance 新增可空列 provider_account_id 及索引。已有实例该列为 NULL，继续使用自己保存的连接配置，行为不变。
-- 不迁移旧数据。可重复执行，兼容 MySQL 5.7/8。需在部署新版 Model Service 之前执行。

CREATE TABLE IF NOT EXISTS `model_provider_account` (
    `id`                       VARCHAR(64)   NOT NULL PRIMARY KEY,
    `name`                     VARCHAR(128)  NOT NULL COMMENT 'Human-readable account name, unique per provider',
    `provider`                 VARCHAR(64)   NOT NULL COMMENT 'Provider key, same vocabulary as model_template.provider',
    `connection_config_json`   MEDIUMTEXT    DEFAULT NULL COMMENT 'Encrypted {baseUrl, apiKey, authHeader, authPrefix} (aesgcm:)',
    `status`                   VARCHAR(32)   NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE',
    `remark`                   VARCHAR(512)  DEFAULT NULL,
    `created_at`               DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`               DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY `uk_model_provider_account_name` (`provider`, `name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Provider base URL and API key stored once, shared by model instances';

SET @add_instance_account_column = IF(
    EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'model_instance'
          AND column_name = 'provider_account_id'
    ),
    'SELECT 1',
    'ALTER TABLE `model_instance` ADD COLUMN `provider_account_id` VARCHAR(64) DEFAULT NULL COMMENT ''model_provider_account.id; when set, base URL and credentials come from the account'' AFTER `project_scope_key`'
);
PREPARE add_instance_account_column_stmt FROM @add_instance_account_column;
EXECUTE add_instance_account_column_stmt;
DEALLOCATE PREPARE add_instance_account_column_stmt;

SET @add_instance_account_index = IF(
    EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'model_instance'
          AND index_name = 'idx_model_instance_account'
    ),
    'SELECT 1',
    'ALTER TABLE `model_instance` ADD KEY `idx_model_instance_account` (`provider_account_id`)'
);
PREPARE add_instance_account_index_stmt FROM @add_instance_account_index;
EXECUTE add_instance_account_index_stmt;
DEALLOCATE PREPARE add_instance_account_index_stmt;

-- 回读：应返回 model_provider_account 表 1 行、provider_account_id 列 1 行
SELECT table_name FROM information_schema.tables
WHERE table_schema = DATABASE() AND table_name = 'model_provider_account';
SELECT column_name, column_type FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'model_instance' AND column_name = 'provider_account_id';
-- End source 03

-- Section 04/10: 平台管理员调用权限
-- Consolidated from: sql/upgrade-20261016-platform-admin-tool-acl.sql
-- Source SHA-256 (UTF-8 LF): 29d7a881c5891c551b516db83cfcea4b74ca7a04d27a67c6a6fede270604fd45
-- Begin source 04
-- 2026-10-16 补齐平台管理员的调用权限种子
--
-- 问题：initV2 只给角色码 `admin` 种了 ALL/* ALLOW，但平台登录会话的管理员角色码是 `PLATFORM_ADMIN`。
-- 全新环境里平台管理员打开任意业务方法试调用都会得到 403 CONSOLE_CAPABILITY_ACL_DENIED。
--
-- 影响：只在 control_tool_acl 中新增一条 PLATFORM_ADMIN / ALL / * / ALLOW 规则；已存在同样规则时不做任何事。
-- 不修改、不删除现有规则；如果已有 PLATFORM_ADMIN 的显式 DENY 规则，DENY 仍然优先。
-- 可重复执行，兼容 MySQL 5.7/8。

INSERT INTO `control_tool_acl` (`role_code`, `target_kind`, `target_name`, `permission`, `note`)
SELECT 'PLATFORM_ADMIN', 'ALL', '*', 'ALLOW', '内建：平台管理员默认放行全部能力'
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM `control_tool_acl`
    WHERE `role_code` = 'PLATFORM_ADMIN' AND `target_kind` = 'ALL' AND `target_name` = '*'
);

-- 回读：应返回 1 行
SELECT `role_code`, `target_kind`, `target_name`, `permission`, `enabled`
FROM `control_tool_acl`
WHERE `role_code` = 'PLATFORM_ADMIN' AND `target_kind` = 'ALL' AND `target_name` = '*';
-- End source 04

-- Section 05/10: Agent 记忆策略
-- Consolidated from: sql/upgrade-20261017-agent-memory-policy.sql
-- Source SHA-256 (UTF-8 LF): 19c5371e21d82a631c759f924d8a29a9b3ba52e9522094a1263b57cdb29caaf2
-- Begin source 05
-- 2026-10-17 Agent 记忆策略（记忆模块重设计 M1）
--
-- 问题：记忆对所有 Agent 一刀切。个人长期记忆的召回和提取只看调用身份，会话窗口只能用环境变量全局配置，
-- 实施人员无法按 Agent 决定是否使用记忆。
--
-- 影响：runtime_agent_config_version 新增 4 列，随配置版本发布：
--   session_memory_enabled  会话记忆开关，默认 1（与升级前行为一致）
--   session_memory_window   会话窗口条数，默认 40（与升级前全局默认一致）
--   personal_memory_read    读取个人长期记忆，默认 0
--   personal_memory_write   写入个人长期记忆 OFF / EXPLICIT / EXPLICIT_AND_CANDIDATE，默认 OFF
-- 已有配置版本（含已发布版本）按默认值补齐：会话记忆行为不变，个人长期记忆的召回和提取关闭，
-- 需要的 Agent 由实施人员在 Agent 编辑页开启后重新发布。不修改已有个人记忆数据。
-- 可重复执行，兼容 MySQL 5.7/8。需在部署新版 Runtime 之前执行。

SET @add_session_memory_enabled = IF(
    EXISTS (SELECT 1 FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'runtime_agent_config_version'
              AND column_name = 'session_memory_enabled'),
    'SELECT 1',
    'ALTER TABLE `runtime_agent_config_version` ADD COLUMN `session_memory_enabled` TINYINT(1) NOT NULL DEFAULT 1 COMMENT ''会话记忆：同一对话的多轮上下文是否持久化'' AFTER `tool_catalog_mode`'
);
PREPARE stmt FROM @add_session_memory_enabled;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_session_memory_window = IF(
    EXISTS (SELECT 1 FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'runtime_agent_config_version'
              AND column_name = 'session_memory_window'),
    'SELECT 1',
    'ALTER TABLE `runtime_agent_config_version` ADD COLUMN `session_memory_window` INT NOT NULL DEFAULT 40 COMMENT ''会话记忆窗口：保留最近 N 条消息（4-100）'' AFTER `session_memory_enabled`'
);
PREPARE stmt FROM @add_session_memory_window;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_personal_memory_read = IF(
    EXISTS (SELECT 1 FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'runtime_agent_config_version'
              AND column_name = 'personal_memory_read'),
    'SELECT 1',
    'ALTER TABLE `runtime_agent_config_version` ADD COLUMN `personal_memory_read` TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''是否读取用户的个人长期记忆'' AFTER `session_memory_window`'
);
PREPARE stmt FROM @add_personal_memory_read;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_personal_memory_write = IF(
    EXISTS (SELECT 1 FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'runtime_agent_config_version'
              AND column_name = 'personal_memory_write'),
    'SELECT 1',
    'ALTER TABLE `runtime_agent_config_version` ADD COLUMN `personal_memory_write` VARCHAR(32) NOT NULL DEFAULT ''OFF'' COMMENT ''个人长期记忆写入：OFF / EXPLICIT / EXPLICIT_AND_CANDIDATE'' AFTER `personal_memory_read`'
);
PREPARE stmt FROM @add_personal_memory_write;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 回读：应返回 4 行
SELECT column_name, column_type, is_nullable, column_default
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'runtime_agent_config_version'
  AND column_name IN ('session_memory_enabled', 'session_memory_window', 'personal_memory_read', 'personal_memory_write')
ORDER BY ordinal_position;
-- End source 05

-- Section 06/10: 业务索引回源改名
-- Consolidated from: sql/upgrade-20261017-business-index-reference-naming.sql
-- Source SHA-256 (UTF-8 LF): e2d1c663696d8a6eecb0926ef96ba0ed31705e116a798a27906ca74b05cf127c
-- Begin source 06
-- 2026-10-17 业务索引回源改名（记忆模块重设计 M4）
--
-- 问题：业务索引的「允许 Agent 引用并按当前身份回源」开关叫 agent_memory_enabled，和个人记忆混为一谈。
-- 业务索引回源不是记忆：业务系统才是事实源，索引只给出带版本的引用，Agent 使用前会按当前用户重新鉴权回源。
--
-- 影响：knowledge_business_index.agent_memory_enabled 改名为 agent_reference_enabled（保留数据），
--       索引 idx_biz_agent_memory 改名为 idx_biz_agent_reference。
-- 可重复执行，兼容 MySQL 5.7/8。需在部署新版 Knowledge 之前执行。

SET @rename_reference_column = IF(
    EXISTS (SELECT 1 FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'knowledge_business_index'
              AND column_name = 'agent_memory_enabled')
    AND NOT EXISTS (SELECT 1 FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'knowledge_business_index'
              AND column_name = 'agent_reference_enabled'),
    'ALTER TABLE `knowledge_business_index` CHANGE COLUMN `agent_memory_enabled` `agent_reference_enabled` TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''是否允许生成非权威 Agent 业务索引引用'' ',
    'SELECT 1'
);
PREPARE stmt FROM @rename_reference_column;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @rename_reference_index = IF(
    EXISTS (SELECT 1 FROM information_schema.statistics
            WHERE table_schema = DATABASE() AND table_name = 'knowledge_business_index'
              AND index_name = 'idx_biz_agent_memory'),
    'ALTER TABLE `knowledge_business_index` RENAME INDEX `idx_biz_agent_memory` TO `idx_biz_agent_reference`',
    'SELECT 1'
);
PREPARE stmt FROM @rename_reference_index;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
-- End source 06

-- Section 07/10: 个人记忆独立化
-- Consolidated from: sql/upgrade-20261017-personal-memory-tables.sql
-- Source SHA-256 (UTF-8 LF): 9e4419f72c17fcf852a615f33f1e67262f908bf4d2aaffca004439d2eea61faa
-- Begin source 07
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
-- End source 07

-- Section 08/10: 平台角色与权限
-- Consolidated from: sql/upgrade-20261017-platform-roles-redesign.sql
-- Source SHA-256 (UTF-8 LF): 40380932f6e077f31ff3b4c6f8b99412437f893a12fd851daaf9a2eb2f69dd15
-- Begin source 08
-- 2026-10-17 人员角色与权限重设计 R1
-- 设计见 docs/plans/人员角色与权限重设计方案.md。只改 Control 自有的平台角色/权限种子与角色绑定，不改表结构。
--
-- 影响：
-- 1. 内置角色收口为 PLATFORM_ADMIN（平台管理员）、DEVELOPER（开发人员）、OPERATOR（运营人员）、AUDITOR（审计员）。
--    原 AGENT_DESIGNER（智能体设计者）的账号绑定迁到 OPERATOR；原 PROJECT_OWNER（项目负责人）的绑定迁到
--    OPERATOR + DEVELOPER，保留原授权范围（GLOBAL 或 PROJECT）。随后删除这两个旧角色。
--    原 OPERATOR（运维操作员）就地改为运营人员，权限明显扩大（可编排、发布、管理知识与调用权限）。
-- 2. 新增权限码 knowledge:read/write、model:read/manage、tool-acl:read/manage、embed:read/manage。
--    知识库、模型、调用权限、嵌入配置不再由 platform:read/write 放行；自定义角色若需要这些功能，须重新勾选。
-- 3. 删除无代码使用的权限码 workspace:build:access、workspace:operate:access、context:runtime-user:review。
-- 4. 四个内置角色的权限按新矩阵整体重置（先删后插）；自定义角色只受第 2、3 点影响。
--
-- 兼容 MySQL 5.7/8，可重复执行。执行前备份 control_platform_role、control_platform_permission、
-- control_platform_role_permission、control_platform_user_role；需在部署新版 Control 之前执行。
-- 执行后已登录的会话需重新登录才会拿到新权限。

INSERT INTO `control_platform_role` (`role_code`, `role_name`, `description`, `role_kind`, `status`)
VALUES
('PLATFORM_ADMIN', '平台管理员', '账号与角色、认证源、模型、合规等平台管理，拥有全部权限', 'SYSTEM', 'ACTIVE'),
('DEVELOPER', '开发人员', 'Java 接入开发者：项目接入、SDK 凭证、业务方法与 HTTP API、变化评审；建议按项目授权', 'SYSTEM', 'ACTIVE'),
('OPERATOR', '运营人员', 'Agent / Workflow 实施与日常运营：编排、发布、知识、运行中心、使用者与调用权限', 'SYSTEM', 'ACTIVE'),
('AUDITOR', '审计员', '只读查看配置、运行记录与权限', 'SYSTEM', 'ACTIVE')
ON DUPLICATE KEY UPDATE `role_name` = VALUES(`role_name`), `description` = VALUES(`description`),
    `role_kind` = VALUES(`role_kind`), `status` = VALUES(`status`);

INSERT INTO `control_platform_permission` (`permission_code`, `permission_name`, `resource_type`, `action`)
VALUES
('*', '全部权限', 'PLATFORM', '*'),
('platform:read', '查看项目与业务能力', 'PROJECT', 'READ'),
('platform:write', '维护项目与业务能力', 'PROJECT', 'WRITE'),
('platform:admin', '管理平台账号、角色与认证源', 'PLATFORM', 'ADMIN'),
('capability:invoke', '在管理端调试调用业务方法与 API', 'CAPABILITY', 'INVOKE'),
('knowledge:read', '查看知识库与业务索引', 'KNOWLEDGE', 'READ'),
('knowledge:write', '维护知识库与业务索引', 'KNOWLEDGE', 'WRITE'),
('model:read', '查看与试用模型', 'MODEL', 'READ'),
('model:manage', '管理模型实例与供应商账号', 'MODEL', 'MANAGE'),
('tool-acl:read', '查看调用权限', 'TOOL_ACL', 'READ'),
('tool-acl:manage', '管理调用权限', 'TOOL_ACL', 'MANAGE'),
('embed:read', '查看嵌入式对话配置与会话', 'EMBED', 'READ'),
('embed:manage', '管理嵌入式对话配置', 'EMBED', 'MANAGE'),
('identity:business-user:read', '查看业务用户目录', 'BUSINESS_USER', 'READ'),
('identity:business-user:manage', '管理业务用户与外部身份绑定', 'BUSINESS_USER', 'MANAGE'),
('skill:read', '查看 Agent Skill 包', 'AGENT_SKILL', 'READ'),
('skill:import', '导入 Agent Skill 包', 'AGENT_SKILL', 'IMPORT'),
('skill:review', '审核 Agent Skill 包版本', 'AGENT_SKILL', 'REVIEW'),
('skill:publish', '发布、弃用和撤销 Agent Skill 版本', 'AGENT_SKILL', 'PUBLISH'),
('skill:bind', '把已发布 Skill 绑定到 Agent', 'AGENT_SKILL', 'BIND'),
('skill:script:approve', '批准 Skill 沙箱脚本执行策略', 'AGENT_SKILL', 'APPROVE_SCRIPT'),
('a2a-hub:read', '查看 A2A 互联', 'A2A_HUB', 'READ'),
('a2a-hub:publication:manage', '管理本地 A2A 发布', 'A2A_PUBLICATION', 'MANAGE'),
('a2a-hub:remote-agent:manage', '管理受信远程 A2A Agent', 'A2A_REMOTE_AGENT', 'MANAGE'),
('a2a-hub:trust:manage', '管理 A2A 主体与信任配置', 'A2A_TRUST', 'MANAGE'),
('a2a-hub:credential:manage', '创建、轮换和吊销 A2A 凭证', 'A2A_CREDENTIAL', 'MANAGE'),
('a2a-hub:task:operate', '操作 A2A 任务（不读取留存载荷）', 'A2A_TASK', 'OPERATE'),
('a2a-hub:payload:read', '在审计下读取 A2A 留存载荷', 'A2A_PAYLOAD', 'READ'),
('a2a-hub:conformance:run', '运行 A2A 诊断与一致性检查', 'A2A_CONFORMANCE', 'RUN'),
('mcp-hub:read', '查看 MCP 发布与调用摘要', 'MCP_HUB', 'READ'),
('mcp-hub:publication:manage', '管理 MCP 对外发布', 'MCP_PUBLICATION', 'MANAGE'),
('mcp-hub:publication:irreversible', '发布含不可逆工具的 MCP 版本', 'MCP_PUBLICATION', 'PUBLISH_IRREVERSIBLE'),
('mcp-hub:credential:manage', '创建、轮换和吊销 MCP 凭证', 'MCP_CREDENTIAL', 'MANAGE'),
('mcp-hub:credential-role:manage', '为 MCP 凭证授予调用权限角色', 'MCP_CREDENTIAL_ROLE', 'MANAGE'),
('mcp-hub:payload:read', '在审计下读取 MCP 留存载荷', 'MCP_PAYLOAD', 'READ'),
('context:runtime-user:mapping:manage', '管理运行时用户映射', 'RUNTIME_USER_MAPPING', 'MANAGE'),
('context:memory:erasure:manage', '管理跨域数据擦除', 'MEMORY_ERASURE', 'MANAGE'),
('runtime:session:retention:manage', '管理会话保留与 Legal Hold', 'RUNTIME_SESSION', 'MANAGE_RETENTION'),
('automation:read', '查看自动化', 'AUTOMATION', 'READ'),
('automation:write', '创建和维护自动化', 'AUTOMATION', 'WRITE'),
('automation:operate', '运行、重试和取消自动化', 'AUTOMATION', 'OPERATE'),
('agent:read', '查看 Agent', 'AGENT', 'READ'),
('agent:write', '创建和编辑 Agent', 'AGENT', 'WRITE'),
('agent:debug', '调试 Agent 并处理开发期审批', 'AGENT', 'DEBUG'),
('agent:evaluate', '维护和运行 Agent 评测', 'AGENT', 'EVALUATE'),
('agent:publish', '发布 Agent 配置版本', 'AGENT', 'PUBLISH'),
('workflow:read', '查看 Workflow', 'WORKFLOW', 'READ'),
('workflow:write', '创建和编辑 Workflow', 'WORKFLOW', 'WRITE'),
('workflow:debug', '调试 Workflow', 'WORKFLOW', 'DEBUG'),
('workflow:publish', '发布和回滚 Workflow 版本', 'WORKFLOW', 'PUBLISH'),
('workflow:credential:manage', '管理 Workflow 执行凭证', 'WORKFLOW_CREDENTIAL', 'MANAGE'),
('runops:read', '查看运行记录、Trace 与诊断', 'RUNOPS', 'READ'),
('runops:operate', '回放和操作运行', 'RUNOPS', 'OPERATE')
ON DUPLICATE KEY UPDATE `permission_name` = VALUES(`permission_name`),
    `resource_type` = VALUES(`resource_type`), `action` = VALUES(`action`);

-- 旧角色绑定迁移（保留原授权范围）。
INSERT IGNORE INTO `control_platform_user_role` (`user_id`, `role_id`, `scope_type`, `scope_value`, `created_at`)
SELECT ur.`user_id`, target.`id`, ur.`scope_type`, ur.`scope_value`, ur.`created_at`
FROM `control_platform_user_role` ur
JOIN `control_platform_role` old_role ON old_role.`id` = ur.`role_id`
JOIN `control_platform_role` target ON target.`role_code` = 'OPERATOR'
WHERE old_role.`role_code` IN ('AGENT_DESIGNER', 'PROJECT_OWNER');

INSERT IGNORE INTO `control_platform_user_role` (`user_id`, `role_id`, `scope_type`, `scope_value`, `created_at`)
SELECT ur.`user_id`, target.`id`, ur.`scope_type`, ur.`scope_value`, ur.`created_at`
FROM `control_platform_user_role` ur
JOIN `control_platform_role` old_role ON old_role.`id` = ur.`role_id`
JOIN `control_platform_role` target ON target.`role_code` = 'DEVELOPER'
WHERE old_role.`role_code` = 'PROJECT_OWNER';

DELETE ur FROM `control_platform_user_role` ur
JOIN `control_platform_role` r ON r.`id` = ur.`role_id`
WHERE r.`role_code` IN ('AGENT_DESIGNER', 'PROJECT_OWNER');

DELETE rp FROM `control_platform_role_permission` rp
JOIN `control_platform_role` r ON r.`id` = rp.`role_id`
WHERE r.`role_code` IN ('AGENT_DESIGNER', 'PROJECT_OWNER', 'PLATFORM_ADMIN', 'DEVELOPER', 'OPERATOR', 'AUDITOR');

DELETE FROM `control_platform_role` WHERE `role_code` IN ('AGENT_DESIGNER', 'PROJECT_OWNER');

-- 无代码使用的权限码。
DELETE rp FROM `control_platform_role_permission` rp
JOIN `control_platform_permission` p ON p.`id` = rp.`permission_id`
WHERE p.`permission_code` IN ('workspace:build:access', 'workspace:operate:access', 'context:runtime-user:review');

DELETE FROM `control_platform_permission`
WHERE `permission_code` IN ('workspace:build:access', 'workspace:operate:access', 'context:runtime-user:review');

-- 内置角色权限矩阵。
INSERT IGNORE INTO `control_platform_role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id FROM `control_platform_role` r JOIN `control_platform_permission` p
WHERE r.role_code = 'PLATFORM_ADMIN';

INSERT IGNORE INTO `control_platform_role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id FROM `control_platform_role` r JOIN `control_platform_permission` p
WHERE r.role_code = 'DEVELOPER'
  AND p.permission_code IN ('platform:read', 'platform:write', 'capability:invoke',
                            'agent:read', 'workflow:read', 'runops:read', 'automation:read',
                            'knowledge:read', 'model:read');

INSERT IGNORE INTO `control_platform_role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id FROM `control_platform_role` r JOIN `control_platform_permission` p
WHERE r.role_code = 'OPERATOR'
  AND p.permission_code IN ('platform:read', 'capability:invoke',
                            'agent:read', 'agent:write', 'agent:debug', 'agent:evaluate', 'agent:publish',
                            'workflow:read', 'workflow:write', 'workflow:debug', 'workflow:publish',
                            'workflow:credential:manage',
                            'runops:read', 'runops:operate',
                            'automation:read', 'automation:write', 'automation:operate',
                            'knowledge:read', 'knowledge:write', 'model:read',
                            'tool-acl:read', 'tool-acl:manage', 'embed:read', 'embed:manage',
                            'identity:business-user:read', 'identity:business-user:manage',
                            'skill:read', 'skill:import', 'skill:bind',
                            'mcp-hub:read', 'mcp-hub:publication:manage', 'mcp-hub:credential:manage',
                            'a2a-hub:read', 'a2a-hub:publication:manage', 'a2a-hub:remote-agent:manage',
                            'a2a-hub:task:operate', 'a2a-hub:conformance:run');

INSERT IGNORE INTO `control_platform_role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id FROM `control_platform_role` r JOIN `control_platform_permission` p
WHERE r.role_code = 'AUDITOR'
  AND p.permission_code IN ('platform:read', 'agent:read', 'workflow:read', 'runops:read',
                            'automation:read', 'knowledge:read', 'model:read', 'tool-acl:read',
                            'embed:read', 'identity:business-user:read', 'skill:read',
                            'mcp-hub:read', 'a2a-hub:read');


-- 回读：应为 4 个内置角色、0 个旧角色、0 个死权限码。
SELECT `role_code`, `role_name` FROM `control_platform_role` WHERE `role_kind` = 'SYSTEM' ORDER BY `role_code`;
SELECT COUNT(*) AS retired_roles FROM `control_platform_role` WHERE `role_code` IN ('AGENT_DESIGNER', 'PROJECT_OWNER');
SELECT COUNT(*) AS retired_permissions FROM `control_platform_permission`
WHERE `permission_code` IN ('workspace:build:access', 'workspace:operate:access', 'context:runtime-user:review');
SELECT r.`role_code`, COUNT(rp.`id`) AS permissions
FROM `control_platform_role` r LEFT JOIN `control_platform_role_permission` rp ON rp.`role_id` = r.`id`
WHERE r.`role_kind` = 'SYSTEM' GROUP BY r.`role_code` ORDER BY r.`role_code`;
-- End source 08

-- Section 09/10: 运行中心清理
-- Consolidated from: sql/upgrade-20261017-run-center-cleanup.sql
-- Source SHA-256 (UTF-8 LF): 4102341992d1fcac09f1af982693f80b9f98b31d21abcb5bb905b823ccc7800b
-- Begin source 09
-- 运行中心 C4 清理（MySQL 5.7 / 8，可重复执行）。
-- 影响：
--   1. 删除旧版评测 v1 的 4 张 Runtime 表 runtime_agent_eval_dataset / _case / _run / _case_result。
--      对应接口 /api/runtime/evals/datasets、/api/runtime/evals/runs 已随本次发布删除；
--      管理端只使用 EvalOps v2（runtime_eval_*），这 4 张表中的旧数据不迁移，直接丢弃。
--   2. runtime_run 增加 idx_runtime_run_session_time (session_id, started_at)，
--      供运行中心「对话」按会话查询根运行和会话统计。
--   3. 补建自动化缺失的 4 张 Runtime 表 runtime_automation / _attempt / _event / _engine_command
--      （宝塔线上库 2026-10-17 核对缺这 4 张；定义与 initV2.sql 相同，已存在则跳过）。
--      不补齐前开启 REACHAI_AUTOMATION_ENABLED 会让自动化页面和执行器报错。
--   4. control_embed_session 增加 external_user_name（嵌入令牌里的业务用户显示名），
--      运行中心「对话」显示「昵称（业务用户 ID）」。只加可空列，旧会话为空，按 ID 显示。
--      带这一列的新版 Control 必须在本脚本执行之后部署。
-- 执行前备份 reach_ai（至少备份 4 张旧评测表）；执行后部署配套 Runtime / Control（Control 必须在本脚本之后），
-- 回读 information_schema 确认 4 张旧评测表不存在、新索引存在、4 张自动化表存在、control_embed_session.external_user_name 存在。新库只执行 initV2.sql。
SET NAMES utf8mb4;

DROP TABLE IF EXISTS `runtime_agent_eval_case_result`;
DROP TABLE IF EXISTS `runtime_agent_eval_run`;
DROP TABLE IF EXISTS `runtime_agent_eval_case`;
DROP TABLE IF EXISTS `runtime_agent_eval_dataset`;

SET @reachai_run_center_sql = IF(
  NOT EXISTS(SELECT 1 FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'runtime_run'
      AND INDEX_NAME = 'idx_runtime_run_session_time'),
  'ALTER TABLE `runtime_run` ADD KEY `idx_runtime_run_session_time` (`session_id`, `started_at`)',
  'SELECT ''runtime_run.idx_runtime_run_session_time already present'' AS upgrade_status'
);
PREPARE reachai_run_center_statement FROM @reachai_run_center_sql;
EXECUTE reachai_run_center_statement;
DEALLOCATE PREPARE reachai_run_center_statement;

CREATE TABLE IF NOT EXISTS `runtime_automation` (
    `id`                 BIGINT       NOT NULL AUTO_INCREMENT,
    `automation_key`     VARCHAR(64)  NOT NULL COMMENT '公开稳定标识 aut_<uuid>',
    `tenant_id`          VARCHAR(96)  NOT NULL DEFAULT 'default',
    `project_id`         BIGINT       DEFAULT NULL,
    `project_code`       VARCHAR(96)  DEFAULT NULL,
    `name`               VARCHAR(128) NOT NULL,
    `description`        VARCHAR(1000) DEFAULT NULL,
    `status`             VARCHAR(24)  NOT NULL COMMENT 'DRAFT / ACTIVE / PAUSED / COMPLETED / ARCHIVED',
    `current_version_id` BIGINT       DEFAULT NULL COMMENT '当前不可变 runtime_automation_version',
    `next_fire_at`       DATETIME(6)  DEFAULT NULL COMMENT 'UTC 投影；真实时钟事实由 runtime_scheduler_task 持有',
    `last_fire_at`       DATETIME(6)  DEFAULT NULL COMMENT '最近名义触发时刻（UTC）',
    `revision`           BIGINT       NOT NULL DEFAULT 1 COMMENT '乐观锁修订号',
    `created_by`         VARCHAR(128) NOT NULL,
    `updated_by`         VARCHAR(128) NOT NULL,
    `created_at`         DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at`         DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_runtime_automation_key` (`automation_key`),
    KEY `idx_runtime_automation_project` (`tenant_id`, `project_code`, `status`),
    KEY `idx_runtime_automation_next_fire` (`status`, `next_fire_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Runtime 自动化聚合根与当前版本投影';

CREATE TABLE IF NOT EXISTS `runtime_automation_attempt` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT,
    `occurrence_id`  BIGINT       NOT NULL,
    `attempt_no`     INT          NOT NULL,
    `status`         VARCHAR(24)  NOT NULL,
    `worker_id`      VARCHAR(96)  DEFAULT NULL,
    `lease_token`    VARCHAR(64)  DEFAULT NULL,
    `trace_id`       VARCHAR(64)  DEFAULT NULL,
    `result_summary` VARCHAR(4000) DEFAULT NULL,
    `error_code`     VARCHAR(96)  DEFAULT NULL,
    `error_message`  VARCHAR(2000) DEFAULT NULL,
    `started_at`     DATETIME(6)  DEFAULT NULL,
    `ended_at`       DATETIME(6)  DEFAULT NULL,
    `created_at`     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_runtime_automation_attempt` (`occurrence_id`, `attempt_no`),
    KEY `idx_runtime_automation_attempt_trace` (`trace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Automation occurrence 每次租约尝试的审计事实';

CREATE TABLE IF NOT EXISTS `runtime_automation_event` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT,
    `automation_id`  BIGINT       NOT NULL,
    `occurrence_id`  BIGINT       DEFAULT NULL,
    `event_type`     VARCHAR(96)  NOT NULL,
    `actor_type`     VARCHAR(32)  NOT NULL,
    `actor_id`       VARCHAR(128) NOT NULL,
    `detail_json`    MEDIUMTEXT   NOT NULL COMMENT '无凭据的状态转换与证据摘要',
    `created_at`     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    KEY `idx_runtime_automation_event_history` (`automation_id`, `created_at`),
    KEY `idx_runtime_automation_event_occurrence` (`occurrence_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Automation 元数据审计事件';

CREATE TABLE IF NOT EXISTS `runtime_automation_engine_command` (
    `id`                    BIGINT       NOT NULL AUTO_INCREMENT,
    `automation_id`         BIGINT       NOT NULL,
    `automation_version_id` BIGINT       DEFAULT NULL,
    `command_type`          VARCHAR(16)  NOT NULL COMMENT 'UPSERT / CANCEL',
    `status`                VARCHAR(24)  NOT NULL COMMENT 'PENDING / RETRY / PROCESSING / COMPLETED / DEAD',
    `attempt_count`         INT          NOT NULL DEFAULT 0,
    `available_at`          DATETIME(6)  NOT NULL,
    `lease_owner`           VARCHAR(96)  DEFAULT NULL,
    `lease_token`           VARCHAR(64)  DEFAULT NULL,
    `leased_until`          DATETIME(6)  DEFAULT NULL,
    `last_error_code`       VARCHAR(96)  DEFAULT NULL,
    `last_error_message`    VARCHAR(2000) DEFAULT NULL,
    `created_at`            DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at`            DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    `completed_at`          DATETIME(6)  DEFAULT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_runtime_automation_engine_command_poll` (`status`, `available_at`, `id`),
    KEY `idx_runtime_automation_engine_command_target` (`automation_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Automation 定义事务与外部时钟表之间的持久命令';

SET @reachai_run_center_sql = IF(
  NOT EXISTS(SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'control_embed_session'
      AND COLUMN_NAME = 'external_user_name'),
  'ALTER TABLE `control_embed_session` ADD COLUMN `external_user_name` VARCHAR(128) DEFAULT NULL COMMENT ''嵌入令牌里的业务用户显示名，仅用于运行中心展示'' AFTER `external_user_id`',
  'SELECT ''control_embed_session.external_user_name already present'' AS upgrade_status'
);
PREPARE reachai_run_center_statement FROM @reachai_run_center_sql;
EXECUTE reachai_run_center_statement;
DEALLOCATE PREPARE reachai_run_center_statement;
-- End source 09

-- Section 10/10: 知识模块清理
-- Consolidated from: sql/upgrade-20261018-knowledge-cleanup.sql
-- Source SHA-256 (UTF-8 LF): 8a817877694bd9706c2d97d3bc36912210fc9c7972fbf66470ddb9c868db33b8
-- Begin source 10
-- 知识模块整理（MySQL 5.7 / 8，可重复执行）。方案：docs/plans/知识模块整理方案.md
-- 影响：
--   1. knowledge_base 增加 access_mode（谁能检索）：ALL_CALLERS 所有调用方 / FILE_GRANTS 仅逐文件授权用户。
--      已有知识库全部设为 ALL_CALLERS：原来只有导入人能在对话中查到，执行后调用该 Agent 的所有用户都能查到。
--      需要按人限制的知识库，部署后在知识库设置里改成「仅授权文件」。
--   2. 删除 knowledge_base.direct_return_enabled / direct_return_threshold（Runtime 从不支持「直接返回」）。
--   3. knowledge_hit_log 删除 direct_return，增加 source（RUNTIME 真实对话 / CONSOLE 管理端检索测试）和
--      索引 idx_kb_source_time。已有命中记录都来自管理端检索测试，标为 CONSOLE。
--   4. 删除 knowledge_tag（标签）和 knowledge_question（问题映射）两张表：线上为空表，检索从不读取它们。
-- 执行前备份 reach_ai；执行后部署配套 Knowledge（读取 access_mode / source 的新版必须在本脚本之后部署，
-- 旧版 Knowledge 在本脚本之后会因缺列报错，所以两者之间不要留间隔）。
-- 回读 information_schema 确认 access_mode、source、idx_kb_source_time 存在，direct_return* 三列及
-- knowledge_tag、knowledge_question 两张表不存在。
-- 新库只执行 initV2.sql。
SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS reachai_knowledge_cleanup_exec;
DELIMITER $$
CREATE PROCEDURE reachai_knowledge_cleanup_exec(IN p_table VARCHAR(64), IN p_column VARCHAR(64),
                                                IN p_when_present TINYINT, IN p_sql TEXT)
BEGIN
    DECLARE present INT DEFAULT 0;
    SELECT COUNT(*) INTO present FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table AND COLUMN_NAME = p_column;
    IF (present > 0 AND p_when_present = 1) OR (present = 0 AND p_when_present = 0) THEN
        SET @reachai_knowledge_cleanup_sql = p_sql;
        PREPARE reachai_knowledge_cleanup_statement FROM @reachai_knowledge_cleanup_sql;
        EXECUTE reachai_knowledge_cleanup_statement;
        DEALLOCATE PREPARE reachai_knowledge_cleanup_statement;
    END IF;
END$$
DELIMITER ;

CALL reachai_knowledge_cleanup_exec('knowledge_base', 'access_mode', 0,
    'ALTER TABLE `knowledge_base` ADD COLUMN `access_mode` VARCHAR(16) NOT NULL DEFAULT ''ALL_CALLERS'' COMMENT ''谁能检索: ALL_CALLERS 所有调用方 / FILE_GRANTS 仅逐文件授权用户'' AFTER `scope`');
CALL reachai_knowledge_cleanup_exec('knowledge_base', 'direct_return_enabled', 1,
    'ALTER TABLE `knowledge_base` DROP COLUMN `direct_return_enabled`');
CALL reachai_knowledge_cleanup_exec('knowledge_base', 'direct_return_threshold', 1,
    'ALTER TABLE `knowledge_base` DROP COLUMN `direct_return_threshold`');

CALL reachai_knowledge_cleanup_exec('knowledge_hit_log', 'source', 0,
    'ALTER TABLE `knowledge_hit_log` ADD COLUMN `source` VARCHAR(16) NOT NULL DEFAULT ''CONSOLE'' COMMENT ''RUNTIME 真实对话 / CONSOLE 管理端检索测试'' AFTER `score`');
CALL reachai_knowledge_cleanup_exec('knowledge_hit_log', 'direct_return', 1,
    'ALTER TABLE `knowledge_hit_log` DROP COLUMN `direct_return`');

SET @reachai_knowledge_cleanup_sql = IF(
  NOT EXISTS(SELECT 1 FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_hit_log'
      AND INDEX_NAME = 'idx_kb_source_time'),
  'ALTER TABLE `knowledge_hit_log` ADD KEY `idx_kb_source_time` (`knowledge_base_id`, `source`, `create_time`)',
  'SELECT ''knowledge_hit_log.idx_kb_source_time already present'' AS upgrade_status'
);
PREPARE reachai_knowledge_cleanup_statement FROM @reachai_knowledge_cleanup_sql;
EXECUTE reachai_knowledge_cleanup_statement;
DEALLOCATE PREPARE reachai_knowledge_cleanup_statement;

DROP TABLE IF EXISTS knowledge_tag;
DROP TABLE IF EXISTS knowledge_question;

DROP PROCEDURE IF EXISTS reachai_knowledge_cleanup_exec;
-- End source 10

-- 汇总回读：11 张表、11 列和 3 个索引应全部返回；退役对象和死权限计数应为 0。
SELECT table_name FROM information_schema.tables
WHERE table_schema = DATABASE() AND table_name IN (
    'runtime_http_api_project_connection', 'model_provider_account',
    'control_personal_memory', 'control_personal_memory_candidate',
    'control_personal_memory_audit', 'control_personal_memory_outbox', 'control_runtime_user_mapping',
    'runtime_automation', 'runtime_automation_attempt', 'runtime_automation_event',
    'runtime_automation_engine_command') ORDER BY table_name;

SELECT table_name, column_name, column_type, column_default FROM information_schema.columns
WHERE table_schema = DATABASE() AND (
    (table_name = 'runtime_http_api_connection' AND column_name = 'override_active')
 OR (table_name = 'model_catalog_setting' AND column_name = 'analyzer_model_instance_id')
 OR (table_name = 'model_instance' AND column_name = 'provider_account_id')
 OR (table_name = 'runtime_agent_config_version' AND column_name IN (
        'session_memory_enabled', 'session_memory_window', 'personal_memory_read', 'personal_memory_write'))
 OR (table_name = 'knowledge_business_index' AND column_name = 'agent_reference_enabled')
 OR (table_name = 'control_embed_session' AND column_name = 'external_user_name')
 OR (table_name = 'knowledge_base' AND column_name = 'access_mode')
 OR (table_name = 'knowledge_hit_log' AND column_name = 'source')) ORDER BY table_name, column_name;

SELECT table_name, index_name, GROUP_CONCAT(column_name ORDER BY seq_in_index) AS index_columns
FROM information_schema.statistics WHERE table_schema = DATABASE() AND (
    (table_name = 'model_instance' AND index_name = 'idx_model_instance_account')
 OR (table_name = 'runtime_run' AND index_name = 'idx_runtime_run_session_time')
 OR (table_name = 'knowledge_hit_log' AND index_name = 'idx_kb_source_time'))
GROUP BY table_name, index_name ORDER BY table_name, index_name;

SELECT COUNT(*) AS retired_tables FROM information_schema.tables
WHERE table_schema = DATABASE() AND table_name IN (
    'control_context_namespace', 'control_context_item', 'control_context_binding',
    'control_context_evidence', 'control_context_audit_event', 'control_context_memory_candidate',
    'control_context_memory_outbox', 'control_context_runtime_user_mapping',
    'runtime_agent_eval_dataset', 'runtime_agent_eval_case', 'runtime_agent_eval_run',
    'runtime_agent_eval_case_result', 'knowledge_tag', 'knowledge_question');

SELECT COUNT(*) AS retired_columns FROM information_schema.columns
WHERE table_schema = DATABASE() AND (
    (table_name = 'knowledge_base' AND column_name IN ('direct_return_enabled', 'direct_return_threshold'))
 OR (table_name = 'knowledge_hit_log' AND column_name = 'direct_return')
 OR (table_name = 'knowledge_business_index' AND column_name = 'agent_memory_enabled'));

SELECT role_code, role_name FROM control_platform_role WHERE role_kind = 'SYSTEM' ORDER BY role_code;
SELECT COUNT(*) AS retired_roles FROM control_platform_role
WHERE role_code IN ('AGENT_DESIGNER', 'PROJECT_OWNER');
SELECT COUNT(*) AS retired_permissions FROM control_platform_permission
WHERE permission_code IN ('workspace:build:access', 'workspace:operate:access', 'context:runtime-user:review');
SELECT COUNT(*) AS platform_admin_allow_rules FROM control_tool_acl
WHERE role_code = 'PLATFORM_ADMIN' AND target_kind = 'ALL' AND target_name = '*' AND permission = 'ALLOW';
