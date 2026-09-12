package com.enterprise.ai.service.impl;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Pattern;

/** Opt-in MySQL fixture. Every test SQL identifier is redirected to a uniquely owned scratch table. */
public final class KnowledgePublicationMysqlDatabase extends AbstractDataSource implements AutoCloseable {
    private static final java.util.List<String> ORIGINALS = java.util.List.of("knowledge_document_import_job", "knowledge_base", "knowledge_file_info", "knowledge_chunk", "knowledge_document_index_execution");
    private static final String DEVELOPMENT_SERVER = "d68937fe-df9e-11f0-ad94-00163e10c3b6";
    private final HikariDataSource delegate;
    private final int lockWaitSeconds;
    private final String table;
    private final boolean ownsTables;
    private final java.util.Map<String, Long> originalRows = new java.util.LinkedHashMap<>();
    private final java.util.Map<String, String> created = new java.util.LinkedHashMap<>();

    KnowledgePublicationMysqlDatabase() throws SQLException {
        this(1);
    }

    KnowledgePublicationMysqlDatabase(int lockWaitSeconds) throws SQLException {
        this(lockWaitSeconds, null);
    }

    static KnowledgePublicationMysqlDatabase withFileAssociations(int lockWaitSeconds) throws SQLException {
        var originals = new java.util.ArrayList<>(ORIGINALS);
        originals.add("knowledge_user_file_permission");
        originals.add("knowledge_question");
        return new KnowledgePublicationMysqlDatabase(lockWaitSeconds, null, originals);
    }

    static KnowledgePublicationMysqlDatabase withBaseCommands(int lockWaitSeconds) throws SQLException {
        return new KnowledgePublicationMysqlDatabase(lockWaitSeconds, null, java.util.List.of("knowledge_base"));
    }

    static KnowledgePublicationMysqlDatabase withContentManagement(int lockWaitSeconds) throws SQLException {
        return new KnowledgePublicationMysqlDatabase(lockWaitSeconds, null,
                java.util.List.of("knowledge_base", "knowledge_file_info", "knowledge_chunk", "knowledge_tag"));
    }

    static KnowledgePublicationMysqlDatabase withCollectionLifecycle(int lockWaitSeconds) throws SQLException {
        var originals = new java.util.ArrayList<>(ORIGINALS);
        originals.add("knowledge_user_file_permission");
        originals.add("knowledge_question");
        originals.add("knowledge_tag");
        originals.add("knowledge_collection_lifecycle");
        return new KnowledgePublicationMysqlDatabase(lockWaitSeconds, null, originals);
    }

    public static KnowledgePublicationMysqlDatabase withArtifactLifecycle(int lockWaitSeconds) throws SQLException {
        return new KnowledgePublicationMysqlDatabase(lockWaitSeconds, null, artifactTables());
    }

    static KnowledgePublicationMysqlDatabase withTagLifecycle(int lockWaitSeconds) throws SQLException {
        var originals = new java.util.ArrayList<>(ORIGINALS);
        originals.add("knowledge_user_file_permission");
        originals.add("knowledge_question");
        originals.add("knowledge_tag");
        return new KnowledgePublicationMysqlDatabase(lockWaitSeconds, null, originals);
    }

    public static KnowledgePublicationMysqlDatabase attachArtifactLifecycle(String prefix) throws SQLException {
        if (prefix == null || !prefix.matches("audit_publication_[0-9a-f]{32}")) {
            throw new IllegalArgumentException("Invalid parent fixture namespace");
        }
        return new KnowledgePublicationMysqlDatabase(1, prefix, artifactTables());
    }

    private static java.util.List<String> artifactTables() {
        var originals = new java.util.ArrayList<>(ORIGINALS);
        originals.add("knowledge_user_file_permission");
        originals.add("knowledge_question");
        originals.add("knowledge_document_artifact_lifecycle");
        return originals;
    }

    /** A child JVM may use only the parent's exact scratch namespace; it never creates or drops tables. */
    static KnowledgePublicationMysqlDatabase attach(String prefix) throws SQLException {
        if (prefix == null || !prefix.matches("audit_publication_[0-9a-f]{32}")) throw new IllegalArgumentException("Invalid parent fixture namespace");
        return new KnowledgePublicationMysqlDatabase(1, prefix);
    }

    public String namespace() { return table; }

    private KnowledgePublicationMysqlDatabase(int lockWaitSeconds, String prefix) throws SQLException {
        this(lockWaitSeconds, prefix, ORIGINALS);
    }

