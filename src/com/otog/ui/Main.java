package com.otog.ui;

import com.otog.database.DatabaseManager;
import com.otog.model.Transaction;
import com.otog.model.TransactionType;
import com.otog.model.User;
import com.otog.model.Wallet;
import com.otog.service.InvestmentService;
import com.otog.service.LoanService;
import com.otog.service.SessionManager;
import com.otog.service.WalletOperations;
import com.otog.service.WalletService;
import com.otog.util.IdGenerator;
import com.otog.util.MoneyUtil;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public class Main extends JFrame {

    public enum DashboardState {
        LOADING,
        EMPTY,
        READY,
        ERROR
    }

    private static final int RECENT_TRANSACTION_LIMIT = 50;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final WalletOperations walletService;
    private final LoanService loanService;
    private final InvestmentService investmentService;
    private final DatabaseManager ownedDatabaseManager;

    private JLabel lblWelcome;
    private JLabel lblBalance;
    private JLabel lblWalletId;
    private JLabel lblStatus;
    private JButton btnRefresh;
    private JButton btnLogout;

    private JTabbedPane tabs;

    private JButton btnNavCashIn;
    private JButton btnNavCashOut;
    private JButton btnNavSendMoney;

    private CardLayout activityCardLayout;
    private JPanel activityCardPanel;
    private JLabel lblLoadingState;
    private JLabel lblEmptyState;
    private JLabel lblErrorState;
    private JButton btnRetryLoad;
    private DefaultTableModel transactionsTableModel;
    private JTable tblTransactions;
    private JButton btnViewReceipt;

    private JComboBox<String> cmbCashOperation;
    private JComboBox<String> cmbCashChannel;
    private JTextField txtCashAmount;
    private JTextField txtCashNote;
    private JButton btnSubmitCashOp;
    private JLabel lblCashFeedback;

    private JTextField txtRecipient;
    private JTextField txtSendAmount;
    private JTextField txtSendNote;
    private JButton btnSubmitSendMoney;
    private JLabel lblSendFeedback;

    private volatile DashboardState currentState = DashboardState.LOADING;
    private volatile boolean lastLoadExecutedOffEdt;
    private volatile boolean lastPaymentExecutedOffEdt;
    private CompletableFuture<Void> initialLoadFuture;
    private List<Transaction> loadedTransactions = Collections.emptyList();
    private Runnable onLogoutCallback;

    public Main() {
        this(requireAuthAndCreateDb(), null, null);
    }

    private static DatabaseManager requireAuthAndCreateDb() {
        SessionManager.getInstance().requireAuthentication();
        return new DatabaseManager();
    }

    private Main(DatabaseManager ownedDatabaseManager, LoanService loanService, InvestmentService investmentService) {
        this(new WalletService(ownedDatabaseManager), loanService, investmentService, ownedDatabaseManager);
    }

    public Main(WalletOperations walletService) {
        this(walletService, null, null, null);
    }

    public Main(WalletOperations walletService, LoanService loanService, InvestmentService investmentService) {
        this(walletService, loanService, investmentService, null);
    }

    private Main(WalletOperations walletService, LoanService loanService, InvestmentService investmentService,
                 DatabaseManager ownedDatabaseManager) {
        SessionManager.getInstance().requireAuthentication();
        if (walletService == null) {
            throw new IllegalArgumentException("WalletOperations service must not be null");
        }
        this.walletService = walletService;
        this.loanService = loanService;
        this.investmentService = investmentService;
        this.ownedDatabaseManager = ownedDatabaseManager;

        initUI();
        this.initialLoadFuture = refreshDashboardAsync();
    }

    private void initUI() {
        setTitle("OTOG E-Wallet — Dashboard");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(900, 620);
        setMinimumSize(new Dimension(820, 540));
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

        JPanel identityPanel = new JPanel(new GridLayout(2, 1, 0, 2));
        lblWelcome = new JLabel("Welcome");
        lblWelcome.setFont(new Font("SansSerif", Font.BOLD, 16));
        lblWalletId = new JLabel("Wallet: --");
        lblWalletId.setFont(new Font("SansSerif", Font.PLAIN, 12));
        lblWalletId.setForeground(new Color(90, 98, 110));
        identityPanel.add(lblWelcome);
        identityPanel.add(lblWalletId);

        JPanel balanceAndActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        JPanel balanceBox = new JPanel(new GridLayout(2, 1, 0, 1));
        JLabel balanceCaption = new JLabel("Available Balance", SwingConstants.RIGHT);
        balanceCaption.setFont(new Font("SansSerif", Font.PLAIN, 11));
        balanceCaption.setForeground(new Color(90, 98, 110));
        lblBalance = new JLabel("PHP 0.00", SwingConstants.RIGHT);
        lblBalance.setFont(new Font("SansSerif", Font.BOLD, 18));
        lblBalance.setForeground(new Color(17, 102, 68));
        balanceBox.add(balanceCaption);
        balanceBox.add(lblBalance);

        btnRefresh = new JButton("Refresh");
        btnRefresh.addActionListener(e -> refreshDashboardAsync());

        btnLogout = new JButton("Logout");
        btnLogout.addActionListener(e -> handleLogout());

        balanceAndActions.add(balanceBox);
        balanceAndActions.add(Box.createHorizontalStrut(8));
        balanceAndActions.add(btnRefresh);
        balanceAndActions.add(btnLogout);

        header.add(identityPanel, BorderLayout.WEST);
        header.add(balanceAndActions, BorderLayout.EAST);
        return header;
    }

    private JTabbedPane buildTabsPanel() {
        tabs = new JTabbedPane();
        tabs.addTab("Overview", buildOverviewPanel());
        tabs.addTab("Cash In / Out", buildCashInOutPanel());
        tabs.addTab("Send Money", buildSendMoneyPanel());

        // Hide unfinished modules unless an implementation is explicitly provided
        if (loanService != null) {
            tabs.addTab("Loans", buildServiceReadyNotice("Loans module active"));
        }
        if (investmentService != null) {
            tabs.addTab("Investments", buildServiceReadyNotice("Investments module active"));
        }
        return tabs;
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

        quickActions.add(btnNavCashIn);
        quickActions.add(btnNavCashOut);
        quickActions.add(btnNavSendMoney);

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
        btnRetryLoad.addActionListener(e -> refreshDashboardAsync());
        JPanel retryWrapper = new JPanel(new FlowLayout(FlowLayout.CENTER));
        retryWrapper.add(btnRetryLoad);
        errorInner.add(lblErrorState, BorderLayout.CENTER);
        errorInner.add(retryWrapper, BorderLayout.SOUTH);
        errorCard.add(errorInner);

        String[] columns = {"Date", "Ref #", "Type", "Direction", "Amount", "Fee", "Status", "Description"};
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

        JPanel readyCard = new JPanel(new BorderLayout(6, 6));
        readyCard.add(new JScrollPane(tblTransactions), BorderLayout.CENTER);

        JPanel tableActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        btnViewReceipt = new JButton("View Receipt");
        btnViewReceipt.addActionListener(e -> showSelectedReceipt());
        tableActions.add(btnViewReceipt);
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
        cmbCashChannel = new JComboBox<>(new String[]{"GCash", "Maya", "BDO", "BPI", "7-Eleven OTC"});
        txtCashAmount = new JTextField(20);
        txtCashNote = new JTextField(20);
        btnSubmitCashOp = new JButton("Submit Cash Transaction");
        lblCashFeedback = new JLabel(" ");
        lblCashFeedback.setFont(new Font("SansSerif", Font.PLAIN, 12));

        addFormRow(form, gbc, 0, "Operation:", cmbCashOperation);
        addFormRow(form, gbc, 1, "Channel / Provider:", cmbCashChannel);
        addFormRow(form, gbc, 2, "Amount (PHP):", txtCashAmount);
        addFormRow(form, gbc, 3, "Note (Optional):", txtCashNote);

        gbc.gridx = 1;
        gbc.gridy = 4;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.NONE;
        form.add(btnSubmitCashOp, gbc);

        gbc.gridx = 0;
        gbc.gridy = 5;
        gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        form.add(lblCashFeedback, gbc);

        btnSubmitCashOp.addActionListener(e -> submitCashOperationAsync(
                (String) cmbCashOperation.getSelectedItem(),
                (String) cmbCashChannel.getSelectedItem(),
                txtCashAmount.getText(),
                txtCashNote.getText()
        ));

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
        btnSubmitSendMoney = new JButton("Send Money");
        lblSendFeedback = new JLabel(" ");
        lblSendFeedback.setFont(new Font("SansSerif", Font.PLAIN, 12));

        addFormRow(form, gbc, 0, "Recipient (Mobile / Email / Name / Wallet ID):", txtRecipient);
        addFormRow(form, gbc, 1, "Amount (PHP):", txtSendAmount);
        addFormRow(form, gbc, 2, "Note (Optional):", txtSendNote);

        gbc.gridx = 1;
        gbc.gridy = 3;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.NONE;
        form.add(btnSubmitSendMoney, gbc);

        gbc.gridx = 0;
        gbc.gridy = 4;
        gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        form.add(lblSendFeedback, gbc);

        btnSubmitSendMoney.addActionListener(e -> submitSendMoneyAsync(
                txtRecipient.getText(),
                txtSendAmount.getText(),
                txtSendNote.getText()
        ));

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
        updateHeaderFromSession(user, wallet);
    }

    private void updateHeaderFromSession(User user, Wallet wallet) {
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
    }

    public CompletableFuture<Void> refreshDashboardAsync() {
        CompletableFuture<Void> future = new CompletableFuture<>();
        this.currentState = DashboardState.LOADING;

        runOnEdt(() -> {
            setLoadingState("Loading wallet balance and recent activity...");
            if (btnRefresh != null) {
                btnRefresh.setEnabled(false);
            }
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
                return new DashboardSnapshot(user, wallet, transactions);
            }

            @Override
            protected void done() {
                if (btnRefresh != null) {
                    btnRefresh.setEnabled(true);
                }
                try {
                    DashboardSnapshot snapshot = get();
                    updateHeaderFromSession(snapshot.user, snapshot.wallet);
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
                }
                future.complete(null);
            }
        };
        worker.execute();
        return future;
    }

    public CompletableFuture<Transaction> submitCashOperationAsync(String operationType, String channel,
                                                                    String amountText, String note) {
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
                Wallet wallet = session.getCurrentWallet();
                if (wallet == null) {
                    wallet = session.refreshWallet(walletService);
                }

                BigDecimal amount = MoneyUtil.validatePositiveAmount(MoneyUtil.parse(amountText));
                String ref = IdGenerator.nextReferenceNumber();
                String cleanChannel = (channel != null && !channel.trim().isEmpty())
                        ? channel.trim() : "OTOG Partner";
                String cleanNote = (note != null && !note.trim().isEmpty()) ? note.trim() : null;

                if ("CASH_IN".equals(op)) {
                    String desc = cleanNote != null ? cleanChannel + " - " + cleanNote : "Cash In via " + cleanChannel;
                    return walletService.cashIn(ref, wallet.getWalletId(), null, amount, desc);
                } else if ("CASH_OUT".equals(op)) {
                    String desc = cleanNote != null ? cleanChannel + " - " + cleanNote : "Cash Out to " + cleanChannel;
                    return walletService.cashOut(ref, wallet.getWalletId(), null, amount, MoneyUtil.ZERO, desc);
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
                    lblCashFeedback.setForeground(new Color(17, 102, 68));
                    lblCashFeedback.setText(opLabel + " successful: "
                            + MoneyUtil.format(txn.getAmount(), txn.getCurrency())
                            + " (Ref: " + txn.getReferenceNumber() + ")");
                    refreshDashboardAsync().whenComplete((v, err) -> future.complete(txn));
                } catch (Exception ex) {
                    Throwable cause = unwrapCause(ex);
                    String message = (cause.getMessage() != null && !cause.getMessage().trim().isEmpty())
                            ? cause.getMessage()
                            : cause.getClass().getSimpleName();
                    lblCashFeedback.setForeground(new Color(176, 38, 38));
                    lblCashFeedback.setText("Failed: " + message);
                    lblStatus.setText(opLabel + " failed: " + message);
                    future.completeExceptionally(cause);
                }
            }
        };
        worker.execute();
        return future;
    }

    public CompletableFuture<Transaction> submitSendMoneyAsync(String recipient, String amountText, String note) {
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
                Wallet wallet = session.getCurrentWallet();
                if (wallet == null) {
                    wallet = session.refreshWallet(walletService);
                }

                if (recipient == null || recipient.trim().isEmpty()) {
                    throw new IllegalArgumentException("Recipient identifier is required.");
                }
                BigDecimal amount = MoneyUtil.validatePositiveAmount(MoneyUtil.parse(amountText));
                String ref = IdGenerator.nextReferenceNumber();
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
                    String message = (cause.getMessage() != null && !cause.getMessage().trim().isEmpty())
                            ? cause.getMessage()
                            : cause.getClass().getSimpleName();
                    lblSendFeedback.setForeground(new Color(176, 38, 38));
                    lblSendFeedback.setText("Transfer failed: " + message);
                    lblStatus.setText("Transfer failed: " + message);
                    future.completeExceptionally(cause);
                }
            }
        };
        worker.execute();
        return future;
    }

    private void setLoadingState(String message) {
        lblLoadingState.setText(message);
        activityCardLayout.show(activityCardPanel, DashboardState.LOADING.name());
        lblStatus.setText(message);
    }

    private void populateTransactionsTable(String walletId, List<Transaction> transactions) {
        this.loadedTransactions = transactions != null ? transactions : Collections.emptyList();
        transactionsTableModel.setRowCount(0);
        for (Transaction txn : this.loadedTransactions) {
            String date = txn.getCreatedAt() != null ? txn.getCreatedAt().format(DATE_FORMATTER) : "-";
            String direction = resolveDirection(walletId, txn);
            transactionsTableModel.addRow(new Object[]{
                    date,
                    txn.getReferenceNumber(),
                    txn.getType() != null ? txn.getType().name() : "-",
                    direction,
                    MoneyUtil.format(txn.getAmount(), txn.getCurrency()),
                    MoneyUtil.format(txn.getFee(), txn.getCurrency()),
                    txn.getStatus() != null ? txn.getStatus().name() : "-",
                    txn.getDescription() != null ? txn.getDescription() : ""
            });
        }
    }

    private String resolveDirection(String walletId, Transaction txn) {
        if (txn.getType() == TransactionType.CASH_IN) {
            return "IN (+)";
        }
        if (txn.getType() == TransactionType.CASH_OUT) {
            return "OUT (-)";
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

    private void showSelectedReceipt() {
        int row = tblTransactions.getSelectedRow();
        if (row < 0 || row >= loadedTransactions.size()) {
            JOptionPane.showMessageDialog(this,
                    "Please select a transaction row to view its receipt.",
                    "No Transaction Selected",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        Transaction txn = loadedTransactions.get(row);
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
        for (int i = 0; i < tabs.getTabCount(); i++) {
            if (title.equalsIgnoreCase(tabs.getTitleAt(i))) {
                tabs.setSelectedIndex(i);
                return true;
            }
        }
        return false;
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

    private void handleLogout() {
        SessionManager.getInstance().logout();
        dispose();
        if (onLogoutCallback != null) {
            SwingUtilities.invokeLater(onLogoutCallback);
        }
    }

    @Override
    public void dispose() {
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
        if (ex instanceof ExecutionException && ex.getCause() != null) {
            return ex.getCause();
        }
        return ex;
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

    public JButton getCashInQuickNavButton() {
        return btnNavCashIn;
    }

    public JButton getCashOutQuickNavButton() {
        return btnNavCashOut;
    }

    public JButton getSendMoneyQuickNavButton() {
        return btnNavSendMoney;
    }

    public JButton getRetryLoadButton() {
        return btnRetryLoad;
    }

    public JComboBox<String> getCashOperationComboBox() {
        return cmbCashOperation;
    }

    private static final class DashboardSnapshot {
        private final User user;
        private final Wallet wallet;
        private final List<Transaction> transactions;

        private DashboardSnapshot(User user, Wallet wallet, List<Transaction> transactions) {
            this.user = user;
            this.wallet = wallet;
            this.transactions = transactions;
        }
    }
}
