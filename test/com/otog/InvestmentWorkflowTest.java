package com.otog;

import com.intellij.uiDesigner.core.GridLayoutManager;
import com.otog.database.DatabaseManager;
import com.otog.exception.DuplicateTransactionException;
import com.otog.exception.InsufficientFundsException;
import com.otog.exception.InvalidAmountException;
import com.otog.exception.WalletException;
import com.otog.model.InvestMoneyTransaction;
import com.otog.model.LedgerEntry;
import com.otog.model.LedgerEntryType;
import com.otog.model.Notification;
import com.otog.model.NotificationType;
import com.otog.model.Transaction;
import com.otog.model.TransactionBuilder;
import com.otog.model.TransactionStatus;
import com.otog.model.TransactionType;
import com.otog.model.User;
import com.otog.model.UserRole;
import com.otog.model.UserStatus;
import com.otog.model.Wallet;
import com.otog.model.WalletStatus;
import com.otog.service.AuthService;
import com.otog.service.AuthServiceImpl;
import com.otog.service.InvestmentService;
import com.otog.service.ProtectedWalletOperationsProxy;
import com.otog.service.SessionManager;
import com.otog.service.StatementFileService;
import com.otog.service.WalletOperations;
import com.otog.service.WalletService;
import com.otog.ui.InvestmentPanel;
import com.otog.ui.Main;
import com.otog.util.IdGenerator;
import com.otog.util.OtogConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests CAP-13: Basic simulated investment workflow (seeded simulated product,
 * preview units & cost, atomic purchase/redemption, persisted holdings & activity,
 * insufficient funds & overselling rejection, security isolation, notifications,
 * concurrency safety, and bound InvestmentPanel.form UI).
 */
public class InvestmentWorkflowTest {

    private DatabaseManager databaseManager;
    private AuthServiceImpl authService;
    private WalletService walletService;
    private Main activeDashboard;

    @BeforeEach
    void setUp() {
        OtogConfig.getInstance().clearOverrides();
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
        OtogConfig.getInstance().clearOverrides();
        databaseManager.close();
    }

    @Test
    void testSeededSimulatedProductSingleProductPolicyAndBoundPanel() {
        InvestmentService.SimulatedProduct product = walletService.getSeededProduct();
        assertEquals("OTOG-SIM-FUND", product.getProductCode());
        assertEquals("OTOG Simulated Balanced Fund", product.getProductName());
        assertEquals(new BigDecimal("100.00"), product.getSimulatedUnitPrice());
        assertEquals("PHP", product.getCurrency());
        assertTrue(product.getSimulationDisclaimer().contains("SIMULATED"));

        InvestmentPanel panel = new InvestmentPanel();
        assertNotNull(panel.$$$getRootComponent$$$());
        assertInstanceOf(GridLayoutManager.class, panel.getRootPanel().getLayout());
        assertTrue(panel.getSimulatedPriceLabel().getText().contains("SIMULATED"));
        assertEquals("0.0000 units", panel.getHoldingsUnitsLabel().getText());
        assertEquals(2, panel.getOperationComboBox().getItemCount());
        assertNotNull(panel.getActivityTableModel());
        assertSame(panel.getActivityTableModel(), panel.getActivityTable().getModel());

        User investor = authService.registerUser("Elena Torres", "09172000001", "elena@otog.ph", "Secret123");
        Wallet wallet = walletService.getWalletByUserId(investor.getUserId());
        walletService.cashIn("REQ-INV-FUND-0", wallet.getWalletId(), null, new BigDecimal("500.00"), "Seed");
        SessionManager.getInstance().login(investor, walletService.getWallet(wallet.getWalletId()));
        authService.setPin(investor.getUserId(), "Secret123", "1234");
        authService.verifyPin(investor.getUserId(), "1234");

        // Multiple product types are deferred/rejected
        WalletException ex = assertThrows(WalletException.class, () ->
                walletService.invest("REQ-INV-MULTI", wallet.getWalletId(),
                        "High Risk Crypto Fund", new BigDecimal("100.00"), BigDecimal.ONE));
        assertTrue(ex.getMessage().contains("Only the seeded simulated product"));
    }

