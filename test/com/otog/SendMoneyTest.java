package com.otog;

import com.otog.database.DatabaseManager;
import com.otog.exception.*;
import com.otog.model.*;
import com.otog.repository.*;
import com.otog.service.*;
import com.otog.util.IdGenerator;
import com.otog.util.MoneyUtil;
import com.otog.util.PasswordUtil;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CAP-06 – Send Money: end-to-end acceptance tests.
 *
 * Acceptance Criteria verified:
 * AC-1 Validate recipient, amount, balance (incl. fee), and configured limits.
 * AC-2 Show recipient and total before PIN confirmation (preview).
 * AC-3 Construct SendMoneyTransaction via TransactionFactory/TransactionBuilder
 * and debit sender / credit recipient atomically; repeated confirmation
 * produces one transfer (idempotency).
 * AC-4 Verify success, invalid recipient, insufficient balance, wrong PIN,
 * and failed persistence (rollback).
 */
public class SendMoneyTest {

        private DatabaseManager databaseManager;
        private AuthServiceImpl authService;
        private WalletService walletService;
        private SendMoneyService sendMoneyService;

        /* Test fixtures */
        private User sender;
        private User recipient;
        private Wallet senderWallet;
        private Wallet recipientWallet;

        private static final String SENDER_PIN = "1234";
        private static final String SENDER_PASS = "password123";
        private static final String RECIPIENT_PASS = "password456";

        /* ================================================================ */
        /* Setup / Teardown */
        /* ================================================================ */

        @BeforeEach
        void setUp() {
                SessionManager.getInstance().reset();

                databaseManager = new DatabaseManager("jdbc:sqlite::memory:");
                authService = new AuthServiceImpl(databaseManager);
                walletService = authService.getWalletService();
                sendMoneyService = new SendMoneyService(walletService,
                                authService.getUserRepository(), authService);

                /*
                 * Register sender and recipient via AuthService (creates user + cred + wallet)
                 */
                sender = authService.registerUser("Carlos Sender", "09171111111", "carlos@mail.com", SENDER_PASS);
                recipient = authService.registerUser("Diana Recipient", "09172222222", "diana@mail.com",
                                RECIPIENT_PASS);

                /* Fund the sender's wallet */
                senderWallet = walletService.getWalletByUserId(sender.getUserId());
                walletService.cashIn(IdGenerator.nextReferenceNumber(),
                                senderWallet.getWalletId(), null,
                                new BigDecimal("5000.00"), "Initial funding");
                senderWallet = walletService.getWallet(senderWallet.getWalletId());

                recipientWallet = walletService.getWalletByUserId(recipient.getUserId());

                /* Log in as sender and set a transaction PIN */
                authService.authenticatePassword("09171111111", SENDER_PASS);
                SessionManager.getInstance().login(sender, senderWallet);
                authService.setPin(sender.getUserId(), SENDER_PASS, SENDER_PIN);
        }

        @AfterEach
        void tearDown() {
                SessionManager.getInstance().reset();
                databaseManager.close();
        }

        /* ================================================================ */
        /* AC-1 Validate recipient, amount, balance, fees, limits */
        /* ================================================================ */

        @Test
        @DisplayName("AC-1: Valid recipient resolves to the correct user")
        void resolveRecipient_validMobile_returnsUser() {
                User found = sendMoneyService.resolveRecipient("09172222222");
                assertEquals(recipient.getUserId(), found.getUserId());
                assertEquals("Diana Recipient", found.getName());
        }

        @Test
        @DisplayName("AC-1: Invalid / unknown recipient throws EntityNotFoundException")
        void resolveRecipient_unknown_throws() {
                assertThrows(EntityNotFoundException.class,
                                () -> sendMoneyService.resolveRecipient("09999999999"));
        }

        @Test
        @DisplayName("AC-1: Null / empty recipient identifier throws IllegalArgumentException")
        void resolveRecipient_nullOrEmpty_throws() {
                assertThrows(IllegalArgumentException.class,
                                () -> sendMoneyService.resolveRecipient(null));
                assertThrows(IllegalArgumentException.class,
                                () -> sendMoneyService.resolveRecipient("   "));
        }

