package com.otog;

import com.otog.database.DatabaseManager;
import com.otog.exception.DuplicateTransactionException;
import com.otog.exception.InsufficientFundsException;
import com.otog.exception.OtogException;
import com.otog.model.CashInOutTransaction;
import com.otog.model.Notification;
import com.otog.model.NotificationType;
import com.otog.model.ReceiptBuilder;
import com.otog.model.SendMoneyTransaction;
import com.otog.model.Transaction;
import com.otog.model.TransactionFilter;
import com.otog.model.TransactionStatus;
import com.otog.model.TransactionType;
import com.otog.model.User;
import com.otog.model.Wallet;
import com.otog.repository.CredentialRepository;
import com.otog.repository.NotificationRepository;
import com.otog.service.AuthServiceImpl;
import com.otog.service.BankResponseStatus;
import com.otog.service.NotificationServiceImpl;
import com.otog.service.PaymentProviderFactory;
import com.otog.service.ProtectedWalletOperationsProxy;
import com.otog.service.SessionManager;
import com.otog.service.WalletOperations;
import com.otog.service.WalletService;
import com.otog.ui.Main;
import com.otog.ui.NotificationInboxDialog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CAP-08 verification suite covering:
 * - Owned transaction history with date, type, and status filters
 * - Masked receipt formatting via ReceiptBuilder and Prototype repeat-draft creation
 * - Persistent read/unread notifications emitted strictly after commit
 * - Fault-isolated FIFO notification retries that never repeat payments
 * - Sender/recipient notifications, strict account isolation, and Swing UI integration
 */
public class HistoryReceiptsNotificationsTest {

    private DatabaseManager databaseManager;
    private AuthServiceImpl authService;
    private WalletService walletService;
    private Main activeDashboard;

    private User alice;
    private User bob;
    private User charlie;
    private Wallet aliceWallet;
    private Wallet bobWallet;
    private Wallet charlieWallet;

    @BeforeEach
    void setUp() {
        SessionManager.getInstance().reset();
        databaseManager = new DatabaseManager("jdbc:sqlite::memory:");
        walletService = new WalletService(databaseManager);
        authService = new AuthServiceImpl(
                databaseManager,
                walletService.getUserRepository(),
                new CredentialRepository(databaseManager),
                walletService
        );

        alice = authService.registerUser("Alice Reyes", "09171111111", "alice@otog.ph", "Secret123");
        bob = authService.registerUser("Bob Santos", "09172222222", "bob@otog.ph", "Secret123");
        charlie = authService.registerUser("Charlie Cruz", "09173333333", "charlie@otog.ph", "Secret123");

        aliceWallet = walletService.getWalletByUserId(alice.getUserId());
        bobWallet = walletService.getWalletByUserId(bob.getUserId());
        charlieWallet = walletService.getWalletByUserId(charlie.getUserId());
    }

    @AfterEach
    void tearDown() {
        if (activeDashboard != null) {
            activeDashboard.dispose();
            activeDashboard = null;
        }
        SessionManager.getInstance().reset();
        databaseManager.close();
    }

