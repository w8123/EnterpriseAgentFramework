-- Upgrade: Embed identity and authorization model

CALL add_col_if_absent('registry_project_credential', 'allowed_origins_json',   'TEXT DEFAULT NULL COMMENT ''允许嵌入 Chat 的业务前端 origin JSON 数组'' AFTER `expires_at`');
CALL add_col_if_absent('registry_project_credential', 'allowed_agent_ids_json', 'TEXT DEFAULT NULL COMMENT ''允许申请 embedToken 的 Agent ID / keySlug JSON 数组，空数组表示按项目归属校验'' AFTER `allowed_origins_json`');
CALL add_col_if_absent('registry_project_credential', 'token_ttl_seconds',      'INT DEFAULT 600 COMMENT ''embedToken 默认有效期（秒）'' AFTER `allowed_agent_ids_json`');
CALL add_col_if_absent('agent_definition', 'allowed_roles_json', 'TEXT DEFAULT NULL COMMENT ''允许运行该 Agent 的业务角色 JSON 数组，空表示不限制'' AFTER `visibility`');

CREATE TABLE IF NOT EXISTS `eaf_business_user` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT,
    `tenant_id`      VARCHAR(96)  NOT NULL DEFAULT 'default',
    `global_user_id` VARCHAR(128) NOT NULL,
    `display_name`   VARCHAR(128) DEFAULT NULL,
    `email`          VARCHAR(128) DEFAULT NULL,
    `mobile`         VARCHAR(64)  DEFAULT NULL,
    `status`         VARCHAR(24)  NOT NULL DEFAULT 'ACTIVE',
    `source`         VARCHAR(32)  DEFAULT NULL,
    `last_seen_at`   DATETIME     DEFAULT NULL,
    `created_at`     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_business_user_global` (`tenant_id`, `global_user_id`),
    KEY `idx_business_user_status` (`tenant_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ReachAI 可识别的业务终端用户主体';

