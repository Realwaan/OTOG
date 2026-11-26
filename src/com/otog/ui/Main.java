package com.otog.ui;

import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import com.otog.database.DatabaseManager;
import com.otog.model.BillPaymentTransaction;
import com.otog.model.CashInOutTransaction;
import com.otog.model.CompositeFeePolicy;
import com.otog.model.InvestMoneyTransaction;
import com.otog.model.PaymentMethod;
import com.otog.model.SendMoneyTransaction;
import com.otog.model.Transaction;
import com.otog.model.TransactionFilter;
import com.otog.model.TransactionStatus;
import com.otog.model.TransactionType;
import com.otog.model.User;
import com.otog.model.Wallet;
import com.otog.service.AdminService;
import com.otog.service.AuthService;
import com.otog.service.AuthServiceImpl;
import com.otog.service.InvestmentService;
import com.otog.service.LoanService;
import com.otog.service.ProtectedWalletOperationsProxy;
import com.otog.service.SessionManager;
import com.otog.service.StatementFileService;
import com.otog.service.WalletOperations;
import com.otog.service.WalletService;
import com.otog.service.MerchantQrService;
import com.otog.util.MoneyUtil;
import com.otog.util.OtogConfig;
import com.otog.util.SwingUiSupport;
import com.otog.util.UiTheme;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Top-level authenticated E-Wallet dashboard shell bound to {@code Main.form}.
 * Hosts header identity/balance controls, tabbed navigation across modular
 * {@code .form}-backed panels, session monitoring, and the status footer.
 */
public class Main extends JFrame {

    public enum DashboardState {
        LOADING,
        EMPTY,
        READY,
        ERROR
    }

    private final WalletOperations walletService;
    private final AuthService authService;
    private final LoanService loanService;
    private final InvestmentService investmentService;
    private final DatabaseManager ownedDatabaseManager;
    private final Map<String, Integer> tabIndexByTitle = new LinkedHashMap<>();
    private final Deque<String> navigationHistory = new ArrayDeque<>();
    private final Object refreshLock = new Object();
    private final List<CompletableFuture<Void>> pendingRefreshFutures = new ArrayList<>();
    private long refreshGeneration;
    private String lastSelectedTabTitle;
    private boolean suppressHistoryRecording;
    private boolean logoutHandled;

    private JPanel pnlRoot;
    private JPanel pnlHeader;
    private JPanel pnlIdentity;
    private JPanel pnlBalanceAndActions;
    private JPanel pnlBalanceBox;
    private JPanel pnlFooter;

    private JLabel lblWelcome;
    private JLabel lblWalletId;
    private JLabel lblPinStatus;
    private JLabel lblBalance;
    private JLabel lblStatus;
    private JButton btnBack;
    private JButton btnRefresh;
    private JButton btnNotifications;
    private JButton btnPinSettings;
    private JButton btnLogout;

    private JTabbedPane tabs;
    private OverviewPanel overviewPanel;
    private CashInOutPanel cashInOutPanel;
    private SendMoneyPanel sendMoneyPanel;
    private PayBillsPanel payBillsPanel;
    private MerchantQrPanel merchantQrPanel;
    private InvestmentPanel investmentPanel;
    private AdminControlsPanel adminControlsPanel;

    private Timer sessionMonitorTimer;
    private volatile DashboardState currentState = DashboardState.LOADING;
    private volatile boolean lastLoadExecutedOffEdt;
    private volatile boolean lastPaymentExecutedOffEdt;
    private volatile boolean lastAdminLoadExecutedOffEdt;
    private volatile boolean cachedHasPin;
    private CompletableFuture<Void> initialLoadFuture;
    private volatile CompletableFuture<Void> lastPayBillsRefreshFuture = CompletableFuture.completedFuture(null);
    private volatile int cachedUnreadNotifications;
    private Runnable onLogoutCallback;

    /**
     * Creates a dashboard that takes ownership of an already-initialized {@link DatabaseManager}
     * and closes it on {@link #dispose()}. Schema bootstrapping must happen off the EDT before this
     * call; the UI constructor never constructs a database itself.
     */
    public static Main withOwnedDatabase(DatabaseManager initializedDatabaseManager) {
        return withOwnedDatabase(initializedDatabaseManager, null);
    }

    public static Main withOwnedDatabase(DatabaseManager initializedDatabaseManager, AuthService authService) {
        if (initializedDatabaseManager == null) {
            throw new IllegalArgumentException("DatabaseManager must not be null");
        }
        AuthService effectiveAuth = authService != null
                ? authService
                : new AuthServiceImpl(initializedDatabaseManager);
        WalletOperations guardedWalletOps = ProtectedWalletOperationsProxy.wrap(
                new WalletService(initializedDatabaseManager, effectiveAuth));
        InvestmentService invService = ProtectedWalletOperationsProxy.asInvestmentServiceOrNull(guardedWalletOps);
        return new Main(guardedWalletOps, effectiveAuth, null, invService, initializedDatabaseManager);
    }

    public Main(WalletOperations walletService) {
        this(walletService, null, null, null, null);
    }

    public Main(WalletOperations walletService, AuthService authService) {
        this(walletService, authService, null, null, null);
    }

    public Main(WalletOperations walletService, LoanService loanService, InvestmentService investmentService) {
        this(walletService, null, loanService, investmentService, null);
    }

    public Main(WalletOperations walletService, AuthService authService,
                LoanService loanService, InvestmentService investmentService) {
        this(walletService, authService, loanService, investmentService, null);
    }

    private Main(WalletOperations walletService, AuthService authService,
                 LoanService loanService, InvestmentService investmentService,
                 DatabaseManager ownedDatabaseManager) {
        SessionManager.getInstance().requireAuthentication();
        if (walletService == null) {
            throw new IllegalArgumentException("WalletOperations service must not be null");
        }
        this.walletService = walletService;
        this.authService = authService;
        this.loanService = loanService;
        this.investmentService = investmentService;
        this.ownedDatabaseManager = ownedDatabaseManager;

        if (pnlRoot == null) {
            $$$setupUI$$$();
        }
        initUI();
        startSessionMonitor();
        this.initialLoadFuture = refreshDashboardAsync();
    }

    private void initUI() {
        OtogConfig config = OtogConfig.getInstance();
        setTitle(config.getAppTitle() + " \u2014 Dashboard");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        applyShellStyling();

        btnBack.setToolTipText("Return to the previously viewed tab");
        btnBack.setEnabled(false);
        btnBack.addActionListener(e -> navigateBack());

        btnRefresh.addActionListener(e -> handleInteractiveRefresh());

        btnNotifications.setToolTipText("View persistent payment notifications and unread alerts");
        btnNotifications.addActionListener(e -> openNotificationInboxDialog());

        btnPinSettings.setEnabled(authService != null);
        btnPinSettings.setToolTipText(authService != null
                ? "Set or change your " + config.getPinMinLength() + "-" + config.getPinMaxLength() + " digit Transaction PIN"
                : "PIN management requires AuthService");
        btnPinSettings.addActionListener(e -> openPinSettingsDialog());

        btnLogout.addActionListener(e -> confirmAndLogout());

        buildTabsPanel();
        setContentPane(pnlRoot);
        applyInitialSessionSnapshot();

        SwingUiSupport.applyFlexibleWindowBounds(
                this,
                config.getUiDimension("ui.dashboard.width", "ui.dashboard.height", 1020, 680),
                config.getUiDimension("ui.dashboard.min.width", "ui.dashboard.min.height", 720, 520),
                false
        );
        setLocationRelativeTo(null);
    }

