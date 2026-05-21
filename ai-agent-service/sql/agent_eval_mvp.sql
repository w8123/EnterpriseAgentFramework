CREATE TABLE IF NOT EXISTS `agent_eval_dataset` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `agent_id` VARCHAR(64) DEFAULT NULL COMMENT '关联 agent_definition.id；草稿评测可为空',
  `agent_name` VARCHAR(128) DEFAULT NULL,
  `name` VARCHAR(128) NOT NULL,
  `description` VARCHAR(512) DEFAULT NULL,
  `source` VARCHAR(32) NOT NULL DEFAULT 'IMPORT',
  `case_count` INT NOT NULL DEFAULT 0,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY `idx_eval_dataset_agent` (`agent_id`, `create_time`),
  KEY `idx_eval_dataset_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent Studio 评测数据集';

CREATE TABLE IF NOT EXISTS `agent_eval_case` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `dataset_id` BIGINT NOT NULL,
  `case_no` VARCHAR(64) NOT NULL,
  `message` TEXT DEFAULT NULL,
  `input_params_json` MEDIUMTEXT DEFAULT NULL,
  `expected_json` MEDIUMTEXT DEFAULT NULL,
  `judge_config_json` MEDIUMTEXT DEFAULT NULL,
  `tags` VARCHAR(512) DEFAULT NULL,
  `enabled` TINYINT(1) NOT NULL DEFAULT 1,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY `idx_eval_case_dataset` (`dataset_id`, `enabled`, `id`),
  UNIQUE KEY `uk_eval_case_no` (`dataset_id`, `case_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent Studio 评测用例';

CREATE TABLE IF NOT EXISTS `agent_eval_run` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `dataset_id` BIGINT NOT NULL,
  `agent_id` VARCHAR(64) DEFAULT NULL,
  `agent_name` VARCHAR(128) DEFAULT NULL,
  `run_name` VARCHAR(128) DEFAULT NULL,
  `repeat_count` INT NOT NULL DEFAULT 1,
  `status` VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  `canvas_snapshot_json` MEDIUMTEXT DEFAULT NULL,
  `graph_spec_json` MEDIUMTEXT DEFAULT NULL,
  `summary_json` MEDIUMTEXT DEFAULT NULL,
  `suggestion_json` MEDIUMTEXT DEFAULT NULL,
  `started_at` DATETIME DEFAULT NULL,
  `finished_at` DATETIME DEFAULT NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY `idx_eval_run_dataset` (`dataset_id`, `create_time`),
  KEY `idx_eval_run_agent` (`agent_id`, `create_time`),
  KEY `idx_eval_run_status` (`status`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent Studio 评测运行任务';

CREATE TABLE IF NOT EXISTS `agent_eval_case_result` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `run_id` BIGINT NOT NULL,
  `dataset_id` BIGINT NOT NULL,
  `case_id` BIGINT NOT NULL,
  `case_no` VARCHAR(64) NOT NULL,
  `round_no` INT NOT NULL DEFAULT 1,
  `status` VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  `runtime_success` TINYINT(1) NOT NULL DEFAULT 0,
  `assertion_passed` TINYINT(1) NOT NULL DEFAULT 0,
  `semantic_score` DOUBLE DEFAULT NULL,
  `score` DOUBLE NOT NULL DEFAULT 0,
  `elapsed_ms` INT NOT NULL DEFAULT 0,
  `answer` MEDIUMTEXT DEFAULT NULL,
  `trace_id` VARCHAR(96) DEFAULT NULL,
  `step_results_json` MEDIUMTEXT DEFAULT NULL,
  `judge_result_json` MEDIUMTEXT DEFAULT NULL,
  `error_code` VARCHAR(128) DEFAULT NULL,
  `error_message` TEXT DEFAULT NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY `idx_eval_result_run` (`run_id`, `case_id`, `round_no`),
  KEY `idx_eval_result_case` (`case_id`, `create_time`),
  KEY `idx_eval_result_status` (`run_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent Studio 评测用例结果';
