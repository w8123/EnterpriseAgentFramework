-- 控制台业务方法试调用：新增 Runtime 独占的幂等、结果和未知状态记录。
-- 已有开发/测试库须先确认备份并停止旧 Runtime 写入，再执行本脚本并部署 Control/Runtime/Capability。
-- 新库只执行 initV2.sql。脚本不回填历史调用，不授予任何既有角色 capability:invoke，
-- 不保存原始输入、项目凭据、浏览器身份桥接或上游异常正文。
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `runtime_console_capability_invocation` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `invocation_id` CHAR(36) NOT NULL COMMENT '控制台 UUID 幂等身份',
    `platform_actor_id` VARCHAR(128) NOT NULL COMMENT '经验证的平台操作者；不是业务身份',
    `project_id` BIGINT NOT NULL,
    `project_code` VARCHAR(96) NOT NULL,
    `qualified_name` VARCHAR(200) NOT NULL,
    `expected_contract_hash` CHAR(64) NOT NULL,
    `input_fingerprint` CHAR(64) NOT NULL COMMENT '不保存原始输入',
    `deadline_epoch_ms` BIGINT NOT NULL,
    `run_id` BIGINT NOT NULL,
    `trace_id` VARCHAR(64) NOT NULL,
    `identity_mode` VARCHAR(64) NOT NULL,
    `side_effect` VARCHAR(32) NOT NULL,
    `confirmed_side_effect` TINYINT NOT NULL DEFAULT 0,
    `status` VARCHAR(32) NOT NULL,
    `dispatch_stage` VARCHAR(32) NOT NULL,
    `result_json` MEDIUMTEXT DEFAULT NULL COMMENT '脱敏、限长结果',
    `result_truncated` TINYINT NOT NULL DEFAULT 0,
    `result_expires_at` DATETIME DEFAULT NULL,
    `error_code` VARCHAR(128) DEFAULT NULL,
    `error_message` VARCHAR(256) DEFAULT NULL COMMENT '固定安全摘要',
    `latency_ms` BIGINT DEFAULT NULL,
    `started_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `dispatched_at` DATETIME DEFAULT NULL,
    `ended_at` DATETIME DEFAULT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_runtime_console_capability_invocation` (`invocation_id`),
    KEY `idx_runtime_console_capability_actor` (`platform_actor_id`, `created_at`),
    KEY `idx_runtime_console_capability_project` (`project_id`, `created_at`),
    KEY `idx_runtime_console_capability_status` (`status`, `updated_at`),
    KEY `idx_runtime_console_capability_trace` (`trace_id`),
    KEY `idx_runtime_console_capability_result_expiry` (`result_expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='控制台业务方法试调用的独立幂等、结果和未知状态记录';

INSERT IGNORE INTO `control_platform_permission` (`permission_code`, `permission_name`, `resource_type`, `action`)
VALUES ('capability:invoke', 'Invoke accepted business methods from the console', 'CAPABILITY', 'INVOKE');

-- 不向 PLATFORM_ADMIN 或其他既有角色补写绑定；部署后由平台管理员按项目范围显式授予。
SELECT permission_code, permission_name, resource_type, action
FROM control_platform_permission
WHERE permission_code = 'capability:invoke';

SELECT column_name, data_type, character_maximum_length, is_nullable, column_comment
FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'runtime_console_capability_invocation'
ORDER BY ordinal_position;

SELECT index_name, non_unique, seq_in_index, column_name
FROM information_schema.statistics
WHERE table_schema = DATABASE() AND table_name = 'runtime_console_capability_invocation'
ORDER BY index_name, seq_in_index;
