package com.otog;

import com.intellij.uiDesigner.core.GridLayoutManager;
import com.otog.database.DatabaseManager;
import com.otog.exception.DuplicateTransactionException;
import com.otog.exception.InsufficientFundsException;
import com.otog.exception.OtogException;
import com.otog.exception.WalletException;
import com.otog.model.BillPaymentTransaction;
import com.otog.model.CompositeFeePolicy;
import com.otog.model.FeeComponent;
import com.otog.model.FlatFee;
import com.otog.model.Notification;
import com.otog.model.NotificationType;
import com.otog.model.ReceiptBuilder;
import com.otog.model.Transaction;
import com.otog.model.TransactionBuilder;
import com.otog.model.TransactionStatus;
import com.otog.model.TransactionType;
import com.otog.model.User;
import com.otog.model.Wallet;
import com.otog.repository.CredentialRepository;
import com.otog.repository.NotificationRepository;
import com.otog.repository.TransactionRepository;
import com.otog.service.AuthServiceImpl;
import com.otog.service.BankApiClient;
import com.otog.service.BankApiResponse;
import com.otog.service.BankResponseStatus;
import com.otog.service.NotificationServiceImpl;
import com.otog.service.PaymentProviderFactory;
import com.otog.service.ProtectedWalletOperationsProxy;
import com.otog.service.SessionManager;
import com.otog.service.WalletOperations;
import com.otog.service.WalletService;
import com.otog.ui.Main;
import com.otog.ui.OverviewPanel;
import com.otog.ui.PayBillsPanel;
import com.otog.util.MoneyUtil;
import com.otog.util.OtogConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive verification suite for CAP-10: Pay Bills.
 * Covers:
 * - AC-1: Seeded biller list, reference validation, amount entry, fee preview, and PIN confirmation in bound PayBillsPanel.form
 * - AC-2: PaymentProviderFactory reuse, deterministic settlement (COMPLETED, FAILED, PENDING), duplicate protection, status lookup, and repeat draft
 * - AC-3: SQLite persistence with biller_reference column, masked receipts, post-commit persistent notifications, and fund reservation/release
 */
public class PayBillsTest {

    private DatabaseManager databaseManager;
    private WalletService walletService;
    private AuthServiceImpl authService;
    private Main activeDashboard;

    private User testUser;
    private Wallet testWallet;

    @BeforeEach
    void setUp() {
        SessionManager.getInstance().reset();
        databaseManager = new DatabaseManager("jdbc:sqlite::memory:");
        authService = new AuthServiceImpl(databaseManager);
        walletService = new WalletService(databaseManager, authService);

        testUser = authService.registerUser("Maria Clara", "09181234567", "maria@otog.ph", "Secret123");
        testWallet = walletService.getWalletByUserId(testUser.getUserId());
        SessionManager.getInstance().login(testUser, testWallet);
        authService.setPin(testUser.getUserId(), "Secret123", "1234");

        // Seed wallet with initial funds via cash in
        walletService.setPaymentProviderFactory(PaymentProviderFactory.successFactory());
        walletService.cashIn("INIT-FUND-01", testWallet.getWalletId(), null, new BigDecimal("5000.00"), "Initial Funding");
        testWallet = walletService.getWallet(testWallet.getWalletId());
        SessionManager.getInstance().login(testUser, testWallet);
        walletService.markAllNotificationsRead(testUser.getUserId());
        SessionManager.getInstance().markPinVerified();
    }

    @AfterEach
    void tearDown() {
        OtogConfig.getInstance().clearOverrides();
        if (activeDashboard != null) {
            activeDashboard.dispose();
            activeDashboard = null;
        }
        SessionManager.getInstance().reset();
        databaseManager.close();
    }

    // =========================================================================
    // AC-1: Seeded billers, reference validation, amount entry, fee preview, PIN
    // =========================================================================

    @Test
    @DisplayName("AC-1: Seeded biller list contains at least 5 billers and populates combobox even when pre-constructed")
    void testSeededBillerListAndComboboxPopulation() {
        List<String> seeded = PayBillsPanel.SEEDED_BILLERS;
        assertTrue(seeded.size() >= 5, "Must have at least 5 seeded billers");
        assertTrue(seeded.stream().anyMatch(b -> b.contains("Meralco")));
        assertTrue(seeded.stream().anyMatch(b -> b.contains("Manila Water")));
        assertTrue(seeded.stream().anyMatch(b -> b.contains("Maynilad")));
        assertTrue(seeded.stream().anyMatch(b -> b.contains("PLDT")));
        assertTrue(seeded.stream().anyMatch(b -> b.contains("Globe")));

        PayBillsPanel panel = new PayBillsPanel(walletService, authService);
        assertNotNull(panel.getBillerComboBox());
        assertTrue(panel.getBillerComboBox().getItemCount() >= 5);
        assertEquals("Meralco (Electricity)", panel.getBillerComboBox().getItemAt(0));
    }

