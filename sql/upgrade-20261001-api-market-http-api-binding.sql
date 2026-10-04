-- BMAPI-3D: Capability-owned selection -> HTTP API owner relation.
-- Existing integrations remain intent-only until explicitly reselected. No acceptance,
-- Runtime connection, credential, ACL, verification or workflow pin is migrated.
SET @market_schema = DATABASE();
SET @market_ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@market_schema
    AND TABLE_NAME='capability_project_external_api' AND COLUMN_NAME='selection_revision')=0,
    'ALTER TABLE capability_project_external_api ADD COLUMN selection_revision BIGINT NOT NULL DEFAULT 1 COMMENT ''Explicit catalog selection revision, not verification proof''', 'SELECT 1');
PREPARE market_stmt FROM @market_ddl;
EXECUTE market_stmt;
DEALLOCATE PREPARE market_stmt;
SET @market_ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@market_schema
    AND TABLE_NAME='capability_external_api_operation' AND COLUMN_NAME='response_content_type')=0,
    'ALTER TABLE capability_external_api_operation ADD COLUMN response_content_type VARCHAR(128) DEFAULT NULL COMMENT ''Source-declared response media type; missing is unsupported''', 'SELECT 1');
PREPARE market_stmt FROM @market_ddl;
EXECUTE market_stmt;
DEALLOCATE PREPARE market_stmt;
SET @market_ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@market_schema
    AND TABLE_NAME='capability_external_api_operation' AND COLUMN_NAME='response_status')=0,
    'ALTER TABLE capability_external_api_operation ADD COLUMN response_status INT DEFAULT NULL COMMENT ''Source-declared success status; missing is unsupported''', 'SELECT 1');
PREPARE market_stmt FROM @market_ddl;
EXECUTE market_stmt;
DEALLOCATE PREPARE market_stmt;
SET @market_ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@market_schema
    AND TABLE_NAME='capability_project_external_api_operation' AND COLUMN_NAME='api_asset_id')=0,
    'ALTER TABLE capability_project_external_api_operation ADD COLUMN api_asset_id BIGINT DEFAULT NULL COMMENT ''Server-derived HTTP API owner; explicit reselection required''', 'SELECT 1');
PREPARE market_stmt FROM @market_ddl;
EXECUTE market_stmt;
DEALLOCATE PREPARE market_stmt;
SET @market_ddl = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=@market_schema
    AND TABLE_NAME='capability_project_external_api_operation' AND INDEX_NAME='idx_project_external_api_operation_asset')=0,
    'ALTER TABLE capability_project_external_api_operation ADD INDEX idx_project_external_api_operation_asset (api_asset_id)', 'SELECT 1');
PREPARE market_stmt FROM @market_ddl;
EXECUTE market_stmt;
DEALLOCATE PREPARE market_stmt;
