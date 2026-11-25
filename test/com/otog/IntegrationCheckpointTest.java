package com.otog;

import com.otog.database.DatabaseManager;
import com.otog.exception.DuplicateTransactionException;
import com.otog.exception.EntityNotFoundException;
import com.otog.exception.InsufficientFundsException;
import com.otog.exception.InvalidAmountException;
import com.otog.exception.MalformedQrException;
import com.otog.exception.WalletException;
import com.otog.model.AdminAction;
import com.otog.model.LedgerEntry;
import com.otog.model.LedgerEntryType;
import com.otog.model.MerchantQrPayload;
import com.otog.model.Notification;
import com.otog.model.NotificationType;
import com.otog.model.PaymentMethod;
import com.otog.model.ReceiptBuilder;
import com.otog.model.Transaction;
import com.otog.model.TransactionFilter;
import com.otog.model.TransactionStatus;
import com.otog.model.TransactionType;
import com.otog.model.User;
import com.otog.model.UserRole;
import com.otog.model.Wallet;
import com.otog.model.WalletStatus;
import com.otog.service.AdminService;
import com.otog.service.AuthServiceImpl;
import com.otog.service.BankResponseStatus;
import com.otog.service.DemoDataSeeder;
import com.otog.service.MerchantQrService;
import com.otog.service.PaymentProviderFactory;
import com.otog.service.ProtectedWalletOperationsProxy;
import com.otog.service.SendMoneyService;
import com.otog.service.SessionManager;
import com.otog.service.StatementFileService;
import com.otog.service.WalletOperations;
import com.otog.service.WalletService;
import com.otog.ui.Main;
import com.otog.ui.MerchantQrPanel;
import com.otog.util.OtogConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CAP-14 Integration Checkpoint & Feature Freeze test suite.
 * Verifies:
 * - AC1: Merged features in one runnable build with cross-feature balance, ledger, history,
 *        receipt, statement, restart persistence, and role/permission consistency.
 * - AC2: Deterministic user, merchant, admin, biller, and bank demo data with idempotent
 *        startup seeding and clean CLI/programmatic reset.
 * - AC3: Complete original sequence: send money, validate, update both balances, deliver notifications.
 * - AC4: Feature freeze governance documentation and hidden unfinished/deferred modules.
 */
public class IntegrationCheckpointTest {

    private Main activeDashboard;

    @BeforeEach
    void resetState() {
        OtogConfig.getInstance().clearOverrides();
        OtogConfig.getInstance().reload();
        SessionManager.getInstance().reset();
    }

    @AfterEach
    void cleanUp() {
        if (activeDashboard != null) {
            activeDashboard.dispose();
            activeDashboard = null;
        }
        SessionManager.getInstance().reset();
        OtogConfig.getInstance().clearOverrides();
        OtogConfig.getInstance().reload();
    }

    // =========================================================================
    // AC2: Deterministic user, merchant, admin, biller, and bank demo data
    // =========================================================================

    @Test
    void testDeterministicDemoDataSeedingIdempotencyAndCliReset(@TempDir Path tempDir) throws Exception {
        Path dbFile = tempDir.resolve("cap14-demo.db");
        String jdbcUrl = "jdbc:sqlite:" + dbFile.toAbsolutePath();

        try (DatabaseManager db = new DatabaseManager(jdbcUrl)) {
            DemoDataSeeder.DemoSeedSummary summary = DemoDataSeeder.ensureSeeded(db);

            // 1. Verify all 4 deterministic actors: Admin, Merchant, Demo Sender, Demo Recipient
            assertEquals(4, summary.accounts().size(), "Expected admin, merchant, and 2 demo users");
            assertEquals(3, summary.banks().size(), "Expected 3 seeded demo banks (BDO, BPI, Metrobank)");
            assertEquals(5, summary.billers().size(), "Expected 5 seeded demo biller accounts");

            AuthServiceImpl auth = new AuthServiceImpl(db);
            WalletService wallet = new WalletService(db, auth);

            // Verify deterministic IDs, roles, starting balances, and deterministic linked banks
            verifySeededActor(auth, wallet, "USR-ADMIN-01", "WLT-ADMIN-01", UserRole.ADMIN,
                    "admin", "password", "123456", new BigDecimal("1000.00"), "PM-DEMO-BDO");
            verifySeededActor(auth, wallet, "USR-MERCHANT-01", "WLT-MERCHANT-01", UserRole.MERCHANT,
                    "09178888888", "Merchant123!", "8888", new BigDecimal("0.00"), "PM-DEMO-USR-MERCHANT-01-BDO");
            verifySeededActor(auth, wallet, "USR-DEMO-01", "WLT-DEMO-01", UserRole.USER,
                    "09000000101", "DemoPass123!", "1234", new BigDecimal("5000.00"), "PM-DEMO-USR-DEMO-01-BDO");
            verifySeededActor(auth, wallet, "USR-DEMO-02", "WLT-DEMO-02", UserRole.USER,
                    "09000000102", "DemoPass123!", "5678", new BigDecimal("1500.00"), "PM-DEMO-USR-DEMO-02-BDO");

            // 2. Verify idempotency: mutating a balance then re-running initSchema/ensureSeeded
            // must NOT duplicate records or reset live transaction balances
            wallet.cashIn("REQ-IDEMP-SEED-01", "WLT-DEMO-01", "PM-DEMO-USR-DEMO-01-BDO",
                    new BigDecimal("250.00"), "Check idempotent seed");
            assertEquals(new BigDecimal("5250.00"), wallet.getBalance("WLT-DEMO-01"));

            db.initSchema();
            DemoDataSeeder.DemoSeedSummary afterReinit = DemoDataSeeder.ensureSeeded(db);
            assertEquals(4, afterReinit.accounts().size());
            assertEquals(new BigDecimal("5250.00"), wallet.getBalance("WLT-DEMO-01"),
                    "Re-running initSchema must preserve live balances");
            assertEquals(3, wallet.getPaymentMethodsByUserId("USR-DEMO-01").size(),
                    "Re-running initSchema must not duplicate demo bank payment methods");

            String guide = DemoDataSeeder.formatDemoGuide(afterReinit);
            assertTrue(guide.contains("USR-ADMIN-01"));
            assertTrue(guide.contains("USR-MERCHANT-01"));
            assertTrue(guide.contains("USR-DEMO-01"));
            assertTrue(guide.contains("USR-DEMO-02"));
            assertTrue(guide.contains("MERALCO-10023456"));
            assertTrue(guide.contains("BDO Unibank"));
        }

        // 3. Verify programmatic reset restores clean initial balances
        DemoDataSeeder.DemoSeedSummary resetSummary = DemoDataSeeder.resetAndSeed(dbFile);
        DemoDataSeeder.SeededAccountSummary resetDemo1 = resetSummary.accounts().stream()
                .filter(a -> "USR-DEMO-01".equals(a.userId()))
                .findFirst()
                .orElseThrow();
        assertEquals(new BigDecimal("5000.00"), resetDemo1.balance(),
                "resetAndSeed must recreate clean deterministic initial balance");

        // 4. Verify UserLogin CLI --seed-demo and --reset-demo modes against temp DB override
        OtogConfig.getInstance().setOverride("db.path", dbFile.toAbsolutePath().toString());
        Class<?> loginClass = Class.forName("UserLogin");
        Method mainMethod = loginClass.getMethod("main", String[].class);
        assertDoesNotThrow(() -> mainMethod.invoke(null, (Object) new String[]{"--seed-demo"}));
        assertDoesNotThrow(() -> mainMethod.invoke(null, (Object) new String[]{"--reset-demo"}));
    }

