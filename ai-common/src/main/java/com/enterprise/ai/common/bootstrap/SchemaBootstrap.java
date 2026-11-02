package com.enterprise.ai.common.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Creates the schema on an empty database from the bundled {@code initV2.sql}.
 * Runs before Spring creates any bean, under a MySQL named lock, so when five services
 * start together exactly one of them initializes and the others wait for it.
 * Existing databases are never touched: upgrades stay explicit (see sql/README.md).
 */
final class SchemaBootstrap {

    static final String SCHEMA_RESOURCE = "reachai/schema/initV2.sql";
    static final String LOCK_NAME = "reachai.schema.bootstrap";
    private static final int LOCK_TIMEOUT_SECONDS = 300;

    enum State {
        /** The database had no tables; this call created the schema. */
        CREATED,
        /** The database already had tables. */
        EXISTING
    }

    /** Work that must happen while the bootstrap lock is held, e.g. creating shared secrets. */
    interface InsideLock {
        void run(State state) throws IOException;
    }

    private SchemaBootstrap() {
    }

    static State run(String url, String username, String password, boolean createSchema, InsideLock insideLock)
            throws SQLException, IOException {
        Properties properties = new Properties();
        if (username != null) {
            properties.setProperty("user", username);
        }
        if (password != null) {
            properties.setProperty("password", password);
        }
        properties.setProperty("connectTimeout", "5000");
        properties.setProperty("allowMultiQueries", "false");
        try (Connection connection = DriverManager.getConnection(url, properties)) {
            acquireLock(connection);
            try {
                State state = tableCount(connection) == 0 ? State.CREATED : State.EXISTING;
                if (state == State.CREATED && createSchema) {
                    execute(connection, loadScript());
                }
                insideLock.run(state);
                return state;
            } finally {
                releaseLock(connection);
            }
        }
    }

    static int tableCount(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()")) {
            result.next();
            return result.getInt(1);
        }
    }

    static String loadScript() throws IOException {
        try (InputStream input = SchemaBootstrap.class.getClassLoader().getResourceAsStream(SCHEMA_RESOURCE)) {
            if (input == null) {
                throw new IOException("Bundled schema " + SCHEMA_RESOURCE + " is missing from the classpath");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Runs the script against the connection's database; its own CREATE DATABASE / USE lines are skipped. */
    static void execute(Connection connection, String script) throws SQLException {
        List<String> statements = SqlScriptSplitter.split(script);
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET NAMES utf8mb4");
            for (String sql : statements) {
                String head = sql.toUpperCase(Locale.ROOT);
                if (head.startsWith("CREATE DATABASE") || head.startsWith("USE ")) {
                    continue;
                }
                try {
                    statement.execute(sql);
                } catch (SQLException failure) {
                    throw new SQLException("Schema initialization failed at: " + abbreviate(sql), failure);
                }
            }
        }
    }

    private static void acquireLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, ?)")) {
            statement.setString(1, LOCK_NAME);
            statement.setInt(2, LOCK_TIMEOUT_SECONDS);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getInt(1) != 1) {
                    throw new SQLException("Timed out waiting for another ReachAI service to initialize the database");
                }
            }
        }
    }

    private static void releaseLock(Connection connection) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            statement.setString(1, LOCK_NAME);
            statement.executeQuery().close();
        } catch (SQLException ignored) {
            // Closing the connection releases the lock as well.
        }
    }

    private static String abbreviate(String sql) {
        String oneLine = sql.replaceAll("\\s+", " ");
        return oneLine.length() <= 160 ? oneLine : oneLine.substring(0, 160) + "...";
    }
}