    @Test
    @DisplayName("AC-1: Owned transaction history filters by date, type, status, and isolates other accounts")
    void testOwnedTransactionHistoryFilteringAndIsolation() throws Exception {
        // Alice: COMPLETED cash-in on Nov 10
        Transaction cinOk = walletService.cashIn("REQ-HIST-CIN-OK", aliceWallet.getWalletId(), null,
                new BigDecimal("1000.00"), "Cash In via BDO");
        updateTransactionCreatedAt(cinOk.getTransactionId(), LocalDateTime.of(2026, 11, 10, 9, 30));

        // Alice: FAILED cash-in on Nov 12
        walletService.setPaymentProviderFactory(PaymentProviderFactory.failureFactory());
        Transaction cinFail = walletService.cashIn("REQ-HIST-CIN-FAIL", aliceWallet.getWalletId(), null,
                new BigDecimal("250.00"), "Cash In via BPI");
        updateTransactionCreatedAt(cinFail.getTransactionId(), LocalDateTime.of(2026, 11, 12, 14, 0));

        // Alice: PENDING cash-in on Nov 15
        walletService.setPaymentProviderFactory(PaymentProviderFactory.timeoutFactory());
        Transaction cinPending = walletService.cashIn("REQ-HIST-CIN-PEND", aliceWallet.getWalletId(), null,
                new BigDecimal("300.00"), "Cash In via GCash");
        updateTransactionCreatedAt(cinPending.getTransactionId(), LocalDateTime.of(2026, 11, 15, 16, 45));

        // Reset provider to success; Alice sends to Bob on Nov 18
        walletService.setPaymentProviderFactory(PaymentProviderFactory.successFactory());
        Transaction sendTxn = walletService.transfer("REQ-HIST-SEND", aliceWallet.getWalletId(), bobWallet.getWalletId(),
                new BigDecimal("150.00"), new BigDecimal("5.00"), "Dinner split");
        updateTransactionCreatedAt(sendTxn.getTransactionId(), LocalDateTime.of(2026, 11, 18, 19, 15));

        // Alice: COMPLETED cash-out on Nov 19
        Transaction coutOk = walletService.cashOut("REQ-HIST-COUT", aliceWallet.getWalletId(), null,
                new BigDecimal("100.00"), BigDecimal.ZERO, "Cash Out to BDO");
        updateTransactionCreatedAt(coutOk.getTransactionId(), LocalDateTime.of(2026, 11, 19, 10, 0));

        // Charlie performs private cash-in that must NEVER leak into Alice's history
        walletService.cashIn("REQ-CHARLIE-PRIV", charlieWallet.getWalletId(), null,
                new BigDecimal("999.00"), "Charlie private top-up");

        // 1. All Alice owned transactions = 5 (completed, failed, pending all traceable; Charlie excluded)
        List<Transaction> allAlice = walletService.getTransactionHistory(aliceWallet.getWalletId(), TransactionFilter.empty());
        assertEquals(5, allAlice.size());
        assertTrue(allAlice.stream().noneMatch(t -> "REQ-CHARLIE-PRIV".equals(t.getReferenceNumber())));

        // 2. Filter by status
        List<Transaction> failedOnly = walletService.getTransactionHistory(
                aliceWallet.getWalletId(), TransactionFilter.builder().status(TransactionStatus.FAILED).build());
        assertEquals(1, failedOnly.size());
        assertEquals("REQ-HIST-CIN-FAIL", failedOnly.get(0).getReferenceNumber());

        List<Transaction> pendingOnly = walletService.getTransactionHistory(
                aliceWallet.getWalletId(), TransactionFilter.builder().status(TransactionStatus.PENDING).build());
        assertEquals(1, pendingOnly.size());
        assertEquals("REQ-HIST-CIN-PEND", pendingOnly.get(0).getReferenceNumber());

        // 3. Filter by type
        List<Transaction> cashIns = walletService.getTransactionHistory(
                aliceWallet.getWalletId(), TransactionFilter.builder().type(TransactionType.CASH_IN).build());
        assertEquals(3, cashIns.size());

        List<Transaction> sends = walletService.getTransactionHistory(
                aliceWallet.getWalletId(), TransactionFilter.builder().type(TransactionType.SEND_MONEY).build());
        assertEquals(1, sends.size());
        assertEquals("REQ-HIST-SEND", sends.get(0).getReferenceNumber());

        // 4. Filter by date range (Nov 12 to Nov 18 inclusive)
        List<Transaction> midRange = walletService.getTransactionHistory(
                aliceWallet.getWalletId(),
                TransactionFilter.builder()
                        .fromDate(LocalDate.of(2026, 11, 12))
                        .toDate(LocalDate.of(2026, 11, 18))
                        .build());
        assertEquals(3, midRange.size());
        assertEquals("REQ-HIST-SEND", midRange.get(0).getReferenceNumber());
        assertEquals("REQ-HIST-CIN-PEND", midRange.get(1).getReferenceNumber());
        assertEquals("REQ-HIST-CIN-FAIL", midRange.get(2).getReferenceNumber());

        // 5. Combined date + type + status filter
        List<Transaction> combined = walletService.getTransactionHistory(
                aliceWallet.getWalletId(),
                TransactionFilter.builder()
                        .fromDate(LocalDate.of(2026, 11, 10))
                        .toDate(LocalDate.of(2026, 11, 16))
                        .type(TransactionType.CASH_IN)
                        .status(TransactionStatus.COMPLETED)
                        .build());
        assertEquals(1, combined.size());
        assertEquals("REQ-HIST-CIN-OK", combined.get(0).getReferenceNumber());

        // 6. Invalid date range rejected
        assertThrows(IllegalArgumentException.class, () -> TransactionFilter.builder()
                .fromDate(LocalDate.of(2026, 11, 20))
                .toDate(LocalDate.of(2026, 11, 10))
                .build());

        // 7. Comparators sort deterministically
        List<Transaction> byAmountDesc = new ArrayList<>(allAlice);
        byAmountDesc.sort(TransactionFilter.AMOUNT_DESC);
        assertEquals(new BigDecimal("1000.00"), byAmountDesc.get(0).getAmount());
        assertEquals(new BigDecimal("100.00"), byAmountDesc.get(byAmountDesc.size() - 1).getAmount());
    }

