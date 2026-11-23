package com.otog.util;

import java.awt.Dimension;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe Singleton providing externalized application configuration loaded from
 * {@code config/otog.properties}, classpath {@code /otog.properties}, system properties
 * ({@code -Dotog.<key>=<value>}), and optional test overrides.
 */
public final class OtogConfig {

    public static final String DEFAULT_CONFIG_PATH = "config/otog.properties";
    public static final String CONFIG_FILE_SYSPROP = "otog.config.file";
    public static final String DEFAULT_INVESTMENT_PRODUCT_CODE = "OTOG-SIM-FUND";
    public static final String DEFAULT_INVESTMENT_PRODUCT_NAME = "OTOG Simulated Balanced Fund";
    public static final BigDecimal DEFAULT_INVESTMENT_SIMULATED_UNIT_PRICE = new BigDecimal("100.00");
    private static final String SYSPROP_PREFIX = "otog.";

    private static volatile OtogConfig instance;

    private final ConcurrentHashMap<String, String> overrides = new ConcurrentHashMap<>();
    private final Properties loadedProperties = new Properties();

    /**
     * Immutable record describing a seeded demo bank payment method entry.
     */
    public record DemoBankAccount(String idSuffix,
                                  String providerName,
                                  String accountSuffix,
                                  String maskedNumber,
                                  boolean isDefault) {
    }

    /**
     * Immutable record describing the default administrator account seed configuration.
     */
    public record AdminSeedConfig(boolean enabled,
                                  String userId,
                                  String walletId,
                                  String username,
                                  String mobile,
                                  String email,
                                  String password,
                                  String pin,
                                  BigDecimal initialBalance) {
    }

    private OtogConfig() {
        reload();
    }

    public static OtogConfig getInstance() {
        if (instance == null) {
            synchronized (OtogConfig.class) {
                if (instance == null) {
                    instance = new OtogConfig();
                }
            }
        }
        return instance;
    }

    public synchronized void reload() {
        String customPath = System.getProperty(CONFIG_FILE_SYSPROP);
        Path targetPath = (customPath != null && !customPath.trim().isEmpty())
                ? Paths.get(customPath.trim())
                : Paths.get(DEFAULT_CONFIG_PATH);
        reloadFrom(targetPath);
    }

