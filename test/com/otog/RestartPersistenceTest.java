package com.otog;

import com.otog.database.DatabaseManager;
import com.otog.model.*;
import com.otog.service.WalletService;
import com.otog.util.IdGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests AC 4: Verify restart persistence using file-based SQLite database.
 * Simulates complete app shutdown and restart.
 */
public class RestartPersistenceTest {
    private static final String TEST_DB_PATH = "data/test_restart_domain.db";
    private static final String TEST_JDBC_URL = "jdbc:sqlite:" + TEST_DB_PATH;

    @BeforeEach
    @AfterEach
    void cleanUpDbFile() {
        File f = new File(TEST_DB_PATH);
        if (f.exists()) {
            f.delete();
        }
    }

    @Test
    void testDataSurvivesApplicationRestart() {
        String userId1 = "USR-RESTART-01";
        String userId2 = "USR-RESTART-02";
        String pmId = "PM-RESTART-01";
        String walletId1;
        String walletId2;
        String transferRef = "REQ-RESTART-TXN";

        // === PHASE 1: Initial Application Session ===
        try (DatabaseManager dm1 = new DatabaseManager(TEST_JDBC_URL)) {
            WalletService ws1 = new WalletService(dm1);

            // 1. Create Users
            User user1 = new User(userId1, "Elena Gilbert", "09175551234", "elena@otog.ph", UserRole.USER, UserStatus.ACTIVE);
            User user2 = new User(userId2, "Stefan Salvatore", "09175555678", "stefan@otog.ph", UserRole.USER, UserStatus.ACTIVE);
            ws1.getUserRepository().save(user1);
            ws1.getUserRepository().save(user2);

            // 2. Create Wallets
            Wallet w1 = ws1.createWallet(userId1, new BigDecimal("2000.00"), "PHP");
            Wallet w2 = ws1.createWallet(userId2, new BigDecimal("300.00"), "PHP");
            walletId1 = w1.getWalletId();
            walletId2 = w2.getWalletId();

            // 3. Create PaymentMethod
            PaymentMethod pm = new PaymentMethod(pmId, userId1, PaymentMethodType.E_WALLET,
                    "GCash", "Elena Gilbert", "09175551234");
            pm.setDefault(true);
            ws1.getPaymentMethodRepository().save(pm);

            // 4. Perform Transfer
            ws1.transfer(transferRef, walletId1, walletId2,
                    new BigDecimal("500.00"), new BigDecimal("10.00"), "Groceries share");

            // Verify balances before shutdown
            assertEquals(new BigDecimal("1490.00"), ws1.getBalance(walletId1));
            assertEquals(new BigDecimal("800.00"), ws1.getBalance(walletId2));
        }
        // === SIMULATED SHUTDOWN: dm1 is closed, JVM / session ended ===

        // Verify the database file physically exists on disk
        File dbFile = new File(TEST_DB_PATH);
        assertTrue(dbFile.exists() && dbFile.length() > 0, "Database file must persist on disk");

        // === PHASE 2: Application Restart with Fresh Instances ===
        try (DatabaseManager dm2 = new DatabaseManager(TEST_JDBC_URL)) {
            WalletService ws2 = new WalletService(dm2);

            // 1. Verify Users persisted
            Optional<User> foundUser1 = ws2.getUserRepository().findById(userId1);
            assertTrue(foundUser1.isPresent());
            assertEquals("Elena Gilbert", foundUser1.get().getName());
            assertEquals("09175551234", foundUser1.get().getMobileNumber());

            Optional<User> foundUser2 = ws2.getUserRepository().findById(userId2);
            assertTrue(foundUser2.isPresent());
            assertEquals("Stefan Salvatore", foundUser2.get().getName());

            // 2. Verify Wallets and Balances persisted accurately
            Wallet loadedW1 = ws2.getWallet(walletId1);
            assertEquals(new BigDecimal("1490.00"), loadedW1.getBalance(),
                    "Wallet 1 balance must survive restart");
            assertEquals("PHP", loadedW1.getCurrency());
            assertEquals(WalletStatus.ACTIVE, loadedW1.getStatus());

            Wallet loadedW2 = ws2.getWallet(walletId2);
            assertEquals(new BigDecimal("800.00"), loadedW2.getBalance(),
                    "Wallet 2 balance must survive restart");

            // 3. Verify PaymentMethod persisted
            Optional<PaymentMethod> loadedPm = ws2.getPaymentMethodRepository().findById(pmId);
            assertTrue(loadedPm.isPresent());
            assertEquals("GCash", loadedPm.get().getProviderName());
            assertTrue(loadedPm.get().isDefault());

            // 4. Verify Transactions persisted
            Optional<Transaction> loadedTxn = ws2.getTransactionRepository().findByReferenceNumber(transferRef);
            assertTrue(loadedTxn.isPresent());
            assertEquals(TransactionStatus.COMPLETED, loadedTxn.get().getStatus());
            assertEquals(new BigDecimal("500.00"), loadedTxn.get().getAmount());
            assertEquals(new BigDecimal("10.00"), loadedTxn.get().getFee());
            assertEquals(new BigDecimal("510.00"), loadedTxn.get().getTotalAmount());

            // 5. Verify Ledger entries persisted and audit matches
            List<LedgerEntry> w1Ledgers = ws2.getLedgerRepository().findByWalletId(walletId1);
            assertEquals(2, w1Ledgers.size()); // 1 initial deposit + 1 debit
            BigDecimal w1Calculated = ws2.getLedgerRepository().calculateBalanceFromLedger(walletId1);
            assertEquals(new BigDecimal("1490.00"), w1Calculated);

            List<LedgerEntry> w2Ledgers = ws2.getLedgerRepository().findByWalletId(walletId2);
            assertEquals(2, w2Ledgers.size()); // 1 initial deposit + 1 credit
            BigDecimal w2Calculated = ws2.getLedgerRepository().calculateBalanceFromLedger(walletId2);
            assertEquals(new BigDecimal("800.00"), w2Calculated);

            // 6. Verify subsequent operations work seamlessly on restored state
            String subsequentRef = "REQ-POST-RESTART-01";
            Transaction newTxn = ws2.transfer(subsequentRef, walletId1, walletId2,
                    new BigDecimal("100.00"), BigDecimal.ZERO, "After restart");
            assertNotNull(newTxn);
            assertEquals(new BigDecimal("1390.00"), ws2.getBalance(walletId1));
            assertEquals(new BigDecimal("900.00"), ws2.getBalance(walletId2));
        }
    }
}
