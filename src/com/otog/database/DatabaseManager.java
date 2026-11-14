package com.otog.database;

import com.otog.exception.OtogException;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * Manages SQLite database connectivity, schema lifecycle, and transactional boundaries.
 */
public class DatabaseManager implements AutoCloseable {
    public static final String DEFAULT_DB_PATH = "data/otog.db";
    public static final String DEFAULT_JDBC_URL = "jdbc:sqlite:" + DEFAULT_DB_PATH;

    private final String jdbcUrl;
    private final boolean isMemory;
    private Connection memoryAnchorConnection; // Kept open for in-memory databases to preserve state

    public DatabaseManager() {
        this(DEFAULT_JDBC_URL);
    }

    public DatabaseManager(String rawUrl) {
        String url = (rawUrl != null && !rawUrl.trim().isEmpty()) ? rawUrl : DEFAULT_JDBC_URL;
        if (url.equals("jdbc:sqlite::memory:")) {
            // Use shared in-memory DB so multiple connections share the same memory instance
            String memId = "otog_mem_" + UUID.randomUUID().toString().replace("-", "");
            this.jdbcUrl = "jdbc:sqlite:file:" + memId + "?mode=memory&cache=shared";
            this.isMemory = true;
        } else {
            this.jdbcUrl = url;
            this.isMemory = url.contains(":memory:") || url.contains("mode=memory");
        }

        if (!isMemory && this.jdbcUrl.startsWith("jdbc:sqlite:")) {
            String filePath = this.jdbcUrl.substring("jdbc:sqlite:".length());
            File dbFile = new File(filePath);
            File parentDir = dbFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }
        }

        if (isMemory) {
            try {
                this.memoryAnchorConnection = createRawConnection();
            } catch (SQLException e) {
                throw new OtogException("Failed to initialize in-memory database anchor", e);
            }
        }

