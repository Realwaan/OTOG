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
import com.otog.ui.Main;
import com.otog.ui.MerchantQrPanel;
import com.otog.ui.NotificationInboxDialog;
import com.otog.ui.OverviewPanel;
import com.otog.ui.PinSettingsDialog;
import com.otog.ui.RegisterDialog;
import com.otog.ui.SendMoneyPanel;
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
        Map<String, String> expectedForms = Map.of(
                "UserLogin.form", "UserLogin",
                "com/otog/ui/RegisterDialog.form", "com.otog.ui.RegisterDialog",
                "com/otog/ui/PinSettingsDialog.form", "com.otog.ui.PinSettingsDialog",
                "com/otog/ui/Main.form", "com.otog.ui.Main",
                "com/otog/ui/OverviewPanel.form", "com.otog.ui.OverviewPanel",
                "com/otog/ui/CashInOutPanel.form", "com.otog.ui.CashInOutPanel",
                "com/otog/ui/SendMoneyPanel.form", "com.otog.ui.SendMoneyPanel",
                "com/otog/ui/AdminControlsPanel.form", "com.otog.ui.AdminControlsPanel",
                "com/otog/ui/NotificationInboxDialog.form", "com.otog.ui.NotificationInboxDialog",
                "com/otog/ui/MerchantQrPanel.form", "com.otog.ui.MerchantQrPanel"
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

        AdminControlsPanel adminPanel = new AdminControlsPanel(null, null, null, null);
        assertNotNull(adminPanel.$$$getRootComponent$$$());
        assertInstanceOf(GridLayoutManager.class, adminPanel.getRootPanel().getLayout());

        MerchantQrPanel merchantQrPanel = new MerchantQrPanel();
        assertNotNull(merchantQrPanel.$$$getRootComponent$$$());
        assertInstanceOf(GridLayoutManager.class, merchantQrPanel.getRootPanel().getLayout());
        assertNotNull(merchantQrPanel.getImportQrButton());
        assertNotNull(merchantQrPanel.getConfirmPayButton());
        assertNotNull(merchantQrPanel.getGenerateQrButton());
        assertNotNull(merchantQrPanel.getSaveQrButton());

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
}
