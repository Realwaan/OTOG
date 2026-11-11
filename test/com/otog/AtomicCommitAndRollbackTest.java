package com.otog;

import com.otog.database.DatabaseManager;
import com.otog.exception.DuplicateTransactionException;
import com.otog.exception.InsufficientFundsException;
import com.otog.exception.OtogException;
import com.otog.model.*;
import com.otog.service.WalletService;
import com.otog.util.IdGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests AC 3 & AC 4: Commit balance changes and entries atomically;
 * reject insufficient funds and duplicate requests; verify rollback on failure.
 */
public class AtomicCommitAndRollbackTest {
    private DatabaseManager databaseManager;
    private WalletService walletService;
    private Wallet senderWallet;
    private Wallet recipientWallet;

    @BeforeEach
    void setUp() {
        databaseManager = new DatabaseManager("jdbc:sqlite::memory:");
        walletService = new WalletService(databaseManager);

        User u1 = new User(IdGenerator.nextUserId(), "Carlos", "09170000010");
        User u2 = new User(IdGenerator.nextUserId(), "Diana", "09170000020");
        walletService.getUserRepository().save(u1);
        walletService.getUserRepository().save(u2);

        senderWallet = walletService.createWallet(u1.getUserId(), new BigDecimal("1000.00"), "PHP");
        recipientWallet = walletService.createWallet(u2.getUserId(), new BigDecimal("200.00"), "PHP");
    }

    @AfterEach
    void tearDown() {
        databaseManager.close();
    }

    @Test
    void testAtomicTransferCommitAndLedgerIntegrity() {
        String ref = "REQ-ATOMIC-01";
        BigDecimal transferAmount = new BigDecimal("350.00");
        BigDecimal fee = new BigDecimal("15.00");

        Transaction txn = walletService.transfer(ref, senderWallet.getWalletId(), recipientWallet.getWalletId(),
                transferAmount, fee, "Rent contribution");

        assertEquals(TransactionStatus.COMPLETED, txn.getStatus());

        // Verify balances in database
        BigDecimal newSenderBalance = walletService.getBalance(senderWallet.getWalletId());
        BigDecimal newRecipientBalance = walletService.getBalance(recipientWallet.getWalletId());

        assertEquals(new BigDecimal("635.00"), newSenderBalance, "1000 - (350 + 15) = 635");
        assertEquals(new BigDecimal("550.00"), newRecipientBalance, "200 + 350 = 550");

        // Verify ledger entries for sender
        List<LedgerEntry> senderLedgers = walletService.getLedgerRepository().findByWalletId(senderWallet.getWalletId());
        // 1 initial deposit + 1 debit
        assertEquals(2, senderLedgers.size());
        LedgerEntry senderDebit = senderLedgers.get(1);
        assertEquals(LedgerEntryType.DEBIT, senderDebit.getEntryType());
        assertEquals(new BigDecimal("365.00"), senderDebit.getAmount());
        assertEquals(new BigDecimal("1000.00"), senderDebit.getBalanceBefore());
        assertEquals(new BigDecimal("635.00"), senderDebit.getBalanceAfter());
        assertEquals(txn.getTransactionId(), senderDebit.getTransactionId());

        // Verify ledger entries for recipient
        List<LedgerEntry> recipientLedgers = walletService.getLedgerRepository().findByWalletId(recipientWallet.getWalletId());
        // 1 initial deposit + 1 credit
        assertEquals(2, recipientLedgers.size());
        LedgerEntry recipientCredit = recipientLedgers.get(1);
        assertEquals(LedgerEntryType.CREDIT, recipientCredit.getEntryType());
        assertEquals(new BigDecimal("350.00"), recipientCredit.getAmount());
        assertEquals(new BigDecimal("200.00"), recipientCredit.getBalanceBefore());
        assertEquals(new BigDecimal("550.00"), recipientCredit.getBalanceAfter());
        assertEquals(txn.getTransactionId(), recipientCredit.getTransactionId());

        // Verify ledger net calculation equals actual wallet balance
        BigDecimal senderLedgerNet = walletService.getLedgerRepository().calculateBalanceFromLedger(senderWallet.getWalletId());
        BigDecimal recipientLedgerNet = walletService.getLedgerRepository().calculateBalanceFromLedger(recipientWallet.getWalletId());
        assertEquals(newSenderBalance, senderLedgerNet);
        assertEquals(newRecipientBalance, recipientLedgerNet);
    }