    @Test
    void testPreviewAndAtomicBuyDebitsWalletIncreasesHoldingsAndNotifies() {
        User investor = authService.registerUser("Marco Diaz", "09172000002", "marco@otog.ph", "Secret123");
        Wallet wallet = walletService.getWalletByUserId(investor.getUserId());
        walletService.cashIn("REQ-INV-CASHIN-1", wallet.getWalletId(), null, new BigDecimal("500.00"), "Deposit");
        SessionManager.getInstance().login(investor, walletService.getWallet(wallet.getWalletId()));
        authService.setPin(investor.getUserId(), "Secret123", "1234");

        InvestmentService.InvestmentPreview previewByAmount =
                walletService.previewBuy(wallet.getWalletId(), null, new BigDecimal("250.00"));
        assertEquals("BUY", previewByAmount.getOperation());
        assertEquals(new BigDecimal("2.5000"), previewByAmount.getUnits());
        assertEquals(new BigDecimal("250.00"), previewByAmount.getTotalAmount());
        assertEquals(new BigDecimal("0.0000"), previewByAmount.getCurrentHoldingUnits());
        assertEquals(new BigDecimal("2.5000"), previewByAmount.getProjectedHoldingUnits());
        assertTrue(previewByAmount.toConfirmationText().contains("[SIMULATED]"));

        InvestMoneyTransaction buyTxn = walletService.buyInvestmentWithPin(
                "REQ-INV-BUY-1", wallet.getWalletId(), new BigDecimal("2.5000"), null, "1234");
        assertEquals(TransactionType.INVESTMENT_BUY, buyTxn.getType());
        assertEquals(TransactionStatus.COMPLETED, buyTxn.getStatus());
        assertEquals(new BigDecimal("250.00"), buyTxn.getAmount());
        assertEquals(new BigDecimal("2.5000"), buyTxn.getUnits());
        assertEquals("OTOG Simulated Balanced Fund", buyTxn.getProduct());

        // BUY receipt includes Est. Returns
        String buyReceipt = walletService.getTransactionReceipt(wallet.getWalletId(), buyTxn.getReferenceNumber());
        assertTrue(buyReceipt.contains("Est. Returns"));
        assertTrue(buyReceipt.contains("OTOG Simulated Balanced Fund"));

        // CAP-08 post-commit notification created for INVESTMENT_PURCHASED
        List<Notification> notifs = walletService.getNotifications(investor.getUserId(), false);
        assertTrue(notifs.stream().anyMatch(n ->
                n.getType() == NotificationType.INVESTMENT_PURCHASED
                        && buyTxn.getReferenceNumber().equals(n.getReferenceNumber())));

        assertEquals(new BigDecimal("250.00"), walletService.getBalance(wallet.getWalletId()));
        InvestmentService.InvestmentHolding holdingAfterFirstBuy = walletService.getHolding(wallet.getWalletId());
        assertEquals(new BigDecimal("2.5000"), holdingAfterFirstBuy.getUnits());
        assertEquals(new BigDecimal("250.00"), holdingAfterFirstBuy.getSimulatedMarketValue());

        List<LedgerEntry> ledger = walletService.getLedgerRepository().findByWalletId(wallet.getWalletId());
        assertTrue(ledger.stream().anyMatch(e ->
                e.getEntryType() == LedgerEntryType.DEBIT
                        && e.getAmount().compareTo(new BigDecimal("250.00")) == 0
                        && buyTxn.getTransactionId().equals(e.getTransactionId())));

        // Second purchase increments existing holdings atomically
        walletService.buyInvestment("REQ-INV-BUY-2", wallet.getWalletId(), new BigDecimal("1.0000"), null);
        assertEquals(new BigDecimal("150.00"), walletService.getBalance(wallet.getWalletId()));
        assertEquals(new BigDecimal("3.5000"), walletService.getHolding(wallet.getWalletId()).getUnits());
    }

    @Test
    void testRedeemCreditsWalletNotifiesAndRejectsInsufficientFundsOversellingAndZeroProceeds() {
        User investor = authService.registerUser("Nadia Lim", "09172000003", "nadia@otog.ph", "Secret123");
        Wallet wallet = walletService.getWalletByUserId(investor.getUserId());
        walletService.cashIn("REQ-INV-CASHIN-2", wallet.getWalletId(), null, new BigDecimal("500.00"), "Deposit");
        SessionManager.getInstance().login(investor, walletService.getWallet(wallet.getWalletId()));
        authService.setPin(investor.getUserId(), "Secret123", "1234");

        // Insufficient wallet funds rejected cleanly
        assertThrows(InsufficientFundsException.class, () ->
                walletService.previewBuy(wallet.getWalletId(), new BigDecimal("10.0000"), null));
        assertThrows(InsufficientFundsException.class, () ->
                walletService.buyInvestmentWithPin("REQ-INV-FAIL-BUY", wallet.getWalletId(),
                        new BigDecimal("10.0000"), null, "1234"));
        assertEquals(new BigDecimal("500.00"), walletService.getBalance(wallet.getWalletId()));
        assertEquals(new BigDecimal("0.0000"), walletService.getHolding(wallet.getWalletId()).getUnits());

        // Purchase 3 units (PHP 300.00), leaving PHP 200.00 balance
        InvestMoneyTransaction initialBuy = walletService.buyInvestmentWithPin(
                "REQ-INV-BUY-3", wallet.getWalletId(), new BigDecimal("3.0000"), null, "1234");
        assertEquals(new BigDecimal("200.00"), walletService.getBalance(wallet.getWalletId()));

        // Preview and redeem 1.25 units (PHP 125.00 proceeds)
        InvestmentService.InvestmentPreview redeemPreview =
                walletService.previewRedeem(wallet.getWalletId(), new BigDecimal("1.2500"));
        assertEquals("REDEEM", redeemPreview.getOperation());
        assertEquals(new BigDecimal("125.00"), redeemPreview.getTotalAmount());
        assertEquals(new BigDecimal("1.7500"), redeemPreview.getProjectedHoldingUnits());

        InvestMoneyTransaction redeemTxn = walletService.redeemInvestment(
                "REQ-INV-REDEEM-1", wallet.getWalletId(), new BigDecimal("1.2500"));
        assertEquals(TransactionType.INVESTMENT_REDEEM, redeemTxn.getType());
        assertEquals(TransactionStatus.COMPLETED, redeemTxn.getStatus());
        assertEquals(new BigDecimal("125.00"), redeemTxn.getAmount());
        assertEquals(new BigDecimal("1.2500"), redeemTxn.getUnits());
        assertEquals(new BigDecimal("325.00"), walletService.getBalance(wallet.getWalletId()));
        assertEquals(new BigDecimal("1.7500"), walletService.getHolding(wallet.getWalletId()).getUnits());

        // REDEEM receipt omits Est. Returns
        String redeemReceipt = walletService.getTransactionReceipt(wallet.getWalletId(), redeemTxn.getReferenceNumber());
        assertFalse(redeemReceipt.contains("Est. Returns"),
                "Redemption receipt must not show Est. Returns for liquidated units");

        // CAP-08 post-commit notification created for INVESTMENT_REDEEMED
        List<Notification> notifs = walletService.getNotifications(investor.getUserId(), false);
        assertTrue(notifs.stream().anyMatch(n ->
                n.getType() == NotificationType.INVESTMENT_REDEEMED
                        && redeemTxn.getReferenceNumber().equals(n.getReferenceNumber())));

        List<LedgerEntry> ledger = walletService.getLedgerRepository().findByWalletId(wallet.getWalletId());
        assertTrue(ledger.stream().anyMatch(e ->
                e.getEntryType() == LedgerEntryType.CREDIT
                        && e.getAmount().compareTo(new BigDecimal("125.00")) == 0
                        && redeemTxn.getTransactionId().equals(e.getTransactionId())));

        // Reject overselling (> 1.7500 held units)
        WalletException oversellPreviewEx = assertThrows(WalletException.class, () ->
                walletService.previewRedeem(wallet.getWalletId(), new BigDecimal("2.0000")));
        assertTrue(oversellPreviewEx.getMessage().contains("Overselling"));

        WalletException oversellEx = assertThrows(WalletException.class, () ->
                walletService.redeemInvestment("REQ-INV-OVERSELL", wallet.getWalletId(), new BigDecimal("2.0000")));
        assertTrue(oversellEx.getMessage().contains("Overselling"));
        assertEquals(new BigDecimal("325.00"), walletService.getBalance(wallet.getWalletId()));
        assertEquals(new BigDecimal("1.7500"), walletService.getHolding(wallet.getWalletId()).getUnits());

        // Reject 0.00 proceeds in previewRedeem (matching redeemInvestment)
        OtogConfig.getInstance().setOverride("investment.simulated.unit.price", "10.00");
        assertThrows(InvalidAmountException.class, () ->
                walletService.previewRedeem(wallet.getWalletId(), new BigDecimal("0.0001")));
        OtogConfig.getInstance().clearOverrides();

        // Redeem remaining 1.7500 units cleanly to zero holdings
        walletService.redeemInvestment("REQ-INV-REDEEM-2", wallet.getWalletId(), new BigDecimal("1.7500"));
        assertEquals(new BigDecimal("500.00"), walletService.getBalance(wallet.getWalletId()));
        assertEquals(new BigDecimal("0.0000"), walletService.getHolding(wallet.getWalletId()).getUnits());

        // withdrawInvestment by transaction ID rejects when holdings already exhausted
        assertThrows(WalletException.class, () ->
                walletService.withdrawInvestment("REQ-INV-WITHDRAW-EXHAUSTED",
                        wallet.getWalletId(), initialBuy.getTransactionId()));
    }