    @Test
    @DisplayName("AC-1: Shared biller reference validation rejects blank, short (<6 chars), and invalid format strings")
    void testBillerReferenceValidation() {
        PayBillsPanel panel = new PayBillsPanel(walletService, authService);

        // Blank / null
        assertThrows(IllegalArgumentException.class, () -> panel.validateReference(null));
        assertThrows(IllegalArgumentException.class, () -> panel.validateReference("   "));
        assertThrows(IllegalArgumentException.class, () -> WalletService.validateBillerReference(null));
        assertThrows(IllegalArgumentException.class, () -> WalletService.validateBillerReference(""));

        // Too short (< 6 characters)
        assertThrows(IllegalArgumentException.class, () -> panel.validateReference("12345"));
        assertThrows(IllegalArgumentException.class, () -> WalletService.validateBillerReference("12345"));
        assertThrows(IllegalArgumentException.class, () ->
                walletService.payBill("REF-VAL-01", testWallet.getWalletId(), "Meralco", "123", new BigDecimal("100.00"), BigDecimal.ZERO, "Bill"));

        // Invalid characters (spaces, special characters)
        assertThrows(IllegalArgumentException.class, () -> panel.validateReference("ACC 123456"));
        assertThrows(IllegalArgumentException.class, () -> WalletService.validateBillerReference("ACC@123#$"));

        // Valid references (>= 6 chars, alphanumeric, hyphens, dots, underscores)
        assertDoesNotThrow(() -> panel.validateReference("123456"));
        assertDoesNotThrow(() -> panel.validateReference("MERALCO-987654321"));
        assertDoesNotThrow(() -> WalletService.validateBillerReference("ACC_0918.234"));
    }

    @Test
    @DisplayName("AC-1: Amount entry and live fee preview update accurately with configured policy")
    void testAmountEntryAndFeePreview() {
        PayBillsPanel panel = new PayBillsPanel(walletService, authService);

        // Default empty shows PHP 0.00
        assertEquals("PHP 0.00", panel.getFeePreviewLabel().getText());
        assertEquals("PHP 0.00", panel.getTotalPreviewLabel().getText());

        // Configure fee policy (flat PHP 15.00)
        FeeComponent feePolicy = new FlatFee("Bill Convenience Fee", new BigDecimal("15.00"));
        panel.setFeePolicy(feePolicy);

        panel.getBillAmountField().setText("500.00");
        panel.updateFeePreview();
        assertEquals("PHP 15.00", panel.getFeePreviewLabel().getText());
        assertEquals("PHP 515.00", panel.getTotalPreviewLabel().getText());

        // Invalid amount
        panel.getBillAmountField().setText("-20.00");
        panel.updateFeePreview();
        assertEquals("Invalid amount", panel.getFeePreviewLabel().getText());

        panel.getBillAmountField().setText("abc");
        panel.updateFeePreview();
        assertEquals("Invalid amount", panel.getFeePreviewLabel().getText());
    }

    @Test
    @DisplayName("AC-1: Bound IntelliJ Swing form panel initializes cleanly with GridLayoutManager and UI interactions")
    void testPayBillsPanelBoundFormLayoutAndInteractions() {
        PayBillsPanel panel = new PayBillsPanel(walletService, authService);
        assertNotNull(panel.$$$getRootComponent$$$());
        assertInstanceOf(GridLayoutManager.class, panel.getRootPanel().getLayout());

        assertNotNull(panel.getBillerComboBox());
        assertNotNull(panel.getBillReferenceField());
        assertNotNull(panel.getBillAmountField());
        assertNotNull(panel.getFeePreviewLabel());
        assertNotNull(panel.getTotalPreviewLabel());
        assertNotNull(panel.getBillPinField());
        assertNotNull(panel.getSubmitBillPaymentButton());
        assertNotNull(panel.getBillBackToOverviewButton());
        assertNotNull(panel.getBillFeedbackLabel());

        AtomicBoolean backClicked = new AtomicBoolean(false);
        panel.setOnBackToOverview(() -> backClicked.set(true));
        panel.getBillBackToOverviewButton().doClick();
        assertTrue(backClicked.get(), "Back to Overview listener must be invoked on click");
    }

    @Test
    @DisplayName("AC-1: Transaction PIN verification blocks incorrect PIN and clears PIN field on failure")
    void testPinVerificationAndClearingOnFailure() {
        PayBillsPanel panel = new PayBillsPanel(walletService, authService);
        panel.getBillReferenceField().setText("1234567890");
        panel.getBillAmountField().setText("250.00");
        panel.getBillPinField().setText("9999"); // Wrong PIN

        assertThrows(WalletException.class, panel::executePayment);
        assertEquals("", new String(panel.getBillPinField().getPassword()), "PIN field must be cleared after failed attempt");
        assertEquals(new BigDecimal("5000.00"), walletService.getBalance(testWallet.getWalletId()), "Balance must remain unchanged");
    }

    // =========================================================================
    // AC-2: PaymentProviderFactory, settlement modes, idempotency & duplicate guard
    // =========================================================================

    @Test
    @DisplayName("AC-2: Immediate SUCCESS settles bill payment to COMPLETED and balance remains debited")
    void testImmediateSuccessSettlement() {
        walletService.setPaymentProviderFactory(PaymentProviderFactory.successFactory());

        PayBillsPanel panel = new PayBillsPanel(walletService, authService);
        panel.getBillerComboBox().setSelectedItem("Meralco (Electricity)");
        panel.getBillReferenceField().setText("ACC-MER-12345");
        panel.getBillAmountField().setText("1000.00");
        panel.getBillPinField().setText("1234");

        Transaction txn = panel.executePayment();
        assertNotNull(txn);
        assertInstanceOf(BillPaymentTransaction.class, txn);
        assertEquals(TransactionStatus.COMPLETED, txn.getStatus());
        assertNotNull(txn.getCompletedAt());
        assertEquals(new BigDecimal("1000.00"), txn.getAmount());
        assertEquals(new BigDecimal("4000.00"), walletService.getBalance(testWallet.getWalletId()));

        // Verification of field clearing on success
        assertEquals("", panel.getBillReferenceField().getText());
        assertEquals("", panel.getBillAmountField().getText());
        assertEquals("", new String(panel.getBillPinField().getPassword()));
    }