    @Test
    void testRejectInsufficientFundsLeavesStateUntouched() {
        String ref = "REQ-INSUFFICIENT-01";
        // Sender only has 1000.00, attempting to send 1200.00
        BigDecimal excessiveAmount = new BigDecimal("1200.00");

        InsufficientFundsException ex = assertThrows(InsufficientFundsException.class, () ->
                walletService.transfer(ref, senderWallet.getWalletId(), recipientWallet.getWalletId(),
                        excessiveAmount, BigDecimal.ZERO, "Overdraft attempt"));

        assertTrue(ex.getMessage().contains("Insufficient funds"));
        assertEquals(new BigDecimal("1000.00"), ex.getAvailableBalance());
        assertEquals(new BigDecimal("1200.00"), ex.getRequiredAmount());

        // Verify balances are completely unchanged
        assertEquals(new BigDecimal("1000.00"), walletService.getBalance(senderWallet.getWalletId()));
        assertEquals(new BigDecimal("200.00"), walletService.getBalance(recipientWallet.getWalletId()));

        // Verify no new transaction was persisted for this reference
        assertFalse(walletService.getTransactionRepository().existsByReferenceNumber(ref));

        // Verify ledger entry count is still only initial deposits (1 each)
        assertEquals(1, walletService.getLedgerRepository().findByWalletId(senderWallet.getWalletId()).size());
        assertEquals(1, walletService.getLedgerRepository().findByWalletId(recipientWallet.getWalletId()).size());
    }

    @Test
    void testRejectDuplicateRequests() {
        String ref = "REQ-IDEMPOTENT-01";
        BigDecimal amount = new BigDecimal("100.00");

        // First transfer succeeds
        Transaction firstTxn = walletService.transfer(ref, senderWallet.getWalletId(), recipientWallet.getWalletId(),
                amount, BigDecimal.ZERO, "First transfer");
        assertNotNull(firstTxn);

        assertEquals(new BigDecimal("900.00"), walletService.getBalance(senderWallet.getWalletId()));
        assertEquals(new BigDecimal("300.00"), walletService.getBalance(recipientWallet.getWalletId()));

        // Second transfer with exact same reference must be rejected
        DuplicateTransactionException dupEx = assertThrows(DuplicateTransactionException.class, () ->
                walletService.transfer(ref, senderWallet.getWalletId(), recipientWallet.getWalletId(),
                        amount, BigDecimal.ZERO, "Second duplicate transfer"));

        assertTrue(dupEx.getMessage().contains("Duplicate transaction request reference"));

        // Balances must remain unchanged from after the first transfer
        assertEquals(new BigDecimal("900.00"), walletService.getBalance(senderWallet.getWalletId()));
        assertEquals(new BigDecimal("300.00"), walletService.getBalance(recipientWallet.getWalletId()));

        // Ledger count remains exactly 1 initial + 1 debit for sender
        assertEquals(2, walletService.getLedgerRepository().findByWalletId(senderWallet.getWalletId()).size());
    }

