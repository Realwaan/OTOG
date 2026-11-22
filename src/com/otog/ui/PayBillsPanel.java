package com.otog.ui;

import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import com.intellij.uiDesigner.core.Spacer;
import com.otog.exception.DuplicateTransactionException;
import com.otog.exception.InsufficientFundsException;
import com.otog.exception.OtogException;
import com.otog.model.CompositeFeePolicy;
import com.otog.model.FeeComponent;
import com.otog.model.Transaction;
import com.otog.model.Wallet;
import com.otog.service.AuthService;
import com.otog.service.SessionManager;
import com.otog.service.WalletOperations;
import com.otog.util.IdGenerator;
import com.otog.util.MoneyUtil;
import com.otog.util.OtogConfig;
import com.otog.util.UiTheme;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.math.BigDecimal;
import java.util.List;

/**
 * Bound IntelliJ Swing form panel for the Pay Bills flow.
 * Offers seeded biller selection, account/reference validation, amount entry,
 * live fee preview, and Transaction PIN confirmation.
 */
public class PayBillsPanel extends JPanel {

    public static final List<String> SEEDED_BILLERS = List.of(
            "Meralco (Electricity)",
            "Manila Water (Water)",
            "Maynilad (Water)",
            "PLDT (Telecom & Internet)",
            "Globe Telecom (Mobile & Broadband)"
    );

    private JPanel pnlRoot;
    private JPanel pnlForm;
    private JPanel pnlActions;
    private JComboBox<String> cmbBiller;
    private JTextField txtBillReference;
    private JTextField txtBillAmount;
    private JLabel lblFeePreview;
    private JLabel lblTotalPreview;
    private JPasswordField txtBillPin;
    private JButton btnSubmitBillPayment;
    private JButton btnBillBackToOverview;
    private JLabel lblBillFeedback;

    private WalletOperations walletService;
    private AuthService authService;
    private FeeComponent feePolicy = CompositeFeePolicy.fromConfig();

    public PayBillsPanel() {
        this(null, null);
    }

    public PayBillsPanel(WalletOperations walletService, AuthService authService) {
        super(new BorderLayout());
        this.walletService = walletService;
        this.authService = authService;
        if (pnlRoot == null) {
            $$$setupUI$$$();
        }
        add(pnlRoot, BorderLayout.CENTER);
        initInteractions();
    }

    public void setWalletService(WalletOperations walletService) {
        this.walletService = walletService;
    }

    public void setAuthService(AuthService authService) {
        this.authService = authService;
    }

    public void setFeePolicy(FeeComponent feePolicy) {
        this.feePolicy = feePolicy != null ? feePolicy : CompositeFeePolicy.fromConfig();
        updateFeePreview();
    }

