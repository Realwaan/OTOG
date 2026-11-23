package com.otog;

import com.intellij.uiDesigner.core.GridLayoutManager;
import com.otog.database.DatabaseManager;
import com.otog.exception.InsufficientFundsException;
import com.otog.exception.WalletException;
import com.otog.model.InvestMoneyTransaction;
import com.otog.model.LedgerEntry;
import com.otog.model.LedgerEntryType;
import com.otog.model.Transaction;
import com.otog.model.TransactionStatus;
import com.otog.model.TransactionType;
import com.otog.model.User;
import com.otog.model.Wallet;
import com.otog.service.AuthServiceImpl;
import com.otog.service.InvestmentService;
import com.otog.service.ProtectedWalletOperationsProxy;
import com.otog.service.SessionManager;
import com.otog.service.WalletService;
import com.otog.ui.InvestmentPanel;
import com.otog.ui.Main;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests CAP-13: Basic simulated investment workflow (seeded simulated product,
 * preview units & cost, atomic purchase/redemption, persisted holdings & activity,
 * insufficient funds & overselling rejection, and bound InvestmentPanel.form UI).
 */
public class InvestmentWorkflowTest {

    private DatabaseManager databaseManager;
    private AuthServiceImpl authService;
    private WalletService walletService;
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
    void testPreviewAndAtomicBuyDebitsWalletAndIncreasesHoldings() {
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
    void testRedeemCreditsWalletAndRejectsInsufficientFundsAndOverselling() {
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

        // Redeem remaining 1.7500 units cleanly to zero holdings
        walletService.redeemInvestment("REQ-INV-REDEEM-2", wallet.getWalletId(), new BigDecimal("1.7500"));
        assertEquals(new BigDecimal("500.00"), walletService.getBalance(wallet.getWalletId()));
        assertEquals(new BigDecimal("0.0000"), walletService.getHolding(wallet.getWalletId()).getUnits());

        // withdrawInvestment by transaction ID also rejects when holdings already exhausted
        assertThrows(WalletException.class, () ->
                walletService.withdrawInvestment("REQ-INV-WITHDRAW-EXHAUSTED",
                        wallet.getWalletId(), initialBuy.getTransactionId()));
    }

    @Test
    void testHoldingsAndActivityPersistAcrossDatabaseRestart(@TempDir Path tempDir) {
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

            InvestmentService.InvestmentHolding persistedHolding = wallet2.getHolding(walletId);
            assertEquals("OTOG Simulated Balanced Fund", persistedHolding.getProduct());
            assertEquals(new BigDecimal("4.0000"), persistedHolding.getUnits());
            assertEquals(new BigDecimal("400.00"), persistedHolding.getSimulatedMarketValue());

            List<InvestMoneyTransaction> activities = wallet2.getInvestmentsByWalletId(walletId);
            assertEquals(1, activities.size());
            assertEquals("REQ-PERSIST-BUY", activities.get(0).getReferenceNumber());
            assertEquals(new BigDecimal("4.0000"), activities.get(0).getUnits());
            assertEquals("OTOG Simulated Balanced Fund", activities.get(0).getProduct());
        }
    }

    @Test
    void testDashboardInvestmentTabAsyncPreviewBuyRedeemAndOversellFeedback() throws Exception {
        User investor = authService.registerUser("Ria Gomez", "09172000005", "ria@otog.ph", "Secret123");
        Wallet wallet = walletService.getWalletByUserId(investor.getUserId());
        walletService.cashIn("REQ-UI-INV-SEED", wallet.getWalletId(), null, new BigDecimal("500.00"), "Seed");
        SessionManager.getInstance().login(investor, walletService.getWallet(wallet.getWalletId()));
        authService.setPin(investor.getUserId(), "Secret123", "1234");

        ProtectedWalletOperationsProxy proxy = (ProtectedWalletOperationsProxy) ProtectedWalletOperationsProxy.wrap(walletService);
        activeDashboard = new Main(proxy, authService, null, proxy);
        activeDashboard.getInitialLoadFuture().get(5, TimeUnit.SECONDS);

        assertTrue(activeDashboard.getVisibleTabTitles().contains("Investments"));
        assertTrue(activeDashboard.navigateToTab("Investments"));
        assertNotNull(activeDashboard.getInvestmentPanel());

        // Async preview
        InvestmentService.InvestmentPreview preview =
                activeDashboard.previewInvestmentAsync("BUY", "2.0000", null).get(5, TimeUnit.SECONDS);
        assertEquals(new BigDecimal("200.00"), preview.getTotalAmount());
        assertTrue(activeDashboard.getInvestPreviewSummaryLabel().getText().contains("[SIMULATED]"));

        // Async buy
        InvestMoneyTransaction buyTxn = activeDashboard.submitInvestmentAsync(
                "BUY", "2.0000", null, "1234").get(5, TimeUnit.SECONDS);
        assertEquals(TransactionType.INVESTMENT_BUY, buyTxn.getType());
        assertEquals("PHP 300.00", activeDashboard.getBalanceLabel().getText());
        assertEquals("2.0000 units", activeDashboard.getInvestmentPanel().getHoldingsUnitsLabel().getText());
        assertEquals(1, activeDashboard.getInvestmentPanel().getActivityTable().getRowCount());

        // Async redeem
        InvestMoneyTransaction redeemTxn = activeDashboard.submitInvestmentAsync(
                "REDEEM", "0.5000", null, "1234").get(5, TimeUnit.SECONDS);
        assertEquals(TransactionType.INVESTMENT_REDEEM, redeemTxn.getType());
        assertEquals("PHP 350.00", activeDashboard.getBalanceLabel().getText());
        assertEquals("1.5000 units", activeDashboard.getInvestmentPanel().getHoldingsUnitsLabel().getText());
        assertEquals(2, activeDashboard.getInvestmentPanel().getActivityTable().getRowCount());

        // Async oversell rejected with error feedback
        ExecutionException ex = assertThrows(ExecutionException.class, () ->
                activeDashboard.submitInvestmentAsync("REDEEM", "5.0000", null, "1234").get(5, TimeUnit.SECONDS));
        assertInstanceOf(WalletException.class, ex.getCause());
        assertTrue(activeDashboard.getInvestFeedbackLabel().getText().contains("failed"));
        assertEquals("1.5000 units", activeDashboard.getInvestmentPanel().getHoldingsUnitsLabel().getText());
    }
}