    @Test
    @DisplayName("AC-2: ReceiptBuilder formats masked receipts with counterparty and supports Prototype repeat drafts")
    void testReceiptBuilderMaskingCounterpartyAndPrototypeRepeatDraft() {
        walletService.cashIn("REQ-RCPT-FUND", aliceWallet.getWalletId(), null,
                new BigDecimal("800.00"), "Cash In via BDO");
        Transaction sendTxn = walletService.transfer("REQ-RCPT-SEND", aliceWallet.getWalletId(), bobWallet.getWalletId(),
                new BigDecimal("200.00"), new BigDecimal("10.00"), "Electricity share");

        // Sender perspective receipt
        String aliceReceipt = walletService.getTransactionReceipt(aliceWallet.getWalletId(), "REQ-RCPT-SEND");
        assertTrue(aliceReceipt.contains("OTOG E-WALLET RECEIPT"));
        assertTrue(aliceReceipt.contains("REQ-RCPT-SEND"));
        assertTrue(aliceReceipt.contains("PHP 200.00"));
        assertTrue(aliceReceipt.contains("PHP 10.00"));
        assertTrue(aliceReceipt.contains("PHP 210.00"));
        assertTrue(aliceReceipt.contains("Counterparty: Bob Santos (" + ReceiptBuilder.maskIdentifier("09172222222") + ")"));
        assertTrue(aliceReceipt.contains("Date:"));
        assertTrue(aliceReceipt.contains("Completed:"));
        assertTrue(aliceReceipt.contains(ReceiptBuilder.maskIdentifier(aliceWallet.getWalletId())));
        assertTrue(aliceReceipt.contains(ReceiptBuilder.maskIdentifier(bobWallet.getWalletId())));
        assertFalse(aliceReceipt.contains(aliceWallet.getWalletId()), "Raw source wallet ID must be masked");
        assertFalse(aliceReceipt.contains(bobWallet.getWalletId()), "Raw target wallet ID must be masked");
        assertFalse(aliceReceipt.contains("09172222222"), "Raw counterparty mobile must be masked");

        // Recipient perspective receipt shows sender as masked counterparty
        String bobReceipt = walletService.getTransactionReceipt(bobWallet.getWalletId(), "REQ-RCPT-SEND");
        assertTrue(bobReceipt.contains("Counterparty: Alice Reyes (" + ReceiptBuilder.maskIdentifier("09171111111") + ")"));
        assertFalse(bobReceipt.contains("09171111111"), "Raw sender mobile must be masked");

        // Cash-In receipt shows bank channel as counterparty & masks wallet ID
        String cashInReceipt = walletService.getTransactionReceipt(aliceWallet.getWalletId(), "REQ-RCPT-FUND");
        assertTrue(cashInReceipt.contains("Counterparty: BDO"));
        assertFalse(cashInReceipt.contains(aliceWallet.getWalletId()));

        // Prototype repeat-draft helper generates fresh reference number & PENDING state without mutating original
        Transaction draft = walletService.createRepeatTransactionDraft(aliceWallet.getWalletId(), "REQ-RCPT-SEND", null);
        assertInstanceOf(SendMoneyTransaction.class, draft);
        assertNotEquals(sendTxn.getTransactionId(), draft.getTransactionId());
        assertNotEquals(sendTxn.getReferenceNumber(), draft.getReferenceNumber());
        assertNotNull(draft.getReferenceNumber());
        assertEquals(TransactionStatus.PENDING, draft.getStatus());
        assertNull(draft.getCompletedAt());
        assertEquals(new BigDecimal("200.00"), draft.getAmount());
        assertEquals(new BigDecimal("10.00"), draft.getFee());
        assertEquals("Electricity share", draft.getDescription());
        assertEquals(TransactionStatus.COMPLETED, sendTxn.getStatus(), "Original transaction must remain COMPLETED");

        // Reusing original reference in repeat draft is rejected
        assertThrows(IllegalArgumentException.class,
                () -> ReceiptBuilder.createRepeatDraft(sendTxn, "REQ-RCPT-SEND"));

        // Non-participant cannot fetch receipt or clone draft
        assertThrows(SecurityException.class,
                () -> walletService.getTransactionReceipt(charlieWallet.getWalletId(), "REQ-RCPT-SEND"));
        assertThrows(SecurityException.class,
                () -> walletService.createRepeatTransactionDraft(charlieWallet.getWalletId(), "REQ-RCPT-SEND", null));
    }

