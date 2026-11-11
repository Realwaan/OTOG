package com.otog;

import com.otog.database.DatabaseManager;
import com.otog.exception.InsufficientFundsException;
import com.otog.model.LedgerEntry;
import com.otog.model.LedgerEntryType;
import com.otog.model.User;
import com.otog.model.Wallet;
import com.otog.service.WalletService;
import com.otog.util.IdGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests AC 4: Verify competing attempts to spend the same funds (concurrency & race condition prevention).
 */
public class ConcurrencyDoubleSpendTest {
    private static final String TEST_DB_PATH = "data/test_concurrency.db";
    private static final String TEST_JDBC_URL = "jdbc:sqlite:" + TEST_DB_PATH;

    private DatabaseManager databaseManager;
    private WalletService walletService;

    @BeforeEach
    void setUp() {
        cleanDb();
        databaseManager = new DatabaseManager(TEST_JDBC_URL);
        walletService = new WalletService(databaseManager);
    }

    @AfterEach
    void tearDown() {
        if (databaseManager != null) {
            databaseManager.close();
        }
        cleanDb();
    }

    private void cleanDb() {
        File f = new File(TEST_DB_PATH);
        if (f.exists()) {
            f.delete();
        }
    }

    @Test
    void testCompetingAttemptsToSpendSameFunds() throws InterruptedException {
        // Setup sender with 100.00 PHP
        User u1 = new User(IdGenerator.nextUserId(), "Spender", "09170001111");
        User u2 = new User(IdGenerator.nextUserId(), "Recipient", "09170002222");
        walletService.getUserRepository().save(u1);
        walletService.getUserRepository().save(u2);

        Wallet spenderWallet = walletService.createWallet(u1.getUserId(), new BigDecimal("100.00"), "PHP");
        Wallet recipientWallet = walletService.createWallet(u2.getUserId(), new BigDecimal("0.00"), "PHP");

        int threadCount = 10;
        BigDecimal spendAmount = new BigDecimal("70.00"); // 70 > 100 / 2, so at most 1 can succeed!
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger insufficientFundsCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await(); // wait for simultaneous trigger
                    String ref = "REQ-RACE-" + index;
                    walletService.transfer(ref, spenderWallet.getWalletId(), recipientWallet.getWalletId(),
                            spendAmount, BigDecimal.ZERO, "Competing spend #" + index);
                    successCount.incrementAndGet();
                } catch (InsufficientFundsException e) {
                    insufficientFundsCount.incrementAndGet();
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                }
            });
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown(); // Unleash all threads simultaneously
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        // EXACTLY 1 thread must succeed
        assertEquals(1, successCount.get(), "Exactly one competing thread should succeed");
        // Remaining 9 threads must fail with InsufficientFundsException
        assertEquals(9, insufficientFundsCount.get(), "Remaining 9 threads must be rejected for insufficient funds");
        // No unexpected errors
        assertEquals(0, errorCount.get(), "No unexpected errors should occur");

        // Spender final balance must be exactly 30.00 PHP (100 - 70)
        BigDecimal finalSpenderBalance = walletService.getBalance(spenderWallet.getWalletId());
        assertEquals(new BigDecimal("30.00"), finalSpenderBalance);

        // Recipient final balance must be exactly 70.00 PHP (0 + 70)
        BigDecimal finalRecipientBalance = walletService.getBalance(recipientWallet.getWalletId());
        assertEquals(new BigDecimal("70.00"), finalRecipientBalance);

        // Ledger verification
        List<LedgerEntry> spenderLedger = walletService.getLedgerRepository().findByWalletId(spenderWallet.getWalletId());
        assertEquals(2, spenderLedger.size(), "Spender ledger must have 1 initial deposit + 1 debit");
        assertEquals(LedgerEntryType.DEBIT, spenderLedger.get(1).getEntryType());
        assertEquals(new BigDecimal("70.00"), spenderLedger.get(1).getAmount());

        List<LedgerEntry> recipientLedger = walletService.getLedgerRepository().findByWalletId(recipientWallet.getWalletId());
        assertEquals(1, recipientLedger.size(), "Recipient ledger must have 1 credit");
        assertEquals(LedgerEntryType.CREDIT, recipientLedger.get(0).getEntryType());
        assertEquals(new BigDecimal("70.00"), recipientLedger.get(0).getAmount());
    }

    @Test
    void testConcurrentBidirectionalTransfersPreserveFundConservation() throws InterruptedException {
        // Two wallets each seeded with 500.00 PHP (Total: 1000.00 PHP)
        User u1 = new User(IdGenerator.nextUserId(), "UserA", "09170003333");
        User u2 = new User(IdGenerator.nextUserId(), "UserB", "09170004444");
        walletService.getUserRepository().save(u1);
        walletService.getUserRepository().save(u2);

        Wallet walletA = walletService.createWallet(u1.getUserId(), new BigDecimal("500.00"), "PHP");
        Wallet walletB = walletService.createWallet(u2.getUserId(), new BigDecimal("500.00"), "PHP");

        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    String ref = "REQ-BIDI-" + index;
                    if (index % 2 == 0) {
                        // A -> B: 10.00 PHP
                        walletService.transfer(ref, walletA.getWalletId(), walletB.getWalletId(),
                                new BigDecimal("10.00"), BigDecimal.ZERO, "A to B");
                    } else {
                        // B -> A: 10.00 PHP
                        walletService.transfer(ref, walletB.getWalletId(), walletA.getWalletId(),
                                new BigDecimal("10.00"), BigDecimal.ZERO, "B to A");
                    }
                } catch (Exception ignored) {
                }
            });
        }

        startLatch.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));

        // Conservation of funds: sum of balances must remain exactly 1000.00 PHP
        BigDecimal balA = walletService.getBalance(walletA.getWalletId());
        BigDecimal balB = walletService.getBalance(walletB.getWalletId());
        assertEquals(new BigDecimal("1000.00"), balA.add(balB),
                "Total system funds across both wallets must be conserved");
        assertTrue(balA.compareTo(BigDecimal.ZERO) >= 0);
        assertTrue(balB.compareTo(BigDecimal.ZERO) >= 0);
    }
}
