package com.enterprise.ai.capability.registry;

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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Shared opt-in registry fixture; SQL is confined to uniquely owned empty table clones. */
final class RegistryMysqlTestDatabase extends AbstractDataSource implements AutoCloseable {
    private static final String DEVELOPMENT_SERVER = "d68937fe-df9e-11f0-ad94-00163e10c3b6";
    private final HikariDataSource delegate;
    private final Map<String, String> tables = new LinkedHashMap<>();
    private final Map<String, Long> originalRows = new LinkedHashMap<>();
    private final Set<String> created = new LinkedHashSet<>();

    static RegistryMysqlTestDatabase instances() throws SQLException {
        return new RegistryMysqlTestDatabase("reachai.mysql.instanceVerification", "instance", List.of("capability_project_instance"));
    }

    static RegistryMysqlTestDatabase enrollment() throws SQLException {
        return new RegistryMysqlTestDatabase("reachai.mysql.enrollmentVerification", "enrollment", List.of(
                "capability_scan_project", "capability_registry_project_credential", "capability_registry_enrollment_token"));
    }

    private RegistryMysqlTestDatabase(String option, String domain, List<String> originals) throws SQLException {
        if (!Boolean.getBoolean(option)) throw new IllegalStateException("MySQL fixture must be explicitly enabled");
        String prefix = "audit_registry_" + domain + "_" + UUID.randomUUID().toString().replace("-", "");
        for (int i = 0; i < originals.size(); i++) tables.put(originals.get(i), prefix + "_" + i);
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
        pool.setPoolName(prefix);
        pool.setMaximumPoolSize(2);
        pool.setMinimumIdle(2);
        pool.setConnectionTimeout(30000);
        pool.setTransactionIsolation("TRANSACTION_REPEATABLE_READ");
        delegate = new HikariDataSource(pool);
        try {
            // Establish both independent sessions before any timed transaction interleaving.
            try (Connection first = delegate.getConnection(); Connection second = delegate.getConnection()) {
                if (!first.isValid(5) || !second.isValid(5)) throw new SQLException("MySQL fixture sessions are not ready");
            }
            var admin = new JdbcTemplate(delegate);
            if (!DEVELOPMENT_SERVER.equals(admin.queryForObject("SELECT @@server_uuid", String.class))
                    || !"reach_ai".equals(admin.queryForObject("SELECT DATABASE()", String.class))) {
                throw new IllegalStateException("MySQL fixture server identity mismatch");
            }
            for (var item : tables.entrySet()) {
                originalRows.put(item.getKey(), admin.queryForObject("SELECT COUNT(*) FROM " + item.getKey(), Long.class));
                // Live tables provide schema and counts only; test data stays in empty, isolated clones.
                System.out.println("MYSQL_REGISTRY_FIXTURE_ALLOCATED " + item.getValue());
                created.add(item.getValue());
                admin.execute("CREATE TABLE `" + item.getValue() + "` LIKE `" + item.getKey() + "`");
                System.out.println("MYSQL_REGISTRY_FIXTURE_CREATED " + item.getValue());
            }
        } catch (RuntimeException | SQLException failure) {
            try { close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    @Override public Connection getConnection() throws SQLException { return wrap(delegate.getConnection()); }
    @Override public Connection getConnection(String username, String password) throws SQLException {
        throw new SQLException("Explicit credentials are not supported by this fixture");
    }

    private Connection wrap(Connection raw) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
            if (method.getName().equals("equals")) return proxy == args[0];
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (method.getName().equals("toString")) return "IsolatedRegistryTestConnection";
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
        String rewritten = sql;
        boolean matched = false;
        for (var item : tables.entrySet()) {
            var pattern = Pattern.compile("(?i)(?<![a-z0-9_])" + item.getKey() + "(?![a-z0-9_])");
            if (pattern.matcher(rewritten).find()) {
                matched = true;
                rewritten = pattern.matcher(rewritten).replaceAll(item.getValue());
            }
        }
        if (!matched) throw new IllegalArgumentException("SQL outside the isolated registry fixture");
        return rewritten;
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    @Override public void close() {
        try {
            var admin = new JdbcTemplate(delegate);
            RuntimeException cleanupFailure = null;
            for (var item : tables.entrySet()) {
                String table = item.getValue();
                if (!created.contains(table)) continue;
                try {
                    if (!table.matches("audit_registry_(?:instance|enrollment)_[0-9a-f]{32}_[0-2]")) throw new IllegalStateException("Unexpected scratch table name");
                    admin.execute("DROP TABLE IF EXISTS `" + table + "`");
                    created.remove(table);
                    Long remaining = admin.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?", Long.class, table);
                    Long actualRows = admin.queryForObject("SELECT COUNT(*) FROM " + item.getKey(), Long.class);
                    if (remaining != 0 || !actualRows.equals(originalRows.get(item.getKey()))) throw new IllegalStateException("Scratch cleanup or original row count verification failed");
                    System.out.println("MYSQL_REGISTRY_FIXTURE_REMOVED " + table + " original_rows_unchanged=true");
                } catch (RuntimeException failure) {
                    if (cleanupFailure == null) cleanupFailure = failure;
                    else cleanupFailure.addSuppressed(failure);
                }
            }
            if (cleanupFailure != null) throw cleanupFailure;
        } finally { delegate.close(); }
    }
}