    @Test
    void testHoldingsPersistAcrossRestartProductRenameAndLegacyMigration(@TempDir Path tempDir) throws Exception {
        String dbUrl = "jdbc:sqlite:" + tempDir.resolve("otog-invest-persist.db").toString().replace("\\", "/");
        String userId;
        String walletId;

        try (DatabaseManager db1 = new DatabaseManager(dbUrl)) {
            AuthServiceImpl auth1 = new AuthServiceImpl(db1);
            WalletService wallet1 = new WalletService(db1, auth1);
            User user = auth1.registerUser("Paolo Reyes", "09172000004", "paolo@otog.ph", "Secret123");
            Wallet wallet = wallet1.getWalletByUserId(user.getUserId());
            userId = user.getUserId();
            walletId = wallet.getWalletId();

            wallet1.cashIn("REQ-PERSIST-CASHIN", walletId, null, new BigDecimal("1000.00"), "Seed");
            SessionManager.getInstance().login(user, wallet1.getWallet(walletId));
            auth1.setPin(userId, "Secret123", "1234");
            wallet1.buyInvestmentWithPin("REQ-PERSIST-BUY", walletId, new BigDecimal("4.0000"), null, "1234");
            SessionManager.getInstance().reset();
        }

        try (DatabaseManager db2 = new DatabaseManager(dbUrl)) {
            AuthServiceImpl auth2 = new AuthServiceImpl(db2);
            WalletService wallet2 = new WalletService(db2, auth2);
            assertEquals(new BigDecimal("600.00"), wallet2.getBalance(walletId));

            // Persisted row in investment_products is read directly
            try (Connection conn = db2.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("UPDATE investment_products SET simulated_unit_price = '125.00' WHERE product_code = 'OTOG-SIM-FUND'");
            }
            assertEquals(new BigDecimal("125.00"), wallet2.getSeededProduct().getSimulatedUnitPrice());

            // Renaming product display name via config does NOT orphan holdings (keyed by immutable product_code)
            OtogConfig.getInstance().setOverride("investment.product.name", "OTOG Renamed Balanced Fund");
            InvestmentService.InvestmentHolding renamedHolding = wallet2.getHolding(walletId);
            assertEquals("OTOG Renamed Balanced Fund", renamedHolding.getProduct());
            assertEquals(new BigDecimal("4.0000"), renamedHolding.getUnits());
            assertEquals(new BigDecimal("500.00"), renamedHolding.getSimulatedMarketValue());
            OtogConfig.getInstance().clearOverrides();

            List<InvestMoneyTransaction> activities = wallet2.getInvestmentsByWalletId(walletId);
            assertEquals(1, activities.size());
            assertEquals("REQ-PERSIST-BUY", activities.get(0).getReferenceNumber());
            assertEquals(new BigDecimal("4.0000"), activities.get(0).getUnits());
            assertEquals("OTOG Simulated Balanced Fund", activities.get(0).getProduct());
        }

        // Legacy table migration test: table with old `product` column migrates cleanly to `product_code`
        String legacyUrl = "jdbc:sqlite:" + tempDir.resolve("otog-legacy-holdings.db").toString().replace("\\", "/");
        try (DatabaseManager legacyDb = new DatabaseManager(legacyUrl)) {
            AuthServiceImpl legacyAuth = new AuthServiceImpl(legacyDb);
            WalletService legacyWalletSvc = new WalletService(legacyDb, legacyAuth);
            User legacyUser = legacyAuth.registerUser("Legacy User", "09172000099", "legacy@otog.ph", "Secret123");
            String legacyWalletId = legacyWalletSvc.getWalletByUserId(legacyUser.getUserId()).getWalletId();

            try (Connection conn = legacyDb.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("DROP TABLE investment_holdings");
                stmt.execute("CREATE TABLE investment_holdings (" +
                        "wallet_id TEXT NOT NULL, product TEXT NOT NULL, units TEXT NOT NULL, " +
                        "total_invested TEXT NOT NULL DEFAULT '0.00', updated_at TEXT NOT NULL, " +
                        "PRIMARY KEY (wallet_id, product))");
                stmt.execute("INSERT INTO investment_holdings VALUES ('" + legacyWalletId
                        + "', 'OTOG Simulated Balanced Fund', '2.5000', '250.00', '2026-11-20T10:00:00')");
            }
            legacyDb.initSchema();
            assertEquals(new BigDecimal("2.5000"), legacyWalletSvc.getHolding(legacyWalletId).getUnits());
        }
    }

