package com.otog.ui;

import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import com.intellij.uiDesigner.core.Spacer;
import com.otog.service.BankResponseStatus;

import javax.swing.*;
import java.awt.*;

/**
 * Bound IntelliJ Swing form panel for the Cash In / Cash Out tab.
 */
public class CashInOutPanel extends JPanel {

    private JPanel pnlRoot;
    private JPanel pnlForm;
    private JPanel pnlActions;
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

    public CashInOutPanel(BankResponseStatus initialProviderMode) {
        super(new BorderLayout());
        if (pnlRoot == null) {
            $$$setupUI$$$();
        }
        if (initialProviderMode != null) {
            cmbProviderMode.setSelectedItem(initialProviderMode.name());
        }
        add(pnlRoot, BorderLayout.CENTER);
    }

    public JPanel getRootPanel() {
        return pnlRoot;
    }

    public JComboBox<String> getCashOperationComboBox() {
        return cmbCashOperation;
    }

    public JComboBox<String> getPaymentMethodComboBox() {
        return cmbPaymentMethod;
    }

    public JComboBox<String> getCashChannelComboBox() {
        return cmbCashChannel;
    }

    public JComboBox<String> getProviderModeComboBox() {
        return cmbProviderMode;
    }

    public JTextField getCashAmountField() {
        return txtCashAmount;
    }

    public JTextField getCashNoteField() {
        return txtCashNote;
    }

    public JPasswordField getCashPinField() {
        return txtCashPin;
    }

    public JButton getSubmitCashOpButton() {
        return btnSubmitCashOp;
    }

    public JButton getCashBackToOverviewButton() {
        return btnCashBackToOverview;
    }

    public JLabel getCashFeedbackLabel() {
        return lblCashFeedback;
    }

    private void $$$setupUI$$$() {
        pnlRoot = new JPanel();
        pnlRoot.setLayout(new GridLayoutManager(2, 1, new Insets(16, 16, 16, 16), 10, 10));

        pnlForm = new JPanel();
        pnlForm.setLayout(new GridLayoutManager(9, 2, new Insets(12, 14, 12, 14), 10, 10));
        pnlForm.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), "Cash In / Cash Out via Linked Bank or Channel"));
        pnlRoot.add(pnlForm, new GridConstraints(0, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                null, null, null, 0, false));

        cmbCashOperation = new JComboBox<>(new String[]{"CASH_IN", "CASH_OUT"});
        cmbPaymentMethod = new JComboBox<>(new String[]{"Use Selected Channel"});
        cmbCashChannel = new JComboBox<>(new String[]{"GCash", "Maya", "BDO", "BPI", "7-Eleven OTC"});
        cmbProviderMode = new JComboBox<>(new String[]{"SUCCESS", "REJECTED", "FAILED", "TIMEOUT", "PENDING", "UNKNOWN"});
        txtCashAmount = new JTextField(20);
        txtCashNote = new JTextField(20);
        txtCashPin = new JPasswordField(20);
        txtCashPin.setEnabled(false);
        txtCashPin.setToolTipText("Required for Cash Out transactions");

        addRow(pnlForm, 0, "Operation:", cmbCashOperation);
        addRow(pnlForm, 1, "Linked Bank Account:", cmbPaymentMethod);
        addRow(pnlForm, 2, "Channel / Provider:", cmbCashChannel);
        addRow(pnlForm, 3, "Mock Bank Mode:", cmbProviderMode);
        addRow(pnlForm, 4, "Amount (PHP):", txtCashAmount);
        addRow(pnlForm, 5, "Note (Optional):", txtCashNote);
        addRow(pnlForm, 6, "Transaction PIN (Cash Out):", txtCashPin);

        pnlActions = new JPanel();
        pnlActions.setLayout(new GridLayoutManager(1, 3, new Insets(4, 0, 0, 0), 8, 0));
        pnlForm.add(pnlActions, new GridConstraints(7, 1, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        btnSubmitCashOp = new JButton("Submit Cash Transaction");
        pnlActions.add(btnSubmitCashOp, new GridConstraints(0, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        btnCashBackToOverview = new JButton("\u2190 Back to Overview");
        pnlActions.add(btnCashBackToOverview, new GridConstraints(0, 1, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        pnlActions.add(new Spacer(), new GridConstraints(0, 2, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_WANT_GROW, 1,
                null, null, null, 0, false));

        lblCashFeedback = new JLabel(" ");
        lblCashFeedback.setFont(new Font("SansSerif", Font.PLAIN, 12));
        pnlForm.add(lblCashFeedback, new GridConstraints(8, 0, 1, 2,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        pnlRoot.add(new Spacer(), new GridConstraints(1, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_VERTICAL,
                1, GridConstraints.SIZEPOLICY_WANT_GROW,
                null, null, null, 0, false));
    }

    private static void addRow(JPanel panel, int row, String labelText, JComponent field) {
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
