package com.otog.ui;

import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import com.intellij.uiDesigner.core.Spacer;
import com.otog.database.DatabaseManager;
import com.otog.model.BillPaymentTransaction;
import com.otog.model.CashInOutTransaction;
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
import com.otog.util.MoneyUtil;
import com.otog.util.OtogConfig;
import com.otog.util.UiTheme;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
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
    private InvestmentPanel investmentPanel;
    private AdminControlsPanel adminControlsPanel;
    private final StatementFileService statementFileService = new StatementFileService();

    private JButton btnNavCashIn;
    private JButton btnNavCashOut;
    private JButton btnNavSendMoney;
    private JButton btnNavPayBills;
    private JButton btnNavInvest;
    private JButton btnNavPinSettings;
    private JButton btnExportStatement;
    private JButton btnLoadStatement;

    private JComboBox<String> cmbFilterType;
    private JComboBox<String> cmbFilterStatus;
    private JTextField txtFilterFromDate;
    private JTextField txtFilterToDate;
    private JButton btnApplyFilters;
    private JButton btnClearFilters;

    private CardLayout activityCardLayout;
    private JPanel activityCardPanel;
    private JLabel lblLoadingState;
    private JLabel lblEmptyState;
    private JLabel lblErrorState;
    private JButton btnRetryLoad;
    private DefaultTableModel transactionsTableModel;
    private JTable tblTransactions;
    private JButton btnViewReceipt;
    private JButton btnExportReceipt;
    private JButton btnCheckStatus;
    private JButton btnRepeatDraft;
    private JLabel lblActivityFeedback;

    private PayBillsPanel payBillsPanel;
    private AdminControlsPanel adminControlsPanel;

    private JComboBox<String> cmbInvestOperation;
    private JTextField txtInvestUnits;
    private JTextField txtInvestAmount;
    private JPasswordField txtInvestPin;
    private JLabel lblInvestPreviewSummary;
    private JButton btnPreviewInvest;
    private JButton btnSubmitInvest;
    private JButton btnInvestBackToOverview;
    private JLabel lblInvestFeedback;
    private boolean updatingInvestmentInputs;

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
        setSize(config.getUiDimension("ui.dashboard.width", "ui.dashboard.height", 940, 640));
        setMinimumSize(config.getUiDimension("ui.dashboard.min.width", "ui.dashboard.min.height", 840, 560));
        setLocationRelativeTo(null);

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
    }

    private JTabbedPane buildTabsPanel() {
        tabs.removeAll();
        tabIndexByTitle.clear();

        overviewPanel = new OverviewPanel(walletService, new StatementFileService());
        cashInOutPanel = new CashInOutPanel(walletService, authService, walletService.getCurrentProviderMode());
        sendMoneyPanel = new SendMoneyPanel(walletService, authService);
        payBillsPanel = new PayBillsPanel(walletService, authService);

        wireOverviewPanel();
        wireCashInOutPanel();
        wireSendMoneyPanel();
        wirePayBillsPanel();

        registerTab("Overview", overviewPanel);
        registerTab("Cash In / Out", cashInOutPanel);
        registerTab("Send Money", sendMoneyPanel);
        registerTab("Pay Bills", payBillsPanel);

        // Hide unfinished modules unless an implementation is explicitly provided
        if (loanService != null) {
            registerTab("Loans", buildServiceReadyNotice("Loans module active"));
        }
        if (investmentService != null) {
            investmentPanel = new InvestmentPanel();
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

    private void wireOverviewPanel() {
        btnNavCashIn = overviewPanel.getNavCashInButton();
        btnNavCashOut = overviewPanel.getNavCashOutButton();
        btnNavSendMoney = overviewPanel.getNavSendMoneyButton();
        btnNavPayBills = overviewPanel.getNavPayBillsButton();
        btnNavInvest = overviewPanel.getNavInvestButton();
        btnNavPinSettings = overviewPanel.getNavPinSettingsButton();
        btnExportStatement = overviewPanel.getExportStatementButton();
        btnLoadStatement = overviewPanel.getLoadStatementButton();

        cmbFilterType = overviewPanel.getFilterTypeComboBox();
        cmbFilterStatus = overviewPanel.getFilterStatusComboBox();
        txtFilterFromDate = overviewPanel.getFilterFromDateField();
        txtFilterToDate = overviewPanel.getFilterToDateField();
        btnApplyFilters = overviewPanel.getApplyFiltersButton();
        btnClearFilters = overviewPanel.getClearFiltersButton();

        activityCardLayout = overviewPanel.getActivityCardLayout();
        activityCardPanel = overviewPanel.getActivityCardPanel();
        lblLoadingState = overviewPanel.getLoadingStateLabel();
        lblEmptyState = overviewPanel.getEmptyStateLabel();
        lblErrorState = overviewPanel.getErrorStateLabel();
        btnRetryLoad = overviewPanel.getRetryLoadButton();
        transactionsTableModel = overviewPanel.getTransactionsTableModel();
        tblTransactions = overviewPanel.getTransactionsTable();
        btnCheckStatus = overviewPanel.getCheckStatusButton();
        btnRepeatDraft = overviewPanel.getRepeatDraftButton();
        btnViewReceipt = overviewPanel.getViewReceiptButton();
        btnExportReceipt = overviewPanel.getExportReceiptButton();
        lblActivityFeedback = overviewPanel.getActivityFeedbackLabel();

        btnNavCashIn.addActionListener(e -> {
            cmbCashOperation.setSelectedItem("CASH_IN");
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

        if (btnNavPayBills != null) {
            btnNavPayBills.addActionListener(e -> {
                navigateToTab("Pay Bills");
                if (payBillsPanel != null) {
                    payBillsPanel.getBillReferenceField().requestFocusInWindow();
                }
            });
        }

        if (btnNavInvest != null) {
            btnNavInvest.setVisible(investmentService != null);
            btnNavInvest.addActionListener(e -> {
                if (navigateToTab("Investments") && txtInvestUnits != null) {
                    txtInvestUnits.requestFocusInWindow();
                }
            });
        }

        btnNavPinSettings.setEnabled(authService != null);
        btnNavPinSettings.addActionListener(e -> openPinSettingsDialog());

        btnExportStatement.addActionListener(e -> handleInteractiveExportStatement());
        btnLoadStatement.addActionListener(e -> handleInteractiveLoadStatement());

        btnRetryLoad.addActionListener(e -> handleInteractiveRefresh());
        tblTransactions.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && tblTransactions.getSelectedRow() >= 0) {
                    showSelectedReceipt();
                }
            }
        });
        tblTransactions.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateTableActionButtonsState();
            }
        });
        overviewPanel.setOnQuickNavPinSettings(this::openPinSettingsDialog);
        overviewPanel.setBeforeInteractiveAction(this::ensureActiveSessionForInteraction);
        overviewPanel.setOnRetryLoad(this::handleInteractiveRefresh);
        overviewPanel.setOnRefreshRequested(this::refreshDashboardAsync);
        overviewPanel.setOnStatusMessage(lblStatus::setText);
        overviewPanel.setOnStatusLookupFeedback((msg, color) -> cashInOutPanel.showFeedback(msg, color));
        overviewPanel.setOnRepeatDraftReady(this::applyRepeatDraftToUi);
        overviewPanel.setDialogMessageCallback(this::showDialogMessage);
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
        cmbInvestOperation = investmentPanel.getOperationComboBox();
        txtInvestUnits = investmentPanel.getUnitsField();
        txtInvestAmount = investmentPanel.getAmountField();
        txtInvestPin = investmentPanel.getPinField();
        lblInvestPreviewSummary = investmentPanel.getPreviewSummaryLabel();
        btnPreviewInvest = investmentPanel.getPreviewButton();
        btnSubmitInvest = investmentPanel.getSubmitButton();
        btnInvestBackToOverview = investmentPanel.getBackToOverviewButton();
        lblInvestFeedback = investmentPanel.getFeedbackLabel();

        cmbInvestOperation.addActionListener(e -> {
            boolean isBuy = "BUY".equals(cmbInvestOperation.getSelectedItem());
            txtInvestAmount.setEnabled(isBuy);
            if (!isBuy) {
                setInvestmentInputTexts(txtInvestUnits.getText(), "");
            }
            resetInvestPreviewSummary(isBuy);
        });

        txtInvestUnits.getDocument().addDocumentListener(new DocumentListener() {
            private void onUnitsEdited() {
                if (updatingInvestmentInputs) {
                    return;
                }
                if (txtInvestUnits.getText() != null && !txtInvestUnits.getText().isEmpty()
                        && txtInvestAmount.getText() != null && !txtInvestAmount.getText().isEmpty()) {
                    updatingInvestmentInputs = true;
                    try {
                        txtInvestAmount.setText("");
                    } finally {
                        updatingInvestmentInputs = false;
                    }
                }
            }

            @Override
            public void insertUpdate(DocumentEvent e) {
                onUnitsEdited();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                onUnitsEdited();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                onUnitsEdited();
            }
        });

        txtInvestAmount.getDocument().addDocumentListener(new DocumentListener() {
            private void onAmountEdited() {
                if (updatingInvestmentInputs) {
                    return;
                }
                if (txtInvestAmount.getText() != null && !txtInvestAmount.getText().isEmpty()
                        && txtInvestUnits.getText() != null && !txtInvestUnits.getText().isEmpty()) {
                    updatingInvestmentInputs = true;
                    try {
                        txtInvestUnits.setText("");
                    } finally {
                        updatingInvestmentInputs = false;
                    }
                }
            }

            @Override
            public void insertUpdate(DocumentEvent e) {
                onAmountEdited();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                onAmountEdited();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                onAmountEdited();
            }
        });

        btnInvestBackToOverview.addActionListener(e -> navigateToTab("Overview"));
        btnPreviewInvest.addActionListener(e -> handleInteractiveInvestPreview());
        btnSubmitInvest.addActionListener(e -> handleInteractiveInvestSubmit());
        txtInvestUnits.addActionListener(e -> handleInteractiveInvestPreview());
        txtInvestAmount.addActionListener(e -> handleInteractiveInvestPreview());
        txtInvestPin.addActionListener(e -> handleInteractiveInvestSubmit());
    }

    private void setInvestmentInputTexts(String units, String amount) {
        updatingInvestmentInputs = true;
        try {
            if (txtInvestUnits != null && units != null) {
                txtInvestUnits.setText(units);
            }
            if (txtInvestAmount != null && amount != null) {
                txtInvestAmount.setText(amount);
            }
        } finally {
            updatingInvestmentInputs = false;
        }
    }

    private void resetInvestPreviewSummary(boolean isBuy) {
        if (lblInvestPreviewSummary == null) {
            return;
        }
        String defaultCurrency = OtogConfig.getInstance().getDefaultCurrency();
        lblInvestPreviewSummary.setForeground(UiTheme.color(UiTheme.ColorRole.INFO));
        lblInvestPreviewSummary.setText(isBuy
                ? "Enter units or " + defaultCurrency + " amount, then click Preview Units & Cost."
                : "Enter units to redeem, then click Preview Units & Cost.");
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
                        UiSupport.runOnEdt(() -> lblStatus.setText(billStatusText));
                    }
                });
            } else {
                lastPayBillsRefreshFuture = refreshDashboardAsync();
            }
        });
        payBillsPanel.setOnPaymentError(err -> {
            String msg = UiSupport.extractErrorMessage(err);
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
            lblPinStatus.setText("Transaction PIN: Not Set (required for Cash Out, Send Money, Pay Bills & Investments)");
            lblPinStatus.setForeground(UiTheme.color(UiTheme.ColorRole.WARNING));
            if (btnPinSettings != null) {
                btnPinSettings.setText("Set PIN");
            }
        } else if (pinVerified) {
            lblPinStatus.setText("Transaction PIN: Set & Verified for active session");
            lblPinStatus.setForeground(UiTheme.color(UiTheme.ColorRole.SUCCESS));
            if (btnPinSettings != null) {
                btnPinSettings.setText("Change PIN");
            }
        } else {
            lblPinStatus.setText("Transaction PIN: Set (enter PIN on outgoing payments)");
            lblPinStatus.setForeground(UiTheme.color(UiTheme.ColorRole.PRIMARY));
            if (btnPinSettings != null) {
                btnPinSettings.setText("Change PIN");
            }
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

        UiSupport.runOnEdt(() -> {
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
                    populatePaymentMethodsCombo(snapshot.paymentMethods);
                    populateTransactionsTable(snapshot.wallet.getWalletId(), snapshot.transactions);
                    if (investmentPanel != null) {
                        investmentPanel.updateProductAndHoldings(snapshot.investProduct, snapshot.investHolding);
                        investmentPanel.updateActivityTable(snapshot.investActivities);
                    }
                    boolean filtered = activeHistoryFilter != null && !activeHistoryFilter.isEmpty();
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
                    Throwable cause = UiSupport.unwrapCause(ex);
                    String message = UiSupport.extractErrorMessage(cause);
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
        refreshDashboardAsync().whenComplete((v, err) -> UiSupport.runOnEdt(() -> {
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

    public CompletableFuture<Transaction> submitStatusLookupAsync(String referenceNumber) {
        CompletableFuture<Transaction> future = new CompletableFuture<>();
        runOnEdt(() -> {
            if (btnCheckStatus != null) {
                btnCheckStatus.setEnabled(false);
            }
            lblStatus.setText("Checking bank status for " + referenceNumber + "...");
        });

        SwingWorker<Transaction, Void> worker = new SwingWorker<>() {
            @Override
            protected Transaction doInBackground() {
                SessionManager.getInstance().requireAuthentication();
                return walletService.statusLookup(referenceNumber);
            }

            @Override
            protected void done() {
                updateTableActionButtonsState();
                try {
                    Transaction txn = get();
                    String provRef = txn.getProviderReference() != null ? " | Bank Ref: " + txn.getProviderReference() : "";
                    String provStatus = txn.getProviderStatus() != null ? " [" + txn.getProviderStatus() + "]" : "";
                    Color color;
                    String msg;
                    if (txn.getStatus() == TransactionStatus.COMPLETED) {
                        color = UiTheme.color(UiTheme.ColorRole.SUCCESS);
                        msg = "Status lookup settled COMPLETED: "
                                + txn.getReferenceNumber() + provRef + provStatus;
                    } else if (txn.getStatus() == TransactionStatus.FAILED) {
                        color = UiTheme.color(UiTheme.ColorRole.ERROR);
                        msg = "Status lookup confirmed FAILED: "
                                + txn.getReferenceNumber() + provRef + provStatus;
                    } else {
                        color = UiTheme.color(UiTheme.ColorRole.WARNING);
                        msg = "Status lookup remains PENDING: "
                                + txn.getReferenceNumber() + provRef + provStatus;
                    }
                    lblCashFeedback.setForeground(color);
                    lblCashFeedback.setText(msg);
                    if (lblActivityFeedback != null) {
                        lblActivityFeedback.setForeground(color);
                        lblActivityFeedback.setText(msg);
                    }
                    String syncStatusText = "Status sync: " + txn.getStatus() + provStatus;
                    lblStatus.setText(syncStatusText);
                    refreshDashboardAsync().whenComplete((v, err) -> {
                        runOnEdt(() -> lblStatus.setText(syncStatusText));
                        future.complete(txn);
                    });
                } catch (Exception ex) {
                    Throwable cause = unwrapCause(ex);
                    String message = (cause.getMessage() != null && !cause.getMessage().trim().isEmpty())
                            ? cause.getMessage()
                            : cause.getClass().getSimpleName();
                    if (lblActivityFeedback != null) {
                        lblActivityFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
                        lblActivityFeedback.setText("Status lookup failed: " + message);
                    }
                    lblStatus.setText("Status lookup failed: " + message);
                    future.completeExceptionally(cause);
                }
            }
        };
        worker.execute();
        return future;
    }

    public CompletableFuture<Transaction> submitSendMoneyAsync(String recipient, String amountText, String note) {
        return submitSendMoneyAsync(recipient, amountText, note, null);
    }

    public CompletableFuture<Transaction> submitSendMoneyAsync(String recipient, String amountText,
                                                              String note, String rawPin) {
        return executeSendMoneyAsync(null, recipient, amountText, note, rawPin);
    }

    private CompletableFuture<Transaction> executeSendMoneyAsync(String explicitReference, String recipient,
                                                               String amountText, String note, String rawPin) {
        CompletableFuture<Transaction> future = new CompletableFuture<>();

        runOnEdt(() -> {
            btnSubmitSendMoney.setEnabled(false);
            lblSendFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.INFO));
            lblSendFeedback.setText("Sending money...");
            lblStatus.setText("Processing transfer...");
        });

        SwingWorker<Transaction, Void> worker = new SwingWorker<>() {
            @Override
            protected Transaction doInBackground() {
                lastPaymentExecutedOffEdt = !SwingUtilities.isEventDispatchThread();
                SessionManager session = SessionManager.getInstance();
                session.requireAuthentication();
                User user = session.getCurrentUser();
                Wallet wallet = session.getCurrentWallet();
                if (wallet == null) {
                    wallet = session.refreshWallet(walletService);
                }

                if (recipient == null || recipient.trim().isEmpty()) {
                    throw new IllegalArgumentException("Recipient identifier is required.");
                }
                BigDecimal amount = MoneyUtil.validatePositiveAmount(MoneyUtil.parse(amountText));
                String ref = (explicitReference != null && !explicitReference.trim().isEmpty())
                        ? explicitReference.trim()
                        : IdGenerator.nextReferenceNumber();
                String cleanPin = rawPin != null ? rawPin.trim() : "";
                if (!cleanPin.isEmpty()) {
                    if (authService != null && user != null) {
                        verifyPinOrThrow(user.getUserId(), cleanPin);
                        return walletService.sendMoneyToRecipient(
                                ref, wallet.getWalletId(), recipient.trim(), amount, MoneyUtil.ZERO, note);
                    }
                    return walletService.sendMoneyToRecipientWithPin(
                            ref, wallet.getWalletId(), recipient.trim(), amount, MoneyUtil.ZERO, note, cleanPin);
                }
                return walletService.sendMoneyToRecipient(
                        ref, wallet.getWalletId(), recipient.trim(), amount, MoneyUtil.ZERO, note);
            }

            @Override
            protected void done() {
                btnSubmitSendMoney.setEnabled(true);
                try {
                    Transaction txn = get();
                    txtSendAmount.setText("");
                    txtSendNote.setText("");
                    lblSendFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.SUCCESS));
                    lblSendFeedback.setText("Transfer successful: "
                            + MoneyUtil.format(txn.getAmount(), txn.getCurrency())
                            + " (Ref: " + txn.getReferenceNumber() + ")");
                    refreshDashboardAsync().whenComplete((v, err) -> future.complete(txn));
                } catch (Exception ex) {
                    Throwable cause = unwrapCause(ex);
                    String message = extractErrorMessage(cause);
                    lblSendFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
                    lblSendFeedback.setText("Transfer failed: " + message);
                    lblStatus.setText("Transfer failed: " + message);
                    checkSessionTerminatedOnError(message);
                    future.completeExceptionally(cause);
                }
            }
        };
        worker.execute();
        return future;
    }

    public CompletableFuture<InvestmentService.InvestmentPreview> previewInvestmentAsync(
            String operation, String unitsText, String amountText) {
        CompletableFuture<InvestmentService.InvestmentPreview> future = new CompletableFuture<>();
        if (investmentService == null) {
            future.completeExceptionally(new IllegalStateException("InvestmentService is not enabled."));
            return future;
        }
        String rawOp = operation != null ? operation.trim().toUpperCase(Locale.ROOT) : "";

        runOnEdt(() -> {
            if (btnPreviewInvest != null) {
                btnPreviewInvest.setEnabled(false);
            }
            if (lblInvestPreviewSummary != null) {
                lblInvestPreviewSummary.setForeground(UiTheme.color(UiTheme.ColorRole.INFO));
                lblInvestPreviewSummary.setText("Calculating preview...");
            }
        });

        new SwingWorker<InvestmentService.InvestmentPreview, Void>() {
            @Override
            protected InvestmentService.InvestmentPreview doInBackground() {
                String op = requireValidInvestmentOperation(rawOp);
                SessionManager session = SessionManager.getInstance();
                session.requireAuthentication();
                Wallet wallet = session.getCurrentWallet();
                if (wallet == null) {
                    wallet = session.refreshWallet(walletService);
                }
                BigDecimal units = parseOptionalDecimal(unitsText);
                BigDecimal amount = parseOptionalDecimal(amountText);
                if ("REDEEM".equals(op)) {
                    return investmentService.previewRedeem(wallet.getWalletId(), units);
                }
                return investmentService.previewBuy(wallet.getWalletId(), units, amount);
            }

            @Override
            protected void done() {
                if (btnPreviewInvest != null) {
                    btnPreviewInvest.setEnabled(true);
                }
                try {
                    InvestmentService.InvestmentPreview preview = get();
                    setInvestmentInputTexts(
                            preview.getUnits().toPlainString(),
                            "BUY".equals(preview.getOperation()) ? preview.getTotalAmount().toPlainString() : null
                    );
                    if (lblInvestPreviewSummary != null) {
                        lblInvestPreviewSummary.setForeground(UiTheme.color(UiTheme.ColorRole.PRIMARY));
                        lblInvestPreviewSummary.setText(preview.getOperation() + " " + preview.getUnits().toPlainString()
                                + " units @ " + MoneyUtil.format(preview.getSimulatedUnitPrice(), preview.getCurrency())
                                + " [SIMULATED] = " + MoneyUtil.format(preview.getTotalAmount(), preview.getCurrency())
                                + " (Projected holdings: " + preview.getProjectedHoldingUnits().toPlainString() + " units)");
                    }
                    if (lblInvestFeedback != null) {
                        lblInvestFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.INFO));
                        lblInvestFeedback.setText("Preview ready — review units and cost, then click Confirm Investment.");
                    }
                    future.complete(preview);
                } catch (Exception ex) {
                    Throwable cause = unwrapCause(ex);
                    String message = extractErrorMessage(cause);
                    if (lblInvestPreviewSummary != null) {
                        lblInvestPreviewSummary.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
                        lblInvestPreviewSummary.setText("Preview failed: " + message);
                    }
                    if (lblInvestFeedback != null) {
                        lblInvestFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
                        lblInvestFeedback.setText("Preview failed: " + message);
                    }
                    checkSessionTerminatedOnError(message);
                    future.completeExceptionally(cause);
                }
            }
        }.execute();
        return future;
    }

    public CompletableFuture<InvestMoneyTransaction> submitInvestmentAsync(
            String operation, String unitsText, String amountText, String rawPin) {
        return executeInvestmentAsync(null, operation, unitsText, amountText, rawPin);
    }

    private CompletableFuture<InvestMoneyTransaction> executeInvestmentAsync(
            String explicitReference, String operation, String unitsText, String amountText, String rawPin) {
        CompletableFuture<InvestMoneyTransaction> future = new CompletableFuture<>();
        if (investmentService == null) {
            future.completeExceptionally(new IllegalStateException("InvestmentService is not enabled."));
            return future;
        }
        String rawOp = operation != null ? operation.trim().toUpperCase(Locale.ROOT) : "";
        String opLabel = "REDEEM".equals(rawOp) ? "Investment Redemption" : "Investment Purchase";

        runOnEdt(() -> {
            if (btnSubmitInvest != null) {
                btnSubmitInvest.setEnabled(false);
            }
            if (btnPreviewInvest != null) {
                btnPreviewInvest.setEnabled(false);
            }
            if (lblInvestFeedback != null) {
                lblInvestFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.INFO));
                lblInvestFeedback.setText("Processing " + opLabel + "...");
            }
            lblStatus.setText("Processing " + opLabel + "...");
        });

        new SwingWorker<InvestMoneyTransaction, Void>() {
            @Override
            protected InvestMoneyTransaction doInBackground() {
                lastPaymentExecutedOffEdt = !SwingUtilities.isEventDispatchThread();
                String op = requireValidInvestmentOperation(rawOp);
                SessionManager session = SessionManager.getInstance();
                session.requireAuthentication();
                User user = session.getCurrentUser();
                Wallet wallet = session.getCurrentWallet();
                if (wallet == null) {
                    wallet = session.refreshWallet(walletService);
                }
                BigDecimal units = parseOptionalDecimal(unitsText);
                BigDecimal amount = parseOptionalDecimal(amountText);
                String ref = (explicitReference != null && !explicitReference.trim().isEmpty())
                        ? explicitReference.trim()
                        : IdGenerator.nextReferenceNumber();
                String cleanPin = rawPin != null ? rawPin.trim() : "";

                if (!cleanPin.isEmpty()) {
                    if (authService != null && user != null) {
                        verifyPinOrThrow(user.getUserId(), cleanPin);
                        return "REDEEM".equals(op)
                                ? investmentService.redeemInvestment(ref, wallet.getWalletId(), units)
                                : investmentService.buyInvestment(ref, wallet.getWalletId(), units, amount);
                    }
                    return "REDEEM".equals(op)
                            ? investmentService.redeemInvestmentWithPin(ref, wallet.getWalletId(), units, cleanPin)
                            : investmentService.buyInvestmentWithPin(ref, wallet.getWalletId(), units, amount, cleanPin);
                }
                return "REDEEM".equals(op)
                        ? investmentService.redeemInvestment(ref, wallet.getWalletId(), units)
                        : investmentService.buyInvestment(ref, wallet.getWalletId(), units, amount);
            }

            @Override
            protected void done() {
                if (btnSubmitInvest != null) {
                    btnSubmitInvest.setEnabled(true);
                }
                if (btnPreviewInvest != null) {
                    btnPreviewInvest.setEnabled(true);
                }
                try {
                    InvestMoneyTransaction txn = get();
                    setInvestmentInputTexts("", "");
                    boolean isBuySelected = cmbInvestOperation == null || "BUY".equals(cmbInvestOperation.getSelectedItem());
                    resetInvestPreviewSummary(isBuySelected);
                    if (lblInvestFeedback != null) {
                        lblInvestFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.SUCCESS));
                        lblInvestFeedback.setText(opLabel + " completed: "
                                + (txn.getUnits() != null ? txn.getUnits().toPlainString() : "-")
                                + " units (" + MoneyUtil.format(txn.getAmount(), txn.getCurrency())
                                + ", Ref: " + txn.getReferenceNumber() + ")");
                    }
                    lblStatus.setText(opLabel + " completed (Ref: " + txn.getReferenceNumber() + ")");
                    refreshDashboardAsync().whenComplete((v, err) -> future.complete(txn));
                } catch (Exception ex) {
                    Throwable cause = unwrapCause(ex);
                    String message = extractErrorMessage(cause);
                    if (lblInvestFeedback != null) {
                        lblInvestFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
                        lblInvestFeedback.setText(opLabel + " failed: " + message);
                    }
                    lblStatus.setText(opLabel + " failed: " + message);
                    checkSessionTerminatedOnError(message);
                    future.completeExceptionally(cause);
                }
            }
        }.execute();
        return future;
    }

    private static String requireValidInvestmentOperation(String rawOp) {
        if ("BUY".equals(rawOp) || "REDEEM".equals(rawOp)) {
            return rawOp;
        }
        throw new IllegalArgumentException("Unsupported investment operation: " + rawOp);
    }

    private void handleInteractiveInvestPreview() {
        if (!ensureActiveSessionForInteraction() || investmentService == null) {
            return;
        }
        String op = (String) cmbInvestOperation.getSelectedItem();
        String unitsText = txtInvestUnits.getText() != null ? txtInvestUnits.getText().trim() : "";
        String amountText = txtInvestAmount.getText() != null ? txtInvestAmount.getText().trim() : "";
        previewInvestmentAsync(op, unitsText, amountText);
    }

    private void handleInteractiveInvestSubmit() {
        if (!ensureActiveSessionForInteraction() || investmentService == null) {
            return;
        }
        String op = (String) cmbInvestOperation.getSelectedItem();
        String unitsText = txtInvestUnits.getText() != null ? txtInvestUnits.getText().trim() : "";
        String amountText = txtInvestAmount.getText() != null ? txtInvestAmount.getText().trim() : "";
        String pin = new String(txtInvestPin.getPassword()).trim();

        // Validate operation and order inputs before prompting for PIN
        try {
            String validOp = requireValidInvestmentOperation(op != null ? op.trim().toUpperCase(Locale.ROOT) : "");
            BigDecimal parsedUnits = parseOptionalDecimal(unitsText);
            BigDecimal parsedAmount = parseOptionalDecimal(amountText);
            if ("REDEEM".equals(validOp)) {
                if (parsedUnits == null || parsedUnits.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new IllegalArgumentException("Please enter a positive number of units to redeem.");
                }
            } else {
                if ((parsedUnits == null || parsedUnits.compareTo(BigDecimal.ZERO) <= 0)
                        && (parsedAmount == null || parsedAmount.compareTo(BigDecimal.ZERO) <= 0)) {
                    throw new IllegalArgumentException("Please enter positive units or a positive "
                            + OtogConfig.getInstance().getDefaultCurrency() + " amount to invest.");
                }
            }
        } catch (RuntimeException ex) {
            String msg = extractErrorMessage(ex);
            lblInvestFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
            lblInvestFeedback.setText("Investment failed: " + msg);
            lblStatus.setText("Investment validation failed: " + msg);
            showDialogMessage(this, "Invalid Investment Input", msg, JOptionPane.ERROR_MESSAGE);
            txtInvestUnits.requestFocusInWindow();
            return;
        }

        if (authService != null && !cachedHasPin) {
            txtInvestPin.setText("");
            promptPinSetupIfMissing("A Transaction PIN is required before buying or redeeming simulated investments.");
            return;
        }
        if (authService != null && pin.isEmpty()) {
            OtogConfig config = OtogConfig.getInstance();
            String msg = "Please enter your " + config.getPinMinLength() + "-" + config.getPinMaxLength()
                    + " digit Transaction PIN to authorize investment order.";
            lblInvestFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
            lblInvestFeedback.setText("Investment failed: " + msg);
            showDialogMessage(this, "PIN Required", msg, JOptionPane.WARNING_MESSAGE);
            txtInvestPin.requestFocusInWindow();
            return;
        }
        txtInvestPin.setText("");

        if (btnSubmitInvest != null) {
            btnSubmitInvest.setEnabled(false);
        }
        if (btnPreviewInvest != null) {
            btnPreviewInvest.setEnabled(false);
        }

        previewInvestmentAsync(op, unitsText, amountText).whenComplete((preview, previewErr) -> runOnEdt(() -> {
            if (previewErr != null) {
                if (btnSubmitInvest != null) {
                    btnSubmitInvest.setEnabled(true);
                }
                if (btnPreviewInvest != null) {
                    btnPreviewInvest.setEnabled(true);
                }
                Throwable cause = unwrapCause(previewErr);
                showDialogMessage(this, "Investment Preview Failed", extractErrorMessage(cause),
                        JOptionPane.ERROR_MESSAGE);
                return;
            }
            JTextArea area = new JTextArea(preview.toConfirmationText());
            area.setEditable(false);
            area.setFont(UiTheme.font(UiTheme.FontRole.MONO));
            area.setMargin(new Insets(6, 8, 6, 8));
            int choice = showConfirmDialog(
                    this,
                    "Confirm Investment Order",
                    new JScrollPane(area),
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.QUESTION_MESSAGE
            );
            if (choice != JOptionPane.OK_OPTION) {
                if (btnSubmitInvest != null) {
                    btnSubmitInvest.setEnabled(true);
                }
                if (btnPreviewInvest != null) {
                    btnPreviewInvest.setEnabled(true);
                }
                lblInvestFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.MUTED));
                lblInvestFeedback.setText("Investment order cancelled.");
                return;
            }
            executeInvestmentAsync(
                    preview.getReferenceNumber(),
                    op,
                    preview.getUnits().toPlainString(),
                    "BUY".equals(preview.getOperation()) ? preview.getTotalAmount().toPlainString() : null,
                    pin
            ).whenComplete((txn, err) -> runOnEdt(() -> {
                if (err != null && !logoutHandled) {
                    Throwable cause = unwrapCause(err);
                    showDialogMessage(this, "Investment Failed", extractErrorMessage(cause),
                            JOptionPane.ERROR_MESSAGE);
                } else if (txn != null) {
                    showDialogMessage(this,
                            "Investment Complete",
                            "Order executed!\nProduct: " + txn.getProduct()
                                    + "\nUnits: " + txn.getUnits().toPlainString()
                                    + "\nAmount: " + MoneyUtil.format(txn.getAmount(), txn.getCurrency())
                                    + "\nReference: " + txn.getReferenceNumber(),
                            JOptionPane.INFORMATION_MESSAGE);
                }
            }));
        }));
    }

    private static BigDecimal parseOptionalDecimal(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(text.trim().replace(",", ""));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid numeric value: " + text.trim());
        }
    }

    private void verifyPinOrThrow(String userId, String rawPin) {
        try {
            if (!authService.verifyPin(userId, rawPin)) {
                SessionManager session = SessionManager.getInstance();
                if (session.isLockedOutByPin()) {
                    throw new WalletException("Session terminated after maximum failed PIN attempts.");
                }
                throw new WalletException("Invalid transaction PIN. Outgoing payment blocked.");
            }
        } catch (SecurityException ex) {
            throw new WalletException(ex.getMessage(), ex);
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

    private void setLoadingState(String message) {
        lblLoadingState.setText(message);
        activityCardLayout.show(activityCardPanel, DashboardState.LOADING.name());
        lblStatus.setText(message);
    }

    private void populatePaymentMethodsCombo(List<PaymentMethod> paymentMethods) {
        if (cmbPaymentMethod == null) {
            return;
        }
        String prevSelectedId = getSelectedPaymentMethodId();
        List<PaymentMethod> activeMethods = new ArrayList<>();
        if (paymentMethods != null) {
            for (PaymentMethod pm : paymentMethods) {
                if (pm.getStatus() == PaymentMethodStatus.ACTIVE) {
                    activeMethods.add(pm);
                }
            }
        }
        this.loadedPaymentMethods = Collections.unmodifiableList(activeMethods);
        cmbPaymentMethod.removeAllItems();
        cmbPaymentMethod.addItem("Use Selected Channel");
        int defaultIndex = 0;
        int prevIndex = -1;
        for (int i = 0; i < activeMethods.size(); i++) {
            PaymentMethod pm = activeMethods.get(i);
            String label = pm.getProviderName() + " (" + pm.getAccountNumber() + ")"
                    + (pm.isDefault() ? " [Default]" : "");
            cmbPaymentMethod.addItem(label);
            if (prevSelectedId != null && prevSelectedId.equals(pm.getPaymentMethodId())) {
                prevIndex = i + 1;
            } else if (pm.isDefault() && defaultIndex == 0) {
                defaultIndex = i + 1;
            }
        }
        cmbPaymentMethod.setSelectedIndex(prevIndex >= 0 ? prevIndex : defaultIndex);
        if (cmbCashChannel != null) {
            cmbCashChannel.setEnabled(cmbPaymentMethod.getSelectedIndex() <= 0);
        }
    }

    public String getSelectedPaymentMethodId() {
        if (cmbPaymentMethod == null) {
            return null;
        }
        int idx = cmbPaymentMethod.getSelectedIndex();
        if (idx <= 0 || idx - 1 >= loadedPaymentMethods.size()) {
            return null;
        }
        return loadedPaymentMethods.get(idx - 1).getPaymentMethodId();
    }

    private void populateTransactionsTable(String walletId, List<Transaction> transactions) {
        if (transactions == null || transactions.isEmpty()) {
            this.loadedTransactions = Collections.emptyList();
        } else {
            List<Transaction> sorted = new ArrayList<>(transactions);
            Collections.sort(sorted);
            this.loadedTransactions = Collections.unmodifiableList(sorted);
        }
        transactionsTableModel.setRowCount(0);
        for (Transaction txn : this.loadedTransactions) {
            String date = txn.getCreatedAt() != null ? txn.getCreatedAt().format(dateFormatter) : "-";
            String direction = resolveDirection(walletId, txn);
            String statusText = txn.getStatus() != null ? txn.getStatus().name() : "-";
            if (txn.getProviderStatus() != null && !txn.getProviderStatus().isEmpty()) {
                statusText += " [" + txn.getProviderStatus() + "]";
            }
            String provRef = (txn.getProviderReference() != null && !txn.getProviderReference().isEmpty())
                    ? txn.getProviderReference() : "-";
            transactionsTableModel.addRow(new Object[]{
                    date,
                    txn.getReferenceNumber(),
                    txn.getType() != null ? txn.getType().name() : "-",
                    direction,
                    MoneyUtil.format(txn.getAmount(), txn.getCurrency()),
                    MoneyUtil.format(txn.getFee(), txn.getCurrency()),
                    statusText,
                    provRef,
                    txn.getDescription() != null ? txn.getDescription() : ""
            });
        }
        updateTableActionButtonsState();
    }

    private void updateTableActionButtonsState() {
        Transaction selected = getSelectedTransactionForReceipt();
        if (btnCheckStatus != null) {
            boolean isCashTxn = selected != null
                    && (selected.getType() == TransactionType.CASH_IN
                    || selected.getType() == TransactionType.CASH_OUT
                    || selected.getType() == TransactionType.BILL_PAYMENT);
            btnCheckStatus.setEnabled(isCashTxn);
        }
        if (btnRepeatDraft != null) {
            boolean isInvestRepeat = investmentService != null
                    && selected != null
                    && (selected.getType() == TransactionType.INVESTMENT_BUY
                    || selected.getType() == TransactionType.INVESTMENT_REDEEM);
            boolean canRepeat = selected != null
                    && (selected.getType() == TransactionType.SEND_MONEY
                    || selected.getType() == TransactionType.CASH_IN
                    || selected.getType() == TransactionType.CASH_OUT
                    || isInvestRepeat
                    || selected.getType() == TransactionType.BILL_PAYMENT);
            btnRepeatDraft.setEnabled(canRepeat);
        }
    }

    private static Map<TransactionType, String> createDirectionMap() {
        EnumMap<TransactionType, String> map = new EnumMap<>(TransactionType.class);
        map.put(TransactionType.CASH_IN, "IN (+)");
        map.put(TransactionType.CASH_OUT, "OUT (-)");
        map.put(TransactionType.BILL_PAYMENT, "OUT (-)");
        map.put(TransactionType.LOAN_DISBURSEMENT, "IN (+)");
        map.put(TransactionType.LOAN_REPAYMENT, "OUT (-)");
        map.put(TransactionType.INVESTMENT_BUY, "OUT (-)");
        map.put(TransactionType.INVESTMENT_REDEEM, "IN (+)");
        return Collections.unmodifiableMap(map);
    }

    private String resolveDirection(String walletId, Transaction txn) {
        if (txn.getType() != null) {
            String fixedDirection = FIXED_DIRECTION_BY_TYPE.get(txn.getType());
            if (fixedDirection != null) {
                return fixedDirection;
            }
        }
        if (walletId != null && walletId.equals(txn.getTargetWalletId())
                && !walletId.equals(txn.getSourceWalletId())) {
            return "IN (+)";
        }
        if (walletId != null && walletId.equals(txn.getSourceWalletId())) {
            return "OUT (-)";
        }
        return "-";
    }

    /** Resolves the currently selected table row via view-to-model mapping, or {@code null} if none. */
    public Transaction getSelectedTransactionForReceipt() {
        if (tblTransactions == null) {
            return null;
        }
        int viewRow = tblTransactions.getSelectedRow();
        int row = viewRow >= 0 ? tblTransactions.convertRowIndexToModel(viewRow) : -1;
        if (row < 0 || row >= loadedTransactions.size()) {
            return null;
        }
        return loadedTransactions.get(row);
    }

    private void lookupSelectedTransactionStatus() {
        Transaction txn = getSelectedTransactionForReceipt();
        if (txn == null) {
            JOptionPane.showMessageDialog(this,
                    "Please select a transaction row to check its bank status.",
                    "No Transaction Selected",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (txn.getType() != TransactionType.CASH_IN
                && txn.getType() != TransactionType.CASH_OUT
                && txn.getType() != TransactionType.BILL_PAYMENT) {
            String msg = "Status lookup is only available for Cash In / Cash Out and Bill Payment transactions.";
            if (lblActivityFeedback != null) {
                lblActivityFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.WARNING));
                lblActivityFeedback.setText(msg);
            }
            lblStatus.setText(msg);
            return;
        }
        submitStatusLookupAsync(txn.getReferenceNumber());
    }

    private void showSelectedReceipt() {
        Transaction txn = getSelectedTransactionForReceipt();
        if (txn == null) {
            showDialogMessage(this,
                    "No Transaction Selected",
                    "Please select a transaction row to view its receipt.",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        Wallet wallet = SessionManager.getInstance().getCurrentWallet();
        String walletId = wallet != null ? wallet.getWalletId() : null;
        String refKey = txn.getReferenceNumber() != null ? txn.getReferenceNumber() : txn.getTransactionId();

        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                if (walletId != null && refKey != null) {
                    return walletService.getTransactionReceipt(walletId, refKey);
                }
                return txn.getReceipt();
            }

            @Override
            protected void done() {
                try {
                    String formattedReceipt = get();
                    JTextArea area = new JTextArea(formattedReceipt);
                    area.setEditable(false);
                    area.setFont(UiTheme.font(UiTheme.FontRole.MONO));
                    area.setMargin(new Insets(8, 10, 8, 10));
                    JScrollPane scroll = new JScrollPane(area);
                    scroll.setPreferredSize(OtogConfig.getInstance().getUiDimension(
                            "ui.receipt.width", "ui.receipt.height", 460, 360));
                    JOptionPane.showMessageDialog(Main.this, scroll,
                            "Transaction Receipt — " + txn.getReferenceNumber(),
                            JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception ex) {
                    Throwable cause = unwrapCause(ex);
                    showDialogMessage(Main.this, "Receipt Error", extractErrorMessage(cause),
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void handleApplyHistoryFilters() {
        if (!ensureActiveSessionForInteraction()) {
            return;
        }
        try {
            TransactionFilter.Builder builder = TransactionFilter.builder();
            String selectedType = cmbFilterType != null ? (String) cmbFilterType.getSelectedItem() : null;
            if (selectedType != null && !OverviewPanel.ALL_TYPES_OPTION.equals(selectedType)) {
                builder.type(TransactionType.valueOf(selectedType));
            }
            String selectedStatus = cmbFilterStatus != null ? (String) cmbFilterStatus.getSelectedItem() : null;
            if (selectedStatus != null && !OverviewPanel.ALL_STATUSES_OPTION.equals(selectedStatus)) {
                builder.status(TransactionStatus.valueOf(selectedStatus));
            }
            String fromRaw = txtFilterFromDate != null && txtFilterFromDate.getText() != null
                    ? txtFilterFromDate.getText().trim() : "";
            String toRaw = txtFilterToDate != null && txtFilterToDate.getText() != null
                    ? txtFilterToDate.getText().trim() : "";
            if (!fromRaw.isEmpty()) {
                builder.fromDate(LocalDate.parse(fromRaw));
            }
            if (!toRaw.isEmpty()) {
                builder.toDate(LocalDate.parse(toRaw));
            }
            applyHistoryFiltersAsync(builder.build());
        } catch (DateTimeParseException ex) {
            String msg = "Invalid date format. Please use YYYY-MM-DD (e.g., 2026-11-19).";
            lblStatus.setText("Filter validation failed: " + msg);
            showDialogMessage(this, "Invalid Date Filter", msg, JOptionPane.WARNING_MESSAGE);
        } catch (IllegalArgumentException ex) {
            String msg = extractErrorMessage(ex);
            lblStatus.setText("Filter validation failed: " + msg);
            showDialogMessage(this, "Invalid History Filter", msg, JOptionPane.WARNING_MESSAGE);
        }
    }

    public CompletableFuture<Void> applyHistoryFiltersAsync(TransactionFilter filter) {
        this.activeHistoryFilter = filter != null ? filter : TransactionFilter.empty();
        return refreshDashboardAsync();
    }

    public CompletableFuture<Void> clearHistoryFiltersAsync() {
        this.activeHistoryFilter = TransactionFilter.empty();
        runOnEdt(() -> {
            if (cmbFilterType != null) {
                cmbFilterType.setSelectedIndex(0);
            }
            if (cmbFilterStatus != null) {
                cmbFilterStatus.setSelectedIndex(0);
            }
            if (txtFilterFromDate != null) {
                txtFilterFromDate.setText("");
            }
            if (txtFilterToDate != null) {
                txtFilterToDate.setText("");
            }
        });
        return refreshDashboardAsync();
    }

    private void handleRepeatSelectedTransactionDraft() {
        if (!ensureActiveSessionForInteraction()) {
            return;
        }
        createRepeatDraftForSelectedAsync().whenComplete((draft, err) -> runOnEdt(() -> {
            if (err != null) {
                Throwable cause = unwrapCause(err);
                showDialogMessage(this, "Repeat Draft Failed", extractErrorMessage(cause),
                        JOptionPane.ERROR_MESSAGE);
            }
        }));
    }

    /**
     * Clones the currently selected transaction via Prototype ({@code createRepeatTransactionDraft})
     * with a fresh reference number and pre-fills the matching payment form without auto-submitting.
     */
    public CompletableFuture<Transaction> createRepeatDraftForSelectedAsync() {
        CompletableFuture<Transaction> future = new CompletableFuture<>();
        Transaction selected = getSelectedTransactionForReceipt();
        if (selected == null) {
            future.completeExceptionally(new IllegalStateException("Please select a transaction to repeat."));
            return future;
        }
        Wallet wallet = SessionManager.getInstance().getCurrentWallet();
        if (wallet == null) {
            future.completeExceptionally(new IllegalStateException("Active wallet is required."));
            return future;
        }
        String key = selected.getReferenceNumber() != null ? selected.getReferenceNumber() : selected.getTransactionId();

        new SwingWorker<Transaction, Void>() {
            @Override
            protected Transaction doInBackground() {
                SessionManager.getInstance().touchActivity();
                return walletService.createRepeatTransactionDraft(wallet.getWalletId(), key, null);
            }

            @Override
            protected void done() {
                try {
                    Transaction draft = get();
                    applyRepeatDraftToUi(draft);
                    future.complete(draft);
                } catch (Exception ex) {
                    future.completeExceptionally(unwrapCause(ex));
                }
            }
        }.execute();
        return future;
    }

    private void applyRepeatDraftToUi(Transaction draft) {
        if (draft == null) {
            return;
        }
        String amountStr = draft.getAmount() != null ? draft.getAmount().toPlainString() : "";
        String noteStr = draft.getDescription() != null ? draft.getDescription() : "";
        if (draft.getType() == TransactionType.SEND_MONEY || draft instanceof SendMoneyTransaction) {
            String recipientTarget = (draft instanceof SendMoneyTransaction smt && smt.getRecipientNumber() != null)
                    ? smt.getRecipientNumber()
                    : (draft.getTargetWalletId() != null ? draft.getTargetWalletId() : "");
            txtRecipient.setText(recipientTarget);
            txtSendAmount.setText(amountStr);
            txtSendNote.setText(noteStr);
            txtSendPin.setText("");
            lblSendFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.INFO));
            lblSendFeedback.setText("Loaded repeat draft (New Ref: " + draft.getReferenceNumber()
                    + "). Review details and enter PIN to send.");
            lblStatus.setText("Repeat Send Money draft prepared: " + draft.getReferenceNumber());
            navigateToTab("Send Money");
        } else if (draft instanceof CashInOutTransaction
                || draft.getType() == TransactionType.CASH_IN
                || draft.getType() == TransactionType.CASH_OUT) {
            String op = draft.getType() == TransactionType.CASH_OUT ? "CASH_OUT" : "CASH_IN";
            cmbCashOperation.setSelectedItem(op);
            if (draft instanceof CashInOutTransaction cio && cio.getChannel() != null) {
                cmbCashChannel.setSelectedItem(cio.getChannel());
            }
            txtCashAmount.setText(amountStr);
            txtCashNote.setText(noteStr);
            txtCashPin.setText("");
            lblCashFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.INFO));
            lblCashFeedback.setText("Loaded repeat draft (New Ref: " + draft.getReferenceNumber()
                    + "). Review details and submit when ready.");
            lblStatus.setText("Repeat " + op + " draft prepared: " + draft.getReferenceNumber());
            navigateToTab("Cash In / Out");
        } else if ((draft instanceof InvestMoneyTransaction
                || draft.getType() == TransactionType.INVESTMENT_BUY
                || draft.getType() == TransactionType.INVESTMENT_REDEEM)
                && investmentPanel != null) {
            String op = draft.getType() == TransactionType.INVESTMENT_REDEEM ? "REDEEM" : "BUY";
            cmbInvestOperation.setSelectedItem(op);
            String unitsStr = (draft instanceof InvestMoneyTransaction imt && imt.getUnits() != null)
                    ? imt.getUnits().toPlainString()
                    : "";
            setInvestmentInputTexts(unitsStr, "");
            txtInvestPin.setText("");
            lblInvestFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.INFO));
            lblInvestFeedback.setText("Loaded repeat draft (New Ref: " + draft.getReferenceNumber()
                    + "). Review units and click Preview or Confirm.");
            lblStatus.setText("Repeat investment " + op + " draft prepared: " + draft.getReferenceNumber());
            navigateToTab("Investments");
        } else if (draft instanceof BillPaymentTransaction || draft.getType() == TransactionType.BILL_PAYMENT) {
            BillPaymentTransaction bpt = (draft instanceof BillPaymentTransaction b) ? b : null;
            if (payBillsPanel != null) {
                if (bpt != null && bpt.getBillerName() != null) {
                    payBillsPanel.getBillerComboBox().setSelectedItem(bpt.getBillerName());
                }
                String refVal = bpt != null ? bpt.getBillerReference() : null;
                if (refVal != null) {
                    payBillsPanel.getBillReferenceField().setText(refVal);
                }
                payBillsPanel.getBillAmountField().setText(amountStr);
                payBillsPanel.getBillPinField().setText("");
                payBillsPanel.getBillFeedbackLabel().setForeground(UiTheme.color(UiTheme.ColorRole.INFO));
                payBillsPanel.getBillFeedbackLabel().setText("Loaded repeat draft (New Ref: " + draft.getReferenceNumber()
                        + "). Review details and enter PIN to pay.");
            }
            lblStatus.setText("Repeat Bill Payment draft prepared: " + draft.getReferenceNumber());
            navigateToTab("Pay Bills");
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
                        "Session cleared locally, though server logout reported: " + UiSupport.extractErrorMessage(ex),
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
        return btnNavInvest;
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
        return cmbInvestOperation;
    }

    public JTextField getInvestUnitsField() {
        return txtInvestUnits;
    }

    public JTextField getInvestAmountField() {
        return txtInvestAmount;
    }

    public JPasswordField getInvestPinField() {
        return txtInvestPin;
    }

    public JLabel getInvestPreviewSummaryLabel() {
        return lblInvestPreviewSummary;
    }

    public JButton getPreviewInvestButton() {
        return btnPreviewInvest;
    }

    public JButton getSubmitInvestButton() {
        return btnSubmitInvest;
    }

    public JLabel getInvestFeedbackLabel() {
        return lblInvestFeedback;
    }

    public AdminControlsPanel getAdminControlsPanel() {
        return adminControlsPanel;
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
        pnlRoot.setLayout(new GridLayoutManager(3, 1, new Insets(14, 16, 14, 16), 12, 12));

        pnlHeader = new JPanel();
        pnlHeader.setLayout(new GridLayoutManager(1, 3, new Insets(0, 0, 10, 0), 12, 6));
        pnlHeader.setBorder(UiTheme.bottomDividerBorder());
        pnlRoot.add(pnlHeader, new GridConstraints(0, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        pnlIdentity = new JPanel();
        pnlIdentity.setLayout(new GridLayoutManager(3, 1, new Insets(0, 0, 0, 0), 0, 2));
        pnlHeader.add(pnlIdentity, new GridConstraints(0, 0, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_VERTICAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        lblWelcome = new JLabel("Welcome");
        lblWelcome.setFont(UiTheme.font(UiTheme.FontRole.BANNER));
        pnlIdentity.add(lblWelcome, new GridConstraints(0, 0, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        lblWalletId = new JLabel("Wallet: --");
        lblWalletId.setFont(UiTheme.font(UiTheme.FontRole.LABEL));
        lblWalletId.setForeground(UiTheme.color(UiTheme.ColorRole.MUTED));
        pnlIdentity.add(lblWalletId, new GridConstraints(1, 0, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        lblPinStatus = new JLabel("Transaction PIN: Checking...");
        lblPinStatus.setFont(UiTheme.font(UiTheme.FontRole.SMALL));
        lblPinStatus.setForeground(UiTheme.color(UiTheme.ColorRole.MUTED));
        pnlIdentity.add(lblPinStatus, new GridConstraints(2, 0, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        pnlHeader.add(new Spacer(), new GridConstraints(0, 1, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_WANT_GROW, 1,
                null, null, null, 0, false));

        pnlBalanceAndActions = new JPanel();
        pnlBalanceAndActions.setLayout(new GridLayoutManager(1, 6, new Insets(0, 0, 0, 0), 8, 0));
        pnlHeader.add(pnlBalanceAndActions, new GridConstraints(0, 2, 1, 1,
                GridConstraints.ANCHOR_EAST, GridConstraints.FILL_VERTICAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        pnlBalanceBox = new JPanel();
        pnlBalanceBox.setLayout(new GridLayoutManager(2, 1, new Insets(0, 0, 0, 8), 0, 1));
        pnlBalanceAndActions.add(pnlBalanceBox, new GridConstraints(0, 0, 1, 1,
                GridConstraints.ANCHOR_EAST, GridConstraints.FILL_VERTICAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
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
        lblBalance.setFont(UiTheme.font(UiTheme.FontRole.BALANCE));
        lblBalance.setForeground(UiTheme.color(UiTheme.ColorRole.SUCCESS));
        pnlBalanceBox.add(lblBalance, new GridConstraints(1, 0, 1, 1,
                GridConstraints.ANCHOR_EAST, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        btnBack = new JButton("\u2190 Back");
        btnBack.setEnabled(false);
        UiTheme.styleSecondaryButton(btnBack);
        pnlBalanceAndActions.add(btnBack, new GridConstraints(0, 1, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        btnRefresh = new JButton("Refresh");
        UiTheme.styleSecondaryButton(btnRefresh);
        pnlBalanceAndActions.add(btnRefresh, new GridConstraints(0, 2, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        btnNotifications = new JButton("Notifications (0)");
        UiTheme.styleSecondaryButton(btnNotifications);
        pnlBalanceAndActions.add(btnNotifications, new GridConstraints(0, 3, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        btnPinSettings = new JButton("Transaction PIN");
        UiTheme.styleSecondaryButton(btnPinSettings);
        pnlBalanceAndActions.add(btnPinSettings, new GridConstraints(0, 4, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        btnLogout = new JButton("Logout");
        UiTheme.styleSecondaryButton(btnLogout);
        pnlBalanceAndActions.add(btnLogout, new GridConstraints(0, 5, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        tabs = new JTabbedPane();
        tabs.setFont(UiTheme.font(UiTheme.FontRole.LABEL_BOLD));
        pnlRoot.add(tabs, new GridConstraints(1, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW,
                null, new Dimension(860, 480), null, 0, false));

        pnlFooter = new JPanel();
        pnlFooter.setLayout(new GridLayoutManager(1, 1, new Insets(6, 2, 2, 2), 0, 0));
        pnlFooter.setBorder(UiTheme.topDividerBorder());
        pnlRoot.add(pnlFooter, new GridConstraints(2, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        lblStatus = new JLabel("Initializing dashboard...");
        lblStatus.setFont(UiTheme.font(UiTheme.FontRole.SMALL));
        lblStatus.setForeground(UiTheme.color(UiTheme.ColorRole.MUTED));
        pnlFooter.add(lblStatus, new GridConstraints(0, 0, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED,
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