    // =========================================================================
    // AC3: Complete original sequence — send money, validate, update both
    //      balances, deliver notifications
    // =========================================================================

    @Test
    void testCompleteOriginalSendMoneySequenceValidateBalancesAndNotifications() throws Exception {
        try (DatabaseManager db = new DatabaseManager("jdbc:sqlite::memory:")) {
            AuthServiceImpl auth = new AuthServiceImpl(db);
            WalletService wallet = new WalletService(db, auth);
            WalletOperations guarded = ProtectedWalletOperationsProxy.wrap(wallet);
            SendMoneyService sendService = guarded.createSendMoneyService(auth);

            // Step 1: Login Demo Sender via UserLogin (password + simulated SMS 2FA OTP)
            Class<?> loginClass = Class.forName("UserLogin");
            Object loginFrame = loginClass
                    .getDeclaredConstructor(com.otog.service.AuthService.class, WalletOperations.class)
                    .newInstance(auth, wallet);
            try {
                Method attemptLogin = loginClass.getMethod("attemptLogin", String.class, String.class);
                Method verify2FA = loginClass.getMethod("verifyTwoFactorCode", String.class);
                Method getLastOtp = loginClass.getMethod("getLastIssuedOtp");

                assertTrue((Boolean) attemptLogin.invoke(loginFrame, "09000000101", "DemoPass123!"),
                        "Demo Sender password login must succeed");
                String otp = (String) getLastOtp.invoke(loginFrame);
                assertNotNull(otp, "Simulated 2FA OTP must be issued");
                assertTrue((Boolean) verify2FA.invoke(loginFrame, otp), "2FA OTP verification must establish session");
            } finally {
                if (loginFrame instanceof JFrame frame) {
                    frame.dispose();
                }
            }

            assertTrue(SessionManager.getInstance().isSessionActive("USR-DEMO-01"));

            // Step 2: Validation checks BEFORE transfer (must reject without moving funds or notifying)
            assertThrows(InvalidAmountException.class, () ->
                    sendService.preview("USR-DEMO-01", "09000000102", BigDecimal.ZERO, null, "Zero"));
            assertThrows(InvalidAmountException.class, () ->
                    sendService.preview("USR-DEMO-01", "09000000102", new BigDecimal("50001.00"), null, "Over limit"));
            assertThrows(InsufficientFundsException.class, () ->
                    sendService.preview("USR-DEMO-01", "09000000102", new BigDecimal("6000.00"), null, "Insufficient"));
            assertThrows(WalletException.class, () ->
                    sendService.preview("USR-DEMO-01", "09000000101", new BigDecimal("100.00"), null, "Self"));
            assertThrows(EntityNotFoundException.class, () ->
                    sendService.preview("USR-DEMO-01", "09998887766", new BigDecimal("100.00"), null, "Missing"));

            SendMoneyService.SendMoneyPreview badPinPreview = sendService.preview(
                    "USR-DEMO-01", "09000000102", new BigDecimal("100.00"), null, "Wrong PIN check");
            assertThrows(WalletException.class, () ->
                    sendService.confirmAndSend(badPinPreview.getReferenceNumber(), "USR-DEMO-01", "9999",
                            "09000000102", new BigDecimal("100.00"), null, "Wrong PIN check"));

            // Confirm zero side effects from all failed validations
            assertEquals(new BigDecimal("5000.00"), wallet.getBalance("WLT-DEMO-01"));
            assertEquals(new BigDecimal("1500.00"), wallet.getBalance("WLT-DEMO-02"));
            assertTrue(wallet.getTransactionRepository().findAll().isEmpty());
            assertTrue(wallet.getLedgerRepository().findAll().isEmpty());
            assertEquals(0, wallet.getUnreadNotificationCount("USR-DEMO-01"));
            assertEquals(0, wallet.getUnreadNotificationCount("USR-DEMO-02"));

            // Step 3: Valid preview + PIN-authorized atomic Send Money with configured fee policy
            OtogConfig.getInstance().setOverride("send.default.fee.flat", "10.00");
            OtogConfig.getInstance().setOverride("send.default.fee.percent", "0.01");

            SendMoneyService.SendMoneyPreview preview = sendService.preview(
                    "USR-DEMO-01", "09000000102", new BigDecimal("1000.00"), null, "Rent share");
            assertEquals(new BigDecimal("20.00"), preview.getFee(), "Fee must be 10.00 + 1% of 1000.00 = 20.00");
            assertEquals(new BigDecimal("1020.00"), preview.getTotalDebit());

            Transaction txn = sendService.confirmAndSend(
                    preview.getReferenceNumber(), "USR-DEMO-01", "1234",
                    "09000000102", new BigDecimal("1000.00"), null, "Rent share");
            assertEquals(TransactionStatus.COMPLETED, txn.getStatus());
            assertEquals(TransactionType.SEND_MONEY, txn.getType());

            // Step 4: Verify BOTH balances and double-entry ledger entries updated atomically
            assertEquals(new BigDecimal("3980.00"), wallet.getBalance("WLT-DEMO-01"));
            assertEquals(new BigDecimal("2500.00"), wallet.getBalance("WLT-DEMO-02"));

            List<LedgerEntry> senderLedger = wallet.getLedgerRepository().findByWalletId("WLT-DEMO-01");
            List<LedgerEntry> recipientLedger = wallet.getLedgerRepository().findByWalletId("WLT-DEMO-02");
            assertEquals(1, senderLedger.size());
            assertEquals(LedgerEntryType.DEBIT, senderLedger.get(0).getEntryType());
            assertEquals(new BigDecimal("1020.00"), senderLedger.get(0).getAmount());
            assertEquals(new BigDecimal("5000.00"), senderLedger.get(0).getBalanceBefore());
            assertEquals(new BigDecimal("3980.00"), senderLedger.get(0).getBalanceAfter());

            assertEquals(1, recipientLedger.size());
            assertEquals(LedgerEntryType.CREDIT, recipientLedger.get(0).getEntryType());
            assertEquals(new BigDecimal("1000.00"), recipientLedger.get(0).getAmount());
            assertEquals(new BigDecimal("1500.00"), recipientLedger.get(0).getBalanceBefore());
            assertEquals(new BigDecimal("2500.00"), recipientLedger.get(0).getBalanceAfter());

            // Step 5: Verify idempotent duplicate rejection never double-debits
            assertThrows(DuplicateTransactionException.class, () ->
                    sendService.confirmAndSend(preview.getReferenceNumber(), "USR-DEMO-01", "1234",
                            "09000000102", new BigDecimal("1000.00"), null, "Replay"));
            assertEquals(new BigDecimal("3980.00"), wallet.getBalance("WLT-DEMO-01"));
            assertEquals(new BigDecimal("2500.00"), wallet.getBalance("WLT-DEMO-02"));

            // Step 6: Verify persistent post-commit notifications delivered to BOTH sender & recipient
            List<Notification> senderNotifs = guarded.getNotifications("USR-DEMO-01", false);
            assertEquals(1, senderNotifs.size());
            assertEquals(NotificationType.TRANSFER_SENT, senderNotifs.get(0).getType());
            assertTrue(senderNotifs.get(0).getMessage().contains(txn.getReferenceNumber()));
            assertFalse(senderNotifs.get(0).getMessage().contains("09000000102"),
                    "Sender notification must mask recipient mobile number");
            assertEquals(1, guarded.getUnreadNotificationCount("USR-DEMO-01"));

            // Cross-account isolation: sender cannot inspect recipient's notifications via proxy
            assertThrows(SecurityException.class, () -> guarded.getNotifications("USR-DEMO-02", false));

            // Switch session to Demo Recipient and verify receipt, history, and notification inbox
            User recipientUser = auth.authenticatePassword("09000000102", "DemoPass123!").orElseThrow();
            Wallet recipientWallet = wallet.getWallet("WLT-DEMO-02");
            SessionManager.getInstance().login(recipientUser, recipientWallet);

            List<Notification> recipientNotifs = guarded.getNotifications("USR-DEMO-02", false);
            assertEquals(1, recipientNotifs.size());
            assertEquals(NotificationType.TRANSFER_RECEIVED, recipientNotifs.get(0).getType());
            assertTrue(recipientNotifs.get(0).getMessage().contains(txn.getReferenceNumber()));
            assertFalse(recipientNotifs.get(0).getMessage().contains("09000000101"),
                    "Recipient notification must mask sender mobile number");

            String recipientReceipt = guarded.getTransactionReceipt("WLT-DEMO-02", txn.getReferenceNumber());
            assertTrue(recipientReceipt.contains("Demo Sender"));
            assertFalse(recipientReceipt.contains("09000000101"));

            guarded.markNotificationRead("USR-DEMO-02", recipientNotifs.get(0).getNotificationId());
            assertEquals(0, guarded.getUnreadNotificationCount("USR-DEMO-02"));
        }
    }

