package com.otog.database;

import com.otog.exception.OtogException;
import com.otog.util.IdGenerator;
import com.otog.util.MoneyUtil;
import com.otog.util.OtogConfig;
import com.otog.util.PasswordUtil;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

/**
 * Manages SQLite database connectivity, schema lifecycle, and transactional boundaries.
 */
public class DatabaseManager implements AutoCloseable {
    public static final String DEFAULT_DB_PATH = OtogConfig.getInstance().getDefaultDbPath();
    public static final String DEFAULT_JDBC_URL = OtogConfig.getInstance().getDefaultJdbcUrl();

    private final String jdbcUrl;
    private final boolean isMemory;
    private Connection memoryAnchorConnection; // Kept open for in-memory databases to preserve state

    public DatabaseManager() {
        this(OtogConfig.getInstance().getDefaultJdbcUrl());
    }

    public DatabaseManager(String rawUrl) {
        OtogConfig config = OtogConfig.getInstance();
        String url = (rawUrl != null && !rawUrl.trim().isEmpty()) ? rawUrl : config.getDefaultJdbcUrl();
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
        int busyTimeoutMs = OtogConfig.getInstance().getDbBusyTimeoutMs();
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON;");
            stmt.execute("PRAGMA busy_timeout = " + busyTimeoutMs + ";");
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
                        "provider_reference TEXT, " +
                        "provider_status TEXT, " +
                        "channel TEXT, " +
                        "biller_reference TEXT, " +
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
                        "pin_hash TEXT, " +
                        "pin_salt TEXT, " +
                        "pin_failed_attempts INTEGER NOT NULL DEFAULT 0, " +
                        "updated_at TEXT NOT NULL, " +
                        "FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE);",
                "CREATE INDEX IF NOT EXISTS idx_cred_user ON credentials(user_id);",

                "CREATE TABLE IF NOT EXISTS admin_actions (" +
                        "action_id TEXT PRIMARY KEY, " +
                        "admin_user_id TEXT NOT NULL, " +
                        "action_type TEXT NOT NULL, " +
                        "target_user_id TEXT, " +
                        "reason TEXT, " +
                        "details TEXT, " +
                        "created_at TEXT NOT NULL, " +
                        "FOREIGN KEY (admin_user_id) REFERENCES users(user_id) ON DELETE RESTRICT);",
                "CREATE INDEX IF NOT EXISTS idx_admin_act_admin ON admin_actions(admin_user_id);",
                "CREATE INDEX IF NOT EXISTS idx_admin_act_target ON admin_actions(target_user_id);",

                "CREATE TABLE IF NOT EXISTS notifications (" +
                        "notification_id TEXT PRIMARY KEY, " +
                        "user_id TEXT NOT NULL, " +
                        "transaction_id TEXT, " +
                        "reference_number TEXT, " +
                        "type TEXT NOT NULL, " +
                        "title TEXT NOT NULL, " +
                        "message TEXT NOT NULL, " +
                        "is_read INTEGER NOT NULL DEFAULT 0, " +
                        "dedup_key TEXT UNIQUE, " +
                        "created_at TEXT NOT NULL, " +
                        "read_at TEXT, " +
                        "FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE, " +
                        "FOREIGN KEY (transaction_id) REFERENCES transactions(transaction_id) ON DELETE SET NULL);",
                "CREATE INDEX IF NOT EXISTS idx_notif_user ON notifications(user_id);",
                "CREATE INDEX IF NOT EXISTS idx_notif_user_read ON notifications(user_id, is_read);",
                "CREATE INDEX IF NOT EXISTS idx_notif_txn ON notifications(transaction_id);"
        };

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            for (String ddl : ddlStatements) {
                stmt.execute(ddl);
            }
            ensureCredentialPinColumns(conn);
            ensureTransactionColumns(conn);
            seedDefaultAdmin(conn);
            seedDefaultMerchant(conn);
            seedDemoUsers(conn);
            seedDemoPaymentMethods(conn);
        } catch (SQLException e) {
            throw new OtogException("Failed to initialize database schema", e);
        }
    }

    private void ensureCredentialPinColumns(Connection conn) throws SQLException {
        boolean hasPinHash = false;
        boolean hasPinSalt = false;
        boolean hasPinFailedAttempts = false;
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(credentials)")) {
            while (rs.next()) {
                String col = rs.getString("name");
                if ("pin_hash".equalsIgnoreCase(col)) {
                    hasPinHash = true;
                } else if ("pin_salt".equalsIgnoreCase(col)) {
                    hasPinSalt = true;
                } else if ("pin_failed_attempts".equalsIgnoreCase(col)) {
                    hasPinFailedAttempts = true;
                }
            }
        }
        try (Statement stmt = conn.createStatement()) {
            if (!hasPinHash) {
                stmt.execute("ALTER TABLE credentials ADD COLUMN pin_hash TEXT");
            }
            if (!hasPinSalt) {
                stmt.execute("ALTER TABLE credentials ADD COLUMN pin_salt TEXT");
            }
            if (!hasPinFailedAttempts) {
                stmt.execute("ALTER TABLE credentials ADD COLUMN pin_failed_attempts INTEGER NOT NULL DEFAULT 0");
            }
        }
    }

    private void ensureTransactionColumns(Connection conn) throws SQLException {
        boolean hasProviderRef = false;
        boolean hasProviderStatus = false;
        boolean hasChannel = false;
        boolean hasBillerRef = false;
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(transactions)")) {
            while (rs.next()) {
                String col = rs.getString("name");
                if ("provider_reference".equalsIgnoreCase(col)) {
                    hasProviderRef = true;
                } else if ("provider_status".equalsIgnoreCase(col)) {
                    hasProviderStatus = true;
                } else if ("channel".equalsIgnoreCase(col)) {
                    hasChannel = true;
                } else if ("biller_reference".equalsIgnoreCase(col)) {
                    hasBillerRef = true;
                }
            }
        }
        try (Statement stmt = conn.createStatement()) {
            if (!hasProviderRef) {
                stmt.execute("ALTER TABLE transactions ADD COLUMN provider_reference TEXT");
            }
            if (!hasProviderStatus) {
                stmt.execute("ALTER TABLE transactions ADD COLUMN provider_status TEXT");
            }
            if (!hasChannel) {
                stmt.execute("ALTER TABLE transactions ADD COLUMN channel TEXT");
            }
            if (!hasBillerRef) {
                stmt.execute("ALTER TABLE transactions ADD COLUMN biller_reference TEXT");
            }
        }
    }

    private void seedDemoPaymentMethods(Connection conn) {
        OtogConfig config = OtogConfig.getInstance();
        List<OtogConfig.DemoBankAccount> demoAccounts = config.getDemoPaymentMethods();
        OtogConfig.AdminSeedConfig adminSeed = config.getAdminSeedConfig();
        OtogConfig.MerchantSeedConfig merchantSeed = config.getMerchantSeedConfig();
        List<OtogConfig.DemoUserSeedConfig> demoUsers = config.getDemoUserSeeds();

        String insertSql = "INSERT OR IGNORE INTO payment_methods (payment_method_id, user_id, type, provider_name, " +
                "account_name, account_number, is_default, status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

        if (adminSeed.enabled()) {
            seedDeterministicPaymentMethodsForUser(
                    conn, insertSql, adminSeed.userId(), "Admin", "PM-DEMO-", demoAccounts);
        }
        if (merchantSeed.enabled()) {
            seedDeterministicPaymentMethodsForUser(
                    conn, insertSql, merchantSeed.userId(), merchantSeed.username(),
                    "PM-DEMO-" + merchantSeed.userId() + "-", demoAccounts);
        }
        for (OtogConfig.DemoUserSeedConfig demoUser : demoUsers) {
            seedDeterministicPaymentMethodsForUser(
                    conn, insertSql, demoUser.userId(), demoUser.username(),
                    "PM-DEMO-" + demoUser.userId() + "-", demoAccounts);
        }

        // Backfill demo bank methods for any pre-existing non-seeded users lacking payment methods
        String missingUsersSql = "SELECT u.user_id, u.name FROM users u " +
                "WHERE NOT EXISTS (SELECT 1 FROM payment_methods pm WHERE pm.user_id = u.user_id)";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(missingUsersSql);
             PreparedStatement ps = conn.prepareStatement(insertSql)) {
            String now = java.time.LocalDateTime.now().toString();
            while (rs.next()) {
                String uid = rs.getString("user_id");
                String uname = rs.getString("name");
                String displayName = (uname != null && !uname.trim().isEmpty()) ? uname.trim() : "Demo User";

                for (OtogConfig.DemoBankAccount demo : demoAccounts) {
                    ps.setString(1, IdGenerator.nextPaymentMethodId());
                    ps.setString(2, uid);
                    ps.setString(3, "BANK_ACCOUNT");
                    ps.setString(4, demo.providerName());
                    ps.setString(5, displayName + " " + demo.accountSuffix());
                    ps.setString(6, demo.maskedNumber());
                    ps.setInt(7, demo.isDefault() ? 1 : 0);
                    ps.setString(8, "ACTIVE");
                    ps.setString(9, now);
                    ps.addBatch();
                }
            }
            ps.executeBatch();
        } catch (SQLException e) {
            if (e.getMessage() == null || !e.getMessage().toUpperCase().contains("CONSTRAINT")) {
                throw new OtogException("Failed to backfill demo payment methods", e);
            }
        }
    }

    private void seedDeterministicPaymentMethodsForUser(Connection conn,
                                                        String insertSql,
                                                        String userId,
                                                        String displayName,
                                                        String idPrefix,
                                                        List<OtogConfig.DemoBankAccount> demoAccounts) {
        if (userId == null || userId.trim().isEmpty()) {
            return;
        }
        String checkUserSql = "SELECT user_id FROM users WHERE user_id = ?";
        String checkPmSql = "SELECT payment_method_id FROM payment_methods WHERE user_id = ?";
        try (PreparedStatement userPs = conn.prepareStatement(checkUserSql)) {
            userPs.setString(1, userId);
            try (ResultSet userRs = userPs.executeQuery()) {
                if (!userRs.next()) {
                    return;
                }
            }
            try (PreparedStatement checkPs = conn.prepareStatement(checkPmSql)) {
                checkPs.setString(1, userId);
                try (ResultSet rs = checkPs.executeQuery()) {
                    if (!rs.next()) {
                        String now = java.time.LocalDateTime.now().toString();
                        String label = (displayName != null && !displayName.trim().isEmpty())
                                ? displayName.trim() : "Demo User";
                        try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                            for (OtogConfig.DemoBankAccount demo : demoAccounts) {
                                ps.setString(1, idPrefix + demo.idSuffix());
                                ps.setString(2, userId);
                                ps.setString(3, "BANK_ACCOUNT");
                                ps.setString(4, demo.providerName());
                                ps.setString(5, label + " " + demo.accountSuffix());
                                ps.setString(6, demo.maskedNumber());
                                ps.setInt(7, demo.isDefault() ? 1 : 0);
                                ps.setString(8, "ACTIVE");
                                ps.setString(9, now);
                                ps.addBatch();
                            }
                            ps.executeBatch();
                        }
                    }
                }
            }
        } catch (SQLException e) {
            if (e.getMessage() == null || !e.getMessage().toUpperCase().contains("CONSTRAINT")) {
                throw new OtogException("Failed to seed deterministic demo payment methods for " + userId, e);
            }
        }
    }

    private void seedDefaultAdmin(Connection conn) {
        OtogConfig config = OtogConfig.getInstance();
        OtogConfig.AdminSeedConfig seed = config.getAdminSeedConfig();
        if (!seed.enabled()) {
            return;
        }
        seedAccountIfMissing(conn, seed.userId(), seed.walletId(), seed.username(), seed.mobile(),
                seed.email(), seed.password(), seed.pin(), "ADMIN", seed.initialBalance(),
                "default administrator");
    }

    private void seedDefaultMerchant(Connection conn) {
        OtogConfig config = OtogConfig.getInstance();
        OtogConfig.MerchantSeedConfig seed = config.getMerchantSeedConfig();
        if (!seed.enabled()) {
            return;
        }
        seedAccountIfMissing(conn, seed.userId(), seed.walletId(), seed.username(), seed.mobile(),
                seed.email(), seed.password(), seed.pin(), "MERCHANT", seed.initialBalance(),
                "default merchant");
    }

    private void seedDemoUsers(Connection conn) {
        OtogConfig config = OtogConfig.getInstance();
        for (OtogConfig.DemoUserSeedConfig seed : config.getDemoUserSeeds()) {
            seedAccountIfMissing(conn, seed.userId(), seed.walletId(), seed.username(), seed.mobile(),
                    seed.email(), seed.password(), seed.pin(), seed.role(), seed.initialBalance(),
                    "demo user " + seed.userId());
        }
    }

    private void seedAccountIfMissing(Connection conn,
                                      String userId,
                                      String walletId,
                                      String username,
                                      String mobile,
                                      String email,
                                      String rawPassword,
                                      String rawPin,
                                      String role,
                                      java.math.BigDecimal initialBalance,
                                      String label) {
        OtogConfig config = OtogConfig.getInstance();
        String checkSql = "SELECT user_id FROM users WHERE user_id = ? OR LOWER(name) = LOWER(?) OR mobile_number = ?";
        try (PreparedStatement checkPs = conn.prepareStatement(checkSql)) {
            checkPs.setString(1, userId);
            checkPs.setString(2, username);
            checkPs.setString(3, mobile);
            try (ResultSet rs = checkPs.executeQuery()) {
                if (!rs.next()) {
                    String salt = PasswordUtil.generateSalt();
                    String hash = PasswordUtil.hashPassword(rawPassword, salt);
                    String pinSalt = (rawPin != null && !rawPin.trim().isEmpty()) ? PasswordUtil.generateSalt() : null;
                    String pinHash = (pinSalt != null) ? PasswordUtil.hashPassword(rawPin.trim(), pinSalt) : null;
                    String now = java.time.LocalDateTime.now().toString();
                    String currency = MoneyUtil.validateCurrency(config.getDefaultCurrency());
                    String effectiveRole = (role != null && !role.trim().isEmpty()) ? role.trim().toUpperCase() : "USER";
                    java.math.BigDecimal safeBalance = initialBalance != null
                            ? MoneyUtil.validateNonNegativeAmount(initialBalance)
                            : MoneyUtil.ZERO;

                    String insertUserSql = "INSERT OR IGNORE INTO users " +
                            "(user_id, name, mobile_number, email, role, status, created_at, updated_at) " +
                            "VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?)";
                    try (PreparedStatement ps = conn.prepareStatement(insertUserSql)) {
                        ps.setString(1, userId);
                        ps.setString(2, username);
                        ps.setString(3, mobile);
                        ps.setString(4, email);
                        ps.setString(5, effectiveRole);
                        ps.setString(6, now);
                        ps.setString(7, now);
                        ps.executeUpdate();
                    }

                    String insertCredSql = "INSERT OR IGNORE INTO credentials " +
                            "(user_id, password_hash, salt, failed_attempts, locked_until, pin_hash, pin_salt, pin_failed_attempts, updated_at) " +
                            "VALUES (?, ?, ?, 0, NULL, ?, ?, 0, ?)";
                    try (PreparedStatement ps = conn.prepareStatement(insertCredSql)) {
                        ps.setString(1, userId);
                        ps.setString(2, hash);
                        ps.setString(3, salt);
                        ps.setString(4, pinHash);
                        ps.setString(5, pinSalt);
                        ps.setString(6, now);
                        ps.executeUpdate();
                    }

                    String insertWalletSql = "INSERT OR IGNORE INTO wallets " +
                            "(wallet_id, user_id, balance, currency, status, created_at, updated_at) " +
                            "VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)";
                    try (PreparedStatement ps = conn.prepareStatement(insertWalletSql)) {
                        ps.setString(1, walletId);
                        ps.setString(2, userId);
                        ps.setString(3, safeBalance.toPlainString());
                        ps.setString(4, currency);
                        ps.setString(5, now);
                        ps.setString(6, now);
                        ps.executeUpdate();
                    }
                }
            }
        } catch (SQLException e) {
            if (e.getMessage() == null || !e.getMessage().toUpperCase().contains("CONSTRAINT")) {
                throw new OtogException("Failed to seed " + label + " account", e);
            }
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
                    e.addSuppressed(rollbackEx);
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
                } catch (SQLException cleanupEx) {
                    System.err.println("[WARN] Failed to restore connection autoCommit: " + cleanupEx.getMessage());
                }
                try {
                    conn.close();
                } catch (SQLException closeEx) {
                    System.err.println("[WARN] Failed to close SQLite connection: " + closeEx.getMessage());
                }
            }
        }
    }

    @Override
    public void close() {
        if (memoryAnchorConnection != null) {
            try {
                memoryAnchorConnection.close();
            } catch (SQLException closeEx) {
                System.err.println("[WARN] Failed to close in-memory anchor connection: " + closeEx.getMessage());
            }
            memoryAnchorConnection = null;
        }
    }
}
