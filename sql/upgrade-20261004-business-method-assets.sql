-- BMAPI-6: 业务方法独立资产与接纳修订。
-- owning services: 方法/SDK凭据归 reachai-capability-service；Console调用审计归 reachai-runtime-service。
-- 影响：新增两张 owner 表。旧 Tool 记录不作为迁移事实，不批量推断类型或回填资产；
-- 新模型只由可信 SDK 来源接纳建立。完整旧资产退场 SQL 在消费者收口后同批完成。
-- 执行前核对目标开发库并备份；不执行全量 initV2，不改无关表或真实授权。

SET @bmapi6_credential_revision_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'capability_registry_project_credential' AND COLUMN_NAME = 'revision'
);
SET @bmapi6_credential_revision_sql = IF(@bmapi6_credential_revision_exists = 0,
    'ALTER TABLE capability_registry_project_credential ADD COLUMN revision BIGINT NOT NULL DEFAULT 1 COMMENT ''SDK签名凭据及有效策略修订''',
    'SELECT 1');
PREPARE bmapi6_credential_revision_stmt FROM @bmapi6_credential_revision_sql;
EXECUTE bmapi6_credential_revision_stmt;
DEALLOCATE PREPARE bmapi6_credential_revision_stmt;

SET @bmapi6_execution_revision_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'runtime_console_capability_invocation' AND COLUMN_NAME = 'expected_execution_revision'
);
SET @bmapi6_execution_revision_sql = IF(@bmapi6_execution_revision_exists = 0,
    'ALTER TABLE runtime_console_capability_invocation ADD COLUMN expected_execution_revision CHAR(64) DEFAULT NULL COMMENT ''业务方法执行绑定修订，缺失审计值不用于重发''',
    'SELECT 1');
PREPARE bmapi6_execution_revision_stmt FROM @bmapi6_execution_revision_sql;
EXECUTE bmapi6_execution_revision_stmt;
DEALLOCATE PREPARE bmapi6_execution_revision_stmt;

CREATE TABLE IF NOT EXISTS `capability_business_method_asset` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `project_id` BIGINT NOT NULL,
    `project_code` VARCHAR(96) NOT NULL,
    `method_code` VARCHAR(192) NOT NULL,
    `qualified_name` VARCHAR(320) NOT NULL,
    `invocation_name` VARCHAR(320) NOT NULL,
    `title` VARCHAR(192) NOT NULL,
    `description` MEDIUMTEXT NOT NULL,
    `accepted_revision_id` BIGINT DEFAULT NULL,
    `status` VARCHAR(32) NOT NULL,
    `enabled` TINYINT(1) NOT NULL DEFAULT 1,
    `side_effect` VARCHAR(24) NOT NULL,
    `created_at` DATETIME NOT NULL,
    `updated_at` DATETIME NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_business_method_qualified` (`qualified_name`),
    UNIQUE KEY `uk_business_method_invocation` (`invocation_name`),
    UNIQUE KEY `uk_business_method_project_code` (`project_id`, `method_code`),
    KEY `idx_business_method_project_status` (`project_id`, `status`, `enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务方法稳定资产身份与当前接纳修订，无运行连接或凭据';

CREATE TABLE IF NOT EXISTS `capability_business_method_revision` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `asset_id` BIGINT NOT NULL,
    `snapshot_id` BIGINT NOT NULL,
    `diff_item_id` BIGINT NOT NULL,
    `contract_hash` CHAR(64) NOT NULL,
    `invocation_hash` CHAR(64) NOT NULL,
    `binding_hash` CHAR(64) NOT NULL,
    `accepted_at` DATETIME NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_business_method_revision_decision` (`asset_id`, `diff_item_id`),
    KEY `idx_business_method_revision_snapshot` (`snapshot_id`),
    KEY `idx_business_method_revision_contract` (`asset_id`, `contract_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务方法不可变接纳修订，业务契约与SDK传输绑定分别计算指纹，内容引用来源快照';
