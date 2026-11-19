package com.otog.ui;

import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import com.intellij.uiDesigner.core.Spacer;
import com.otog.database.DatabaseManager;
import com.otog.exception.WalletException;
import com.otog.model.CashInOutTransaction;
import com.otog.model.PaymentMethod;
import com.otog.model.PaymentMethodStatus;
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
import com.otog.service.BankResponseStatus;
import com.otog.service.InvestmentService;
import com.otog.service.LoanService;
import com.otog.service.PaymentProviderFactory;
import com.otog.service.ProtectedWalletOperationsProxy;
import com.otog.service.SendMoneyService;
import com.otog.service.SessionManager;
import com.otog.service.StatementFileService;
import com.otog.service.WalletOperations;
import com.otog.service.WalletService;
import com.otog.util.IdGenerator;
import com.otog.util.MoneyUtil;
import com.otog.util.OtogConfig;
import com.otog.util.UiTheme;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

public class Main extends JFrame {

    public enum DashboardState {
        LOADING,
        EMPTY,
        READY,
        ERROR
    }

    private final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern(OtogConfig.getInstance().getUiDatePattern());
    private static final Map<TransactionType, String> FIXED_DIRECTION_BY_TYPE = createDirectionMap();

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
    private AdminControlsPanel adminControlsPanel;
    private final StatementFileService statementFileService = new StatementFileService();

    private JButton btnNavCashIn;
    private JButton btnNavCashOut;
    private JButton btnNavSendMoney;
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

    private JComboBox<String> cmbCashOperation;
    private JComboBox<String> cmbPaymentMethod;
    private JComboBox<String> cmbCashChannel;
    private JComboBox<String> cmbProviderMode;
    private JTextField txtCashAmount;
    private JTextField txtCashNote;
    private JPasswordField txtCashPin;
    private JButton btnSubmitCashOp;
    private JButton btnCashBackToOverview;
    private JLabel lblCashFeedback;
    private List<PaymentMethod> loadedPaymentMethods = Collections.emptyList();

    private JTextField txtRecipient;
    private JTextField txtSendAmount;
    private JTextField txtSendNote;
    private JPasswordField txtSendPin;
    private JButton btnSubmitSendMoney;
    private JButton btnSendBackToOverview;
    private JLabel lblSendFeedback;

    private Timer sessionMonitorTimer;
    private volatile DashboardState currentState = DashboardState.LOADING;
    private volatile boolean lastLoadExecutedOffEdt;
    private volatile boolean lastPaymentExecutedOffEdt;
    private volatile boolean lastAdminLoadExecutedOffEdt;
    private volatile boolean cachedHasPin;
    private CompletableFuture<Void> initialLoadFuture;
    private List<Transaction> loadedTransactions = Collections.emptyList();
    private volatile TransactionFilter activeHistoryFilter = TransactionFilter.empty();
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
        return new Main(guardedWalletOps, effectiveAuth, null, null, initializedDatabaseManager);
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

        overviewPanel = new OverviewPanel();
        cashInOutPanel = new CashInOutPanel(walletService.getCurrentProviderMode());
        sendMoneyPanel = new SendMoneyPanel();

        wireOverviewPanel();
        wireCashInOutPanel();
        wireSendMoneyPanel();

        registerTab("Overview", overviewPanel);
        registerTab("Cash In / Out", cashInOutPanel);
        registerTab("Send Money", sendMoneyPanel);