    @Test
    @DisplayName("AC-3: Notifications persist after commit, track read/unread, and retries never repeat payments")
    void testPostCommitNotificationsReadUnreadAndFaultIsolatedRetries() {
        // 1. Failed pre-commit transfer (insufficient funds) produces ZERO notifications
        assertThrows(InsufficientFundsException.class, () ->
                walletService.transfer("REQ-FAIL-PRE", aliceWallet.getWalletId(), bobWallet.getWalletId(),
                        new BigDecimal("500.00"), BigDecimal.ZERO, "Should not notify"));
        assertEquals(0, walletService.getNotifications(alice.getUserId(), false).size());
        assertEquals(0, walletService.getNotifications(bob.getUserId(), false).size());

        // 2. Fund Alice via COMPLETED cash-in -> 1 unread notification
        walletService.cashIn("REQ-NOTIF-FUND", aliceWallet.getWalletId(), null,
                new BigDecimal("1000.00"), "Cash In via BDO");
        assertEquals(1, walletService.getUnreadNotificationCount(alice.getUserId()));

        // 3. Pending Cash-In followed by statusLookup settlement emits pending + settled notifications,
        // and repeat statusLookup never duplicates notification or balance credit
        walletService.setPaymentProviderFactory(PaymentProviderFactory.timeoutFactory());
        walletService.cashIn("REQ-NOTIF-PEND", aliceWallet.getWalletId(), null,
                new BigDecimal("200.00"), "Cash In via GCash");
        assertEquals(2, walletService.getUnreadNotificationCount(alice.getUserId()));

        walletService.setPaymentProviderFactory(PaymentProviderFactory.successFactory());
        walletService.statusLookup("REQ-NOTIF-PEND");
        assertEquals(new BigDecimal("1200.00"), walletService.getBalance(aliceWallet.getWalletId()));
        assertEquals(3, walletService.getUnreadNotificationCount(alice.getUserId()));

        // Duplicate statusLookup is idempotent: no extra credit, no extra notification
        walletService.statusLookup("REQ-NOTIF-PEND");
        assertEquals(new BigDecimal("1200.00"), walletService.getBalance(aliceWallet.getWalletId()));
        assertEquals(3, walletService.getUnreadNotificationCount(alice.getUserId()));

        // 4. Mark single notification read / unread and mark all read (persisted across fresh repo/service)
        List<Notification> aliceNotifs = walletService.getNotifications(alice.getUserId(), false);
        Notification firstNotif = aliceNotifs.get(0);
        assertFalse(firstNotif.isRead());

        walletService.markNotificationRead(alice.getUserId(), firstNotif.getNotificationId());
        assertEquals(2, walletService.getUnreadNotificationCount(alice.getUserId()));
        assertEquals(2, walletService.getNotifications(alice.getUserId(), true).size());

        // Verify persistence via a fresh NotificationServiceImpl over the same SQLite DB
        NotificationServiceImpl freshReader = new NotificationServiceImpl(databaseManager);
        assertEquals(2, freshReader.getUnreadCount(alice.getUserId()));

        walletService.markNotificationUnread(alice.getUserId(), firstNotif.getNotificationId());
        assertEquals(3, freshReader.getUnreadCount(alice.getUserId()));

        int marked = walletService.markAllNotificationsRead(alice.getUserId());
        assertEquals(3, marked);
        assertEquals(0, freshReader.getUnreadCount(alice.getUserId()));

        // 5. Simulate notification persistence failure after transfer commit -> FIFO retry NEVER repeats payment
        AtomicInteger remainingFailures = new AtomicInteger(2);
        NotificationRepository flakyRepo = new NotificationRepository(databaseManager) {
            @Override
            public Notification save(Notification notification) {
                if (remainingFailures.getAndDecrement() > 0) {
                    throw new OtogException("Simulated transient notification store outage");
                }
                return super.save(notification);
            }
        };
        NotificationServiceImpl flakyNotifService = new NotificationServiceImpl(flakyRepo);
        walletService.setNotificationService(flakyNotifService);

        int txCountBefore = walletService.getTransactionRepository().findAll().size();
        int ledgerCountBefore = walletService.getLedgerRepository().findAll().size();

        // Transfer MUST commit cleanly despite notification persistence throwing
        Transaction committedTransfer = walletService.transfer(
                "REQ-RETRY-SAFE", aliceWallet.getWalletId(), bobWallet.getWalletId(),
                new BigDecimal("300.00"), BigDecimal.ZERO, "Rent payment");
        assertEquals(TransactionStatus.COMPLETED, committedTransfer.getStatus());
        assertEquals(new BigDecimal("900.00"), walletService.getBalance(aliceWallet.getWalletId()));
        assertEquals(new BigDecimal("300.00"), walletService.getBalance(bobWallet.getWalletId()));
        assertEquals(2, flakyNotifService.getPendingRetryCount(),
                "Both sender and recipient notifications should be queued in FIFO retry queue");
        assertEquals(0, walletService.getUnreadNotificationCount(bob.getUserId()),
                "Recipient notification is not persisted yet while outage active");

        // Duplicate transfer reference still rejected; never repeats payment
        assertThrows(DuplicateTransactionException.class, () ->
                walletService.transfer("REQ-RETRY-SAFE", aliceWallet.getWalletId(), bobWallet.getWalletId(),
                        new BigDecimal("300.00"), BigDecimal.ZERO, "Duplicate"));

        // Retry queued notifications -> persists both notifications without touching balances or ledger
        int retried = walletService.retryPendingNotifications();
        assertEquals(2, retried);
        assertEquals(0, flakyNotifService.getPendingRetryCount());
        assertEquals(1, walletService.getUnreadNotificationCount(alice.getUserId()));
        assertEquals(1, walletService.getUnreadNotificationCount(bob.getUserId()));

        // Verify payment was NEVER repeated on retry
        assertEquals(new BigDecimal("900.00"), walletService.getBalance(aliceWallet.getWalletId()));
        assertEquals(new BigDecimal("300.00"), walletService.getBalance(bobWallet.getWalletId()));
        assertEquals(txCountBefore + 1, walletService.getTransactionRepository().findAll().size());
        assertEquals(ledgerCountBefore + 2, walletService.getLedgerRepository().findAll().size());

        // Calling retry again is an idempotent no-op
        assertEquals(0, walletService.retryPendingNotifications());
        assertEquals(1, walletService.getNotifications(bob.getUserId(), false).size());
    }