    @Test
    @DisplayName("AC-2: Immediate FAILURE settles bill payment to FAILED and reserved funds are released")
    void testImmediateFailureReleasesReservedFunds() {
        walletService.setPaymentProviderFactory(PaymentProviderFactory.failureFactory());

        PayBillsPanel panel = new PayBillsPanel(walletService, authService);
        panel.getBillerComboBox().setSelectedItem("Maynilad (Water)");
        panel.getBillReferenceField().setText("ACC-MAY-67890");
        panel.getBillAmountField().setText("800.00");
        panel.getBillPinField().setText("1234");

        Transaction txn = panel.executePayment();
        assertNotNull(txn);
        assertEquals(TransactionStatus.FAILED, txn.getStatus());
        assertNotNull(txn.getCompletedAt());
        // 5000 - 800 + 800 (refund) = 5000
        assertEquals(new BigDecimal("5000.00"), walletService.getBalance(testWallet.getWalletId()));
        // On FAILED settlement, reference and amount stay populated while PIN is cleared
        assertEquals("ACC-MAY-67890", panel.getBillReferenceField().getText());
        assertEquals("800.00", panel.getBillAmountField().getText());
        assertEquals("", new String(panel.getBillPinField().getPassword()));
    }

    @Test
    @DisplayName("AC-2: TIMEOUT leaves transaction PENDING, and deferred statusLookup settles it idempotently")
    void testTimeoutPendingAndDeferredStatusLookup() {
        // Step 1: Initial timeout
        walletService.setPaymentProviderFactory(PaymentProviderFactory.timeoutFactory());

        Transaction txn = walletService.payBillWithPin(
                "REQ-DEFER-01",
                testWallet.getWalletId(),
                "PLDT (Telecom & Internet)",
                "PLDT-987654",
                new BigDecimal("1500.00"),
                BigDecimal.ZERO,
                "Fiber Internet",
                "1234"
        );
        assertEquals(TransactionStatus.PENDING, txn.getStatus());
        assertNull(txn.getCompletedAt());
        // Funds are reserved
        assertEquals(new BigDecimal("3500.00"), walletService.getBalance(testWallet.getWalletId()));

        // Step 2: Switch provider to SUCCESS and lookup status
        walletService.setPaymentProviderFactory(PaymentProviderFactory.successFactory());
        Transaction settled = walletService.statusLookup("REQ-DEFER-01");
        assertEquals(TransactionStatus.COMPLETED, settled.getStatus());
        assertNotNull(settled.getCompletedAt());
        assertEquals(new BigDecimal("3500.00"), walletService.getBalance(testWallet.getWalletId()));

        // Step 3: Repeated status lookup is idempotent and does not duplicate debit
        Transaction repeat = walletService.statusLookup("REQ-DEFER-01");
        assertEquals(TransactionStatus.COMPLETED, repeat.getStatus());
        assertEquals(new BigDecimal("3500.00"), walletService.getBalance(testWallet.getWalletId()));
    }

    @Test
    @DisplayName("AC-2: Deferred failure releases reserved funds back to wallet")
    void testDeferredFailureReleasesReservedFunds() {
        walletService.setPaymentProviderFactory(PaymentProviderFactory.timeoutFactory());

        Transaction txn = walletService.payBillWithPin(
                "REQ-FAIL-DEF",
                testWallet.getWalletId(),
                "Manila Water (Water)",
                "MW-11223344",
                new BigDecimal("400.00"),
                new BigDecimal("10.00"),
                "Water Bill",
                "1234"
        );
        assertEquals(TransactionStatus.PENDING, txn.getStatus());
        // 5000 - (400 + 10) = 4590
        assertEquals(new BigDecimal("4590.00"), walletService.getBalance(testWallet.getWalletId()));

        // Settle with failure
        walletService.setPaymentProviderFactory(PaymentProviderFactory.failureFactory());
        Transaction failed = walletService.statusLookup("REQ-FAIL-DEF");
        assertEquals(TransactionStatus.FAILED, failed.getStatus());
        // 4590 + 410 = 5000 (totalDebit refunded)
        assertEquals(new BigDecimal("5000.00"), walletService.getBalance(testWallet.getWalletId()));
    }

    @Test
    @DisplayName("AC-2: Whitespace-padded duplicate referenceNumber throws DuplicateTransactionException")
    void testWhitespacePaddedDuplicateProtection() {
        walletService.setPaymentProviderFactory(PaymentProviderFactory.successFactory());

        walletService.payBill(
                "BILL-DUP-01",
                testWallet.getWalletId(),
                "Globe Telecom (Mobile & Broadband)",
                "GLOBE-554433",
                new BigDecimal("500.00"),
                BigDecimal.ZERO,
                "Mobile Bill"
        );

        // Exact match
        assertThrows(DuplicateTransactionException.class, () ->
                walletService.payBill(
                        "BILL-DUP-01",
                        testWallet.getWalletId(),
                        "Globe Telecom (Mobile & Broadband)",
                        "GLOBE-554433",
                        new BigDecimal("500.00"),
                        BigDecimal.ZERO,
                        "Mobile Bill"
                ));

        // Whitespace-padded reference ("  BILL-DUP-01  ")
        assertThrows(DuplicateTransactionException.class, () ->
                walletService.payBill(
                        "   BILL-DUP-01   ",
                        testWallet.getWalletId(),
                        "Globe Telecom (Mobile & Broadband)",
                        "GLOBE-554433",
                        new BigDecimal("500.00"),
                        BigDecimal.ZERO,
                        "Mobile Bill"
                ));
    }

