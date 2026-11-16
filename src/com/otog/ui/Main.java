package com.otog.ui;

import com.otog.database.DatabaseManager;
import com.otog.exception.WalletException;
import com.otog.model.AdminAction;
import com.otog.model.PaymentMethod;
import com.otog.model.PaymentMethodStatus;
import com.otog.model.Transaction;
import com.otog.model.TransactionStatus;
import com.otog.model.TransactionType;
import com.otog.model.User;
import com.otog.model.UserRole;
import com.otog.model.Wallet;
import com.otog.service.AuthService;
import com.otog.service.AuthServiceImpl;
import com.otog.service.BankResponseStatus;
import com.otog.service.InvestmentService;
import com.otog.service.LoanService;
import com.otog.service.SendMoneyService;
import com.otog.service.PaymentProviderFactory;
import com.otog.service.SessionManager;
import com.otog.service.WalletOperations;
import com.otog.service.WalletService;
import com.otog.util.IdGenerator;
import com.otog.util.MoneyUtil;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

public class Main extends JFrame {

    public enum DashboardState {
        LOADING,
        EMPTY,
        READY,
        ERROR
    }

    private static final int RECENT_TRANSACTION_LIMIT = 50;
    private static final int SESSION_POLL_INTERVAL_MS = 15_000;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
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

    private JLabel lblWelcome;
    private JLabel lblWalletId;
    private JLabel lblPinStatus;
    private JLabel lblBalance;
    private JLabel lblStatus;
    private JButton btnBack;
    private JButton btnRefresh;
    private JButton btnPinSettings;
    private JButton btnLogout;

    private JTabbedPane tabs;

    private JButton btnNavCashIn;
    private JButton btnNavCashOut;
    private JButton btnNavSendMoney;
    private JButton btnNavPinSettings;

    private CardLayout activityCardLayout;
    private JPanel activityCardPanel;
    private JLabel lblLoadingState;
    private JLabel lblEmptyState;
    private JLabel lblErrorState;
    private JButton btnRetryLoad;
    private DefaultTableModel transactionsTableModel;
    private JTable tblTransactions;
    private JButton btnViewReceipt;
    private JButton btnCheckStatus;
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
        return new Main(new WalletService(initializedDatabaseManager, effectiveAuth),
                effectiveAuth, null, null, initializedDatabaseManager);
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

