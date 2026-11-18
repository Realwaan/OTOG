package com.otog.ui;

import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import com.intellij.uiDesigner.core.Spacer;

import javax.swing.*;
import java.awt.*;

/**
 * Bound IntelliJ Swing form panel for the Send Money tab.
 */
public class SendMoneyPanel extends JPanel {

    private JPanel pnlRoot;
    private JPanel pnlForm;
    private JPanel pnlActions;
    private JTextField txtRecipient;
    private JTextField txtSendAmount;
    private JTextField txtSendNote;
    private JPasswordField txtSendPin;
    private JButton btnSubmitSendMoney;
    private JButton btnSendBackToOverview;
    private JLabel lblSendFeedback;

    public SendMoneyPanel() {
        super(new BorderLayout());
        if (pnlRoot == null) {
            $$$setupUI$$$();
        }
        add(pnlRoot, BorderLayout.CENTER);
    }

    public JPanel getRootPanel() {
        return pnlRoot;
    }

    public JTextField getRecipientField() {
        return txtRecipient;
    }

    public JTextField getSendAmountField() {
        return txtSendAmount;
    }

    public JTextField getSendNoteField() {
        return txtSendNote;
    }

    public JPasswordField getSendPinField() {
        return txtSendPin;
    }

    public JButton getSubmitSendMoneyButton() {
        return btnSubmitSendMoney;
    }

    public JButton getSendBackToOverviewButton() {
        return btnSendBackToOverview;
    }

    public JLabel getSendFeedbackLabel() {
        return lblSendFeedback;
    }

    private void $$$setupUI$$$() {
        pnlRoot = new JPanel();
        pnlRoot.setLayout(new GridLayoutManager(2, 1, new Insets(16, 16, 16, 16), 10, 10));

        pnlForm = new JPanel();
        pnlForm.setLayout(new GridLayoutManager(6, 2, new Insets(12, 14, 12, 14), 10, 10));
        pnlForm.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), "Wallet-to-Wallet Transfer"));
        pnlRoot.add(pnlForm, new GridConstraints(0, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                null, null, null, 0, false));

        txtRecipient = new JTextField(22);
        txtSendAmount = new JTextField(22);
        txtSendNote = new JTextField(22);
        txtSendPin = new JPasswordField(22);
        txtSendPin.setToolTipText("Enter your 4-6 digit Transaction PIN to authorize outgoing transfer");

        addRow(pnlForm, 0, "Recipient (Mobile / Email / Name / Wallet ID):", txtRecipient);
        addRow(pnlForm, 1, "Amount (PHP):", txtSendAmount);
        addRow(pnlForm, 2, "Note (Optional):", txtSendNote);
        addRow(pnlForm, 3, "Transaction PIN:", txtSendPin);

        pnlActions = new JPanel();
        pnlActions.setLayout(new GridLayoutManager(1, 3, new Insets(4, 0, 0, 0), 8, 0));
        pnlForm.add(pnlActions, new GridConstraints(4, 1, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        btnSubmitSendMoney = new JButton("Send Money");
        pnlActions.add(btnSubmitSendMoney, new GridConstraints(0, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        btnSendBackToOverview = new JButton("\u2190 Back to Overview");
        pnlActions.add(btnSendBackToOverview, new GridConstraints(0, 1, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        pnlActions.add(new Spacer(), new GridConstraints(0, 2, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_WANT_GROW, 1,
                null, null, null, 0, false));

        lblSendFeedback = new JLabel(" ");
        lblSendFeedback.setFont(new Font("SansSerif", Font.PLAIN, 12));
        pnlForm.add(lblSendFeedback, new GridConstraints(5, 0, 1, 2,
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