    public synchronized void reloadFrom(Path filePath) {
        loadedProperties.clear();

        // Load classpath defaults first, then overlay filesystem config file if present.
        try (InputStream cp = OtogConfig.class.getResourceAsStream("/otog.properties")) {
            if (cp != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(cp, StandardCharsets.UTF_8))) {
                    loadedProperties.load(reader);
                }
            }
        } catch (IOException ex) {
            System.err.println("[WARN] Failed to load classpath otog.properties: " + ex.getMessage());
        }

        if (filePath != null && Files.isRegularFile(filePath)) {
            try (BufferedReader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
                loadedProperties.load(reader);
            } catch (IOException ex) {
                System.err.println("[WARN] Failed to load config file " + filePath + ": " + ex.getMessage());
            }
        }
    }

    public void setOverride(String key, String value) {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("Configuration key cannot be null or blank");
        }
        if (value == null) {
            overrides.remove(key.trim());
        } else {
            overrides.put(key.trim(), value);
        }
    }

    public void removeOverride(String key) {
        if (key != null) {
            overrides.remove(key.trim());
        }
    }

    public void clearOverrides() {
        overrides.clear();
    }

    public boolean hasOverrideOrSysProp(String key) {
        if (key == null || key.trim().isEmpty()) {
            return false;
        }
        String trimmedKey = key.trim();
        return overrides.containsKey(trimmedKey) || System.getProperty(SYSPROP_PREFIX + trimmedKey) != null;
    }

    public String getString(String key, String defaultValue) {
        if (key == null || key.trim().isEmpty()) {
            return defaultValue;
        }
        String trimmedKey = key.trim();
        String overrideVal = overrides.get(trimmedKey);
        if (overrideVal != null) {
            return overrideVal;
        }
        String sysVal = System.getProperty(SYSPROP_PREFIX + trimmedKey);
        if (sysVal != null) {
            return sysVal;
        }
        synchronized (this) {
            String propVal = loadedProperties.getProperty(trimmedKey);
            if (propVal != null) {
                return propVal.trim();
            }
        }
        return defaultValue;
    }

    public int getInt(String key, int defaultValue) {
        String raw = getString(key, null);
        if (raw == null || raw.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    public long getLong(String key, long defaultValue) {
        String raw = getString(key, null);
        if (raw == null || raw.isEmpty()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String raw = getString(key, null);
        if (raw == null || raw.isEmpty()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(raw.trim());
    }

    public BigDecimal getBigDecimal(String key, String defaultValue) {
        String raw = getString(key, defaultValue);
        if (raw == null || raw.isEmpty()) {
            return new BigDecimal(defaultValue);
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException ex) {
            return new BigDecimal(defaultValue);
        }
    }

    public List<String> getStringList(String key, List<String> defaults) {
        String raw = getString(key, null);
        if (raw == null || raw.isEmpty()) {
            return defaults;
        }
        List<String> items = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                items.add(trimmed);
            }
        }
        return items.isEmpty() ? defaults : Collections.unmodifiableList(items);
    }

    // --- Database ---

    public String getDefaultDbPath() {
        return getString("db.path", "data/otog.db");
    }

    public String getDefaultJdbcUrl() {
        return "jdbc:sqlite:" + getDefaultDbPath();
    }

    public int getDbBusyTimeoutMs() {
        return Math.max(100, getInt("db.busy.timeout.ms", 5000));
    }

    // --- Currency & Money ---

    public String getDefaultCurrency() {
        return getString("currency.default", "PHP");
    }

    // --- Auth, PIN & Session ---

    public int getAuthMaxFailedAttempts() {
        return Math.max(1, getInt("auth.max.failed.attempts", 5));
    }

    public int getMinPasswordLength() {
        return Math.max(1, getInt("auth.min.password.length", 6));
    }

    public String getMobileRegex() {
        return getString("auth.mobile.regex", "^09[0-9]{9}$");
    }

    public int getPinMinLength() {
        return Math.max(1, getInt("auth.pin.min.length", 4));
    }

    public int getPinMaxLength() {
        return Math.max(getPinMinLength(), getInt("auth.pin.max.length", 6));
    }

    public int getMaxPinAttempts() {
        return Math.max(1, getInt("auth.pin.max.attempts", 3));
    }

    public long getSessionInactivityMinutes() {
        return Math.max(1L, getLong("session.inactivity.minutes", 15L));
    }

    public int getSessionPollIntervalMs() {
        return Math.max(500, getInt("session.poll.interval.ms", 15_000));
    }

    // --- OTP ---

    public int getOtpExpiryMinutes() {
        return Math.max(1, getInt("otp.expiry.minutes", 5));
    }

    public int getOtpResendCooldownSeconds() {
        return Math.max(1, getInt("otp.resend.cooldown.seconds", 60));
    }

    public int getOtpMaxAttempts() {
        return Math.max(1, getInt("otp.max.attempts", 5));
    }

    public int getOtpLength() {
        return Math.max(4, Math.min(9, getInt("otp.length", 6)));
    }

    // --- Transactions & Limits ---

    public BigDecimal getSendMinAmount() {
        return getBigDecimal("send.min.amount", "1.00")
                .setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING);
    }

    public BigDecimal getSendMaxAmount() {
        return getBigDecimal("send.max.amount", "50000.00")
                .setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING);
    }

    public BigDecimal getSendDefaultFlatFee() {
        return getBigDecimal("send.default.fee.flat", "0.00")
                .setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING);
    }

    public BigDecimal getSendDefaultPercentFee() {
        return getBigDecimal("send.default.fee.percent", "0.00");
    }

    public int getMinRecipientLength() {
        return Math.max(1, getInt("send.min.recipient.length", 10));
    }

    public BigDecimal getInvestmentReturnRate() {
        return getBigDecimal("investment.default.return.rate", "0.05");
    }

    public String getInvestmentProductCode() {
        String code = getString("investment.product.code", DEFAULT_INVESTMENT_PRODUCT_CODE);
        return (code == null || code.isBlank()) ? DEFAULT_INVESTMENT_PRODUCT_CODE : code.trim();
    }

    public String getInvestmentProductName() {
        String name = getString("investment.product.name", DEFAULT_INVESTMENT_PRODUCT_NAME);
        return (name == null || name.isBlank()) ? DEFAULT_INVESTMENT_PRODUCT_NAME : name.trim();
    }

    public BigDecimal getInvestmentSimulatedUnitPrice() {
        BigDecimal price = getBigDecimal("investment.simulated.unit.price", DEFAULT_INVESTMENT_SIMULATED_UNIT_PRICE.toPlainString());
        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            price = DEFAULT_INVESTMENT_SIMULATED_UNIT_PRICE;
        }
        return price.setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING);
    }

    public int getRecentTransactionLimit() {
        return Math.max(1, getInt("dashboard.recent.transaction.limit", 50));
    }

    // --- Channels & Seeded Demo Data ---

    public List<String> getCashOperations() {
        return getStringList("cash.operations", Arrays.asList("CASH_IN", "CASH_OUT"));
    }

    public List<String> getCashChannels() {
        return getStringList("cash.channels", Arrays.asList("GCash", "Maya", "BDO", "BPI", "7-Eleven OTC"));
    }

    public List<DemoBankAccount> getDemoPaymentMethods() {
        String raw = getString("demo.payment.methods", "");
        List<DemoBankAccount> parsed = new ArrayList<>();
        if (raw != null && !raw.isEmpty()) {
            String[] entries = raw.split(";");
            for (String entry : entries) {
                String trimmed = entry.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                String[] parts = trimmed.split("\\|", -1);
                if (parts.length >= 5) {
                    parsed.add(new DemoBankAccount(
                            parts[0].trim(),
                            parts[1].trim(),
                            parts[2].trim(),
                            parts[3].trim(),
                            Boolean.parseBoolean(parts[4].trim())
                    ));
                }
            }
        }
        if (parsed.isEmpty()) {
            parsed.add(new DemoBankAccount("BDO", "BDO Unibank", "BDO Demo", "\u2022\u2022\u2022\u2022 1234", true));
            parsed.add(new DemoBankAccount("BPI", "Bank of the Philippine Islands (BPI)", "BPI Demo", "\u2022\u2022\u2022\u2022 5678", false));
            parsed.add(new DemoBankAccount("METRO", "Metrobank", "Metrobank Demo", "\u2022\u2022\u2022\u2022 9012", false));
        }
        return Collections.unmodifiableList(parsed);
    }

    public AdminSeedConfig getAdminSeedConfig() {
        return new AdminSeedConfig(
                getBoolean("admin.seed.enabled", true),
                getString("admin.user.id", "USR-ADMIN-01"),
                getString("admin.wallet.id", "WLT-ADMIN-01"),
                getString("admin.username", "admin"),
                getString("admin.mobile", "09000000000"),
                getString("admin.email", "admin@otog.ph"),
                getString("admin.password", "password"),
                getString("admin.pin", "123456"),
                getBigDecimal("admin.initial.balance", "1000.00")
                        .setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING)
        );
    }

    // --- UI Dimensions & Theme ---

    public String getAppTitle() {
        return getString("ui.app.title", "OTOG E-Wallet");
    }

    public String getFontFamily() {
        return getString("ui.font.family", "SansSerif");
    }

    public String getMonoFontFamily() {
        return getString("ui.font.mono", "Monospaced");
    }

    public String getColorHex(String key, String fallback) {
        return getString(key, fallback);
    }

    public Dimension getUiDimension(String widthKey, String heightKey, int defaultWidth, int defaultHeight) {
        return new Dimension(getInt(widthKey, defaultWidth), getInt(heightKey, defaultHeight));
    }

    public int getTableRowHeight() {
        return Math.max(16, getInt("ui.table.row.height", 24));
    }

    // --- File Handling & Formatting ---

    public String getStatementDefaultFileName() {
        return getString("file.statement.default.name", "otog-statement.txt");
    }

    public String getReceiptFilePrefix() {
        return getString("file.receipt.prefix", "receipt-");
    }

    public String getTimestampPattern() {
        return getString("file.timestamp.pattern", "yyyy-MM-dd HH:mm:ss");
    }

    public String getUiDatePattern() {
        return getString("ui.date.pattern", "yyyy-MM-dd HH:mm");
    }
}
