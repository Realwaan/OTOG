package com.otog.ui;

import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import com.intellij.uiDesigner.core.Spacer;
import com.otog.model.Transaction;
import com.otog.model.TransactionFilter;
import com.otog.model.TransactionStatus;
import com.otog.model.TransactionType;
import com.otog.model.User;
import com.otog.model.Wallet;
import com.otog.service.SessionManager;
import com.otog.service.StatementFileService;
import com.otog.service.WalletOperations;
import com.otog.util.MoneyUtil;
import com.otog.util.OtogConfig;
import com.otog.util.SwingUiSupport;
import com.otog.util.UiTheme;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Bound IntelliJ Swing form panel for the Dashboard Overview tab:
 * Quick Actions, Statement File Export/Import buttons, Transaction History Filters,
 * Recent Activity state cards, receipt inspection, repeat drafts, and status lookup.
 */
public class OverviewPanel extends JPanel {

    public static final String ALL_TYPES_OPTION = "All Types";
    public static final String ALL_STATUSES_OPTION = "All Statuses";

    private static final String[] TRANSACTION_COLUMNS = {
            "Date", "Ref #", "Type", "Direction", "Amount", "Fee", "Status", "Provider Ref", "Description"
    };
    private static final Map<TransactionType, String> FIXED_DIRECTION_BY_TYPE = createDirectionMap();

    private final WalletOperations walletService;
    private final StatementFileService statementFileService;
    private final DateTimeFormatter dateFormatter =
            DateTimeFormatter.ofPattern(OtogConfig.getInstance().getUiDatePattern());

    private JPanel pnlRoot;
    private JPanel pnlQuickActions;
    private JPanel pnlRecentActivity;
    private JPanel pnlHistoryFilters;

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
    private JPanel pnlLoadingCard;
    private JPanel pnlEmptyCard;
    private JPanel pnlErrorCard;
    private JPanel pnlErrorInner;
    private JPanel pnlRetryWrapper;
    private JPanel pnlReadyCard;
    private JPanel pnlTableActions;
    private JPanel pnlTableActionButtons;
    private JLabel lblLoadingState;
    private JLabel lblEmptyState;
    private JLabel lblErrorState;
    private JButton btnRetryLoad;
    private DefaultTableModel transactionsTableModel;
    private JTable tblTransactions;
    private JButton btnCheckStatus;
    private JButton btnRepeatDraft;
    private JButton btnViewReceipt;
    private JButton btnExportReceipt;
    private JLabel lblActivityFeedback;

    private List<Transaction> loadedTransactions = Collections.emptyList();
    private volatile TransactionFilter activeHistoryFilter = TransactionFilter.empty();
    private boolean investmentsEnabled;

    private Runnable onQuickNavCashIn;
    private Runnable onQuickNavCashOut;
    private Runnable onQuickNavSendMoney;
    private Runnable onQuickNavPayBills;
    private Runnable onQuickNavInvest;
    private Runnable onQuickNavPinSettings;
    private BooleanSupplier beforeInteractiveAction;
    private Runnable onRetryLoad;
    private Supplier<CompletableFuture<Void>> onRefreshRequested;
    private Consumer<String> onStatusMessage;
    private Consumer<String> onOperationError;
    private BiConsumer<String, Color> onStatusLookupFeedback;
    private Consumer<Transaction> onRepeatDraftReady;
    private SwingUiSupport.DialogMessageCallback dialogMessageCallback = SwingUiSupport.defaultMessageCallback();
    private SwingUiSupport.ComponentMessageCallback componentMessageCallback = SwingUiSupport.defaultComponentMessageCallback();

    public OverviewPanel() {
        this(null, new StatementFileService());
    }

    public OverviewPanel(WalletOperations walletService, StatementFileService statementFileService) {
        super(new BorderLayout());
        this.walletService = walletService;
        this.statementFileService = statementFileService != null
                ? statementFileService
                : new StatementFileService();
        if (pnlRoot == null) {
            $$$setupUI$$$();
        }
        ensureComponentsInitialized();
        add(pnlRoot, BorderLayout.CENTER);
        initInteractions();
    }