    @Test
    void testRollbackOnFailure() {
        BigDecimal initialSenderBalance = walletService.getBalance(senderWallet.getWalletId());
        BigDecimal initialRecipientBalance = walletService.getBalance(recipientWallet.getWalletId());
        int initialLedgerCount = walletService.getLedgerRepository().findAll().size();
        int initialTxnCount = walletService.getTransactionRepository().findAll().size();

        // Attempt a transaction where an unhandled failure occurs midway
        assertThrows(OtogException.class, () ->
                walletService.executeInTransaction(conn -> {
                    // Step 1: Debit sender in database
                    walletService.getWalletRepository().updateBalance(
                            senderWallet.getWalletId(), new BigDecimal("500.00"), conn);

                    // Step 2: Insert a ledger entry
                    LedgerEntry partialEntry = new LedgerEntry(
                            IdGenerator.nextLedgerEntryId(),
                            "DUMMY_TXN",
                            senderWallet.getWalletId(),
                            LedgerEntryType.DEBIT,
                            new BigDecimal("500.00"),
                            initialSenderBalance,
                            new BigDecimal("500.00"),
                            "PHP",
                            "Midway entry"
                    );
                    walletService.getLedgerRepository().save(partialEntry, conn);

                    // Step 3: Simulate catastrophic crash or network timeout
                    throw new RuntimeException("Simulated catastrophic crash before commit!");
                }));

        // Verify atomic rollback: balances restored to exact pre-transaction values
        assertEquals(initialSenderBalance, walletService.getBalance(senderWallet.getWalletId()));
        assertEquals(initialRecipientBalance, walletService.getBalance(recipientWallet.getWalletId()));

        // Verify no orphan ledger entries or transactions were committed
        assertEquals(initialLedgerCount, walletService.getLedgerRepository().findAll().size());
        assertEquals(initialTxnCount, walletService.getTransactionRepository().findAll().size());
    }

    @Test
    void testCashInAndCashOutAtomicExecution() {
        // Cash In 500.00 to sender
        String cashInRef = "REQ-CASHIN-01";
        Transaction cashInTxn = walletService.cashIn(cashInRef, senderWallet.getWalletId(), null,
                new BigDecimal("500.00"), "Cash in via Bank");
        assertEquals(TransactionStatus.COMPLETED, cashInTxn.getStatus());
        assertEquals(new BigDecimal("1500.00"), walletService.getBalance(senderWallet.getWalletId()));

        // Cash Out 300.00 with 10.00 fee
        String cashOutRef = "REQ-CASHOUT-01";
        Transaction cashOutTxn = walletService.cashOut(cashOutRef, senderWallet.getWalletId(), null,
                new BigDecimal("300.00"), new BigDecimal("10.00"), "ATM withdrawal");
        assertEquals(TransactionStatus.COMPLETED, cashOutTxn.getStatus());
        assertEquals(new BigDecimal("1190.00"), walletService.getBalance(senderWallet.getWalletId()));

        // Insufficient funds on cash out
        assertThrows(InsufficientFundsException.class, () ->
                walletService.cashOut("REQ-CASHOUT-FAIL", senderWallet.getWalletId(), null,
                        new BigDecimal("2000.00"), BigDecimal.ZERO, "Overdraft Cashout"));
        assertEquals(new BigDecimal("1190.00"), walletService.getBalance(senderWallet.getWalletId()));
    }

    @Test
    void testSelfTransferAndFrozenWalletRejection() {
        // Self-transfer rejected
        assertThrows(com.otog.exception.WalletException.class, () ->
                walletService.transfer("REQ-SELF-01", senderWallet.getWalletId(), senderWallet.getWalletId(),
                        new BigDecimal("50.00"), BigDecimal.ZERO, "Self transfer"));

        // Non-existent source wallet rejected
        assertThrows(com.otog.exception.EntityNotFoundException.class, () ->
                walletService.transfer("REQ-NONEXIST-01", "WLT-NONEXISTENT", recipientWallet.getWalletId(),
                        new BigDecimal("50.00"), BigDecimal.ZERO, "Invalid source"));

        // Frozen wallet rejected
        senderWallet.setStatus(WalletStatus.FROZEN);
        walletService.getWalletRepository().update(senderWallet);

        assertThrows(com.otog.exception.WalletException.class, () ->
                walletService.transfer("REQ-FROZEN-01", senderWallet.getWalletId(), recipientWallet.getWalletId(),
                        new BigDecimal("50.00"), BigDecimal.ZERO, "From frozen wallet"));
    }
}