    @Test
    void testCrossAccountProxyIsolationIncludingCalculateReturns() {
        User alice = authService.registerUser("Alice Invest", "09172000010", "alice.inv@otog.ph", "Secret123");
        User bob = authService.registerUser("Bob Invest", "09172000011", "bob.inv@otog.ph", "Secret123");
        Wallet aliceWallet = walletService.getWalletByUserId(alice.getUserId());
        Wallet bobWallet = walletService.getWalletByUserId(bob.getUserId());
        walletService.cashIn("REQ-ISO-A", aliceWallet.getWalletId(), null, new BigDecimal("500.00"), "Seed A");
        walletService.cashIn("REQ-ISO-B", bobWallet.getWalletId(), null, new BigDecimal("500.00"), "Seed B");

        SessionManager.getInstance().login(bob, walletService.getWallet(bobWallet.getWalletId()));
        authService.setPin(bob.getUserId(), "Secret123", "1234");
        InvestMoneyTransaction bobBuy = walletService.buyInvestmentWithPin(
                "REQ-ISO-BOB-BUY", bobWallet.getWalletId(), new BigDecimal("5.0000"), null, "1234");

        SessionManager.getInstance().login(alice, walletService.getWallet(aliceWallet.getWalletId()));
        authService.setPin(alice.getUserId(), "Secret123", "1234");
        InvestMoneyTransaction aliceBuy = walletService.buyInvestmentWithPin(
                "REQ-ISO-ALICE-BUY", aliceWallet.getWalletId(), new BigDecimal("2.0000"), null, "1234");

        ProtectedWalletOperationsProxy proxy = (ProtectedWalletOperationsProxy) ProtectedWalletOperationsProxy.wrap(walletService);
        assertTrue(proxy.supportsInvestments());

        // Alice can inspect her own returns, but NOT Bob's returns or holdings
        assertEquals(new BigDecimal("10.00"), proxy.calculateReturns(aliceBuy.getTransactionId()));
        assertThrows(SecurityException.class, () -> proxy.calculateReturns(bobBuy.getTransactionId()));
        assertThrows(SecurityException.class, () -> proxy.calculateReturns(bobBuy.getReferenceNumber()));
        assertThrows(SecurityException.class, () -> walletService.calculateReturns(bobBuy.getTransactionId()));
        assertThrows(SecurityException.class, () -> proxy.getHolding(bobWallet.getWalletId()));
        assertThrows(SecurityException.class, () -> proxy.getInvestmentsByWalletId(bobWallet.getWalletId()));
        assertThrows(SecurityException.class, () -> proxy.buyInvestment("REQ-HIJACK-B", bobWallet.getWalletId(), BigDecimal.ONE, null));
        assertThrows(SecurityException.class, () -> proxy.redeemInvestment("REQ-HIJACK-R", bobWallet.getWalletId(), BigDecimal.ONE));

        // Admin can inspect Bob's returns
        User admin = walletService.getUserRepository().findById("USR-ADMIN-01").orElseThrow();
        SessionManager.getInstance().login(admin, walletService.getWalletByUserId(admin.getUserId()));
        assertEquals(new BigDecimal("25.00"), proxy.calculateReturns(bobBuy.getTransactionId()));
    }