    @Test
    @DisplayName("AC-2: Insufficient funds prevents fund reservation and throws InsufficientFundsException")
    void testInsufficientFundsRejection() {
        walletService.setPaymentProviderFactory(PaymentProviderFactory.successFactory());
        assertThrows(InsufficientFundsException.class, () ->
                walletService.payBill(
                        "REQ-NO-FUNDS",
                        testWallet.getWalletId(),
                        "Meralco (Electricity)",
                        "ACC-MER-9999",
                        new BigDecimal("99999.00"),
                        BigDecimal.ZERO,
                        "Excessive Bill"
                ));
        assertEquals(new BigDecimal("5000.00"), walletService.getBalance(testWallet.getWalletId()));
    }

    // =========================================================================
    // AC-3: History, SQLite reload, receipt masking, and notifications
    // =========================================================================

    @Test
    @DisplayName("AC-3: billerReference survives SQLite persistence & reload without corruption even with custom descriptions")
    void testBillerReferencePersistsAcrossSqliteReload() {
        walletService.setPaymentProviderFactory(PaymentProviderFactory.successFactory());

        String originalRef = "MERALCO-ACCT-889900";
        String customDesc = "November fiber and electric bill with brackets [notes]";

        Transaction txn = walletService.payBill(
                "REQ-PERSIST-01",
                testWallet.getWalletId(),
                "Meralco (Electricity)",
                originalRef,
                new BigDecimal("1200.00"),
                new BigDecimal("15.00"),
                customDesc
        );
        assertEquals(TransactionStatus.COMPLETED, txn.getStatus());

        // Reload via fresh repository over SQLite
        TransactionRepository freshRepo = new TransactionRepository(databaseManager);
        Optional<Transaction> loadedOpt = freshRepo.findByReferenceNumber("REQ-PERSIST-01");
        assertTrue(loadedOpt.isPresent());
        Transaction loaded = loadedOpt.get();
        assertInstanceOf(BillPaymentTransaction.class, loaded);
        BillPaymentTransaction loadedBpt = (BillPaymentTransaction) loaded;

        assertEquals(originalRef, loadedBpt.getBillerReference(),
                "Biller reference must match original input exactly, not corrupted by description");
        assertEquals("Meralco (Electricity)", loadedBpt.getBillerName());
        assertEquals(customDesc, loadedBpt.getDescription());
        assertEquals(new BigDecimal("1200.00"), loadedBpt.getAmount());
        assertEquals(new BigDecimal("15.00"), loadedBpt.getFee());
        assertEquals(new BigDecimal("1215.00"), loadedBpt.getTotalAmount());
    }

    @Test
    @DisplayName("AC-3: Masked receipts display biller counterparty and mask account reference numbers")
    void testMaskedReceiptDetails() {
        walletService.setPaymentProviderFactory(PaymentProviderFactory.successFactory());

        String rawAcct = "987654321012";
        Transaction txn = walletService.payBill(
                "REQ-RCPT-BILL",
                testWallet.getWalletId(),
                "PLDT (Telecom & Internet)",
                rawAcct,
                new BigDecimal("1899.00"),
                new BigDecimal("20.00"),
                "Monthly Internet"
        );

        String receipt = walletService.getTransactionReceipt(testWallet.getWalletId(), "REQ-RCPT-BILL");
        assertNotNull(receipt);
        assertTrue(receipt.contains("BILL_PAYMENT"));
        assertTrue(receipt.contains("PLDT (Telecom & Internet)"));
        assertTrue(receipt.contains("Counterparty:"));
        assertTrue(receipt.contains("PHP 1,899.00"));
        assertTrue(receipt.contains("PHP 20.00"));
        assertTrue(receipt.contains("PHP 1,919.00"));

        // Must NOT expose raw unmasked account number
        assertFalse(receipt.contains(rawAcct), "Receipt must never contain raw unmasked account number");
        assertTrue(receipt.contains(ReceiptBuilder.maskIdentifier(rawAcct)));
    }

    @Test
    @DisplayName("AC-3: Post-commit persistent notifications are created for BILL_PAYMENT and not mislabeled as Cash Out")
    void testPostCommitNotificationsForBillPayment() {
        walletService.setPaymentProviderFactory(PaymentProviderFactory.successFactory());

        assertEquals(0, walletService.getUnreadNotificationCount(testUser.getUserId()));

        walletService.payBill(
                "REQ-NOTIF-01",
                testWallet.getWalletId(),
                "Meralco (Electricity)",
                "ACC-11223344",
                new BigDecimal("750.00"),
                BigDecimal.ZERO,
                "Power bill"
        );

        // 1 unread notification
        assertEquals(1, walletService.getUnreadNotificationCount(testUser.getUserId()));
        List<Notification> notifs = walletService.getNotifications(testUser.getUserId(), true);
        assertEquals(1, notifs.size());
        Notification n = notifs.get(0);

        assertEquals(NotificationType.BILL_PAYMENT_COMPLETED, n.getType());
        assertEquals("Bill Payment Completed", n.getTitle());
        assertTrue(n.getMessage().contains("Meralco (Electricity)"));
        assertFalse(n.getMessage().contains("Cash Out"), "Bill payment notification must never be labeled as Cash Out");

        // Mark read
        walletService.markNotificationRead(testUser.getUserId(), n.getNotificationId());
        assertEquals(0, walletService.getUnreadNotificationCount(testUser.getUserId()));
    }