    private void initInteractions() {
        txtBillAmount.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                updateFeePreview();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                updateFeePreview();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                updateFeePreview();
            }
        });

        btnSubmitBillPayment.addActionListener(e -> executePayment());
        txtBillReference.addActionListener(e -> executePayment());
        txtBillAmount.addActionListener(e -> executePayment());
        txtBillPin.addActionListener(e -> executePayment());
    }

    public BigDecimal calculateFee(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return MoneyUtil.ZERO;
        }
        return feePolicy != null ? feePolicy.calculate(amount) : MoneyUtil.ZERO;
    }

    public void updateFeePreview() {
        String text = txtBillAmount.getText() != null ? txtBillAmount.getText().trim() : "";
        OtogConfig config = OtogConfig.getInstance();
        if (text.isEmpty()) {
            lblFeePreview.setText("PHP 0.00");
            lblTotalPreview.setText("PHP 0.00");
            return;
        }
        try {
            BigDecimal amount = MoneyUtil.parse(text);
            if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                lblFeePreview.setText("Invalid amount");
                lblTotalPreview.setText("Invalid amount");
                return;
            }
            BigDecimal fee = calculateFee(amount);
            BigDecimal total = amount.add(fee).setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING);
            lblFeePreview.setText(MoneyUtil.format(fee, config.getDefaultCurrency()));
            lblTotalPreview.setText(MoneyUtil.format(total, config.getDefaultCurrency()));
        } catch (RuntimeException ex) {
            lblFeePreview.setText("Invalid amount");
            lblTotalPreview.setText("Invalid amount");
        }
    }

    public void validateReference(String ref) {
        if (ref == null || ref.trim().isEmpty()) {
            throw new IllegalArgumentException("Account / reference number must not be empty.");
        }
        String clean = ref.trim();
        if (clean.length() < 6) {
            throw new IllegalArgumentException("Account / reference number must be at least 6 characters.");
        }
    }

    public Transaction executePayment() {
        String biller = (String) cmbBiller.getSelectedItem();
        String ref = txtBillReference.getText() != null ? txtBillReference.getText().trim() : "";
        String amountText = txtBillAmount.getText() != null ? txtBillAmount.getText().trim() : "";
        String pin = new String(txtBillPin.getPassword()).trim();

        try {
            if (biller == null || biller.trim().isEmpty()) {
                throw new IllegalArgumentException("Please select a biller.");
            }
            validateReference(ref);
            if (amountText.isEmpty()) {
                throw new IllegalArgumentException("Please enter a bill payment amount.");
            }
            BigDecimal parsedAmount = MoneyUtil.validatePositiveAmount(MoneyUtil.parse(amountText));
            BigDecimal fee = calculateFee(parsedAmount);

            OtogConfig config = OtogConfig.getInstance();
            if (pin.isEmpty()) {
                throw new IllegalArgumentException("Please enter your " + config.getPinMinLength() + "-"
                        + config.getPinMaxLength() + " digit Transaction PIN.");
            }

            if (walletService == null) {
                lblBillFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.INFO));
                lblBillFeedback.setText("Validated: " + biller + " Ref: " + ref + " Amount: " + parsedAmount);
                return null;
            }

            SessionManager session = SessionManager.getInstance();
            Wallet wallet = session.getCurrentWallet();
            if (wallet == null) {
                throw new IllegalStateException("Active wallet session required to pay bills.");
            }

            String reqReference = IdGenerator.nextReferenceNumber();
            Transaction txn = walletService.payBillWithPin(
                    reqReference,
                    wallet.getWalletId(),
                    biller,
                    ref,
                    parsedAmount,
                    fee,
                    "Bill Payment to " + biller + " [" + ref + "]",
                    pin
            );

            txtBillPin.setText("");
            if (txn.getStatus() == com.otog.model.TransactionStatus.COMPLETED) {
                lblBillFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.SUCCESS));
                lblBillFeedback.setText("Bill payment successful! Ref: " + txn.getReferenceNumber()
                        + " Total: " + MoneyUtil.format(txn.getTotalAmount(), txn.getCurrency()));
            } else if (txn.getStatus() == com.otog.model.TransactionStatus.FAILED) {
                lblBillFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
                lblBillFeedback.setText("Bill payment failed. Reserved funds released back to wallet.");
            } else {
                lblBillFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.WARNING));
                lblBillFeedback.setText("Bill payment pending provider settlement. Ref: " + txn.getReferenceNumber());
            }
            return txn;
        } catch (DuplicateTransactionException ex) {
            lblBillFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
            lblBillFeedback.setText("Payment failed: Duplicate transaction detected (" + ex.getMessage() + ")");
            throw ex;
        } catch (InsufficientFundsException ex) {
            lblBillFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
            lblBillFeedback.setText("Payment failed: Insufficient wallet balance.");
            throw ex;
        } catch (RuntimeException ex) {
            lblBillFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
            lblBillFeedback.setText("Payment validation failed: " + ex.getMessage());
            throw ex;
        }
    }

    public JPanel getRootPanel() {
        return pnlRoot;
    }

    public JComboBox<String> getBillerComboBox() {
        return cmbBiller;
    }

    public JTextField getBillReferenceField() {
        return txtBillReference;
    }

    public JTextField getBillAmountField() {
        return txtBillAmount;
    }

    public JLabel getFeePreviewLabel() {
        return lblFeePreview;
    }

    public JLabel getTotalPreviewLabel() {
        return lblTotalPreview;
    }

    public JPasswordField getBillPinField() {
        return txtBillPin;
    }

    public JButton getSubmitBillPaymentButton() {
        return btnSubmitBillPayment;
    }

    public JButton getBillBackToOverviewButton() {
        return btnBillBackToOverview;
    }

    public JLabel getBillFeedbackLabel() {
        return lblBillFeedback;
    }

    public JComponent $$$getRootComponent$$$() {
        return pnlRoot;
    }

    private void $$$setupUI$$$() {
        pnlRoot = new JPanel();
        pnlRoot.setLayout(new GridLayoutManager(2, 1, new Insets(16, 16, 16, 16), 10, 10));

        OtogConfig config = OtogConfig.getInstance();
        pnlForm = new JPanel();
        pnlForm.setLayout(new GridLayoutManager(8, 2, new Insets(12, 14, 12, 14), 10, 10));
        pnlForm.setBorder(UiTheme.titledBorder("Pay Utility & Service Bills"));
        pnlRoot.add(pnlForm, new GridConstraints(0, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                null, null, null, 0, false));

        cmbBiller = new JComboBox<>(SEEDED_BILLERS.toArray(String[]::new));
        txtBillReference = new JTextField(22);
        txtBillAmount = new JTextField(22);
        lblFeePreview = new JLabel("PHP 0.00");
        lblFeePreview.setFont(UiTheme.font(UiTheme.FontRole.LABEL));
        lblTotalPreview = new JLabel("PHP 0.00");
        lblTotalPreview.setFont(UiTheme.font(UiTheme.FontRole.LABEL));
        txtBillPin = new JPasswordField(22);
        txtBillPin.setToolTipText("Enter your " + config.getPinMinLength() + "-" + config.getPinMaxLength()
                + " digit Transaction PIN to authorize bill payment");

        addRow(pnlForm, 0, "Select Biller:", cmbBiller);
        addRow(pnlForm, 1, "Account / Reference Number:", txtBillReference);
        addRow(pnlForm, 2, "Amount (" + config.getDefaultCurrency() + "):", txtBillAmount);
        addRow(pnlForm, 3, "Fee Preview:", lblFeePreview);
        addRow(pnlForm, 4, "Total to Pay:", lblTotalPreview);
        addRow(pnlForm, 5, "Transaction PIN:", txtBillPin);

        pnlActions = new JPanel();
        pnlActions.setLayout(new GridLayoutManager(1, 3, new Insets(4, 0, 0, 0), 8, 0));
        pnlForm.add(pnlActions, new GridConstraints(6, 1, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        btnSubmitBillPayment = new JButton("Pay Bill");
        pnlActions.add(btnSubmitBillPayment, new GridConstraints(0, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        btnBillBackToOverview = new JButton("\u2190 Back to Overview");
        pnlActions.add(btnBillBackToOverview, new GridConstraints(0, 1, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        pnlActions.add(new Spacer(), new GridConstraints(0, 2, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_WANT_GROW, 1,
                null, null, null, 0, false));

        lblBillFeedback = new JLabel(" ");
        lblBillFeedback.setFont(UiTheme.font(UiTheme.FontRole.LABEL));
        pnlForm.add(lblBillFeedback, new GridConstraints(7, 0, 1, 2,
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
}