    @Test
    void testNonDivisorUnitPricesHaveZeroDriftAndUiPreviewThenEditWorks() throws Exception {
        User investor = authService.registerUser("Tessa Cruz", "09172000012", "tessa@otog.ph", "Secret123");
        Wallet wallet = walletService.getWalletByUserId(investor.getUserId());
        walletService.cashIn("REQ-ROUND-SEED", wallet.getWalletId(), null, new BigDecimal("500.00"), "Seed");
        SessionManager.getInstance().login(investor, walletService.getWallet(wallet.getWalletId()));
        authService.setPin(investor.getUserId(), "Secret123", "1234");

        // Price = 300.00, Amount = 100.00 -> canonical units = 0.3333, exactCost = 99.99 (no drift)
        OtogConfig.getInstance().setOverride("investment.simulated.unit.price", "300.00");
        InvestmentService.InvestmentPreview p300 = walletService.previewBuy(wallet.getWalletId(), null, new BigDecimal("100.00"));
        assertEquals(new BigDecimal("0.3333"), p300.getUnits());
        assertEquals(new BigDecimal("99.99"), p300.getTotalAmount());
        InvestMoneyTransaction buy300 = walletService.buyInvestmentWithPin(
                p300.getReferenceNumber(), wallet.getWalletId(), p300.getUnits(), p300.getTotalAmount(), "1234");
        assertEquals(p300.getTotalAmount(), buy300.getAmount());
        assertEquals(new BigDecimal("400.01"), walletService.getBalance(wallet.getWalletId()));

        // Price = 700.00, Amount = 100.00 -> canonical units = 0.1428, exactCost = 99.96 (no crash, <= budget)
        OtogConfig.getInstance().setOverride("investment.simulated.unit.price", "700.00");
        InvestmentService.InvestmentPreview p700 = walletService.previewBuy(wallet.getWalletId(), null, new BigDecimal("100.00"));
        assertEquals(new BigDecimal("0.1428"), p700.getUnits());
        assertEquals(new BigDecimal("99.96"), p700.getTotalAmount());
        InvestMoneyTransaction buy700 = walletService.buyInvestment(
                p700.getReferenceNumber(), wallet.getWalletId(), p700.getUnits(), p700.getTotalAmount());
        assertEquals(p700.getTotalAmount(), buy700.getAmount());

        // Reset to default 100.00 and test UI preview-then-edit companion field clearing
        OtogConfig.getInstance().clearOverrides();
        ProtectedWalletOperationsProxy proxy = (ProtectedWalletOperationsProxy) ProtectedWalletOperationsProxy.wrap(walletService);
        activeDashboard = new Main(proxy, authService, null, proxy);
        activeDashboard.getInitialLoadFuture().get(5, TimeUnit.SECONDS);

        InvestmentService.InvestmentPreview firstPreview =
                activeDashboard.previewInvestmentAsync("BUY", "", "200.00").get(5, TimeUnit.SECONDS);
        assertEquals(new BigDecimal("2.0000"), firstPreview.getUnits());
        assertEquals("2.0000", activeDashboard.getInvestUnitsField().getText());
        assertEquals("200.00", activeDashboard.getInvestAmountField().getText());

        // Editing amount to 300.00 automatically clears units so subsequent preview succeeds
        SwingUtilities.invokeAndWait(() -> activeDashboard.getInvestAmountField().setText("300.00"));
        assertEquals("", activeDashboard.getInvestUnitsField().getText());

        InvestmentService.InvestmentPreview secondPreview = activeDashboard.previewInvestmentAsync(
                "BUY",
                activeDashboard.getInvestUnitsField().getText(),
                activeDashboard.getInvestAmountField().getText()).get(5, TimeUnit.SECONDS);
        assertEquals(new BigDecimal("3.0000"), secondPreview.getUnits());
        assertEquals(new BigDecimal("300.00"), secondPreview.getTotalAmount());
    }

    @Test
    void testInteractiveConfirmPreservesPreviewReferenceAndDisablesSubmitDuringPreview() throws Exception {
        User investor = authService.registerUser("Victor Santos", "09172000013", "victor@otog.ph", "Secret123");
        Wallet wallet = walletService.getWalletByUserId(investor.getUserId());
        walletService.cashIn("REQ-CONF-SEED", wallet.getWalletId(), null, new BigDecimal("500.00"), "Seed");
        SessionManager.getInstance().login(investor, walletService.getWallet(wallet.getWalletId()));
        authService.setPin(investor.getUserId(), "Secret123", "1234");

        AtomicReference<String> confirmedPreviewRef = new AtomicReference<>();
        AtomicBoolean submitDisabledDuringConfirm = new AtomicBoolean(false);
        CountDownLatch completionDialogLatch = new CountDownLatch(1);
        AtomicReference<String> completionDialogText = new AtomicReference<>();

        ProtectedWalletOperationsProxy proxy = (ProtectedWalletOperationsProxy) ProtectedWalletOperationsProxy.wrap(walletService);
        activeDashboard = new Main(proxy, authService, null, proxy) {
            @Override
            protected int showConfirmDialog(Component parent, String title, Object message, int optionType, int messageType) {
                submitDisabledDuringConfirm.set(!getSubmitInvestButton().isEnabled());
                if (message instanceof JScrollPane sp && sp.getViewport().getView() instanceof JTextArea area) {
                    Matcher m = Pattern.compile("Reference:\\s*(\\S+)").matcher(area.getText());
                    if (m.find()) {
                        confirmedPreviewRef.set(m.group(1));
                    }
                }
                return JOptionPane.OK_OPTION;
            }

            @Override
            protected void showDialogMessage(Component parent, String title, String message, int messageType) {
                if ("Investment Complete".equals(title)) {
                    completionDialogText.set(message);
                    completionDialogLatch.countDown();
                }
            }
        };
        activeDashboard.getInitialLoadFuture().get(5, TimeUnit.SECONDS);

        SwingUtilities.invokeAndWait(() -> {
            activeDashboard.getInvestUnitsField().setText("2.0000");
            activeDashboard.getInvestPinField().setText("1234");
            activeDashboard.getSubmitInvestButton().doClick();
        });

        assertTrue(completionDialogLatch.await(5, TimeUnit.SECONDS), "Interactive submit should complete");
        assertTrue(submitDisabledDuringConfirm.get(), "Submit button must remain disabled during preview and confirmation");
        assertNotNull(confirmedPreviewRef.get());
        assertTrue(completionDialogText.get().contains(confirmedPreviewRef.get()),
                "Completion dialog must match the preview reference number");

        List<InvestMoneyTransaction> txns = walletService.getInvestmentsByWalletId(wallet.getWalletId());
        assertEquals(1, txns.size());
        assertEquals(confirmedPreviewRef.get(), txns.get(0).getReferenceNumber());
    }