        @Test
        @DisplayName("AC-1: Amount below MIN_SEND_AMOUNT is rejected")
        void preview_belowMinAmount_throws() {
                assertThrows(InvalidAmountException.class,
                                () -> sendMoneyService.preview(sender.getUserId(), "09172222222",
                                                new BigDecimal("0.50"), null, null));
        }

        @Test
        @DisplayName("AC-1: Amount above MAX_SEND_AMOUNT is rejected")
        void preview_aboveMaxAmount_throws() {
                assertThrows(InvalidAmountException.class,
                                () -> sendMoneyService.preview(sender.getUserId(), "09172222222",
                                                new BigDecimal("60000.00"), null, null));
        }

        @Test
        @DisplayName("AC-1: Zero or negative amount is rejected")
        void preview_zeroOrNegativeAmount_throws() {
                assertThrows(InvalidAmountException.class,
                                () -> sendMoneyService.preview(sender.getUserId(), "09172222222",
                                                BigDecimal.ZERO, null, null));
                assertThrows(InvalidAmountException.class,
                                () -> sendMoneyService.preview(sender.getUserId(), "09172222222",
                                                new BigDecimal("-100.00"), null, null));
        }

        @Test
        @DisplayName("AC-1: Insufficient balance (amount + fee) is rejected at preview")
        void preview_insufficientBalance_throws() {
                // Sender has 5000.00 — try to send 4990 + fee 20 = 5010 total
                InsufficientFundsException ex = assertThrows(InsufficientFundsException.class,
                                () -> sendMoneyService.preview(sender.getUserId(), "09172222222",
                                                new BigDecimal("4990.00"), new BigDecimal("20.00"), null));
                assertNotNull(ex.getAvailableBalance());
                assertNotNull(ex.getRequiredAmount());
        }

        @Test
        @DisplayName("AC-1: Self-transfer is rejected")
        void preview_selfTransfer_throws() {
                assertThrows(WalletException.class,
                                () -> sendMoneyService.preview(sender.getUserId(), "09171111111",
                                                new BigDecimal("100.00"), null, null));
        }

        @Test
        @DisplayName("AC-1: Amount with > 2 decimal places is rejected")
        void preview_excessiveDecimalPrecision_throws() {
                assertThrows(InvalidAmountException.class,
                                () -> sendMoneyService.preview(sender.getUserId(), "09172222222",
                                                new BigDecimal("100.999"), null, null));
        }

        /* ================================================================ */
        /* AC-2 Show recipient and total before PIN confirmation */
        /* ================================================================ */

        @Test
        @DisplayName("AC-2: Preview shows recipient name, number, amount, fee, and total")
        void preview_displaysCorrectConfirmation() {
                SendMoneyService.SendMoneyPreview preview = sendMoneyService.preview(
                                sender.getUserId(), "09172222222",
                                new BigDecimal("500.00"), new BigDecimal("10.00"), "Birthday gift");

                assertEquals(senderWallet.getWalletId(), preview.getSenderWalletId());
                assertEquals(recipientWallet.getWalletId(), preview.getRecipientWalletId());
                assertEquals("Diana Recipient", preview.getRecipientName());
                assertEquals("09172222222", preview.getRecipientNumber());
                assertEquals(new BigDecimal("500.00"), preview.getAmount());
                assertEquals(new BigDecimal("10.00"), preview.getFee());
                assertEquals(new BigDecimal("510.00"), preview.getTotalDebit());
                assertNotNull(preview.getReferenceNumber());
                assertTrue(preview.getReferenceNumber().startsWith("REQ-"));
        }

        @Test
        @DisplayName("AC-2: Preview confirmation text includes all key details")
        void preview_confirmationText_containsAllDetails() {
                SendMoneyService.SendMoneyPreview preview = sendMoneyService.preview(
                                sender.getUserId(), "09172222222",
                                new BigDecimal("250.00"), null, null);

                String text = preview.toConfirmationText();
                assertTrue(text.contains("250.00"), "Should contain amount");
                assertTrue(text.contains("Diana Recipient"), "Should contain name");
                assertTrue(text.contains("09172222222"), "Should contain number");
                assertTrue(text.contains("REQ-"), "Should contain reference");
        }