    /**
     * Applies shell typography, divider borders, and button styles outside {@code $$$setupUI$$$()}
     * so IntelliJ GUI Designer bytecode instrumentation preserves consistent theming.
     */
    private void applyShellStyling() {
        if (pnlHeader != null) {
            pnlHeader.setBorder(UiTheme.bottomDividerBorder());
        }
        if (pnlBalanceAndActions != null) {
            SwingUiSupport.applyWrapLayout(pnlBalanceAndActions, FlowLayout.RIGHT, 8, 4);
        }
        if (pnlFooter != null) {
            pnlFooter.setBorder(UiTheme.topDividerBorder());
        }
        if (lblWelcome != null) {
            lblWelcome.setFont(UiTheme.font(UiTheme.FontRole.BANNER));
        }
        if (lblWalletId != null) {
            lblWalletId.setFont(UiTheme.font(UiTheme.FontRole.LABEL));
            lblWalletId.setForeground(UiTheme.color(UiTheme.ColorRole.MUTED));
        }
        if (lblPinStatus != null) {
            lblPinStatus.setFont(UiTheme.font(UiTheme.FontRole.SMALL));
            lblPinStatus.setForeground(UiTheme.color(UiTheme.ColorRole.MUTED));
        }
        if (lblBalance != null) {
            lblBalance.setFont(UiTheme.font(UiTheme.FontRole.BALANCE));
            lblBalance.setForeground(UiTheme.color(UiTheme.ColorRole.SUCCESS));
        }
        if (lblStatus != null) {
            lblStatus.setFont(UiTheme.font(UiTheme.FontRole.SMALL));
            lblStatus.setForeground(UiTheme.color(UiTheme.ColorRole.MUTED));
        }
        SwingUiSupport.makeShrinkable(lblWelcome, lblWalletId, lblPinStatus, lblStatus);
        if (tabs != null) {
            tabs.setFont(UiTheme.font(UiTheme.FontRole.LABEL_BOLD));
            tabs.setTabLayoutPolicy(JTabbedPane.WRAP_TAB_LAYOUT);
            tabs.setMinimumSize(new Dimension(200, 200));
        }
        UiTheme.styleSecondaryButton(btnBack);
        UiTheme.styleSecondaryButton(btnRefresh);
        UiTheme.styleSecondaryButton(btnNotifications);
        UiTheme.styleSecondaryButton(btnPinSettings);
        UiTheme.styleSecondaryButton(btnLogout);
    }

    private JTabbedPane buildTabsPanel() {
        tabs.removeAll();
        tabIndexByTitle.clear();

        overviewPanel = new OverviewPanel(walletService, new StatementFileService());
        cashInOutPanel = new CashInOutPanel(walletService, authService, walletService.getCurrentProviderMode());
        sendMoneyPanel = new SendMoneyPanel(walletService, authService);
        payBillsPanel = new PayBillsPanel(walletService, authService);
        MerchantQrService merchantQrService = walletService.createMerchantQrService(authService);
        merchantQrPanel = new MerchantQrPanel(merchantQrService, walletService, authService,
                () -> navigateToTab("Overview"),
                this::refreshDashboardAsync);
        wireMerchantQrPanel();

        wireOverviewPanel();
        wireCashInOutPanel();
        wireSendMoneyPanel();
        wirePayBillsPanel();

        registerTab("Overview", overviewPanel);
        registerTab("Cash In / Out", cashInOutPanel);
        registerTab("Send Money", sendMoneyPanel);
        registerTab("Pay Bills", payBillsPanel);
        registerTab("Merchant QR", merchantQrPanel);

        // Hide unfinished modules unless an implementation is explicitly provided
        if (loanService != null) {
            registerTab("Loans", buildServiceReadyNotice("Loans module active"));
        }
        if (investmentService != null) {
            investmentPanel = new InvestmentPanel(walletService, investmentService, authService);
            wireInvestmentPanel();
            registerTab("Investments", investmentPanel);
        }

        // CAP-09: Admin Controls tab — only visible to administrators
        User sessionUser = SessionManager.getInstance().getCurrentUser();
        AdminService adminService = walletService.getAdminService();
        if (sessionUser != null && sessionUser.isAdmin() && adminService != null) {
            adminControlsPanel = new AdminControlsPanel(
                    adminService,
                    sessionUser.getUserId(),
                    offEdt -> lastAdminLoadExecutedOffEdt = offEdt,
                    this::showDialogMessage
            );
            registerTab("Admin Controls", adminControlsPanel);
        }

        lastSelectedTabTitle = tabs.getTabCount() > 0 ? tabs.getTitleAt(tabs.getSelectedIndex()) : null;
        // Record every tab switch (manual click or programmatic) on the LIFO back-stack.
        tabs.addChangeListener(e -> onTabSelectionChanged());
        return tabs;
    }

    private void wireMerchantQrPanel() {
        if (merchantQrPanel == null) {
            return;
        }
        merchantQrPanel.setBeforeInteractiveSubmit(this::ensureActiveSessionForInteraction);
        merchantQrPanel.setPinConfiguredSupplier(() -> cachedHasPin);
        merchantQrPanel.setOnPinNotSet(() ->
                promptPinSetupIfMissing("A Transaction PIN is required before paying via Merchant QR."));
        merchantQrPanel.setOffEdtObserver(offEdt -> lastPaymentExecutedOffEdt = offEdt);
        merchantQrPanel.setOnPaymentError(err -> {
            String msg = SwingUiSupport.extractErrorMessage(err);
            lblStatus.setText("Merchant QR error: " + msg);
            checkSessionTerminatedOnError(msg);
        });
    }

    private void wireOverviewPanel() {
        overviewPanel.setPinSettingsEnabled(authService != null);
        overviewPanel.setInvestmentsEnabled(investmentService != null);
        overviewPanel.setOnQuickNavCashIn(() -> {
            navigateToTab("Cash In / Out");
            cashInOutPanel.prepareForOperation("CASH_IN");
        });
        overviewPanel.setOnQuickNavCashOut(() -> {
            navigateToTab("Cash In / Out");
            cashInOutPanel.prepareForOperation("CASH_OUT");
        });
        overviewPanel.setOnQuickNavSendMoney(() -> {
            navigateToTab("Send Money");
            sendMoneyPanel.focusRecipient();
        });
        overviewPanel.setOnQuickNavPayBills(() -> {
            navigateToTab("Pay Bills");
            if (payBillsPanel != null) {
                payBillsPanel.getBillReferenceField().requestFocusInWindow();
            }
        });
        overviewPanel.setOnQuickNavInvest(() -> {
            if (navigateToTab("Investments") && investmentPanel != null) {
                investmentPanel.focusUnits();
            }
        });
        overviewPanel.setOnQuickNavMerchantQr(() -> navigateToTab("Merchant QR"));
        overviewPanel.setOnQuickNavPinSettings(this::openPinSettingsDialog);
        overviewPanel.setBeforeInteractiveAction(this::ensureActiveSessionForInteraction);
        overviewPanel.setOnRetryLoad(this::handleInteractiveRefresh);
        overviewPanel.setOnRefreshRequested(this::refreshDashboardAsync);
        overviewPanel.setOnStatusMessage(lblStatus::setText);
        overviewPanel.setOnOperationError(this::checkSessionTerminatedOnError);
        overviewPanel.setOnStatusLookupFeedback((msg, color) -> cashInOutPanel.showFeedback(msg, color));
        overviewPanel.setOnRepeatDraftReady(this::applyRepeatDraftToUi);
        overviewPanel.setDialogMessageCallback(this::showDialogMessage);
        overviewPanel.setComponentMessageCallback(this::showComponentDialogMessage);
    }