    @Test
    void testDuplicateTrimmedRefsUnknownOpBuilderDirectionUsdCurrencyAndAccountStatus() throws Exception {
        User investor = authService.registerUser("Gino Ramos", "09172000014", "gino@otog.ph", "Secret123");
        Wallet wallet = walletService.getWalletByUserId(investor.getUserId());
        walletService.cashIn("REQ-EDGE-SEED", wallet.getWalletId(), null, new BigDecimal("500.00"), "Seed");
        SessionManager.getInstance().login(investor, walletService.getWallet(wallet.getWalletId()));
        authService.setPin(investor.getUserId(), "Secret123", "1234");

        // Untrimmed duplicate references are rejected on buy and redeem
        walletService.buyInvestmentWithPin("REQ-DUP-INV", wallet.getWalletId(), BigDecimal.ONE, null, "1234");
        assertThrows(DuplicateTransactionException.class, () ->
                walletService.buyInvestment(" REQ-DUP-INV ", wallet.getWalletId(), BigDecimal.ONE, null));
        walletService.redeemInvestment("REQ-DUP-RED", wallet.getWalletId(), new BigDecimal("0.5000"));
        assertThrows(DuplicateTransactionException.class, () ->
                walletService.redeemInvestment("  REQ-DUP-RED  ", wallet.getWalletId(), new BigDecimal("0.5000")));

        // TransactionBuilder sets targetWalletId on INVESTMENT_REDEEM
        InvestMoneyTransaction builtRedeem = new TransactionBuilder()
                .referenceNumber("REQ-BUILD-RED")
                .to(wallet.getWalletId())
                .amount(new BigDecimal("100.00"))
                .product("OTOG Simulated Balanced Fund")
                .units(BigDecimal.ONE)
                .type(TransactionType.INVESTMENT_REDEEM)
                .currency("USD")
                .buildInvestment();
        assertNull(builtRedeem.getSourceWalletId());
        assertEquals(wallet.getWalletId(), builtRedeem.getTargetWalletId());
        assertEquals("USD", builtRedeem.getCurrency());

        // USD wallet preserves USD currency on buy and redeem
        User usdUser = new User(IdGenerator.nextUserId(), "USD Holder", "09172000015",
                "usd@otog.ph", UserRole.USER, UserStatus.ACTIVE);
        walletService.getUserRepository().save(usdUser);
        Wallet usdWallet = walletService.createWallet(usdUser.getUserId(), new BigDecimal("500.00"), "USD");
        SessionManager.getInstance().reset();
        WalletService noPinWalletSvc = new WalletService(databaseManager);
        InvestMoneyTransaction usdBuy = noPinWalletSvc.buyInvestment("REQ-USD-BUY", usdWallet.getWalletId(), BigDecimal.ONE, null);
        assertEquals("USD", usdBuy.getCurrency());
        InvestMoneyTransaction usdRedeem = noPinWalletSvc.redeemInvestment("REQ-USD-RED", usdWallet.getWalletId(), BigDecimal.ONE);
        assertEquals("USD", usdRedeem.getCurrency());

        // Suspended, locked users and frozen wallets are blocked on BOTH preview and execute
        User lockedUser = authService.registerUser("Locked User", "09172000016", "locked@otog.ph", "Secret123");
        Wallet lockedWallet = walletService.getWalletByUserId(lockedUser.getUserId());
        walletService.cashIn("REQ-LOCK-SEED", lockedWallet.getWalletId(), null, new BigDecimal("200.00"), "Seed");
        walletService.getUserRepository().updateStatus(lockedUser.getUserId(), UserStatus.LOCKED);
        assertThrows(WalletException.class, () -> noPinWalletSvc.previewBuy(lockedWallet.getWalletId(), BigDecimal.ONE, null));
        assertThrows(WalletException.class, () -> noPinWalletSvc.previewRedeem(lockedWallet.getWalletId(), BigDecimal.ONE));
        assertThrows(WalletException.class, () -> noPinWalletSvc.buyInvestment("REQ-LOCK-BUY", lockedWallet.getWalletId(), BigDecimal.ONE, null));

        walletService.getUserRepository().updateStatus(lockedUser.getUserId(), UserStatus.ACTIVE);
        Wallet refreshedLockedWallet = walletService.getWallet(lockedWallet.getWalletId());
        refreshedLockedWallet.setStatus(WalletStatus.FROZEN);
        walletService.getWalletRepository().update(refreshedLockedWallet);
        assertThrows(WalletException.class, () -> noPinWalletSvc.previewBuy(lockedWallet.getWalletId(), BigDecimal.ONE, null));
        assertThrows(WalletException.class, () -> noPinWalletSvc.buyInvestment("REQ-FROZEN-BUY", lockedWallet.getWalletId(), BigDecimal.ONE, null));

        // Unknown operation string ("SELL") rejected in Main UI
        SessionManager.getInstance().login(investor, walletService.getWallet(wallet.getWalletId()));
        ProtectedWalletOperationsProxy proxy = (ProtectedWalletOperationsProxy) ProtectedWalletOperationsProxy.wrap(walletService);
        activeDashboard = new Main(proxy, authService, null, proxy);
        activeDashboard.getInitialLoadFuture().get(5, TimeUnit.SECONDS);

        ExecutionException previewSellEx = assertThrows(ExecutionException.class, () ->
                activeDashboard.previewInvestmentAsync("SELL", "1.0000", null).get(5, TimeUnit.SECONDS));
        assertInstanceOf(IllegalArgumentException.class, previewSellEx.getCause());

        ExecutionException submitSellEx = assertThrows(ExecutionException.class, () ->
                activeDashboard.submitInvestmentAsync("SELL", "1.0000", null, "1234").get(5, TimeUnit.SECONDS));
        assertInstanceOf(IllegalArgumentException.class, submitSellEx.getCause());
    }