    /**
     * Initializes CardLayout, table models, combo box entries, tooltips, and UiTheme styling
     * outside {@code $$$setupUI$$$()} so IntelliJ GUI Designer bytecode instrumentation never
     * leaves {@code activityCardLayout} or {@code transactionsTableModel} null or drops table styles.
     */
    private void ensureComponentsInitialized() {
        if (activityCardPanel != null) {
            LayoutManager lm = activityCardPanel.getLayout();
            if (lm instanceof CardLayout cl) {
                activityCardLayout = cl;
            } else {
                activityCardLayout = new CardLayout();
                activityCardPanel.setLayout(activityCardLayout);
                if (pnlLoadingCard != null) {
                    activityCardPanel.add(pnlLoadingCard, Main.DashboardState.LOADING.name());
                }
                if (pnlEmptyCard != null) {
                    activityCardPanel.add(pnlEmptyCard, Main.DashboardState.EMPTY.name());
                }
                if (pnlErrorCard != null) {
                    activityCardPanel.add(pnlErrorCard, Main.DashboardState.ERROR.name());
                }
                if (pnlReadyCard != null) {
                    activityCardPanel.add(pnlReadyCard, Main.DashboardState.READY.name());
                }
            }
        }
        if (cmbFilterType != null) {
            if (cmbFilterType.getItemCount() == 0) {
                cmbFilterType.addItem(ALL_TYPES_OPTION);
                for (TransactionType type : TransactionType.values()) {
                    cmbFilterType.addItem(type.name());
                }
            }
            UiTheme.styleInputField(cmbFilterType);
        }
        if (cmbFilterStatus != null) {
            if (cmbFilterStatus.getItemCount() == 0) {
                cmbFilterStatus.addItem(ALL_STATUSES_OPTION);
                for (TransactionStatus status : TransactionStatus.values()) {
                    cmbFilterStatus.addItem(status.name());
                }
            }
            UiTheme.styleInputField(cmbFilterStatus);
        }
        if (txtFilterFromDate != null) {
            txtFilterFromDate.setToolTipText("Start date (YYYY-MM-DD)");
            UiTheme.styleInputField(txtFilterFromDate);
        }
        if (txtFilterToDate != null) {
            txtFilterToDate.setToolTipText("End date (YYYY-MM-DD)");
            UiTheme.styleInputField(txtFilterToDate);
        }
        if (transactionsTableModel == null) {
            transactionsTableModel = new DefaultTableModel(TRANSACTION_COLUMNS, 0) {
                @Override
                public boolean isCellEditable(int row, int column) {
                    return false;
                }
            };
        }
        if (tblTransactions != null) {
            if (tblTransactions.getModel() != transactionsTableModel) {
                tblTransactions.setModel(transactionsTableModel);
            }
            UiTheme.styleTable(tblTransactions, 115, 130, 95, 70, 95, 75, 125, 120, 175);
            UiTheme.alignTableColumnsRight(tblTransactions, 4, 5);
        }

        UiTheme.styleSecondaryButton(btnNavCashIn);
        UiTheme.styleSecondaryButton(btnNavCashOut);
        UiTheme.styleSecondaryButton(btnNavSendMoney);
        UiTheme.styleSecondaryButton(btnNavPayBills);
        if (btnNavInvest != null) {
            btnNavInvest.setVisible(investmentsEnabled);
            UiTheme.styleSecondaryButton(btnNavInvest);
        }
        UiTheme.styleSecondaryButton(btnNavPinSettings);
        if (btnExportStatement != null) {
            btnExportStatement.setToolTipText("Export wallet statement and recent activity to a file");
            UiTheme.styleSecondaryButton(btnExportStatement);
        }
        if (btnLoadStatement != null) {
            btnLoadStatement.setToolTipText("Load and inspect a saved wallet statement file");
            UiTheme.styleSecondaryButton(btnLoadStatement);
        }
        if (btnApplyFilters != null) {
            btnApplyFilters.setToolTipText("Filter owned transaction history by date, type, and status");
            UiTheme.stylePrimaryButton(btnApplyFilters);
        }
        if (btnClearFilters != null) {
            btnClearFilters.setToolTipText("Clear active filters and show recent activity");
            UiTheme.styleSecondaryButton(btnClearFilters);
        }
        UiTheme.styleSecondaryButton(btnRetryLoad);
        if (btnCheckStatus != null) {
            btnCheckStatus.setEnabled(false);
            UiTheme.styleSecondaryButton(btnCheckStatus);
        }
        if (btnRepeatDraft != null) {
            btnRepeatDraft.setToolTipText("Clone selected transaction into a fresh repeat draft with a new reference number");
            btnRepeatDraft.setEnabled(false);
            UiTheme.styleSecondaryButton(btnRepeatDraft);
        }
        UiTheme.styleSecondaryButton(btnViewReceipt);
        UiTheme.styleSecondaryButton(btnExportReceipt);

        if (lblLoadingState != null) {
            lblLoadingState.setFont(UiTheme.font(UiTheme.FontRole.BODY_ITALIC));
        }
        if (lblEmptyState != null) {
            lblEmptyState.setFont(UiTheme.font(UiTheme.FontRole.BODY));
            lblEmptyState.setForeground(UiTheme.color(UiTheme.ColorRole.MUTED));
        }
        if (lblErrorState != null) {
            lblErrorState.setFont(UiTheme.font(UiTheme.FontRole.BODY));
            lblErrorState.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
        }
        if (lblActivityFeedback != null) {
            lblActivityFeedback.setFont(UiTheme.font(UiTheme.FontRole.LABEL));
        }
    }