        initUI();
        startSessionMonitor();
        this.initialLoadFuture = refreshDashboardAsync();
    }

    private void initUI() {
        setTitle("OTOG E-Wallet — Dashboard");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(940, 640);
        setMinimumSize(new Dimension(840, 560));
        setLocationRelativeTo(null);

        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));

        root.add(buildHeaderPanel(), BorderLayout.NORTH);
        root.add(buildTabsPanel(), BorderLayout.CENTER);
        root.add(buildFooterPanel(), BorderLayout.SOUTH);

        setContentPane(root);
        applyInitialSessionSnapshot();
    }

    private JPanel buildHeaderPanel() {
        JPanel header = new JPanel(new BorderLayout(12, 6));
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(215, 220, 226)),
                BorderFactory.createEmptyBorder(0, 0, 10, 0)
        ));

        JPanel identityPanel = new JPanel(new GridLayout(3, 1, 0, 2));
        lblWelcome = new JLabel("Welcome");
        lblWelcome.setFont(new Font("SansSerif", Font.BOLD, 16));
        lblWalletId = new JLabel("Wallet: --");
        lblWalletId.setFont(new Font("SansSerif", Font.PLAIN, 12));
        lblWalletId.setForeground(new Color(90, 98, 110));
        lblPinStatus = new JLabel("Transaction PIN: Checking...");
        lblPinStatus.setFont(new Font("SansSerif", Font.PLAIN, 11));
        lblPinStatus.setForeground(new Color(90, 98, 110));
        identityPanel.add(lblWelcome);
        identityPanel.add(lblWalletId);
        identityPanel.add(lblPinStatus);

        JPanel balanceAndActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JPanel balanceBox = new JPanel(new GridLayout(2, 1, 0, 1));
        JLabel balanceCaption = new JLabel("Available Balance", SwingConstants.RIGHT);
        balanceCaption.setFont(new Font("SansSerif", Font.PLAIN, 11));
        balanceCaption.setForeground(new Color(90, 98, 110));
        lblBalance = new JLabel("PHP 0.00", SwingConstants.RIGHT);
        lblBalance.setFont(new Font("SansSerif", Font.BOLD, 18));
        lblBalance.setForeground(new Color(17, 102, 68));
        balanceBox.add(balanceCaption);
        balanceBox.add(lblBalance);

        btnBack = new JButton("\u2190 Back");
        btnBack.setToolTipText("Return to the previously viewed tab");
        btnBack.setEnabled(false);
        btnBack.addActionListener(e -> navigateBack());

        btnRefresh = new JButton("Refresh");
        btnRefresh.addActionListener(e -> handleInteractiveRefresh());

        btnPinSettings = new JButton("Transaction PIN");
        btnPinSettings.setEnabled(authService != null);
        btnPinSettings.setToolTipText(authService != null
                ? "Set or change your 4-6 digit Transaction PIN"
                : "PIN management requires AuthService");
        btnPinSettings.addActionListener(e -> openPinSettingsDialog());

        btnLogout = new JButton("Logout");
        btnLogout.addActionListener(e -> confirmAndLogout());

        balanceAndActions.add(balanceBox);
        balanceAndActions.add(Box.createHorizontalStrut(8));
        balanceAndActions.add(btnBack);
        balanceAndActions.add(btnRefresh);
        balanceAndActions.add(btnPinSettings);
        balanceAndActions.add(btnLogout);

        header.add(identityPanel, BorderLayout.WEST);
        header.add(balanceAndActions, BorderLayout.EAST);
        return header;
    }

    private JTabbedPane buildTabsPanel() {
        tabs = new JTabbedPane();
        tabIndexByTitle.clear();
        registerTab("Overview", buildOverviewPanel());
        registerTab("Cash In / Out", buildCashInOutPanel());
        registerTab("Send Money", buildSendMoneyPanel());

        // Hide unfinished modules unless an implementation is explicitly provided
        if (loanService != null) {
            registerTab("Loans", buildServiceReadyNotice("Loans module active"));
        }
        if (investmentService != null) {
            registerTab("Investments", buildServiceReadyNotice("Investments module active"));
        }

        // CAP-09: Admin Controls tab — only visible to administrators
        User sessionUser = SessionManager.getInstance().getCurrentUser();
        if (sessionUser != null && sessionUser.isAdmin() && walletService instanceof WalletService) {
            registerTab("Admin Controls", buildAdminControlsPanel((WalletService) walletService));
        }

        lastSelectedTabTitle = tabs.getTabCount() > 0 ? tabs.getTitleAt(tabs.getSelectedIndex()) : null;
        // Record every tab switch (manual click or programmatic) on the LIFO back-stack.
        tabs.addChangeListener(e -> onTabSelectionChanged());
        return tabs;
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

    private JPanel buildOverviewPanel() {
        JPanel overview = new JPanel(new BorderLayout(12, 12));
        overview.setBorder(BorderFactory.createEmptyBorder(10, 4, 4, 4));

        JPanel quickActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 8));
        quickActions.setBorder(BorderFactory.createTitledBorder("Quick Actions"));

        btnNavCashIn = new JButton("Cash In");
        btnNavCashIn.addActionListener(e -> {
            cmbCashOperation.setSelectedItem("CASH_IN");
            navigateToTab("Cash In / Out");
            txtCashAmount.requestFocusInWindow();
        });

        btnNavCashOut = new JButton("Cash Out");
        btnNavCashOut.addActionListener(e -> {
            cmbCashOperation.setSelectedItem("CASH_OUT");
            navigateToTab("Cash In / Out");
            txtCashAmount.requestFocusInWindow();
        });

        btnNavSendMoney = new JButton("Send Money");
        btnNavSendMoney.addActionListener(e -> {
            navigateToTab("Send Money");
            txtRecipient.requestFocusInWindow();
        });

        btnNavPinSettings = new JButton("Set / Change PIN");
        btnNavPinSettings.setEnabled(authService != null);
        btnNavPinSettings.addActionListener(e -> openPinSettingsDialog());

        quickActions.add(btnNavCashIn);
        quickActions.add(btnNavCashOut);
        quickActions.add(btnNavSendMoney);
        quickActions.add(btnNavPinSettings);

        overview.add(quickActions, BorderLayout.NORTH);
        overview.add(buildRecentActivityPanel(), BorderLayout.CENTER);
        return overview;
    }

    private JPanel buildRecentActivityPanel() {
        JPanel container = new JPanel(new BorderLayout(8, 8));
        container.setBorder(BorderFactory.createTitledBorder("Recent Activity"));

        activityCardLayout = new CardLayout();
        activityCardPanel = new JPanel(activityCardLayout);

        JPanel loadingCard = new JPanel(new GridBagLayout());
        lblLoadingState = new JLabel("Loading wallet balance and recent activity...");
        lblLoadingState.setFont(new Font("SansSerif", Font.ITALIC, 13));
        loadingCard.add(lblLoadingState);

        JPanel emptyCard = new JPanel(new GridBagLayout());
        lblEmptyState = new JLabel("No recent transactions yet. Use Cash In or Send Money to get started.");
        lblEmptyState.setFont(new Font("SansSerif", Font.PLAIN, 13));
        lblEmptyState.setForeground(new Color(95, 103, 114));
        emptyCard.add(lblEmptyState);

        JPanel errorCard = new JPanel(new GridBagLayout());
        JPanel errorInner = new JPanel(new BorderLayout(8, 10));
        lblErrorState = new JLabel("Unable to load wallet activity.", SwingConstants.CENTER);
        lblErrorState.setFont(new Font("SansSerif", Font.PLAIN, 13));
        lblErrorState.setForeground(new Color(176, 38, 38));
        btnRetryLoad = new JButton("Retry");
        btnRetryLoad.addActionListener(e -> handleInteractiveRefresh());
        JPanel retryWrapper = new JPanel(new FlowLayout(FlowLayout.CENTER));
        retryWrapper.add(btnRetryLoad);
        errorInner.add(lblErrorState, BorderLayout.CENTER);
        errorInner.add(retryWrapper, BorderLayout.SOUTH);
        errorCard.add(errorInner);

        String[] columns = {"Date", "Ref #", "Type", "Direction", "Amount", "Fee", "Status", "Provider Ref", "Description"};
        transactionsTableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        tblTransactions = new JTable(transactionsTableModel);
        tblTransactions.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        tblTransactions.setRowHeight(24);
        tblTransactions.getTableHeader().setReorderingAllowed(false);
        tblTransactions.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && tblTransactions.getSelectedRow() >= 0) {
                    showSelectedReceipt();
                }
            }
        });

        JPanel readyCard = new JPanel(new BorderLayout(6, 6));
        readyCard.add(new JScrollPane(tblTransactions), BorderLayout.CENTER);

        JPanel tableActions = new JPanel(new BorderLayout(8, 0));
        lblActivityFeedback = new JLabel(" ");
        lblActivityFeedback.setFont(new Font("SansSerif", Font.PLAIN, 12));
        JPanel tableActionButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        btnCheckStatus = new JButton("Lookup Status");
        btnCheckStatus.setEnabled(false);
        btnCheckStatus.addActionListener(e -> lookupSelectedTransactionStatus());
        btnViewReceipt = new JButton("View Receipt");
        btnViewReceipt.addActionListener(e -> showSelectedReceipt());
        tableActionButtons.add(btnCheckStatus);
        tableActionButtons.add(btnViewReceipt);
        tableActions.add(lblActivityFeedback, BorderLayout.WEST);
        tableActions.add(tableActionButtons, BorderLayout.EAST);
        tblTransactions.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateTableActionButtonsState();
            }
        });
        readyCard.add(tableActions, BorderLayout.SOUTH);

        activityCardPanel.add(loadingCard, DashboardState.LOADING.name());
        activityCardPanel.add(emptyCard, DashboardState.EMPTY.name());
        activityCardPanel.add(errorCard, DashboardState.ERROR.name());
        activityCardPanel.add(readyCard, DashboardState.READY.name());

        container.add(activityCardPanel, BorderLayout.CENTER);
        return container;
    }

    private JPanel buildCashInOutPanel() {
        JPanel wrapper = new JPanel(new BorderLayout(10, 10));
        wrapper.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;

        cmbCashOperation = new JComboBox<>(new String[]{"CASH_IN", "CASH_OUT"});
        cmbPaymentMethod = new JComboBox<>(new String[]{"Use Selected Channel"});
        cmbCashChannel = new JComboBox<>(new String[]{"GCash", "Maya", "BDO", "BPI", "7-Eleven OTC"});
        cmbProviderMode = new JComboBox<>(new String[]{"SUCCESS", "REJECTED", "FAILED", "TIMEOUT", "PENDING", "UNKNOWN"});
        BankResponseStatus initialMode = walletService.getCurrentProviderMode();
        if (initialMode != null) {
            cmbProviderMode.setSelectedItem(initialMode.name());
        }
        txtCashAmount = new JTextField(20);
        txtCashNote = new JTextField(20);
        txtCashPin = new JPasswordField(20);
        txtCashPin.setEnabled(false);
        txtCashPin.setToolTipText("Required for Cash Out transactions");
        btnSubmitCashOp = new JButton("Submit Cash Transaction");
        btnCashBackToOverview = new JButton("\u2190 Back to Overview");
        lblCashFeedback = new JLabel(" ");
        lblCashFeedback.setFont(new Font("SansSerif", Font.PLAIN, 12));

        addFormRow(form, gbc, 0, "Operation:", cmbCashOperation);
        addFormRow(form, gbc, 1, "Linked Bank Account:", cmbPaymentMethod);
        addFormRow(form, gbc, 2, "Channel / Provider:", cmbCashChannel);
        addFormRow(form, gbc, 3, "Mock Bank Mode:", cmbProviderMode);
        addFormRow(form, gbc, 4, "Amount (PHP):", txtCashAmount);
        addFormRow(form, gbc, 5, "Note (Optional):", txtCashNote);
        addFormRow(form, gbc, 6, "Transaction PIN (Cash Out):", txtCashPin);

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

        JPanel cashActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        cashActions.add(btnSubmitCashOp);
        cashActions.add(btnCashBackToOverview);

        gbc.gridx = 1;
        gbc.gridy = 7;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.NONE;
        form.add(cashActions, gbc);

        gbc.gridx = 0;
        gbc.gridy = 8;
        gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        form.add(lblCashFeedback, gbc);

        btnCashBackToOverview.addActionListener(e -> navigateToTab("Overview"));
        btnSubmitCashOp.addActionListener(e -> handleInteractiveCashSubmit());
        txtCashAmount.addActionListener(e -> handleInteractiveCashSubmit());
        txtCashNote.addActionListener(e -> handleInteractiveCashSubmit());
        txtCashPin.addActionListener(e -> handleInteractiveCashSubmit());
        btnSubmitCashOp.addActionListener(e -> {
            String op = (String) cmbCashOperation.getSelectedItem();
            String pin = new String(txtCashPin.getPassword());
            txtCashPin.setText("");
            submitCashOperationAsync(
                    op,
                    (String) cmbCashChannel.getSelectedItem(),
                    getSelectedPaymentMethodId(),
                    txtCashAmount.getText(),
                    txtCashNote.getText(),
                    pin
            );
        });

        wrapper.add(form, BorderLayout.NORTH);
        return wrapper;
    }

    private JPanel buildSendMoneyPanel() {
        JPanel wrapper = new JPanel(new BorderLayout(10, 10));
        wrapper.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;

        txtRecipient = new JTextField(22);
        txtSendAmount = new JTextField(22);
        txtSendNote = new JTextField(22);
        txtSendPin = new JPasswordField(22);
        txtSendPin.setToolTipText("Enter your 4-6 digit Transaction PIN to authorize outgoing transfer");
        btnSubmitSendMoney = new JButton("Send Money");
        btnSendBackToOverview = new JButton("\u2190 Back to Overview");
        lblSendFeedback = new JLabel(" ");
        lblSendFeedback.setFont(new Font("SansSerif", Font.PLAIN, 12));

        addFormRow(form, gbc, 0, "Recipient (Mobile / Email / Name / Wallet ID):", txtRecipient);
        addFormRow(form, gbc, 1, "Amount (PHP):", txtSendAmount);
        addFormRow(form, gbc, 2, "Note (Optional):", txtSendNote);
        addFormRow(form, gbc, 3, "Transaction PIN:", txtSendPin);

        JPanel sendActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        sendActions.add(btnSubmitSendMoney);
        sendActions.add(btnSendBackToOverview);

        gbc.gridx = 1;
        gbc.gridy = 4;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.NONE;
        form.add(sendActions, gbc);

        gbc.gridx = 0;
        gbc.gridy = 5;
        gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        form.add(lblSendFeedback, gbc);

        btnSendBackToOverview.addActionListener(e -> navigateToTab("Overview"));
        btnSubmitSendMoney.addActionListener(e -> handleInteractiveSendMoneySubmit());
        txtRecipient.addActionListener(e -> handleInteractiveSendMoneySubmit());
        txtSendAmount.addActionListener(e -> handleInteractiveSendMoneySubmit());
        txtSendNote.addActionListener(e -> handleInteractiveSendMoneySubmit());
        txtSendPin.addActionListener(e -> handleInteractiveSendMoneySubmit());

        wrapper.add(form, BorderLayout.NORTH);
        return wrapper;
    }

    private void addFormRow(JPanel panel, GridBagConstraints gbc, int row, String labelText, JComponent field) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 1;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        panel.add(new JLabel(labelText), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(field, gbc);
    }

    private JPanel buildServiceReadyNotice(String text) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(new JLabel(text, SwingConstants.CENTER), BorderLayout.CENTER);
        return panel;
    }

    // ─── CAP-09: Admin Controls Panel ───────────────────────────────────────────

    /**
     * Builds the Admin Controls tab: user account and wallet overview, suspend/reactivate/role
     * actions (with mandatory recorded reasons), transaction inspection, and audit log viewer.
     * Only rendered when the session user has role ADMIN; all DB calls execute off the EDT.
     */
    private JPanel buildAdminControlsPanel(WalletService ws) {
        User admin = SessionManager.getInstance().getCurrentUser();
        String adminId = admin.getUserId();

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        JLabel title = new JLabel("Admin Controls — Account Management & Audit Log");
        title.setFont(new Font("SansSerif", Font.BOLD, 14));
        root.add(title, BorderLayout.NORTH);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        split.setResizeWeight(0.55);

        String[] userCols = {"User ID", "Name", "Mobile", "Role", "Status", "Wallet", "Balance"};
        DefaultTableModel userModel = new DefaultTableModel(userCols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        JTable userTable = new JTable(userModel);
        userTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        userTable.setAutoCreateRowSorter(true);

        JScrollPane userScroll = new JScrollPane(userTable);
        userScroll.setBorder(BorderFactory.createTitledBorder("Registered Accounts & Wallets"));

        JButton btnSuspend = new JButton("Suspend User");
        JButton btnReactivate = new JButton("Reactivate User");
        JButton btnChangeRole = new JButton("Change Role");
        JButton btnViewUserTxns = new JButton("View User Txns");
        JButton btnViewAllTxns = new JButton("All System Txns");
        JButton btnRefreshAll = new JButton("Refresh");
        btnSuspend.setEnabled(false);
        btnReactivate.setEnabled(false);
        btnChangeRole.setEnabled(false);
        btnViewUserTxns.setEnabled(false);

        userTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                boolean hasSelection = userTable.getSelectedRow() >= 0;
                btnSuspend.setEnabled(hasSelection);
                btnReactivate.setEnabled(hasSelection);
                btnChangeRole.setEnabled(hasSelection);
                btnViewUserTxns.setEnabled(hasSelection);
            }
        });

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        btnPanel.add(btnSuspend);
        btnPanel.add(btnReactivate);
        btnPanel.add(btnChangeRole);
        btnPanel.add(btnViewUserTxns);
        btnPanel.add(btnViewAllTxns);
        btnPanel.add(btnRefreshAll);

        JPanel usersPanel = new JPanel(new BorderLayout(4, 4));
        usersPanel.add(userScroll, BorderLayout.CENTER);
        usersPanel.add(btnPanel, BorderLayout.SOUTH);
        split.setTopComponent(usersPanel);

        String[] auditCols = {"Action ID", "Type", "Target", "Reason", "Timestamp"};
        DefaultTableModel auditModel = new DefaultTableModel(auditCols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        JTable auditTable = new JTable(auditModel);
        auditTable.setAutoCreateRowSorter(true);

        JScrollPane auditScroll = new JScrollPane(auditTable);
        auditScroll.setBorder(BorderFactory.createTitledBorder("Admin Action Audit Log"));
        split.setBottomComponent(auditScroll);

        root.add(split, BorderLayout.CENTER);

        JLabel statusLbl = new JLabel("Loading admin records...");
        statusLbl.setFont(new Font("SansSerif", Font.PLAIN, 11));
        root.add(statusLbl, BorderLayout.SOUTH);

        Consumer<Boolean> reloadAll = showErrorPopup -> {
            btnRefreshAll.setEnabled(false);
            statusLbl.setText("Loading admin records...");
            new SwingWorker<AdminPanelSnapshot, Void>() {
                @Override
                protected AdminPanelSnapshot doInBackground() {
                    lastAdminLoadExecutedOffEdt = !SwingUtilities.isEventDispatchThread();
                    List<User> users = ws.getAllUsers(adminId);
                    List<Wallet> wallets = ws.getAllWallets(adminId);
                    List<AdminAction> actions = ws.getAdminActions(adminId);
                    Map<String, Wallet> walletsByUser = new HashMap<>();
                    for (Wallet w : wallets) {
                        walletsByUser.put(w.getUserId(), w);
                    }
                    return new AdminPanelSnapshot(users, walletsByUser, actions);
                }

                @Override
                protected void done() {
                    btnRefreshAll.setEnabled(true);
                    try {
                        AdminPanelSnapshot snap = get();
                        userModel.setRowCount(0);
                        for (User u : snap.users) {
                            Wallet w = snap.walletsByUser.get(u.getUserId());
                            userModel.addRow(new Object[]{
                                    u.getUserId(),
                                    u.getName(),
                                    u.getMobileNumber(),
                                    u.getRole(),
                                    u.getStatus(),
                                    w != null ? w.getWalletId() + " (" + w.getStatus() + ")" : "--",
                                    w != null ? MoneyUtil.format(w.getBalance(), w.getCurrency()) : "--"
                            });
                        }
                        auditModel.setRowCount(0);
                        for (AdminAction a : snap.actions) {
                            auditModel.addRow(new Object[]{
                                    a.getActionId(),
                                    a.getActionType(),
                                    a.getTargetUserId(),
                                    a.getReason(),
                                    a.getCreatedAt()
                            });
                        }
                        statusLbl.setText("Last refreshed: " + java.time.LocalDateTime.now()
                                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                    } catch (Exception ex) {
                        Throwable cause = unwrapCause(ex);
                        String message = extractErrorMessage(cause);
                        statusLbl.setText("Error loading admin data: " + message);
                        if (showErrorPopup) {
                            showDialogMessage(root, "Admin Refresh Failed", message, JOptionPane.ERROR_MESSAGE);
                        }
                    }
                }
            }.execute();
        };

        reloadAll.accept(false);

        btnSuspend.addActionListener(e -> executeAdminStatusAction(
                ws, adminId, userTable, userModel, statusLbl, () -> reloadAll.accept(false), true));

        btnReactivate.addActionListener(e -> executeAdminStatusAction(
                ws, adminId, userTable, userModel, statusLbl, () -> reloadAll.accept(false), false));

        btnChangeRole.addActionListener(e -> {
            int row = userTable.getSelectedRow();
            if (row < 0) return;
            int modelRow = userTable.convertRowIndexToModel(row);
            String targetId = (String) userModel.getValueAt(modelRow, 0);
            String targetName = (String) userModel.getValueAt(modelRow, 1);
            UserRole currentRole = (UserRole) userModel.getValueAt(modelRow, 3);

            UserRole selectedRole = (UserRole) JOptionPane.showInputDialog(
                    root,
                    "Select new role for " + targetName + " (" + targetId + "):",
                    "Change User Role",
                    JOptionPane.QUESTION_MESSAGE,
                    null,
                    UserRole.values(),
                    currentRole
            );
            if (selectedRole == null) return;
            String reason = promptMandatoryAdminReason(root,
                    "Enter reason for changing role of " + targetName + " to " + selectedRole + ":",
                    "Change User Role");
            if (reason == null) return;

            statusLbl.setText("Updating role for " + targetId + "...");
            new SwingWorker<Void, Void>() {
                @Override
                protected Void doInBackground() {
                    ws.getAdminService().changeUserRole(adminId, targetId, selectedRole, reason);
                    return null;
                }

                @Override
                protected void done() {
                    try {
                        get();
                        showDialogMessage(root, "Done", "Role updated to " + selectedRole + ".",
                                JOptionPane.INFORMATION_MESSAGE);
                    } catch (Exception ex) {
                        Throwable cause = unwrapCause(ex);
                        showDialogMessage(root, "Role Update Failed", "Error: " + extractErrorMessage(cause),
                                JOptionPane.ERROR_MESSAGE);
                    } finally {
                        reloadAll.accept(false);
                    }
                }
            }.execute();
        });

        btnViewUserTxns.addActionListener(e -> {
            int row = userTable.getSelectedRow();
            if (row < 0) return;
            int modelRow = userTable.convertRowIndexToModel(row);
            String targetId = (String) userModel.getValueAt(modelRow, 0);
            String targetName = (String) userModel.getValueAt(modelRow, 1);
            loadAndShowAdminTransactions(ws, adminId, targetId,
                    "Transactions — " + targetName + " (" + targetId + ")", root, statusLbl,
                    () -> reloadAll.accept(false));
        });

        btnViewAllTxns.addActionListener(e ->
                loadAndShowAdminTransactions(ws, adminId, null,
                        "All System Transactions", root, statusLbl, () -> reloadAll.accept(false)));

        btnRefreshAll.addActionListener(e -> reloadAll.accept(true));

        return root;
    }

    private void executeAdminStatusAction(WalletService ws, String adminId, JTable userTable,
                                          DefaultTableModel userModel, JLabel statusLbl,
                                          Runnable reloadAll, boolean suspend) {
        int row = userTable.getSelectedRow();
        if (row < 0) return;
        int modelRow = userTable.convertRowIndexToModel(row);
        String targetId = (String) userModel.getValueAt(modelRow, 0);
        String targetName = (String) userModel.getValueAt(modelRow, 1);

        String verb = suspend ? "suspending" : "reactivating";
        String dialogTitle = suspend ? "Suspend Account" : "Reactivate Account";
        String reason = promptMandatoryAdminReason(userTable,
                "Enter reason for " + verb + " " + targetName + " (" + targetId + "):",
                dialogTitle);
        if (reason == null) return;

        statusLbl.setText((suspend ? "Suspending " : "Reactivating ") + targetId + "...");
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                if (suspend) {
                    ws.suspendUser(adminId, targetId, reason);
                } else {
                    ws.reactivateUser(adminId, targetId, reason);
                }
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    showDialogMessage(userTable, "Done",
                            suspend ? "Account suspended." : "Account reactivated.",
                            JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception ex) {
                    Throwable cause = unwrapCause(ex);
                    showDialogMessage(userTable,
                            suspend ? "Suspend Failed" : "Reactivate Failed",
                            "Error: " + extractErrorMessage(cause),
                            JOptionPane.ERROR_MESSAGE);
                } finally {
                    reloadAll.run();
                }
            }
        }.execute();
    }

    private String promptMandatoryAdminReason(Component parent, String message, String title) {
        String reason = JOptionPane.showInputDialog(parent, message, title, JOptionPane.QUESTION_MESSAGE);
        if (reason == null) {
            return null;
        }
        String trimmed = reason.trim();
        if (trimmed.isEmpty()) {
            showDialogMessage(parent, "Validation Error", "A reason is required.", JOptionPane.ERROR_MESSAGE);
            return null;
        }
        return trimmed;
    }

    private void loadAndShowAdminTransactions(WalletService ws, String adminId, String targetUserId,
                                              String dialogTitle, Component parent,
                                              JLabel statusLbl, Runnable reloadAll) {
        statusLbl.setText("Loading transactions...");
        new SwingWorker<List<Transaction>, Void>() {
            @Override
            protected List<Transaction> doInBackground() {
                return targetUserId != null
                        ? ws.getUserTransactions(adminId, targetUserId)
                        : ws.getAllTransactions(adminId);
            }

            @Override
            protected void done() {
                try {
                    List<Transaction> txns = get();
                    String[] cols = {"Date", "Ref #", "Type", "Source", "Target", "Amount", "Fee", "Status", "Description"};
                    DefaultTableModel model = new DefaultTableModel(cols, 0) {
                        @Override public boolean isCellEditable(int r, int c) { return false; }
                    };
                    for (Transaction t : txns) {
                        model.addRow(new Object[]{
                                t.getCreatedAt() != null ? t.getCreatedAt().format(DATE_FORMATTER) : "-",
                                t.getReferenceNumber(),
                                t.getType(),
                                t.getSourceWalletId() != null ? t.getSourceWalletId() : "-",
                                t.getTargetWalletId() != null ? t.getTargetWalletId() : "-",
                                MoneyUtil.format(t.getAmount(), t.getCurrency()),
                                MoneyUtil.format(t.getFee(), t.getCurrency()),
                                t.getStatus(),
                                t.getDescription() != null ? t.getDescription() : ""
                        });
                    }
                    JTable table = new JTable(model);
                    table.setAutoCreateRowSorter(true);
                    JScrollPane scroll = new JScrollPane(table);
                    scroll.setPreferredSize(new Dimension(760, 320));
                    JOptionPane.showMessageDialog(parent, scroll, dialogTitle, JOptionPane.INFORMATION_MESSAGE);
                    reloadAll.run();
                } catch (Exception ex) {
                    Throwable cause = unwrapCause(ex);
                    String message = extractErrorMessage(cause);
                    statusLbl.setText("Error loading transactions: " + message);
                    showDialogMessage(parent, "Transaction Inspection Failed", "Error: " + message,
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private JPanel buildFooterPanel() {
        JPanel footer = new JPanel(new BorderLayout());
        footer.setBorder(BorderFactory.createEmptyBorder(4, 2, 0, 2));
        lblStatus = new JLabel("Initializing dashboard...");
        lblStatus.setFont(new Font("SansSerif", Font.PLAIN, 11));
        lblStatus.setForeground(new Color(90, 98, 110));
        footer.add(lblStatus, BorderLayout.WEST);
        return footer;
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
            lblBalance.setText("PHP 0.00");
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
            lblPinStatus.setForeground(new Color(90, 98, 110));
            return;
        }
        boolean pinVerified = SessionManager.getInstance().isPinVerified();
        if (!hasPin) {
            lblPinStatus.setText("Transaction PIN: Not Set (required for Cash Out & Send Money)");
            lblPinStatus.setForeground(new Color(180, 95, 10));
            if (btnPinSettings != null) {
                btnPinSettings.setText("Set PIN");
            }
        } else if (pinVerified) {
            lblPinStatus.setText("Transaction PIN: Set & Verified for active session");
            lblPinStatus.setForeground(new Color(17, 102, 68));
            if (btnPinSettings != null) {
                btnPinSettings.setText("Change PIN");
            }
        } else {
            lblPinStatus.setText("Transaction PIN: Set (enter PIN on outgoing payments)");
            lblPinStatus.setForeground(new Color(45, 90, 140));
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
                List<Transaction> transactions = walletService.getRecentTransactions(
                        wallet.getWalletId(), RECENT_TRANSACTION_LIMIT);
                List<PaymentMethod> paymentMethods = user != null
                        ? walletService.getPaymentMethodsByUserId(user.getUserId())
                        : Collections.emptyList();
                boolean hasPin = authService != null && user != null && authService.hasPin(user.getUserId());
                return new DashboardSnapshot(user, wallet, transactions, paymentMethods, hasPin);
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
                    populatePaymentMethodsCombo(snapshot.paymentMethods);
                    populateTransactionsTable(snapshot.wallet.getWalletId(), snapshot.transactions);
                    if (snapshot.transactions.isEmpty()) {
                        currentState = DashboardState.EMPTY;
                        activityCardLayout.show(activityCardPanel, DashboardState.EMPTY.name());
                        lblStatus.setText("Ready — no transactions yet");
                    } else {
                        currentState = DashboardState.READY;
                        activityCardLayout.show(activityCardPanel, DashboardState.READY.name());
                        lblStatus.setText("Ready — showing " + snapshot.transactions.size() + " recent transaction(s)");
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
            lblCashFeedback.setForeground(new Color(70, 80, 95));
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
                        lblCashFeedback.setForeground(new Color(17, 102, 68));
                        lblCashFeedback.setText(opLabel + " successful: "
                                + MoneyUtil.format(txn.getAmount(), txn.getCurrency())
                                + " (Ref: " + txn.getReferenceNumber() + ")" + provRef + provStatus);
                        lblStatus.setText(opLabel + " completed" + provStatus);
                    } else if (txn.getStatus() == TransactionStatus.FAILED) {
                        lblCashFeedback.setForeground(new Color(176, 38, 38));
                        lblCashFeedback.setText(opLabel + " failed/rejected: "
                                + MoneyUtil.format(txn.getAmount(), txn.getCurrency())
                                + " (Ref: " + txn.getReferenceNumber() + ")" + provRef + provStatus);
                        lblStatus.setText(opLabel + " failed" + provStatus);
                    } else {
                        lblCashFeedback.setForeground(new Color(180, 95, 10));
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
                    lblCashFeedback.setForeground(new Color(176, 38, 38));
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
            lblCashFeedback.setForeground(new Color(176, 38, 38));
            lblCashFeedback.setText("Failed: " + msg);
            lblStatus.setText(opLabel + " validation failed: " + msg);
            showDialogMessage(this, "Invalid Amount", msg, JOptionPane.ERROR_MESSAGE);
            txtCashAmount.requestFocusInWindow();
            return;
        }

        if ("CASH_OUT".equals(op)) {
            if (authService != null && !cachedHasPin) {
                txtCashPin.setText("");
                promptPinSetupIfMissing("A Transaction PIN is required before making a Cash Out.");
                return;
            }
            if (pin.isEmpty()) {
                String msg = "Please enter your 4-6 digit Transaction PIN to authorize Cash Out.";
                lblCashFeedback.setForeground(new Color(176, 38, 38));
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
        confirmMsg.append("Amount: ").append(MoneyUtil.format(parsedAmount, "PHP"));
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
            lblCashFeedback.setForeground(new Color(90, 98, 110));
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
            lblSendFeedback.setForeground(new Color(176, 38, 38));
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
            lblSendFeedback.setForeground(new Color(176, 38, 38));
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

        if (pin.isEmpty()) {
            String msg = "Please enter your 4-6 digit Transaction PIN to authorize Send Money.";
            lblSendFeedback.setForeground(new Color(176, 38, 38));
            lblSendFeedback.setText("Transfer failed: " + msg);
            showDialogMessage(this, "PIN Required", msg, JOptionPane.WARNING_MESSAGE);
            txtSendPin.requestFocusInWindow();
            return;
        }

        txtSendPin.setText("");
        User currentUser = session.getCurrentUser();
        if (walletService instanceof WalletService ws && currentUser != null) {
            btnSubmitSendMoney.setEnabled(false);
            lblSendFeedback.setForeground(new Color(70, 80, 95));
            lblSendFeedback.setText("Preparing transfer confirmation...");
            lblStatus.setText("Validating recipient and balance...");

            SendMoneyService sendMoneyService = new SendMoneyService(ws, ws.getUserRepository(), authService);
            new SwingWorker<SendMoneyService.SendMoneyPreview, Void>() {
                @Override
                protected SendMoneyService.SendMoneyPreview doInBackground() {
                    return sendMoneyService.preview(currentUser.getUserId(), recipient, parsedAmount, MoneyUtil.ZERO, note);
                }

                @Override
                protected void done() {
                    btnSubmitSendMoney.setEnabled(true);
                    try {
                        SendMoneyService.SendMoneyPreview preview = get();
                        JTextArea previewArea = new JTextArea(preview.toConfirmationText());
                        previewArea.setEditable(false);
                        previewArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
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
                            lblSendFeedback.setForeground(new Color(90, 98, 110));
                            lblSendFeedback.setText("Transfer cancelled.");
                            lblStatus.setText("Transfer cancelled");
                            return;
                        }
                        dispatchConfirmedSendMoney(preview.getReferenceNumber(), recipient, amountText, note, pin);
                    } catch (Exception ex) {
                        Throwable cause = unwrapCause(ex);
                        String message = extractErrorMessage(cause);
                        lblSendFeedback.setForeground(new Color(176, 38, 38));
                        lblSendFeedback.setText("Transfer failed: " + message);
                        lblStatus.setText("Transfer failed: " + message);
                        showDialogMessage(Main.this, "Transfer Validation Failed", message, JOptionPane.ERROR_MESSAGE);
                    }
                }
            }.execute();
        } else {
            StringBuilder confirmMsg = new StringBuilder();
            confirmMsg.append("Recipient: ").append(recipient).append("\n");
            confirmMsg.append("Amount: ").append(MoneyUtil.format(parsedAmount, "PHP"));
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
                lblSendFeedback.setForeground(new Color(90, 98, 110));
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
                        color = new Color(17, 102, 68);
                        msg = "Status lookup settled COMPLETED: "
                                + txn.getReferenceNumber() + provRef + provStatus;
                    } else if (txn.getStatus() == TransactionStatus.FAILED) {
                        color = new Color(176, 38, 38);
                        msg = "Status lookup confirmed FAILED: "
                                + txn.getReferenceNumber() + provRef + provStatus;
                    } else {
                        color = new Color(180, 95, 10);
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
                        lblActivityFeedback.setForeground(new Color(176, 38, 38));
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
            lblSendFeedback.setForeground(new Color(70, 80, 95));
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
                    lblSendFeedback.setForeground(new Color(17, 102, 68));
                    lblSendFeedback.setText("Transfer successful: "
                            + MoneyUtil.format(txn.getAmount(), txn.getCurrency())
                            + " (Ref: " + txn.getReferenceNumber() + ")");
                    refreshDashboardAsync().whenComplete((v, err) -> future.complete(txn));
                } catch (Exception ex) {
                    Throwable cause = unwrapCause(ex);
                    String message = extractErrorMessage(cause);
                    lblSendFeedback.setForeground(new Color(176, 38, 38));
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

        JDialog dialog = new JDialog(this, cachedHasPin ? "Change Transaction PIN" : "Set Transaction PIN", true);
        dialog.setSize(420, 270);
        dialog.setLocationRelativeTo(this);
        dialog.setResizable(false);

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(14, 16, 10, 16));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;

        JRadioButton rbChangePin = new JRadioButton("Use Current PIN", cachedHasPin);
        JRadioButton rbResetWithPassword = new JRadioButton(
                cachedHasPin ? "Reset with Account Password" : "Verify with Account Password", !cachedHasPin);
        ButtonGroup modeGroup = new ButtonGroup();
        modeGroup.add(rbChangePin);
        modeGroup.add(rbResetWithPassword);
        rbChangePin.setEnabled(cachedHasPin);

        JLabel lblSecret = new JLabel(cachedHasPin ? "Current PIN:" : "Account Password:");
        JPasswordField txtSecret = new JPasswordField(18);
        JPasswordField txtNewPin = new JPasswordField(18);
        JPasswordField txtConfirmPin = new JPasswordField(18);
        JLabel lblPinDialogFeedback = new JLabel(" ");
        lblPinDialogFeedback.setFont(new Font("SansSerif", Font.PLAIN, 11));

        if (cachedHasPin) {
            JPanel modePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            modePanel.add(rbChangePin);
            modePanel.add(rbResetWithPassword);
            gbc.gridx = 0;
            gbc.gridy = 0;
            gbc.gridwidth = 2;
            form.add(modePanel, gbc);
        }

        rbChangePin.addActionListener(e -> lblSecret.setText("Current PIN:"));
        rbResetWithPassword.addActionListener(e -> lblSecret.setText("Account Password:"));

        int row = cachedHasPin ? 1 : 0;
        gbc.gridwidth = 1;
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        form.add(lblSecret, gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        form.add(txtSecret, gbc);

        addFormRow(form, gbc, row + 1, "New PIN (4-6 digits):", txtNewPin);
        addFormRow(form, gbc, row + 2, "Confirm New PIN:", txtConfirmPin);

        gbc.gridx = 0;
        gbc.gridy = row + 3;
        gbc.gridwidth = 2;
        form.add(lblPinDialogFeedback, gbc);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 10));
        JButton btnCancel = new JButton("Cancel");
        JButton btnSavePin = new JButton("Save PIN");
        buttons.add(btnCancel);
        buttons.add(btnSavePin);

        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.getRootPane().setDefaultButton(btnSavePin);
        dialog.getRootPane().registerKeyboardAction(
                e -> {
                    if (btnCancel.isEnabled()) {
                        dialog.dispose();
                    }
                },
                KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW
        );

        btnCancel.addActionListener(e -> dialog.dispose());
        btnSavePin.addActionListener(e -> {
            String secret = new String(txtSecret.getPassword());
            String newPin = new String(txtNewPin.getPassword()).trim();
            String confirmPin = new String(txtConfirmPin.getPassword()).trim();

            if (secret.isEmpty() || newPin.isEmpty()) {
                String msg = "Please fill in all fields.";
                lblPinDialogFeedback.setForeground(new Color(176, 38, 38));
                lblPinDialogFeedback.setText(msg);
                showDialogMessage(dialog, "Validation Error", msg, JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (!newPin.equals(confirmPin)) {
                String msg = "New PIN and confirmation do not match.";
                lblPinDialogFeedback.setForeground(new Color(176, 38, 38));
                lblPinDialogFeedback.setText(msg);
                showDialogMessage(dialog, "Validation Error", msg, JOptionPane.WARNING_MESSAGE);
                return;
            }

            final boolean useCurrentPin = rbChangePin.isSelected();
            btnSavePin.setEnabled(false);
            btnCancel.setEnabled(false);
            dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            lblPinDialogFeedback.setForeground(new Color(70, 80, 95));
            lblPinDialogFeedback.setText("Saving Transaction PIN...");

            new SwingWorker<Void, Void>() {
                @Override
                protected Void doInBackground() {
                    if (useCurrentPin) {
                        authService.changePin(currentUser.getUserId(), secret, newPin);
                    } else {
                        authService.setPin(currentUser.getUserId(), secret, newPin);
                    }
                    return null;
                }

                @Override
                protected void done() {
                    btnSavePin.setEnabled(true);
                    btnCancel.setEnabled(true);
                    dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
                    try {
                        get();
                        updatePinStatusDisplay(true);
                        lblStatus.setText("Transaction PIN updated.");
                        showDialogMessage(dialog,
                                "Transaction PIN",
                                "Transaction PIN saved successfully.",
                                JOptionPane.INFORMATION_MESSAGE);
                        dialog.dispose();
                    } catch (Exception ex) {
                        Throwable cause = unwrapCause(ex);
                        String message = extractErrorMessage(cause);
                        lblPinDialogFeedback.setForeground(new Color(176, 38, 38));
                        lblPinDialogFeedback.setText(message);
                        if (SessionManager.getInstance().isExpiredDueToInactivity()
                                || SessionManager.getInstance().isLockedOutByPin()) {
                            dialog.dispose();
                            handleSessionTermination(message);
                        } else {
                            showDialogMessage(dialog, "Transaction PIN Error", message, JOptionPane.ERROR_MESSAGE);
                        }
                    }
                }
            }.execute();
        });

        dialog.setLayout(new BorderLayout());
        dialog.add(form, BorderLayout.CENTER);
        dialog.add(buttons, BorderLayout.SOUTH);
        dialog.setVisible(true);
    }

    private void startSessionMonitor() {
        sessionMonitorTimer = new Timer(SESSION_POLL_INTERVAL_MS, e -> {
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
            String date = txn.getCreatedAt() != null ? txn.getCreatedAt().format(DATE_FORMATTER) : "-";
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
        if (btnCheckStatus == null) {
            return;
        }
        Transaction selected = getSelectedTransactionForReceipt();
        boolean isCashTxn = selected != null
                && (selected.getType() == TransactionType.CASH_IN || selected.getType() == TransactionType.CASH_OUT);
        btnCheckStatus.setEnabled(isCashTxn);
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
                lblActivityFeedback.setForeground(new Color(180, 95, 10));
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
        JTextArea area = new JTextArea(txn.getReceipt());
        area.setEditable(false);
        area.setFont(new Font("Monospaced", Font.PLAIN, 12));
        JOptionPane.showMessageDialog(this, new JScrollPane(area),
                "Transaction Receipt — " + txn.getReferenceNumber(),
                JOptionPane.INFORMATION_MESSAGE);
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

    private static final class DashboardSnapshot {
        private final User user;
        private final Wallet wallet;
        private final List<Transaction> transactions;
        private final List<PaymentMethod> paymentMethods;
        private final boolean hasPin;

        private DashboardSnapshot(User user, Wallet wallet, List<Transaction> transactions,
                                  List<PaymentMethod> paymentMethods, boolean hasPin) {
            this.user = user;
            this.wallet = wallet;
            this.transactions = transactions;
            this.paymentMethods = paymentMethods;
            this.hasPin = hasPin;
        }
    }

    private static final class AdminPanelSnapshot {
        private final List<User> users;
        private final Map<String, Wallet> walletsByUser;
        private final List<AdminAction> actions;

        private AdminPanelSnapshot(List<User> users, Map<String, Wallet> walletsByUser, List<AdminAction> actions) {
            this.users = users;
            this.walletsByUser = walletsByUser;
            this.actions = actions;
        }
    }
}
