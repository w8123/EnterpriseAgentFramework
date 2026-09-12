package com.enterprise.ai.common.testing;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Pattern;

/** Explicit development MySQL verification; owning-service SQL is redirected to independent cloned tables. */
public class ClonedDevelopmentMysqlDatabase extends AbstractDataSource implements AutoCloseable {
    private static final String SERVER = "d68937fe-df9e-11f0-ad94-00163e10c3b6";
    private final String namespace;
    private final HikariDataSource pool;
    private final Map<String, String> allocated = new LinkedHashMap<>();
    private final Map<String, Long> originalRows = new LinkedHashMap<>();

    public ClonedDevelopmentMysqlDatabase(String optInProperty, String namespacePrefix, String owningPrefix,
                                           List<String> tables) throws SQLException {
        if (!Boolean.getBoolean(optInProperty)) throw new IllegalStateException("MySQL verification must be explicitly enabled");
        if (!namespacePrefix.matches("audit_[a-z_]{1,24}") || !owningPrefix.matches("[a-z]+_")) {
            throw new IllegalArgumentException("Unexpected verification namespace or owning service");
        }
        if (tables == null || tables.isEmpty() || tables.stream().distinct().count() != tables.size()
                || tables.stream().anyMatch(table -> !table.matches(owningPrefix + "[a-z0-9_]+"))) {
            throw new IllegalArgumentException("Unexpected owning-service verification tables");
        }
        namespace = namespacePrefix + "_" + UUID.randomUUID().toString().replace("-", "");
        String url = System.getenv("AI_MYSQL_URL");
        if (url == null || !url.startsWith("jdbc:mysql:")) throw new IllegalStateException("Development MySQL configuration is unavailable");
        URI target = URI.create(url.substring("jdbc:".length()));
        if (!"39.106.238.224".equals(target.getHost()) || target.getPort() != 33106
                || !"/reach_ai".equals(target.getPath()) || target.getUserInfo() != null) {
            throw new IllegalStateException("MySQL target is not the reviewed development database");
        }
        var source = new DriverManagerDataSource(url, System.getenv("AI_MYSQL_USER"), System.getenv("AI_MYSQL_PASSWORD"));
        var options = new Properties();
        options.setProperty("connectTimeout", "10000"); options.setProperty("socketTimeout", "15000");
        options.setProperty("characterEncoding", "UTF-8"); source.setConnectionProperties(options);
        var config = new HikariConfig();
        config.setDataSource(source); config.setPoolName(namespace); config.setMaximumPoolSize(2); config.setMinimumIdle(2);
        config.setConnectionTimeout(30000);
        config.setConnectionInitSql("SET SESSION innodb_lock_wait_timeout=8");
        pool = new HikariDataSource(config);
        try {
            // Warm and verify both sessions before the timed transaction interleavings begin.
            try (var first = pool.getConnection(); var second = pool.getConnection()) {
                verifyServer(first); verifyServer(second);
                System.out.println("MYSQL_CLONE_SESSIONS_READY isolation=" + first.getTransactionIsolation());
            }
            var admin = new JdbcTemplate(pool);
            for (int i = 0; i < tables.size(); i++) {
                String original = tables.get(i), clone = namespace + "_" + i;
                if (admin.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?", Long.class, clone) != 0) {
                    throw new IllegalStateException("Scratch table already exists");
                }
                if (admin.queryForObject("SELECT COUNT(*) FROM information_schema.key_column_usage WHERE table_schema=DATABASE() AND table_name=? AND referenced_table_name IS NOT NULL", Long.class, original) != 0) {
                    throw new IllegalStateException("CREATE TABLE LIKE cannot preserve foreign keys for this fixture");
                }
                originalRows.put(original, admin.queryForObject("SELECT COUNT(*) FROM `" + original + "`", Long.class));
                // The name was absent and uniquely allocated; retain ownership even if the DDL response is lost.
                allocated.put(original, clone);
                System.out.println("MYSQL_CLONE_FIXTURE_ALLOCATED " + clone);
                admin.execute("CREATE TABLE `" + clone + "` LIKE `" + original + "`");
                System.out.println("MYSQL_CLONE_FIXTURE_CREATED " + clone);
            }
        } catch (RuntimeException | SQLException failure) {
            try { close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private static void verifyServer(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT @@server_uuid, DATABASE()")) {
            if (!result.next() || !SERVER.equals(result.getString(1)) || !"reach_ai".equals(result.getString(2))) {
                throw new IllegalStateException("Development MySQL server identity mismatch");
            }
        }
    }

    @Override public Connection getConnection() throws SQLException {
        Connection connection = pool.getConnection();
        return wrap(connection);
    }

    @Override public Connection getConnection(String username, String password) throws SQLException {
        throw new SQLException("Explicit credentials are not supported by this fixture");
    }

    private Connection wrap(Connection connection) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
            if (method.getName().equals("equals")) return proxy == args[0];
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (method.getName().equals("toString")) return "IsolatedDevelopmentTestConnection";
            Object[] rewritten = args;
            if (args != null && args.length > 0 && args[0] instanceof String sql
                    && (method.getName().startsWith("prepareStatement") || method.getName().startsWith("prepareCall"))) {
                rewritten = args.clone(); rewritten[0] = rewrite(sql);
            }
            Object result = invoke(connection, method, rewritten);
            return method.getName().equals("createStatement") ? wrap((Statement) result) : result;
        });
    }

    private Statement wrap(Statement statement) {
        return (Statement) Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[]{Statement.class}, (proxy, method, args) -> {
            Object[] rewritten = args;
            if (args != null && args.length > 0 && args[0] instanceof String sql
                    && (method.getName().startsWith("execute") || method.getName().equals("addBatch"))) {
                rewritten = args.clone(); rewritten[0] = rewrite(sql);
            }
            return invoke(statement, method, rewritten);
        });
    }

    private String rewrite(String sql) {
        boolean matched = false;
        for (var table : allocated.entrySet()) {
            var pattern = Pattern.compile("(?i)(?<![a-z0-9_])" + table.getKey() + "(?![a-z0-9_])");
            matched |= pattern.matcher(sql).find(); sql = pattern.matcher(sql).replaceAll(table.getValue());
        }
        if (!matched) throw new IllegalArgumentException("SQL outside the isolated owning-service fixture");
        return sql;
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    @Override public void close() {
        try {
            var admin = new JdbcTemplate(pool);
            RuntimeException failure = null;
            for (var table : allocated.entrySet()) {
                try {
                    String clone = table.getValue();
                    if (!clone.startsWith(namespace + "_") || !clone.matches("audit_[a-z_]+_[0-9a-f]{32}_[0-9]+")) throw new IllegalStateException("Unexpected scratch table name");
                    admin.execute("DROP TABLE IF EXISTS `" + clone + "`");
                    Long remaining = admin.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?", Long.class, clone);
                    Long actualRows = admin.queryForObject("SELECT COUNT(*) FROM `" + table.getKey() + "`", Long.class);
                    if (remaining != 0 || !actualRows.equals(originalRows.get(table.getKey()))) {
                        throw new IllegalStateException("Scratch cleanup or original row count verification failed");
                    }
                    System.out.println("MYSQL_CLONE_FIXTURE_REMOVED " + clone + " original_rows_unchanged=true");
                } catch (RuntimeException cleanup) {
                    if (failure == null) failure = cleanup; else failure.addSuppressed(cleanup);
                }
            }
            allocated.clear();
            if (failure != null) throw failure;
        } finally { pool.close(); }
    }
}
