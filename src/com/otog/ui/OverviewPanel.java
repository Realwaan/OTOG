package com.otog.ui;

import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import com.intellij.uiDesigner.core.Spacer;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;

/**
 * Bound IntelliJ Swing form panel for the Dashboard Overview tab:
 * Quick Actions, Statement File Export/Import buttons, and Recent Activity state cards.
 */
public class OverviewPanel extends JPanel {

    private JPanel pnlRoot;
    private JPanel pnlQuickActions;
    private JPanel pnlRecentActivity;

    private JButton btnNavCashIn;
    private JButton btnNavCashOut;
    private JButton btnNavSendMoney;
    private JButton btnNavPinSettings;
    private JButton btnExportStatement;
    private JButton btnLoadStatement;

    private CardLayout activityCardLayout;
    private JPanel activityCardPanel;
    private JLabel lblLoadingState;
    private JLabel lblEmptyState;
    private JLabel lblErrorState;
    private JButton btnRetryLoad;
    private DefaultTableModel transactionsTableModel;
    private JTable tblTransactions;
    private JButton btnCheckStatus;
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

    public JButton getNavPinSettingsButton() {
        return btnNavPinSettings;
    }

    public JButton getExportStatementButton() {
        return btnExportStatement;
    }

    public JButton getLoadStatementButton() {
        return btnLoadStatement;
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
        pnlQuickActions.setLayout(new GridLayoutManager(1, 7, new Insets(6, 8, 6, 8), 8, 4));
        pnlQuickActions.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), "Quick Actions & Statement Files"));
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

        btnNavPinSettings = new JButton("Set / Change PIN");
        pnlQuickActions.add(btnNavPinSettings, new GridConstraints(0, 3, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        btnExportStatement = new JButton("Export Statement");
        btnExportStatement.setToolTipText("Export wallet statement and recent activity to a file");
        pnlQuickActions.add(btnExportStatement, new GridConstraints(0, 4, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        btnLoadStatement = new JButton("Load Statement");
        btnLoadStatement.setToolTipText("Load and inspect a saved wallet statement file");
        pnlQuickActions.add(btnLoadStatement, new GridConstraints(0, 5, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        pnlQuickActions.add(new Spacer(), new GridConstraints(0, 6, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_WANT_GROW, 1,
                null, null, null, 0, false));

        pnlRecentActivity = new JPanel();
        pnlRecentActivity.setLayout(new GridLayoutManager(1, 1, new Insets(6, 8, 6, 8), 8, 8));
        pnlRecentActivity.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), "Recent Activity"));
        pnlRoot.add(pnlRecentActivity, new GridConstraints(1, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW,
                null, null, null, 0, false));

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

        JPanel readyCard = new JPanel(new BorderLayout(6, 6));
        readyCard.add(new JScrollPane(tblTransactions), BorderLayout.CENTER);

        JPanel tableActions = new JPanel(new BorderLayout(8, 0));
        lblActivityFeedback = new JLabel(" ");
        lblActivityFeedback.setFont(new Font("SansSerif", Font.PLAIN, 12));
        JPanel tableActionButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        btnCheckStatus = new JButton("Lookup Status");
        btnCheckStatus.setEnabled(false);
        btnViewReceipt = new JButton("View Receipt");
        btnExportReceipt = new JButton("Save Receipt File");
        tableActionButtons.add(btnCheckStatus);
        tableActionButtons.add(btnViewReceipt);
        tableActionButtons.add(btnExportReceipt);
        tableActions.add(lblActivityFeedback, BorderLayout.WEST);
        tableActions.add(tableActionButtons, BorderLayout.EAST);
        readyCard.add(tableActions, BorderLayout.SOUTH);

        activityCardPanel.add(loadingCard, Main.DashboardState.LOADING.name());
        activityCardPanel.add(emptyCard, Main.DashboardState.EMPTY.name());
        activityCardPanel.add(errorCard, Main.DashboardState.ERROR.name());
        activityCardPanel.add(readyCard, Main.DashboardState.READY.name());

        pnlRecentActivity.add(activityCardPanel, new GridConstraints(0, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW,
                null, null, null, 0, false));
    }

    public JComponent $$$getRootComponent$$$() {
        return pnlRoot;
    }
}
