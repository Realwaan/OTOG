package com.otog.ui;

import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import com.intellij.uiDesigner.core.Spacer;
import com.otog.model.InvestMoneyTransaction;
import com.otog.service.InvestmentService;
import com.otog.util.MoneyUtil;
import com.otog.util.OtogConfig;
import com.otog.util.UiTheme;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Bound IntelliJ Swing form panel for CAP-13 Basic Simulated Investment Workflow.
 */
public class InvestmentPanel extends JPanel {

    private final DateTimeFormatter dateFormatter =
            DateTimeFormatter.ofPattern(OtogConfig.getInstance().getUiDatePattern());

    private JPanel pnlRoot;
    private JPanel pnlProductSummary;
    private JPanel pnlOrderForm;
    private JPanel pnlActions;
    private JPanel pnlActivity;

    private JLabel lblProductName;
    private JLabel lblSimulatedPrice;
    private JLabel lblHoldingsUnits;
    private JLabel lblHoldingsValue;

    private JComboBox<String> cmbInvestOperation;
    private JTextField txtInvestUnits;
    private JTextField txtInvestAmount;
    private JPasswordField txtInvestPin;
    private JLabel lblPreviewSummary;
    private JButton btnPreviewInvest;
    private JButton btnSubmitInvest;
    private JButton btnInvestBackToOverview;
    private JLabel lblInvestFeedback;

    private JScrollPane scrollInvestmentActivity;
    private DefaultTableModel activityTableModel;
    private JTable tblInvestmentActivity;

    public InvestmentPanel() {
        super(new BorderLayout());
        if (pnlRoot == null) {
            $$$setupUI$$$();
        }
        ensureInteractiveComponentsInitialized();
        add(pnlRoot, BorderLayout.CENTER);
    }

    private void ensureInteractiveComponentsInitialized() {
        OtogConfig config = OtogConfig.getInstance();
        String defaultCurrency = config.getDefaultCurrency();
        if (cmbInvestOperation != null && cmbInvestOperation.getItemCount() == 0) {
            cmbInvestOperation.addItem("BUY");
            cmbInvestOperation.addItem("REDEEM");
        }
        if (activityTableModel == null) {
            activityTableModel = new DefaultTableModel(
                    new Object[]{"Date", "Reference", "Type", "Product", "Units", "Amount", "Status", "Description"}, 0) {
                @Override
                public boolean isCellEditable(int row, int column) {
                    return false;
                }
            };
        }
        if (tblInvestmentActivity != null) {
            if (tblInvestmentActivity.getModel() != activityTableModel) {
                tblInvestmentActivity.setModel(activityTableModel);
            }
            tblInvestmentActivity.setRowHeight(config.getTableRowHeight());
            tblInvestmentActivity.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            tblInvestmentActivity.getTableHeader().setReorderingAllowed(false);
        }
        if (lblSimulatedPrice != null) {
            lblSimulatedPrice.setText(MoneyUtil.format(
                    config.getInvestmentSimulatedUnitPrice(), defaultCurrency) + " / unit [SIMULATED FIXED PRICE]");
        }
        if (lblHoldingsValue != null) {
            lblHoldingsValue.setText(MoneyUtil.format(BigDecimal.ZERO, defaultCurrency));
        }
        if (lblPreviewSummary != null) {
            lblPreviewSummary.setText("Enter units or " + defaultCurrency + " amount, then click Preview Units & Cost.");
        }
    }

    public JPanel getRootPanel() {
        return pnlRoot;
    }

    public JLabel getProductNameLabel() {
        return lblProductName;
    }

    public JLabel getSimulatedPriceLabel() {
        return lblSimulatedPrice;
    }

    public JLabel getHoldingsUnitsLabel() {
        return lblHoldingsUnits;
    }

    public JLabel getHoldingsValueLabel() {
        return lblHoldingsValue;
    }

    public JComboBox<String> getOperationComboBox() {
        return cmbInvestOperation;
    }

    public JTextField getUnitsField() {
        return txtInvestUnits;
    }

    public JTextField getAmountField() {
        return txtInvestAmount;
    }

    public JPasswordField getPinField() {
        return txtInvestPin;
    }

    public JLabel getPreviewSummaryLabel() {
        return lblPreviewSummary;
    }

    public JButton getPreviewButton() {
        return btnPreviewInvest;
    }