        @Test
        @DisplayName("AC-2: Preview with zero fee shows PHP 0.00 fee")
        void preview_zeroFee_showsCorrectTotal() {
                SendMoneyService.SendMoneyPreview preview = sendMoneyService.preview(
                                sender.getUserId(), "09172222222",
                                new BigDecimal("1000.00"), null, null);

                assertEquals(MoneyUtil.ZERO, preview.getFee());
                assertEquals(new BigDecimal("1000.00"), preview.getTotalDebit());
        }

        /* ================================================================ */
        /* AC-3 Construct via Factory/Builder, atomic debit/credit, */
        /* idempotent (repeated confirmation → one transfer) */
        /* ================================================================ */

        @Test
        @DisplayName("AC-3: TransactionFactory creates a valid PENDING SendMoneyTransaction")
        void factory_createSendMoney_correctFields() {
                SendMoneyTransaction txn = TransactionFactory.createSendMoney(
                                "REQ-FACTORY-01",
                                "WLT-SRC", "WLT-TGT",
                                new BigDecimal("300.00"), new BigDecimal("5.00"),
                                "09172222222", "Test note");

                assertNotNull(txn.getTransactionId());
                assertTrue(txn.getTransactionId().startsWith("TXN-"));
                assertEquals("REQ-FACTORY-01", txn.getReferenceNumber());
                assertEquals("WLT-SRC", txn.getSourceWalletId());
                assertEquals("WLT-TGT", txn.getTargetWalletId());
                assertEquals(new BigDecimal("300.00"), txn.getAmount());
                assertEquals(new BigDecimal("5.00"), txn.getFee());
                assertEquals(new BigDecimal("305.00"), txn.getTotalAmount());
                assertEquals(TransactionType.SEND_MONEY, txn.getType());
                assertEquals(TransactionStatus.PENDING, txn.getStatus());
                assertEquals("09172222222", txn.getRecipientNumber());
        }

        @Test
        @DisplayName("AC-3: TransactionBuilder builds identical transaction to Factory")
        void builder_buildsSendMoneyTransaction() {
                SendMoneyTransaction txn = new TransactionBuilder()
                                .referenceNumber("REQ-BUILDER-01")
                                .from("WLT-A")
                                .to("WLT-B")
                                .amount(new BigDecimal("750.00"))
                                .fee(new BigDecimal("15.00"))
                                .recipientNumber("09172222222")
                                .note("Builder test")
                                .build();

                assertNotNull(txn.getTransactionId());
                assertEquals("REQ-BUILDER-01", txn.getReferenceNumber());
                assertEquals("WLT-A", txn.getSourceWalletId());
                assertEquals("WLT-B", txn.getTargetWalletId());
                assertEquals(new BigDecimal("750.00"), txn.getAmount());
                assertEquals(new BigDecimal("15.00"), txn.getFee());
                assertEquals(new BigDecimal("765.00"), txn.getTotalAmount());
                assertEquals(TransactionType.SEND_MONEY, txn.getType());
                assertEquals(TransactionStatus.PENDING, txn.getStatus());
        }

        @Test
        @DisplayName("AC-3: Builder rejects missing required fields")
        void builder_missingFields_throws() {
                assertThrows(IllegalStateException.class,
                                () -> new TransactionBuilder().build());

                assertThrows(IllegalStateException.class,
                                () -> new TransactionBuilder()
                                                .referenceNumber("REF-1").from("WLT-A")
                                                .amount(new BigDecimal("10.00"))
                                                .build()); // missing .to()
        }

