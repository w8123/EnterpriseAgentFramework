DROP TABLE IF EXISTS control_ai_coding_task_artifact;
DROP TABLE IF EXISTS control_ai_coding_task_question;
DROP TABLE IF EXISTS control_ai_coding_task_event;
DROP TABLE IF EXISTS control_ai_coding_task_handoff;
DROP TABLE IF EXISTS control_ai_coding_task_target;
DROP TABLE IF EXISTS control_ai_coding_project_policy;
DROP TABLE IF EXISTS control_ai_coding_task;
DROP TABLE IF EXISTS control_page_analysis_finding;
DROP TABLE IF EXISTS control_page_action;
DROP TABLE IF EXISTS control_project_page_resource;
DROP TABLE IF EXISTS control_project_page;

CREATE TABLE control_project_page (
    id BIGINT NOT NULL AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    project_code VARCHAR(96) NOT NULL,
    page_key VARCHAR(160) NOT NULL,
    module_key VARCHAR(128),
    module_name VARCHAR(160),
    name VARCHAR(160) NOT NULL,
    description VARCHAR(1000),
    route_pattern VARCHAR(512),
    component_path VARCHAR(768),
    source_type VARCHAR(32) NOT NULL,
    lifecycle_status VARCHAR(24) NOT NULL,
    last_discovered_at TIMESTAMP,
    last_verified_at TIMESTAMP,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE (project_code, page_key)
);

CREATE TABLE control_project_page_resource (
    id BIGINT NOT NULL AUTO_INCREMENT,
    page_id BIGINT NOT NULL,
    project_code VARCHAR(96) NOT NULL,
    resource_type VARCHAR(32) NOT NULL,
    resource_key VARCHAR(512) NOT NULL,
    display_name VARCHAR(256),
    location VARCHAR(1000),
    http_method VARCHAR(16),
    access_mode VARCHAR(16) NOT NULL,
    metadata_json CLOB,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE (page_id, resource_type, resource_key)
);

CREATE TABLE control_page_action (
    id BIGINT NOT NULL AUTO_INCREMENT,
    page_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    project_code VARCHAR(96) NOT NULL,
    page_key VARCHAR(160) NOT NULL,
    action_key VARCHAR(160) NOT NULL,
    title VARCHAR(160) NOT NULL,
    description VARCHAR(1000),
    action_type VARCHAR(32) NOT NULL,
    risk_level VARCHAR(24) NOT NULL,
    confirm_required BOOLEAN NOT NULL,
    permission_key VARCHAR(160),
    input_schema_json CLOB,
    output_schema_json CLOB,
    sample_args_json CLOB,
    allowed_agent_ids_json CLOB,
    implementation_ref VARCHAR(1000),
    source_type VARCHAR(32) NOT NULL,
    status VARCHAR(24) NOT NULL,
    metadata_json CLOB,
    last_verified_at TIMESTAMP,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE (page_id, action_key)
);

CREATE TABLE control_page_analysis_finding (
    id BIGINT NOT NULL AUTO_INCREMENT,
    finding_key VARCHAR(96) NOT NULL,
    project_id BIGINT NOT NULL,
    project_code VARCHAR(96) NOT NULL,
    page_id BIGINT NOT NULL,
    page_key VARCHAR(160) NOT NULL,
    source_task_id VARCHAR(40) NOT NULL,
    category VARCHAR(64),
    title VARCHAR(256) NOT NULL,
    confirmed_fact CLOB NOT NULL,
    technical_inference CLOB,
    open_question CLOB,
    use_case CLOB,
    business_confirm_status VARCHAR(32) NOT NULL,
    technical_feasibility VARCHAR(32) NOT NULL,
    operation_risk VARCHAR(32) NOT NULL,
    information_completeness VARCHAR(32) NOT NULL,
    read_scope CLOB,
    write_scope CLOB,
    implementation_reference CLOB,
    acceptance_criteria CLOB,
    related_pages_json CLOB,
    evidence_json CLOB,
    code_references_json CLOB,
    status VARCHAR(24) NOT NULL,
    reviewed_by VARCHAR(96),
    reviewed_at TIMESTAMP,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE (page_id, finding_key)
);

