import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Connector/J-backed guarded executor used by scripts/apply-memory-migrations.mjs and
 * the explicitly reviewed current root-level schema migration.
 *
 * The outer Node runner validates explicit execution intent for its normal workflow; direct
 * callers must establish the same authorization and exact target scope before starting it.
 * This helper deliberately never prints the JDBC URL, host, database, username, password,
 * SQL text, row content, or raw SQLException messages.
 */
public final class ReachAiMysqlMigrationRunner {
    private static final String RESULT_SCHEMA = "reachai-memory-migration-execution-v1";
    private static final String REQUIRED_DATABASE = "reach_ai";
    private static final String MIGRATION_LOCK = "reachai-memory-migrations-v1";

    private static final List<String> REQUIRED_BASE_TABLES = Arrays.asList(
            "control_context_item",
            "control_context_memory_candidate",
            "control_context_namespace",
            "control_context_runtime_user_mapping",
            "control_platform_permission",
            "control_platform_role",
            "control_platform_role_permission",
            "knowledge_business_index",
            "knowledge_business_index_record"
    );

    private ReachAiMysqlMigrationRunner() {
    }

    public static void main(String[] args) {
        RunnerResult result;
        try {
            result = run(args, System.getenv());
        } catch (RunnerFailure failure) {
            result = RunnerResult.failed(failure);
        } catch (Throwable failure) {
            result = RunnerResult.failed(new RunnerFailure(
                    "UNEXPECTED_RUNNER_FAILURE", "RUNNER", null, null, null, failure));
        }
        System.out.println(result.toJson());
        if (!result.passed) {
            System.exit(1);
        }
    }

    static RunnerResult run(String[] args, Map<String, String> environment) throws RunnerFailure {
        if (args.length == 1 && "--self-test".equals(args[0])) {
            runParserSelfTest();
            return RunnerResult.selfTestPassed();
        }
        if (args.length == 0) {
            throw new RunnerFailure("MIGRATION_SCRIPT_REQUIRED", "ARGUMENT", null, null, null, null);
        }

        boolean parseOnly = false;
        boolean preflightOnly = false;
        List<Path> scripts = new ArrayList<>();
        for (String arg : args) {
            if ("--parse-only".equals(arg)) {
                parseOnly = true;
                continue;
            }
            if ("--preflight-only".equals(arg)) {
                preflightOnly = true;
                continue;
            }
            if (!arg.startsWith("--script=")) {
                throw new RunnerFailure("UNSUPPORTED_ARGUMENT", "ARGUMENT", null, null, null, null);
            }
            Path script = Paths.get(arg.substring("--script=".length())).toAbsolutePath().normalize();
            if (!Files.isRegularFile(script)) {
                throw new RunnerFailure("MIGRATION_SCRIPT_NOT_FOUND", "ARGUMENT",
                        safeScriptName(script), null, null, null);
            }
            scripts.add(script);
        }
        if (scripts.isEmpty()) {
            throw new RunnerFailure("MIGRATION_SCRIPT_REQUIRED", "ARGUMENT", null, null, null, null);
        }
        if (parseOnly && preflightOnly) {
            throw new RunnerFailure("MODE_CONFLICT", "ARGUMENT", null, null, null, null);
        }
        if (parseOnly) {
            runParserSelfTest();
            List<ParsedScript> parsedScripts = new ArrayList<>();
            for (Path script : scripts) {
                try {
                    List<SqlUnit> statements = parseSql(Files.readString(script, StandardCharsets.UTF_8));
                    if (statements.isEmpty()) {
                        throw new RunnerFailure("MIGRATION_SCRIPT_EMPTY", "PARSER",
                                safeScriptName(script), null, null, null);
                    }
                    parsedScripts.add(new ParsedScript(safeScriptName(script), statements.size()));
                } catch (IOException error) {
                    throw new RunnerFailure("MIGRATION_SCRIPT_READ_FAILED", "SCRIPT",
                            safeScriptName(script), null, null, error);
                }
            }
            return RunnerResult.parseOnlyPassed(parsedScripts);
        }

        String jdbcUrl = requiredEnvironment(environment, "AI_MYSQL_URL");
        String username = requiredEnvironment(environment, "AI_MYSQL_USER");
        String password = requiredEnvironment(environment, "AI_MYSQL_PASSWORD");
        validateJdbcUrl(jdbcUrl);
        configureJavaTrustStore(environment);

        Properties properties = new Properties();
        properties.setProperty("user", username);
        properties.setProperty("password", password);
        properties.setProperty("useUnicode", "true");
        properties.setProperty("characterEncoding", "UTF-8");

        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException error) {
            throw new RunnerFailure("MYSQL_CONNECTOR_NOT_FOUND", "CONNECTOR", null, null, null, error);
        }