    public JButton getSubmitButton() {
        return btnSubmitInvest;
    }

    public JButton getBackToOverviewButton() {
        return btnInvestBackToOverview;
    }

    public JLabel getFeedbackLabel() {
        return lblInvestFeedback;
    }

    public JTable getActivityTable() {
        return tblInvestmentActivity;
    }

    public DefaultTableModel getActivityTableModel() {
        return activityTableModel;
    }

    public void updateProductAndHoldings(InvestmentService.SimulatedProduct product,
                                         InvestmentService.InvestmentHolding holding) {
        if (product != null) {
            lblProductName.setText(product.getProductName() + " (" + product.getProductCode() + ")");
            lblSimulatedPrice.setText(MoneyUtil.format(product.getSimulatedUnitPrice(), product.getCurrency())
                    + " / unit [SIMULATED FIXED PRICE]");
        }
        if (holding != null) {
            lblHoldingsUnits.setText(holding.getUnits().toPlainString() + " units");
            lblHoldingsValue.setText(MoneyUtil.format(holding.getSimulatedMarketValue(), holding.getCurrency()));
        } else {
            lblHoldingsUnits.setText("0.0000 units");
            lblHoldingsValue.setText(MoneyUtil.format(BigDecimal.ZERO, OtogConfig.getInstance().getDefaultCurrency()));
        }
    }

    public void updateActivityTable(List<InvestMoneyTransaction> activities) {
        activityTableModel.setRowCount(0);
        if (activities == null) {
            return;
        }
        for (InvestMoneyTransaction txn : activities) {
            String date = txn.getCreatedAt() != null ? txn.getCreatedAt().format(dateFormatter) : "-";
            String units = txn.getUnits() != null ? txn.getUnits().toPlainString() : "-";
            activityTableModel.addRow(new Object[]{
                    date,
                    txn.getReferenceNumber(),
                    txn.getType() != null ? txn.getType().name() : "-",
                    txn.getProduct() != null ? txn.getProduct() : "-",
                    units,
                    MoneyUtil.format(txn.getAmount(), txn.getCurrency()),
                    txn.getStatus() != null ? txn.getStatus().name() : "-",
                    txn.getDescription() != null ? txn.getDescription() : ""
            });
        }
    }

