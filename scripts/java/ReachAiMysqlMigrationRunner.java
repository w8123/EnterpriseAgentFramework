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
 * Connector/J-backed executor used only by scripts/apply-memory-migrations.mjs.
 *
 * The outer Node runner validates explicit execution intent before this process is started.
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