        Instant startedAt = Instant.now();
        List<MigrationResult> migrationResults = new ArrayList<>();
        ServerSummary serverSummary;
        boolean lockAcquired = false;
        try (Connection connection = DriverManager.getConnection(jdbcUrl, properties)) {
            connection.setAutoCommit(true);
            configureSession(connection);
            serverSummary = inspectServer(connection);
            validateBaseSchema(connection);
            if (preflightOnly) {
                return RunnerResult.passed(serverSummary, new ArrayList<>(),
                        Duration.between(startedAt, Instant.now()).toMillis());
            }
            lockAcquired = acquireMigrationLock(connection);
            if (!lockAcquired) {
                throw new RunnerFailure("MIGRATION_LOCK_UNAVAILABLE", "LOCK", null, null, null, null);
            }

            for (Path script : scripts) {
                try {
                    migrationResults.add(executeTwiceAndVerify(connection, script));
                } catch (RunnerFailure failure) {
                    return RunnerResult.failedAfterExecution(serverSummary, migrationResults,
                            Duration.between(startedAt, Instant.now()).toMillis(), failure);
                }
            }
        } catch (RunnerFailure failure) {
            throw failure;
        } catch (SQLException failure) {
            throw RunnerFailure.fromSql("MYSQL_CONNECTION_OR_PREFLIGHT_FAILED", "DATABASE",
                    null, null, failure);
        } finally {
            // The named lock is connection-scoped and is released automatically when the
            // connection closes. An explicit release is unnecessary and could mask failure.
            lockAcquired = false;
        }
        return RunnerResult.passed(serverSummary, migrationResults,
                Duration.between(startedAt, Instant.now()).toMillis());
    }

    private static String requiredEnvironment(Map<String, String> environment, String name)
            throws RunnerFailure {
        String value = environment.get(name);
        if (value == null || value.trim().isEmpty()) {
            throw new RunnerFailure("REQUIRED_ENVIRONMENT_MISSING", "ENVIRONMENT",
                    null, null, null, null);
        }
        return value;
    }

    private static void configureJavaTrustStore(Map<String, String> environment) throws RunnerFailure {
        String pathValue = environment.get("REACHAI_MYSQL_JAVA_TRUSTSTORE");
        String password = environment.get("REACHAI_MYSQL_JAVA_TRUSTSTORE_PASSWORD");
        if ((pathValue == null || pathValue.trim().isEmpty())
                && (password == null || password.isEmpty())) {
            return;
        }
        if (pathValue == null || pathValue.trim().isEmpty()
                || password == null || password.isEmpty()) {
            throw new RunnerFailure("JAVA_TRUSTSTORE_CONFIGURATION_INCOMPLETE", "TRANSPORT",
                    null, null, null, null);
        }
        Path trustStore = Paths.get(pathValue).toAbsolutePath().normalize();
        if (!Files.isRegularFile(trustStore)) {
            throw new RunnerFailure("JAVA_TRUSTSTORE_NOT_FOUND", "TRANSPORT",
                    null, null, null, null);
        }
        System.setProperty("javax.net.ssl.trustStore", trustStore.toString());
        System.setProperty("javax.net.ssl.trustStorePassword", password);
        String type = environment.get("REACHAI_MYSQL_JAVA_TRUSTSTORE_TYPE");
        if (type != null && !type.trim().isEmpty()) {
            System.setProperty("javax.net.ssl.trustStoreType", type.trim());
        }
    }

    private static void validateJdbcUrl(String jdbcUrl) throws RunnerFailure {
        if (!jdbcUrl.startsWith("jdbc:mysql://")) {
            throw new RunnerFailure("JDBC_URL_INVALID", "TRANSPORT", null, null, null, null);
        }
        try {
            URI uri = new URI(jdbcUrl.substring("jdbc:".length()));
            if (uri.getUserInfo() != null) {
                throw new RunnerFailure("JDBC_URL_EMBEDDED_CREDENTIALS", "TRANSPORT",
                        null, null, null, null);
            }
            String host = uri.getHost();
            if (host == null || host.trim().isEmpty()) {
                throw new RunnerFailure("JDBC_HOST_MISSING", "TRANSPORT",
                        null, null, null, null);
            }
            String database = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/", "");
            if (!REQUIRED_DATABASE.equals(database)) {
                throw new RunnerFailure("UNEXPECTED_DATABASE", "TRANSPORT", null, null, null, null);
            }
        } catch (URISyntaxException error) {
            throw new RunnerFailure("JDBC_URL_INVALID", "TRANSPORT", null, null, null, error);
        }
    }

    private static void configureSession(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET NAMES utf8mb4");
            statement.execute("SET SESSION innodb_lock_wait_timeout = 15");
            try {
                statement.execute("SET SESSION lock_wait_timeout = 15");
            } catch (SQLException ignored) {
                // MySQL variants may not expose metadata lock_wait_timeout. InnoDB's bounded
                // wait remains active, and each migration still fails closed on SQL errors.
            }
        }
    }

    private static ServerSummary inspectServer(Connection connection) throws SQLException, RunnerFailure {
        String database;
        String version;
        String databaseCharset;
        String connectionCharset;
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT DATABASE(), VERSION(), @@character_set_database, @@character_set_connection")) {
            if (!result.next()) {
                throw new RunnerFailure("SERVER_PREFLIGHT_EMPTY", "PREFLIGHT", null, null, null, null);
            }
            database = result.getString(1);
            version = result.getString(2);
            databaseCharset = result.getString(3);
            connectionCharset = result.getString(4);
        }
        if (!REQUIRED_DATABASE.equals(database)) {
            throw new RunnerFailure("UNEXPECTED_DATABASE", "PREFLIGHT", null, null, null, null);
        }
        int[] versionParts = parseVersion(version);
        boolean supported = version.toLowerCase(Locale.ROOT).contains("mariadb")
                ? versionParts[0] > 10 || (versionParts[0] == 10 && versionParts[1] >= 2)
                : versionParts[0] > 5 || (versionParts[0] == 5 && versionParts[1] >= 7);
        if (!supported) {
            throw new RunnerFailure("MYSQL_VERSION_UNSUPPORTED", "PREFLIGHT", null, null, null, null);
        }
        if (!"utf8mb4".equalsIgnoreCase(databaseCharset)
                || !"utf8mb4".equalsIgnoreCase(connectionCharset)) {
            throw new RunnerFailure("MYSQL_UTF8MB4_REQUIRED", "PREFLIGHT", null, null, null, null);
        }
        return new ServerSummary(versionParts[0], versionParts[1], databaseCharset, connectionCharset);
    }

    private static int[] parseVersion(String rawVersion) throws RunnerFailure {
        String[] parts = rawVersion == null ? new String[0] : rawVersion.split("[.-]", 3);
        try {
            if (parts.length < 2) {
                throw new NumberFormatException("missing version segment");
            }
            return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
        } catch (NumberFormatException error) {
            throw new RunnerFailure("MYSQL_VERSION_UNRECOGNIZED", "PREFLIGHT",
                    null, null, null, error);
        }
    }

    private static void validateBaseSchema(Connection connection) throws SQLException, RunnerFailure {
        String placeholders = String.join(",", java.util.Collections.nCopies(
                REQUIRED_BASE_TABLES.size(), "?"));
        String sql = "SELECT TABLE_NAME FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN (" + placeholders + ")";
        List<String> found = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < REQUIRED_BASE_TABLES.size(); index++) {
                statement.setString(index + 1, REQUIRED_BASE_TABLES.get(index));
            }
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    found.add(result.getString(1));
                }
            }
        }
        if (!found.containsAll(REQUIRED_BASE_TABLES)) {
            throw new RunnerFailure("BASE_SCHEMA_INCOMPLETE", "PREFLIGHT", null, null, null, null);
        }
        long duplicateActiveOwners = scalarLong(connection,
                "SELECT COUNT(*) FROM ("
                        + "SELECT 1 FROM control_context_runtime_user_mapping "
                        + "WHERE status = 'ACTIVE' GROUP BY tenant_id, platform_user_id HAVING COUNT(*) > 1"
                        + ") duplicate_owner_slots");
        if (duplicateActiveOwners != 0L) {
            throw new RunnerFailure("DUPLICATE_ACTIVE_RUNTIME_USER_MAPPING", "PREFLIGHT",
                    null, null, null, null);
        }
    }

    private static boolean acquireMigrationLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, 0)")) {
            statement.setString(1, MIGRATION_LOCK);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getInt(1) == 1;
            }
        }
    }

    private static MigrationResult executeTwiceAndVerify(Connection connection, Path script)
            throws RunnerFailure {
        String safeName = safeScriptName(script);
        List<SqlUnit> statements;
        try {
            statements = parseSql(Files.readString(script, StandardCharsets.UTF_8));
        } catch (IOException error) {
            throw new RunnerFailure("MIGRATION_SCRIPT_READ_FAILED", "SCRIPT",
                    safeName, null, null, error);
        }
        if (statements.isEmpty()) {
            throw new RunnerFailure("MIGRATION_SCRIPT_EMPTY", "SCRIPT",
                    safeName, null, null, null);
        }

        Instant startedAt = Instant.now();
        executeStatements(connection, safeName, statements, 1);
        List<CheckResult> firstChecks = verifyMigration(connection, safeName);
        executeStatements(connection, safeName, statements, 2);
        List<CheckResult> secondChecks = verifyMigration(connection, safeName);
        return new MigrationResult(safeName, statements.size(), firstChecks, secondChecks,
                Duration.between(startedAt, Instant.now()).toMillis());
    }

    private static void executeStatements(Connection connection, String script,
                                          List<SqlUnit> statements, int pass) throws RunnerFailure {
        for (int index = 0; index < statements.size(); index++) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(statements.get(index).sql);
            } catch (SQLException error) {
                throw RunnerFailure.fromSql("MIGRATION_STATEMENT_FAILED", "EXECUTE",
                        script, index + 1, error).withPass(pass);
            }
        }
    }

    private static List<CheckResult> verifyMigration(Connection connection, String script)
            throws RunnerFailure {
        try {
            List<CheckResult> checks = new ArrayList<>();
            switch (script) {
                case "upgrade-20260830-platform-consolidated.sql":
                    for (String section : Arrays.asList(
                            "upgrade-20260823-a2a-hub.sql",
                            "upgrade-20260823-agent-skill-catalog.sql",
                            "upgrade-20260823-api-market.sql",
                            "upgrade-20260824-agent-skill-market.sql",
                            "upgrade-20260824-eval-target-snapshot.sql",
                            "upgrade-20260824-evalops-experiments.sql",
                            "upgrade-20260824-runtime-checkpoint-v1.sql",
                            "upgrade-20260828-mcp-hub.sql")) {
                        checks.addAll(verifyMigration(connection, section));
                    }
                    checks.add(requireTables(connection, "consolidated.managed_executor.tables", 5,
                            "runtime_managed_execution", "runtime_managed_execution_event",
                            "runtime_managed_artifact", "runtime_managed_execution_outbox",
                            "control_managed_execution_inbox"));
                    checks.add(requireColumns(connection, "consolidated.managed_executor.columns", 6,
                            "runtime_managed_execution", "worker_token_digest", "lease_owner",
                            "lease_expires_at", "objective_sha256", "last_event_sequence",
                            "provision_available_at"));
                    checks.add(requireIndex(connection, "consolidated.managed_executor.lease_index",
                            "runtime_managed_execution", "idx_runtime_managed_execution_lease",
                            "status,lease_expires_at,id", false));
                    checks.add(requireTables(connection, "consolidated.automation.tables", 8,
                            "runtime_automation", "runtime_automation_version",
                            "runtime_automation_occurrence", "runtime_automation_attempt",
                            "runtime_automation_event", "runtime_automation_engine_command",
                            "runtime_automation_execution_slot", "runtime_scheduler_task"));
                    checks.add(requireAtLeast(connection, "consolidated.automation.permissions", 3,
                            "SELECT COUNT(*) FROM control_platform_permission "
                                    + "WHERE permission_code IN "
                                    + "('automation:read','automation:write','automation:operate')"));
                    checks.add(requireTables(connection, "consolidated.model_catalog.tables", 5,
                            "model_catalog_source", "model_catalog_setting",
                            "model_catalog_sync_run", "model_catalog_snapshot",
                            "model_catalog_change"));
                    checks.add(requireColumns(connection, "consolidated.model_catalog.columns", 5,
                            "model_template", "lifecycle_status", "source_url",
                            "source_checked_at", "recommendation_level", "recommendation_reason"));
                    checks.add(requireAtLeast(connection, "consolidated.model_catalog.setting", 1,
                            "SELECT COUNT(*) FROM model_catalog_setting WHERE id = 1"));
                    checks.add(requireAtLeast(connection, "consolidated.authorization.p1", 4,
                            "SELECT COUNT(*) FROM control_platform_permission WHERE permission_code IN "
                                    + "('workspace:build:access','workspace:operate:access',"
                                    + "'identity:business-user:read','identity:business-user:manage')"));
                    checks.add(requireAtLeast(connection, "consolidated.authorization.p2", 12,
                            "SELECT COUNT(*) FROM control_platform_permission WHERE "
                                    + "permission_code LIKE 'agent:%' OR permission_code LIKE 'workflow:%' "
                                    + "OR permission_code LIKE 'runops:%'"));
                    checks.add(requireColumns(connection, "consolidated.authorization.p3", 1,
                            "control_platform_role", "role_kind"));
                    checks.add(requireZero(connection, "consolidated.authorization.system_role_kind",
                            "SELECT COUNT(*) FROM control_platform_role WHERE role_code IN "
                                    + "('PLATFORM_ADMIN','AGENT_DESIGNER','PROJECT_OWNER','OPERATOR','AUDITOR') "
                                    + "AND role_kind <> 'SYSTEM'"));
                    break;
                case "upgrade-20260813-runtime-session-memory.sql":
                    checks.add(requireTables(connection, "runtime_session.tables", 2,
                            "runtime_conversation_session", "runtime_conversation_event"));
                    checks.add(requireTableProperties(connection, "runtime_session.table_properties", 2,
                            "runtime_conversation_session", "runtime_conversation_event"));
                    checks.add(requireIndex(connection, "runtime_session.session_scope_index",
                            "runtime_conversation_session", "uk_runtime_conversation_tenant_session",
                            "tenant_id,session_id", true));
                    checks.add(requireIndex(connection, "runtime_session.event_sequence_index",
                            "runtime_conversation_event", "uk_runtime_conversation_event_sequence",
                            "conversation_session_id,sequence_no", true));
                    break;
                case "upgrade-20260814-runtime-conversation-turn-id.sql":
                    checks.add(requireColumns(connection, "runtime_turn.columns", 1,
                            "runtime_conversation_event", "turn_id"));
                    checks.add(requireIndex(connection, "runtime_turn.unique_index",
                            "runtime_conversation_event", "uk_runtime_conversation_event_turn_role",
                            "conversation_session_id,turn_id,role", true));
                    checks.add(requireColumnDefinition(connection, "runtime_turn.column_definition",
                            "runtime_conversation_event", "turn_id", "varchar", 128L, false));
                    checks.add(requireZero(connection, "runtime_turn.null_rows",
                            "SELECT COUNT(*) FROM runtime_conversation_event "
                                    + "WHERE turn_id IS NULL OR CHAR_LENGTH(TRIM(turn_id)) = 0"));
                    break;
                case "upgrade-20260813-personal-memory-candidates.sql":
                    checks.add(requireTables(connection, "personal_memory.tables", 4,
                            "control_platform_auth_audit_event", "control_context_memory_outbox",
                            "knowledge_personal_memory_index", "knowledge_personal_memory_index_event"));
                    checks.add(requireTableProperties(connection, "personal_memory.table_properties", 4,
                            "control_platform_auth_audit_event", "control_context_memory_outbox",
                            "knowledge_personal_memory_index", "knowledge_personal_memory_index_event"));
                    checks.add(requireColumns(connection, "personal_memory.candidate_columns", 8,
                            "control_context_memory_candidate", "content_sha256", "dedupe_key",
                            "semantic_key", "conflict_item_id", "conflict_type", "occurrence_count",
                            "last_seen_at", "extraction_version"));
                    checks.add(requireColumns(connection, "personal_memory.owner_slot", 1,
                            "control_context_runtime_user_mapping", "active_marker"));
                    checks.add(requireIndex(connection, "personal_memory.owner_unique_index",
                            "control_context_runtime_user_mapping",
                            "uk_context_runtime_user_mapping_active",
                            "tenant_id,platform_user_id,active_marker", true));
                    checks.add(requireIndex(connection, "personal_memory.candidate_dedupe_index",
                            "control_context_memory_candidate", "uk_context_memory_candidate_dedupe",
                            "dedupe_key", true));
                    checks.add(requireIndex(connection, "personal_memory.candidate_semantic_index",
                            "control_context_memory_candidate", "idx_context_memory_candidate_semantic",
                            "namespace_id,semantic_key,status", false));
                    checks.add(requireIndex(connection, "personal_memory.outbox_event_index",
                            "control_context_memory_outbox", "uk_context_memory_outbox_event",
                            "event_id", true));
                    checks.add(requireIndex(connection, "personal_memory.projection_scope_index",
                            "knowledge_personal_memory_index", "uk_knowledge_personal_memory",
                            "tenant_id,runtime_user_hash,memory_id", true));
                    checks.add(requireColumnDefinition(connection, "personal_memory.owner_slot_definition",
                            "control_context_runtime_user_mapping", "active_marker",
                            "tinyint", null, true));
                    checks.add(requireZero(connection, "personal_memory.duplicate_active_owners",
                            "SELECT COUNT(*) FROM (SELECT 1 FROM control_context_runtime_user_mapping "
                                    + "WHERE status = 'ACTIVE' GROUP BY tenant_id, platform_user_id "
                                    + "HAVING COUNT(*) > 1) duplicate_owner_slots"));
                    break;
                case "upgrade-20260815-personal-memory-outbox-redaction.sql":
                    checks.add(requireZero(connection, "personal_memory.outbox_unsafe_error",
                            "SELECT COUNT(*) FROM control_context_memory_outbox "
                                    + "WHERE aggregate_type = 'PERSONAL_MEMORY' AND last_error IS NOT NULL "
                                    + "AND last_error NOT REGEXP '^PUBLISH_[A-Z0-9_]{1,56}$'"));
                    checks.add(requireZero(connection, "personal_memory.outbox_unredacted_receipt",
                            "SELECT COUNT(*) FROM control_context_memory_outbox "
                                    + "WHERE aggregate_type = 'PERSONAL_MEMORY' AND status = 'PUBLISHED' "
                                    + "AND (JSON_VALID(payload_json) = 0 OR "
                                    + "COALESCE(JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.schema')), '') "
                                    + "<> 'reachai-personal-memory-index-receipt-v1')"));
                    break;
                case "upgrade-20260815-personal-memory-semantic-projection.sql":
                    checks.add(requireColumns(connection, "personal_memory.semantic_columns", 12,
                            "knowledge_personal_memory_index", "embedding_vector", "embedding_format",
                            "embedding_dimension", "embedding_model_instance_id",
                            "embedding_source_version", "embedding_text_sha256", "embedding_status",
                            "embedding_attempts", "embedding_error_code", "embedding_next_attempt_at",
                            "embedding_claim_token", "embedding_claim_until"));
                    checks.add(requireColumnDefinition(connection, "personal_memory.vector_definition",
                            "knowledge_personal_memory_index", "embedding_vector",
                            "mediumblob", null, true));
                    checks.add(requireColumnDefinition(connection, "personal_memory.embedding_status_definition",
                            "knowledge_personal_memory_index", "embedding_status",
                            "varchar", 24L, false));
                    checks.add(requireIndex(connection, "personal_memory.semantic_index",
                            "knowledge_personal_memory_index",
                            "idx_knowledge_personal_memory_embedding",
                            "embedding_status,embedding_next_attempt_at,embedding_claim_until,id",
                            false));
                    checks.add(requireZero(connection, "personal_memory.deleted_vector_residue",
                            "SELECT COUNT(*) FROM knowledge_personal_memory_index "
                                    + "WHERE status <> 'ACTIVE' AND (embedding_vector IS NOT NULL "
                                    + "OR embedding_text_sha256 IS NOT NULL)"));
                    break;
                case "upgrade-20260814-business-memory-reference.sql":
                    checks.add(requireColumns(connection, "business_memory.index_columns", 2,
                            "knowledge_business_index", "agent_memory_enabled", "resolver_capability_key"));
                    checks.add(requireColumns(connection, "business_memory.record_columns", 2,
                            "knowledge_business_index_record", "source_version", "source_updated_at"));
                    checks.add(requireColumnDefinition(connection, "business_memory.enabled_definition",
                            "knowledge_business_index", "agent_memory_enabled",
                            "tinyint", null, false));
                    checks.add(requireColumnDefinition(connection, "business_memory.resolver_definition",
                            "knowledge_business_index", "resolver_capability_key",
                            "varchar", 128L, true));
                    checks.add(requireColumnDefinition(connection, "business_memory.version_definition",
                            "knowledge_business_index_record", "source_version",
                            "varchar", 128L, true));
                    checks.add(requireIndex(connection, "business_memory.index_scope",
                            "knowledge_business_index", "idx_biz_agent_memory",
                            "tenant_id,project_code,agent_memory_enabled,status", false));
                    checks.add(requireIndex(connection, "business_memory.source_version_index",
                            "knowledge_business_index_record", "idx_biz_source_version",
                            "index_code,biz_id,source_version", false));
                    break;
                case "upgrade-20260815-runtime-session-retention.sql":
                    checks.add(requireTables(connection, "runtime_retention.tables", 2,
                            "runtime_session_retention_policy", "runtime_session_retention_audit"));
                    checks.add(requireTableProperties(connection, "runtime_retention.table_properties", 2,
                            "runtime_session_retention_policy", "runtime_session_retention_audit"));
                    checks.add(requireColumns(connection, "runtime_retention.session_columns", 9,
                            "runtime_conversation_session", "legal_hold", "legal_hold_reason_code",
                            "legal_hold_reference", "legal_hold_set_at", "legal_hold_set_by_hash",
                            "lifecycle_owner", "lifecycle_lease_expires_at", "lifecycle_reason_code",
                            "lifecycle_previous_status"));
                    checks.add(requireColumnDefinition(connection, "runtime_retention.legal_hold_definition",
                            "runtime_conversation_session", "legal_hold", "tinyint", null, false));
                    checks.add(requireIndex(connection, "runtime_retention.retention_index",
                            "runtime_conversation_session", "idx_runtime_conversation_retention",
                            "legal_hold,status,updated_at", false));
                    checks.add(requireIndex(connection, "runtime_retention.cleared_index",
                            "runtime_conversation_session", "idx_runtime_conversation_cleared_retention",
                            "legal_hold,status,cleared_at", false));
                    checks.add(requireIndex(connection, "runtime_retention.lease_index",
                            "runtime_conversation_session", "idx_runtime_conversation_lifecycle_lease",
                            "status,lifecycle_lease_expires_at", false));
                    checks.add(requirePermissionBinding(connection, "runtime_retention.permission",
                            "runtime:session:retention:manage"));
                    break;
                case "upgrade-20260815-knowledge-enterprise-ingress.sql":
                    checks.add(requireTables(connection, "knowledge_ingress.tables", 3,
                            "capability_registry_enrollment_token", "capability_internal_auth_nonce",
                            "capability_registry_request_nonce"));
                    checks.add(requireTableProperties(connection, "knowledge_ingress.table_properties", 3,
                            "capability_registry_enrollment_token", "capability_internal_auth_nonce",
                            "capability_registry_request_nonce"));
                    checks.add(requireIndex(connection, "knowledge_ingress.enrollment_digest_index",
                            "capability_registry_enrollment_token",
                            "uk_capability_registry_enrollment_digest", "token_digest", true));
                    checks.add(requireIndex(connection, "knowledge_ingress.internal_nonce_primary",
                            "capability_internal_auth_nonce", "PRIMARY", "nonce", true));
                    checks.add(requireIndex(connection, "knowledge_ingress.project_nonce_primary",
                            "capability_registry_request_nonce", "PRIMARY", "nonce_digest", true));
                    break;
                case "upgrade-20260815-cross-domain-memory-erasure.sql":
                    checks.add(requireTables(connection, "memory_erasure.tables", 2,
                            "control_memory_erasure_request", "control_memory_erasure_domain"));
                    checks.add(requireTableProperties(connection, "memory_erasure.table_properties", 2,
                            "control_memory_erasure_request", "control_memory_erasure_domain"));
                    checks.add(requireColumns(connection, "memory_erasure.outbox_correlation", 1,
                            "control_context_memory_outbox", "correlation_id"));
                    checks.add(requireColumnDefinition(connection, "memory_erasure.correlation_definition",
                            "control_context_memory_outbox", "correlation_id",
                            "varchar", 64L, true));
                    checks.add(requireIndex(connection, "memory_erasure.outbox_correlation_index",
                            "control_context_memory_outbox", "idx_context_memory_outbox_correlation",
                            "correlation_id,status,id", false));
                    checks.add(requireIndex(connection, "memory_erasure.request_id_index",
                            "control_memory_erasure_request", "uk_memory_erasure_request_id",
                            "request_id", true));
                    checks.add(requireIndex(connection, "memory_erasure.client_idempotency_index",
                            "control_memory_erasure_request", "uk_memory_erasure_client",
                            "tenant_id,client_request_id", true));
                    checks.add(requireIndex(connection, "memory_erasure.domain_unique_index",
                            "control_memory_erasure_domain", "uk_memory_erasure_domain",
                            "erasure_request_id,domain_code", true));
                    checks.add(requirePermissionBinding(connection, "memory_erasure.permission",
                            "context:memory:erasure:manage"));
                    break;
                case "upgrade-20260823-agent-skill-catalog.sql":
                    checks.add(requireTables(connection, "agent_skill.tables", 5,
                            "control_internal_auth_nonce",
                            "control_agent_skill",
                            "control_agent_skill_version",
                            "control_agent_skill_review",
                            "runtime_agent_skill_binding"));
                    checks.add(requireTableProperties(connection, "agent_skill.table_properties", 5,
                            "control_internal_auth_nonce",
                            "control_agent_skill",
                            "control_agent_skill_version",
                            "control_agent_skill_review",
                            "runtime_agent_skill_binding"));
                    checks.add(requireColumns(connection, "agent_skill.binding_columns", 8,
                            "runtime_agent_skill_binding", "agent_id", "agent_config_version_id",
                            "skill_id", "skill_version_id", "publisher", "standard_name",
                            "source_sha256", "content_tree_sha256"));
                    checks.add(requireIndex(connection, "agent_skill.binding_identity",
                            "runtime_agent_skill_binding", "uk_runtime_agent_config_skill",
                            "agent_config_version_id,publisher,standard_name", true));
                    checks.add(requireIndex(connection, "agent_skill.version_identity",
                            "control_agent_skill_version", "uk_control_agent_skill_version",
                            "skill_id,version", true));
                    checks.add(requireAtLeast(connection, "agent_skill.permissions", 6,
                            "SELECT COUNT(*) FROM control_platform_permission "
                                    + "WHERE permission_code LIKE 'skill:%'"));
                    break;
                case "upgrade-20260823-api-market.sql":
                    checks.add(requireTables(connection, "api_market.tables", 9,
                            "capability_external_api_source",
                            "capability_external_api_provider",
                            "capability_external_api_entry",
                            "capability_external_api_version",
                            "capability_external_api_operation",
                            "capability_external_api_verification",
                            "capability_external_api_sync_run",
                            "capability_project_external_api",
                            "capability_project_external_api_operation"));
                    checks.add(requireTableProperties(connection, "api_market.table_properties", 9,
                            "capability_external_api_source",
                            "capability_external_api_provider",
                            "capability_external_api_entry",
                            "capability_external_api_version",
                            "capability_external_api_operation",
                            "capability_external_api_verification",
                            "capability_external_api_sync_run",
                            "capability_project_external_api",
                            "capability_project_external_api_operation"));
                    checks.add(requireIndex(connection, "api_market.entry_identity",
                            "capability_external_api_entry", "uk_external_api_entry_key",
                            "entry_key", true));
                    checks.add(requireIndex(connection, "api_market.integration_identity",
                            "capability_project_external_api", "uk_project_external_api",
                            "project_id,entry_id,environment", true));
                    checks.add(requireAtLeast(connection, "api_market.seed_entries", 6,
                            "SELECT COUNT(*) FROM capability_external_api_entry WHERE entry_key IN "
                                    + "('open-meteo','rest-countries','github-rest',"
                                    + "'open-library-search','nager-date','nasa-apod')"));
                    checks.add(requireAtLeast(connection, "api_market.seed_operations", 6,
                            "SELECT COUNT(*) FROM capability_external_api_operation o "
                                    + "JOIN capability_external_api_version v ON v.id = o.version_id "
                                    + "JOIN capability_external_api_entry e ON e.id = v.entry_id "
                                    + "WHERE e.entry_key IN ('open-meteo','rest-countries','github-rest',"
                                    + "'open-library-search','nager-date','nasa-apod')"));
                    checks.add(requireAtLeast(connection, "api_market.verification_evidence", 4,
                            "SELECT COUNT(*) FROM capability_external_api_verification vf "
                                    + "JOIN capability_external_api_entry e ON e.id = vf.entry_id "
                                    + "WHERE e.entry_key IN ('open-meteo','rest-countries',"
                                    + "'github-rest','nager-date') AND vf.verification_type = 'CONNECTIVITY'"));
                    checks.add(requireZero(connection, "api_market.credential_columns",
                            "SELECT COUNT(*) FROM information_schema.COLUMNS "
                                    + "WHERE TABLE_SCHEMA = DATABASE() "
                                    + "AND TABLE_NAME LIKE 'capability%external_api%' "
                                    + "AND COLUMN_NAME REGEXP 'secret|token|api_key|password'"));
                    checks.add(requireZero(connection, "api_market.seed_mojibake",
                            "SELECT COUNT(*) FROM capability_external_api_entry "
                                    + "WHERE entry_key IN ('open-meteo','rest-countries','github-rest',"
                                    + "'open-library-search','nager-date','nasa-apod') "
                                    + "AND INSTR(CONCAT(title, summary), '???') > 0"));
                    break;
                case "upgrade-20260823-a2a-hub.sql":
                    checks.add(requireTables(connection, "a2a_hub.tables", 19,
                            "control_a2a_trust_profile",
                            "control_a2a_credential",
                            "control_a2a_principal",
                            "control_a2a_publication",
                            "control_a2a_publication_revision",
                            "control_a2a_remote_agent",
                            "control_a2a_remote_agent_revision",
                            "control_a2a_context",
                            "control_a2a_task",
                            "control_a2a_outbound_execution",
                            "control_a2a_message",
                            "control_a2a_artifact",
                            "control_a2a_task_event",
                            "control_a2a_push_notification_config",
                            "control_a2a_transport_event",
                            "control_a2a_rate_limit_window",
                            "control_a2a_conformance_run",
                            "control_a2a_outbox",
                            "runtime_agent_remote_agent_binding"));
                    checks.add(requireTableProperties(connection, "a2a_hub.table_properties", 19,
                            "control_a2a_trust_profile",
                            "control_a2a_credential",
                            "control_a2a_principal",
                            "control_a2a_publication",
                            "control_a2a_publication_revision",
                            "control_a2a_remote_agent",
                            "control_a2a_remote_agent_revision",
                            "control_a2a_context",
                            "control_a2a_task",
                            "control_a2a_outbound_execution",
                            "control_a2a_message",
                            "control_a2a_artifact",
                            "control_a2a_task_event",
                            "control_a2a_push_notification_config",
                            "control_a2a_transport_event",
                            "control_a2a_rate_limit_window",
                            "control_a2a_conformance_run",
                            "control_a2a_outbox",
                            "runtime_agent_remote_agent_binding"));
                    checks.add(requireColumns(connection, "a2a_hub.task_columns", 10,
                            "control_a2a_task", "task_id", "direction", "principal_id",
                            "tenant_scope", "remote_agent_id", "remote_revision_id",
                            "remote_task_id", "origin_message_id", "cancel_phase", "deadline_at"));
                    checks.add(requireColumns(connection, "a2a_hub.outbound_columns", 10,
                            "control_a2a_outbound_execution", "task_ref_id", "runtime_binding_id",
                            "agent_config_version_id", "principal_trust_profile_id",
                            "remote_trust_profile_id", "remote_interface_key", "credential_id",
                            "poll_status", "next_poll_at", "lease_until"));
                    checks.add(requireColumns(connection, "a2a_hub.remote_revision_columns", 4,
                            "control_a2a_remote_agent_revision", "default_input_modes_json",
                            "default_output_modes_json", "signature_status", "signing_key_id"));
                    checks.add(requireColumns(connection, "a2a_hub.remote_agent_columns", 1,
                            "control_a2a_remote_agent", "preferred_security_scheme_key"));
                    checks.add(requireColumns(connection, "a2a_hub.encrypted_message_columns", 5,
                            "control_a2a_message", "payload_ciphertext", "payload_object_ref",
                            "encryption_key_id", "encryption_nonce", "idempotency_sha256"));
                    checks.add(requireColumns(connection, "a2a_hub.credential_columns", 6,
                            "control_a2a_credential", "material_hash", "material_salt",
                            "material_ciphertext", "encryption_key_id", "encryption_nonce",
                            "external_ref"));
                    checks.add(requireColumns(connection, "a2a_hub.runtime_binding_columns", 7,
                            "runtime_agent_remote_agent_binding", "agent_config_version_id",
                            "principal_id", "remote_agent_id", "remote_agent_revision_id",
                            "allowed_skill_ids_json", "permission_key", "timeout_ms"));
                    checks.add(requireIndex(connection, "a2a_hub.task_idempotency_index",
                            "control_a2a_task", "uk_a2a_task_message_idempotency",
                            "direction,principal_id,tenant_scope,origin_message_id", true));
                    checks.add(requireIndex(connection, "a2a_hub.outbound_poll_index",
                            "control_a2a_outbound_execution", "idx_a2a_outbound_poll_claim",
                            "poll_status,next_poll_at,lease_until", false));
                    checks.add(requireIndex(connection, "a2a_hub.task_event_sequence_index",
                            "control_a2a_task_event", "uk_a2a_task_event_sequence",
                            "task_ref_id,sequence_no", true));
                    checks.add(requireIndex(connection, "a2a_hub.runtime_binding_tool_index",
                            "runtime_agent_remote_agent_binding", "uk_runtime_agent_remote_tool",
                            "agent_config_version_id,tool_name", true));
                    checks.add(requireAtLeast(connection, "a2a_hub.permissions", 8,
                            "SELECT COUNT(*) FROM control_platform_permission "
                                    + "WHERE permission_code LIKE 'a2a-hub:%'"));
                    checks.add(requireZero(connection, "a2a_hub.legacy_tables",
                            "SELECT COUNT(*) FROM information_schema.TABLES "
                                    + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN "
                                    + "('control_a2a_endpoint','control_a2a_call_log')"));
                    checks.add(requireZero(connection, "a2a_hub.legacy_task_shape",
                            "SELECT COUNT(*) FROM information_schema.COLUMNS "
                                    + "WHERE TABLE_SCHEMA = DATABASE() "
                                    + "AND TABLE_NAME = 'control_a2a_task' "
                                    + "AND COLUMN_NAME = 'endpoint_id'"));
                    break;
                case "upgrade-20260824-runtime-checkpoint-v1.sql":
                    checks.add(requireColumns(connection, "runtime_checkpoint_v1.columns", 4,
                            "runtime_interaction_session",
                            "checkpoint_schema_version", "execution_engine_version",
                            "checkpoint_digest", "checkpoint_size_bytes"));
                    checks.add(requireColumnDefinition(connection, "runtime_checkpoint_v1.schema_definition",
                            "runtime_interaction_session", "checkpoint_schema_version",
                            "smallint", null, false));
                    checks.add(requireColumnDefinition(connection, "runtime_checkpoint_v1.engine_definition",
                            "runtime_interaction_session", "execution_engine_version",
                            "varchar", 32L, false));
                    checks.add(requireColumnDefinition(connection, "runtime_checkpoint_v1.digest_definition",
                            "runtime_interaction_session", "checkpoint_digest",
                            "char", 64L, true));
                    checks.add(requireColumnDefinition(connection, "runtime_checkpoint_v1.size_definition",
                            "runtime_interaction_session", "checkpoint_size_bytes",
                            "int", null, true));
                    checks.add(requireZero(connection, "runtime_checkpoint_v1.invalid_v1_metadata",
                            "SELECT COUNT(*) FROM runtime_interaction_session "
                                    + "WHERE checkpoint_schema_version = 1 AND ("
                                    + "execution_engine_version <> 'RUNTIME_KERNEL_V2' "
                                    + "OR checkpoint_digest IS NULL OR CHAR_LENGTH(checkpoint_digest) <> 64 "
                                    + "OR checkpoint_size_bytes IS NULL OR checkpoint_size_bytes < 2 "
                                    + "OR checkpoint_size_bytes > 1048576)"));
                    checks.add(requireZero(connection, "runtime_checkpoint_v1.invalid_legacy_metadata",
                            "SELECT COUNT(*) FROM runtime_interaction_session "
                                    + "WHERE checkpoint_schema_version = 0 "
                                    + "AND execution_engine_version <> 'LEGACY'"));
                    break;
                case "upgrade-20260824-eval-target-snapshot.sql":
                    checks.add(requireTables(connection, "eval_target_snapshot.tables", 1,
                            "runtime_eval_target_snapshot"));
                    checks.add(requireTableProperties(connection, "eval_target_snapshot.table_properties", 1,
                            "runtime_eval_target_snapshot"));
                    checks.add(requireColumns(connection, "eval_target_snapshot.run_columns", 4,
                            "runtime_agent_eval_run",
                            "target_snapshot_id", "target_config_version_id",
                            "target_config_status", "target_fingerprint"));
                    checks.add(requireColumnDefinition(connection, "eval_target_snapshot.fingerprint_definition",
                            "runtime_eval_target_snapshot", "fingerprint_sha256",
                            "char", 64L, false));
                    checks.add(requireIndex(connection, "eval_target_snapshot.fingerprint_index",
                            "runtime_eval_target_snapshot", "uk_runtime_eval_target_fingerprint",
                            "tenant_id,target_type,target_id,fingerprint_sha256", true));
                    checks.add(requireIndex(connection, "eval_target_snapshot.run_index",
                            "runtime_agent_eval_run", "idx_eval_run_target_snapshot",
                            "target_snapshot_id,create_time", false));
                    break;
                case "upgrade-20260824-evalops-experiments.sql":
                    checks.add(requireTables(connection, "evalops.tables", 9,
                            "runtime_eval_dataset",
                            "runtime_eval_dataset_version",
                            "runtime_eval_dataset_item",
                            "runtime_eval_evaluator_suite_version",
                            "runtime_eval_experiment",
                            "runtime_eval_experiment_variant",
                            "runtime_eval_experiment_item",
                            "runtime_eval_score",
                            "runtime_eval_task"));
                    checks.add(requireTableProperties(connection, "evalops.table_properties", 9,
                            "runtime_eval_dataset",
                            "runtime_eval_dataset_version",
                            "runtime_eval_dataset_item",
                            "runtime_eval_evaluator_suite_version",
                            "runtime_eval_experiment",
                            "runtime_eval_experiment_variant",
                            "runtime_eval_experiment_item",
                            "runtime_eval_score",
                            "runtime_eval_task"));
                    checks.add(requireColumns(connection, "evalops.task_lease_columns", 8,
                            "runtime_eval_task",
                            "status", "attempt_count", "max_attempts", "available_at",
                            "lease_owner", "lease_token", "leased_until", "last_error_code"));
                    checks.add(requireIndex(connection, "evalops.dataset_identity",
                            "runtime_eval_dataset", "uk_runtime_eval_dataset_name",
                            "tenant_id,target_type,target_id,name", true));
                    checks.add(requireIndex(connection, "evalops.dataset_version_identity",
                            "runtime_eval_dataset_version", "uk_runtime_eval_dataset_version",
                            "dataset_id,version_no", true));
                    checks.add(requireIndex(connection, "evalops.dataset_item_identity",
                            "runtime_eval_dataset_item", "uk_runtime_eval_dataset_item",
                            "dataset_version_id,item_key", true));
                    checks.add(requireIndex(connection, "evalops.evaluator_fingerprint_identity",
                            "runtime_eval_evaluator_suite_version",
                            "uk_runtime_eval_evaluator_suite_fingerprint",
                            "tenant_id,fingerprint_sha256", true));
                    checks.add(requireIndex(connection, "evalops.experiment_idempotency",
                            "runtime_eval_experiment", "uk_runtime_eval_experiment_idempotency",
                            "tenant_id,idempotency_key", true));
                    checks.add(requireIndex(connection, "evalops.experiment_item_identity",
                            "runtime_eval_experiment_item", "uk_runtime_eval_experiment_item",
                            "experiment_id,variant_id,dataset_item_id,repeat_no", true));
                    checks.add(requireIndex(connection, "evalops.score_identity",
                            "runtime_eval_score", "uk_runtime_eval_score_item_evaluator",
                            "experiment_item_id,evaluator_key", true));
                    checks.add(requireIndex(connection, "evalops.task_identity",
                            "runtime_eval_task", "uk_runtime_eval_task_item",
                            "task_type,experiment_item_id", true));
                    checks.add(requireIndex(connection, "evalops.task_lease_index",
                            "runtime_eval_task", "idx_runtime_eval_task_lease",
                            "status,available_at,leased_until,priority,id", false));
                    break;
                case "upgrade-20260828-mcp-hub.sql":
                    checks.add(requireTables(connection, "mcp_hub.tables", 5,
                            "control_mcp_publication", "control_mcp_publication_item",
                            "control_mcp_publication_revision", "control_mcp_client",
                            "control_mcp_call_log"));
                    checks.add(requireTableProperties(connection, "mcp_hub.table_properties", 5,
                            "control_mcp_publication", "control_mcp_publication_item",
                            "control_mcp_publication_revision", "control_mcp_client",
                            "control_mcp_call_log"));
                    checks.add(requireColumns(connection, "mcp_hub.client_columns", 6,
                            "control_mcp_client", "publication_id", "project_code",
                            "environment", "tenant_id", "tool_scope_json", "state"));
                    checks.add(requireColumnDefinition(connection, "mcp_hub.client_project_scope",
                            "control_mcp_client", "project_code", "varchar", 96L, false));
                    checks.add(requireColumnDefinition(connection, "mcp_hub.client_environment_scope",
                            "control_mcp_client", "environment", "varchar", 32L, false));
                    checks.add(requireColumnDefinition(connection, "mcp_hub.client_tenant_scope",
                            "control_mcp_client", "tenant_id", "varchar", 96L, false));
                    checks.add(requireColumns(connection, "mcp_hub.call_log_columns", 10,
                            "control_mcp_call_log", "direction", "publication_id",
                            "remote_server_id", "error_category", "request_body",
                            "response_body", "run_id", "project_code", "environment",
                            "tenant_id"));
                    checks.add(requireColumnDefinition(connection, "mcp_hub.request_body_capacity",
                            "control_mcp_call_log", "request_body", "mediumtext", 16777215L, true));
                    checks.add(requireColumnDefinition(connection, "mcp_hub.response_body_capacity",
                            "control_mcp_call_log", "response_body", "mediumtext", 16777215L, true));
                    checks.add(requireIndex(connection, "mcp_hub.client_publication_index",
                            "control_mcp_client", "idx_publication", "publication_id", false));
                    checks.add(requireIndex(connection, "mcp_hub.call_direction_index",
                            "control_mcp_call_log", "idx_direction_created",
                            "direction,created_at", false));
                    checks.add(requireIndex(connection, "mcp_hub.call_publication_index",
                            "control_mcp_call_log", "idx_publication_created",
                            "publication_id,created_at", false));
                    checks.add(requireIndex(connection, "mcp_hub.call_project_trace_index",
                            "control_mcp_call_log", "idx_mcp_log_project_trace",
                            "project_code,trace_id", false));
                    checks.add(requireAtLeast(connection, "mcp_hub.permissions", 6,
                            "SELECT COUNT(*) FROM control_platform_permission "
                                    + "WHERE permission_code LIKE 'mcp-hub:%'"));
                    checks.add(requireZero(connection, "mcp_hub.legacy_table",
                            "SELECT COUNT(*) FROM information_schema.TABLES "
                                    + "WHERE TABLE_SCHEMA = DATABASE() "
                                    + "AND TABLE_NAME = 'control_mcp_visibility'"));
                    checks.add(requireZero(connection, "mcp_hub.legacy_client_column",
                            "SELECT COUNT(*) FROM information_schema.COLUMNS "
                                    + "WHERE TABLE_SCHEMA = DATABASE() "
                                    + "AND TABLE_NAME = 'control_mcp_client' "
                                    + "AND COLUMN_NAME = 'tool_whitelist_json'"));
                    checks.add(requireZero(connection, "mcp_hub.permission_mojibake",
                            "SELECT COUNT(*) FROM control_platform_permission "
                                    + "WHERE permission_code LIKE 'mcp-hub:%' "
                                    + "AND permission_name LIKE '%?%'"));
                    break;
                case "upgrade-20260824-agent-skill-market.sql":
                    checks.add(requireTables(connection, "agent_skill_market.tables", 2,
                            "control_agent_skill_market_source",
                            "control_agent_skill_market_import"));
                    checks.add(requireTableProperties(connection, "agent_skill_market.table_properties", 2,
                            "control_agent_skill_market_source",
                            "control_agent_skill_market_import"));
                    checks.add(requireColumns(connection, "agent_skill_market.import_provenance", 8,
                            "control_agent_skill_market_import",
                            "provider_key", "marketplace_skill_id", "repository",
                            "source_commit_sha", "source_root", "bundle_source_sha256",
                            "selected_source_sha256", "skill_version_id"));
                    checks.add(requireIndex(connection, "agent_skill_market.source_identity",
                            "control_agent_skill_market_source",
                            "uk_control_agent_skill_market_source", "source_key", true));
                    checks.add(requireIndex(connection, "agent_skill_market.origin_identity",
                            "control_agent_skill_market_import",
                            "uk_control_agent_skill_market_origin", "origin_fingerprint", true));
                    checks.add(requireIndex(connection, "agent_skill_market.repository_lookup",
                            "control_agent_skill_market_import",
                            "idx_control_agent_skill_market_import_repo",
                            "repository,source_commit_sha", false));
                    checks.add(requireAtLeast(connection, "agent_skill_market.seed_sources", 6,
                            "SELECT COUNT(*) FROM control_agent_skill_market_source "
                                    + "WHERE source_key IN ('SKILLS_SH','GITHUB','ANTHROPIC_SKILLS',"
                                    + "'VERCEL_AGENT_SKILLS','JETBRAINS_SKILLS','OPENAI_PLUGINS')"));
                    checks.add(requireZero(connection, "agent_skill_market.seed_utf8",
                            "SELECT COUNT(*) FROM control_agent_skill_market_source "
                                    + "WHERE display_name LIKE '%?%' OR description LIKE '%?%'"));
                    break;
                default:
                    throw new RunnerFailure("UNAPPROVED_MIGRATION_SCRIPT", "VERIFY",
                            script, null, null, null);
            }
            for (CheckResult check : checks) {
                if (!check.passed) {
                    throw new RunnerFailure("MIGRATION_VERIFICATION_FAILED", "VERIFY",
                            script, null, check.code, null);
                }
            }
            return checks;
        } catch (SQLException error) {
            throw RunnerFailure.fromSql("MIGRATION_VERIFICATION_QUERY_FAILED", "VERIFY",
                    script, null, error);
        }
    }

    private static CheckResult requireTables(Connection connection, String code, long expected,
                                             String... tables) throws SQLException {
        String placeholders = String.join(",", java.util.Collections.nCopies(tables.length, "?"));
        String sql = "SELECT COUNT(*) FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN (" + placeholders + ")";
        long actual = preparedScalarLong(connection, sql, tables);
        return new CheckResult(code, actual == expected, expected, actual);
    }

    private static CheckResult requireColumns(Connection connection, String code, long expected,
                                              String table, String... columns) throws SQLException {
        String placeholders = String.join(",", java.util.Collections.nCopies(columns.length, "?"));
        String sql = "SELECT COUNT(*) FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? "
                + "AND COLUMN_NAME IN (" + placeholders + ")";
        String[] parameters = new String[columns.length + 1];
        parameters[0] = table;
        System.arraycopy(columns, 0, parameters, 1, columns.length);
        long actual = preparedScalarLong(connection, sql, parameters);
        return new CheckResult(code, actual == expected, expected, actual);
    }

    private static CheckResult requireTableProperties(Connection connection, String code,
                                                      long expected, String... tables)
            throws SQLException {
        String placeholders = String.join(",", java.util.Collections.nCopies(tables.length, "?"));
        String sql = "SELECT COUNT(*) FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN (" + placeholders + ") "
                + "AND ENGINE = 'InnoDB' AND TABLE_COLLATION LIKE 'utf8mb4%'";
        long actual = preparedScalarLong(connection, sql, tables);
        return new CheckResult(code, actual == expected, expected, actual);
    }

    private static CheckResult requireColumnDefinition(Connection connection, String code,
                                                       String table, String column, String dataType,
                                                       Long characterLength, boolean nullable)
            throws SQLException {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ? "
                        + "AND DATA_TYPE = ? AND IS_NULLABLE = ?");
        List<String> parameters = new ArrayList<>(Arrays.asList(
                table, column, dataType, nullable ? "YES" : "NO"));
        if (characterLength != null) {
            sql.append(" AND CHARACTER_MAXIMUM_LENGTH = ?");
            parameters.add(Long.toString(characterLength));
        }
        long actual = preparedScalarLong(connection, sql.toString(),
                parameters.toArray(new String[0]));
        return new CheckResult(code, actual == 1, 1, actual);
    }

    private static CheckResult requireIndex(Connection connection, String code, String table,
                                            String index, String expectedColumns, boolean unique)
            throws SQLException {
        String actualColumns = null;
        Integer nonUnique = null;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX), MIN(NON_UNIQUE) "
                        + "FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() "
                        + "AND TABLE_NAME = ? AND INDEX_NAME = ?")) {
            statement.setString(1, table);
            statement.setString(2, index);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    actualColumns = result.getString(1);
                    nonUnique = result.getObject(2) == null ? null : result.getInt(2);
                }
            }
        }
        boolean passed = expectedColumns.equals(actualColumns)
                && nonUnique != null && nonUnique == (unique ? 0 : 1);
        return new CheckResult(code, passed, 1, passed ? 1 : 0);
    }

    private static CheckResult requirePermissionBinding(Connection connection, String code,
                                                        String permissionCode) throws SQLException {
        long actual = preparedScalarLong(connection,
                "SELECT COUNT(*) FROM control_platform_role r "
                        + "JOIN control_platform_role_permission rp ON rp.role_id = r.id "
                        + "JOIN control_platform_permission p ON p.id = rp.permission_id "
                        + "WHERE r.role_code = 'PLATFORM_ADMIN' AND p.permission_code = ?",
                permissionCode);
        return new CheckResult(code, actual >= 1, 1, actual);
    }

    private static CheckResult requireZero(Connection connection, String code, String sql)
            throws SQLException {
        long actual = scalarLong(connection, sql);
        return new CheckResult(code, actual == 0, 0, actual);
    }

    private static CheckResult requireAtLeast(Connection connection, String code,
                                              long expectedMinimum, String sql)
            throws SQLException {
        long actual = scalarLong(connection, sql);
        return new CheckResult(code, actual >= expectedMinimum, expectedMinimum, actual);
    }

    private static long scalarLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) {
                throw new SQLException("scalar query returned no row");
            }
            return result.getLong(1);
        }
    }

    private static long preparedScalarLong(Connection connection, String sql, String... parameters)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setString(index + 1, parameters[index]);
            }
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("scalar query returned no row");
                }
                return result.getLong(1);
            }
        }
    }

    static List<SqlUnit> parseSql(String source) throws RunnerFailure {
        String normalized = source.replace("\r\n", "\n").replace('\r', '\n');
        List<SqlUnit> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String delimiter = ";";
        LexicalState state = new LexicalState();
        int lineNumber = 1;
        int statementStartLine = 1;
        String[] lines = normalized.split("\n", -1);
        for (String line : lines) {
            String trimmed = line.trim();
            if (state.isNeutral() && current.toString().trim().isEmpty()
                    && trimmed.toUpperCase(Locale.ROOT).startsWith("DELIMITER ")) {
                String nextDelimiter = trimmed.substring("DELIMITER ".length()).trim();
                if (nextDelimiter.isEmpty() || nextDelimiter.matches(".*\\s+.*")) {
                    throw new RunnerFailure("SQL_DELIMITER_INVALID", "PARSER",
                            null, lineNumber, null, null);
                }
                delimiter = nextDelimiter;
                statementStartLine = lineNumber + 1;
                lineNumber++;
                continue;
            }

            String withNewline = line + "\n";
            int index = 0;
            while (index < withNewline.length()) {
                char ch = withNewline.charAt(index);
                char next = index + 1 < withNewline.length() ? withNewline.charAt(index + 1) : '\0';

                if (state.lineComment) {
                    current.append(ch);
                    if (ch == '\n') {
                        state.lineComment = false;
                    }
                    index++;
                    continue;
                }
                if (state.blockComment) {
                    current.append(ch);
                    if (ch == '*' && next == '/') {
                        current.append(next);
                        state.blockComment = false;
                        index += 2;
                    } else {
                        index++;
                    }
                    continue;
                }
                if (state.quote != 0) {
                    current.append(ch);
                    if (ch == '\\' && state.quote != '`' && next != '\0') {
                        current.append(next);
                        index += 2;
                        continue;
                    }
                    if (ch == state.quote) {
                        if (next == state.quote) {
                            current.append(next);
                            index += 2;
                            continue;
                        }
                        state.quote = 0;
                    }
                    index++;
                    continue;
                }

                if (ch == '-' && next == '-') {
                    state.lineComment = true;
                    current.append(ch).append(next);
                    index += 2;
                    continue;
                }
                if (ch == '#') {
                    state.lineComment = true;
                    current.append(ch);
                    index++;
                    continue;
                }
                if (ch == '/' && next == '*') {
                    state.blockComment = true;
                    current.append(ch).append(next);
                    index += 2;
                    continue;
                }
                if (ch == '\'' || ch == '"' || ch == '`') {
                    state.quote = ch;
                    current.append(ch);
                    index++;
                    continue;
                }
                if (withNewline.startsWith(delimiter, index)) {
                    String sql = current.toString().trim();
                    if (!sql.isEmpty()) {
                        statements.add(new SqlUnit(sql, statementStartLine));
                    }
                    current.setLength(0);
                    index += delimiter.length();
                    statementStartLine = lineNumber;
                    continue;
                }
                current.append(ch);
                index++;
            }
            lineNumber++;
        }
        if (!state.isNeutral()) {
            throw new RunnerFailure("SQL_LEXICAL_STATE_UNTERMINATED", "PARSER",
                    null, lineNumber, null, null);
        }
        if (!current.toString().trim().isEmpty()) {
            throw new RunnerFailure("SQL_STATEMENT_UNTERMINATED", "PARSER",
                    null, statementStartLine, null, null);
        }
        return statements;
    }

    private static void runParserSelfTest() throws RunnerFailure {
        String sample = "-- heading\nDROP PROCEDURE IF EXISTS p;\nDELIMITER $$\n"
                + "CREATE PROCEDURE p()\nBEGIN\nSELECT 'a;''b';\nSELECT 2;\nEND$$\n"
                + "DELIMITER ;\nCALL p();\nDROP PROCEDURE IF EXISTS p;\n";
        List<SqlUnit> statements = parseSql(sample);
        if (statements.size() != 4
                || !statements.get(1).sql.contains("SELECT 2;")
                || !statements.get(2).sql.startsWith("CALL p()")) {
            throw new RunnerFailure("SQL_PARSER_SELF_TEST_FAILED", "PARSER",
                    null, null, null, null);
        }
    }

    private static String safeScriptName(Path script) {
        return script.getFileName().toString();
    }

    private static String jsonString(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder result = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            switch (ch) {
                case '"': result.append("\\\""); break;
                case '\\': result.append("\\\\"); break;
                case '\b': result.append("\\b"); break;
                case '\f': result.append("\\f"); break;
                case '\n': result.append("\\n"); break;
                case '\r': result.append("\\r"); break;
                case '\t': result.append("\\t"); break;
                default:
                    if (ch < 0x20) {
                        result.append(String.format("\\u%04x", (int) ch));
                    } else {
                        result.append(ch);
                    }
            }
        }
        return result.append('"').toString();
    }

    static final class SqlUnit {
        final String sql;
        final int startLine;

        SqlUnit(String sql, int startLine) {
            this.sql = sql;
            this.startLine = startLine;
        }
    }

    static final class LexicalState {
        boolean lineComment;
        boolean blockComment;
        char quote;

        boolean isNeutral() {
            return !lineComment && !blockComment && quote == 0;
        }
    }

    static final class CheckResult {
        final String code;
        final boolean passed;
        final long expected;
        final long actual;

        CheckResult(String code, boolean passed, long expected, long actual) {
            this.code = code;
            this.passed = passed;
            this.expected = expected;
            this.actual = actual;
        }

        String toJson() {
            return "{\"code\":" + jsonString(code)
                    + ",\"passed\":" + passed
                    + ",\"expected\":" + expected
                    + ",\"actual\":" + actual + "}";
        }
    }

    static final class MigrationResult {
        final String script;
        final int statementCountPerPass;
        final List<CheckResult> firstChecks;
        final List<CheckResult> secondChecks;
        final long durationMs;

        MigrationResult(String script, int statementCountPerPass,
                        List<CheckResult> firstChecks, List<CheckResult> secondChecks,
                        long durationMs) {
            this.script = script;
            this.statementCountPerPass = statementCountPerPass;
            this.firstChecks = firstChecks;
            this.secondChecks = secondChecks;
            this.durationMs = durationMs;
        }

        String toJson() {
            return "{\"script\":" + jsonString(script)
                    + ",\"status\":\"PASSED\""
                    + ",\"statementCountPerPass\":" + statementCountPerPass
                    + ",\"idempotencePasses\":2"
                    + ",\"durationMs\":" + durationMs
                    + ",\"firstPassChecks\":" + checksJson(firstChecks)
                    + ",\"secondPassChecks\":" + checksJson(secondChecks) + "}";
        }

        private static String checksJson(List<CheckResult> checks) {
            StringBuilder result = new StringBuilder("[");
            for (int index = 0; index < checks.size(); index++) {
                if (index > 0) result.append(',');
                result.append(checks.get(index).toJson());
            }
            return result.append(']').toString();
        }
    }

    static final class ParsedScript {
        final String script;
        final int statementCount;

        ParsedScript(String script, int statementCount) {
            this.script = script;
            this.statementCount = statementCount;
        }

        String toJson() {
            return "{\"script\":" + jsonString(script)
                    + ",\"statementCount\":" + statementCount + "}";
        }
    }

    static final class ServerSummary {
        final int major;
        final int minor;
        final String databaseCharset;
        final String connectionCharset;

        ServerSummary(int major, int minor, String databaseCharset, String connectionCharset) {
            this.major = major;
            this.minor = minor;
            this.databaseCharset = databaseCharset;
            this.connectionCharset = connectionCharset;
        }

        String toJson() {
            return "{\"major\":" + major + ",\"minor\":" + minor
                    + ",\"databaseCharset\":" + jsonString(databaseCharset)
                    + ",\"connectionCharset\":" + jsonString(connectionCharset) + "}";
        }
    }

    static final class RunnerFailure extends Exception {
        final String code;
        final String phase;
        final String script;
        final Integer statementIndex;
        final String checkCode;
        final String sqlState;
        final Integer vendorCode;
        Integer pass;

        RunnerFailure(String code, String phase, String script, Integer statementIndex,
                      String checkCode, Throwable cause) {
            super(code, cause);
            this.code = code;
            this.phase = phase;
            this.script = script;
            this.statementIndex = statementIndex;
            this.checkCode = checkCode;
            this.sqlState = cause instanceof SQLException ? ((SQLException) cause).getSQLState() : null;
            this.vendorCode = cause instanceof SQLException ? ((SQLException) cause).getErrorCode() : null;
        }

        static RunnerFailure fromSql(String code, String phase, String script,
                                     Integer statementIndex, SQLException cause) {
            return new RunnerFailure(code, phase, script, statementIndex, null, cause);
        }

        RunnerFailure withPass(int value) {
            this.pass = value;
            return this;
        }

        String toJson() {
            return "{\"code\":" + jsonString(code)
                    + ",\"phase\":" + jsonString(phase)
                    + ",\"script\":" + jsonString(script)
                    + ",\"statementIndex\":" + (statementIndex == null ? "null" : statementIndex)
                    + ",\"idempotencePass\":" + (pass == null ? "null" : pass)
                    + ",\"checkCode\":" + jsonString(checkCode)
                    + ",\"sqlState\":" + jsonString(sqlState)
                    + ",\"vendorCode\":" + (vendorCode == null ? "null" : vendorCode) + "}";
        }
    }

    static final class RunnerResult {
        final boolean passed;
        final boolean selfTest;
        final boolean parseOnly;
        final ServerSummary server;
        final List<MigrationResult> migrations;
        final List<ParsedScript> parsedScripts;
        final long durationMs;
        final RunnerFailure failure;

        private RunnerResult(boolean passed, boolean selfTest, boolean parseOnly,
                             ServerSummary server, List<MigrationResult> migrations,
                             List<ParsedScript> parsedScripts, long durationMs,
                             RunnerFailure failure) {
            this.passed = passed;
            this.selfTest = selfTest;
            this.parseOnly = parseOnly;
            this.server = server;
            this.migrations = migrations;
            this.parsedScripts = parsedScripts;
            this.durationMs = durationMs;
            this.failure = failure;
        }

        static RunnerResult selfTestPassed() {
            return new RunnerResult(true, true, false, null,
                    new ArrayList<>(), new ArrayList<>(), 0, null);
        }

        static RunnerResult parseOnlyPassed(List<ParsedScript> parsedScripts) {
            return new RunnerResult(true, false, true, null,
                    new ArrayList<>(), parsedScripts, 0, null);
        }

        static RunnerResult passed(ServerSummary server, List<MigrationResult> migrations,
                                   long durationMs) {
            return new RunnerResult(true, false, false, server,
                    migrations, new ArrayList<>(), durationMs, null);
        }

        static RunnerResult failed(RunnerFailure failure) {
            return new RunnerResult(false, false, false, null,
                    new ArrayList<>(), new ArrayList<>(), 0, failure);
        }

        static RunnerResult failedAfterExecution(ServerSummary server,
                                                 List<MigrationResult> migrations,
                                                 long durationMs,
                                                 RunnerFailure failure) {
            return new RunnerResult(false, false, false, server,
                    migrations, new ArrayList<>(), durationMs, failure);
        }

        String toJson() {
            StringBuilder migrationsJson = new StringBuilder("[");
            for (int index = 0; index < migrations.size(); index++) {
                if (index > 0) migrationsJson.append(',');
                migrationsJson.append(migrations.get(index).toJson());
            }
            migrationsJson.append(']');
            StringBuilder parsedScriptsJson = new StringBuilder("[");
            for (int index = 0; index < parsedScripts.size(); index++) {
                if (index > 0) parsedScriptsJson.append(',');
                parsedScriptsJson.append(parsedScripts.get(index).toJson());
            }
            parsedScriptsJson.append(']');
            return "{\"schema\":\"" + RESULT_SCHEMA + "\""
                    + ",\"passed\":" + passed
                    + ",\"selfTest\":" + selfTest
                    + ",\"parseOnly\":" + parseOnly
                    + ",\"credentialsUsed\":" + (!selfTest && !parseOnly)
                    + ",\"server\":" + (server == null ? "null" : server.toJson())
                    + ",\"migrations\":" + migrationsJson
                    + ",\"parsedScripts\":" + parsedScriptsJson
                    + ",\"durationMs\":" + durationMs
                    + ",\"failure\":" + (failure == null ? "null" : failure.toJson()) + "}";
        }
    }
}
