-- ReachAI 2026-10-28：Knowledge 签名防重放从 Redis 改存数据库（MySQL 5.7 / 8，可重复执行）。
-- 前置条件：部署新版 Knowledge 之前执行；执行前确认当前连接为目标库 reach_ai。
-- 数据影响：只新增一张表 knowledge_internal_auth_nonce，不改已有数据。之后 Knowledge 不再需要 Redis。
-- 不执行的后果：新版 Knowledge 收到的所有内部签名请求都会被拒绝（知识库、个人记忆等功能不可用）。

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `knowledge_internal_auth_nonce` (
    `nonce`      VARCHAR(128) NOT NULL COMMENT '调用 Knowledge 的 HMAC nonce',
    `caller`     VARCHAR(96)  NOT NULL COMMENT '调用方服务 ID',
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '写入时间',
    PRIMARY KEY (`nonce`),
    KEY `idx_knowledge_internal_auth_nonce_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Knowledge internal service auth nonce anti-replay store';

-- 回读：应为 1。
SELECT COUNT(*) AS knowledge_internal_auth_nonce_tables
FROM information_schema.tables
WHERE table_schema = DATABASE() AND table_name = 'knowledge_internal_auth_nonce';
