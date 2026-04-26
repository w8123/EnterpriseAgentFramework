-- Phase 2.x InteractiveFormSkill — 挂起/恢复状态表
-- 可重复执行：若表已存在则跳过 CREATE

USE `ai_text_service`;

CREATE TABLE IF NOT EXISTS `skill_interaction` (
  `id`              VARCHAR(64)   NOT NULL COMMENT 'interactionId，与前端 uiRequest.interactionId 对齐',
  `trace_id`        VARCHAR(64)   NOT NULL,
  `session_id`      VARCHAR(64)   DEFAULT NULL,
  `user_id`         VARCHAR(64)   DEFAULT NULL,
  `agent_id`        BIGINT        DEFAULT NULL,
  `skill_name`      VARCHAR(128)  NOT NULL,
  `status`          VARCHAR(16)   NOT NULL COMMENT 'PENDING / SUBMITTED / EXPIRED / CANCELLED',
  `slot_state`      JSON          NOT NULL COMMENT '含 slots 与 phase: COLLECT|CONFIRM',
  `pending_keys`    JSON          DEFAULT NULL,
  `ui_payload`      JSON          DEFAULT NULL,
  `spec_snapshot`   JSON          NOT NULL,
  `created_at`      DATETIME(3)   NOT NULL,
  `updated_at`      DATETIME(3)   NOT NULL,
  `expires_at`      DATETIME(3)   NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_trace` (`trace_id`),
  KEY `idx_status_expires` (`status`, `expires_at`),
  KEY `idx_user_status` (`user_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='交互式表单 Skill 挂起状态';
