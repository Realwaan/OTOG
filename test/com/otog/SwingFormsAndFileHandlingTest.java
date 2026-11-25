package com.otog;

import com.intellij.uiDesigner.core.GridLayoutManager;
import com.otog.database.DatabaseManager;
import com.otog.exception.OtogException;
import com.otog.model.Transaction;
import com.otog.model.User;
import com.otog.model.Wallet;
import com.otog.repository.CredentialRepository;
import com.otog.service.AuthServiceImpl;
import com.otog.service.BankResponseStatus;
import com.otog.service.SessionManager;
import com.otog.service.StatementFileService;
import com.otog.service.WalletService;
import com.otog.ui.AdminControlsPanel;
import com.otog.ui.CashInOutPanel;
import com.otog.ui.InvestmentPanel;
import com.otog.ui.Main;
import com.otog.ui.NotificationInboxDialog;
import com.otog.ui.OverviewPanel;
import com.otog.ui.PinSettingsDialog;
import com.otog.ui.RegisterDialog;
import com.otog.ui.SendMoneyPanel;
import com.otog.util.OtogConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies CAP-19, CAP-20, and CAP-21:
 * - All Swing screens, dialogs, and dashboard panels have bound IntelliJ GUI Designer .form files
 *   and initialize cleanly via $$$setupUI$$$() / $$$getRootComponent$$$() with GridLayoutManager.
 * - StatementFileService stores and loads wallet statements and transaction receipts via
 *   BufferedWriter and BufferedReader with identifier masking and corruption validation.
 */
public class SwingFormsAndFileHandlingTest {

    private DatabaseManager databaseManager;
    private WalletService walletService;
    private AuthServiceImpl authService;
    private Main activeDashboard;