    public void setOnQuickNavCashIn(Runnable onQuickNavCashIn) {
        this.onQuickNavCashIn = onQuickNavCashIn;
    }

    public void setOnQuickNavCashOut(Runnable onQuickNavCashOut) {
        this.onQuickNavCashOut = onQuickNavCashOut;
    }

    public void setOnQuickNavSendMoney(Runnable onQuickNavSendMoney) {
        this.onQuickNavSendMoney = onQuickNavSendMoney;
    }

    public void setOnQuickNavPayBills(Runnable onQuickNavPayBills) {
        this.onQuickNavPayBills = onQuickNavPayBills;
    }

    public void setOnQuickNavInvest(Runnable onQuickNavInvest) {
        this.onQuickNavInvest = onQuickNavInvest;
    }

    public void setInvestmentsEnabled(boolean enabled) {
        this.investmentsEnabled = enabled;
        if (btnNavInvest != null) {
            btnNavInvest.setVisible(enabled);
        }
        updateTableActionButtonsState();
    }

    public void setOnQuickNavPinSettings(Runnable onQuickNavPinSettings) {
        this.onQuickNavPinSettings = onQuickNavPinSettings;
    }

    public void setPinSettingsEnabled(boolean enabled) {
        if (btnNavPinSettings != null) {
            btnNavPinSettings.setEnabled(enabled);
        }
    }

    public void setBeforeInteractiveAction(BooleanSupplier beforeInteractiveAction) {
        this.beforeInteractiveAction = beforeInteractiveAction;
    }

    public void setOnRetryLoad(Runnable onRetryLoad) {
        this.onRetryLoad = onRetryLoad;
    }

    public void setOnRefreshRequested(Supplier<CompletableFuture<Void>> onRefreshRequested) {
        this.onRefreshRequested = onRefreshRequested;
    }

    public void setOnStatusMessage(Consumer<String> onStatusMessage) {
        this.onStatusMessage = onStatusMessage;
    }

    public void setOnOperationError(Consumer<String> onOperationError) {
        this.onOperationError = onOperationError;
    }

    public void setOnStatusLookupFeedback(BiConsumer<String, Color> onStatusLookupFeedback) {
        this.onStatusLookupFeedback = onStatusLookupFeedback;
    }

    public void setOnRepeatDraftReady(Consumer<Transaction> onRepeatDraftReady) {
        this.onRepeatDraftReady = onRepeatDraftReady;
    }

    public void setDialogMessageCallback(SwingUiSupport.DialogMessageCallback dialogMessageCallback) {
        this.dialogMessageCallback = dialogMessageCallback != null
                ? dialogMessageCallback
                : SwingUiSupport.defaultMessageCallback();
    }

    public void setComponentMessageCallback(SwingUiSupport.ComponentMessageCallback componentMessageCallback) {
        this.componentMessageCallback = componentMessageCallback != null
                ? componentMessageCallback
                : SwingUiSupport.defaultComponentMessageCallback();
    }

    private void initInteractions() {
        btnNavCashIn.addActionListener(e -> {
            if (onQuickNavCashIn != null) {
                onQuickNavCashIn.run();
            }
        });
        btnNavCashOut.addActionListener(e -> {
            if (onQuickNavCashOut != null) {
                onQuickNavCashOut.run();
            }
        });
        btnNavSendMoney.addActionListener(e -> {
            if (onQuickNavSendMoney != null) {
                onQuickNavSendMoney.run();
            }
        });
        if (btnNavPayBills != null) {
            btnNavPayBills.addActionListener(e -> {
                if (onQuickNavPayBills != null) {
                    onQuickNavPayBills.run();
                }
            });
        }
        if (btnNavInvest != null) {
            btnNavInvest.addActionListener(e -> {
                if (onQuickNavInvest != null) {
                    onQuickNavInvest.run();
                }
            });
        }
        btnNavPinSettings.addActionListener(e -> {
            if (onQuickNavPinSettings != null) {
                onQuickNavPinSettings.run();
            }
        });

        btnExportStatement.addActionListener(e -> handleInteractiveExportStatement());
        btnLoadStatement.addActionListener(e -> handleInteractiveLoadStatement());

        btnRetryLoad.addActionListener(e -> {
            if (onRetryLoad != null) {
                onRetryLoad.run();
            }
        });
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
        btnClearFilters.addActionListener(e -> handleClearHistoryFilters());
        txtFilterFromDate.addActionListener(e -> handleApplyHistoryFilters());
        txtFilterToDate.addActionListener(e -> handleApplyHistoryFilters());
    }