        @Test
        @DisplayName("AC-3: Successful send debits sender and credits recipient atomically")
        void sendMoney_success_atomicDebitCredit() {
                BigDecimal senderBefore = walletService.getBalance(senderWallet.getWalletId());
                BigDecimal recipientBefore = walletService.getBalance(recipientWallet.getWalletId());

                // Re-verify PIN for the session
                authService.verifyPin(sender.getUserId(), SENDER_PIN);

                Transaction txn = sendMoneyService.sendMoney(
                                sender.getUserId(), "09172222222",
                                new BigDecimal("500.00"), new BigDecimal("10.00"),
                                "Birthday gift", SENDER_PIN);

                assertEquals(TransactionStatus.COMPLETED, txn.getStatus());

                BigDecimal senderAfter = walletService.getBalance(senderWallet.getWalletId());
                BigDecimal recipientAfter = walletService.getBalance(recipientWallet.getWalletId());

                // Sender: 5000 − (500 + 10) = 4490
                assertEquals(new BigDecimal("4490.00"), senderAfter);
                // Recipient: 0 + 500 = 500 (fee stays with sender)
                assertEquals(new BigDecimal("500.00"), recipientAfter);

                // Verify conservation of value (total system funds accounted for)
                BigDecimal feeLost = new BigDecimal("10.00");
                assertEquals(
                                senderBefore.add(recipientBefore),
                                senderAfter.add(recipientAfter).add(feeLost),
                                "Total value must be conserved (sender + recipient + fee)");
        }

        @Test
        @DisplayName("AC-3: Ledger entries are created for both sender and recipient")
        void sendMoney_success_ledgerEntries() {
                authService.verifyPin(sender.getUserId(), SENDER_PIN);

                sendMoneyService.sendMoney(
                                sender.getUserId(), "09172222222",
                                new BigDecimal("200.00"), null,
                                "Ledger test", SENDER_PIN);

                // Sender ledger: 1 initial cash-in + 1 debit for send
                List<LedgerEntry> senderLedger = walletService.getLedgerRepository()
                                .findByWalletId(senderWallet.getWalletId());
                assertEquals(2, senderLedger.size());
                LedgerEntry debit = senderLedger.get(1);
                assertEquals(LedgerEntryType.DEBIT, debit.getEntryType());
                assertEquals(new BigDecimal("200.00"), debit.getAmount());

                // Recipient ledger: 1 credit for send (no initial funding)
                List<LedgerEntry> recipientLedger = walletService.getLedgerRepository()
                                .findByWalletId(recipientWallet.getWalletId());
                assertEquals(1, recipientLedger.size());
                LedgerEntry credit = recipientLedger.get(0);
                assertEquals(LedgerEntryType.CREDIT, credit.getEntryType());
                assertEquals(new BigDecimal("200.00"), credit.getAmount());

                // Ledger-derived balance matches actual wallet balance
                BigDecimal senderLedgerBal = walletService.getLedgerRepository()
                                .calculateBalanceFromLedger(senderWallet.getWalletId());
                assertEquals(walletService.getBalance(senderWallet.getWalletId()), senderLedgerBal);
        }

        @Test
        @DisplayName("AC-3: Repeated confirmation with same reference produces exactly one transfer (idempotency)")
        void sendMoney_duplicateReference_rejectedNoDoubleSpend() {
                authService.verifyPin(sender.getUserId(), SENDER_PIN);

                // Step 1: Preview to get a reference number
                SendMoneyService.SendMoneyPreview preview = sendMoneyService.preview(
                                sender.getUserId(), "09172222222",
                                new BigDecimal("300.00"), null, null);
                String ref = preview.getReferenceNumber();

                // Step 2: First confirmation succeeds
                Transaction first = sendMoneyService.confirmAndSend(
                                ref, sender.getUserId(), SENDER_PIN,
                                "09172222222", new BigDecimal("300.00"), null, null);
                assertNotNull(first);
                assertEquals(TransactionStatus.COMPLETED, first.getStatus());

                BigDecimal senderBalAfterFirst = walletService.getBalance(senderWallet.getWalletId());
                BigDecimal recipientBalAfterFirst = walletService.getBalance(recipientWallet.getWalletId());

                assertEquals(new BigDecimal("4700.00"), senderBalAfterFirst);
                assertEquals(new BigDecimal("300.00"), recipientBalAfterFirst);

                // Step 3: Second confirmation with SAME reference is rejected
                assertThrows(DuplicateTransactionException.class,
                                () -> sendMoneyService.confirmAndSend(
                                                ref, sender.getUserId(), SENDER_PIN,
                                                "09172222222", new BigDecimal("300.00"), null, null));

                // Step 4: Balances remain unchanged — no double-send
                assertEquals(senderBalAfterFirst,
                                walletService.getBalance(senderWallet.getWalletId()));
                assertEquals(recipientBalAfterFirst,
                                walletService.getBalance(recipientWallet.getWalletId()));
        }