    @Test
    @DisplayName("AC-4: Sender/recipient notifications and strict cross-account isolation via Proxy and Service")
    void testSenderRecipientNotificationsAndAccountIsolation() {
        walletService.cashIn("REQ-ISO-FUND", aliceWallet.getWalletId(), null,
                new BigDecimal("500.00"), "Seed");
        Transaction sendTxn = walletService.transfer("REQ-ISO-SEND", aliceWallet.getWalletId(), bobWallet.getWalletId(),
                new BigDecimal("125.50"), new BigDecimal("2.50"), "Books");

        List<Notification> aliceNotifs = walletService.getNotifications(alice.getUserId(), false);
        List<Notification> bobNotifs = walletService.getNotifications(bob.getUserId(), false);
        List<Notification> charlieNotifs = walletService.getNotifications(charlie.getUserId(), false);

        // Sender receives TRANSFER_SENT with masked recipient
        Notification senderNotice = aliceNotifs.stream()
                .filter(n -> "REQ-ISO-SEND".equals(n.getReferenceNumber()))
                .findFirst()
                .orElseThrow();
        assertEquals(NotificationType.TRANSFER_SENT, senderNotice.getType());
        assertTrue(senderNotice.getMessage().contains("PHP 125.50"));
        assertTrue(senderNotice.getMessage().contains("Bob Santos"));
        assertFalse(senderNotice.getMessage().contains("09172222222"), "Raw recipient mobile must not leak");

        // Recipient receives TRANSFER_RECEIVED with masked sender
        assertEquals(1, bobNotifs.size());
        Notification recipientNotice = bobNotifs.get(0);
        assertEquals(NotificationType.TRANSFER_RECEIVED, recipientNotice.getType());
        assertEquals(sendTxn.getTransactionId(), recipientNotice.getTransactionId());
        assertTrue(recipientNotice.getMessage().contains("PHP 125.50"));
        assertTrue(recipientNotice.getMessage().contains("Alice Reyes"));
        assertFalse(recipientNotice.getMessage().contains("09171111111"), "Raw sender mobile must not leak");

        // Uninvolved third party receives ZERO notifications
        assertTrue(charlieNotifs.isEmpty());

        // Direct service rejects cross-account notification mutations
        assertThrows(SecurityException.class,
                () -> walletService.markNotificationRead(alice.getUserId(), recipientNotice.getNotificationId()));
        assertThrows(SecurityException.class,
                () -> walletService.markNotificationUnread(charlie.getUserId(), senderNotice.getNotificationId()));

        // Protection proxy enforces strict account isolation on history, receipts, drafts, and inbox
        WalletOperations proxy = ProtectedWalletOperationsProxy.wrap(walletService);
        SessionManager.getInstance().login(alice, walletService.getWallet(aliceWallet.getWalletId()));

        // Alice can access her own history, receipt, repeat draft, and inbox
        assertEquals(2, proxy.getTransactionHistory(aliceWallet.getWalletId(), TransactionFilter.empty()).size());
        assertTrue(proxy.getTransactionReceipt(aliceWallet.getWalletId(), "REQ-ISO-SEND").contains("REQ-ISO-SEND"));
        assertNotNull(proxy.createRepeatTransactionDraft(aliceWallet.getWalletId(), "REQ-ISO-SEND", null));
        assertEquals(2, proxy.getNotifications(alice.getUserId(), false).size());

        // Alice is blocked from Bob's and Charlie's history, receipts, drafts, and notifications
        assertThrows(SecurityException.class,
                () -> proxy.getTransactionHistory(bobWallet.getWalletId(), TransactionFilter.empty()));
        assertThrows(SecurityException.class,
                () -> proxy.getTransactionReceipt(bobWallet.getWalletId(), "REQ-ISO-SEND"));
        assertThrows(SecurityException.class,
                () -> proxy.createRepeatTransactionDraft(bobWallet.getWalletId(), "REQ-ISO-SEND", null));
        assertThrows(SecurityException.class,
                () -> proxy.getNotifications(bob.getUserId(), false));
        assertThrows(SecurityException.class,
                () -> proxy.getUnreadNotificationCount(bob.getUserId()));
        assertThrows(SecurityException.class,
                () -> proxy.markNotificationRead(bob.getUserId(), recipientNotice.getNotificationId()));
        assertThrows(SecurityException.class,
                () -> proxy.markAllNotificationsRead(bob.getUserId()));
    }

