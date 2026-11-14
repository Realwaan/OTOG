package com.otog;

import com.otog.database.DatabaseManager;
import com.otog.exception.EntityNotFoundException;
import com.otog.exception.InsufficientFundsException;
import com.otog.exception.InvalidAmountException;
import com.otog.model.CashInOutTransaction;
import com.otog.model.SendMoneyTransaction;
import com.otog.model.Transaction;
import com.otog.model.User;
import com.otog.model.Wallet;
import com.otog.repository.CredentialRepository;
import com.otog.service.AuthService;
import com.otog.service.AuthServiceImpl;
import com.otog.service.SessionManager;
import com.otog.service.WalletOperations;
import com.otog.service.WalletService;
import com.otog.ui.Main;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class DashboardAndNavigationTest {
    private DatabaseManager databaseManager;
    private WalletService walletService;
    private AuthServiceImpl authService;
    private Main activeDashboard;

    @BeforeEach
    void setUp() {
        databaseManager = new DatabaseManager("jdbc:sqlite::memory:");
        walletService = new WalletService(databaseManager);
        authService = new AuthServiceImpl(
                databaseManager,
                walletService.getUserRepository(),
                new CredentialRepository(databaseManager),
                walletService
        );
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
    void testUnauthenticatedDashboardCreationThrowsSecurityException() {
        assertFalse(SessionManager.getInstance().isLoggedIn());
        assertThrows(SecurityException.class, Main::new);
        assertThrows(SecurityException.class, () -> new Main(walletService));
    }

    @Test
    void testAuthenticatedLoginOpensDashboardOffEdtWithEmptyState() throws Exception {
        User alice = authService.registerUser("Alice Reyes", "09171001001", "alice@otog.ph", "Secret123");
        Wallet aliceWallet = walletService.getWalletByUserId(alice.getUserId());
        SessionManager.getInstance().login(alice, aliceWallet);

        activeDashboard = new Main(walletService);
        activeDashboard.getInitialLoadFuture().get(5, TimeUnit.SECONDS);

        assertTrue(activeDashboard.isLastLoadExecutedOffEdt(),
                "Initial wallet/activity fetch must run off the Swing EDT");
        assertTrue(activeDashboard.getWelcomeLabel().getText().contains("Alice Reyes"));
        assertEquals("PHP 0.00", activeDashboard.getBalanceLabel().getText());
        assertEquals(Main.DashboardState.EMPTY, activeDashboard.getCurrentState());
        assertTrue(activeDashboard.getEmptyStateLabel().getText().contains("No recent transactions"));
        assertEquals(0, activeDashboard.getRecentTransactionsTable().getRowCount());
    }

    @Test
    void testUnfinishedFeaturesAreHiddenAndWorkingTabsAreNavigable() throws Exception {
        User user = authService.registerUser("Bob Santos", "09171001002", "bob@otog.ph", "Secret123");
        SessionManager.getInstance().login(user, walletService.getWalletByUserId(user.getUserId()));

        activeDashboard = new Main(walletService);
        activeDashboard.getInitialLoadFuture().get(5, TimeUnit.SECONDS);

        List<String> visibleTabs = activeDashboard.getVisibleTabTitles();
        assertEquals(List.of("Overview", "Cash In / Out", "Send Money"), visibleTabs,
                "Only finished working features should be visible in demo build");
        assertFalse(visibleTabs.contains("Loans"), "Unfinished Loans tab must be hidden");
        assertFalse(visibleTabs.contains("Investments"), "Unfinished Investments tab must be hidden");

        assertTrue(activeDashboard.navigateToTab("Cash In / Out"));
        assertEquals("Cash In / Out", activeDashboard.getTabs().getTitleAt(activeDashboard.getTabs().getSelectedIndex()));

        assertTrue(activeDashboard.navigateToTab("Send Money"));
        assertEquals("Send Money", activeDashboard.getTabs().getTitleAt(activeDashboard.getTabs().getSelectedIndex()));

        assertFalse(activeDashboard.navigateToTab("Loans"));

        assertEquals(2, activeDashboard.getNavigationHistoryDepth());
        assertTrue(activeDashboard.navigateBack());
        assertEquals("Cash In / Out", activeDashboard.getTabs().getTitleAt(activeDashboard.getTabs().getSelectedIndex()));
        assertTrue(activeDashboard.navigateBack());
        assertEquals("Overview", activeDashboard.getTabs().getTitleAt(activeDashboard.getTabs().getSelectedIndex()));
        assertFalse(activeDashboard.navigateBack());
    }

    @Test
    void testCashInAndCashOutRefreshSessionBalanceAndRecentActivityOffEdt() throws Exception {
        User carol = authService.registerUser("Carol Cruz", "09171001003", "carol@otog.ph", "Secret123");
        SessionManager.getInstance().login(carol, walletService.getWalletByUserId(carol.getUserId()));

        activeDashboard = new Main(walletService);
        activeDashboard.getInitialLoadFuture().get(5, TimeUnit.SECONDS);

        Transaction cashInTxn = activeDashboard.submitCashOperationAsync("CASH_IN", "GCash", "500.00", "Allowance")
                .get(5, TimeUnit.SECONDS);
        assertNotNull(cashInTxn);
        assertInstanceOf(CashInOutTransaction.class, cashInTxn,
                "Cash-In should instantiate polymorphic CashInOutTransaction subclass");
        assertTrue(activeDashboard.isLastPaymentExecutedOffEdt(),
                "Cash-In execution must run off the Swing EDT");
        assertEquals(new BigDecimal("500.00"), SessionManager.getInstance().getCurrentWallet().getBalance());
        assertEquals("PHP 500.00", activeDashboard.getBalanceLabel().getText());
        assertEquals(Main.DashboardState.READY, activeDashboard.getCurrentState());
        assertEquals(1, activeDashboard.getRecentTransactionsTable().getRowCount());
        assertTrue(activeDashboard.getCashFeedbackLabel().getText().contains("successful"));

        Transaction cashOutTxn = activeDashboard.submitCashOperationAsync("CASH_OUT", "BPI", "125.50", "ATM")
                .get(5, TimeUnit.SECONDS);
        assertNotNull(cashOutTxn);
        assertInstanceOf(CashInOutTransaction.class, cashOutTxn,
                "Cash-Out should instantiate polymorphic CashInOutTransaction subclass");
        assertTrue(activeDashboard.isLastPaymentExecutedOffEdt(),
                "Cash-Out execution must run off the Swing EDT");
        assertEquals(new BigDecimal("374.50"), SessionManager.getInstance().getCurrentWallet().getBalance());
        assertEquals("PHP 374.50", activeDashboard.getBalanceLabel().getText());
        assertEquals(Main.DashboardState.READY, activeDashboard.getCurrentState());
        assertEquals(2, activeDashboard.getRecentTransactionsTable().getRowCount());
        assertTrue(cashOutTxn.compareTo(cashInTxn) <= 0,
                "Comparable<Transaction> must order newer transactions first");
    }

    @Test
    void testSendMoneyByMobileNumberUpdatesSenderAndRecipient() throws Exception {
        User sender = authService.registerUser("Dave Lim", "09171001004", "dave@otog.ph", "Secret123");
        User recipient = authService.registerUser("Eva Tan", "09171001005", "eva@otog.ph", "Secret123");

        Wallet senderWallet = walletService.getWalletByUserId(sender.getUserId());
        walletService.cashIn("REQ-INIT-01", senderWallet.getWalletId(), null, new BigDecimal("1000.00"), "Seed");
        SessionManager.getInstance().login(sender, walletService.getWallet(senderWallet.getWalletId()));

        activeDashboard = new Main(walletService);
        activeDashboard.getInitialLoadFuture().get(5, TimeUnit.SECONDS);
        assertEquals("PHP 1,000.00", activeDashboard.getBalanceLabel().getText());

        Transaction sendTxn = activeDashboard.submitSendMoneyAsync("09171001005", "250.00", "Dinner split")
                .get(5, TimeUnit.SECONDS);
        assertNotNull(sendTxn);
        assertInstanceOf(SendMoneyTransaction.class, sendTxn,
                "Send Money should instantiate polymorphic SendMoneyTransaction subclass");
        assertTrue(activeDashboard.isLastPaymentExecutedOffEdt(),
                "Send Money execution must run off the Swing EDT");
        assertEquals(new BigDecimal("750.00"), SessionManager.getInstance().getCurrentWallet().getBalance());
        assertEquals("PHP 750.00", activeDashboard.getBalanceLabel().getText());
        assertEquals(Main.DashboardState.READY, activeDashboard.getCurrentState());
        assertEquals(2, activeDashboard.getRecentTransactionsTable().getRowCount());
        assertTrue(activeDashboard.getSendFeedbackLabel().getText().contains("successful"));

        Wallet recipientWallet = walletService.getWalletByUserId(recipient.getUserId());
        assertEquals(new BigDecimal("250.00"), recipientWallet.getBalance());
    }

    @Test
    void testDatabaseFailureStateAndRetryRecovery() throws Exception {
        User frank = authService.registerUser("Frank Uy", "09171001006", "frank@otog.ph", "Secret123");
        Wallet frankWallet = walletService.getWalletByUserId(frank.getUserId());
        SessionManager.getInstance().login(frank, frankWallet);

        AtomicBoolean failReads = new AtomicBoolean(true);
        WalletOperations flakyWalletOps = new WalletOperations() {
            @Override
            public Wallet createWallet(String userId, BigDecimal initialBalance, String currency) {
                return walletService.createWallet(userId, initialBalance, currency);
            }

            @Override
            public Wallet getWallet(String walletId) {
                if (failReads.get()) {
                    throw new IllegalStateException("Simulated database unavailable");
                }
                return walletService.getWallet(walletId);
            }

            @Override
            public Wallet getWalletByUserId(String userId) {
                if (failReads.get()) {
                    throw new IllegalStateException("Simulated database unavailable");
                }
                return walletService.getWalletByUserId(userId);
            }

            @Override
            public BigDecimal getBalance(String walletId) {
                return walletService.getBalance(walletId);
            }

            @Override
            public Transaction transfer(String referenceNumber, String sourceWalletId, String targetWalletId,
                                        BigDecimal amount, BigDecimal fee, String description) {
                return walletService.transfer(referenceNumber, sourceWalletId, targetWalletId, amount, fee, description);
            }

            @Override
            public Transaction cashIn(String referenceNumber, String targetWalletId, String paymentMethodId,
                                      BigDecimal amount, String description) {
                return walletService.cashIn(referenceNumber, targetWalletId, paymentMethodId, amount, description);
            }

            @Override
            public Transaction cashOut(String referenceNumber, String sourceWalletId, String paymentMethodId,
                                       BigDecimal amount, BigDecimal fee, String description) {
                return walletService.cashOut(referenceNumber, sourceWalletId, paymentMethodId, amount, fee, description);
            }

            @Override
            public List<Transaction> getTransactionsByWalletId(String walletId) {
                if (failReads.get()) {
                    throw new IllegalStateException("Simulated database unavailable");
                }
                return walletService.getTransactionsByWalletId(walletId);
            }

            @Override
            public List<Transaction> getRecentTransactions(String walletId, int limit) {
                if (failReads.get()) {
                    throw new IllegalStateException("Simulated database unavailable");
                }
                return walletService.getRecentTransactions(walletId, limit);
            }
        };

        activeDashboard = new Main(flakyWalletOps);
        activeDashboard.getInitialLoadFuture().get(5, TimeUnit.SECONDS);

        assertEquals(Main.DashboardState.ERROR, activeDashboard.getCurrentState());
        assertTrue(activeDashboard.getErrorStateLabel().getText().contains("Simulated database unavailable"));

        failReads.set(false);
        activeDashboard.refreshDashboardAsync().get(5, TimeUnit.SECONDS);
        assertEquals(Main.DashboardState.EMPTY, activeDashboard.getCurrentState());
        assertEquals("PHP 0.00", activeDashboard.getBalanceLabel().getText());
    }

    @Test
    void testLogoutClearsSessionAndInvokesCallback() throws Exception {
        User grace = authService.registerUser("Grace Go", "09171001007", "grace@otog.ph", "Secret123");
        SessionManager.getInstance().login(grace, walletService.getWalletByUserId(grace.getUserId()));

        activeDashboard = new Main(walletService);
        activeDashboard.getInitialLoadFuture().get(5, TimeUnit.SECONDS);

        CountDownLatch callbackLatch = new CountDownLatch(1);
        activeDashboard.setOnLogoutCallback(callbackLatch::countDown);
        SwingUtilities.invokeAndWait(() -> activeDashboard.triggerLogout());

        assertTrue(callbackLatch.await(5, TimeUnit.SECONDS), "Logout callback must fire");
        assertFalse(SessionManager.getInstance().isLoggedIn(), "Session must be logged out");
        assertNull(SessionManager.getInstance().getCurrentUser(), "Current user must be cleared");
        assertNull(SessionManager.getInstance().getCurrentWallet(), "Current wallet must be cleared");
    }

    @Test
    void testWalletServiceRecentTransactionsAndRecipientResolutionBoundaries() {
        User alice = authService.registerUser("Alice Lim", "09171001010", "alice.lim@otog.ph", "Secret123");
        User eva = authService.registerUser("Eva Tan", "09171001011", "eva@otog.ph", "Secret123");

        Wallet aliceWallet = walletService.getWalletByUserId(alice.getUserId());
        Wallet evaWallet = walletService.getWalletByUserId(eva.getUserId());

        for (int i = 1; i <= 5; i++) {
            walletService.cashIn("REQ-BOUND-" + i, aliceWallet.getWalletId(), null,
                    new BigDecimal("200.00"), "Top-up " + i);
        }

        List<Transaction> limited = walletService.getRecentTransactions(aliceWallet.getWalletId(), 3);
        assertEquals(3, limited.size(), "getRecentTransactions must cap results at requested limit");

        assertThrows(IllegalArgumentException.class,
                () -> walletService.getRecentTransactions(aliceWallet.getWalletId(), 0));
        assertThrows(IllegalArgumentException.class,
                () -> walletService.getRecentTransactions(aliceWallet.getWalletId(), -2));
        assertThrows(IllegalArgumentException.class,
                () -> walletService.getRecentTransactions("  ", 5));
        assertThrows(IllegalArgumentException.class,
                () -> walletService.getRecentTransactions(null, 5));
        assertThrows(EntityNotFoundException.class,
                () -> walletService.getRecentTransactions("WLT-NONEXISTENT", 5));

        walletService.sendMoneyToRecipient("REQ-SEND-EMAIL", aliceWallet.getWalletId(),
                "eva@otog.ph", new BigDecimal("10.00"), BigDecimal.ZERO, "By email");
        walletService.sendMoneyToRecipient("REQ-SEND-NAME", aliceWallet.getWalletId(),
                "Eva Tan", new BigDecimal("10.00"), BigDecimal.ZERO, "By name");
        walletService.sendMoneyToRecipient("REQ-SEND-WLT", aliceWallet.getWalletId(),
                evaWallet.getWalletId(), new BigDecimal("10.00"), BigDecimal.ZERO, "By wallet ID");

        assertEquals(new BigDecimal("30.00"), walletService.getBalance(evaWallet.getWalletId()));
        assertEquals(new BigDecimal("970.00"), walletService.getBalance(aliceWallet.getWalletId()));

        assertThrows(EntityNotFoundException.class,
                () -> walletService.sendMoneyToRecipient("REQ-BAD-RECIP", aliceWallet.getWalletId(),
                        "09999999999", new BigDecimal("10.00"), BigDecimal.ZERO, "Unknown"));
        assertThrows(IllegalArgumentException.class,
                () -> walletService.sendMoneyToRecipient("REQ-BLANK-RECIP", aliceWallet.getWalletId(),
                        "   ", new BigDecimal("10.00"), BigDecimal.ZERO, "Blank"));
    }

    @Test
    void testPaymentValidationAndInsufficientFundsFeedback() throws Exception {
        User hank = authService.registerUser("Hank Dy", "09171001012", "hank@otog.ph", "Secret123");
        Wallet hankWallet = walletService.getWalletByUserId(hank.getUserId());
        walletService.cashIn("REQ-HANK-SEED", hankWallet.getWalletId(), null,
                new BigDecimal("100.00"), "Seed");
        SessionManager.getInstance().login(hank, walletService.getWallet(hankWallet.getWalletId()));

        activeDashboard = new Main(walletService);
        activeDashboard.getInitialLoadFuture().get(5, TimeUnit.SECONDS);
        assertEquals("PHP 100.00", activeDashboard.getBalanceLabel().getText());

        ExecutionException overdrawEx = assertThrows(ExecutionException.class, () ->
                activeDashboard.submitCashOperationAsync("CASH_OUT", "BDO", "250.00", "Overdraw")
                        .get(5, TimeUnit.SECONDS));
        assertInstanceOf(InsufficientFundsException.class, overdrawEx.getCause());
        assertTrue(activeDashboard.getCashFeedbackLabel().getText().startsWith("Failed:"));
        assertEquals("PHP 100.00", activeDashboard.getBalanceLabel().getText());

        for (String invalidAmount : new String[]{"-10.00", "0.00", "abc"}) {
            ExecutionException invalidCashEx = assertThrows(ExecutionException.class, () ->
                    activeDashboard.submitCashOperationAsync("CASH_IN", "GCash", invalidAmount, "Bad")
                            .get(5, TimeUnit.SECONDS));
            assertInstanceOf(InvalidAmountException.class, invalidCashEx.getCause());
            assertTrue(activeDashboard.getCashFeedbackLabel().getText().startsWith("Failed:"));
            assertEquals("PHP 100.00", activeDashboard.getBalanceLabel().getText());
        }

        ExecutionException invalidSendAmountEx = assertThrows(ExecutionException.class, () ->
                activeDashboard.submitSendMoneyAsync("09171001099", "0.00", "Zero")
                        .get(5, TimeUnit.SECONDS));
        assertInstanceOf(InvalidAmountException.class, invalidSendAmountEx.getCause());
        assertTrue(activeDashboard.getSendFeedbackLabel().getText().startsWith("Transfer failed:"));
        assertEquals("PHP 100.00", activeDashboard.getBalanceLabel().getText());

        ExecutionException missingRecipientEx = assertThrows(ExecutionException.class, () ->
                activeDashboard.submitSendMoneyAsync("09170000000", "25.00", "Missing")
                        .get(5, TimeUnit.SECONDS));
        assertInstanceOf(EntityNotFoundException.class, missingRecipientEx.getCause());
        assertTrue(activeDashboard.getSendFeedbackLabel().getText().startsWith("Transfer failed:"));
        assertEquals("PHP 100.00", activeDashboard.getBalanceLabel().getText());
    }

    @Test
    void testEndToEndLoginToDashboardQuickActionsAndTransactions() throws Exception {
        authService.registerUser("Ivy Cruz", "09171001013", "ivy@otog.ph", "Secret123");

        Class<?> loginClass = Class.forName("UserLogin");
        Object loginWindow = loginClass.getConstructor(AuthService.class, WalletService.class)
                .newInstance(authService, walletService);

        try {
            Method attemptLogin = loginClass.getMethod("attemptLogin", String.class, String.class);
            Method verifyTwoFactor = loginClass.getMethod("verifyTwoFactorCode", String.class);
            Method openDashboard = loginClass.getMethod("openDashboard");

            assertTrue((Boolean) attemptLogin.invoke(loginWindow, "09171001013", "Secret123"));
            assertTrue((Boolean) verifyTwoFactor.invoke(loginWindow, "123456"));
            assertTrue(SessionManager.getInstance().isLoggedIn());

            AtomicReference<Main> dashboardRef = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                try {
                    dashboardRef.set((Main) openDashboard.invoke(loginWindow));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            activeDashboard = dashboardRef.get();
            assertNotNull(activeDashboard);
            activeDashboard.getInitialLoadFuture().get(5, TimeUnit.SECONDS);

            SwingUtilities.invokeAndWait(() -> {
                activeDashboard.getCashInQuickNavButton().doClick();
                assertEquals("Cash In / Out",
                        activeDashboard.getTabs().getTitleAt(activeDashboard.getTabs().getSelectedIndex()));
                assertEquals("CASH_IN", activeDashboard.getCashOperationComboBox().getSelectedItem());

                activeDashboard.getCashOutQuickNavButton().doClick();
                assertEquals("Cash In / Out",
                        activeDashboard.getTabs().getTitleAt(activeDashboard.getTabs().getSelectedIndex()));
                assertEquals("CASH_OUT", activeDashboard.getCashOperationComboBox().getSelectedItem());

                activeDashboard.getSendMoneyQuickNavButton().doClick();
                assertEquals("Send Money",
                        activeDashboard.getTabs().getTitleAt(activeDashboard.getTabs().getSelectedIndex()));
            });
        } finally {
            if (loginWindow instanceof JFrame frame) {
                SwingUtilities.invokeAndWait(frame::dispose);
            }
        }
    }

    @Test
    void testDashboardRefreshPerformanceUnderLoad() throws Exception {
        User jack = authService.registerUser("Jack Lee", "09171001014", "jack@otog.ph", "Secret123");
        Wallet jackWallet = walletService.getWalletByUserId(jack.getUserId());
        for (int i = 1; i <= 25; i++) {
            walletService.cashIn("REQ-PERF-" + i, jackWallet.getWalletId(), null,
                    new BigDecimal("10.00"), "Perf batch " + i);
        }

        SessionManager.getInstance().login(jack, walletService.getWallet(jackWallet.getWalletId()));
        activeDashboard = new Main(walletService);
        activeDashboard.getInitialLoadFuture().get(5, TimeUnit.SECONDS);

        long startNanos = System.nanoTime();
        activeDashboard.refreshDashboardAsync().get(5, TimeUnit.SECONDS);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        assertTrue(elapsedMs < 1500, "Dashboard refresh under 25 transactions took " + elapsedMs + " ms");
        assertEquals(25, activeDashboard.getRecentTransactionsTable().getRowCount());
        assertEquals("PHP 250.00", activeDashboard.getBalanceLabel().getText());
        assertEquals(Main.DashboardState.READY, activeDashboard.getCurrentState());
    }
}