    @Test
    void testConcurrentBuyAndRedeemRacesPreventDoubleSpendAndOversell() throws Exception {
        WalletService concurrentSvc = new WalletService(databaseManager);
        User user = new User(IdGenerator.nextUserId(), "Race Investor", "09172000017",
                "race@otog.ph", UserRole.USER, UserStatus.ACTIVE);
        concurrentSvc.getUserRepository().save(user);
        Wallet wallet = concurrentSvc.createWallet(user.getUserId(), new BigDecimal("100.00"), "PHP");

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            CountDownLatch buyStart = new CountDownLatch(1);
            AtomicInteger buySuccess = new AtomicInteger();
            List<Future<?>> buyFutures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                final int idx = i;
                buyFutures.add(pool.submit(() -> {
                    buyStart.await();
                    try {
                        concurrentSvc.buyInvestment("REQ-RACE-BUY-" + idx, wallet.getWalletId(), BigDecimal.ONE, null);
                        buySuccess.incrementAndGet();
                    } catch (InsufficientFundsException ignored) {
                    }
                    return null;
                }));
            }
            buyStart.countDown();
            for (Future<?> f : buyFutures) {
                f.get(5, TimeUnit.SECONDS);
            }
            assertEquals(1, buySuccess.get(), "Exactly one buy should succeed on PHP 100.00 balance");
            assertEquals(new BigDecimal("0.00"), concurrentSvc.getBalance(wallet.getWalletId()));
            assertEquals(new BigDecimal("1.0000"), concurrentSvc.getHolding(wallet.getWalletId()).getUnits());

