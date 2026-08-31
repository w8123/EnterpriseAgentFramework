CREATE TABLE control_agent_skill (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    scope_key VARCHAR(192) NOT NULL,
    publisher VARCHAR(64) NOT NULL,
    standard_name VARCHAR(64) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    description VARCHAR(1024) NOT NULL,
    visibility VARCHAR(24) NOT NULL,
    owner_user_id BIGINT,
    project_code VARCHAR(128),
    status VARCHAR(24) NOT NULL,
    latest_version_id BIGINT,
    default_version_id BIGINT,
    created_by VARCHAR(128),
    updated_by VARCHAR(128),
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    CONSTRAINT uk_control_agent_skill_identity UNIQUE (scope_key, publisher, standard_name)
);

CREATE TABLE control_agent_skill_version (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    skill_id BIGINT NOT NULL,
    version VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    source_type VARCHAR(24) NOT NULL,
    source_ref VARCHAR(512),
    source_sha256 CHAR(64) NOT NULL,
    content_tree_sha256 CHAR(64) NOT NULL,
    artifact_key VARCHAR(256) NOT NULL,
    artifact_size BIGINT NOT NULL,
    declared_license VARCHAR(512),
    declared_compatibility VARCHAR(500),
    has_scripts BOOLEAN NOT NULL,
    frontmatter_json CLOB NOT NULL,
    package_manifest_json CLOB NOT NULL,
    validation_report_json CLOB NOT NULL,
    risk_report_json CLOB NOT NULL,
    compatibility_report_json CLOB NOT NULL,
    reviewed_by VARCHAR(128),
    reviewed_at TIMESTAMP,
    published_by VARCHAR(128),
    published_at TIMESTAMP,
    created_by VARCHAR(128),
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    CONSTRAINT uk_control_agent_skill_version UNIQUE (skill_id, version)
);

CREATE TABLE control_agent_skill_review (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    skill_id BIGINT NOT NULL,
    skill_version_id BIGINT NOT NULL,
    decision VARCHAR(16) NOT NULL,
    comment VARCHAR(2000),
    findings_json CLOB,
    reviewer VARCHAR(128) NOT NULL,
    created_at TIMESTAMP
);
