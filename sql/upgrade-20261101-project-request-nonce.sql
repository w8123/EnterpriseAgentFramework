-- ReachAI：项目凭证请求防重放表（MySQL 5.7 / 8，可重复执行）。
-- 前置条件：确认当前连接为目标 ReachAI 数据库，先完成备份并与 initV2.sql 比较。
-- 数据影响：仅在表不存在时创建 Capability 所有的请求 nonce 表，不修改业务数据。
-- 若已存在同名表，CREATE IF NOT EXISTS 不修复结构；须回读并与基线逐项比较。
-- 未部署此表时，正文签名的项目请求（包括 Embed Token 交换）不能通过重放校验。

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `capability_registry_request_nonce` (
    `nonce_digest` CHAR(64)     NOT NULL COMMENT 'SHA-256(projectCode, appKey, nonce)',
    `project_code` VARCHAR(96)  NOT NULL,
    `app_key_hash` CHAR(64)     NOT NULL COMMENT 'SHA-256 appKey; raw appKey is not retained in replay history',
    `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`nonce_digest`),
    KEY `idx_capability_registry_request_nonce_created` (`created_at`),
    KEY `idx_capability_registry_request_nonce_project` (`project_code`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目凭证业务写入请求防重放 nonce';

SHOW CREATE TABLE `capability_registry_request_nonce`;
