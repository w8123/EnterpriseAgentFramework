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