    // =========================================================================
    // AC1: Cross-feature balance, ledger, history, receipts, statements &
    //      restart persistence across ALL integrated modules
    // =========================================================================

    @Test
    void testCrossFeatureBalanceLedgerHistoryReceiptsAndRestartPersistence(@TempDir Path tempDir) throws Exception {
        Path dbFile = tempDir.resolve("cap14-cross-feature.db");
        String jdbcUrl = "jdbc:sqlite:" + dbFile.toAbsolutePath();

        String meralcoTxnRef;
        String qrTxnRef;

        try (DatabaseManager db = new DatabaseManager(jdbcUrl)) {
            AuthServiceImpl auth = new AuthServiceImpl(db);
            WalletService wallet = new WalletService(db, auth);
            WalletOperations guarded = ProtectedWalletOperationsProxy.wrap(wallet);
            MerchantQrService qrService = guarded.createMerchantQrService(auth);

            User sender = auth.authenticatePassword("09000000101", "DemoPass123!").orElseThrow();
            User merchant = auth.authenticatePassword("09178888888", "Merchant123!").orElseThrow();
            SessionManager.getInstance().login(sender, wallet.getWallet("WLT-DEMO-01"));

            // 1. Cash-In SUCCESS via deterministic BDO demo bank (+1000.00 -> 6000.00)
            wallet.setPaymentProviderFactory(PaymentProviderFactory.forMode(BankResponseStatus.SUCCESS));
            Transaction ci1 = guarded.cashIn("REQ-INT-CI-1", "WLT-DEMO-01", "PM-DEMO-USR-DEMO-01-BDO",
                    new BigDecimal("1000.00"), "Demo top-up");
            assertEquals(TransactionStatus.COMPLETED, ci1.getStatus());
            assertEquals(new BigDecimal("6000.00"), wallet.getBalance("WLT-DEMO-01"));

            // 2. Cash-In TIMEOUT -> PENDING -> settled SUCCESS via statusLookup (+500.00 -> 6500.00)
            wallet.setPaymentProviderFactory(PaymentProviderFactory.forMode(BankResponseStatus.TIMEOUT));
            Transaction ciPending = guarded.cashIn("REQ-INT-CI-2", "WLT-DEMO-01", "PM-DEMO-USR-DEMO-01-BDO",
                    new BigDecimal("500.00"), "Pending top-up");
            assertEquals(TransactionStatus.PENDING, ciPending.getStatus());
            assertEquals(new BigDecimal("6000.00"), wallet.getBalance("WLT-DEMO-01"));

            wallet.setPaymentProviderFactory(PaymentProviderFactory.forMode(BankResponseStatus.SUCCESS));
            Transaction ciSettled = guarded.statusLookup("REQ-INT-CI-2");
            assertEquals(TransactionStatus.COMPLETED, ciSettled.getStatus());
            assertEquals(new BigDecimal("6500.00"), wallet.getBalance("WLT-DEMO-01"));

            // 3. Cash-Out TIMEOUT (reserves 300.00 -> 6200.00) -> FAILED statusLookup (releases -> 6500.00)
            wallet.setPaymentProviderFactory(PaymentProviderFactory.forMode(BankResponseStatus.TIMEOUT));
            Transaction coPending = guarded.cashOutWithPin("REQ-INT-CO-1", "WLT-DEMO-01", "PM-DEMO-USR-DEMO-01-BPI",
                    new BigDecimal("300.00"), BigDecimal.ZERO, "Pending withdrawal", "1234");
            assertEquals(TransactionStatus.PENDING, coPending.getStatus());
            assertEquals(new BigDecimal("6200.00"), wallet.getBalance("WLT-DEMO-01"));

            wallet.setPaymentProviderFactory(PaymentProviderFactory.forMode(BankResponseStatus.FAILED));
            Transaction coFailed = guarded.statusLookup("REQ-INT-CO-1");
            assertEquals(TransactionStatus.FAILED, coFailed.getStatus());
            assertEquals(new BigDecimal("6500.00"), wallet.getBalance("WLT-DEMO-01"));

            // 4. Cash-Out SUCCESS (-200.00 -> 6300.00)
            wallet.setPaymentProviderFactory(PaymentProviderFactory.forMode(BankResponseStatus.SUCCESS));
            Transaction coOk = guarded.cashOutWithPin("REQ-INT-CO-2", "WLT-DEMO-01", "PM-DEMO-USR-DEMO-01-BPI",
                    new BigDecimal("200.00"), BigDecimal.ZERO, "Confirmed withdrawal", "1234");
            assertEquals(TransactionStatus.COMPLETED, coOk.getStatus());
            assertEquals(new BigDecimal("6300.00"), wallet.getBalance("WLT-DEMO-01"));

            // 5. Send Money P2P (Demo Sender -> Demo Recipient, 800.00 -> 5500.00 / 2300.00)
            Transaction sendTxn = guarded.sendMoneyToRecipientWithPin("REQ-INT-SEND-1", "WLT-DEMO-01",
                    "09000000102", new BigDecimal("800.00"), BigDecimal.ZERO, "Cross-feature send", "1234");
            assertEquals(TransactionStatus.COMPLETED, sendTxn.getStatus());
            assertEquals(new BigDecimal("5500.00"), wallet.getBalance("WLT-DEMO-01"));
            assertEquals(new BigDecimal("2300.00"), wallet.getBalance("WLT-DEMO-02"));

            // 6. Pay Bills SUCCESS (Meralco, 1250.00 -> 4250.00)
            OtogConfig.DemoBillerAccount meralco = OtogConfig.getInstance().getDemoBillerAccounts().get(0);
            Transaction billOk = guarded.payBillWithPin("REQ-INT-BILL-1", "WLT-DEMO-01",
                    meralco.billerName(), meralco.accountReference(), meralco.sampleAmount(),
                    BigDecimal.ZERO, "Monthly power bill", "1234");
            assertEquals(TransactionStatus.COMPLETED, billOk.getStatus());
            meralcoTxnRef = billOk.getReferenceNumber();
            assertEquals(new BigDecimal("4250.00"), wallet.getBalance("WLT-DEMO-01"));

            String billReceipt = guarded.getTransactionReceipt("WLT-DEMO-01", meralcoTxnRef);
            assertTrue(billReceipt.contains("Meralco"));
            assertFalse(billReceipt.contains(meralco.accountReference()),
                    "Bill receipt must mask raw biller account reference");

            // 7. Pay Bills TIMEOUT (Manila Water, reserves 485.50 -> 3764.50) -> settled SUCCESS
            OtogConfig.DemoBillerAccount water = OtogConfig.getInstance().getDemoBillerAccounts().get(1);
            wallet.setPaymentProviderFactory(PaymentProviderFactory.forMode(BankResponseStatus.TIMEOUT));
            Transaction billPending = guarded.payBillWithPin("REQ-INT-BILL-2", "WLT-DEMO-01",
                    water.billerName(), water.accountReference(), water.sampleAmount(),
                    BigDecimal.ZERO, "Water bill", "1234");
            assertEquals(TransactionStatus.PENDING, billPending.getStatus());
            assertEquals(new BigDecimal("3764.50"), wallet.getBalance("WLT-DEMO-01"));

            wallet.setPaymentProviderFactory(PaymentProviderFactory.forMode(BankResponseStatus.SUCCESS));
            Transaction billSettled = guarded.statusLookup("REQ-INT-BILL-2");
            assertEquals(TransactionStatus.COMPLETED, billSettled.getStatus());
            assertEquals(new BigDecimal("3764.50"), wallet.getBalance("WLT-DEMO-01"));

            // 8. Merchant QR Payment (Generate -> Export PNG -> Import -> Preview -> PIN Pay, 350.00 -> 3414.50 / 350.00)
            MerchantQrPayload qrPayload = new MerchantQrPayload(
                    merchant.getUserId(), "WLT-MERCHANT-01", merchant.getName(),
                    new BigDecimal("350.00"), "PHP", "QR-INT-001", "Coffee & snacks");
            Path qrPng = tempDir.resolve("merchant-qr.png");
            qrService.exportQrImage(qrPayload, qrPng);
            MerchantQrPayload imported = qrService.importQrImage(qrPng);
            MerchantQrService.MerchantPaymentPreview qrPreview =
                    qrService.previewPayment("USR-DEMO-01", imported, null);
            Transaction qrTxn = qrService.confirmAndPay(qrPreview, "1234");
            assertEquals(TransactionStatus.COMPLETED, qrTxn.getStatus());
            qrTxnRef = qrTxn.getReferenceNumber();

            assertEquals(new BigDecimal("3414.50"), wallet.getBalance("WLT-DEMO-01"));
            assertEquals(new BigDecimal("2300.00"), wallet.getBalance("WLT-DEMO-02"));
            assertEquals(new BigDecimal("350.00"), wallet.getBalance("WLT-MERCHANT-01"));
            assertEquals(new BigDecimal("1000.00"), wallet.getBalance("WLT-ADMIN-01"));

            // 9. Reconcile double-entry ledger chains across EVERY seeded wallet
            assertLedgerReconciles(wallet, "WLT-DEMO-01", new BigDecimal("5000.00"), new BigDecimal("3414.50"));
            assertLedgerReconciles(wallet, "WLT-DEMO-02", new BigDecimal("1500.00"), new BigDecimal("2300.00"));
            assertLedgerReconciles(wallet, "WLT-MERCHANT-01", new BigDecimal("0.00"), new BigDecimal("350.00"));
            assertLedgerReconciles(wallet, "WLT-ADMIN-01", new BigDecimal("1000.00"), new BigDecimal("1000.00"));

            // 10. Filtered history consistency & Statement file export/import round trip
            List<Transaction> allSenderTxns = guarded.getTransactionHistory("WLT-DEMO-01", TransactionFilter.empty());
            assertEquals(8, allSenderTxns.size(), "Demo Sender must have all 8 cross-feature transactions");
            assertEquals(2, guarded.getTransactionHistory("WLT-DEMO-01",
                    TransactionFilter.builder().type(TransactionType.BILL_PAYMENT).build()).size());
            assertEquals(2, guarded.getTransactionHistory("WLT-DEMO-01",
                    TransactionFilter.builder().type(TransactionType.SEND_MONEY).build()).size());
            assertEquals(1, guarded.getTransactionHistory("WLT-DEMO-01",
                    TransactionFilter.builder().status(TransactionStatus.FAILED).build()).size());

            StatementFileService stmtService = new StatementFileService();
            Path txtStmt = tempDir.resolve("demo-sender-statement.txt");
            Path csvStmt = tempDir.resolve("demo-sender-statement.csv");
            stmtService.exportWalletStatement(sender, wallet.getWallet("WLT-DEMO-01"), allSenderTxns, txtStmt);
            stmtService.exportWalletStatement(sender, wallet.getWallet("WLT-DEMO-01"), allSenderTxns, csvStmt);

            StatementFileService.LoadedStatement loaded = stmtService.importWalletStatement(txtStmt);
            assertEquals(new BigDecimal("3414.50"), loaded.getBalance());
            assertEquals(8, loaded.getEntries().size());
            StatementFileService.LoadedStatement loadedCsv = stmtService.importWalletStatement(csvStmt);
            assertEquals(new BigDecimal("3414.50"), loadedCsv.getBalance());
            assertEquals(8, loadedCsv.getEntries().size());
            String rawTxt = Files.readString(txtStmt, StandardCharsets.UTF_8);
            assertFalse(rawTxt.contains("09000000101"), "Exported statement must mask raw mobile number");
            assertFalse(rawTxt.contains("WLT-DEMO-01"), "Exported statement must mask raw wallet ID");

            // Check notifications across all participants (sender, recipient, merchant)
            assertTrue(guarded.getUnreadNotificationCount("USR-DEMO-01") >= 8,
                    "Sender must receive post-commit notifications across cash, send, bill, and QR flows");
            assertEquals(1, wallet.getUnreadNotificationCount("USR-DEMO-02"));
            assertEquals(1, wallet.getUnreadNotificationCount("USR-MERCHANT-01"));
        }

        // 11. Restart persistence check: reopen SQLite DB fresh and verify every record survived
        try (DatabaseManager reopenedDb = new DatabaseManager(jdbcUrl)) {
            WalletService reopenedWallet = new WalletService(reopenedDb);
            assertEquals(new BigDecimal("3414.50"), reopenedWallet.getBalance("WLT-DEMO-01"));
            assertEquals(new BigDecimal("2300.00"), reopenedWallet.getBalance("WLT-DEMO-02"));
            assertEquals(new BigDecimal("350.00"), reopenedWallet.getBalance("WLT-MERCHANT-01"));
            assertEquals(new BigDecimal("1000.00"), reopenedWallet.getBalance("WLT-ADMIN-01"));
            assertEquals(8, reopenedWallet.getTransactionsByWalletId("WLT-DEMO-01").size());
            assertTrue(reopenedWallet.getTransactionRepository().findByReferenceNumber(meralcoTxnRef).isPresent());
            assertTrue(reopenedWallet.getTransactionRepository().findByReferenceNumber(qrTxnRef).isPresent());
            assertEquals(1, reopenedWallet.getUnreadNotificationCount("USR-MERCHANT-01"));
        }
    }