CREATE TABLE control_ai_coding_task (
    task_id VARCHAR(40) NOT NULL,
    project_id BIGINT NOT NULL,
    project_code VARCHAR(96) NOT NULL,
    capability_key VARCHAR(64) NOT NULL,
    task_kind VARCHAR(64) NOT NULL,
    protocol_version VARCHAR(16) NOT NULL,
    executor_provider VARCHAR(24) NOT NULL,
    title VARCHAR(256) NOT NULL,
    objective CLOB NOT NULL,
    access_mode VARCHAR(24) NOT NULL,
    execution_status VARCHAR(32) NOT NULL,
    result_contract_key VARCHAR(128) NOT NULL,
    result_contract_version VARCHAR(32) NOT NULL,
    context_snapshot_json CLOB NOT NULL,
    last_message VARCHAR(1000),
    lock_version BIGINT NOT NULL,
    created_by VARCHAR(96),
    started_at TIMESTAMP,
    result_submitted_at TIMESTAMP,
    completed_at TIMESTAMP,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    PRIMARY KEY (task_id)
);

CREATE TABLE control_ai_coding_project_policy (
    project_id BIGINT NOT NULL,
    handoff_activation_ttl_hours INT NOT NULL,
    task_token_ttl_hours INT NOT NULL,
    updated_by VARCHAR(96),
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    PRIMARY KEY (project_id)
);

CREATE TABLE control_ai_coding_task_target (
    id BIGINT NOT NULL AUTO_INCREMENT,
    task_id VARCHAR(40) NOT NULL,
    target_type VARCHAR(40) NOT NULL,
    target_key VARCHAR(256) NOT NULL,
    target_role VARCHAR(24) NOT NULL,
    access_mode VARCHAR(24) NOT NULL,
    snapshot_json CLOB,
    created_at TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE (task_id, target_type, target_key, target_role)
);

CREATE TABLE control_ai_coding_task_handoff (
    handoff_id VARCHAR(40) NOT NULL,
    task_id VARCHAR(40) NOT NULL,
    activation_code_hash CHAR(64) NOT NULL,
    activation_status VARCHAR(24) NOT NULL,
    activation_attempts INT NOT NULL,
    last_activation_attempt_at TIMESTAMP,
    activation_expires_at TIMESTAMP NOT NULL,
    task_token_hash CHAR(64),
    token_expires_at TIMESTAMP,
    client_provider VARCHAR(24),
    client_session_ref VARCHAR(256),
    activated_at TIMESTAMP,
    last_seen_at TIMESTAMP,
    lease_expires_at TIMESTAMP,
    closed_at TIMESTAMP,
    close_reason VARCHAR(128),
    issued_by VARCHAR(96),
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    PRIMARY KEY (handoff_id)
);

CREATE TABLE control_ai_coding_task_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    task_id VARCHAR(40) NOT NULL,
    client_event_id VARCHAR(96),
    event_type VARCHAR(40) NOT NULL,
    execution_status_after VARCHAR(32),
    message VARCHAR(1000),
    payload_json CLOB,
    actor_type VARCHAR(24) NOT NULL,
    actor_name VARCHAR(96),
    created_at TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE (task_id, client_event_id)
);

CREATE TABLE control_ai_coding_task_question (
    question_id VARCHAR(96) NOT NULL,
    task_id VARCHAR(40) NOT NULL,
    title VARCHAR(256) NOT NULL,
    body CLOB NOT NULL,
    options_json CLOB,
    status VARCHAR(24) NOT NULL,
    answer CLOB,
    asked_by VARCHAR(96),
    answered_by VARCHAR(96),
    asked_at TIMESTAMP,
    answered_at TIMESTAMP,
    updated_at TIMESTAMP,
    PRIMARY KEY (question_id)
);

CREATE TABLE control_ai_coding_task_artifact (
    artifact_id BIGINT NOT NULL AUTO_INCREMENT,
    task_id VARCHAR(40) NOT NULL,
    artifact_key VARCHAR(128) NOT NULL,
    contract_key VARCHAR(128) NOT NULL,
    contract_version VARCHAR(32) NOT NULL,
    content_hash CHAR(64) NOT NULL,
    content_json CLOB NOT NULL,
    processing_status VARCHAR(24) NOT NULL,
    validation_message VARCHAR(1000),
    application_result_json CLOB,
    reported_by VARCHAR(96),
    created_at TIMESTAMP,
    validated_at TIMESTAMP,
    applied_at TIMESTAMP,
    PRIMARY KEY (artifact_id),
    UNIQUE (task_id, artifact_key)
);