    public TransactionFilter getActiveHistoryFilter() {
        return activeHistoryFilter;
    }

    public void setActiveHistoryFilter(TransactionFilter filter) {
        this.activeHistoryFilter = filter != null ? filter : TransactionFilter.empty();
    }

    public void resetFilterControls() {
        this.activeHistoryFilter = TransactionFilter.empty();
        SwingUiSupport.runOnEdt(() -> {
            if (cmbFilterType != null && cmbFilterType.getItemCount() > 0) {
                cmbFilterType.setSelectedIndex(0);
            }
            if (cmbFilterStatus != null && cmbFilterStatus.getItemCount() > 0) {
                cmbFilterStatus.setSelectedIndex(0);
            }
            if (txtFilterFromDate != null) {
                txtFilterFromDate.setText("");
            }
            if (txtFilterToDate != null) {
                txtFilterToDate.setText("");
            }
        });
    }

    public void showLoadingState(String message) {
        lblLoadingState.setText(message);
        activityCardLayout.show(activityCardPanel, Main.DashboardState.LOADING.name());
    }

    public void showEmptyState(boolean filtered) {
        lblEmptyState.setText(filtered
                ? "No transactions match the selected filters."
                : "No recent transactions yet. Use Cash In or Send Money to get started.");
        activityCardLayout.show(activityCardPanel, Main.DashboardState.EMPTY.name());
    }

    public void showErrorState(String message) {
        lblErrorState.setText("Unable to load wallet data: " + message);
        activityCardLayout.show(activityCardPanel, Main.DashboardState.ERROR.name());
    }

    public void showReadyState() {
        activityCardLayout.show(activityCardPanel, Main.DashboardState.READY.name());
    }

    public void setRetryEnabled(boolean enabled) {
        if (btnRetryLoad != null) {
            btnRetryLoad.setEnabled(enabled);
        }
    }

