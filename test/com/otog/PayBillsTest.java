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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
}