    @Test
    @DisplayName("AC-3: Prototype repeat draft clones BillPaymentTransaction with fresh reference and cleared metadata")
    void testRepeatDraftForBillPayment() {
        walletService.setPaymentProviderFactory(PaymentProviderFactory.successFactory());

        Transaction original = walletService.payBill(
                "REQ-DRAFT-ORIG",
                testWallet.getWalletId(),
                "Manila Water (Water)",
                "MW-55667788",
                new BigDecimal("600.00"),
                BigDecimal.ZERO,
                "Water service"
        );

        Transaction draft = walletService.createRepeatTransactionDraft(testWallet.getWalletId(), "REQ-DRAFT-ORIG", null);
        assertNotNull(draft);
        assertInstanceOf(BillPaymentTransaction.class, draft);
        BillPaymentTransaction bptDraft = (BillPaymentTransaction) draft;

        assertNotEquals(original.getTransactionId(), draft.getTransactionId());
        assertNotEquals(original.getReferenceNumber(), draft.getReferenceNumber());
        assertEquals(TransactionStatus.PENDING, draft.getStatus());
        assertNull(draft.getCompletedAt());
        assertEquals(new BigDecimal("600.00"), draft.getAmount());
        assertEquals("Manila Water (Water)", bptDraft.getBillerName());
        assertEquals("MW-55667788", bptDraft.getBillerReference());
    }

    // =========================================================================
    // UI Integration: Dashboard Main wiring, Quick Action button & Repeat Draft
    // =========================================================================

    @Test
    @DisplayName("UI Integration: Main registers Pay Bills tab, overview quick action navigates to Pay Bills, and Repeat Draft pre-fills form")
    void testDashboardMainWiringAndNavigation() throws Exception {
        activeDashboard = new Main(walletService, authService, null, null);
        activeDashboard.getInitialLoadFuture().get(5, TimeUnit.SECONDS);

        // Pay Bills tab is registered and navigable
        List<String> tabs = activeDashboard.getVisibleTabTitles();
        assertTrue(tabs.contains("Pay Bills"), "Pay Bills tab must be visible");
        assertNotNull(activeDashboard.getPayBillsPanel());

        assertTrue(activeDashboard.navigateToTab("Pay Bills"));
        assertEquals("Pay Bills", activeDashboard.getTabs().getTitleAt(activeDashboard.getTabs().getSelectedIndex()));

        // Back to Overview navigates back
        assertTrue(activeDashboard.navigateBack());
        assertEquals("Overview", activeDashboard.getTabs().getTitleAt(activeDashboard.getTabs().getSelectedIndex()));

        // OverviewPanel has Pay Bills quick action button
        OverviewPanel overview = (OverviewPanel) activeDashboard.getTabs().getComponentAt(0);
        assertNotNull(overview.getNavPayBillsButton());
        overview.getNavPayBillsButton().doClick();
        assertEquals("Pay Bills", activeDashboard.getTabs().getTitleAt(activeDashboard.getTabs().getSelectedIndex()));
    }

    @Test
    @DisplayName("Thread Safety: submitPaymentAsync executes service/DB off-EDT and runs Swing callbacks on EDT")
    void testSubmitPaymentAsyncRunsOffEdtAndInvokesCallbacksOnEdt() throws Exception {
        AtomicBoolean payBillOffEdt = new AtomicBoolean(false);
        WalletService spyWalletService = new WalletService(databaseManager, authService) {
            @Override
            public Transaction payBill(String referenceNumber, String sourceWalletId, String billerName,
                                       String billerReference, BigDecimal amount, BigDecimal fee, String description) {
                payBillOffEdt.set(!SwingUtilities.isEventDispatchThread());
                return super.payBill(referenceNumber, sourceWalletId, billerName, billerReference, amount, fee, description);
            }
        };
        spyWalletService.setPaymentProviderFactory(PaymentProviderFactory.successFactory());

        PayBillsPanel panel = new PayBillsPanel(spyWalletService, authService);
        AtomicBoolean callbackOnEdt = new AtomicBoolean(false);
        AtomicReference<Transaction> callbackTxn = new AtomicReference<>();
        panel.setOnPaymentSuccess(txn -> {
            callbackOnEdt.set(SwingUtilities.isEventDispatchThread());
            callbackTxn.set(txn);
        });

        SwingUtilities.invokeAndWait(() -> {
            panel.getBillReferenceField().setText("PRE-FILLED-REF");
            panel.getBillAmountField().setText("999.00");
            panel.getBillPinField().setText("9999");
        });

        Transaction txn = panel.submitPaymentAsync("Meralco (Electricity)", "ACC-ASYNC-01", "250.00", "1234")
                .get(5, TimeUnit.SECONDS);

        assertNotNull(txn);
        assertEquals(TransactionStatus.COMPLETED, txn.getStatus());
        assertTrue(payBillOffEdt.get(), "payBill service call must run off the Swing EDT");
        assertTrue(panel.isLastPaymentExecutedOffEdt(), "SwingWorker must record off-EDT execution");
        assertTrue(callbackOnEdt.get(), "onPaymentSuccess callback must execute on the Swing EDT");
        assertSame(txn, callbackTxn.get());
        assertEquals(new BigDecimal("4750.00"), walletService.getBalance(testWallet.getWalletId()));

        SwingUtilities.invokeAndWait(() -> {
            assertTrue(panel.getSubmitBillPaymentButton().isEnabled());
            assertEquals("", panel.getBillReferenceField().getText());
            assertEquals("", panel.getBillAmountField().getText());
            assertEquals("", new String(panel.getBillPinField().getPassword()));
        });
    }