        /* ================================================================ */
        /* AC-4 Verify success, invalid recipient, insufficient balance, */
        /* wrong PIN, and failed persistence (rollback) */
        /* ================================================================ */

        @Test
        @DisplayName("AC-4: Successful send returns COMPLETED transaction with receipt")
        void sendMoney_success_returnsCompletedWithReceipt() {
                authService.verifyPin(sender.getUserId(), SENDER_PIN);

                Transaction txn = sendMoneyService.sendMoney(
                                sender.getUserId(), "09172222222",
                                new BigDecimal("100.00"), null,
                                "Success test", SENDER_PIN);

                assertEquals(TransactionStatus.COMPLETED, txn.getStatus());
                assertNotNull(txn.getTransactionId());
                assertNotNull(txn.getReferenceNumber());
                assertNotNull(txn.getCompletedAt());

                String receipt = txn.getReceipt();
                assertTrue(receipt.contains("OTOG E-WALLET RECEIPT"));
                assertTrue(receipt.contains("100.00"));
        }

        @Test
        @DisplayName("AC-4: Invalid recipient identifier rejects the send")
        void sendMoney_invalidRecipient_throws() {
                assertThrows(EntityNotFoundException.class,
                                () -> sendMoneyService.sendMoney(
                                                sender.getUserId(), "09999999999",
                                                new BigDecimal("100.00"), null, null, SENDER_PIN));
        }

        @Test
        @DisplayName("AC-4: Insufficient balance rejects the send (at preview)")
        void sendMoney_insufficientBalance_throws() {
                assertThrows(InsufficientFundsException.class,
                                () -> sendMoneyService.sendMoney(
                                                sender.getUserId(), "09172222222",
                                                new BigDecimal("99999.00"), null, null, SENDER_PIN));
        }

        @Test
        @DisplayName("AC-4: Wrong PIN blocks the transfer and leaves balances untouched")
        void sendMoney_wrongPin_throws() {
                BigDecimal senderBefore = walletService.getBalance(senderWallet.getWalletId());
                BigDecimal recipientBefore = walletService.getBalance(recipientWallet.getWalletId());

                assertThrows(WalletException.class,
                                () -> sendMoneyService.sendMoney(
                                                sender.getUserId(), "09172222222",
                                                new BigDecimal("100.00"), null, null, "9999"));

                // Balances must be completely untouched
                assertEquals(senderBefore, walletService.getBalance(senderWallet.getWalletId()));
                assertEquals(recipientBefore, walletService.getBalance(recipientWallet.getWalletId()));
        }

        @Test
        @DisplayName("AC-4: Null PIN throws WalletException")
        void sendMoney_nullPin_throws() {
                assertThrows(WalletException.class,
                                () -> sendMoneyService.sendMoney(
                                                sender.getUserId(), "09172222222",
                                                new BigDecimal("100.00"), null, null, null));
        }

