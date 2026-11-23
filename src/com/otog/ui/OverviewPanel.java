package com.otog.ui;

import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import com.intellij.uiDesigner.core.Spacer;
import com.otog.model.TransactionStatus;
import com.otog.model.TransactionType;
import com.otog.util.OtogConfig;
import com.otog.util.UiTheme;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;

/**
 * Bound IntelliJ Swing form panel for the Dashboard Overview tab:
 * Quick Actions, Statement File Export/Import buttons, Transaction History Filters,
 * and Recent Activity state cards.
 */
public class OverviewPanel extends JPanel {

    public static final String ALL_TYPES_OPTION = "All Types";
    public static final String ALL_STATUSES_OPTION = "All Statuses";

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

    public OverviewPanel() {
        super(new BorderLayout());
        if (pnlRoot == null) {
            $$$setupUI$$$();
        }
        add(pnlRoot, BorderLayout.CENTER);
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
        btnNavInvest.setVisible(false);
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
        btnExportStatement.setToolTipText("Export wallet statement and recent activity to a file");
        pnlQuickActions.add(btnExportStatement, new GridConstraints(0, 6, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        btnLoadStatement = new JButton("Load Statement");
        btnLoadStatement.setToolTipText("Load and inspect a saved wallet statement file");
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
        lblType.setFont(UiTheme.font(UiTheme.FontRole.SMALL));
        pnlHistoryFilters.add(lblType, new GridConstraints(0, 0, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        cmbFilterType = new JComboBox<>();
        cmbFilterType.addItem(ALL_TYPES_OPTION);
        for (TransactionType type : TransactionType.values()) {
            cmbFilterType.addItem(type.name());
        }
        pnlHistoryFilters.add(cmbFilterType, new GridConstraints(0, 1, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, new Dimension(130, -1), null, 0, false));

        JLabel lblStatus = new JLabel("Status:");
        lblStatus.setFont(UiTheme.font(UiTheme.FontRole.SMALL));
        pnlHistoryFilters.add(lblStatus, new GridConstraints(0, 2, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        cmbFilterStatus = new JComboBox<>();
        cmbFilterStatus.addItem(ALL_STATUSES_OPTION);
        for (TransactionStatus status : TransactionStatus.values()) {
            cmbFilterStatus.addItem(status.name());
        }
        pnlHistoryFilters.add(cmbFilterStatus, new GridConstraints(0, 3, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, new Dimension(110, -1), null, 0, false));

        JLabel lblFrom = new JLabel("From:");
        lblFrom.setFont(UiTheme.font(UiTheme.FontRole.SMALL));
        pnlHistoryFilters.add(lblFrom, new GridConstraints(0, 4, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        txtFilterFromDate = new JTextField(9);
        txtFilterFromDate.setToolTipText("Start date (YYYY-MM-DD)");
        pnlHistoryFilters.add(txtFilterFromDate, new GridConstraints(0, 5, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, new Dimension(92, -1), null, 0, false));

        JLabel lblTo = new JLabel("To:");
        lblTo.setFont(UiTheme.font(UiTheme.FontRole.SMALL));
        pnlHistoryFilters.add(lblTo, new GridConstraints(0, 6, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        txtFilterToDate = new JTextField(9);
        txtFilterToDate.setToolTipText("End date (YYYY-MM-DD)");
        pnlHistoryFilters.add(txtFilterToDate, new GridConstraints(0, 7, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, new Dimension(92, -1), null, 0, false));

        btnApplyFilters = new JButton("Filter");
        btnApplyFilters.setToolTipText("Filter owned transaction history by date, type, and status");
        pnlHistoryFilters.add(btnApplyFilters, new GridConstraints(0, 8, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        btnClearFilters = new JButton("Reset");
        btnClearFilters.setToolTipText("Clear active filters and show recent activity");
        pnlHistoryFilters.add(btnClearFilters, new GridConstraints(0, 9, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        pnlHistoryFilters.add(new Spacer(), new GridConstraints(0, 10, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_WANT_GROW, 1,
                null, null, null, 0, false));

        activityCardLayout = new CardLayout();
        activityCardPanel = new JPanel(activityCardLayout);

        pnlLoadingCard = new JPanel(new GridBagLayout());
        lblLoadingState = new JLabel("Loading wallet balance and recent activity...");
        lblLoadingState.setFont(UiTheme.font(UiTheme.FontRole.BODY_ITALIC));
        pnlLoadingCard.add(lblLoadingState);

        pnlEmptyCard = new JPanel(new GridBagLayout());
        lblEmptyState = new JLabel("No recent transactions yet. Use Cash In or Send Money to get started.");
        lblEmptyState.setFont(UiTheme.font(UiTheme.FontRole.BODY));
        lblEmptyState.setForeground(UiTheme.color(UiTheme.ColorRole.MUTED));
        pnlEmptyCard.add(lblEmptyState);

        pnlErrorCard = new JPanel(new GridBagLayout());
        pnlErrorInner = new JPanel(new BorderLayout(8, 10));
        lblErrorState = new JLabel("Unable to load wallet activity.", SwingConstants.CENTER);
        lblErrorState.setFont(UiTheme.font(UiTheme.FontRole.BODY));
        lblErrorState.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
        btnRetryLoad = new JButton("Retry");
        pnlRetryWrapper = new JPanel(new FlowLayout(FlowLayout.CENTER));
        pnlRetryWrapper.add(btnRetryLoad);
        pnlErrorInner.add(lblErrorState, BorderLayout.CENTER);
        pnlErrorInner.add(pnlRetryWrapper, BorderLayout.SOUTH);
        pnlErrorCard.add(pnlErrorInner);

        String[] columns = {"Date", "Ref #", "Type", "Direction", "Amount", "Fee", "Status", "Provider Ref", "Description"};
        transactionsTableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        tblTransactions = new JTable(transactionsTableModel);
        tblTransactions.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        tblTransactions.setRowHeight(OtogConfig.getInstance().getTableRowHeight());
        tblTransactions.getTableHeader().setReorderingAllowed(false);

        pnlReadyCard = new JPanel(new BorderLayout(6, 6));
        pnlReadyCard.add(new JScrollPane(tblTransactions), BorderLayout.CENTER);

        pnlTableActions = new JPanel(new BorderLayout(8, 0));
        lblActivityFeedback = new JLabel(" ");
        lblActivityFeedback.setFont(UiTheme.font(UiTheme.FontRole.LABEL));
        pnlTableActionButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        btnCheckStatus = new JButton("Lookup Status");
        btnCheckStatus.setEnabled(false);
        btnRepeatDraft = new JButton("Repeat Draft");
        btnRepeatDraft.setToolTipText("Clone selected transaction into a fresh repeat draft with a new reference number");
        btnRepeatDraft.setEnabled(false);
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