        initSchema();
    }

    public String getJdbcUrl() {
        return jdbcUrl;
    }

    private Connection createRawConnection() throws SQLException {
        Connection conn = DriverManager.getConnection(jdbcUrl);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON;");
            stmt.execute("PRAGMA busy_timeout = 5000;");
        }
        return conn;
    }

    public Connection getConnection() throws SQLException {
        return createRawConnection();
    }

    public void initSchema() {
        String[] ddlStatements = {
                "CREATE TABLE IF NOT EXISTS users (" +
                        "user_id TEXT PRIMARY KEY, " +
                        "name TEXT NOT NULL, " +
                        "mobile_number TEXT NOT NULL UNIQUE, " +
                        "email TEXT, " +
                        "role TEXT NOT NULL DEFAULT 'USER', " +
                        "status TEXT NOT NULL DEFAULT 'ACTIVE', " +
                        "created_at TEXT NOT NULL, " +
                        "updated_at TEXT NOT NULL);",

                "CREATE TABLE IF NOT EXISTS wallets (" +
                        "wallet_id TEXT PRIMARY KEY, " +
                        "user_id TEXT NOT NULL UNIQUE, " +
                        "balance TEXT NOT NULL, " +
                        "currency TEXT NOT NULL DEFAULT 'PHP', " +
                        "status TEXT NOT NULL DEFAULT 'ACTIVE', " +
                        "created_at TEXT NOT NULL, " +
                        "updated_at TEXT NOT NULL, " +
                        "FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE RESTRICT);",

                "CREATE TABLE IF NOT EXISTS payment_methods (" +
                        "payment_method_id TEXT PRIMARY KEY, " +
                        "user_id TEXT NOT NULL, " +
                        "type TEXT NOT NULL, " +
                        "provider_name TEXT NOT NULL, " +
                        "account_name TEXT NOT NULL, " +
                        "account_number TEXT NOT NULL, " +
                        "is_default INTEGER NOT NULL DEFAULT 0, " +
                        "status TEXT NOT NULL DEFAULT 'ACTIVE', " +
                        "created_at TEXT NOT NULL, " +
                        "FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE RESTRICT);",

                "CREATE TABLE IF NOT EXISTS transactions (" +
                        "transaction_id TEXT PRIMARY KEY, " +
                        "reference_number TEXT NOT NULL UNIQUE, " +
                        "source_wallet_id TEXT, " +
                        "target_wallet_id TEXT, " +
                        "amount TEXT NOT NULL, " +
                        "fee TEXT NOT NULL DEFAULT '0.00', " +
                        "total_amount TEXT NOT NULL, " +
                        "currency TEXT NOT NULL DEFAULT 'PHP', " +
                        "type TEXT NOT NULL, " +
                        "status TEXT NOT NULL, " +
                        "description TEXT, " +
                        "created_at TEXT NOT NULL, " +
                        "completed_at TEXT, " +
                        "FOREIGN KEY (source_wallet_id) REFERENCES wallets(wallet_id), " +
                        "FOREIGN KEY (target_wallet_id) REFERENCES wallets(wallet_id));",

                "CREATE TABLE IF NOT EXISTS ledger_entries (" +
                        "ledger_entry_id TEXT PRIMARY KEY, " +
                        "transaction_id TEXT NOT NULL, " +
                        "wallet_id TEXT NOT NULL, " +
                        "entry_type TEXT NOT NULL, " +
                        "amount TEXT NOT NULL, " +
                        "balance_before TEXT NOT NULL, " +
                        "balance_after TEXT NOT NULL, " +
                        "currency TEXT NOT NULL DEFAULT 'PHP', " +
                        "description TEXT, " +
                        "created_at TEXT NOT NULL, " +
                        "FOREIGN KEY (transaction_id) REFERENCES transactions(transaction_id) ON DELETE RESTRICT, " +
                        "FOREIGN KEY (wallet_id) REFERENCES wallets(wallet_id) ON DELETE RESTRICT);",

                "CREATE INDEX IF NOT EXISTS idx_wallets_user ON wallets(user_id);",
                "CREATE INDEX IF NOT EXISTS idx_tx_ref ON transactions(reference_number);",
                "CREATE INDEX IF NOT EXISTS idx_tx_source ON transactions(source_wallet_id);",
                "CREATE INDEX IF NOT EXISTS idx_tx_target ON transactions(target_wallet_id);",
                "CREATE INDEX IF NOT EXISTS idx_ledger_wallet ON ledger_entries(wallet_id);",
                "CREATE INDEX IF NOT EXISTS idx_ledger_txn ON ledger_entries(transaction_id);",
                "CREATE INDEX IF NOT EXISTS idx_pm_user ON payment_methods(user_id);",

                "CREATE TABLE IF NOT EXISTS credentials (" +
                        "user_id TEXT PRIMARY KEY, " +
                        "password_hash TEXT NOT NULL, " +
                        "salt TEXT NOT NULL, " +
                        "failed_attempts INTEGER NOT NULL DEFAULT 0, " +
                        "locked_until TEXT, " +
                        "updated_at TEXT NOT NULL, " +
                        "FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE);",
                "CREATE INDEX IF NOT EXISTS idx_cred_user ON credentials(user_id);"
        };

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            for (String ddl : ddlStatements) {
                stmt.execute(ddl);
            }
            seedDefaultAdmin(conn);
        } catch (SQLException e) {
            throw new OtogException("Failed to initialize database schema", e);
        }
    }

    private void seedDefaultAdmin(Connection conn) {
        String checkSql = "SELECT user_id FROM users WHERE user_id = 'USR-ADMIN-01' OR LOWER(name) = 'admin'";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(checkSql)) {
            if (!rs.next()) {
                String salt = "a1b2c3d4e5f60718293a4b5c6d7e8f90";
                String hash = com.otog.util.PasswordUtil.hashPassword("password", salt);
                String now = java.time.LocalDateTime.now().toString();

                String insertUser = "INSERT INTO users (user_id, name, mobile_number, email, role, status, created_at, updated_at) " +
                        "VALUES ('USR-ADMIN-01', 'admin', '09000000000', 'admin@otog.ph', 'ADMIN', 'ACTIVE', '" + now + "', '" + now + "')";
                stmt.executeUpdate(insertUser);

                String insertCred = "INSERT INTO credentials (user_id, password_hash, salt, failed_attempts, locked_until, updated_at) " +
                        "VALUES ('USR-ADMIN-01', '" + hash + "', '" + salt + "', 0, NULL, '" + now + "')";
                stmt.executeUpdate(insertCred);

                String insertWallet = "INSERT INTO wallets (wallet_id, user_id, balance, currency, status, created_at, updated_at) " +
                        "VALUES ('WLT-ADMIN-01', 'USR-ADMIN-01', '1000.00', 'PHP', 'ACTIVE', '" + now + "', '" + now + "')";
                stmt.executeUpdate(insertWallet);
            }
        } catch (SQLException e) {
            // Seed failure should not crash if already populated concurrently
        }
    }

    /**
     * Executes a callback within an ACID transaction boundary.
     * Automatically commits if the callback finishes normally, or rolls back on exception.
     */
    public <T> T executeInTransaction(TransactionCallback<T> action) {
        Connection conn = null;
        boolean initialAutoCommit = true;
        try {
            conn = getConnection();
            initialAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);

            T result = action.execute(conn);

            conn.commit();
            return result;
        } catch (Exception e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackEx) {
                    // Suppress secondary rollback exception
                }
            }
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new OtogException("Transaction execution failed: " + e.getMessage(), e);
        } finally {
            if (conn != null) {
                try {
                    conn.setAutoCommit(initialAutoCommit);
                } catch (SQLException ignored) {
                }
                try {
                    conn.close();
                } catch (SQLException ignored) {
                }
            }
        }
    }

    @Override
    public void close() {
        if (memoryAnchorConnection != null) {
            try {
                memoryAnchorConnection.close();
            } catch (SQLException ignored) {
            }
            memoryAnchorConnection = null;
        }
    }
}
