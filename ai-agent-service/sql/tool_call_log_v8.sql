USE `ai_text_service`;

-- ============================================================
-- Tool Retrieval Phase 1：Agent 工具调用审计日志
--   - 记录每次 tool 调用的入参 / 出参 / 耗时 / 召回快照
--   - 作为 Phase 2 Skill Mining 的数据源（高频 chain 挖掘）
-- ============================================================
CREATE TABLE IF NOT EXISTS `tool_call_log` (
    `id`                   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `trace_id`             VARCHAR(64)  NOT NULL                COMMENT '一次 Agent 执行的 trace id',
    `session_id`           VARCHAR(64)  DEFAULT NULL            COMMENT '会话 ID',
    `user_id`              VARCHAR(64)  DEFAULT NULL            COMMENT '用户 ID',
    `agent_name`           VARCHAR(128) DEFAULT NULL            COMMENT '触发 tool 的 Agent 名',
    `intent_type`          VARCHAR(64)  DEFAULT NULL            COMMENT '意图类型',
    `tool_name`            VARCHAR(128) NOT NULL                COMMENT '被调用的 Tool',
    `args_json`            TEXT         DEFAULT NULL            COMMENT '调用入参 JSON',
    `result_summary`       MEDIUMTEXT   DEFAULT NULL            COMMENT '结果摘要（按 result-max-chars 截断）',
    `success`              TINYINT      NOT NULL DEFAULT 1      COMMENT '是否成功',
    `error_code`           VARCHAR(64)  DEFAULT NULL            COMMENT '失败时的错误码/异常类',
    `elapsed_ms`           INT          DEFAULT NULL            COMMENT '耗时毫秒',
    `token_cost`           INT          DEFAULT NULL            COMMENT '本次调用消耗 token（若可获取）',
    `retrieval_trace_json` MEDIUMTEXT   DEFAULT NULL            COMMENT '召回 top-K + 分数 + 选中项 JSON (Skill Mining 用)',
    `create_time`          DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_trace_id`  (`trace_id`),
    KEY `idx_session`   (`session_id`),
    KEY `idx_tool_time` (`tool_name`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent Tool 调用审计日志（Phase 1 采集 / Phase 2 Skill Mining 数据源）';