    private KnowledgePublicationMysqlDatabase(int lockWaitSeconds, String prefix, java.util.List<String> originals) throws SQLException {
        ownsTables = prefix == null;
        table = ownsTables ? "audit_publication_" + UUID.randomUUID().toString().replace("-", "") : prefix;
        if (lockWaitSeconds < 1 || lockWaitSeconds > 10) throw new IllegalArgumentException("Invalid fixture lock wait");
        this.lockWaitSeconds = lockWaitSeconds;
        if (!Boolean.getBoolean("reachai.mysql.publicationVerification")) throw new IllegalStateException("MySQL fixture must be explicitly enabled");
        String url = System.getenv("AI_MYSQL_URL");
        URI target = URI.create(url.substring("jdbc:".length()));
        if (!"39.106.238.224".equals(target.getHost()) || target.getPort() != 33106
                || !"/reach_ai".equals(target.getPath()) || target.getUserInfo() != null) {
            throw new IllegalStateException("MySQL fixture target is not the reviewed development database");
        }
        var source = new DriverManagerDataSource(url, System.getenv("AI_MYSQL_USER"), System.getenv("AI_MYSQL_PASSWORD"));
        var options = new Properties(); options.setProperty("connectTimeout", "10000");
        options.setProperty("socketTimeout", "15000"); options.setProperty("characterEncoding", "UTF-8");
        source.setConnectionProperties(options);
        var pool = new HikariConfig();
        pool.setDataSource(source);
        pool.setPoolName(table);
        pool.setMaximumPoolSize(ownsTables ? 2 : 1);
        pool.setMinimumIdle(ownsTables ? 2 : 1);
        pool.setConnectionTimeout(30000);
        delegate = new HikariDataSource(pool);
        try {
            // Establish both independent sessions before any timed transaction interleaving.
            try (Connection first = delegate.getConnection()) {
                if (!first.isValid(5)) throw new SQLException("MySQL fixture session is not ready");
                if (ownsTables) try (Connection second = delegate.getConnection()) {
                    if (!second.isValid(5)) throw new SQLException("Second MySQL fixture session is not ready");
                }
            }
            var admin = new JdbcTemplate(delegate);
            if (!DEVELOPMENT_SERVER.equals(admin.queryForObject("SELECT @@server_uuid", String.class))
                    || !"reach_ai".equals(admin.queryForObject("SELECT DATABASE()", String.class))) {
                throw new IllegalStateException("MySQL fixture server identity mismatch");
            }
            for (String original : originals) {
                String clone = table + "_" + originals.indexOf(original);
                if (!ownsTables) {
                    Long exists = admin.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?", Long.class, clone);
                    if (exists != 1) throw new IllegalStateException("Parent scratch table missing");
                    created.put(original, clone);
                    continue;
                }
                originalRows.put(original, admin.queryForObject("SELECT COUNT(*) FROM " + original, Long.class));
                System.out.println("MYSQL_PUBLICATION_FIXTURE_ALLOCATED " + clone);
                created.put(original, clone);
                admin.execute("CREATE TABLE `" + clone + "` LIKE `" + original + "`");
                System.out.println("MYSQL_PUBLICATION_FIXTURE_CREATED " + clone);
            }
        } catch (RuntimeException | SQLException failure) {
            try { close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    @Override public Connection getConnection() throws SQLException {
        Connection connection = delegate.getConnection();
        try {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET SESSION innodb_lock_wait_timeout = " + lockWaitSeconds);
            }
            return wrap(connection);
        } catch (SQLException failure) {
            connection.close();
            throw failure;
        }
    }
    @Override public Connection getConnection(String username, String password) throws SQLException {
        throw new SQLException("Explicit credentials are not supported by this fixture");
    }

    private Connection wrap(Connection raw) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
            if (method.getName().equals("equals")) return proxy == args[0];
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (method.getName().equals("toString")) return "IsolatedInstanceTestConnection";
            Object[] rewritten = args;
            if (args != null && args.length > 0 && args[0] instanceof String sql
                    && (method.getName().startsWith("prepareStatement") || method.getName().startsWith("prepareCall"))) {
                rewritten = args.clone(); rewritten[0] = rewrite(sql);
            }
            Object result = invoke(raw, method, rewritten);
            if (method.getName().equals("createStatement")) return wrap((Statement) result);
            return result;
        });
    }

    private Statement wrap(Statement raw) {
        return (Statement) Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[]{Statement.class}, (proxy, method, args) -> {
            Object[] rewritten = args;
            if (args != null && args.length > 0 && args[0] instanceof String sql
                    && (method.getName().startsWith("execute") || method.getName().equals("addBatch"))) {
                rewritten = args.clone(); rewritten[0] = rewrite(sql);
            }
            return invoke(raw, method, rewritten);
        });
    }

    private String rewrite(String sql) {
        boolean matched = false;
        for (var entry : created.entrySet()) {
            var pattern = Pattern.compile("(?i)(?<![a-z0-9_])" + entry.getKey() + "(?![a-z0-9_])");
            matched |= pattern.matcher(sql).find();
            sql = pattern.matcher(sql).replaceAll(entry.getValue());
        }
        if (!matched) throw new IllegalArgumentException("SQL outside the isolated publication fixture");
        return sql;
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    @Override public void close() {
        if (!ownsTables) { delegate.close(); return; }
        try {
            var admin = new JdbcTemplate(delegate);
            RuntimeException failure = null;
            for (var entry : created.entrySet()) {
                try {
                    String clone = entry.getValue();
                    if (!clone.matches("audit_publication_[0-9a-f]{32}_[0-8]")) throw new IllegalStateException("Unexpected scratch table name");
                    admin.execute("DROP TABLE IF EXISTS `" + clone + "`");
                    Long remaining = admin.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?", Long.class, clone);
                    Long actualRows = admin.queryForObject("SELECT COUNT(*) FROM " + entry.getKey(), Long.class);
                    if (remaining != 0 || !actualRows.equals(originalRows.get(entry.getKey()))) throw new IllegalStateException("Scratch cleanup or original row count verification failed");
                    System.out.println("MYSQL_PUBLICATION_FIXTURE_REMOVED " + clone + " original_rows_unchanged=true");
                } catch (RuntimeException cleanup) {
                    if (failure == null) failure = cleanup; else failure.addSuppressed(cleanup);
                }
            }
            created.clear();
            if (failure != null) throw failure;
        } finally { delegate.close(); }
    }
}