    private void wireCashInOutPanel() {
        cashInOutPanel.setOnBackToOverview(() -> navigateToTab("Overview"));
        cashInOutPanel.setBeforeInteractiveSubmit(this::ensureActiveSessionForInteraction);
        cashInOutPanel.setPinConfiguredSupplier(() -> cachedHasPin);
        cashInOutPanel.setOnPinNotSet(() ->
                promptPinSetupIfMissing("A Transaction PIN is required before making a Cash Out."));
        cashInOutPanel.setIsLogoutHandledSupplier(() -> logoutHandled);
        cashInOutPanel.setDialogMessageCallback(this::showDialogMessage);
        cashInOutPanel.setConfirmDialogCallback(this::showConfirmDialog);
        cashInOutPanel.setOnStatusMessage(lblStatus::setText);
        cashInOutPanel.setOffEdtObserver(offEdt -> lastPaymentExecutedOffEdt = offEdt);
        cashInOutPanel.setOnRefreshRequested(this::refreshDashboardAsync);
        cashInOutPanel.setOnOperationError(this::checkSessionTerminatedOnError);
    }

    private void wireSendMoneyPanel() {
        sendMoneyPanel.setOnBackToOverview(() -> navigateToTab("Overview"));
        sendMoneyPanel.setBeforeInteractiveSubmit(this::ensureActiveSessionForInteraction);
        sendMoneyPanel.setPinConfiguredSupplier(() -> cachedHasPin);
        sendMoneyPanel.setOnPinNotSet(() ->
                promptPinSetupIfMissing("A Transaction PIN is required before sending money."));
        sendMoneyPanel.setIsLogoutHandledSupplier(() -> logoutHandled);
        sendMoneyPanel.setDialogMessageCallback(this::showDialogMessage);
        sendMoneyPanel.setConfirmDialogCallback(this::showConfirmDialog);
        sendMoneyPanel.setOnStatusMessage(lblStatus::setText);
        sendMoneyPanel.setOffEdtObserver(offEdt -> lastPaymentExecutedOffEdt = offEdt);
        sendMoneyPanel.setOnRefreshRequested(this::refreshDashboardAsync);
        sendMoneyPanel.setOnOperationError(this::checkSessionTerminatedOnError);
    }

    private void wireInvestmentPanel() {
        if (investmentPanel == null) {
            return;
        }
        investmentPanel.setOnBackToOverview(() -> navigateToTab("Overview"));
        investmentPanel.setBeforeInteractiveSubmit(this::ensureActiveSessionForInteraction);
        investmentPanel.setPinConfiguredSupplier(() -> cachedHasPin);
        investmentPanel.setOnPinNotSet(() ->
                promptPinSetupIfMissing("A Transaction PIN is required before buying or redeeming simulated investments."));
        investmentPanel.setIsLogoutHandledSupplier(() -> logoutHandled);
        investmentPanel.setDialogMessageCallback(this::showDialogMessage);
        investmentPanel.setConfirmDialogCallback(this::showConfirmDialog);
        investmentPanel.setOnStatusMessage(lblStatus::setText);
        investmentPanel.setOffEdtObserver(offEdt -> lastPaymentExecutedOffEdt = offEdt);
        investmentPanel.setOnRefreshRequested(this::refreshDashboardAsync);
        investmentPanel.setOnOperationError(this::checkSessionTerminatedOnError);
    }

    private void wirePayBillsPanel() {
        if (payBillsPanel == null) {
            return;
        }
        payBillsPanel.setOnBackToOverview(() -> navigateToTab("Overview"));
        payBillsPanel.setBeforeInteractiveSubmit(this::ensureActiveSessionForInteraction);
        payBillsPanel.setPinConfiguredSupplier(() -> cachedHasPin);
        payBillsPanel.setOnPinNotSet(() ->
                promptPinSetupIfMissing("A Transaction PIN is required before paying bills."));
        payBillsPanel.setOnPaymentSuccess(txn -> {
            lastPaymentExecutedOffEdt = payBillsPanel.isLastPaymentExecutedOffEdt();
            if (txn != null) {
                String billStatusText;
                if (txn.getStatus() == TransactionStatus.COMPLETED) {
                    billStatusText = "Bill payment completed: " + txn.getReferenceNumber();
                } else if (txn.getStatus() == TransactionStatus.FAILED) {
                    billStatusText = "Bill payment failed: " + txn.getReferenceNumber();
                } else {
                    billStatusText = "Bill payment pending settlement: " + txn.getReferenceNumber();
                }
                lblStatus.setText(billStatusText);
                lastPayBillsRefreshFuture = refreshDashboardAsync().whenComplete((v, err) -> {
                    if (currentState != DashboardState.ERROR) {
                        SwingUiSupport.runOnEdt(() -> lblStatus.setText(billStatusText));
                    }
                });
            } else {
                lastPayBillsRefreshFuture = refreshDashboardAsync();
            }
        });
        payBillsPanel.setOnPaymentError(err -> {
            String msg = SwingUiSupport.extractErrorMessage(err);
            lblStatus.setText("Bill payment failed: " + msg);
            checkSessionTerminatedOnError(msg);
        });
    }

    public CompletableFuture<Transaction> submitBillPaymentAsync(String biller, String billerReference,
                                                                 String amountText, String rawPin) {
        return payBillsPanel.submitPaymentAsync(biller, billerReference, amountText, rawPin)
                .thenCompose(txn -> {
                    CompletableFuture<Void> refresh = lastPayBillsRefreshFuture;
                    return refresh != null ? refresh.thenApply(v -> txn) : CompletableFuture.completedFuture(txn);
                });
    }

    public PayBillsPanel getPayBillsPanel() {
        return payBillsPanel;
    }

    private void onTabSelectionChanged() {
        SessionManager.getInstance().touchActivity();
        int selected = tabs.getSelectedIndex();
        String newTitle = selected >= 0 ? tabs.getTitleAt(selected) : null;
        if (!suppressHistoryRecording && lastSelectedTabTitle != null
                && newTitle != null && !lastSelectedTabTitle.equals(newTitle)) {
            navigationHistory.push(lastSelectedTabTitle);
        }
        lastSelectedTabTitle = newTitle;
        updateBackButtonState();
    }