        // Hide unfinished modules unless an implementation is explicitly provided
        if (loanService != null) {
            registerTab("Loans", buildServiceReadyNotice("Loans module active"));
        }
        if (investmentService != null) {
            registerTab("Investments", buildServiceReadyNotice("Investments module active"));
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
            txtCashAmount.requestFocusInWindow();
        });

        btnNavCashOut.addActionListener(e -> {
            cmbCashOperation.setSelectedItem("CASH_OUT");
            navigateToTab("Cash In / Out");
            txtCashAmount.requestFocusInWindow();
        });

        btnNavSendMoney.addActionListener(e -> {
            navigateToTab("Send Money");
            txtRecipient.requestFocusInWindow();
        });

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
        btnCheckStatus.addActionListener(e -> lookupSelectedTransactionStatus());
        btnRepeatDraft.addActionListener(e -> handleRepeatSelectedTransactionDraft());
        btnViewReceipt.addActionListener(e -> showSelectedReceipt());
        btnExportReceipt.addActionListener(e -> handleInteractiveExportReceipt());
        btnApplyFilters.addActionListener(e -> handleApplyHistoryFilters());
        btnClearFilters.addActionListener(e -> clearHistoryFiltersAsync());
        txtFilterFromDate.addActionListener(e -> handleApplyHistoryFilters());
        txtFilterToDate.addActionListener(e -> handleApplyHistoryFilters());
    }

    private void wireCashInOutPanel() {
        cmbCashOperation = cashInOutPanel.getCashOperationComboBox();
        cmbPaymentMethod = cashInOutPanel.getPaymentMethodComboBox();
        cmbCashChannel = cashInOutPanel.getCashChannelComboBox();
        cmbProviderMode = cashInOutPanel.getProviderModeComboBox();
        txtCashAmount = cashInOutPanel.getCashAmountField();
        txtCashNote = cashInOutPanel.getCashNoteField();
        txtCashPin = cashInOutPanel.getCashPinField();
        btnSubmitCashOp = cashInOutPanel.getSubmitCashOpButton();
        btnCashBackToOverview = cashInOutPanel.getCashBackToOverviewButton();
        lblCashFeedback = cashInOutPanel.getCashFeedbackLabel();

        cmbPaymentMethod.addActionListener(e ->
                cmbCashChannel.setEnabled(cmbPaymentMethod.getSelectedIndex() <= 0));

        cmbProviderMode.addActionListener(e -> {
            String modeName = (String) cmbProviderMode.getSelectedItem();
            if (modeName != null) {
                walletService.setPaymentProviderFactory(
                        PaymentProviderFactory.forMode(BankResponseStatus.valueOf(modeName)));
            }
        });

        cmbCashOperation.addActionListener(e -> {
            boolean isCashOut = "CASH_OUT".equals(cmbCashOperation.getSelectedItem());
            txtCashPin.setEnabled(isCashOut);
            if (!isCashOut) {
                txtCashPin.setText("");
            }
        });

        btnCashBackToOverview.addActionListener(e -> navigateToTab("Overview"));
        btnSubmitCashOp.addActionListener(e -> handleInteractiveCashSubmit());
        txtCashAmount.addActionListener(e -> handleInteractiveCashSubmit());
        txtCashNote.addActionListener(e -> handleInteractiveCashSubmit());
        txtCashPin.addActionListener(e -> handleInteractiveCashSubmit());
    }

    private void wireSendMoneyPanel() {
        txtRecipient = sendMoneyPanel.getRecipientField();
        txtSendAmount = sendMoneyPanel.getSendAmountField();
        txtSendNote = sendMoneyPanel.getSendNoteField();
        txtSendPin = sendMoneyPanel.getSendPinField();
        btnSubmitSendMoney = sendMoneyPanel.getSubmitSendMoneyButton();
        btnSendBackToOverview = sendMoneyPanel.getSendBackToOverviewButton();
        lblSendFeedback = sendMoneyPanel.getSendFeedbackLabel();

        btnSendBackToOverview.addActionListener(e -> navigateToTab("Overview"));
        btnSubmitSendMoney.addActionListener(e -> handleInteractiveSendMoneySubmit());
        txtRecipient.addActionListener(e -> handleInteractiveSendMoneySubmit());
        txtSendAmount.addActionListener(e -> handleInteractiveSendMoneySubmit());
        txtSendNote.addActionListener(e -> handleInteractiveSendMoneySubmit());
        txtSendPin.addActionListener(e -> handleInteractiveSendMoneySubmit());
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
            lblPinStatus.setText("Transaction PIN: Not Set (required for Cash Out & Send Money)");
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

        runOnEdt(() -> {
            setLoadingState("Loading wallet balance and recent activity...");
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
                TransactionFilter currentFilter = activeHistoryFilter;
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
                return new DashboardSnapshot(user, wallet, transactions, paymentMethods, hasPin, unreadNotifications);
            }

            @Override
            protected void done() {
                if (!isLatestRefresh(generation)) {
                    // A newer refresh superseded this one: never let stale data overwrite the UI.
                    // The newest worker will complete this future when it lands.
                    return;
                }
                try {
                    DashboardSnapshot snapshot = get();
                    updateHeaderFromSession(snapshot.user, snapshot.wallet, snapshot.hasPin);
                    updateNotificationsBadge(snapshot.unreadNotifications);
                    populatePaymentMethodsCombo(snapshot.paymentMethods);
                    populateTransactionsTable(snapshot.wallet.getWalletId(), snapshot.transactions);
                    boolean filtered = activeHistoryFilter != null && !activeHistoryFilter.isEmpty();
                    if (snapshot.transactions.isEmpty()) {
                        currentState = DashboardState.EMPTY;
                        lblEmptyState.setText(filtered
                                ? "No transactions match the selected filters."
                                : "No recent transactions yet. Use Cash In or Send Money to get started.");
                        activityCardLayout.show(activityCardPanel, DashboardState.EMPTY.name());
                        lblStatus.setText(filtered
                                ? "Ready — no matching transactions for active filter"
                                : "Ready — no transactions yet");
                    } else {
                        currentState = DashboardState.READY;
                        activityCardLayout.show(activityCardPanel, DashboardState.READY.name());
                        lblStatus.setText(filtered
                                ? "Ready — showing " + snapshot.transactions.size() + " filtered transaction(s)"
                                : "Ready — showing " + snapshot.transactions.size() + " recent transaction(s)");
                    }
                } catch (Exception ex) {
                    Throwable cause = unwrapCause(ex);
                    String message = (cause.getMessage() != null && !cause.getMessage().trim().isEmpty())
                            ? cause.getMessage()
                            : cause.getClass().getSimpleName();
                    currentState = DashboardState.ERROR;
                    lblErrorState.setText("Unable to load wallet data: " + message);
                    activityCardLayout.show(activityCardPanel, DashboardState.ERROR.name());
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
        if (btnRetryLoad != null) {
            btnRetryLoad.setEnabled(enabled);
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
        CompletableFuture<Transaction> future = new CompletableFuture<>();
        String op = operationType != null ? operationType.trim().toUpperCase() : "CASH_IN";
        String opLabel = "CASH_OUT".equals(op) ? "Cash Out" : "Cash In";

        runOnEdt(() -> {
            btnSubmitCashOp.setEnabled(false);
            lblCashFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.INFO));
            lblCashFeedback.setText("Processing " + opLabel + "...");
            lblStatus.setText("Processing " + opLabel + "...");
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

                BigDecimal amount = MoneyUtil.validatePositiveAmount(MoneyUtil.parse(amountText));
                String ref = IdGenerator.nextReferenceNumber();
                String cleanChannel = (channel != null && !channel.trim().isEmpty())
                        ? channel.trim() : "OTOG Partner";
                String cleanNote = (note != null && !note.trim().isEmpty()) ? note.trim() : null;
                String pmId = (paymentMethodId != null && !paymentMethodId.trim().isEmpty()) ? paymentMethodId.trim() : null;

                if ("CASH_IN".equals(op)) {
                    String desc = pmId == null
                            ? (cleanNote != null ? cleanChannel + " - " + cleanNote : "Cash In via " + cleanChannel)
                            : cleanNote;
                    return walletService.cashIn(ref, wallet.getWalletId(), pmId, amount, desc);
                } else if ("CASH_OUT".equals(op)) {
                    String desc = pmId == null
                            ? (cleanNote != null ? cleanChannel + " - " + cleanNote : "Cash Out to " + cleanChannel)
                            : cleanNote;
                    String cleanPin = rawPin != null ? rawPin.trim() : "";
                    if (!cleanPin.isEmpty()) {
                        if (authService != null && user != null) {
                            verifyPinOrThrow(user.getUserId(), cleanPin);
                            return walletService.cashOut(ref, wallet.getWalletId(), pmId, amount, MoneyUtil.ZERO, desc);
                        }
                        return walletService.cashOutWithPin(
                                ref, wallet.getWalletId(), pmId, amount, MoneyUtil.ZERO, desc, cleanPin);
                    }
                    return walletService.cashOut(ref, wallet.getWalletId(), pmId, amount, MoneyUtil.ZERO, desc);
                }
                throw new IllegalArgumentException("Unsupported cash operation: " + operationType);
            }

            @Override
            protected void done() {
                btnSubmitCashOp.setEnabled(true);
                try {
                    Transaction txn = get();
                    txtCashAmount.setText("");
                    txtCashNote.setText("");
                    String provRef = txn.getProviderReference() != null ? " | Bank Ref: " + txn.getProviderReference() : "";
                    String provStatus = txn.getProviderStatus() != null ? " [" + txn.getProviderStatus() + "]" : "";
                    if (txn.getStatus() == TransactionStatus.COMPLETED) {
                        lblCashFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.SUCCESS));
                        lblCashFeedback.setText(opLabel + " successful: "
                                + MoneyUtil.format(txn.getAmount(), txn.getCurrency())
                                + " (Ref: " + txn.getReferenceNumber() + ")" + provRef + provStatus);
                        lblStatus.setText(opLabel + " completed" + provStatus);
                    } else if (txn.getStatus() == TransactionStatus.FAILED) {
                        lblCashFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
                        lblCashFeedback.setText(opLabel + " failed/rejected: "
                                + MoneyUtil.format(txn.getAmount(), txn.getCurrency())
                                + " (Ref: " + txn.getReferenceNumber() + ")" + provRef + provStatus);
                        lblStatus.setText(opLabel + " failed" + provStatus);
                    } else {
                        lblCashFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.WARNING));
                        lblCashFeedback.setText(opLabel + " pending settlement: "
                                + MoneyUtil.format(txn.getAmount(), txn.getCurrency())
                                + " (Ref: " + txn.getReferenceNumber() + ")" + provRef + provStatus);
                        lblStatus.setText(opLabel + " pending settlement" + provStatus);
                    }
                    refreshDashboardAsync().whenComplete((v, err) -> future.complete(txn));
                } catch (Exception ex) {
                    Throwable cause = unwrapCause(ex);
                    String message = (cause.getMessage() != null && !cause.getMessage().trim().isEmpty())
                            ? cause.getMessage()
                            : cause.getClass().getSimpleName();
                    lblCashFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
                    lblCashFeedback.setText("Failed: " + message);
                    lblStatus.setText(opLabel + " failed: " + message);
                    checkSessionTerminatedOnError(message);
                    future.completeExceptionally(cause);
                }
            }
        };
        worker.execute();
        return future;
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
        refreshDashboardAsync().whenComplete((v, err) -> runOnEdt(() -> {
            if (!logoutHandled && currentState == DashboardState.ERROR) {
                String msg = lblErrorState != null ? lblErrorState.getText() : "Unable to load wallet data.";
                showDialogMessage(this, "Dashboard Refresh Error", msg, JOptionPane.ERROR_MESSAGE);
            }
        }));
    }

    private void handleInteractiveCashSubmit() {
        if (!ensureActiveSessionForInteraction()) {
            return;
        }

        String op = (String) cmbCashOperation.getSelectedItem();
        String opLabel = "CASH_OUT".equals(op) ? "Cash Out" : "Cash In";
        String channel = (String) cmbCashChannel.getSelectedItem();
        String amountText = txtCashAmount.getText() != null ? txtCashAmount.getText().trim() : "";
        String note = txtCashNote.getText() != null ? txtCashNote.getText().trim() : "";
        String pin = new String(txtCashPin.getPassword()).trim();

        BigDecimal parsedAmount;
        try {
            if (amountText.isEmpty()) {
                throw new IllegalArgumentException("Please enter a transaction amount.");
            }
            parsedAmount = MoneyUtil.validatePositiveAmount(MoneyUtil.parse(amountText));
        } catch (RuntimeException ex) {
            String msg = extractErrorMessage(ex);
            lblCashFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
            lblCashFeedback.setText("Failed: " + msg);
            lblStatus.setText(opLabel + " validation failed: " + msg);
            showDialogMessage(this, "Invalid Amount", msg, JOptionPane.ERROR_MESSAGE);
            txtCashAmount.requestFocusInWindow();
            return;
        }

        OtogConfig config = OtogConfig.getInstance();
        if ("CASH_OUT".equals(op)) {
            if (authService != null && !cachedHasPin) {
                txtCashPin.setText("");
                promptPinSetupIfMissing("A Transaction PIN is required before making a Cash Out.");
                return;
            }
            if (pin.isEmpty()) {
                String msg = "Please enter your " + config.getPinMinLength() + "-" + config.getPinMaxLength()
                        + " digit Transaction PIN to authorize Cash Out.";
                lblCashFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
                lblCashFeedback.setText("Failed: " + msg);
                showDialogMessage(this, "PIN Required", msg, JOptionPane.WARNING_MESSAGE);
                txtCashPin.requestFocusInWindow();
                return;
            }
        }

        txtCashPin.setText("");
        StringBuilder confirmMsg = new StringBuilder();
        confirmMsg.append("Operation: ").append(opLabel).append("\n");
        confirmMsg.append("Channel: ").append(channel != null ? channel : "OTOG Partner").append("\n");
        confirmMsg.append("Amount: ").append(MoneyUtil.format(parsedAmount, config.getDefaultCurrency()));
        if (!note.isEmpty()) {
            confirmMsg.append("\nNote: ").append(note);
        }
        int choice = showConfirmDialog(
                this,
                "Confirm " + opLabel,
                confirmMsg.toString(),
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE
        );
        if (choice != JOptionPane.OK_OPTION) {
            lblCashFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.MUTED));
            lblCashFeedback.setText(opLabel + " cancelled.");
            return;
        }

        submitCashOperationAsync(op, channel, amountText, note, pin).whenComplete((txn, err) -> runOnEdt(() -> {
            if (err != null) {
                if (!logoutHandled) {
                    Throwable cause = unwrapCause(err);
                    showDialogMessage(this, opLabel + " Failed", extractErrorMessage(cause), JOptionPane.ERROR_MESSAGE);
                }
            } else if (txn != null) {
                showDialogMessage(
                        this,
                        opLabel + " Complete",
                        opLabel + " successful!\nAmount: "
                                + MoneyUtil.format(txn.getAmount(), txn.getCurrency())
                                + "\nReference: " + txn.getReferenceNumber(),
                        JOptionPane.INFORMATION_MESSAGE
                );
            }
        }));
    }

    private void handleInteractiveSendMoneySubmit() {
        if (!ensureActiveSessionForInteraction()) {
            return;
        }
        SessionManager session = SessionManager.getInstance();

        String recipient = txtRecipient.getText() != null ? txtRecipient.getText().trim() : "";
        String amountText = txtSendAmount.getText() != null ? txtSendAmount.getText().trim() : "";
        String note = txtSendNote.getText() != null ? txtSendNote.getText().trim() : "";
        String pin = new String(txtSendPin.getPassword()).trim();

        if (recipient.isEmpty()) {
            String msg = "Please enter a recipient (mobile number, email, name, or wallet ID).";
            lblSendFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
            lblSendFeedback.setText("Transfer failed: " + msg);
            showDialogMessage(this, "Validation Error", msg, JOptionPane.WARNING_MESSAGE);
            txtRecipient.requestFocusInWindow();
            return;
        }

        BigDecimal parsedAmount;
        try {
            if (amountText.isEmpty()) {
                throw new IllegalArgumentException("Please enter an amount to send.");
            }
            parsedAmount = MoneyUtil.validatePositiveAmount(MoneyUtil.parse(amountText));
        } catch (RuntimeException ex) {
            String msg = extractErrorMessage(ex);
            lblSendFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
            lblSendFeedback.setText("Transfer failed: " + msg);
            lblStatus.setText("Transfer validation failed: " + msg);
            showDialogMessage(this, "Invalid Amount", msg, JOptionPane.ERROR_MESSAGE);
            txtSendAmount.requestFocusInWindow();
            return;
        }

        if (authService != null && !cachedHasPin) {
            txtSendPin.setText("");
            promptPinSetupIfMissing("A Transaction PIN is required before sending money.");
            return;
        }

        OtogConfig config = OtogConfig.getInstance();
        if (pin.isEmpty()) {
            String msg = "Please enter your " + config.getPinMinLength() + "-" + config.getPinMaxLength()
                    + " digit Transaction PIN to authorize Send Money.";
            lblSendFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
            lblSendFeedback.setText("Transfer failed: " + msg);
            showDialogMessage(this, "PIN Required", msg, JOptionPane.WARNING_MESSAGE);
            txtSendPin.requestFocusInWindow();
            return;
        }

        txtSendPin.setText("");
        User currentUser = session.getCurrentUser();
        SendMoneyService sendMoneyService = walletService.createSendMoneyService(authService);
        if (sendMoneyService != null && currentUser != null) {
            btnSubmitSendMoney.setEnabled(false);
            lblSendFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.INFO));
            lblSendFeedback.setText("Preparing transfer confirmation...");
            lblStatus.setText("Validating recipient and balance...");

            new SwingWorker<SendMoneyService.SendMoneyPreview, Void>() {
                @Override
                protected SendMoneyService.SendMoneyPreview doInBackground() {
                    return sendMoneyService.preview(currentUser.getUserId(), recipient, parsedAmount, null, note);
                }

                @Override
                protected void done() {
                    btnSubmitSendMoney.setEnabled(true);
                    try {
                        SendMoneyService.SendMoneyPreview preview = get();
                        JTextArea previewArea = new JTextArea(preview.toConfirmationText());
                        previewArea.setEditable(false);
                        previewArea.setFont(UiTheme.font(UiTheme.FontRole.MONO));
                        previewArea.setMargin(new Insets(6, 8, 6, 8));
                        JScrollPane previewScroll = new JScrollPane(previewArea);
                        previewScroll.setPreferredSize(new Dimension(400, 220));
                        int choice = showConfirmDialog(
                                Main.this,
                                "Confirm Send Money",
                                previewScroll,
                                JOptionPane.OK_CANCEL_OPTION,
                                JOptionPane.QUESTION_MESSAGE
                        );
                        if (choice != JOptionPane.OK_OPTION) {
                            lblSendFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.MUTED));
                            lblSendFeedback.setText("Transfer cancelled.");
                            lblStatus.setText("Transfer cancelled");
                            return;
                        }
                        dispatchConfirmedSendMoney(preview.getReferenceNumber(), recipient, amountText, note, pin);
                    } catch (Exception ex) {
                        Throwable cause = unwrapCause(ex);
                        String message = extractErrorMessage(cause);
                        lblSendFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
                        lblSendFeedback.setText("Transfer failed: " + message);
                        lblStatus.setText("Transfer failed: " + message);
                        showDialogMessage(Main.this, "Transfer Validation Failed", message, JOptionPane.ERROR_MESSAGE);
                    }
                }
            }.execute();
        } else {
            StringBuilder confirmMsg = new StringBuilder();
            confirmMsg.append("Recipient: ").append(recipient).append("\n");
            confirmMsg.append("Amount: ").append(MoneyUtil.format(parsedAmount, config.getDefaultCurrency()));
            if (!note.isEmpty()) {
                confirmMsg.append("\nNote: ").append(note);
            }
            int choice = showConfirmDialog(
                    this,
                    "Confirm Send Money",
                    confirmMsg.toString(),
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.QUESTION_MESSAGE
            );
            if (choice != JOptionPane.OK_OPTION) {
                lblSendFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.MUTED));
                lblSendFeedback.setText("Transfer cancelled.");
                lblStatus.setText("Transfer cancelled");
                return;
            }
            dispatchConfirmedSendMoney(null, recipient, amountText, note, pin);
        }
    }

    private void dispatchConfirmedSendMoney(String explicitReference, String recipient,
                                            String amountText, String note, String pin) {
        executeSendMoneyAsync(explicitReference, recipient, amountText, note, pin)
                .whenComplete((txn, err) -> runOnEdt(() -> {
                    if (err != null) {
                        if (!logoutHandled) {
                            Throwable cause = unwrapCause(err);
                            showDialogMessage(this, "Transfer Failed", extractErrorMessage(cause),
                                    JOptionPane.ERROR_MESSAGE);
                        }
                    } else if (txn != null) {
                        showDialogMessage(
                                this,
                                "Transfer Complete",
                                "Send Money successful!\nAmount: "
                                        + MoneyUtil.format(txn.getAmount(), txn.getCurrency())
                                        + "\nReference: " + txn.getReferenceNumber(),
                                JOptionPane.INFORMATION_MESSAGE
                        );
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

    private void handleInteractiveExportStatement() {
        if (!ensureActiveSessionForInteraction()) {
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Export OTOG Wallet Statement");
        FileNameExtensionFilter txtFilter = new FileNameExtensionFilter("OTOG Statement (*.txt)", "txt");
        FileNameExtensionFilter csvFilter = new FileNameExtensionFilter("CSV Statement (*.csv)", "csv");
        chooser.addChoosableFileFilter(txtFilter);
        chooser.addChoosableFileFilter(csvFilter);
        chooser.setFileFilter(txtFilter);
        chooser.setSelectedFile(new File(OtogConfig.getInstance().getStatementDefaultFileName()));
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            Path selectedPath = chooser.getSelectedFile().toPath();
            if (chooser.getFileFilter() == csvFilter
                    && !selectedPath.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv")) {
                selectedPath = selectedPath.resolveSibling(selectedPath.getFileName() + ".csv");
            }
            exportStatementAsync(selectedPath);
        }
    }

    private void handleInteractiveLoadStatement() {
        if (!ensureActiveSessionForInteraction()) {
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Load Saved OTOG Wallet Statement");
        chooser.addChoosableFileFilter(new FileNameExtensionFilter("OTOG Statement Files (*.txt, *.csv)", "txt", "csv"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            importStatementAsync(chooser.getSelectedFile().toPath()).thenAccept(stmt -> {
                if (stmt != null) {
                    runOnEdt(() -> {
                        JTextArea area = new JTextArea(stmt.toDisplaySummary(), 18, 68);
                        area.setEditable(false);
                        area.setFont(UiTheme.font(UiTheme.FontRole.MONO));
                        JOptionPane.showMessageDialog(this, new JScrollPane(area),
                                "Loaded Wallet Statement \u2014 " + stmt.getMaskedWalletId(),
                                JOptionPane.INFORMATION_MESSAGE);
                    });
                }
            });
        }
    }

    private void handleInteractiveExportReceipt() {
        Transaction selected = getSelectedTransactionForReceipt();
        if (selected == null) {
            showDialogMessage(this,
                    "No Transaction Selected",
                    "Please select a transaction row to save its receipt file.",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Save Transaction Receipt");
        FileNameExtensionFilter txtFilter = new FileNameExtensionFilter("OTOG Receipt (*.txt)", "txt");
        FileNameExtensionFilter csvFilter = new FileNameExtensionFilter("CSV Receipt (*.csv)", "csv");
        chooser.addChoosableFileFilter(txtFilter);
        chooser.addChoosableFileFilter(csvFilter);
        chooser.setFileFilter(txtFilter);
        chooser.setSelectedFile(new File(
                OtogConfig.getInstance().getReceiptFilePrefix() + selected.getReferenceNumber() + ".txt"));
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            Path selectedPath = chooser.getSelectedFile().toPath();
            if (chooser.getFileFilter() == csvFilter
                    && !selectedPath.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv")) {
                selectedPath = selectedPath.resolveSibling(selectedPath.getFileName() + ".csv");
            }
            exportSelectedReceiptAsync(selectedPath);
        }
    }

    public CompletableFuture<Path> exportStatementAsync(Path targetFile) {
        CompletableFuture<Path> future = new CompletableFuture<>();
        User user = SessionManager.getInstance().getCurrentUser();
        Wallet wallet = SessionManager.getInstance().getCurrentWallet();
        List<Transaction> snapshotTxns = new ArrayList<>(loadedTransactions);

        new SwingWorker<Path, Void>() {
            @Override
            protected Path doInBackground() {
                Wallet latestWallet = (wallet != null && user != null)
                        ? walletService.getWalletByUserId(user.getUserId())
                        : wallet;
                return statementFileService.exportWalletStatement(user, latestWallet, snapshotTxns, targetFile);
            }

            @Override
            protected void done() {
                try {
                    Path saved = get();
                    lblStatus.setText("Statement exported to " + saved.getFileName());
                    if (lblActivityFeedback != null) {
                        lblActivityFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.SUCCESS));
                        lblActivityFeedback.setText("Exported statement: " + saved.getFileName());
                    }
                    future.complete(saved);
                } catch (Exception ex) {
                    Throwable cause = unwrapCause(ex);
                    String msg = extractErrorMessage(cause);
                    lblStatus.setText("Statement export failed: " + msg);
                    future.completeExceptionally(cause);
                }
            }
        }.execute();
        return future;
    }

    public CompletableFuture<StatementFileService.LoadedStatement> importStatementAsync(Path sourceFile) {
        CompletableFuture<StatementFileService.LoadedStatement> future = new CompletableFuture<>();
        new SwingWorker<StatementFileService.LoadedStatement, Void>() {
            @Override
            protected StatementFileService.LoadedStatement doInBackground() {
                return statementFileService.importWalletStatement(sourceFile);
            }

            @Override
            protected void done() {
                try {
                    StatementFileService.LoadedStatement loaded = get();
                    lblStatus.setText("Loaded statement (" + loaded.getEntries().size() + " transactions).");
                    if (lblActivityFeedback != null) {
                        lblActivityFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.SUCCESS));
                        lblActivityFeedback.setText("Loaded statement for wallet " + loaded.getMaskedWalletId());
                    }
                    future.complete(loaded);
                } catch (Exception ex) {
                    Throwable cause = unwrapCause(ex);
                    String msg = extractErrorMessage(cause);
                    lblStatus.setText("Statement load failed: " + msg);
                    future.completeExceptionally(cause);
                }
            }
        }.execute();
        return future;
    }

    public CompletableFuture<Path> exportSelectedReceiptAsync(Path targetFile) {
        CompletableFuture<Path> future = new CompletableFuture<>();
        Transaction selected = getSelectedTransactionForReceipt();
        new SwingWorker<Path, Void>() {
            @Override
            protected Path doInBackground() {
                return statementFileService.exportReceipt(selected, targetFile);
            }

            @Override
            protected void done() {
                try {
                    Path saved = get();
                    lblStatus.setText("Receipt saved to " + saved.getFileName());
                    future.complete(saved);
                } catch (Exception ex) {
                    Throwable cause = unwrapCause(ex);
                    lblStatus.setText("Receipt save failed: " + extractErrorMessage(cause));
                    future.completeExceptionally(cause);
                }
            }
        }.execute();
        return future;
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
                    && (selected.getType() == TransactionType.CASH_IN || selected.getType() == TransactionType.CASH_OUT);
            btnCheckStatus.setEnabled(isCashTxn);
        }
        if (btnRepeatDraft != null) {
            boolean canRepeat = selected != null
                    && (selected.getType() == TransactionType.SEND_MONEY
                    || selected.getType() == TransactionType.CASH_IN
                    || selected.getType() == TransactionType.CASH_OUT);
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
        if (txn.getType() != TransactionType.CASH_IN && txn.getType() != TransactionType.CASH_OUT) {
            String msg = "Status lookup is only available for Cash In / Cash Out transactions.";
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
                        "Session cleared locally, though server logout reported: " + extractErrorMessage(ex),
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

    private static String extractErrorMessage(Throwable ex) {
        if (ex == null) {
            return "An unexpected error occurred.";
        }
        String msg = ex.getMessage();
        return (msg != null && !msg.trim().isEmpty()) ? msg : ex.getClass().getSimpleName();
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

    private static void runOnEdt(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeLater(action);
        }
    }

    private static Throwable unwrapCause(Throwable ex) {
        Throwable current = ex;
        while ((current instanceof ExecutionException || current instanceof CompletionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
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
        return lblLoadingState;
    }

    public JLabel getEmptyStateLabel() {
        return lblEmptyState;
    }

    public JLabel getErrorStateLabel() {
        return lblErrorState;
    }

    public JLabel getCashFeedbackLabel() {
        return lblCashFeedback;
    }

    public JLabel getActivityFeedbackLabel() {
        return lblActivityFeedback;
    }

    public JLabel getSendFeedbackLabel() {
        return lblSendFeedback;
    }

    public JTable getRecentTransactionsTable() {
        return tblTransactions;
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
        return btnNavCashIn;
    }

    public JButton getCashOutQuickNavButton() {
        return btnNavCashOut;
    }

    public JButton getSendMoneyQuickNavButton() {
        return btnNavSendMoney;
    }

    public JButton getPinSettingsButton() {
        return btnPinSettings;
    }

    public JButton getRetryLoadButton() {
        return btnRetryLoad;
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
        return activeHistoryFilter;
    }

    public JComboBox<String> getFilterTypeComboBox() {
        return cmbFilterType;
    }

    public JComboBox<String> getFilterStatusComboBox() {
        return cmbFilterStatus;
    }

    public JTextField getFilterFromDateField() {
        return txtFilterFromDate;
    }

    public JTextField getFilterToDateField() {
        return txtFilterToDate;
    }

    public JButton getApplyFiltersButton() {
        return btnApplyFilters;
    }

    public JButton getClearFiltersButton() {
        return btnClearFilters;
    }

    public JButton getRepeatDraftButton() {
        return btnRepeatDraft;
    }

    public JComboBox<String> getCashOperationComboBox() {
        return cmbCashOperation;
    }

    public JComboBox<String> getPaymentMethodComboBox() {
        return cmbPaymentMethod;
    }

    public JComboBox<String> getProviderModeComboBox() {
        return cmbProviderMode;
    }

    public JButton getCheckStatusButton() {
        return btnCheckStatus;
    }

    public JPasswordField getCashPinField() {
        return txtCashPin;
    }

    public JPasswordField getSendPinField() {
        return txtSendPin;
    }

    public JButton getSubmitCashButton() {
        return btnSubmitCashOp;
    }

    public JButton getCashBackToOverviewButton() {
        return btnCashBackToOverview;
    }

    public JButton getSubmitSendMoneyButton() {
        return btnSubmitSendMoney;
    }

    public JButton getSendBackToOverviewButton() {
        return btnSendBackToOverview;
    }

    public JTextField getCashAmountField() {
        return txtCashAmount;
    }

    public JTextField getRecipientField() {
        return txtRecipient;
    }

    public JTextField getSendAmountField() {
        return txtSendAmount;
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

    public AdminControlsPanel getAdminControlsPanel() {
        return adminControlsPanel;
    }

    public JButton getExportStatementButton() {
        return btnExportStatement;
    }

    public JButton getLoadStatementButton() {
        return btnLoadStatement;
    }

    public WalletOperations getWalletService() {
        return walletService;
    }

    public JButton getExportReceiptButton() {
        return btnExportReceipt;
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
        pnlBalanceAndActions.add(btnBack, new GridConstraints(0, 1, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        btnRefresh = new JButton("Refresh");
        pnlBalanceAndActions.add(btnRefresh, new GridConstraints(0, 2, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        btnNotifications = new JButton("Notifications (0)");
        pnlBalanceAndActions.add(btnNotifications, new GridConstraints(0, 3, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        btnPinSettings = new JButton("Transaction PIN");
        pnlBalanceAndActions.add(btnPinSettings, new GridConstraints(0, 4, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        btnLogout = new JButton("Logout");
        pnlBalanceAndActions.add(btnLogout, new GridConstraints(0, 5, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        tabs = new JTabbedPane();
        pnlRoot.add(tabs, new GridConstraints(1, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW,
                null, new Dimension(860, 480), null, 0, false));

        pnlFooter = new JPanel();
        pnlFooter.setLayout(new GridLayoutManager(1, 1, new Insets(4, 2, 0, 2), 0, 0));
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

        private DashboardSnapshot(User user, Wallet wallet, List<Transaction> transactions,
                                  List<PaymentMethod> paymentMethods, boolean hasPin,
                                  int unreadNotifications) {
            this.user = user;
            this.wallet = wallet;
            this.transactions = transactions;
            this.paymentMethods = paymentMethods;
            this.hasPin = hasPin;
            this.unreadNotifications = unreadNotifications;
        }
    }
}
