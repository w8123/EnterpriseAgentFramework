CREATE TABLE IF NOT EXISTS skill_draft (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(128) NOT NULL,
  description VARCHAR(512) NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  source_trace_ids TEXT NULL,
  spec_json TEXT NULL,
  confidence_score DOUBLE NULL,
  review_note VARCHAR(512) NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_status_create (status, create_time)
);

CREATE TABLE IF NOT EXISTS skill_eval_snapshot (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  skill_name VARCHAR(128) NOT NULL,
  call_count INT NOT NULL DEFAULT 0,
  hit_rate DOUBLE NULL,
  replacement_rate DOUBLE NULL,
  success_rate_diff DOUBLE NULL,
  token_savings INT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'OBSERVE',
  note VARCHAR(512) NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_skill_time (skill_name, create_time),
  KEY idx_status_time (status, create_time)
);