    private void updateBackButtonState() {
        if (btnBack != null) {
            btnBack.setEnabled(!navigationHistory.isEmpty());
        }
    }

    private void registerTab(String title, JComponent content) {
        int index = tabs.getTabCount();
        tabs.addTab(title, content);
        tabIndexByTitle.put(title.toLowerCase(Locale.ROOT), index);
    }

    private JPanel buildServiceReadyNotice(String text) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(new JLabel(text, SwingConstants.CENTER), BorderLayout.CENTER);
        return panel;
    }

    private void applyInitialSessionSnapshot() {
        User user = SessionManager.getInstance().getCurrentUser();
        Wallet wallet = SessionManager.getInstance().getCurrentWallet();
        updateHeaderFromSession(user, wallet, false);
    }

    private void updateHeaderFromSession(User user, Wallet wallet, boolean hasPin) {
        String displayName = (user != null && user.getName() != null && !user.getName().trim().isEmpty())
                ? user.getName().trim()
                : "User";
        String mobile = (user != null && user.getMobileNumber() != null && !user.getMobileNumber().trim().isEmpty())
                ? " (" + user.getMobileNumber().trim() + ")"
                : "";
        lblWelcome.setText("Welcome, " + displayName + mobile);

        if (wallet != null) {
            lblBalance.setText(MoneyUtil.format(wallet.getBalance(), wallet.getCurrency()));
            lblWalletId.setText("Wallet: " + wallet.getWalletId() + " (" + wallet.getStatus() + ")");
        } else {
            lblBalance.setText(MoneyUtil.format(MoneyUtil.ZERO, OtogConfig.getInstance().getDefaultCurrency()));
            lblWalletId.setText("Wallet: Not provisioned");
        }

        updatePinStatusDisplay(hasPin);
    }

    private void updatePinStatusDisplay(boolean hasPin) {
        this.cachedHasPin = hasPin;
        if (lblPinStatus == null) {
            return;
        }
        if (authService == null) {
            lblPinStatus.setText("Transaction PIN: Session-gated");
            lblPinStatus.setForeground(UiTheme.color(UiTheme.ColorRole.MUTED));
            return;
        }
        boolean pinVerified = SessionManager.getInstance().isPinVerified();
        if (!hasPin) {
            String pinText = "Transaction PIN: Not Set (required for Cash Out, Send Money, Pay Bills, Merchant QR & Investments)";
            lblPinStatus.setText(pinText);
            lblPinStatus.setToolTipText(pinText);
            lblPinStatus.setForeground(UiTheme.color(UiTheme.ColorRole.WARNING));
            if (btnPinSettings != null) {
                btnPinSettings.setText("Set PIN");
            }
        } else if (pinVerified) {
            String pinText = "Transaction PIN: Set & Verified for active session";
            lblPinStatus.setText(pinText);
            lblPinStatus.setToolTipText(pinText);
            lblPinStatus.setForeground(UiTheme.color(UiTheme.ColorRole.SUCCESS));
            if (btnPinSettings != null) {
                btnPinSettings.setText("Change PIN");
            }
        } else {
            String pinText = "Transaction PIN: Set (enter PIN on outgoing payments)";
            lblPinStatus.setText(pinText);
            lblPinStatus.setToolTipText(pinText);
            lblPinStatus.setForeground(UiTheme.color(UiTheme.ColorRole.PRIMARY));
            if (btnPinSettings != null) {
                btnPinSettings.setText("Change PIN");
            }
        }
        if (pnlBalanceAndActions != null) {
            pnlBalanceAndActions.revalidate();
        }
    }

    public CompletableFuture<Void> refreshDashboardAsync() {
        CompletableFuture<Void> future = new CompletableFuture<>();
        final long generation;
        synchronized (refreshLock) {
            generation = ++refreshGeneration;
            pendingRefreshFutures.add(future);
        }
        this.currentState = DashboardState.LOADING;

        SwingUiSupport.runOnEdt(() -> {
            String loadingMessage = "Loading wallet balance and recent activity...";
            overviewPanel.showLoadingState(loadingMessage);
            lblStatus.setText(loadingMessage);
            setRefreshControlsEnabled(false);
        });

        SwingWorker<DashboardSnapshot, Void> worker = new SwingWorker<>() {
            @Override
            protected DashboardSnapshot doInBackground() {
                lastLoadExecutedOffEdt = !SwingUtilities.isEventDispatchThread();
                SessionManager session = SessionManager.getInstance();
                session.requireAuthentication();
                User user = session.getCurrentUser();
                Wallet wallet = session.refreshWallet(walletService);
                TransactionFilter currentFilter = overviewPanel.getActiveHistoryFilter();
                List<Transaction> transactions = (currentFilter != null && !currentFilter.isEmpty())
                        ? walletService.getTransactionHistory(wallet.getWalletId(), currentFilter)
                        : walletService.getRecentTransactions(
                                wallet.getWalletId(), OtogConfig.getInstance().getRecentTransactionLimit());
                List<PaymentMethod> paymentMethods = user != null
                        ? walletService.getPaymentMethodsByUserId(user.getUserId())
                        : Collections.emptyList();
                boolean hasPin = authService != null && user != null && authService.hasPin(user.getUserId());
                int unreadNotifications = user != null
                        ? walletService.getUnreadNotificationCount(user.getUserId())
                        : 0;
                InvestmentService.SimulatedProduct investProduct = null;
                InvestmentService.InvestmentHolding investHolding = null;
                List<InvestMoneyTransaction> investActivities = Collections.emptyList();
                if (investmentService != null && wallet != null) {
                    investProduct = investmentService.getSeededProduct();
                    investHolding = investmentService.getHolding(wallet.getWalletId());
                    investActivities = investmentService.getInvestmentsByWalletId(wallet.getWalletId());
                }
                return new DashboardSnapshot(user, wallet, transactions, paymentMethods, hasPin,
                        unreadNotifications, investProduct, investHolding, investActivities);
            }

            @Override
            protected void done() {
                if (!isLatestRefresh(generation)) {
                    // A newer refresh superseded this one: never let stale data overwrite the UI.
                    return;
                }
                try {
                    DashboardSnapshot snapshot = get();
                    updateHeaderFromSession(snapshot.user, snapshot.wallet, snapshot.hasPin);
                    updateNotificationsBadge(snapshot.unreadNotifications);
                    cashInOutPanel.populatePaymentMethods(snapshot.paymentMethods);
                    overviewPanel.populateTransactions(snapshot.wallet.getWalletId(), snapshot.transactions);
                    if (investmentPanel != null) {
                        investmentPanel.updateProductAndHoldings(snapshot.investProduct, snapshot.investHolding);
                        investmentPanel.updateActivityTable(snapshot.investActivities);
                    }
                    if (merchantQrPanel != null) {
                        merchantQrPanel.applyRoleAccessControl();
                        merchantQrPanel.refreshMerchantSalesIfAuthorized();
                    }
                    TransactionFilter activeFilter = overviewPanel.getActiveHistoryFilter();
                    boolean filtered = activeFilter != null && !activeFilter.isEmpty();
                    if (snapshot.transactions.isEmpty()) {
                        currentState = DashboardState.EMPTY;
                        overviewPanel.showEmptyState(filtered);
                        lblStatus.setText(filtered
                                ? "Ready \u2014 no matching transactions for active filter"
                                : "Ready \u2014 no transactions yet");
                    } else {
                        currentState = DashboardState.READY;
                        overviewPanel.showReadyState();
                        lblStatus.setText(filtered
                                ? "Ready \u2014 showing " + snapshot.transactions.size() + " filtered transaction(s)"
                                : "Ready \u2014 showing " + snapshot.transactions.size() + " recent transaction(s)");
                    }
                } catch (Exception ex) {
                    Throwable cause = SwingUiSupport.unwrapCause(ex);
                    String message = SwingUiSupport.extractErrorMessage(cause);
                    currentState = DashboardState.ERROR;
                    overviewPanel.showErrorState(message);
                    lblStatus.setText("Error loading dashboard: " + message);
                    checkSessionTerminatedOnError(message);
                }
                completeRefreshIfLatest(generation);
            }
        };
        worker.execute();
        return future;
    }

    private boolean isLatestRefresh(long generation) {
        synchronized (refreshLock) {
            return generation == refreshGeneration;
        }
    }

    /** Re-enables refresh controls and completes every waiting future once the newest refresh lands. */
    private void completeRefreshIfLatest(long generation) {
        List<CompletableFuture<Void>> toComplete;
        synchronized (refreshLock) {
            if (generation != refreshGeneration) {
                return;
            }
            toComplete = new ArrayList<>(pendingRefreshFutures);
            pendingRefreshFutures.clear();
        }
        setRefreshControlsEnabled(true);
        for (CompletableFuture<Void> pending : toComplete) {
            pending.complete(null);
        }
    }

    private void setRefreshControlsEnabled(boolean enabled) {
        if (btnRefresh != null) {
            btnRefresh.setEnabled(enabled);
        }
        if (overviewPanel != null) {
            overviewPanel.setRetryEnabled(enabled);
        }
    }

    public CompletableFuture<Transaction> submitCashOperationAsync(String operationType, String channel,
                                                                    String amountText, String note) {
        return submitCashOperationAsync(operationType, channel, null, amountText, note, null);
    }

    public CompletableFuture<Transaction> submitCashOperationAsync(String operationType, String channel,
                                                                    String amountText, String note, String rawPin) {
        return submitCashOperationAsync(operationType, channel, null, amountText, note, rawPin);
    }

    public CompletableFuture<Transaction> submitCashOperationAsync(String operationType, String channel,
                                                                    String paymentMethodId, String amountText,
                                                                    String note, String rawPin) {
        return cashInOutPanel.submitCashOperationAsync(
                operationType, channel, paymentMethodId, amountText, note, rawPin);
    }

    public CompletableFuture<Transaction> submitSendMoneyAsync(String recipient, String amountText, String note) {
        return submitSendMoneyAsync(recipient, amountText, note, null);
    }

    public CompletableFuture<Transaction> submitSendMoneyAsync(String recipient, String amountText,
                                                              String note, String rawPin) {
        return sendMoneyPanel.submitSendMoneyAsync(recipient, amountText, note, rawPin);
    }

    public CompletableFuture<InvestmentService.InvestmentPreview> previewInvestmentAsync(
            String operation, String unitsText, String amountText) {
        if (investmentPanel == null) {
            CompletableFuture<InvestmentService.InvestmentPreview> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalStateException("InvestmentService is not enabled."));
            return failed;
        }
        return investmentPanel.previewInvestmentAsync(operation, unitsText, amountText);
    }

    public CompletableFuture<InvestMoneyTransaction> submitInvestmentAsync(
            String operation, String unitsText, String amountText, String rawPin) {
        if (investmentPanel == null) {
            CompletableFuture<InvestMoneyTransaction> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalStateException("InvestmentService is not enabled."));
            return failed;
        }
        return investmentPanel.submitInvestmentAsync(operation, unitsText, amountText, rawPin);
    }

    public CompletableFuture<Transaction> submitStatusLookupAsync(String referenceNumber) {
        return overviewPanel.submitStatusLookupAsync(referenceNumber);
    }

    public CompletableFuture<Path> exportStatementAsync(Path targetFile) {
        return overviewPanel.exportStatementAsync(targetFile);
    }

    public CompletableFuture<StatementFileService.LoadedStatement> importStatementAsync(Path sourceFile) {
        return overviewPanel.importStatementAsync(sourceFile);
    }

    public CompletableFuture<Path> exportSelectedReceiptAsync(Path targetFile) {
        return overviewPanel.exportSelectedReceiptAsync(targetFile);
    }

    public CompletableFuture<Void> applyHistoryFiltersAsync(TransactionFilter filter) {
        return overviewPanel.applyHistoryFiltersAsync(filter);
    }

    public CompletableFuture<Void> clearHistoryFiltersAsync() {
        return overviewPanel.clearHistoryFiltersAsync();
    }

    public CompletableFuture<Transaction> createRepeatDraftForSelectedAsync() {
        return overviewPanel.createRepeatDraftForSelectedAsync();
    }

    public String getSelectedPaymentMethodId() {
        return cashInOutPanel != null ? cashInOutPanel.getSelectedPaymentMethodId() : null;
    }

    public Transaction getSelectedTransactionForReceipt() {
        return overviewPanel != null ? overviewPanel.getSelectedTransaction() : null;
    }

    private boolean ensureActiveSessionForInteraction() {
        SessionManager session = SessionManager.getInstance();
        if (!session.isLoggedIn()) {
            String reason = session.isLockedOutByPin()
                    ? "Session terminated after maximum failed PIN attempts."
                    : "Session expired due to inactivity. Please log in again.";
            handleSessionTermination(reason);
            return false;
        }
        session.touchActivity();
        return true;
    }

    private void handleInteractiveRefresh() {
        if (!ensureActiveSessionForInteraction()) {
            return;
        }
        refreshDashboardAsync().whenComplete((v, err) -> SwingUiSupport.runOnEdt(() -> {
            if (!logoutHandled && currentState == DashboardState.ERROR) {
                JLabel errorLabel = overviewPanel.getErrorStateLabel();
                String msg = errorLabel != null ? errorLabel.getText() : "Unable to load wallet data.";
                showDialogMessage(this, "Dashboard Refresh Error", msg, JOptionPane.ERROR_MESSAGE);
            }
        }));
    }

    private void promptPinSetupIfMissing(String message) {
        int choice = showConfirmDialog(
                this,
                "Transaction PIN Required",
                message + "\n\nWould you like to set your Transaction PIN now?",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
        );
        if (choice == JOptionPane.YES_OPTION) {
            openPinSettingsDialog();
        }
    }

    private void openPinSettingsDialog() {
        if (authService == null) {
            showDialogMessage(this,
                    "Transaction PIN",
                    "Transaction PIN management is unavailable without AuthService.",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        User currentUser = SessionManager.getInstance().getCurrentUser();
        if (currentUser == null) {
            handleSessionTermination("Session expired. Please log in again.");
            return;
        }
        SessionManager.getInstance().touchActivity();

        PinSettingsDialog dialog = new PinSettingsDialog(
                this,
                authService,
                currentUser.getUserId(),
                cachedHasPin,
                this::showDialogMessage,
                this::handleSessionTermination,
                () -> {
                    updatePinStatusDisplay(true);
                    lblStatus.setText("Transaction PIN updated.");
                }
        );
        dialog.setVisible(true);
    }

    private void applyRepeatDraftToUi(Transaction draft) {
        if (draft == null) {
            return;
        }
        if (draft.getType() == TransactionType.SEND_MONEY || draft instanceof SendMoneyTransaction) {
            sendMoneyPanel.loadRepeatDraft(draft);
            lblStatus.setText("Repeat Send Money draft prepared: " + draft.getReferenceNumber());
            navigateToTab("Send Money");
        } else if (draft instanceof CashInOutTransaction
                || draft.getType() == TransactionType.CASH_IN
                || draft.getType() == TransactionType.CASH_OUT) {
            String op = draft.getType() == TransactionType.CASH_OUT ? "CASH_OUT" : "CASH_IN";
            cashInOutPanel.loadRepeatDraft(draft);
            lblStatus.setText("Repeat " + op + " draft prepared: " + draft.getReferenceNumber());
            navigateToTab("Cash In / Out");
        } else if ((draft instanceof InvestMoneyTransaction
                || draft.getType() == TransactionType.INVESTMENT_BUY
                || draft.getType() == TransactionType.INVESTMENT_REDEEM)
                && investmentPanel != null) {
            String op = draft.getType() == TransactionType.INVESTMENT_REDEEM ? "REDEEM" : "BUY";
            investmentPanel.loadRepeatDraft(draft);
            lblStatus.setText("Repeat investment " + op + " draft prepared: " + draft.getReferenceNumber());
            navigateToTab("Investments");
        } else if (draft instanceof BillPaymentTransaction || draft.getType() == TransactionType.BILL_PAYMENT) {
            if (payBillsPanel != null) {
                payBillsPanel.loadRepeatDraft(draft);
            }
            lblStatus.setText("Repeat Bill Payment draft prepared: " + draft.getReferenceNumber());
            navigateToTab("Pay Bills");
        }
    }

    private void startSessionMonitor() {
        sessionMonitorTimer = new Timer(OtogConfig.getInstance().getSessionPollIntervalMs(), e -> {
            SessionManager session = SessionManager.getInstance();
            if (!session.isLoggedIn() && (session.isExpiredDueToInactivity() || session.isLockedOutByPin())) {
                String reason = session.isLockedOutByPin()
                        ? "Session terminated after maximum failed PIN attempts."
                        : "Session expired due to inactivity. Please log in again.";
                handleSessionTermination(reason);
            }
        });
        sessionMonitorTimer.setRepeats(true);
        sessionMonitorTimer.start();
    }

    private void checkSessionTerminatedOnError(String message) {
        SessionManager session = SessionManager.getInstance();
        if (!session.isLoggedIn() && (session.isExpiredDueToInactivity() || session.isLockedOutByPin())) {
            handleSessionTermination(message);
        }
    }

    private void handleSessionTermination(String reason) {
        if (logoutHandled) {
            return;
        }
        logoutHandled = true;
        if (isShowing()) {
            showDialogMessage(this, "Session Ended", reason, JOptionPane.WARNING_MESSAGE);
        }
        dispose();
        if (onLogoutCallback != null) {
            SwingUtilities.invokeLater(onLogoutCallback);
        }
    }

    private void updateNotificationsBadge(int unreadCount) {
        this.cachedUnreadNotifications = Math.max(0, unreadCount);
        if (btnNotifications != null) {
            btnNotifications.setText("Notifications (" + this.cachedUnreadNotifications + ")");
            btnNotifications.setForeground(this.cachedUnreadNotifications > 0
                    ? UiTheme.color(UiTheme.ColorRole.PRIMARY)
                    : UIManager.getColor("Button.foreground"));
        }
    }

    private void openNotificationInboxDialog() {
        if (!ensureActiveSessionForInteraction()) {
            return;
        }
        User user = SessionManager.getInstance().getCurrentUser();
        Wallet wallet = SessionManager.getInstance().getCurrentWallet();
        if (user == null) {
            handleSessionTermination("Session expired. Please log in again.");
            return;
        }
        NotificationInboxDialog dialog = new NotificationInboxDialog(
                this,
                walletService,
                user.getUserId(),
                wallet != null ? wallet.getWalletId() : null,
                this::updateNotificationsBadge
        );
        dialog.setVisible(true);
    }

    public boolean navigateToTab(String title) {
        if (title == null || tabs == null) {
            return false;
        }
        Integer index = tabIndexByTitle.get(title.trim().toLowerCase(Locale.ROOT));
        if (index == null || index < 0 || index >= tabs.getTabCount()) {
            return false;
        }
        // The tab ChangeListener pushes the previous tab onto navigationHistory.
        tabs.setSelectedIndex(index);
        return true;
    }

    public boolean navigateBack() {
        if (tabs == null || navigationHistory.isEmpty()) {
            return false;
        }
        String previousTitle = navigationHistory.pop();
        Integer index = tabIndexByTitle.get(previousTitle.trim().toLowerCase(Locale.ROOT));
        if (index == null || index < 0 || index >= tabs.getTabCount()) {
            updateBackButtonState();
            return false;
        }
        suppressHistoryRecording = true;
        try {
            tabs.setSelectedIndex(index);
        } finally {
            suppressHistoryRecording = false;
        }
        updateBackButtonState();
        return true;
    }

    public int getNavigationHistoryDepth() {
        return navigationHistory.size();
    }

    public List<String> getVisibleTabTitles() {
        List<String> titles = new ArrayList<>();
        if (tabs != null) {
            for (int i = 0; i < tabs.getTabCount(); i++) {
                titles.add(tabs.getTitleAt(i));
            }
        }
        return Collections.unmodifiableList(titles);
    }

    public void setOnLogoutCallback(Runnable callback) {
        this.onLogoutCallback = callback;
    }

    public void triggerLogout() {
        handleLogout();
    }

    private void confirmAndLogout() {
        int choice = showConfirmDialog(
                this,
                "Confirm Logout",
                "Are you sure you want to log out of your OTOG E-Wallet session?",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE
        );
        if (choice == JOptionPane.YES_OPTION) {
            handleLogout();
        }
    }

    private void handleLogout() {
        if (logoutHandled) {
            return;
        }
        logoutHandled = true;
        User currentUser = SessionManager.getInstance().getCurrentUser();
        try {
            if (authService != null && currentUser != null) {
                authService.logout(currentUser.getUserId());
            } else {
                SessionManager.getInstance().logout();
            }
        } catch (Exception ex) {
            SessionManager.getInstance().logout();
            if (isShowing()) {
                showDialogMessage(this, "Logout Warning",
                        "Session cleared locally, though server logout reported: "
                                + SwingUiSupport.extractErrorMessage(ex),
                        JOptionPane.WARNING_MESSAGE);
            }
        }
        dispose();
        if (onLogoutCallback != null) {
            SwingUtilities.invokeLater(onLogoutCallback);
        }
    }

    protected void showDialogMessage(Component parent, String title, String message, int messageType) {
        JOptionPane.showMessageDialog(parent != null ? parent : this, message, title, messageType);
    }

    protected void showComponentDialogMessage(Component parent, String title, Object message, int messageType) {
        JOptionPane.showMessageDialog(parent != null ? parent : this, message, title, messageType);
    }

    protected int showConfirmDialog(Component parent, String title, Object message, int optionType, int messageType) {
        return JOptionPane.showConfirmDialog(parent != null ? parent : this, message, title, optionType, messageType);
    }

    @Override
    public void dispose() {
        if (sessionMonitorTimer != null) {
            sessionMonitorTimer.stop();
        }
        if (ownedDatabaseManager != null) {
            ownedDatabaseManager.close();
        }
        super.dispose();
    }

    public DashboardState getCurrentState() {
        return currentState;
    }

    public CompletableFuture<Void> getInitialLoadFuture() {
        return initialLoadFuture;
    }

    public JLabel getWelcomeLabel() {
        return lblWelcome;
    }

    public JLabel getBalanceLabel() {
        return lblBalance;
    }

    public JLabel getWalletIdLabel() {
        return lblWalletId;
    }

    public JLabel getPinStatusLabel() {
        return lblPinStatus;
    }

    public JLabel getStatusLabel() {
        return lblStatus;
    }

    public JLabel getLoadingStateLabel() {
        return overviewPanel != null ? overviewPanel.getLoadingStateLabel() : null;
    }

    public JLabel getEmptyStateLabel() {
        return overviewPanel != null ? overviewPanel.getEmptyStateLabel() : null;
    }

    public JLabel getErrorStateLabel() {
        return overviewPanel != null ? overviewPanel.getErrorStateLabel() : null;
    }

    public JLabel getCashFeedbackLabel() {
        return cashInOutPanel != null ? cashInOutPanel.getCashFeedbackLabel() : null;
    }

    public JLabel getActivityFeedbackLabel() {
        return overviewPanel != null ? overviewPanel.getActivityFeedbackLabel() : null;
    }

    public JLabel getSendFeedbackLabel() {
        return sendMoneyPanel != null ? sendMoneyPanel.getSendFeedbackLabel() : null;
    }

    public JTable getRecentTransactionsTable() {
        return overviewPanel != null ? overviewPanel.getTransactionsTable() : null;
    }

    public JTabbedPane getTabs() {
        return tabs;
    }

    public boolean isLastLoadExecutedOffEdt() {
        return lastLoadExecutedOffEdt;
    }

    public boolean isLastPaymentExecutedOffEdt() {
        return lastPaymentExecutedOffEdt;
    }

    public boolean isLastAdminLoadExecutedOffEdt() {
        return lastAdminLoadExecutedOffEdt;
    }

    public JButton getCashInQuickNavButton() {
        return overviewPanel != null ? overviewPanel.getNavCashInButton() : null;
    }

    public JButton getCashOutQuickNavButton() {
        return overviewPanel != null ? overviewPanel.getNavCashOutButton() : null;
    }

    public JButton getSendMoneyQuickNavButton() {
        return overviewPanel != null ? overviewPanel.getNavSendMoneyButton() : null;
    }

    public JButton getInvestQuickNavButton() {
        return overviewPanel != null ? overviewPanel.getNavInvestButton() : null;
    }

    public JButton getPinSettingsButton() {
        return btnPinSettings;
    }

    public JButton getRetryLoadButton() {
        return overviewPanel != null ? overviewPanel.getRetryLoadButton() : null;
    }

    public JButton getBackButton() {
        return btnBack;
    }

    public JButton getRefreshButton() {
        return btnRefresh;
    }

    public JButton getNotificationsButton() {
        return btnNotifications;
    }

    public int getCachedUnreadNotifications() {
        return cachedUnreadNotifications;
    }

    public TransactionFilter getActiveHistoryFilter() {
        return overviewPanel != null ? overviewPanel.getActiveHistoryFilter() : TransactionFilter.empty();
    }

    public JComboBox<String> getFilterTypeComboBox() {
        return overviewPanel != null ? overviewPanel.getFilterTypeComboBox() : null;
    }

    public JComboBox<String> getFilterStatusComboBox() {
        return overviewPanel != null ? overviewPanel.getFilterStatusComboBox() : null;
    }

    public JTextField getFilterFromDateField() {
        return overviewPanel != null ? overviewPanel.getFilterFromDateField() : null;
    }

    public JTextField getFilterToDateField() {
        return overviewPanel != null ? overviewPanel.getFilterToDateField() : null;
    }

    public JTextField getFilterKeywordField() {
        return overviewPanel != null ? overviewPanel.getFilterKeywordField() : null;
    }

    public JButton getApplyFiltersButton() {
        return overviewPanel != null ? overviewPanel.getApplyFiltersButton() : null;
    }

    public JButton getClearFiltersButton() {
        return overviewPanel != null ? overviewPanel.getClearFiltersButton() : null;
    }

    public JButton getRepeatDraftButton() {
        return overviewPanel != null ? overviewPanel.getRepeatDraftButton() : null;
    }

    public JComboBox<String> getCashOperationComboBox() {
        return cashInOutPanel != null ? cashInOutPanel.getCashOperationComboBox() : null;
    }

    public JComboBox<String> getPaymentMethodComboBox() {
        return cashInOutPanel != null ? cashInOutPanel.getPaymentMethodComboBox() : null;
    }

    public JComboBox<String> getProviderModeComboBox() {
        return cashInOutPanel != null ? cashInOutPanel.getProviderModeComboBox() : null;
    }

    public JButton getCheckStatusButton() {
        return overviewPanel != null ? overviewPanel.getCheckStatusButton() : null;
    }

    public JPasswordField getCashPinField() {
        return cashInOutPanel != null ? cashInOutPanel.getCashPinField() : null;
    }

    public JPasswordField getSendPinField() {
        return sendMoneyPanel != null ? sendMoneyPanel.getSendPinField() : null;
    }

    public JButton getSubmitCashButton() {
        return cashInOutPanel != null ? cashInOutPanel.getSubmitCashOpButton() : null;
    }

    public JButton getCashBackToOverviewButton() {
        return cashInOutPanel != null ? cashInOutPanel.getCashBackToOverviewButton() : null;
    }

    public JButton getSubmitSendMoneyButton() {
        return sendMoneyPanel != null ? sendMoneyPanel.getSubmitSendMoneyButton() : null;
    }

    public JButton getSendBackToOverviewButton() {
        return sendMoneyPanel != null ? sendMoneyPanel.getSendBackToOverviewButton() : null;
    }

    public JTextField getCashAmountField() {
        return cashInOutPanel != null ? cashInOutPanel.getCashAmountField() : null;
    }

    public JTextField getRecipientField() {
        return sendMoneyPanel != null ? sendMoneyPanel.getRecipientField() : null;
    }

    public JTextField getSendAmountField() {
        return sendMoneyPanel != null ? sendMoneyPanel.getSendAmountField() : null;
    }

    public JPanel getRootPanel() {
        return pnlRoot;
    }

    public OverviewPanel getOverviewPanel() {
        return overviewPanel;
    }

    public CashInOutPanel getCashInOutPanel() {
        return cashInOutPanel;
    }

    public SendMoneyPanel getSendMoneyPanel() {
        return sendMoneyPanel;
    }

    public InvestmentPanel getInvestmentPanel() {
        return investmentPanel;
    }

    public JComboBox<String> getInvestOperationComboBox() {
        return investmentPanel != null ? investmentPanel.getOperationComboBox() : null;
    }

    public JTextField getInvestUnitsField() {
        return investmentPanel != null ? investmentPanel.getUnitsField() : null;
    }

    public JTextField getInvestAmountField() {
        return investmentPanel != null ? investmentPanel.getAmountField() : null;
    }

    public JPasswordField getInvestPinField() {
        return investmentPanel != null ? investmentPanel.getPinField() : null;
    }

    public JLabel getInvestPreviewSummaryLabel() {
        return investmentPanel != null ? investmentPanel.getPreviewSummaryLabel() : null;
    }

    public JButton getPreviewInvestButton() {
        return investmentPanel != null ? investmentPanel.getPreviewButton() : null;
    }

    public JButton getSubmitInvestButton() {
        return investmentPanel != null ? investmentPanel.getSubmitButton() : null;
    }

    public JLabel getInvestFeedbackLabel() {
        return investmentPanel != null ? investmentPanel.getFeedbackLabel() : null;
    }

    public AdminControlsPanel getAdminControlsPanel() {
        return adminControlsPanel;
    }

    public MerchantQrPanel getMerchantQrPanel() {
        return merchantQrPanel;
    }

    public JButton getExportStatementButton() {
        return overviewPanel != null ? overviewPanel.getExportStatementButton() : null;
    }

    public JButton getLoadStatementButton() {
        return overviewPanel != null ? overviewPanel.getLoadStatementButton() : null;
    }

    public WalletOperations getWalletService() {
        return walletService;
    }

    public JButton getExportReceiptButton() {
        return overviewPanel != null ? overviewPanel.getExportReceiptButton() : null;
    }

    private void $$$setupUI$$$() {
        OtogConfig config = OtogConfig.getInstance();
        pnlRoot = new JPanel();
        pnlRoot.setLayout(new GridLayoutManager(3, 1, new Insets(12, 14, 12, 14), 12, 10));

        pnlHeader = new JPanel();
        pnlHeader.setLayout(new GridLayoutManager(2, 2, new Insets(0, 0, 8, 0), 12, 6));
        pnlRoot.add(pnlHeader, new GridConstraints(0, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        pnlIdentity = new JPanel();
        pnlIdentity.setLayout(new GridLayoutManager(3, 1, new Insets(0, 0, 0, 0), 0, 2));
        pnlHeader.add(pnlIdentity, new GridConstraints(0, 0, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        lblWelcome = new JLabel("Welcome");
        pnlIdentity.add(lblWelcome, new GridConstraints(0, 0, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        lblWalletId = new JLabel("Wallet: --");
        pnlIdentity.add(lblWalletId, new GridConstraints(1, 0, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        lblPinStatus = new JLabel("Transaction PIN: Checking...");
        pnlIdentity.add(lblPinStatus, new GridConstraints(2, 0, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        pnlBalanceBox = new JPanel();
        pnlBalanceBox.setLayout(new GridLayoutManager(2, 1, new Insets(0, 8, 0, 0), 0, 1));
        pnlHeader.add(pnlBalanceBox, new GridConstraints(0, 1, 1, 1,
                GridConstraints.ANCHOR_EAST, GridConstraints.FILL_VERTICAL,
                GridConstraints.SIZEPOLICY_FIXED,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        JLabel balanceCaption = new JLabel("Available Balance", SwingConstants.RIGHT);
        balanceCaption.setFont(UiTheme.font(UiTheme.FontRole.SMALL));
        balanceCaption.setForeground(UiTheme.color(UiTheme.ColorRole.MUTED));
        pnlBalanceBox.add(balanceCaption, new GridConstraints(0, 0, 1, 1,
                GridConstraints.ANCHOR_EAST, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        lblBalance = new JLabel(MoneyUtil.format(MoneyUtil.ZERO, config.getDefaultCurrency()), SwingConstants.RIGHT);
        pnlBalanceBox.add(lblBalance, new GridConstraints(1, 0, 1, 1,
                GridConstraints.ANCHOR_EAST, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        pnlBalanceAndActions = new JPanel();
        pnlBalanceAndActions.setLayout(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        pnlHeader.add(pnlBalanceAndActions, new GridConstraints(1, 0, 1, 2,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        btnBack = new JButton("\u2190 Back");
        btnBack.setEnabled(false);
        pnlBalanceAndActions.add(btnBack);

        btnRefresh = new JButton("Refresh");
        pnlBalanceAndActions.add(btnRefresh);

        btnNotifications = new JButton("Notifications (0)");
        pnlBalanceAndActions.add(btnNotifications);

        btnPinSettings = new JButton("Transaction PIN");
        pnlBalanceAndActions.add(btnPinSettings);

        btnLogout = new JButton("Logout");
        pnlBalanceAndActions.add(btnLogout);

        tabs = new JTabbedPane();
        pnlRoot.add(tabs, new GridConstraints(1, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW,
                new Dimension(200, 200), null, null, 0, false));

        pnlFooter = new JPanel();
        pnlFooter.setLayout(new GridLayoutManager(1, 1, new Insets(6, 2, 2, 2), 0, 0));
        pnlRoot.add(pnlFooter, new GridConstraints(2, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        lblStatus = new JLabel("Initializing dashboard...");
        pnlFooter.add(lblStatus, new GridConstraints(0, 0, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));
    }

    public JComponent $$$getRootComponent$$$() {
        return pnlRoot;
    }

    private static final class DashboardSnapshot {
        private final User user;
        private final Wallet wallet;
        private final List<Transaction> transactions;
        private final List<PaymentMethod> paymentMethods;
        private final boolean hasPin;
        private final int unreadNotifications;
        private final InvestmentService.SimulatedProduct investProduct;
        private final InvestmentService.InvestmentHolding investHolding;
        private final List<InvestMoneyTransaction> investActivities;

        private DashboardSnapshot(User user, Wallet wallet, List<Transaction> transactions,
                                  List<PaymentMethod> paymentMethods, boolean hasPin,
                                  int unreadNotifications,
                                  InvestmentService.SimulatedProduct investProduct,
                                  InvestmentService.InvestmentHolding investHolding,
                                  List<InvestMoneyTransaction> investActivities) {
            this.user = user;
            this.wallet = wallet;
            this.transactions = transactions;
            this.paymentMethods = paymentMethods;
            this.hasPin = hasPin;
            this.unreadNotifications = unreadNotifications;
            this.investProduct = investProduct;
            this.investHolding = investHolding;
            this.investActivities = investActivities;
        }
    }
}