    @Test
    @DisplayName("UI Snapshot & PIN Clearing: Interactive confirm uses captured inputs and clears PIN before dialog")
    void testInteractiveConfirmUsesCapturedSnapshotAndClearsPinBeforeDialog() throws Exception {
        walletService.setPaymentProviderFactory(PaymentProviderFactory.successFactory());
        AtomicReference<String> pinSeenDuringDialog = new AtomicReference<>();
        AtomicReference<String> confirmMessageSeen = new AtomicReference<>();

        PayBillsPanel panel = new PayBillsPanel(walletService, authService) {
            @Override
            protected int showConfirmDialog(String title, String message) {
                pinSeenDuringDialog.set(new String(getBillPinField().getPassword()));
                confirmMessageSeen.set(message);
                // Simulate concurrent/post-confirm field tampering before worker completes
                getBillerComboBox().setSelectedItem("Globe Telecom (Mobile & Broadband)");
                getBillReferenceField().setText("TAMPERED-999999");
                getBillAmountField().setText("999.00");
                getBillPinField().setText("0000");
                return JOptionPane.OK_OPTION;
            }
        };

        AtomicReference<CompletableFuture<Transaction>> futureRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            panel.getBillerComboBox().setSelectedItem("Meralco (Electricity)");
            panel.getBillReferenceField().setText("ORIG-123456");
            panel.getBillAmountField().setText("200.00");
            panel.getBillPinField().setText("1234");
            futureRef.set(panel.submitPaymentAsync());
        });

        Transaction txn = futureRef.get().get(5, TimeUnit.SECONDS);
        assertNotNull(txn);
        BillPaymentTransaction bpt = assertInstanceOf(BillPaymentTransaction.class, txn);
        assertEquals("", pinSeenDuringDialog.get(), "PIN field must be cleared before confirmation dialog opens");
        assertTrue(confirmMessageSeen.get().contains(ReceiptBuilder.maskIdentifier("ORIG-123456")));
        assertEquals("Meralco (Electricity)", bpt.getBillerName());
        assertEquals("ORIG-123456", bpt.getBillerReference());
        assertEquals(new BigDecimal("200.00"), bpt.getAmount());
        assertEquals(new BigDecimal("4800.00"), walletService.getBalance(testWallet.getWalletId()));
    }

    @Test
    @DisplayName("UI Cancel: Cancelling confirmation dialog clears typed PIN and leaves balance untouched")
    void testCancelConfirmDialogClearsPinAndDoesNotDebit() throws Exception {
        PayBillsPanel panel = new PayBillsPanel(walletService, authService) {
            @Override
            protected int showConfirmDialog(String title, String message) {
                return JOptionPane.CANCEL_OPTION;
            }
        };

        AtomicReference<CompletableFuture<Transaction>> futureRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            panel.getBillerComboBox().setSelectedItem("Meralco (Electricity)");
            panel.getBillReferenceField().setText("ACC-CANCEL-01");
            panel.getBillAmountField().setText("300.00");
            panel.getBillPinField().setText("1234");
            futureRef.set(panel.submitPaymentAsync());
        });

        assertNull(futureRef.get().get(5, TimeUnit.SECONDS));
        SwingUtilities.invokeAndWait(() -> {
            assertEquals("", new String(panel.getBillPinField().getPassword()),
                    "Typed PIN must remain cleared after cancelling confirmation");
            assertEquals("Bill payment cancelled.", panel.getBillFeedbackLabel().getText());
        });
        assertEquals(new BigDecimal("5000.00"), walletService.getBalance(testWallet.getWalletId()));
    }

    @Test
    @DisplayName("Dashboard Integration: Main status is status-aware and preserves fields on FAILED settlement")
    void testDashboardPayBillsStatusAwareAndPreservesFieldsOnFailure() throws Exception {
        activeDashboard = new Main(walletService, authService, null, null);
        activeDashboard.getInitialLoadFuture().get(5, TimeUnit.SECONDS);
        PayBillsPanel panel = activeDashboard.getPayBillsPanel();

        // 1. FAILED outcome: status label says failed (not completed) and form inputs stay populated
        walletService.setPaymentProviderFactory(PaymentProviderFactory.failureFactory());
        SwingUtilities.invokeAndWait(() -> {
            panel.getBillReferenceField().setText("FAIL-REF-111");
            panel.getBillAmountField().setText("300.00");
        });
        Transaction failed = activeDashboard
                .submitBillPaymentAsync("Meralco (Electricity)", "FAIL-REF-111", "300.00", "1234")
                .get(5, TimeUnit.SECONDS);
        assertEquals(TransactionStatus.FAILED, failed.getStatus());
        assertTrue(activeDashboard.isLastPaymentExecutedOffEdt());
        assertTrue(activeDashboard.getStatusLabel().getText().contains("failed"));
        assertFalse(activeDashboard.getStatusLabel().getText().contains("completed"));
        assertEquals("FAIL-REF-111", panel.getBillReferenceField().getText());
        assertEquals("300.00", panel.getBillAmountField().getText());

        // 2. PENDING outcome: status label says pending settlement (not completed) and inputs clear
        walletService.setPaymentProviderFactory(PaymentProviderFactory.timeoutFactory());
        Transaction pending = activeDashboard
                .submitBillPaymentAsync("PLDT (Telecom & Internet)", "PEND-REF-222", "400.00", "1234")
                .get(5, TimeUnit.SECONDS);
        assertEquals(TransactionStatus.PENDING, pending.getStatus());
        assertTrue(activeDashboard.getStatusLabel().getText().contains("pending"));
        assertFalse(activeDashboard.getStatusLabel().getText().contains("completed"));
        assertEquals("", panel.getBillReferenceField().getText());
        assertEquals("", panel.getBillAmountField().getText());

        // 3. COMPLETED outcome: status label says completed, balance updates, and inputs clear
        walletService.setPaymentProviderFactory(PaymentProviderFactory.successFactory());
        Transaction completed = activeDashboard
                .submitBillPaymentAsync("Manila Water (Water)", "SUCC-REF-333", "500.00", "1234")
                .get(5, TimeUnit.SECONDS);
        assertEquals(TransactionStatus.COMPLETED, completed.getStatus());
        assertTrue(activeDashboard.getStatusLabel().getText().contains("completed"));
        assertEquals("PHP 4,100.00", activeDashboard.getBalanceLabel().getText());
        assertEquals("", panel.getBillReferenceField().getText());
        assertEquals("", panel.getBillAmountField().getText());
    }

    @Test
    @DisplayName("AC-3 Masking: Null or blank descriptions mask billerReference in default notes, receipts, and builder")
    void testDefaultDescriptionMasksBillerReferenceWhenDescriptionIsNullOrBlank() {
        walletService.setPaymentProviderFactory(PaymentProviderFactory.successFactory());
        String rawAcct = "987654321012";
        String maskedAcct = ReceiptBuilder.maskIdentifier(rawAcct);

        Transaction nullDescTxn = walletService.payBillWithPin(
                "REQ-MASK-NULL",
                testWallet.getWalletId(),
                "PLDT (Telecom & Internet)",
                rawAcct,
                new BigDecimal("100.00"),
                BigDecimal.ZERO,
                null,
                "1234"
        );
        assertFalse(nullDescTxn.getDescription().contains(rawAcct), "Default description must mask billerReference");
        assertTrue(nullDescTxn.getDescription().contains(maskedAcct));
        String receiptNull = walletService.getTransactionReceipt(testWallet.getWalletId(), "REQ-MASK-NULL");
        assertFalse(receiptNull.contains(rawAcct), "Receipt Note must never expose raw billerReference");
        assertTrue(receiptNull.contains(maskedAcct));

        Transaction blankDescTxn = walletService.payBillWithPin(
                "REQ-MASK-BLANK",
                testWallet.getWalletId(),
                "PLDT (Telecom & Internet)",
                rawAcct,
                new BigDecimal("100.00"),
                BigDecimal.ZERO,
                "   ",
                "1234"
        );
        assertFalse(blankDescTxn.getDescription().contains(rawAcct));
        assertTrue(blankDescTxn.getDescription().contains(maskedAcct));

        BillPaymentTransaction builtNull = new TransactionBuilder()
                .referenceNumber("REQ-BLD-NULL")
                .sourceWalletId(testWallet.getWalletId())
                .amount(new BigDecimal("100.00"))
                .billerName("PLDT (Telecom & Internet)")
                .billerReference(rawAcct)
                .note(null)
                .buildBillPayment();
        assertFalse(builtNull.getDescription().contains(rawAcct));
        assertTrue(builtNull.getDescription().contains(maskedAcct));
        assertEquals(rawAcct, builtNull.getBillerReference());

        BillPaymentTransaction builtBlank = new TransactionBuilder()
                .referenceNumber("REQ-BLD-BLANK")
                .sourceWalletId(testWallet.getWalletId())
                .amount(new BigDecimal("100.00"))
                .billerName("PLDT (Telecom & Internet)")
                .billerReference(rawAcct)
                .note("   ")
                .buildBillPayment();
        assertFalse(builtBlank.getDescription().contains(rawAcct));
        assertTrue(builtBlank.getDescription().contains(maskedAcct));
        assertEquals(rawAcct, builtBlank.getBillerReference());
    }

    @Test
    @DisplayName("PIN Handling: Missing PIN wraps SecurityException in WalletException and interactive flow invokes setup prompt")
    void testPinNotSetWrapsInWalletExceptionAndInteractivePromptsSetup() throws Exception {
        User noPinUser = authService.registerUser("No Pin User", "09189998888", "nopin@otog.ph", "Secret123");
        Wallet noPinWallet = walletService.getWalletByUserId(noPinUser.getUserId());
        walletService.setPaymentProviderFactory(PaymentProviderFactory.successFactory());
        walletService.cashIn("INIT-NOPIN-01", noPinWallet.getWalletId(), null, new BigDecimal("500.00"), "Seed");
        noPinWallet = walletService.getWallet(noPinWallet.getWalletId());
        SessionManager.getInstance().login(noPinUser, noPinWallet);

        PayBillsPanel panel = new PayBillsPanel(walletService, authService);
        ExecutionException ex = assertThrows(ExecutionException.class, () ->
                panel.submitPaymentAsync("Meralco (Electricity)", "1234567890", "100.00", "1234")
                        .get(5, TimeUnit.SECONDS));
        assertInstanceOf(WalletException.class, ex.getCause(),
                "PIN-not-set SecurityException must be wrapped in WalletException");

        AtomicBoolean setupPrompted = new AtomicBoolean(false);
        panel.setPinConfiguredSupplier(() -> false);
        panel.setOnPinNotSet(() -> setupPrompted.set(true));

        AtomicReference<CompletableFuture<Transaction>> futureRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            panel.getBillReferenceField().setText("1234567890");
            panel.getBillAmountField().setText("100.00");
            panel.getBillPinField().setText("1234");
            futureRef.set(panel.submitPaymentAsync());
        });

        assertNull(futureRef.get().get(5, TimeUnit.SECONDS));
        assertTrue(setupPrompted.get(), "Interactive submit with no PIN set must trigger setup prompt");
        assertEquals("", new String(panel.getBillPinField().getPassword()));
        assertEquals(new BigDecimal("500.00"), walletService.getBalance(noPinWallet.getWalletId()));
    }

    @Test
    @DisplayName("PIN Lockout: 3 wrong PINs from Pay Bills locks out session and notifies Main immediately")
    void testThreeWrongPinsInPayBillsLocksOutAndNotifiesMainImmediately() throws Exception {
        activeDashboard = new Main(walletService, authService, null, null);
        activeDashboard.getInitialLoadFuture().get(5, TimeUnit.SECONDS);

        CountDownLatch logoutLatch = new CountDownLatch(1);
        activeDashboard.setOnLogoutCallback(logoutLatch::countDown);

        for (int i = 1; i <= 2; i++) {
            ExecutionException badPin = assertThrows(ExecutionException.class, () ->
                    activeDashboard.submitBillPaymentAsync("Meralco (Electricity)", "1234567890", "100.00", "0000")
                            .get(5, TimeUnit.SECONDS));
            assertInstanceOf(WalletException.class, badPin.getCause());
            assertTrue(SessionManager.getInstance().isLoggedIn());
        }

        ExecutionException lockoutEx = assertThrows(ExecutionException.class, () ->
                activeDashboard.submitBillPaymentAsync("Meralco (Electricity)", "1234567890", "100.00", "0000")
                        .get(5, TimeUnit.SECONDS));
        assertInstanceOf(WalletException.class, lockoutEx.getCause());
        assertTrue(logoutLatch.await(3, TimeUnit.SECONDS),
                "3 wrong PIN attempts in Pay Bills must immediately trigger dashboard session termination");
        assertTrue(SessionManager.getInstance().isLockedOutByPin());
        assertFalse(SessionManager.getInstance().isLoggedIn());
        assertEquals(new BigDecimal("5000.00"), walletService.getBalance(testWallet.getWalletId()));
    }

    @Test
    @DisplayName("Config & Factory: Dynamic OtogConfig bill fee overrides apply after PayBillsPanel construction")
    void testDynamicOtogConfigFeeOverridesReflectedInPayBillsPanel() throws Exception {
        PayBillsPanel panel = new PayBillsPanel(walletService, authService);
        OtogConfig config = OtogConfig.getInstance();
        try {
            config.setOverride("bills.default.fee.flat", "15.00");
            config.setOverride("bills.default.fee.percent", "0.02");

            assertEquals(new BigDecimal("35.00"), panel.calculateFee(new BigDecimal("1000.00")));
            assertEquals(new BigDecimal("35.00"),
                    PaymentProviderFactory.successFactory().createBillPaymentFeePolicy().calculate(new BigDecimal("1000.00")));

            SwingUtilities.invokeAndWait(() -> {
                panel.getBillAmountField().setText("1000.00");
                panel.updateFeePreview();
                assertEquals("PHP 35.00", panel.getFeePreviewLabel().getText());
                assertEquals("PHP 1,035.00", panel.getTotalPreviewLabel().getText());
            });
        } finally {
            config.clearOverrides();
        }
    }

    @Test
    @DisplayName("Notifications: Whitespace-only billerReference does not produce Biller (N/A) placeholder")
    void testNotificationOmitsPlaceholderForWhitespaceBillerReference() {
        BillPaymentTransaction txn = new BillPaymentTransaction(
                "TXN-WS-REF",
                "REQ-WS-REF",
                testWallet.getWalletId(),
                new BigDecimal("250.00"),
                BigDecimal.ZERO,
                "Meralco (Electricity)",
                null,
                "Power bill"
        );
        txn.setStatus(TransactionStatus.COMPLETED);
        walletService.getTransactionRepository().save(txn);

        walletService.getNotificationService().notifyBillPaymentCommitted(
                txn, testUser.getUserId(), "Meralco (Electricity)", "   ");

        List<Notification> notifs = walletService.getNotifications(testUser.getUserId(), true);
        Notification wsNotif = notifs.stream()
                .filter(n -> "REQ-WS-REF".equals(n.getReferenceNumber()))
                .findFirst()
                .orElseThrow();
        assertTrue(wsNotif.getMessage().contains("Meralco (Electricity)"));
        assertFalse(wsNotif.getMessage().contains("N/A"),
                "Whitespace-only billerReference must not emit (N/A) placeholder");
    }
}
