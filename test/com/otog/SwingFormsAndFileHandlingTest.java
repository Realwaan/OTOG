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
import com.otog.ui.LoanPanel;
import com.otog.ui.Main;
import com.otog.ui.MerchantQrPanel;
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
import java.awt.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
                Map.entry("com/otog/ui/LoanPanel.form", "com.otog.ui.LoanPanel"),
                Map.entry("com/otog/ui/InvestmentPanel.form", "com.otog.ui.InvestmentPanel"),
                Map.entry("com/otog/ui/MerchantQrPanel.form", "com.otog.ui.MerchantQrPanel"),
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
            assertNotNull(registerDialog.getRootPanel());
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
            assertNotNull(pinDialog.getRootPanel());
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
        assertNotNull(overviewPanel.getRootPanel());
        assertInstanceOf(GridLayoutManager.class, overviewPanel.getRootPanel().getLayout());
        assertNotNull(overviewPanel.getExportStatementButton());
        assertNotNull(overviewPanel.getLoadStatementButton());
        assertNotNull(overviewPanel.getExportReceiptButton());

        CashInOutPanel cashPanel = new CashInOutPanel(BankResponseStatus.SUCCESS);
        assertNotNull(cashPanel.getRootPanel());
        assertInstanceOf(GridLayoutManager.class, cashPanel.getRootPanel().getLayout());

        SendMoneyPanel sendPanel = new SendMoneyPanel();
        assertNotNull(sendPanel.getRootPanel());
        assertInstanceOf(GridLayoutManager.class, sendPanel.getRootPanel().getLayout());

        InvestmentPanel investmentPanel = new InvestmentPanel();
        assertNotNull(investmentPanel.getRootPanel());
        assertInstanceOf(GridLayoutManager.class, investmentPanel.getRootPanel().getLayout());
        assertNotNull(investmentPanel.getPreviewButton());
        assertNotNull(investmentPanel.getSubmitButton());
        assertNotNull(investmentPanel.getActivityTable());

        LoanPanel loanPanel = new LoanPanel();
        assertNotNull(loanPanel.getRootPanel());
        assertInstanceOf(GridLayoutManager.class, loanPanel.getRootPanel().getLayout());
        assertNotNull(loanPanel.getPrincipalField());
        assertNotNull(loanPanel.getPreviewLoanButton());
        assertNotNull(loanPanel.getApplyLoanButton());
        assertNotNull(loanPanel.getRepayLoanButton());
        assertNotNull(loanPanel.getUserLoansTable());
        assertNotNull(loanPanel.getAdminLoansTable());
        assertNotNull(loanPanel.getAdminApproveButton());
        assertNotNull(loanPanel.getAdminRejectButton());

        com.otog.ui.PayBillsPanel payBillsPanel = new com.otog.ui.PayBillsPanel();
        assertNotNull(payBillsPanel.getRootPanel());
        assertInstanceOf(GridLayoutManager.class, payBillsPanel.getRootPanel().getLayout());
        assertNotNull(payBillsPanel.getBillerComboBox());
        assertNotNull(payBillsPanel.getBillReferenceField());
        assertNotNull(payBillsPanel.getBillAmountField());
        assertNotNull(payBillsPanel.getBillPinField());
        assertNotNull(payBillsPanel.getSubmitBillPaymentButton());
        assertNotNull(payBillsPanel.getBillBackToOverviewButton());

        AdminControlsPanel adminPanel = new AdminControlsPanel(null, null, null, null);
        assertNotNull(adminPanel.getRootPanel());
        assertInstanceOf(GridLayoutManager.class, adminPanel.getRootPanel().getLayout());

        MerchantQrPanel merchantQrPanel = new MerchantQrPanel();
        assertNotNull(merchantQrPanel.getRootPanel());
        assertInstanceOf(GridLayoutManager.class, merchantQrPanel.getRootPanel().getLayout());
        assertNotNull(merchantQrPanel.getImportQrButton());
        assertNotNull(merchantQrPanel.getConfirmPayButton());
        assertNotNull(merchantQrPanel.getGenerateQrButton());
        assertNotNull(merchantQrPanel.getSaveQrButton());
        assertNotNull(merchantQrPanel.getSalesTableModel(),
                "MerchantQrPanel salesTableModel must be initialized outside $$$setupUI$$$()");
        assertSame(merchantQrPanel.getSalesTableModel(), merchantQrPanel.getMerchantSalesTable().getModel(),
                "tblMerchantSales must be wired to salesTableModel outside $$$setupUI$$$()");

        NotificationInboxDialog notifDialog = new NotificationInboxDialog(null, null, null, null, null);
        try {
            assertNotNull(notifDialog.getRootPanel());
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

        assertNotNull(activeDashboard.getRootPanel());
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
        assertNotNull(activeDashboard.getMerchantQrPanel());
        assertNotNull(adminPanel);

        assertSame(overview.getTransactionsTable(), activeDashboard.getRecentTransactionsTable());
        assertSame(overview.getFilterTypeComboBox(), activeDashboard.getFilterTypeComboBox());
        assertNotNull(overview.getFilterKeywordField());
        assertSame(overview.getFilterKeywordField(), activeDashboard.getFilterKeywordField());
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

    @Test
    void testIntelliJProjectConfigAndAllFormBindingsAreValid() throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);

        List<Path> ideaXmlFiles = List.of(
                Paths.get(".idea/OTOG.iml"),
                Paths.get(".idea/modules.xml"),
                Paths.get(".idea/misc.xml"),
                Paths.get(".idea/uiDesigner.xml"),
                Paths.get(".idea/encodings.xml"),
                Paths.get(".idea/libraries/lib.xml"),
                Paths.get(".idea/runConfigurations/OTOG_E_Wallet.xml"),
                Paths.get(".idea/runConfigurations/OTOG_All_Tests.xml")
        );
        for (Path xmlPath : ideaXmlFiles) {
            assertTrue(Files.isRegularFile(xmlPath), "Missing IntelliJ config file: " + xmlPath);
            Document doc = dbf.newDocumentBuilder().parse(xmlPath.toFile());
            assertNotNull(doc.getDocumentElement(), "Invalid XML root in " + xmlPath);
        }

        String iml = Files.readString(Paths.get(".idea/OTOG.iml"), StandardCharsets.UTF_8);
        assertTrue(iml.contains("file://$MODULE_DIR$/src"), "OTOG.iml must register src/");
        assertTrue(iml.contains("file://$MODULE_DIR$/test"), "OTOG.iml must register test/");
        assertTrue(iml.contains("file://$MODULE_DIR$/config"), "OTOG.iml must register config/ as resources");
        assertTrue(iml.contains("name=\"lib\""), "OTOG.iml must depend on lib project library");

        String appRun = Files.readString(Paths.get(".idea/runConfigurations/OTOG_E_Wallet.xml"), StandardCharsets.UTF_8);
        assertTrue(appRun.contains("value=\"UserLogin\""), "App run configuration must launch UserLogin");
        assertTrue(appRun.contains("module name=\"OTOG\""), "App run configuration must target OTOG module");

        String uiDesigner = Files.readString(Paths.get(".idea/uiDesigner.xml"), StandardCharsets.UTF_8);
        assertTrue(uiDesigner.contains("name=\"INSTRUMENT_CLASSES\" value=\"true\""),
                "uiDesigner.xml should use Binary class files instrumentation to avoid Form2SourceCompiler IndexNotReadyException");

        List<Path> formFiles = List.of(
                Paths.get("src/UserLogin.form"),
                Paths.get("src/com/otog/ui/RegisterDialog.form"),
                Paths.get("src/com/otog/ui/PinSettingsDialog.form"),
                Paths.get("src/com/otog/ui/Main.form"),
                Paths.get("src/com/otog/ui/OverviewPanel.form"),
                Paths.get("src/com/otog/ui/CashInOutPanel.form"),
                Paths.get("src/com/otog/ui/SendMoneyPanel.form"),
                Paths.get("src/com/otog/ui/PayBillsPanel.form"),
                Paths.get("src/com/otog/ui/LoanPanel.form"),
                Paths.get("src/com/otog/ui/InvestmentPanel.form"),
                Paths.get("src/com/otog/ui/MerchantQrPanel.form"),
                Paths.get("src/com/otog/ui/AdminControlsPanel.form"),
                Paths.get("src/com/otog/ui/NotificationInboxDialog.form")
        );

        for (Path formPath : formFiles) {
            Document doc = dbf.newDocumentBuilder().parse(formPath.toFile());
            String boundClassName = doc.getDocumentElement().getAttribute("bind-to-class");
            assertNotNull(boundClassName);
            Class<?> boundClass = Class.forName(boundClassName);

            NodeList allNodes = doc.getElementsByTagName("*");
            for (int i = 0; i < allNodes.getLength(); i++) {
                Element el = (Element) allNodes.item(i);
                String binding = el.getAttribute("binding");
                if (binding != null && !binding.isEmpty()) {
                    Field declaredField = assertDoesNotThrow(
                            () -> boundClass.getDeclaredField(binding),
                            formPath + " binding '" + binding + "' missing in " + boundClassName
                    );
                    assertNotNull(declaredField);
                }
            }
        }
    }

    @Test
    void testPanelsSurviveIntelliJGuiDesignerRegeneration() throws Exception {
        // Simulate IntelliJ Form2SourceCompiler/AsmCodeGenerator creating raw Swing widgets without custom models/items
        OverviewPanel overviewPanel = new OverviewPanel();
        setField(overviewPanel, "activityCardLayout", null);
        setField(overviewPanel, "transactionsTableModel", null);
        overviewPanel.getFilterTypeComboBox().removeAllItems();
        overviewPanel.getFilterStatusComboBox().removeAllItems();
        invokePrivate(overviewPanel, "ensureComponentsInitialized");
        assertNotNull(overviewPanel.getActivityCardLayout(), "OverviewPanel must recover CardLayout from activityCardPanel");
        assertNotNull(overviewPanel.getTransactionsTableModel(), "OverviewPanel must recreate transactionsTableModel");
        assertSame(overviewPanel.getTransactionsTableModel(), overviewPanel.getTransactionsTable().getModel());
        assertTrue(overviewPanel.getFilterTypeComboBox().getItemCount() > 1);
        assertTrue(overviewPanel.getFilterStatusComboBox().getItemCount() > 1);

        CashInOutPanel cashPanel = new CashInOutPanel(BankResponseStatus.SUCCESS);
        cashPanel.getCashOperationComboBox().removeAllItems();
        cashPanel.getPaymentMethodComboBox().removeAllItems();
        cashPanel.getCashChannelComboBox().removeAllItems();
        cashPanel.getProviderModeComboBox().removeAllItems();
        invokePrivate(cashPanel, "ensureComponentsInitialized");
        assertTrue(cashPanel.getCashOperationComboBox().getItemCount() > 0);
        assertTrue(cashPanel.getPaymentMethodComboBox().getItemCount() > 0);
        assertTrue(cashPanel.getCashChannelComboBox().getItemCount() > 0);
        assertEquals(BankResponseStatus.values().length, cashPanel.getProviderModeComboBox().getItemCount());

        AdminControlsPanel adminPanel = new AdminControlsPanel(null, null, null, null);
        setField(adminPanel, "userModel", null);
        setField(adminPanel, "auditModel", null);
        invokePrivate(adminPanel, "ensureComponentsInitialized");
        assertNotNull(getField(adminPanel, "userModel"), "AdminControlsPanel must recreate userModel");
        assertNotNull(getField(adminPanel, "auditModel"), "AdminControlsPanel must recreate auditModel");
        assertSame(getField(adminPanel, "userModel"), adminPanel.getUserTable().getModel());
        assertSame(getField(adminPanel, "auditModel"), adminPanel.getAuditTable().getModel());

        LoanPanel loanPanel = new LoanPanel();
        setField(loanPanel, "userLoansTableModel", null);
        setField(loanPanel, "adminLoansTableModel", null);
        invokePrivate(loanPanel, "ensureComponentsInitialized");
        assertNotNull(loanPanel.getUserLoansTableModel(), "LoanPanel must recreate userLoansTableModel");
        assertNotNull(loanPanel.getAdminLoansTableModel(), "LoanPanel must recreate adminLoansTableModel");
        assertSame(loanPanel.getUserLoansTableModel(), loanPanel.getUserLoansTable().getModel());
        assertSame(loanPanel.getAdminLoansTableModel(), loanPanel.getAdminLoansTable().getModel());

        NotificationInboxDialog notifDialog = new NotificationInboxDialog(null, null, null, null, null);
        try {
            setField(notifDialog, "notificationsTableModel", null);
            invokePrivate(notifDialog, "ensureComponentsInitialized");
            assertNotNull(getField(notifDialog, "notificationsTableModel"),
                    "NotificationInboxDialog must recreate notificationsTableModel");
            assertSame(getField(notifDialog, "notificationsTableModel"), notifDialog.getNotificationsTable().getModel());
        } finally {
            notifDialog.dispose();
        }
    }

    @Test
    void testResponsiveLayoutsNeverClipButtonsOrInputsAcrossWindowSizes() throws Exception {
        User admin = walletService.getUserRepository().findById("USR-ADMIN-01").orElseThrow();
        Wallet adminWallet = walletService.getWalletByUserId(admin.getUserId());
        walletService.cashIn("REQ-UI-LAYOUT-01", adminWallet.getWalletId(), null, new BigDecimal("250.00"), "Layout check");
        SessionManager.getInstance().login(admin, walletService.getWalletByUserId(admin.getUserId()));

        activeDashboard = new Main(walletService, authService, walletService, walletService);
        activeDashboard.getInitialLoadFuture().get(5, TimeUnit.SECONDS);

        Dimension[] testSizes = new Dimension[]{
                new Dimension(1020, 680),
                new Dimension(940, 640),
                new Dimension(720, 520)
        };

        SwingUtilities.invokeAndWait(activeDashboard::addNotify);
        for (Dimension size : testSizes) {
            SwingUtilities.invokeAndWait(() -> {
                activeDashboard.setSize(size);
                activeDashboard.revalidate();
                activeDashboard.validate();
            });
            // Flush queued WrapLayout revalidations
            SwingUtilities.invokeAndWait(activeDashboard::validate);
            SwingUtilities.invokeAndWait(activeDashboard::validate);

            SwingUtilities.invokeAndWait(() -> {
                Container contentPane = activeDashboard.getContentPane();
                int viewportW = contentPane.getWidth();
                int viewportH = contentPane.getHeight();
                assertTrue(viewportW > 0 && viewportH > 0, "Content pane must have positive dimensions");

                // All 5 header buttons (including Logout) must fit fully inside header and window bounds
                Component headerPanel = activeDashboard.getRootPanel().getComponent(0);
                assertAllVisibleControlsFitHorizontally(headerPanel, contentPane, "Main header @" + size.width + "x" + size.height);
                for (JButton headerBtn : List.of(
                        activeDashboard.getBackButton(),
                        activeDashboard.getRefreshButton(),
                        activeDashboard.getNotificationsButton(),
                        activeDashboard.getPinSettingsButton()
                )) {
                    assertVisibleWithinContainerWidth(headerBtn, contentPane, "Main header @" + size.width + "x" + size.height);
                }

                // Every tab must fit buttons & inputs horizontally without clipping
                JTabbedPane tabs = activeDashboard.getTabs();
                for (int i = 0; i < tabs.getTabCount(); i++) {
                    tabs.setSelectedIndex(i);
                    activeDashboard.validate();
                }
            });

            // Second pass after tab switching settles WrapLayout
            for (int tabIdx = 0; tabIdx < activeDashboard.getTabs().getTabCount(); tabIdx++) {
                final int idx = tabIdx;
                SwingUtilities.invokeAndWait(() -> {
                    activeDashboard.getTabs().setSelectedIndex(idx);
                    activeDashboard.validate();
                });
                SwingUtilities.invokeAndWait(activeDashboard::validate);
                SwingUtilities.invokeAndWait(() -> {
                    Container contentPane = activeDashboard.getContentPane();
                    Component tabComp = activeDashboard.getTabs().getComponentAt(idx);
                    String tabTitle = activeDashboard.getTabs().getTitleAt(idx);
                    assertAllVisibleControlsFitHorizontally(
                            tabComp, contentPane, tabTitle + " @" + size.width + "x" + size.height);
                    if (tabComp instanceof MerchantQrPanel qrPanel && qrPanel.getTabs().getTabCount() > 1) {
                        qrPanel.getTabs().setSelectedIndex(1);
                        activeDashboard.validate();
                        assertAllVisibleControlsFitHorizontally(
                                tabComp, contentPane, tabTitle + " (Merchant Subtab) @" + size.width + "x" + size.height);
                        qrPanel.getTabs().setSelectedIndex(0);
                    }
                    if (tabComp instanceof LoanPanel lp && lp.getTabs().getTabCount() > 1) {
                        lp.getTabs().setSelectedIndex(1);
                        activeDashboard.validate();
                        assertAllVisibleControlsFitHorizontally(
                                tabComp, contentPane, tabTitle + " (Admin Loan Approvals Subtab) @" + size.width + "x" + size.height);
                        lp.getTabs().setSelectedIndex(0);
                    }
                });
            }
        }

        // Verify dialogs are resizable and do not clip their action buttons
        SwingUtilities.invokeAndWait(() -> {
            RegisterDialog reg = new RegisterDialog(null, authService);
            try {
                assertTrue(reg.isResizable(), "RegisterDialog should be resizable");
                reg.getContentPane().setSize(reg.getSize());
                layoutRecursively(reg.getContentPane());
                assertVisibleWithinContainerWidth(reg.getCancelButton(), reg.getContentPane(), "RegisterDialog");
                assertVisibleWithinContainerWidth(reg.getSubmitButton(), reg.getContentPane(), "RegisterDialog");
            } finally {
                reg.dispose();
            }

            PinSettingsDialog pin = new PinSettingsDialog(null, authService, admin.getUserId(), true, null, null, null);
            try {
                assertTrue(pin.isResizable(), "PinSettingsDialog should be resizable");
                pin.getContentPane().setSize(pin.getSize());
                layoutRecursively(pin.getContentPane());
                assertVisibleWithinContainerWidth(pin.getCancelButton(), pin.getContentPane(), "PinSettingsDialog");
                assertVisibleWithinContainerWidth(pin.getSavePinButton(), pin.getContentPane(), "PinSettingsDialog");
            } finally {
                pin.dispose();
            }

            NotificationInboxDialog notif = new NotificationInboxDialog(null, null, null, null, null);
            try {
                assertTrue(notif.isResizable(), "NotificationInboxDialog should be resizable");
                Dimension compact = new Dimension(520, 400);
                notif.setSize(compact);
                notif.getContentPane().setSize(compact);
                layoutRecursively(notif.getContentPane());
                layoutRecursively(notif.getContentPane());
                assertAllVisibleControlsFitHorizontally(notif.getContentPane(), notif.getContentPane(), "NotificationInboxDialog @520x400");
            } finally {
                notif.dispose();
            }
        });
    }

    private static void layoutRecursively(Component comp) {
        if (comp instanceof Container container) {
            container.doLayout();
            for (Component child : container.getComponents()) {
                if (child.isVisible()) {
                    layoutRecursively(child);
                }
            }
            container.doLayout();
        }
    }

    private static void assertAllVisibleControlsFitHorizontally(Component root, Container boundsRoot, String context) {
        if (root == null || !root.isVisible()) {
            return;
        }
        if (root instanceof JButton || root instanceof JTextField || root instanceof JComboBox<?>) {
            assertVisibleWithinContainerWidth(root, boundsRoot, context);
            return;
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                if (!child.isVisible()) {
                    continue;
                }
                if (root instanceof JTabbedPane tp && child != tp.getSelectedComponent()) {
                    continue;
                }
                assertAllVisibleControlsFitHorizontally(child, boundsRoot, context);
            }
        }
    }

    private static void assertVisibleWithinContainerWidth(Component comp, Container boundsRoot, String context) {
        if (comp == null || !comp.isVisible()) {
            return;
        }
        assertTrue(comp.getWidth() > 0 && comp.getHeight() > 0,
                context + ": component has zero size: " + describe(comp));
        Point pt = SwingUtilities.convertPoint(comp.getParent(), comp.getX(), comp.getY(), boundsRoot);
        int rightEdge = pt.x + comp.getWidth();
        int maxWidth = boundsRoot.getWidth();
        assertTrue(pt.x >= -1, context + ": component clipped on left (" + pt.x + "): " + describe(comp));
        assertTrue(rightEdge <= maxWidth + 2,
                context + ": component clipped on right (" + rightEdge + " > " + maxWidth + "): " + describe(comp));
        if (comp.getParent() != null) {
            int parentH = comp.getParent().getHeight();
            int bottomEdge = comp.getY() + comp.getHeight();
            assertTrue(bottomEdge <= parentH + 2,
                    context + ": component clipped vertically inside parent (" + bottomEdge + " > " + parentH + "): " + describe(comp));
        }
        if (SwingUtilities.getAncestorOfClass(JScrollPane.class, comp) == null) {
            int bottomInWindow = pt.y + comp.getHeight();
            int maxHeight = boundsRoot.getHeight();
            assertTrue(bottomInWindow <= maxHeight + 2,
                    context + ": non-scrollable component clipped at bottom (" + bottomInWindow + " > " + maxHeight + "): " + describe(comp));
        }
    }

    private static String describe(Component comp) {
        if (comp instanceof AbstractButton btn) {
            return comp.getClass().getSimpleName() + "['" + btn.getText() + "']";
        }
        return comp.getClass().getSimpleName() + "[" + comp.getWidth() + "x" + comp.getHeight() + "]";
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Object getField(Object target, String fieldName) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        return f.get(target);
    }

    private static void invokePrivate(Object target, String methodName) throws Exception {
        Method m = target.getClass().getDeclaredMethod(methodName);
        m.setAccessible(true);
        m.invoke(target);
    }
}