    @BeforeEach
    void setUp() {
        databaseManager = new DatabaseManager("jdbc:sqlite::memory:");
        authService = new AuthServiceImpl(databaseManager);
        walletService = new WalletService(databaseManager, authService);
        SessionManager.getInstance().reset();
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
    void testAllSwingFormFilesExistAndBindToMatchingClasses() throws Exception {
        Map<String, String> expectedForms = Map.ofEntries(
                Map.entry("UserLogin.form", "UserLogin"),
                Map.entry("com/otog/ui/RegisterDialog.form", "com.otog.ui.RegisterDialog"),
                Map.entry("com/otog/ui/PinSettingsDialog.form", "com.otog.ui.PinSettingsDialog"),
                Map.entry("com/otog/ui/Main.form", "com.otog.ui.Main"),
                Map.entry("com/otog/ui/OverviewPanel.form", "com.otog.ui.OverviewPanel"),
                Map.entry("com/otog/ui/CashInOutPanel.form", "com.otog.ui.CashInOutPanel"),
                Map.entry("com/otog/ui/SendMoneyPanel.form", "com.otog.ui.SendMoneyPanel"),
                Map.entry("com/otog/ui/PayBillsPanel.form", "com.otog.ui.PayBillsPanel"),
                Map.entry("com/otog/ui/InvestmentPanel.form", "com.otog.ui.InvestmentPanel"),
                Map.entry("com/otog/ui/AdminControlsPanel.form", "com.otog.ui.AdminControlsPanel"),
                Map.entry("com/otog/ui/NotificationInboxDialog.form", "com.otog.ui.NotificationInboxDialog")
        );

        for (Map.Entry<String, String> entry : expectedForms.entrySet()) {
            Path srcForm = Paths.get("src", entry.getKey());
            assertTrue(Files.isRegularFile(srcForm), "Missing source .form file: " + srcForm);
            String xml = Files.readString(srcForm, StandardCharsets.UTF_8);
            assertTrue(xml.contains("http://www.intellij.com/uidesigner/form/"),
                    srcForm + " must declare IntelliJ UI Designer XML namespace");
            assertTrue(xml.contains("bind-to-class=\"" + entry.getValue() + "\""),
                    srcForm + " must bind to " + entry.getValue());
            assertTrue(xml.contains("layout-manager=\"GridLayoutManager\""),
                    srcForm + " must use GridLayoutManager");
        }
    }

    @Test
    void testRegisterPinAndTabFormPanelsInitializeWithGridLayoutManager() {
        RegisterDialog registerDialog = new RegisterDialog(null, authService);
        try {
            assertNotNull(registerDialog.$$$getRootComponent$$$());
            assertInstanceOf(GridLayoutManager.class, registerDialog.getRootPanel().getLayout());
            assertNotNull(registerDialog.getNameField());
            assertNotNull(registerDialog.getMobileField());
            assertNotNull(registerDialog.getEmailField());
            assertNotNull(registerDialog.getPasswordField());
            assertNotNull(registerDialog.getConfirmPasswordField());
            assertNotNull(registerDialog.getSubmitButton());
            assertNotNull(registerDialog.getCancelButton());
        } finally {
            registerDialog.dispose();
        }

        PinSettingsDialog pinDialog = new PinSettingsDialog(
                null, authService, "USR-TEST", false, null, null, null);
        try {
            assertNotNull(pinDialog.$$$getRootComponent$$$());
            assertInstanceOf(GridLayoutManager.class, pinDialog.getRootPanel().getLayout());
            assertNotNull(pinDialog.getSecretField());
            assertNotNull(pinDialog.getNewPinField());
            assertNotNull(pinDialog.getConfirmPinField());
            assertNotNull(pinDialog.getSavePinButton());
            assertNotNull(pinDialog.getCancelButton());
        } finally {
            pinDialog.dispose();
        }

        OverviewPanel overviewPanel = new OverviewPanel();
        assertNotNull(overviewPanel.$$$getRootComponent$$$());
        assertInstanceOf(GridLayoutManager.class, overviewPanel.getRootPanel().getLayout());
        assertNotNull(overviewPanel.getExportStatementButton());
        assertNotNull(overviewPanel.getLoadStatementButton());
        assertNotNull(overviewPanel.getExportReceiptButton());

        CashInOutPanel cashPanel = new CashInOutPanel(BankResponseStatus.SUCCESS);
        assertNotNull(cashPanel.$$$getRootComponent$$$());
        assertInstanceOf(GridLayoutManager.class, cashPanel.getRootPanel().getLayout());

        SendMoneyPanel sendPanel = new SendMoneyPanel();
        assertNotNull(sendPanel.$$$getRootComponent$$$());
        assertInstanceOf(GridLayoutManager.class, sendPanel.getRootPanel().getLayout());

        InvestmentPanel investmentPanel = new InvestmentPanel();
        assertNotNull(investmentPanel.$$$getRootComponent$$$());
        assertInstanceOf(GridLayoutManager.class, investmentPanel.getRootPanel().getLayout());
        assertNotNull(investmentPanel.getPreviewButton());
        assertNotNull(investmentPanel.getSubmitButton());
        assertNotNull(investmentPanel.getActivityTable());

        com.otog.ui.PayBillsPanel payBillsPanel = new com.otog.ui.PayBillsPanel();
        assertNotNull(payBillsPanel.$$$getRootComponent$$$());
        assertInstanceOf(GridLayoutManager.class, payBillsPanel.getRootPanel().getLayout());
        assertNotNull(payBillsPanel.getBillerComboBox());
        assertNotNull(payBillsPanel.getBillReferenceField());
        assertNotNull(payBillsPanel.getBillAmountField());
        assertNotNull(payBillsPanel.getBillPinField());
        assertNotNull(payBillsPanel.getSubmitBillPaymentButton());
        assertNotNull(payBillsPanel.getBillBackToOverviewButton());

        AdminControlsPanel adminPanel = new AdminControlsPanel(null, null, null, null);
        assertNotNull(adminPanel.$$$getRootComponent$$$());
        assertInstanceOf(GridLayoutManager.class, adminPanel.getRootPanel().getLayout());

        NotificationInboxDialog notifDialog = new NotificationInboxDialog(null, null, null, null, null);
        try {
            assertNotNull(notifDialog.$$$getRootComponent$$$());
            assertInstanceOf(GridLayoutManager.class, notifDialog.getRootPanel().getLayout());
            assertNotNull(notifDialog.getNotificationsTable());
            assertNotNull(notifDialog.getMarkReadButton());
            assertNotNull(notifDialog.getMarkUnreadButton());
            assertNotNull(notifDialog.getMarkAllReadButton());
            assertNotNull(notifDialog.getViewLinkedReceiptButton());
        } finally {
            notifDialog.dispose();
        }
    }

    @Test
    void testStatementFileServiceStoreAndLoadRoundTripAndMasking(@TempDir Path tempDir) throws Exception {
        User sender = authService.registerUser("Juan Dela Cruz", "09171234567", "juan@otog.ph", "SecretPass123");
        User recipient = authService.registerUser("Maria Santos", "09187654321", "maria@otog.ph", "SecretPass123");

        Wallet senderWallet = walletService.getWalletByUserId(sender.getUserId());
        Wallet recipientWallet = walletService.getWalletByUserId(recipient.getUserId());

        SessionManager.getInstance().login(sender, senderWallet);
        authService.setPin(sender.getUserId(), "SecretPass123", "1234");
        walletService.cashIn("REQ-FILE-01", senderWallet.getWalletId(), null, new BigDecimal("1500.00"), "Initial Deposit");
        SessionManager.getInstance().login(sender, walletService.getWalletByUserId(sender.getUserId()));
        walletService.transferWithPin("REQ-FILE-02", senderWallet.getWalletId(), recipientWallet.getWalletId(),
                new BigDecimal("250.50"), BigDecimal.ZERO, "Lunch split", "1234");

        Wallet updatedWallet = walletService.getWalletByUserId(sender.getUserId());
        List<Transaction> txns = walletService.getRecentTransactions(senderWallet.getWalletId(), 20);
        assertEquals(2, txns.size());

        StatementFileService fileService = new StatementFileService();
        Path statementPath = tempDir.resolve("juan-statement.txt");

        // Store statement to disk
        Path savedPath = fileService.exportWalletStatement(sender, updatedWallet, txns, statementPath);
        assertTrue(Files.isRegularFile(savedPath));

        String rawContent = Files.readString(savedPath, StandardCharsets.UTF_8);
        assertTrue(rawContent.startsWith(StatementFileService.STATEMENT_HEADER));
        assertFalse(rawContent.contains("09171234567"), "Raw mobile number must be masked in exported statement");
        assertFalse(rawContent.contains(senderWallet.getWalletId()), "Raw wallet ID must be masked in exported statement");

        // Load statement back into program
        StatementFileService.LoadedStatement loaded = fileService.importWalletStatement(savedPath);
        assertEquals(StatementFileService.STATEMENT_HEADER, loaded.getVersion());
        assertEquals(new BigDecimal("1249.50"), loaded.getBalance());
        assertEquals("PHP", loaded.getCurrency());
        assertEquals(2, loaded.getEntries().size());
        assertTrue(loaded.toDisplaySummary().contains("OTOG E-WALLET LOADED STATEMENT"));

        // Store and load single receipt
        Path receiptPath = tempDir.resolve("receipt.txt");
        fileService.exportReceipt(txns.get(0), receiptPath);
        String loadedReceipt = fileService.importReceipt(receiptPath);
        assertTrue(loadedReceipt.contains(txns.get(0).getReferenceNumber()));
    }

    @Test
    void testStatementFileServiceRejectsCorruptedOrMissingFiles(@TempDir Path tempDir) throws Exception {
        StatementFileService fileService = new StatementFileService();

        assertThrows(OtogException.class,
                () -> fileService.importWalletStatement(tempDir.resolve("nonexistent.txt")));

        Path badHeader = tempDir.resolve("bad-header.txt");
        Files.writeString(badHeader, "NOT-AN-OTOG-FILE\nBALANCE=100.00\n", StandardCharsets.UTF_8);
        assertThrows(OtogException.class, () -> fileService.importWalletStatement(badHeader));

        Path countMismatch = tempDir.resolve("mismatch.txt");
        Files.writeString(countMismatch,
                StatementFileService.STATEMENT_HEADER + "\n"
                        + "BALANCE=100.00\n"
                        + "COUNT=3\n"
                        + "---TRANSACTIONS---\n",
                StandardCharsets.UTF_8);
        assertThrows(OtogException.class, () -> fileService.importWalletStatement(countMismatch));
    }

    @Test
    void testMainDashboardAsyncStatementExportAndLoad(@TempDir Path tempDir) throws Exception {
        User user = authService.registerUser("Carlo Reyes", "09191112233", "carlo@otog.ph", "SecretPass123");
        Wallet wallet = walletService.getWalletByUserId(user.getUserId());
        walletService.cashIn("REQ-DASH-FILE-01", wallet.getWalletId(), null, new BigDecimal("500.00"), "Top-up");
        SessionManager.getInstance().login(user, walletService.getWalletByUserId(user.getUserId()));

        activeDashboard = new Main(walletService, authService);
        activeDashboard.getInitialLoadFuture().get(5, TimeUnit.SECONDS);

        assertNotNull(activeDashboard.$$$getRootComponent$$$());
        assertInstanceOf(GridLayoutManager.class, activeDashboard.getRootPanel().getLayout());

        Path exportedFile = tempDir.resolve("dashboard-statement.txt");
        Path resultPath = activeDashboard.exportStatementAsync(exportedFile).get(5, TimeUnit.SECONDS);
        assertEquals(exportedFile, resultPath);
        assertTrue(Files.isRegularFile(exportedFile));

        StatementFileService.LoadedStatement loaded =
                activeDashboard.importStatementAsync(exportedFile).get(5, TimeUnit.SECONDS);
        assertEquals(new BigDecimal("500.00"), loaded.getBalance());
        assertEquals(1, loaded.getEntries().size());

        SwingUtilities.invokeAndWait(() -> activeDashboard.getRecentTransactionsTable().setRowSelectionInterval(0, 0));
        Path receiptFile = tempDir.resolve("dashboard-receipt.txt");
        Path savedReceipt = activeDashboard.exportSelectedReceiptAsync(receiptFile).get(5, TimeUnit.SECONDS);
        assertEquals(receiptFile, savedReceipt);
        assertTrue(Files.isRegularFile(receiptFile));
    }

    @Test
    void testDashboardModularPanelDelegationAndConsistentVisualStyling() throws Exception {
        User admin = walletService.getUserRepository().findById("USR-ADMIN-01").orElseThrow();
        Wallet adminWallet = walletService.getWalletByUserId(admin.getUserId());
        SessionManager.getInstance().login(admin, adminWallet);

        activeDashboard = new Main(walletService, authService);
        activeDashboard.getInitialLoadFuture().get(5, TimeUnit.SECONDS);

        OverviewPanel overview = activeDashboard.getOverviewPanel();
        CashInOutPanel cash = activeDashboard.getCashInOutPanel();
        SendMoneyPanel send = activeDashboard.getSendMoneyPanel();
        AdminControlsPanel adminPanel = activeDashboard.getAdminControlsPanel();

        assertNotNull(overview);
        assertNotNull(cash);
        assertNotNull(send);
        assertNotNull(activeDashboard.getPayBillsPanel());
        assertNotNull(adminPanel);

        assertSame(overview.getTransactionsTable(), activeDashboard.getRecentTransactionsTable());
        assertSame(overview.getFilterTypeComboBox(), activeDashboard.getFilterTypeComboBox());
        assertSame(overview.getActivityFeedbackLabel(), activeDashboard.getActivityFeedbackLabel());
        assertSame(cash.getCashOperationComboBox(), activeDashboard.getCashOperationComboBox());
        assertSame(cash.getCashFeedbackLabel(), activeDashboard.getCashFeedbackLabel());
        assertSame(send.getRecipientField(), activeDashboard.getRecipientField());
        assertSame(send.getSubmitSendMoneyButton(), activeDashboard.getSubmitSendMoneyButton());
        assertSame(send.getSendFeedbackLabel(), activeDashboard.getSendFeedbackLabel());

        int expectedRowHeight = OtogConfig.getInstance().getTableRowHeight();
        for (JTable table : List.of(
                overview.getTransactionsTable(),
                adminPanel.getUserTable(),
                adminPanel.getAuditTable()
        )) {
            assertEquals(expectedRowHeight, table.getRowHeight());
            assertFalse(table.getTableHeader().getReorderingAllowed());
            assertEquals(ListSelectionModel.SINGLE_SELECTION, table.getSelectionModel().getSelectionMode());
            assertTrue(table.getFillsViewportHeight());
            assertTrue(table.getTableHeader().getFont().isBold());
        }

        List<String> mainLines = Files.readAllLines(Paths.get("src/com/otog/ui/Main.java"), StandardCharsets.UTF_8);
        assertTrue(mainLines.size() < 1500,
                "Main.java must stay modularized below 1500 lines (was " + mainLines.size() + ")");
    }

    @Test
    void testModularCashAndSendPanelsExecuteOffEdtIndependently() throws Exception {
        User sender = authService.registerUser("Panel Sender", "09172223344", "psender@otog.ph", "SecretPass123");
        User recipient = authService.registerUser("Panel Receiver", "09172223355", "precv@otog.ph", "SecretPass123");
        Wallet senderWallet = walletService.getWalletByUserId(sender.getUserId());
        SessionManager.getInstance().login(sender, senderWallet);
        authService.setPin(sender.getUserId(), "SecretPass123", "1234");

        CashInOutPanel cashPanel = new CashInOutPanel(walletService, authService, BankResponseStatus.SUCCESS);
        Transaction cashInTxn = cashPanel.submitCashOperationAsync(
                "CASH_IN", "GCash", null, "400.00", "Modular top-up", null)
                .get(5, TimeUnit.SECONDS);
        assertNotNull(cashInTxn);
        assertTrue(cashPanel.isLastOperationExecutedOffEdt(), "CashInOutPanel must execute off EDT");
        assertEquals(new BigDecimal("400.00"), walletService.getBalance(senderWallet.getWalletId()));
        assertTrue(cashPanel.getCashFeedbackLabel().getText().contains("successful"));

        SendMoneyPanel sendPanel = new SendMoneyPanel(walletService, authService);
        Transaction sendTxn = sendPanel.submitSendMoneyAsync(
                recipient.getMobileNumber(), "125.00", "Modular send", "1234")
                .get(5, TimeUnit.SECONDS);
        assertNotNull(sendTxn);
        assertTrue(sendPanel.isLastOperationExecutedOffEdt(), "SendMoneyPanel must execute off EDT");
        assertEquals(new BigDecimal("275.00"), walletService.getBalance(senderWallet.getWalletId()));
        assertEquals(new BigDecimal("125.00"), walletService.getWalletByUserId(recipient.getUserId()).getBalance());
        assertTrue(sendPanel.getSendFeedbackLabel().getText().contains("Transfer successful"));
    }

    @Test
    void testModularQaFixesFormParityFlyweightRendererRepeatDraftAndFeeForwarding() throws Exception {
        // 1. Every .java file in src/com/otog/ui/ must have a matching .form file (0 structure warnings)
        try (var stream = Files.list(Paths.get("src/com/otog/ui"))) {
            List<Path> uiJavaFiles = stream
                    .filter(p -> p.toString().endsWith(".java"))
                    .toList();
            assertFalse(uiJavaFiles.isEmpty());
            for (Path javaFile : uiJavaFiles) {
                String formPath = javaFile.toString().replaceAll("\\.java$", ".form");
                assertTrue(Files.isRegularFile(Paths.get(formPath)),
                        "Every class in com.otog.ui must have a matching .form file: missing " + formPath);
            }
        }

        // 2. UiTheme.alignTableColumnsRight reuses shared Flyweight renderer instance
        JTable t1 = new JTable(new Object[][]{{"10.00"}}, new Object[]{"Amount"});
        JTable t2 = new JTable(new Object[][]{{"20.00"}}, new Object[]{"Amount"});
        com.otog.util.UiTheme.alignTableColumnsRight(t1, 0);
        com.otog.util.UiTheme.alignTableColumnsRight(t2, 0);
        assertSame(t1.getColumnModel().getColumn(0).getCellRenderer(),
                t2.getColumnModel().getColumn(0).getCellRenderer(),
                "UiTheme.alignTableColumnsRight must share a cached Flyweight renderer");

        // 3. CashInOutPanel.loadRepeatDraft resets cmbPaymentMethod to index 0 and re-enables cmbCashChannel
        User user = authService.registerUser("Draft Tester", "09173334455", "draft@otog.ph", "SecretPass123");
        Wallet userWallet = walletService.getWalletByUserId(user.getUserId());
        SessionManager.getInstance().login(user, userWallet);
        authService.setPin(user.getUserId(), "SecretPass123", "1234");
        walletService.cashIn("REQ-QA17-SEED", userWallet.getWalletId(), null, new BigDecimal("500.00"), "Seed");

        CashInOutPanel cashPanel = new CashInOutPanel(walletService, authService, BankResponseStatus.SUCCESS);
        cashPanel.populatePaymentMethods(walletService.getPaymentMethodsByUserId(user.getUserId()));
        assertTrue(cashPanel.getPaymentMethodComboBox().getSelectedIndex() > 0, "Default bank should be preselected");
        assertFalse(cashPanel.getCashChannelComboBox().isEnabled(), "Channel combo disabled when bank selected");

        com.otog.model.CashInOutTransaction channelDraft = new com.otog.model.CashInOutTransaction(
                "TXN-DRAFT", "REF-DRAFT-1", userWallet.getWalletId(),
                new BigDecimal("100.00"), BigDecimal.ZERO,
                "CASH_IN", "GCash");
        cashPanel.loadRepeatDraft(channelDraft);
        assertEquals(0, cashPanel.getPaymentMethodComboBox().getSelectedIndex(),
                "loadRepeatDraft with explicit channel must reset linked payment method to index 0");
        assertTrue(cashPanel.getCashChannelComboBox().isEnabled(),
                "loadRepeatDraft with explicit channel must re-enable channel dropdown");
        assertEquals("GCash", cashPanel.getCashChannelComboBox().getSelectedItem());

        // 4. SendMoneyPanel forwards configured CompositeFeePolicy fee instead of hardcoding zero
        User receiver = authService.registerUser("Fee Receiver", "09173334466", "feerecv@otog.ph", "SecretPass123");
        System.setProperty("otog.send.default.fee.flat", "5.00");
        OtogConfig.getInstance().reload();
        try {
            SendMoneyPanel sendPanel = new SendMoneyPanel(walletService, authService);
            Transaction feeTxn = sendPanel.submitSendMoneyAsync(
                    receiver.getMobileNumber(), "100.00", "With configured fee", "1234")
                    .get(5, TimeUnit.SECONDS);
            assertEquals(0, new BigDecimal("5.00").compareTo(feeTxn.getFee()),
                    "SendMoneyPanel must apply configured transfer fee");
            assertEquals(0, new BigDecimal("395.00").compareTo(walletService.getBalance(userWallet.getWalletId())));
        } finally {
            System.clearProperty("otog.send.default.fee.flat");
            OtogConfig.getInstance().reload();
        }

        // 5. OverviewPanel guards Clear Filters / Receipt / Lookup / Export Receipt with beforeInteractiveAction
        OverviewPanel overviewPanel = new OverviewPanel(walletService, new StatementFileService());
        java.util.concurrent.atomic.AtomicInteger sessionChecks = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger refreshCalls = new java.util.concurrent.atomic.AtomicInteger();
        overviewPanel.setBeforeInteractiveAction(() -> {
            sessionChecks.incrementAndGet();
            return false; // simulate expired session
        });
        overviewPanel.setOnRefreshRequested(() -> {
            refreshCalls.incrementAndGet();
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        });
        SwingUtilities.invokeAndWait(() -> {
            overviewPanel.getClearFiltersButton().doClick();
            overviewPanel.getViewReceiptButton().doClick();
            overviewPanel.getExportReceiptButton().doClick();
        });
        assertEquals(3, sessionChecks.get(), "All OverviewPanel interactive buttons must check active session");
        assertEquals(0, refreshCalls.get(), "Expired session must block Clear Filters refresh");
    }
}
