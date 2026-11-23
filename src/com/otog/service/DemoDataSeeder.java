package com.otog.service;

import com.otog.database.DatabaseManager;
import com.otog.exception.OtogException;
import com.otog.model.PaymentMethod;
import com.otog.model.User;
import com.otog.model.UserRole;
import com.otog.model.Wallet;
import com.otog.repository.PaymentMethodRepository;
import com.otog.repository.UserRepository;
import com.otog.repository.WalletRepository;
import com.otog.util.MoneyUtil;
import com.otog.util.OtogConfig;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic demo data seeder and clean-reset helper for CAP-14 integration checkpoints
 * and reproducible capstone presentations.
 */
public final class DemoDataSeeder {

    private DemoDataSeeder() {
    }

    /**
     * Immutable snapshot of one deterministic seeded demo account and its wallet/bank state.
     */
    public record SeededAccountSummary(String userId,
                                       String walletId,
                                       String name,
                                       String mobile,
                                       String email,
                                       UserRole role,
                                       BigDecimal balance,
                                       String currency,
                                       int linkedBankCount) {
    }

    /**
     * Immutable summary of all deterministic demo actors, billers, and banks verified in a database.
     */
    public record DemoSeedSummary(String jdbcUrl,
                                  List<SeededAccountSummary> accounts,
                                  List<OtogConfig.DemoBillerAccount> billers,
                                  List<OtogConfig.DemoBankAccount> banks) {
    }

    /**
     * Ensures schema initialization and all enabled deterministic demo actors (admin, merchant,
     * regular demo users), linked bank payment methods, and biller catalog entries are present
     * without duplicating records or overwriting balances from prior transactions.
     */
    public static DemoSeedSummary ensureSeeded(DatabaseManager dbManager) {
        Objects.requireNonNull(dbManager, "DatabaseManager cannot be null");
        dbManager.initSchema();

        OtogConfig config = OtogConfig.getInstance();
        UserRepository userRepo = new UserRepository(dbManager);
        WalletRepository walletRepo = new WalletRepository(dbManager);
        PaymentMethodRepository pmRepo = new PaymentMethodRepository(dbManager);

        List<String> expectedUserIds = new ArrayList<>();
        OtogConfig.AdminSeedConfig adminSeed = config.getAdminSeedConfig();
        if (adminSeed.enabled()) {
            expectedUserIds.add(adminSeed.userId());
        }
        OtogConfig.MerchantSeedConfig merchantSeed = config.getMerchantSeedConfig();
        if (merchantSeed.enabled()) {
            expectedUserIds.add(merchantSeed.userId());
        }
        for (OtogConfig.DemoUserSeedConfig demoUser : config.getDemoUserSeeds()) {
            expectedUserIds.add(demoUser.userId());
        }

        List<SeededAccountSummary> summaries = new ArrayList<>();
        for (String userId : expectedUserIds) {
            User user = userRepo.findById(userId)
                    .orElseThrow(() -> new OtogException("Expected deterministic demo user missing: " + userId));
            Wallet wallet = walletRepo.findByUserId(userId)
                    .orElseThrow(() -> new OtogException("Expected deterministic demo wallet missing for: " + userId));
            List<PaymentMethod> methods = pmRepo.findByUserId(userId);
            summaries.add(new SeededAccountSummary(
                    user.getUserId(),
                    wallet.getWalletId(),
                    user.getName(),
                    user.getMobileNumber(),
                    user.getEmail(),
                    user.getRole(),
                    wallet.getBalance(),
                    wallet.getCurrency(),
                    methods.size()
            ));
        }

        return new DemoSeedSummary(
                dbManager.getJdbcUrl(),
                Collections.unmodifiableList(summaries),
                config.getDemoBillerAccounts(),
                config.getDemoPaymentMethods()
        );
    }

    /**
     * Deletes any existing file-backed SQLite database at {@code dbFilePath} (including WAL/SHM
     * sidecar files) and initializes a fresh, clean deterministic demo database.
     */
    public static DemoSeedSummary resetAndSeed(Path dbFilePath) {
        Objects.requireNonNull(dbFilePath, "Database file path cannot be null");
        try {
            Path normalized = dbFilePath.toAbsolutePath().normalize();
            Path parent = normalized.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            Files.deleteIfExists(normalized);
            Files.deleteIfExists(Path.of(normalized + "-wal"));
            Files.deleteIfExists(Path.of(normalized + "-shm"));
            Files.deleteIfExists(Path.of(normalized + "-journal"));

            try (DatabaseManager freshDb = new DatabaseManager("jdbc:sqlite:" + normalized)) {
                return ensureSeeded(freshDb);
            }
        } catch (IOException ex) {
            throw new OtogException("Failed to reset demo database at " + dbFilePath + ": " + ex.getMessage(), ex);
        }
    }

    /**
     * Formats a concise human-readable console summary of seeded demo actors, billers, and banks.
     */
    public static String formatDemoGuide(DemoSeedSummary summary) {
        Objects.requireNonNull(summary, "DemoSeedSummary cannot be null");
        StringBuilder sb = new StringBuilder();
        sb.append("============================================================\n");
        sb.append("  OTOG E-WALLET — DETERMINISTIC DEMO DATA SUMMARY\n");
        sb.append("============================================================\n");
        sb.append("Database: ").append(summary.jdbcUrl()).append("\n\n");

        sb.append("Seeded Accounts (credentials configured in config/otog.properties):\n");
        for (SeededAccountSummary acct : summary.accounts()) {
            sb.append(String.format("  - [%-8s] %-20s | ID: %-15s | Wallet: %-15s | Mobile: %-11s | Balance: %-12s | Banks: %d%n",
                    acct.role(),
                    acct.name(),
                    acct.userId(),
                    acct.walletId(),
                    acct.mobile(),
                    MoneyUtil.format(acct.balance(), acct.currency()),
                    acct.linkedBankCount()));
        }

        sb.append("\nSeeded Demo Banks:\n");
        for (OtogConfig.DemoBankAccount bank : summary.banks()) {
            sb.append(String.format("  - %-38s (%s)%s%n",
                    bank.providerName(),
                    bank.maskedNumber(),
                    bank.isDefault() ? " [DEFAULT]" : ""));
        }

        sb.append("\nSeeded Demo Billers & Sample References:\n");
        String currency = OtogConfig.getInstance().getDefaultCurrency();
        for (OtogConfig.DemoBillerAccount biller : summary.billers()) {
            sb.append(String.format("  - %-36s | Ref: %-18s | Sample: %s%n",
                    biller.billerName(),
                    biller.accountReference(),
                    MoneyUtil.format(biller.sampleAmount(), currency)));
        }
        sb.append("============================================================");
        return sb.toString();
    }
}