            CountDownLatch redeemStart = new CountDownLatch(1);
            AtomicInteger redeemSuccess = new AtomicInteger();
            List<Future<?>> redeemFutures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                final int idx = i;
                redeemFutures.add(pool.submit(() -> {
                    redeemStart.await();
                    try {
                        concurrentSvc.redeemInvestment("REQ-RACE-RED-" + idx, wallet.getWalletId(), BigDecimal.ONE);
                        redeemSuccess.incrementAndGet();
                    } catch (WalletException ignored) {
                    }
                    return null;
                }));
            }
            redeemStart.countDown();
            for (Future<?> f : redeemFutures) {
                f.get(5, TimeUnit.SECONDS);
            }
            assertEquals(1, redeemSuccess.get(), "Exactly one redeem should succeed on 1.0000 held unit");
            assertEquals(new BigDecimal("100.00"), concurrentSvc.getBalance(wallet.getWalletId()));
            assertEquals(new BigDecimal("0.0000"), concurrentSvc.getHolding(wallet.getWalletId()).getUnits());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void testDashboardAsyncFlowRepeatDraftStatementsAndPinLockout(@TempDir Path tempDir) throws Exception {
        User investor = authService.registerUser("Ria Gomez", "09172000005", "ria@otog.ph", "Secret123");
        Wallet wallet = walletService.getWalletByUserId(investor.getUserId());
        walletService.cashIn("REQ-UI-INV-SEED", wallet.getWalletId(), null, new BigDecimal("500.00"), "Seed");
        SessionManager.getInstance().login(investor, walletService.getWallet(wallet.getWalletId()));
        authService.setPin(investor.getUserId(), "Secret123", "1234");

        ProtectedWalletOperationsProxy proxy = (ProtectedWalletOperationsProxy) ProtectedWalletOperationsProxy.wrap(walletService);
        AtomicBoolean logoutTriggered = new AtomicBoolean(false);
        activeDashboard = new Main(proxy, authService, null, proxy);
        activeDashboard.setOnLogoutCallback(() -> logoutTriggered.set(true));
        activeDashboard.getInitialLoadFuture().get(5, TimeUnit.SECONDS);

        assertTrue(activeDashboard.getVisibleTabTitles().contains("Investments"));
        assertTrue(activeDashboard.getInvestQuickNavButton().isVisible());
        SwingUtilities.invokeAndWait(() -> activeDashboard.getInvestQuickNavButton().doClick());
        assertEquals("Investments", activeDashboard.getTabs().getTitleAt(activeDashboard.getTabs().getSelectedIndex()));

        // Async preview
        InvestmentService.InvestmentPreview preview =
                activeDashboard.previewInvestmentAsync("BUY", "2.0000", null).get(5, TimeUnit.SECONDS);
        assertEquals(new BigDecimal("200.00"), preview.getTotalAmount());
        assertTrue(activeDashboard.getInvestPreviewSummaryLabel().getText().contains("[SIMULATED]"));

        // Async buy off EDT
        InvestMoneyTransaction buyTxn = activeDashboard.submitInvestmentAsync(
                "BUY", "2.0000", null, "1234").get(5, TimeUnit.SECONDS);
        assertTrue(activeDashboard.isLastPaymentExecutedOffEdt());
        assertEquals(TransactionType.INVESTMENT_BUY, buyTxn.getType());
        assertEquals("PHP 300.00", activeDashboard.getBalanceLabel().getText());
        assertEquals("2.0000 units", activeDashboard.getInvestmentPanel().getHoldingsUnitsLabel().getText());
        assertEquals(1, activeDashboard.getInvestmentPanel().getActivityTable().getRowCount());

        // Async redeem off EDT
        InvestMoneyTransaction redeemTxn = activeDashboard.submitInvestmentAsync(
                "REDEEM", "0.5000", null, "1234").get(5, TimeUnit.SECONDS);
        assertTrue(activeDashboard.isLastPaymentExecutedOffEdt());
        assertEquals(TransactionType.INVESTMENT_REDEEM, redeemTxn.getType());
        assertEquals("PHP 350.00", activeDashboard.getBalanceLabel().getText());
        assertEquals("1.5000 units", activeDashboard.getInvestmentPanel().getHoldingsUnitsLabel().getText());
        assertEquals(2, activeDashboard.getInvestmentPanel().getActivityTable().getRowCount());

        // Repeat draft for investment row pre-fills InvestmentPanel
        SwingUtilities.invokeAndWait(() -> {
            activeDashboard.navigateToTab("Overview");
            activeDashboard.getRecentTransactionsTable().setRowSelectionInterval(0, 0);
            assertTrue(activeDashboard.getRepeatDraftButton().isEnabled());
        });
        Transaction draft = activeDashboard.createRepeatDraftForSelectedAsync().get(5, TimeUnit.SECONDS);
        assertInstanceOf(InvestMoneyTransaction.class, draft);
        assertEquals("Investments", activeDashboard.getTabs().getTitleAt(activeDashboard.getTabs().getSelectedIndex()));
        assertEquals("0.5000", activeDashboard.getInvestUnitsField().getText());

        // Statement export/import includes investment activity cleanly
        Path stmtFile = tempDir.resolve("invest-statement.csv");
        activeDashboard.exportStatementAsync(stmtFile).get(5, TimeUnit.SECONDS);
        StatementFileService.LoadedStatement loaded = activeDashboard.importStatementAsync(stmtFile).get(5, TimeUnit.SECONDS);
        assertTrue(loaded.getEntries().stream().anyMatch(e -> e.getReferenceNumber().equals(buyTxn.getReferenceNumber())));
        assertTrue(loaded.getEntries().stream().anyMatch(e -> e.getReferenceNumber().equals(redeemTxn.getReferenceNumber())));

        // Async oversell rejected with error feedback
        ExecutionException ex = assertThrows(ExecutionException.class, () ->
                activeDashboard.submitInvestmentAsync("REDEEM", "5.0000", null, "1234").get(5, TimeUnit.SECONDS));
        assertInstanceOf(WalletException.class, ex.getCause());
        assertTrue(activeDashboard.getInvestFeedbackLabel().getText().contains("failed"));
        assertEquals("1.5000 units", activeDashboard.getInvestmentPanel().getHoldingsUnitsLabel().getText());

        // 3 wrong PINs trigger session lockout and logout callback
        for (int attempt = 0; attempt < 3; attempt++) {
            assertThrows(ExecutionException.class, () ->
                    activeDashboard.submitInvestmentAsync("BUY", "1.0000", null, "9999").get(5, TimeUnit.SECONDS));
        }
        assertTrue(SessionManager.getInstance().isLockedOutByPin());
        SwingUtilities.invokeAndWait(() -> {});
        assertTrue(logoutTriggered.get(), "3 failed PIN attempts must trigger logout callback");
    }

    @Test
    void testUserLoginOpenDashboardWithNonInvestmentWalletOperationsDoesNotCrash() throws Exception {
        User user = authService.registerUser("Custom Ops User", "09172000018", "custom@otog.ph", "Secret123");
        Wallet wallet = walletService.getWalletByUserId(user.getUserId());

        // WalletOperations implementation that intentionally does NOT implement InvestmentService
        WalletOperations basicOps = new WalletOperations() {
            @Override
            public Wallet createWallet(String userId, BigDecimal initialBalance, String currency) {
                return walletService.createWallet(userId, initialBalance, currency);
            }

            @Override
            public Wallet getWallet(String walletId) {
                return walletService.getWallet(walletId);
            }

            @Override
            public Wallet getWalletByUserId(String userId) {
                return walletService.getWalletByUserId(userId);
            }

            @Override
            public BigDecimal getBalance(String walletId) {
                return walletService.getBalance(walletId);
            }

            @Override
            public Transaction transfer(String ref, String src, String tgt, BigDecimal amt, BigDecimal fee, String desc) {
                return walletService.transfer(ref, src, tgt, amt, fee, desc);
            }

            @Override
            public Transaction transferWithPin(String ref, String src, String tgt, BigDecimal amt, BigDecimal fee, String desc, String pin) {
                return walletService.transferWithPin(ref, src, tgt, amt, fee, desc, pin);
            }

            @Override
            public Transaction cashIn(String ref, String tgt, String pmId, BigDecimal amt, String desc) {
                return walletService.cashIn(ref, tgt, pmId, amt, desc);
            }

            @Override
            public Transaction cashOut(String ref, String src, String pmId, BigDecimal amt, BigDecimal fee, String desc) {
                return walletService.cashOut(ref, src, pmId, amt, fee, desc);
            }

            @Override
            public Transaction cashOutWithPin(String ref, String src, String pmId, BigDecimal amt, BigDecimal fee, String desc, String pin) {
                return walletService.cashOutWithPin(ref, src, pmId, amt, fee, desc, pin);
            }
        };

        assertFalse(((ProtectedWalletOperationsProxy) ProtectedWalletOperationsProxy.wrap(basicOps)).supportsInvestments());
        assertNull(ProtectedWalletOperationsProxy.asInvestmentServiceOrNull(ProtectedWalletOperationsProxy.wrap(basicOps)));

        Class<?> loginClass = Class.forName("UserLogin");
        Object loginWindow = loginClass.getConstructor(AuthService.class, WalletOperations.class)
                .newInstance(authService, basicOps);
        try {
            SessionManager.getInstance().login(user, wallet);
            Method openDashboard = loginClass.getMethod("openDashboard");
            AtomicReference<Main> dashRef = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                try {
                    dashRef.set((Main) openDashboard.invoke(loginWindow));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            activeDashboard = dashRef.get();
            assertNotNull(activeDashboard);
            activeDashboard.getInitialLoadFuture().get(5, TimeUnit.SECONDS);

            assertFalse(activeDashboard.getVisibleTabTitles().contains("Investments"),
                    "Investments tab must remain hidden for non-InvestmentService delegates");
            assertEquals(Main.DashboardState.EMPTY, activeDashboard.getCurrentState(),
                    "Dashboard refresh must succeed cleanly without ERROR state");
        } finally {
            if (loginWindow instanceof JFrame frame) {
                SwingUtilities.invokeAndWait(frame::dispose);
            }
        }
    }
}