    @Test
    @DisplayName("UI Integration: Dashboard filters history, prepares repeat drafts, and NotificationInboxDialog manages read/unread off-EDT")
    void testDashboardHistoryFiltersRepeatDraftAndNotificationInboxDialog() throws Exception {
        walletService.cashIn("REQ-UI-FUND", aliceWallet.getWalletId(), null,
                new BigDecimal("600.00"), "Cash In via BDO");
        walletService.transfer("REQ-UI-SEND", aliceWallet.getWalletId(), bobWallet.getWalletId(),
                new BigDecimal("75.00"), BigDecimal.ZERO, "Coffee");

        SessionManager.getInstance().login(alice, walletService.getWallet(aliceWallet.getWalletId()));
        activeDashboard = new Main(ProtectedWalletOperationsProxy.wrap(walletService), authService);
        activeDashboard.getInitialLoadFuture().get(5, TimeUnit.SECONDS);

        // Unread notification badge shows 2 (Cash In + Send Money)
        assertEquals(2, activeDashboard.getCachedUnreadNotifications());
        assertEquals("Notifications (2)", activeDashboard.getNotificationsButton().getText());
        assertEquals(2, activeDashboard.getRecentTransactionsTable().getRowCount());

        // Filter to SEND_MONEY only off-EDT
        activeDashboard.applyHistoryFiltersAsync(
                TransactionFilter.builder().type(TransactionType.SEND_MONEY).build()
        ).get(5, TimeUnit.SECONDS);
        assertTrue(activeDashboard.isLastLoadExecutedOffEdt());
        assertEquals(1, activeDashboard.getRecentTransactionsTable().getRowCount());
        assertEquals("REQ-UI-SEND", activeDashboard.getRecentTransactionsTable().getValueAt(0, 1));

        // Select row and prepare Prototype repeat draft -> switches to Send Money tab with fresh ref
        SwingUtilities.invokeAndWait(() -> activeDashboard.getRecentTransactionsTable().setRowSelectionInterval(0, 0));
        Transaction draft = activeDashboard.createRepeatDraftForSelectedAsync().get(5, TimeUnit.SECONDS);
        assertInstanceOf(SendMoneyTransaction.class, draft);
        assertNotEquals("REQ-UI-SEND", draft.getReferenceNumber());
        assertEquals("Send Money",
                activeDashboard.getTabs().getTitleAt(activeDashboard.getTabs().getSelectedIndex()));
        assertEquals("75.00", activeDashboard.getSendAmountField().getText());

        // Clear filters restores full activity
        activeDashboard.clearHistoryFiltersAsync().get(5, TimeUnit.SECONDS);
        assertEquals(2, activeDashboard.getRecentTransactionsTable().getRowCount());

        // NotificationInboxDialog loads off-EDT and marks all read, updating dashboard badge
        AtomicInteger badgeUpdates = new AtomicInteger(-1);
        NotificationInboxDialog inbox = new NotificationInboxDialog(
                activeDashboard,
                activeDashboard.getWalletService(),
                alice.getUserId(),
                aliceWallet.getWalletId(),
                badgeUpdates::set
        );
        try {
            List<Notification> loaded = inbox.loadNotificationsAsync().get(5, TimeUnit.SECONDS);
            assertTrue(inbox.isLastLoadExecutedOffEdt());
            assertEquals(2, loaded.size());
            assertEquals(2, badgeUpdates.get());

            int markedCount = inbox.markAllReadAsync().get(5, TimeUnit.SECONDS);
            assertEquals(2, markedCount);
            assertEquals(0, badgeUpdates.get());
            assertEquals("Unread notifications: 0", inbox.getUnreadSummaryLabel().getText());
        } finally {
            SwingUtilities.invokeAndWait(inbox::dispose);
        }
    }

    private void updateTransactionCreatedAt(String transactionId, LocalDateTime createdAt) throws SQLException {
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE transactions SET created_at = ? WHERE transaction_id = ?")) {
            ps.setString(1, createdAt.toString());
            ps.setString(2, transactionId);
            ps.executeUpdate();
        }
    }
}