    private void $$$setupUI$$$() {
        OtogConfig config = OtogConfig.getInstance();
        pnlRoot = new JPanel();
        pnlRoot.setLayout(new GridLayoutManager(3, 1, new Insets(14, 14, 14, 14), 10, 10));

        pnlProductSummary = new JPanel();
        pnlProductSummary.setLayout(new GridLayoutManager(2, 4, new Insets(10, 12, 10, 12), 12, 6));
        pnlProductSummary.setBorder(UiTheme.titledBorder("Seeded Simulated Investment Product"));
        pnlRoot.add(pnlProductSummary, new GridConstraints(0, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        lblProductName = new JLabel(config.getInvestmentProductName() + " (" + config.getInvestmentProductCode() + ")");
        lblProductName.setFont(UiTheme.font(UiTheme.FontRole.SECTION_HEADER));

        lblSimulatedPrice = new JLabel(MoneyUtil.format(
                config.getInvestmentSimulatedUnitPrice(), config.getDefaultCurrency()) + " / unit [SIMULATED FIXED PRICE]");
        lblSimulatedPrice.setFont(UiTheme.font(UiTheme.FontRole.LABEL));
        lblSimulatedPrice.setForeground(UiTheme.color(UiTheme.ColorRole.PRIMARY));

        lblHoldingsUnits = new JLabel("0.0000 units");
        lblHoldingsUnits.setFont(UiTheme.font(UiTheme.FontRole.SECTION_HEADER));

        lblHoldingsValue = new JLabel(MoneyUtil.format(BigDecimal.ZERO, config.getDefaultCurrency()));
        lblHoldingsValue.setFont(UiTheme.font(UiTheme.FontRole.SECTION_HEADER));
        lblHoldingsValue.setForeground(UiTheme.color(UiTheme.ColorRole.SUCCESS));

        addSummaryCell(pnlProductSummary, 0, 0, "Product:", lblProductName);
        addSummaryCell(pnlProductSummary, 0, 2, "Simulated Price:", lblSimulatedPrice);
        addSummaryCell(pnlProductSummary, 1, 0, "Persisted Holdings:", lblHoldingsUnits);
        addSummaryCell(pnlProductSummary, 1, 2, "Simulated Market Value:", lblHoldingsValue);

        pnlOrderForm = new JPanel();
        pnlOrderForm.setLayout(new GridLayoutManager(7, 2, new Insets(10, 12, 10, 12), 10, 8));
        pnlOrderForm.setBorder(UiTheme.titledBorder("Simulated Purchase & Redemption"));
        pnlRoot.add(pnlOrderForm, new GridConstraints(1, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        cmbInvestOperation = new JComboBox<>(new String[]{"BUY", "REDEEM"});
        txtInvestUnits = new JTextField(18);
        txtInvestAmount = new JTextField(18);
        txtInvestPin = new JPasswordField(18);
        txtInvestPin.setToolTipText("Enter your Transaction PIN when PIN authorization is enabled");

        lblPreviewSummary = new JLabel("Enter units or " + config.getDefaultCurrency() + " amount, then click Preview Units & Cost.");
        lblPreviewSummary.setFont(UiTheme.font(UiTheme.FontRole.LABEL));
        lblPreviewSummary.setForeground(UiTheme.color(UiTheme.ColorRole.INFO));

        addFormRow(pnlOrderForm, 0, "Operation:", cmbInvestOperation);
        addFormRow(pnlOrderForm, 1, "Units (e.g. 2.5000):", txtInvestUnits);
        addFormRow(pnlOrderForm, 2, "Amount (" + config.getDefaultCurrency() + "):", txtInvestAmount);
        addFormRow(pnlOrderForm, 3, "Transaction PIN (if set):", txtInvestPin);
        addFormRow(pnlOrderForm, 4, "Order Preview:", lblPreviewSummary);

        pnlActions = new JPanel();
        pnlActions.setLayout(new GridLayoutManager(1, 4, new Insets(4, 0, 0, 0), 8, 0));
        pnlOrderForm.add(pnlActions, new GridConstraints(5, 1, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        btnPreviewInvest = new JButton("Preview Units & Cost");
        pnlActions.add(btnPreviewInvest, new GridConstraints(0, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        btnSubmitInvest = new JButton("Confirm Investment");
        pnlActions.add(btnSubmitInvest, new GridConstraints(0, 1, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        btnInvestBackToOverview = new JButton("\u2190 Back to Overview");
        pnlActions.add(btnInvestBackToOverview, new GridConstraints(0, 2, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        pnlActions.add(new Spacer(), new GridConstraints(0, 3, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_WANT_GROW, 1,
                null, null, null, 0, false));

        lblInvestFeedback = new JLabel(" ");
        lblInvestFeedback.setFont(UiTheme.font(UiTheme.FontRole.LABEL));
        pnlOrderForm.add(lblInvestFeedback, new GridConstraints(6, 0, 1, 2,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        pnlActivity = new JPanel();
        pnlActivity.setLayout(new GridLayoutManager(1, 1, new Insets(6, 8, 6, 8), 6, 6));
        pnlActivity.setBorder(UiTheme.titledBorder("Persisted Investment Activity"));
        pnlRoot.add(pnlActivity, new GridConstraints(2, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW,
                null, new Dimension(720, 180), null, 0, false));

        activityTableModel = new DefaultTableModel(
                new Object[]{"Date", "Reference", "Type", "Product", "Units", "Amount", "Status", "Description"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        tblInvestmentActivity = new JTable(activityTableModel);
        tblInvestmentActivity.setRowHeight(config.getTableRowHeight());
        tblInvestmentActivity.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        scrollInvestmentActivity = new JScrollPane(tblInvestmentActivity);
        pnlActivity.add(scrollInvestmentActivity, new GridConstraints(0, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW,
                null, null, null, 0, false));
    }

    private static void addSummaryCell(JPanel panel, int row, int labelCol, String labelText, JLabel valueLabel) {
        panel.add(new JLabel(labelText), new GridConstraints(row, labelCol, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));
        panel.add(valueLabel, new GridConstraints(row, labelCol + 1, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));
    }

    private static void addFormRow(JPanel panel, int row, String labelText, JComponent field) {
        panel.add(new JLabel(labelText), new GridConstraints(row, 0, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));
        panel.add(field, new GridConstraints(row, 1, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));
    }

    public JComponent $$$getRootComponent$$$() {
        return pnlRoot;
    }
}