    public void populateTransactions(String walletId, List<Transaction> transactions) {
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

    private static String resolveDirection(String walletId, Transaction txn) {
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
    public Transaction getSelectedTransaction() {
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

    public void updateTableActionButtonsState() {
        Transaction selected = getSelectedTransaction();
        if (btnCheckStatus != null) {
            boolean isCashTxn = selected != null
                    && (selected.getType() == TransactionType.CASH_IN
                    || selected.getType() == TransactionType.CASH_OUT
                    || selected.getType() == TransactionType.BILL_PAYMENT);
            btnCheckStatus.setEnabled(isCashTxn);
        }
        if (btnRepeatDraft != null) {
            boolean isInvestRepeat = investmentsEnabled
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

    private void handleClearHistoryFilters() {
        if (!ensureInteractiveSession()) {
            return;
        }
        clearHistoryFiltersAsync();
    }

    private void handleApplyHistoryFilters() {
        if (!ensureInteractiveSession()) {
            return;
        }
        try {
            TransactionFilter.Builder builder = TransactionFilter.builder();
            String selectedType = cmbFilterType != null ? (String) cmbFilterType.getSelectedItem() : null;
            if (selectedType != null && !ALL_TYPES_OPTION.equals(selectedType)) {
                builder.type(TransactionType.valueOf(selectedType));
            }
            String selectedStatus = cmbFilterStatus != null ? (String) cmbFilterStatus.getSelectedItem() : null;
            if (selectedStatus != null && !ALL_STATUSES_OPTION.equals(selectedStatus)) {
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
            notifyStatus("Filter validation failed: " + msg);
            showMessage("Invalid Date Filter", msg, JOptionPane.WARNING_MESSAGE);
        } catch (IllegalArgumentException ex) {
            String msg = SwingUiSupport.extractErrorMessage(ex);
            notifyStatus("Filter validation failed: " + msg);
            showMessage("Invalid History Filter", msg, JOptionPane.WARNING_MESSAGE);
        }
    }

    public CompletableFuture<Void> applyHistoryFiltersAsync(TransactionFilter filter) {
        setActiveHistoryFilter(filter);
        return triggerRefresh();
    }

    public CompletableFuture<Void> clearHistoryFiltersAsync() {
        resetFilterControls();
        return triggerRefresh();
    }

    public CompletableFuture<Transaction> submitStatusLookupAsync(String referenceNumber) {
        CompletableFuture<Transaction> future = new CompletableFuture<>();
        if (walletService == null) {
            future.completeExceptionally(new IllegalStateException("WalletOperations service is unavailable."));
            return future;
        }
        SwingUiSupport.runOnEdt(() -> {
            if (btnCheckStatus != null) {
                btnCheckStatus.setEnabled(false);
            }
            notifyStatus("Checking bank status for " + referenceNumber + "...");
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
                    String provRef = txn.getProviderReference() != null
                            ? " | Bank Ref: " + txn.getProviderReference() : "";
                    String provStatus = txn.getProviderStatus() != null
                            ? " [" + txn.getProviderStatus() + "]" : "";
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
                    if (onStatusLookupFeedback != null) {
                        onStatusLookupFeedback.accept(msg, color);
                    }
                    if (lblActivityFeedback != null) {
                        lblActivityFeedback.setForeground(color);
                        lblActivityFeedback.setText(msg);
                    }
                    String syncStatusText = "Status sync: " + txn.getStatus() + provStatus;
                    notifyStatus(syncStatusText);
                    triggerRefresh().whenComplete((v, err) -> {
                        SwingUiSupport.runOnEdt(() -> notifyStatus(syncStatusText));
                        future.complete(txn);
                    });
                } catch (Exception ex) {
                    Throwable cause = SwingUiSupport.unwrapCause(ex);
                    String message = SwingUiSupport.extractErrorMessage(cause);
                    if (lblActivityFeedback != null) {
                        lblActivityFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
                        lblActivityFeedback.setText("Status lookup failed: " + message);
                    }
                    notifyStatus("Status lookup failed: " + message);
                    notifyOperationError(message);
                    future.completeExceptionally(cause);
                }
            }
        };
        worker.execute();
        return future;
    }

    private void lookupSelectedTransactionStatus() {
        if (!ensureInteractiveSession()) {
            return;
        }
        Transaction txn = getSelectedTransaction();
        if (txn == null) {
            showMessage("No Transaction Selected",
                    "Please select a transaction row to check its bank status.",
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
            notifyStatus(msg);
            return;
        }
        submitStatusLookupAsync(txn.getReferenceNumber());
    }

    private void showSelectedReceipt() {
        if (!ensureInteractiveSession()) {
            return;
        }
        Transaction txn = getSelectedTransaction();
        if (txn == null) {
            showMessage("No Transaction Selected",
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
                if (walletService != null && walletId != null && refKey != null) {
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
                    componentMessageCallback.show(OverviewPanel.this,
                            "Transaction Receipt \u2014 " + txn.getReferenceNumber(),
                            scroll,
                            JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception ex) {
                    Throwable cause = SwingUiSupport.unwrapCause(ex);
                    String message = SwingUiSupport.extractErrorMessage(cause);
                    notifyOperationError(message);
                    showMessage("Receipt Error", message, JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void handleRepeatSelectedTransactionDraft() {
        if (!ensureInteractiveSession()) {
            return;
        }
        createRepeatDraftForSelectedAsync().whenComplete((draft, err) -> SwingUiSupport.runOnEdt(() -> {
            if (err != null) {
                Throwable cause = SwingUiSupport.unwrapCause(err);
                showMessage("Repeat Draft Failed", SwingUiSupport.extractErrorMessage(cause),
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
        if (walletService == null) {
            future.completeExceptionally(new IllegalStateException("WalletOperations service is unavailable."));
            return future;
        }
        Transaction selected = getSelectedTransaction();
        if (selected == null) {
            future.completeExceptionally(new IllegalStateException("Please select a transaction to repeat."));
            return future;
        }
        Wallet wallet = SessionManager.getInstance().getCurrentWallet();
        if (wallet == null) {
            future.completeExceptionally(new IllegalStateException("Active wallet is required."));
            return future;
        }
        String key = selected.getReferenceNumber() != null
                ? selected.getReferenceNumber() : selected.getTransactionId();

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
                    if (onRepeatDraftReady != null) {
                        onRepeatDraftReady.accept(draft);
                    }
                    future.complete(draft);
                } catch (Exception ex) {
                    Throwable cause = SwingUiSupport.unwrapCause(ex);
                    notifyOperationError(SwingUiSupport.extractErrorMessage(cause));
                    future.completeExceptionally(cause);
                }
            }
        }.execute();
        return future;
    }

    private void handleInteractiveExportStatement() {
        if (!ensureInteractiveSession()) {
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
        if (!ensureInteractiveSession()) {
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Load Saved OTOG Wallet Statement");
        chooser.addChoosableFileFilter(new FileNameExtensionFilter("OTOG Statement Files (*.txt, *.csv)", "txt", "csv"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            importStatementAsync(chooser.getSelectedFile().toPath()).thenAccept(stmt -> {
                if (stmt != null) {
                    SwingUiSupport.runOnEdt(() -> {
                        JTextArea area = new JTextArea(stmt.toDisplaySummary(), 18, 68);
                        area.setEditable(false);
                        area.setFont(UiTheme.font(UiTheme.FontRole.MONO));
                        componentMessageCallback.show(OverviewPanel.this,
                                "Loaded Wallet Statement \u2014 " + stmt.getMaskedWalletId(),
                                new JScrollPane(area),
                                JOptionPane.INFORMATION_MESSAGE);
                    });
                }
            });
        }
    }

    private void handleInteractiveExportReceipt() {
        if (!ensureInteractiveSession()) {
            return;
        }
        Transaction selected = getSelectedTransaction();
        if (selected == null) {
            showMessage("No Transaction Selected",
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
                Wallet latestWallet = (walletService != null && wallet != null && user != null)
                        ? walletService.getWalletByUserId(user.getUserId())
                        : wallet;
                return statementFileService.exportWalletStatement(user, latestWallet, snapshotTxns, targetFile);
            }

            @Override
            protected void done() {
                try {
                    Path saved = get();
                    notifyStatus("Statement exported to " + saved.getFileName());
                    if (lblActivityFeedback != null) {
                        lblActivityFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.SUCCESS));
                        lblActivityFeedback.setText("Exported statement: " + saved.getFileName());
                    }
                    future.complete(saved);
                } catch (Exception ex) {
                    Throwable cause = SwingUiSupport.unwrapCause(ex);
                    String msg = SwingUiSupport.extractErrorMessage(cause);
                    notifyStatus("Statement export failed: " + msg);
                    notifyOperationError(msg);
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
                    notifyStatus("Loaded statement (" + loaded.getEntries().size() + " transactions).");
                    if (lblActivityFeedback != null) {
                        lblActivityFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.SUCCESS));
                        lblActivityFeedback.setText("Loaded statement for wallet " + loaded.getMaskedWalletId());
                    }
                    future.complete(loaded);
                } catch (Exception ex) {
                    Throwable cause = SwingUiSupport.unwrapCause(ex);
                    String msg = SwingUiSupport.extractErrorMessage(cause);
                    notifyStatus("Statement load failed: " + msg);
                    notifyOperationError(msg);
                    future.completeExceptionally(cause);
                }
            }
        }.execute();
        return future;
    }

    public CompletableFuture<Path> exportSelectedReceiptAsync(Path targetFile) {
        CompletableFuture<Path> future = new CompletableFuture<>();
        Transaction selected = getSelectedTransaction();
        new SwingWorker<Path, Void>() {
            @Override
            protected Path doInBackground() {
                return statementFileService.exportReceipt(selected, targetFile);
            }

            @Override
            protected void done() {
                try {
                    Path saved = get();
                    notifyStatus("Receipt saved to " + saved.getFileName());
                    future.complete(saved);
                } catch (Exception ex) {
                    Throwable cause = SwingUiSupport.unwrapCause(ex);
                    String msg = SwingUiSupport.extractErrorMessage(cause);
                    notifyStatus("Receipt save failed: " + msg);
                    notifyOperationError(msg);
                    future.completeExceptionally(cause);
                }
            }
        }.execute();
        return future;
    }

    private boolean ensureInteractiveSession() {
        return beforeInteractiveAction == null || beforeInteractiveAction.getAsBoolean();
    }

    private CompletableFuture<Void> triggerRefresh() {
        if (onRefreshRequested != null) {
            CompletableFuture<Void> refresh = onRefreshRequested.get();
            if (refresh != null) {
                return refresh;
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    private void showMessage(String title, String message, int messageType) {
        dialogMessageCallback.show(this, title, message, messageType);
    }

    private void notifyStatus(String message) {
        if (onStatusMessage != null) {
            onStatusMessage.accept(message);
        }
    }

    private void notifyOperationError(String message) {
        if (onOperationError != null) {
            onOperationError.accept(message);
        }
    }

    public JPanel getRootPanel() {
        return pnlRoot;
    }

    public JButton getNavCashInButton() {
        return btnNavCashIn;
    }

    public JButton getNavCashOutButton() {
        return btnNavCashOut;
    }

    public JButton getNavSendMoneyButton() {
        return btnNavSendMoney;
    }

    public JButton getNavPayBillsButton() {
        return btnNavPayBills;
    }

    public JButton getNavInvestButton() {
        return btnNavInvest;
    }

    public JButton getNavPinSettingsButton() {
        return btnNavPinSettings;
    }

    public JButton getExportStatementButton() {
        return btnExportStatement;
    }

    public JButton getLoadStatementButton() {
        return btnLoadStatement;
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

    public CardLayout getActivityCardLayout() {
        return activityCardLayout;
    }

    public JPanel getActivityCardPanel() {
        return activityCardPanel;
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

    public JButton getRetryLoadButton() {
        return btnRetryLoad;
    }

    public DefaultTableModel getTransactionsTableModel() {
        return transactionsTableModel;
    }

    public JTable getTransactionsTable() {
        return tblTransactions;
    }

    public JButton getCheckStatusButton() {
        return btnCheckStatus;
    }

    public JButton getRepeatDraftButton() {
        return btnRepeatDraft;
    }

    public JButton getViewReceiptButton() {
        return btnViewReceipt;
    }

    public JButton getExportReceiptButton() {
        return btnExportReceipt;
    }

    public JLabel getActivityFeedbackLabel() {
        return lblActivityFeedback;
    }

    private void $$$setupUI$$$() {
        pnlRoot = new JPanel();
        pnlRoot.setLayout(new GridLayoutManager(2, 1, new Insets(10, 8, 6, 8), 10, 12));

        pnlQuickActions = new JPanel();
        pnlQuickActions.setLayout(new GridLayoutManager(1, 9, new Insets(6, 8, 6, 8), 8, 4));
        pnlQuickActions.setBorder(UiTheme.titledBorder("Quick Actions & Statement Files"));
        pnlRoot.add(pnlQuickActions, new GridConstraints(0, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        btnNavCashIn = new JButton("Cash In");
        pnlQuickActions.add(btnNavCashIn, new GridConstraints(0, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        btnNavCashOut = new JButton("Cash Out");
        pnlQuickActions.add(btnNavCashOut, new GridConstraints(0, 1, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        btnNavSendMoney = new JButton("Send Money");
        pnlQuickActions.add(btnNavSendMoney, new GridConstraints(0, 2, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        btnNavPayBills = new JButton("Pay Bills");
        pnlQuickActions.add(btnNavPayBills, new GridConstraints(0, 3, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        btnNavInvest = new JButton("Investments");
        pnlQuickActions.add(btnNavInvest, new GridConstraints(0, 4, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        btnNavPinSettings = new JButton("Set / Change PIN");
        pnlQuickActions.add(btnNavPinSettings, new GridConstraints(0, 5, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        btnExportStatement = new JButton("Export Statement");
        pnlQuickActions.add(btnExportStatement, new GridConstraints(0, 6, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        btnLoadStatement = new JButton("Load Statement");
        pnlQuickActions.add(btnLoadStatement, new GridConstraints(0, 7, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        pnlQuickActions.add(new Spacer(), new GridConstraints(0, 8, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_WANT_GROW, 1,
                null, null, null, 0, false));

        pnlRecentActivity = new JPanel();
        pnlRecentActivity.setLayout(new GridLayoutManager(2, 1, new Insets(6, 8, 6, 8), 8, 8));
        pnlRecentActivity.setBorder(UiTheme.titledBorder("Recent Activity & History"));
        pnlRoot.add(pnlRecentActivity, new GridConstraints(1, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW,
                null, null, null, 0, false));

        pnlHistoryFilters = new JPanel();
        pnlHistoryFilters.setLayout(new GridLayoutManager(1, 11, new Insets(2, 2, 4, 2), 6, 0));
        pnlRecentActivity.add(pnlHistoryFilters, new GridConstraints(0, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        JLabel lblType = new JLabel("Type:");
        UiTheme.styleFormLabel(lblType);
        pnlHistoryFilters.add(lblType, new GridConstraints(0, 0, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        cmbFilterType = new JComboBox<>();
        pnlHistoryFilters.add(cmbFilterType, new GridConstraints(0, 1, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, new Dimension(130, -1), null, 0, false));

        JLabel lblStatus = new JLabel("Status:");
        UiTheme.styleFormLabel(lblStatus);
        pnlHistoryFilters.add(lblStatus, new GridConstraints(0, 2, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        cmbFilterStatus = new JComboBox<>();
        pnlHistoryFilters.add(cmbFilterStatus, new GridConstraints(0, 3, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, new Dimension(110, -1), null, 0, false));

        JLabel lblFrom = new JLabel("From:");
        UiTheme.styleFormLabel(lblFrom);
        pnlHistoryFilters.add(lblFrom, new GridConstraints(0, 4, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        txtFilterFromDate = new JTextField(9);
        pnlHistoryFilters.add(txtFilterFromDate, new GridConstraints(0, 5, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, new Dimension(92, -1), null, 0, false));

        JLabel lblTo = new JLabel("To:");
        UiTheme.styleFormLabel(lblTo);
        pnlHistoryFilters.add(lblTo, new GridConstraints(0, 6, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        txtFilterToDate = new JTextField(9);
        pnlHistoryFilters.add(txtFilterToDate, new GridConstraints(0, 7, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, new Dimension(92, -1), null, 0, false));

        btnApplyFilters = new JButton("Filter");
        pnlHistoryFilters.add(btnApplyFilters, new GridConstraints(0, 8, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        btnClearFilters = new JButton("Reset");
        pnlHistoryFilters.add(btnClearFilters, new GridConstraints(0, 9, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        pnlHistoryFilters.add(new Spacer(), new GridConstraints(0, 10, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_WANT_GROW, 1,
                null, null, null, 0, false));

        activityCardPanel = new JPanel(new CardLayout());

        pnlLoadingCard = new JPanel(new GridBagLayout());
        lblLoadingState = new JLabel("Loading wallet balance and recent activity...");
        pnlLoadingCard.add(lblLoadingState);

        pnlEmptyCard = new JPanel(new GridBagLayout());
        lblEmptyState = new JLabel("No recent transactions yet. Use Cash In or Send Money to get started.");
        pnlEmptyCard.add(lblEmptyState);

        pnlErrorCard = new JPanel(new GridBagLayout());
        pnlErrorInner = new JPanel(new BorderLayout(8, 10));
        lblErrorState = new JLabel("Unable to load wallet activity.", SwingConstants.CENTER);
        btnRetryLoad = new JButton("Retry");
        pnlRetryWrapper = new JPanel(new FlowLayout(FlowLayout.CENTER));
        pnlRetryWrapper.add(btnRetryLoad);
        pnlErrorInner.add(lblErrorState, BorderLayout.CENTER);
        pnlErrorInner.add(pnlRetryWrapper, BorderLayout.SOUTH);
        pnlErrorCard.add(pnlErrorInner);

        tblTransactions = new JTable();

        pnlReadyCard = new JPanel(new BorderLayout(6, 6));
        pnlReadyCard.add(new JScrollPane(tblTransactions), BorderLayout.CENTER);

        pnlTableActions = new JPanel(new BorderLayout(8, 0));
        lblActivityFeedback = new JLabel(" ");
        pnlTableActionButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        btnCheckStatus = new JButton("Lookup Status");
        btnRepeatDraft = new JButton("Repeat Draft");
        btnViewReceipt = new JButton("View Receipt");
        btnExportReceipt = new JButton("Save Receipt File");
        pnlTableActionButtons.add(btnCheckStatus);
        pnlTableActionButtons.add(btnRepeatDraft);
        pnlTableActionButtons.add(btnViewReceipt);
        pnlTableActionButtons.add(btnExportReceipt);
        pnlTableActions.add(lblActivityFeedback, BorderLayout.WEST);
        pnlTableActions.add(pnlTableActionButtons, BorderLayout.EAST);
        pnlReadyCard.add(pnlTableActions, BorderLayout.SOUTH);

        activityCardPanel.add(pnlLoadingCard, Main.DashboardState.LOADING.name());
        activityCardPanel.add(pnlEmptyCard, Main.DashboardState.EMPTY.name());
        activityCardPanel.add(pnlErrorCard, Main.DashboardState.ERROR.name());
        activityCardPanel.add(pnlReadyCard, Main.DashboardState.READY.name());

        pnlRecentActivity.add(activityCardPanel, new GridConstraints(1, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW,
                null, null, null, 0, false));
    }

    public JComponent $$$getRootComponent$$$() {
        return pnlRoot;
    }
}