    // =========================================================================
    // AC1: Permissions, Admin controls, mid-flow suspension & QR bounds safety
    // =========================================================================

    @Test
    void testCrossFeaturePermissionsAdminControlsAndMidFlowSuspension(@TempDir Path tempDir) throws Exception {
        try (DatabaseManager db = new DatabaseManager("jdbc:sqlite::memory:")) {
            AuthServiceImpl auth = new AuthServiceImpl(db);
            WalletService wallet = new WalletService(db, auth);
            WalletOperations guarded = ProtectedWalletOperationsProxy.wrap(wallet);
            AdminService guardedAdmin = guarded.getAdminService();
            MerchantQrService qrService = guarded.createMerchantQrService(auth);

            User demoSender = auth.authenticatePassword("09000000101", "DemoPass123!").orElseThrow();
            User adminUser = auth.authenticatePassword("admin", "password").orElseThrow();
            User merchantUser = auth.authenticatePassword("09178888888", "Merchant123!").orElseThrow();

            // 1. Ordinary USER cannot access admin ops or inspect other users/merchants
            SessionManager.getInstance().login(demoSender, wallet.getWallet("WLT-DEMO-01"));
            assertThrows(SecurityException.class, () -> guardedAdmin.getAllUsers("USR-DEMO-01"));
            assertThrows(SecurityException.class, () -> guardedAdmin.suspendUser("USR-DEMO-01", "USR-DEMO-02", "No auth"));
            assertThrows(SecurityException.class, () -> guarded.getBalance("WLT-DEMO-02"));
            assertThrows(SecurityException.class, () -> guarded.getTransactionsByWalletId("WLT-MERCHANT-01"));
            assertThrows(SecurityException.class, () -> guarded.getNotifications("USR-MERCHANT-01", false));

            // 2. Tiny corrupted PNG throws MalformedQrException (never ArrayIndexOutOfBoundsException)
            Path tinyPng = tempDir.resolve("tiny.png");
            ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "PNG", tinyPng.toFile());
            assertThrows(MalformedQrException.class, () -> qrService.importQrImage(tinyPng));

            // 3. Admin inspects accounts/wallets, suspends Demo Sender, and direct balance editing is blocked
            SessionManager.getInstance().login(adminUser, wallet.getWallet("WLT-ADMIN-01"));
            assertEquals(4, guardedAdmin.getAllUsers("USR-ADMIN-01").size());
            assertEquals(4, guardedAdmin.getAllWallets("USR-ADMIN-01").size());
            assertThrows(UnsupportedOperationException.class, () ->
                    guardedAdmin.directSetBalance("USR-ADMIN-01", "WLT-DEMO-01", new BigDecimal("99999.00")));

            guardedAdmin.suspendUser("USR-ADMIN-01", "USR-DEMO-01", "Suspicious velocity check");
            assertEquals(WalletStatus.FROZEN, wallet.getWallet("WLT-DEMO-01").getStatus());

            // 4. Suspended Demo Sender cannot authenticate or initiate Send Money / Pay Bills / Cash Out / Merchant QR
            assertThrows(RuntimeException.class, () -> auth.authenticatePassword("09000000101", "DemoPass123!"));

            // Even with a stale in-memory session, every module rejects suspended user
            SessionManager.getInstance().login(demoSender, wallet.getWallet("WLT-DEMO-01"));
            assertThrows(WalletException.class, () -> guarded.sendMoneyToRecipientWithPin(
                    "REQ-SUSP-SEND", "WLT-DEMO-01", "09000000102", new BigDecimal("50.00"), BigDecimal.ZERO, "Blocked", "1234"));
            assertThrows(WalletException.class, () -> guarded.payBillWithPin(
                    "REQ-SUSP-BILL", "WLT-DEMO-01", "Meralco (Electricity)", "MERALCO-10023456",
                    new BigDecimal("100.00"), BigDecimal.ZERO, "Blocked", "1234"));
            assertThrows(WalletException.class, () -> guarded.cashOutWithPin(
                    "REQ-SUSP-CO", "WLT-DEMO-01", "PM-DEMO-USR-DEMO-01-BDO",
                    new BigDecimal("100.00"), BigDecimal.ZERO, "Blocked", "1234"));
            MerchantQrPayload qr = new MerchantQrPayload(
                    merchantUser.getUserId(), "WLT-MERCHANT-01", merchantUser.getName(),
                    new BigDecimal("100.00"), "PHP", "QR-SUSP-1", "Blocked");
            assertThrows(WalletException.class, () -> qrService.previewPayment("USR-DEMO-01", qr, null));

            // 5. Admin reactivates Demo Sender; wallet unfreezes and payments succeed again
            SessionManager.getInstance().login(adminUser, wallet.getWallet("WLT-ADMIN-01"));
            guardedAdmin.reactivateUser("USR-ADMIN-01", "USR-DEMO-01", "Identity cleared");
            assertEquals(WalletStatus.ACTIVE, wallet.getWallet("WLT-DEMO-01").getStatus());

            List<AdminAction> auditLog = guardedAdmin.getAdminActions("USR-ADMIN-01");
            assertTrue(auditLog.stream().anyMatch(a -> "SUSPEND_USER".equals(a.getActionType())));
            assertTrue(auditLog.stream().anyMatch(a -> "REACTIVATE_USER".equals(a.getActionType())));
            for (AdminAction action : auditLog) {
                String combined = (action.getReason() + " " + action.getDetails()).toLowerCase();
                assertFalse(combined.contains("demopass123") || combined.contains("123456"),
                        "Admin audit logs must never contain secrets");
            }

            User restoredSender = auth.authenticatePassword("09000000101", "DemoPass123!").orElseThrow();
            SessionManager.getInstance().login(restoredSender, wallet.getWallet("WLT-DEMO-01"));
            Transaction restoredTxn = guarded.sendMoneyToRecipientWithPin(
                    "REQ-RESTORED-1", "WLT-DEMO-01", "09000000102",
                    new BigDecimal("100.00"), BigDecimal.ZERO, "Restored send", "1234");
            assertEquals(TransactionStatus.COMPLETED, restoredTxn.getStatus());
            assertEquals(new BigDecimal("4900.00"), wallet.getBalance("WLT-DEMO-01"));
            assertEquals(new BigDecimal("1600.00"), wallet.getBalance("WLT-DEMO-02"));
        }
    }

    // =========================================================================
    // AC1, AC3, AC4: Unified Dashboard Shell, Fee Consistency & Freeze Policy
    // =========================================================================

    @Test
    void testUnifiedDashboardShellIntegrationAndFeatureFreezePolicy() throws Exception {
        try (DatabaseManager db = new DatabaseManager("jdbc:sqlite::memory:")) {
            AuthServiceImpl auth = new AuthServiceImpl(db);
            WalletService wallet = new WalletService(db, auth);
            WalletOperations guarded = ProtectedWalletOperationsProxy.wrap(wallet);

            // 1. Regular demo user dashboard exposes exactly the 5 frozen modules and hides unfinished tabs
            User demoSender = auth.authenticatePassword("09000000101", "DemoPass123!").orElseThrow();
            SessionManager.getInstance().login(demoSender, wallet.getWallet("WLT-DEMO-01"));

            activeDashboard = new Main(guarded, auth);
            activeDashboard.getInitialLoadFuture().get(5, TimeUnit.SECONDS);

            assertEquals(List.of("Overview", "Cash In / Out", "Send Money", "Pay Bills", "Merchant QR"),
                    activeDashboard.getVisibleTabTitles());
            assertFalse(activeDashboard.getVisibleTabTitles().contains("Loans"));
            assertFalse(activeDashboard.getVisibleTabTitles().contains("Investments"));
            assertFalse(activeDashboard.getVisibleTabTitles().contains("Admin Controls"));

            // Merchant QR panel cleanly handles non-merchant Refresh Sales without throwing NPE or unauthorized query
            MerchantQrPanel qrPanel = activeDashboard.getMerchantQrPanel();
            assertNotNull(qrPanel);
            SwingUtilities.invokeAndWait(qrPanel::refreshMerchantSalesAsync);
            assertTrue(qrPanel.getMerchantFeedbackLabel().getText().contains("Merchant or Administrator"),
                    "Non-merchant user must see clear permission guidance on Merchant Terminal tab");

            // Main.submitSendMoneyAsync honors configured CompositeFeePolicy and delivers both notifications off-EDT
            OtogConfig.getInstance().setOverride("send.default.fee.flat", "5.00");
            Transaction uiSend = activeDashboard
                    .submitSendMoneyAsync("09000000102", "200.00", "UI integration send", "1234")
                    .get(5, TimeUnit.SECONDS);
            assertEquals(TransactionStatus.COMPLETED, uiSend.getStatus());
            assertEquals(new BigDecimal("5.00"), uiSend.getFee(),
                    "Main Send Money execution must apply configured CompositeFeePolicy");
            assertTrue(activeDashboard.isLastPaymentExecutedOffEdt());
            assertEquals(new BigDecimal("4795.00"), wallet.getBalance("WLT-DEMO-01"));
            assertEquals(new BigDecimal("1700.00"), wallet.getBalance("WLT-DEMO-02"));
            assertEquals(1, wallet.getUnreadNotificationCount("USR-DEMO-01"));
            assertEquals(1, wallet.getUnreadNotificationCount("USR-DEMO-02"));

            activeDashboard.dispose();
            activeDashboard = null;

            // 2. Admin dashboard exposes 6 tabs including Admin Controls while still hiding deferred modules
            User adminUser = auth.authenticatePassword("admin", "password").orElseThrow();
            SessionManager.getInstance().login(adminUser, wallet.getWallet("WLT-ADMIN-01"));
            activeDashboard = new Main(guarded, auth);
            activeDashboard.getInitialLoadFuture().get(5, TimeUnit.SECONDS);

            assertEquals(List.of("Overview", "Cash In / Out", "Send Money", "Pay Bills", "Merchant QR", "Admin Controls"),
                    activeDashboard.getVisibleTabTitles());
            assertFalse(activeDashboard.getVisibleTabTitles().contains("Loans"));
            assertFalse(activeDashboard.getVisibleTabTitles().contains("Investments"));
        }

        // 3. AC4: Verify feature freeze governance document exists and records Nov 29 freeze & deferred scope
        Path freezeDoc = Paths.get("docs", "CAP-14-FEATURE-FREEZE.md");
        assertTrue(Files.isRegularFile(freezeDoc), "Feature freeze governance document must exist at docs/CAP-14-FEATURE-FREEZE.md");
        String freezeText = Files.readString(freezeDoc, StandardCharsets.UTF_8);
        assertTrue(freezeText.contains("November 29, 2026"), "Freeze doc must declare Nov 29, 2026 feature freeze");
        assertTrue(freezeText.contains("CAP-12"), "Freeze doc must explicitly record CAP-12 deferred scope");
        assertTrue(freezeText.contains("CAP-13"), "Freeze doc must explicitly record CAP-13 disposition");
        assertTrue(freezeText.contains("USR-DEMO-01") && freezeText.contains("USR-MERCHANT-01")
                && freezeText.contains("USR-ADMIN-01"), "Freeze doc must catalog deterministic demo accounts");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static void verifySeededActor(AuthServiceImpl auth,
                                          WalletService wallet,
                                          String expectedUserId,
                                          String expectedWalletId,
                                          UserRole expectedRole,
                                          String loginIdentifier,
                                          String password,
                                          String pin,
                                          BigDecimal expectedBalance,
                                          String expectedDefaultBankPmId) {
        Optional<User> authOpt = auth.authenticatePassword(loginIdentifier, password);
        assertTrue(authOpt.isPresent(), "Seeded actor must authenticate: " + expectedUserId);
        User user = authOpt.get();
        assertEquals(expectedUserId, user.getUserId());
        assertEquals(expectedRole, user.getRole());

        Wallet w = wallet.getWalletByUserId(expectedUserId);
        assertEquals(expectedWalletId, w.getWalletId());
        assertEquals(0, expectedBalance.compareTo(w.getBalance()),
                "Unexpected initial balance for " + expectedUserId);

        List<PaymentMethod> methods = wallet.getPaymentMethodsByUserId(expectedUserId);
        assertEquals(3, methods.size(), "Expected 3 demo bank accounts for " + expectedUserId);
        assertTrue(methods.stream().anyMatch(pm -> expectedDefaultBankPmId.equals(pm.getPaymentMethodId())),
                "Expected deterministic payment method ID " + expectedDefaultBankPmId);

        SessionManager.getInstance().login(user, w);
        assertTrue(auth.hasPin(expectedUserId), "Seeded actor must have a configured PIN: " + expectedUserId);
        assertTrue(auth.verifyPin(expectedUserId, pin), "Configured PIN must verify for " + expectedUserId);
        SessionManager.getInstance().reset();
    }

    private static void assertLedgerReconciles(WalletService wallet,
                                               String walletId,
                                               BigDecimal initialBalance,
                                               BigDecimal expectedFinalBalance) {
        List<LedgerEntry> entries = new java.util.ArrayList<>(wallet.getLedgerRepository().findByWalletId(walletId));
        entries.sort(Comparator.comparing(LedgerEntry::getCreatedAt)
                .thenComparing(LedgerEntry::getLedgerEntryId));

        BigDecimal running = initialBalance;
        for (LedgerEntry entry : entries) {
            assertEquals(0, running.compareTo(entry.getBalanceBefore()),
                    "Broken ledger chain for " + walletId + " at entry " + entry.getLedgerEntryId());
            if (entry.getEntryType() == LedgerEntryType.CREDIT) {
                running = running.add(entry.getAmount());
            } else {
                running = running.subtract(entry.getAmount());
            }
            assertEquals(0, running.compareTo(entry.getBalanceAfter()),
                    "Ledger balanceAfter mismatch for " + walletId + " at entry " + entry.getLedgerEntryId());
        }
        assertEquals(0, expectedFinalBalance.compareTo(running),
                "Ledger sum does not reconcile with expected balance for " + walletId);
        assertEquals(0, expectedFinalBalance.compareTo(wallet.getBalance(walletId)),
                "Persisted wallet balance does not match reconciled ledger balance for " + walletId);
    }
}