CREATE TABLE IF NOT EXISTS `eaf_external_user_binding` (
    `id`                 BIGINT       NOT NULL AUTO_INCREMENT,
    `tenant_id`          VARCHAR(96)  NOT NULL DEFAULT 'default',
    `business_user_id`   BIGINT       NOT NULL,
    `app_id`             VARCHAR(96)  NOT NULL,
    `external_user_id`   VARCHAR(128) NOT NULL,
    `external_user_name` VARCHAR(128) DEFAULT NULL,
    `dept_id`            VARCHAR(128) DEFAULT NULL,
    `dept_name`          VARCHAR(128) DEFAULT NULL,
    `status`             VARCHAR(24)  NOT NULL DEFAULT 'ACTIVE',
    `last_seen_at`       DATETIME     DEFAULT NULL,
    `created_at`         DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`         DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_external_binding` (`tenant_id`, `app_id`, `external_user_id`),
    KEY `idx_external_binding_user` (`business_user_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务系统外部用户身份绑定';

CREATE TABLE IF NOT EXISTS `eaf_external_user_role_binding` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT,
    `tenant_id`        VARCHAR(96)  NOT NULL DEFAULT 'default',
    `business_user_id` BIGINT       NOT NULL,
    `app_id`           VARCHAR(96)  NOT NULL,
    `external_user_id` VARCHAR(128) NOT NULL,
    `role_code`        VARCHAR(96)  NOT NULL,
    `role_name`        VARCHAR(128) DEFAULT NULL,
    `source`           VARCHAR(32)  DEFAULT NULL,
    `status`           VARCHAR(24)  NOT NULL DEFAULT 'ACTIVE',
    `created_at`       DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`       DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_external_role` (`tenant_id`, `app_id`, `external_user_id`, `role_code`),
    KEY `idx_external_role_user` (`business_user_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务系统同步的用户角色绑定';

CREATE TABLE IF NOT EXISTS `eaf_embed_session` (
    `id`                  BIGINT       NOT NULL AUTO_INCREMENT,
    `session_id`          VARCHAR(96)  NOT NULL,
    `tenant_id`           VARCHAR(96)  NOT NULL DEFAULT 'default',
    `app_id`              VARCHAR(96)  NOT NULL,
    `project_code`        VARCHAR(96)  NOT NULL,
    `agent_id`            VARCHAR(128) NOT NULL,
    `external_user_id`    VARCHAR(128) NOT NULL,
    `global_user_id`      VARCHAR(128) DEFAULT NULL,
    `page_instance_id`    VARCHAR(128) NOT NULL,
    `route`               VARCHAR(512) DEFAULT NULL,
    `origin`              VARCHAR(512) NOT NULL,
    `sdk_version`         VARCHAR(64)  DEFAULT NULL,
    `bridge_actions_json` TEXT         DEFAULT NULL,
    `status`              VARCHAR(24)  NOT NULL DEFAULT 'ACTIVE',
    `expires_at`          DATETIME     NOT NULL,
    `created_at`          DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`          DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_embed_session` (`session_id`),
    KEY `idx_embed_session_identity` (`tenant_id`, `app_id`, `external_user_id`, `created_at`),
    KEY `idx_embed_session_page` (`page_instance_id`, `status`, `expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='嵌入式对话会话与页面实例绑定';

CALL add_col_if_absent('eaf_embed_session', 'sdk_version', 'VARCHAR(64) DEFAULT NULL COMMENT ''Chat Embed SDK version'' AFTER `origin`');

CREATE TABLE IF NOT EXISTS `eaf_embed_token_revocation` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT,
    `jti`        VARCHAR(128) NOT NULL,
    `reason`     VARCHAR(256) DEFAULT NULL,
    `expires_at` DATETIME     DEFAULT NULL,
    `revoked_at` DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_embed_token_revocation_jti` (`jti`),
    KEY `idx_embed_token_revocation_exp` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='嵌入式对话 token 撤销表';

CREATE TABLE IF NOT EXISTS `eaf_page_action_event` (
    `id`                      BIGINT       NOT NULL AUTO_INCREMENT,
    `request_id`              VARCHAR(96)  NOT NULL,
    `session_id`              VARCHAR(96)  NOT NULL,
    `tenant_id`               VARCHAR(96)  NOT NULL DEFAULT 'default',
    `app_id`                  VARCHAR(96)  NOT NULL,
    `agent_id`                VARCHAR(128) NOT NULL,
    `node_id`                 VARCHAR(128) DEFAULT NULL,
    `action_key`              VARCHAR(160) DEFAULT NULL,
    `title`                   VARCHAR(256) DEFAULT NULL,
    `args_json`               TEXT         DEFAULT NULL,
    `target_page_instance_id` VARCHAR(128) DEFAULT NULL,
    `confirm_required`        TINYINT(1)   DEFAULT 0,
    `status`                  VARCHAR(32)  NOT NULL DEFAULT 'REQUESTED',
    `result_json`             TEXT         DEFAULT NULL,
    `error_message`           VARCHAR(1024) DEFAULT NULL,
    `requested_at`            DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `completed_at`            DATETIME     DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_page_action_request` (`session_id`, `request_id`),
    KEY `idx_page_action_session` (`session_id`, `status`, `requested_at`),
    KEY `idx_page_action_app_agent` (`tenant_id`, `app_id`, `agent_id`, `requested_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='嵌入式页面动作请求和执行结果';

CREATE TABLE IF NOT EXISTS `eaf_embed_chat_event` (
    `id`           BIGINT      NOT NULL AUTO_INCREMENT,
    `session_id`   VARCHAR(96) NOT NULL,
    `event_type`   VARCHAR(64) NOT NULL,
    `role`         VARCHAR(32) DEFAULT NULL,
    `content`      TEXT        DEFAULT NULL,
    `payload_json` LONGTEXT    DEFAULT NULL,
    `trace_id`     VARCHAR(96) DEFAULT NULL,
    `created_at`   DATETIME    DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_embed_chat_event_session` (`session_id`, `created_at`),
    KEY `idx_embed_chat_event_trace` (`trace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='嵌入式对话事件审计';

CREATE TABLE IF NOT EXISTS `eaf_embed_renderer` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT,
    `renderer_key`      VARCHAR(160) NOT NULL,
    `app_id`            VARCHAR(96)  NOT NULL,
    `name`              VARCHAR(128) NOT NULL,
    `version`           VARCHAR(32)  NOT NULL DEFAULT '1.0',
    `input_schema_json` TEXT         DEFAULT NULL,
    `allowed_agent_ids_json` TEXT    DEFAULT NULL,
    `status`            VARCHAR(24)  NOT NULL DEFAULT 'ACTIVE',
    `created_at`        DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`        DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_embed_renderer` (`app_id`, `renderer_key`, `version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='嵌入式对话自定义结构化渲染器注册';

CALL add_col_if_absent('tool_call_log', 'app_id', 'VARCHAR(96) DEFAULT NULL COMMENT ''嵌入式业务应用 ID，等同 project_code'' AFTER `tenant_id`');
CALL add_col_if_absent('tool_call_log', 'external_user_id', 'VARCHAR(128) DEFAULT NULL COMMENT ''业务系统内用户 ID'' AFTER `app_id`');
CALL add_col_if_absent('tool_call_log', 'global_user_id', 'VARCHAR(128) DEFAULT NULL COMMENT ''跨系统稳定用户 ID'' AFTER `external_user_id`');
CALL add_col_if_absent('tool_call_log', 'page_instance_id', 'VARCHAR(128) DEFAULT NULL COMMENT ''业务前端页面实例 ID'' AFTER `global_user_id`');
CALL add_col_if_absent('tool_call_log', 'origin', 'VARCHAR(512) DEFAULT NULL COMMENT ''业务前端 origin'' AFTER `page_instance_id`');
CALL add_idx_if_absent('tool_call_log', 'idx_tool_log_embed_session', '`app_id`, `external_user_id`, `session_id`');

CALL add_col_if_absent('agent_trace_span', 'tenant_id', 'VARCHAR(96) DEFAULT NULL COMMENT ''租户'' AFTER `project_code`');
CALL add_col_if_absent('agent_trace_span', 'app_id', 'VARCHAR(96) DEFAULT NULL COMMENT ''嵌入式业务应用 ID，等同 project_code'' AFTER `tenant_id`');
CALL add_col_if_absent('agent_trace_span', 'external_user_id', 'VARCHAR(128) DEFAULT NULL COMMENT ''业务系统内用户 ID'' AFTER `app_id`');
CALL add_col_if_absent('agent_trace_span', 'global_user_id', 'VARCHAR(128) DEFAULT NULL COMMENT ''跨系统稳定用户 ID'' AFTER `external_user_id`');
CALL add_col_if_absent('agent_trace_span', 'page_instance_id', 'VARCHAR(128) DEFAULT NULL COMMENT ''业务前端页面实例 ID'' AFTER `global_user_id`');
CALL add_idx_if_absent('agent_trace_span', 'idx_trace_embed_user', '`app_id`, `external_user_id`, `created_at`');

CREATE TABLE IF NOT EXISTS `platform_user` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `username` VARCHAR(96) NOT NULL,
    `display_name` VARCHAR(128) DEFAULT NULL,
    `email` VARCHAR(128) DEFAULT NULL,
    `mobile` VARCHAR(64) DEFAULT NULL,
    `status` VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    `source_provider` VARCHAR(32) NOT NULL,
    `external_subject` VARCHAR(128) NOT NULL,
    `password_hash` VARCHAR(256) DEFAULT NULL,
    `last_login_at` DATETIME DEFAULT NULL,
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_platform_user_provider_subject` (`source_provider`, `external_subject`),
    UNIQUE KEY `uk_platform_user_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台管理用户';

CREATE TABLE IF NOT EXISTS `platform_role` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `role_code` VARCHAR(64) NOT NULL,
    `role_name` VARCHAR(128) NOT NULL,
    `description` VARCHAR(512) DEFAULT NULL,
    `status` VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_platform_role_code` (`role_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台管理角色';

CREATE TABLE IF NOT EXISTS `platform_user_role` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `role_id` BIGINT NOT NULL,
    `scope_type` VARCHAR(32) NOT NULL DEFAULT 'GLOBAL',
    `scope_value` VARCHAR(96) DEFAULT '*',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_platform_user_role_scope` (`user_id`, `role_id`, `scope_type`, `scope_value`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台用户角色绑定';

CREATE TABLE IF NOT EXISTS `platform_permission` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `permission_code` VARCHAR(96) NOT NULL,
    `permission_name` VARCHAR(128) NOT NULL,
    `resource_type` VARCHAR(64) DEFAULT NULL,
    `action` VARCHAR(64) DEFAULT NULL,
    `description` VARCHAR(512) DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_platform_permission_code` (`permission_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台管理权限';

CREATE TABLE IF NOT EXISTS `platform_role_permission` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `role_id` BIGINT NOT NULL,
    `permission_id` BIGINT NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_platform_role_permission` (`role_id`, `permission_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台角色权限绑定';

CREATE TABLE IF NOT EXISTS `platform_login_session` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `session_id` VARCHAR(96) NOT NULL,
    `user_id` BIGINT NOT NULL,
    `provider` VARCHAR(32) NOT NULL,
    `access_token_id` VARCHAR(160) NOT NULL,
    `refresh_token_id` VARCHAR(160) DEFAULT NULL,
    `ip` VARCHAR(64) DEFAULT NULL,
    `user_agent` VARCHAR(512) DEFAULT NULL,
    `expires_at` DATETIME NOT NULL,
    `revoked_at` DATETIME DEFAULT NULL,
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_platform_session_id` (`session_id`),
    UNIQUE KEY `uk_platform_access_token` (`access_token_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台管理端登录会话';

CREATE TABLE IF NOT EXISTS `platform_auth_provider` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `provider_code` VARCHAR(32) NOT NULL,
    `provider_name` VARCHAR(128) NOT NULL,
    `provider_type` VARCHAR(32) NOT NULL,
    `config_json` TEXT DEFAULT NULL,
    `status` VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_platform_auth_provider` (`provider_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台管理端身份提供方配置';

INSERT IGNORE INTO `platform_role` (`role_code`, `role_name`, `description`, `status`)
VALUES
('PLATFORM_ADMIN', 'Platform Admin', 'Full platform administration', 'ACTIVE'),
('AGENT_DESIGNER', 'Agent Designer', 'Create and edit Agents and workflows', 'ACTIVE'),
('PROJECT_OWNER', 'Project Owner', 'Manage assigned business projects', 'ACTIVE'),
('OPERATOR', 'Operator', 'Operate and replay runtime sessions', 'ACTIVE'),
('AUDITOR', 'Auditor', 'Read-only audit and trace access', 'ACTIVE');

INSERT IGNORE INTO `platform_permission` (`permission_code`, `permission_name`, `resource_type`, `action`)
VALUES
('*', 'All platform permissions', 'PLATFORM', '*'),
('platform:read', 'Read platform assets', 'PLATFORM', 'READ'),
('platform:write', 'Write platform assets', 'PLATFORM', 'WRITE'),
('platform:admin', 'Administer platform users and roles', 'PLATFORM', 'ADMIN');

INSERT IGNORE INTO `platform_role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id FROM `platform_role` r JOIN `platform_permission` p
WHERE r.role_code = 'PLATFORM_ADMIN'
  AND p.permission_code IN ('*', 'platform:read', 'platform:write', 'platform:admin');

INSERT IGNORE INTO `platform_role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id FROM `platform_role` r JOIN `platform_permission` p
WHERE r.role_code IN ('AGENT_DESIGNER', 'PROJECT_OWNER', 'OPERATOR')
  AND p.permission_code IN ('platform:read', 'platform:write');

INSERT IGNORE INTO `platform_role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id FROM `platform_role` r JOIN `platform_permission` p
WHERE r.role_code = 'AUDITOR'
  AND p.permission_code = 'platform:read';

INSERT IGNORE INTO `platform_auth_provider` (`provider_code`, `provider_name`, `provider_type`, `status`, `config_json`)
VALUES
('LOCAL', 'Local Development Login', 'LOCAL', 'ACTIVE', '{}'),
('HEADER', 'Trusted Gateway Headers', 'HEADER', 'ACTIVE', '{}'),
('OIDC', 'Enterprise OIDC Login', 'OIDC', 'INACTIVE', '{}'),
('SAML', 'Enterprise SAML Login', 'SAML', 'INACTIVE', '{}');