        @Test
        @DisplayName("AC-4: Failed persistence rolls back — no partial debit or orphan records")
        void sendMoney_failedPersistence_rollback() {
                BigDecimal senderBefore = walletService.getBalance(senderWallet.getWalletId());
                BigDecimal recipientBefore = walletService.getBalance(recipientWallet.getWalletId());
                int ledgerCountBefore = walletService.getLedgerRepository().findAll().size();
                int txnCountBefore = walletService.getTransactionRepository().findAll().size();

                // Simulate a crash mid-transaction by going through the raw
                // executeInTransaction
                assertThrows(OtogException.class, () -> walletService.executeInTransaction(conn -> {
                        // Partially debit sender
                        walletService.getWalletRepository().updateBalance(
                                        senderWallet.getWalletId(), new BigDecimal("2000.00"), conn);

                        // Write a ledger entry
                        LedgerEntry partial = new LedgerEntry(
                                        IdGenerator.nextLedgerEntryId(),
                                        "CRASH-TXN",
                                        senderWallet.getWalletId(),
                                        LedgerEntryType.DEBIT,
                                        new BigDecimal("3000.00"),
                                        senderBefore,
                                        new BigDecimal("2000.00"),
                                        "PHP", "Crash before commit");
                        walletService.getLedgerRepository().save(partial, conn);

                        throw new RuntimeException("Simulated persistence failure!");
                }));

                // Everything must be rolled back
                assertEquals(senderBefore, walletService.getBalance(senderWallet.getWalletId()));
                assertEquals(recipientBefore, walletService.getBalance(recipientWallet.getWalletId()));
                assertEquals(ledgerCountBefore, walletService.getLedgerRepository().findAll().size());
                assertEquals(txnCountBefore, walletService.getTransactionRepository().findAll().size());
        }

        /* ================================================================ */
        /* Edge cases */
        /* ================================================================ */

        @Test
        @DisplayName("Edge: Send exact balance (no fee) succeeds → sender at 0")
        void sendMoney_exactBalance_succeeds() {
                authService.verifyPin(sender.getUserId(), SENDER_PIN);

                Transaction txn = sendMoneyService.sendMoney(
                                sender.getUserId(), "09172222222",
                                new BigDecimal("5000.00"), null,
                                "Drain wallet", SENDER_PIN);

                assertEquals(TransactionStatus.COMPLETED, txn.getStatus());
                assertEquals(new BigDecimal("0.00"),
                                walletService.getBalance(senderWallet.getWalletId()));
                assertEquals(new BigDecimal("5000.00"),
                                walletService.getBalance(recipientWallet.getWalletId()));
        }

        @Test
        @DisplayName("Edge: Send exact balance minus fee succeeds")
        void sendMoney_exactBalanceMinusFee_succeeds() {
                authService.verifyPin(sender.getUserId(), SENDER_PIN);

                // 4990 + 10 fee = 5000 (exactly sender's balance)
                Transaction txn = sendMoneyService.sendMoney(
                                sender.getUserId(), "09172222222",
                                new BigDecimal("4990.00"), new BigDecimal("10.00"),
                                "Exact drain with fee", SENDER_PIN);

                assertEquals(TransactionStatus.COMPLETED, txn.getStatus());
                assertEquals(new BigDecimal("0.00"),
                                walletService.getBalance(senderWallet.getWalletId()));
                assertEquals(new BigDecimal("4990.00"),
                                walletService.getBalance(recipientWallet.getWalletId()));
        }

        @Test
        @DisplayName("Edge: Multiple sequential sends debit correctly")
        void sendMoney_multipleSends_cumulativeDebit() {
                authService.verifyPin(sender.getUserId(), SENDER_PIN);

                sendMoneyService.sendMoney(sender.getUserId(), "09172222222",
                                new BigDecimal("1000.00"), null, "First", SENDER_PIN);
                sendMoneyService.sendMoney(sender.getUserId(), "09172222222",
                                new BigDecimal("1500.00"), null, "Second", SENDER_PIN);
                sendMoneyService.sendMoney(sender.getUserId(), "09172222222",
                                new BigDecimal("500.00"), new BigDecimal("25.00"), "Third", SENDER_PIN);

                // 5000 − 1000 − 1500 − (500+25) = 1975
                assertEquals(new BigDecimal("1975.00"),
                                walletService.getBalance(senderWallet.getWalletId()));
                // 0 + 1000 + 1500 + 500 = 3000
                assertEquals(new BigDecimal("3000.00"),
                                walletService.getBalance(recipientWallet.getWalletId()));
        }
}
