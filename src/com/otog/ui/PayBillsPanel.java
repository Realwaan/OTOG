package com.otog.ui;

import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import com.intellij.uiDesigner.core.Spacer;
import com.otog.exception.DuplicateTransactionException;
import com.otog.exception.InsufficientFundsException;
import com.otog.exception.WalletException;
import com.otog.model.BillPaymentTransaction;
import com.otog.model.FeeComponent;
import com.otog.model.ReceiptBuilder;
import com.otog.model.Transaction;
import com.otog.model.TransactionStatus;
import com.otog.model.Wallet;
import com.otog.service.AuthService;
import com.otog.service.BankResponseStatus;
import com.otog.service.PaymentProviderFactory;
import com.otog.service.SessionManager;
import com.otog.service.WalletOperations;
import com.otog.service.WalletService;
import com.otog.util.IdGenerator;
import com.otog.util.MoneyUtil;
import com.otog.util.OtogConfig;
import com.otog.util.SwingUiSupport;
import com.otog.util.UiTheme;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

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
    private FeeComponent customFeePolicy;
    private Runnable onBackToOverview;
    private Consumer<Transaction> onPaymentSuccess;
    private Consumer<Throwable> onPaymentError;
    private BooleanSupplier beforeInteractiveSubmit;
    private BooleanSupplier pinConfiguredSupplier;
    private Runnable onPinNotSet;
    private final AtomicBoolean paymentInFlight = new AtomicBoolean(false);
    private volatile boolean lastPaymentExecutedOffEdt;

    private record BillPaymentRequest(
            String biller,
            String billerReference,
            BigDecimal amount,
            BigDecimal fee,
            String pin
    ) {
    }

    private record ValidatedDetails(
            String biller,
            String billerReference,
            BigDecimal amount,
            BigDecimal fee
    ) {
    }

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
        populateBillersIfEmpty();
        add(pnlRoot, BorderLayout.CENTER);
        initInteractions();
    }

    private void populateBillersIfEmpty() {
        OtogConfig config = OtogConfig.getInstance();
        if (cmbBiller != null) {
            if (cmbBiller.getItemCount() == 0) {
                List<String> billers = config.getSeededBillers();
                if (billers == null || billers.isEmpty()) {
                    billers = SEEDED_BILLERS;
                }
                for (String b : billers) {
                    cmbBiller.addItem(b);
                }
            }
            UiTheme.styleInputField(cmbBiller);
        }
        UiTheme.styleInputField(txtBillReference);
        UiTheme.styleInputField(txtBillAmount);
        if (txtBillPin != null) {
            txtBillPin.setToolTipText("Enter your " + config.getPinMinLength() + "-" + config.getPinMaxLength()
                    + " digit Transaction PIN to authorize bill payment");
            UiTheme.styleInputField(txtBillPin);
        }
        if (lblFeePreview != null) {
            lblFeePreview.setFont(UiTheme.font(UiTheme.FontRole.LABEL_BOLD));
        }
        if (lblTotalPreview != null) {
            lblTotalPreview.setFont(UiTheme.font(UiTheme.FontRole.LABEL_BOLD));
        }
        UiTheme.stylePrimaryButton(btnSubmitBillPayment);
        UiTheme.styleSecondaryButton(btnBillBackToOverview);
        if (lblBillFeedback != null) {
            lblBillFeedback.setFont(UiTheme.font(UiTheme.FontRole.LABEL));
        }
    }

    public void setWalletService(WalletOperations walletService) {
        this.walletService = walletService;
    }

    public void setAuthService(AuthService authService) {
        this.authService = authService;
    }

    public void setFeePolicy(FeeComponent feePolicy) {
        this.customFeePolicy = feePolicy;
        updateFeePreview();
    }

    public void setOnBackToOverview(Runnable onBackToOverview) {
        this.onBackToOverview = onBackToOverview;
    }

    public void setOnPaymentSuccess(Consumer<Transaction> onPaymentSuccess) {
        this.onPaymentSuccess = onPaymentSuccess;
    }

    public void setOnPaymentError(Consumer<Throwable> onPaymentError) {
        this.onPaymentError = onPaymentError;
    }

    public void setBeforeInteractiveSubmit(BooleanSupplier beforeInteractiveSubmit) {
        this.beforeInteractiveSubmit = beforeInteractiveSubmit;
    }

    public void setPinConfiguredSupplier(BooleanSupplier pinConfiguredSupplier) {
        this.pinConfiguredSupplier = pinConfiguredSupplier;
    }

    public void setOnPinNotSet(Runnable onPinNotSet) {
        this.onPinNotSet = onPinNotSet;
    }

    public boolean isLastPaymentExecutedOffEdt() {
        return lastPaymentExecutedOffEdt;
    }

    public void loadRepeatDraft(Transaction draft) {
        if (draft == null) {
            return;
        }
        BillPaymentTransaction bpt = (draft instanceof BillPaymentTransaction b) ? b : null;
        if (bpt != null && bpt.getBillerName() != null) {
            cmbBiller.setSelectedItem(bpt.getBillerName());
        }
        if (bpt != null && bpt.getBillerReference() != null) {
            txtBillReference.setText(bpt.getBillerReference());
        }
        txtBillAmount.setText(draft.getAmount() != null ? draft.getAmount().toPlainString() : "");
        txtBillPin.setText("");
        lblBillFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.INFO));
        lblBillFeedback.setText("Loaded repeat draft (New Ref: " + draft.getReferenceNumber()
                + "). Review details and enter PIN to pay.");
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

        btnSubmitBillPayment.addActionListener(e -> submitPaymentAsync());
        txtBillReference.addActionListener(e -> submitPaymentAsync());
        txtBillAmount.addActionListener(e -> submitPaymentAsync());
        txtBillPin.addActionListener(e -> submitPaymentAsync());
        btnBillBackToOverview.addActionListener(e -> {
            if (onBackToOverview != null) {
                onBackToOverview.run();
            }
        });
    }

    private FeeComponent resolveFeePolicy() {
        if (customFeePolicy != null) {
            return customFeePolicy;
        }
        BankResponseStatus mode = walletService != null
                ? walletService.getCurrentProviderMode()
                : BankResponseStatus.SUCCESS;
        return PaymentProviderFactory.forMode(mode).createBillPaymentFeePolicy();
    }

    public BigDecimal calculateFee(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return MoneyUtil.ZERO;
        }
        FeeComponent policy = resolveFeePolicy();
        return policy != null ? policy.calculate(amount) : MoneyUtil.ZERO;
    }

    public void updateFeePreview() {
        String text = txtBillAmount.getText() != null ? txtBillAmount.getText().trim() : "";
        OtogConfig config = OtogConfig.getInstance();
        if (text.isEmpty()) {
            String zeroFormatted = MoneyUtil.format(MoneyUtil.ZERO, config.getDefaultCurrency());
            lblFeePreview.setText(zeroFormatted);
            lblTotalPreview.setText(zeroFormatted);
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
        WalletService.validateBillerReference(ref);
    }

    protected int showConfirmDialog(String title, String message) {
        if (GraphicsEnvironment.isHeadless()) {
            return JOptionPane.OK_OPTION;
        }
        return JOptionPane.showConfirmDialog(
                this,
                message,
                title,
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE
        );
    }

    public CompletableFuture<Transaction> submitPaymentAsync() {
        if (!btnSubmitBillPayment.isEnabled() || paymentInFlight.get()) {
            return CompletableFuture.completedFuture(null);
        }
        if (beforeInteractiveSubmit != null && !beforeInteractiveSubmit.getAsBoolean()) {
            return CompletableFuture.completedFuture(null);
        }

        String biller = (String) cmbBiller.getSelectedItem();
        String ref = txtBillReference.getText() != null ? txtBillReference.getText().trim() : "";
        String amountText = txtBillAmount.getText() != null ? txtBillAmount.getText().trim() : "";
        String pin = new String(txtBillPin.getPassword()).trim();

        BillPaymentRequest request;
        try {
            ValidatedDetails details = validatePaymentDetails(biller, ref, amountText);
            if (authService != null && pinConfiguredSupplier != null && !pinConfiguredSupplier.getAsBoolean()) {
                txtBillPin.setText("");
                lblBillFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.WARNING));
                lblBillFeedback.setText("Transaction PIN is not set. Please set a Transaction PIN before paying bills.");
                if (onPinNotSet != null) {
                    onPinNotSet.run();
                }
                return CompletableFuture.completedFuture(null);
            }
            String cleanPin = validatePinPresent(pin);
            request = new BillPaymentRequest(
                    details.biller(),
                    details.billerReference(),
                    details.amount(),
                    details.fee(),
                    cleanPin
            );
        } catch (RuntimeException ex) {
            applyFailureUi(ex);
            if (onPaymentError != null) {
                onPaymentError.accept(ex);
            }
            return CompletableFuture.failedFuture(ex);
        }

        // Always clear PIN field before displaying confirmation so cancellation never retains typed PIN
        txtBillPin.setText("");
        OtogConfig config = OtogConfig.getInstance();
        BigDecimal total = request.amount().add(request.fee())
                .setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING);
        String confirmMessage = "Biller: " + request.biller() + "\n"
                + "Account / Ref: " + ReceiptBuilder.maskIdentifier(request.billerReference()) + "\n"
                + "Amount: " + MoneyUtil.format(request.amount(), config.getDefaultCurrency()) + "\n"
                + "Fee: " + MoneyUtil.format(request.fee(), config.getDefaultCurrency()) + "\n"
                + "Total: " + MoneyUtil.format(total, config.getDefaultCurrency());
        int choice = showConfirmDialog("Confirm Bill Payment", confirmMessage);
        if (choice != JOptionPane.OK_OPTION) {
            lblBillFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.MUTED));
            lblBillFeedback.setText("Bill payment cancelled.");
            return CompletableFuture.completedFuture(null);
        }

        return executePaymentAsync(request);
    }

    public CompletableFuture<Transaction> submitPaymentAsync(String biller,
                                                             String billerReference,
                                                             String amountText,
                                                             String pin) {
        BillPaymentRequest request;
        try {
            request = validateInputs(biller, billerReference, amountText, pin);
        } catch (RuntimeException ex) {
            CompletableFuture<Transaction> future = new CompletableFuture<>();
            SwingUiSupport.runOnEdt(() -> {
                applyFailureUi(ex);
                if (onPaymentError != null) {
                    onPaymentError.accept(ex);
                }
                future.completeExceptionally(ex);
            });
            return future;
        }
        return executePaymentAsync(request);
    }

    private CompletableFuture<Transaction> executePaymentAsync(BillPaymentRequest request) {
        if (!paymentInFlight.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Transaction> future = new CompletableFuture<>();
        SwingUiSupport.runOnEdt(() -> {
            btnSubmitBillPayment.setEnabled(false);
            txtBillPin.setText("");
            lblBillFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.INFO));
            lblBillFeedback.setText("Processing bill payment...");
        });

        SwingWorker<Transaction, Void> worker = new SwingWorker<>() {
            @Override
            protected Transaction doInBackground() {
                lastPaymentExecutedOffEdt = !SwingUtilities.isEventDispatchThread();
                return performPaymentWork(request);
            }

            @Override
            protected void done() {
                paymentInFlight.set(false);
                btnSubmitBillPayment.setEnabled(true);
                try {
                    Transaction txn = get();
                    applyPaymentOutcomeUi(request, txn);
                    future.complete(txn);
                } catch (Exception ex) {
                    Throwable cause = SwingUiSupport.unwrapCause(ex);
                    applyFailureUi(cause);
                    if (onPaymentError != null) {
                        onPaymentError.accept(cause);
                    }
                    future.completeExceptionally(cause);
                }
            }
        };
        worker.execute();
        return future;
    }

    public Transaction executePayment() {
        String biller = (String) cmbBiller.getSelectedItem();
        String ref = txtBillReference.getText() != null ? txtBillReference.getText().trim() : "";
        String amountText = txtBillAmount.getText() != null ? txtBillAmount.getText().trim() : "";
        String pin = new String(txtBillPin.getPassword()).trim();

        try {
            BillPaymentRequest request = validateInputs(biller, ref, amountText, pin);
            Transaction txn = performPaymentWork(request);
            applyPaymentOutcomeUi(request, txn);
            return txn;
        } catch (RuntimeException ex) {
            applyFailureUi(ex);
            if (onPaymentError != null) {
                onPaymentError.accept(ex);
            }
            throw ex;
        }
    }

    private BillPaymentRequest validateInputs(String biller, String ref, String amountText, String pin) {
        ValidatedDetails details = validatePaymentDetails(biller, ref, amountText);
        String cleanPin = validatePinPresent(pin);
        return new BillPaymentRequest(
                details.biller(),
                details.billerReference(),
                details.amount(),
                details.fee(),
                cleanPin
        );
    }

    private ValidatedDetails validatePaymentDetails(String biller, String ref, String amountText) {
        if (biller == null || biller.trim().isEmpty()) {
            throw new IllegalArgumentException("Please select a biller.");
        }
        String cleanBiller = biller.trim();
        validateReference(ref);
        String cleanRef = ref.trim();
        String cleanAmountText = amountText != null ? amountText.trim() : "";
        if (cleanAmountText.isEmpty()) {
            throw new IllegalArgumentException("Please enter a bill payment amount.");
        }
        BigDecimal parsedAmount = MoneyUtil.validatePositiveAmount(MoneyUtil.parse(cleanAmountText));
        BigDecimal fee = calculateFee(parsedAmount);
        return new ValidatedDetails(cleanBiller, cleanRef, parsedAmount, fee);
    }

    private String validatePinPresent(String pin) {
        OtogConfig config = OtogConfig.getInstance();
        String cleanPin = pin != null ? pin.trim() : "";
        if (cleanPin.isEmpty()) {
            throw new IllegalArgumentException("Please enter your " + config.getPinMinLength() + "-"
                    + config.getPinMaxLength() + " digit Transaction PIN.");
        }
        return cleanPin;
    }

    private Transaction performPaymentWork(BillPaymentRequest request) {
        if (walletService == null) {
            return null;
        }
        SessionManager session = SessionManager.getInstance();
        Wallet wallet = session.getCurrentWallet();
        if (wallet == null) {
            wallet = session.refreshWallet(walletService);
        }
        if (wallet == null) {
            throw new IllegalStateException("Active wallet session required to pay bills.");
        }

        String reqReference = IdGenerator.nextReferenceNumber();
        String desc = "Bill Payment to " + request.biller() + " [" + ReceiptBuilder.maskIdentifier(request.billerReference()) + "]";
        Transaction txn;

        if (authService != null) {
            AuthService.verifyPinOrThrow(authService, wallet.getUserId(), request.pin());
            txn = walletService.payBill(
                    reqReference,
                    wallet.getWalletId(),
                    request.biller(),
                    request.billerReference(),
                    request.amount(),
                    request.fee(),
                    desc
            );
        } else {
            txn = walletService.payBillWithPin(
                    reqReference,
                    wallet.getWalletId(),
                    request.biller(),
                    request.billerReference(),
                    request.amount(),
                    request.fee(),
                    desc,
                    request.pin()
            );
        }
        session.refreshWallet(walletService);
        return txn;
    }

    private void applyPaymentOutcomeUi(BillPaymentRequest request, Transaction txn) {
        txtBillPin.setText("");
        if (txn == null) {
            lblBillFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.INFO));
            lblBillFeedback.setText("Validated: " + request.biller()
                    + " Ref: " + ReceiptBuilder.maskIdentifier(request.billerReference())
                    + " Amount: " + request.amount());
            return;
        }
        if (txn.getStatus() == TransactionStatus.COMPLETED) {
            txtBillReference.setText("");
            txtBillAmount.setText("");
            lblBillFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.SUCCESS));
            lblBillFeedback.setText("Bill payment successful! Ref: " + txn.getReferenceNumber()
                    + " Total: " + MoneyUtil.format(txn.getTotalAmount(), txn.getCurrency()));
        } else if (txn.getStatus() == TransactionStatus.FAILED) {
            // Preserve txtBillReference and txtBillAmount so the user does not have to retype after a rejection
            lblBillFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
            lblBillFeedback.setText("Bill payment failed. Reserved funds released back to wallet.");
        } else {
            txtBillReference.setText("");
            txtBillAmount.setText("");
            lblBillFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.WARNING));
            lblBillFeedback.setText("Bill payment pending provider settlement. Ref: " + txn.getReferenceNumber());
        }
        if (onPaymentSuccess != null) {
            onPaymentSuccess.accept(txn);
        }
    }

    private void applyFailureUi(Throwable ex) {
        txtBillPin.setText("");
        lblBillFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
        if (ex instanceof DuplicateTransactionException) {
            lblBillFeedback.setText("Payment failed: Duplicate transaction detected (" + ex.getMessage() + ")");
        } else if (ex instanceof InsufficientFundsException) {
            lblBillFeedback.setText("Payment failed: Insufficient wallet balance.");
        } else {
            String msg = SwingUiSupport.extractErrorMessage(ex);
            lblBillFeedback.setText("Payment validation failed: " + msg);
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

        List<String> billers = config.getSeededBillers();
        if (billers == null || billers.isEmpty()) {
            billers = SEEDED_BILLERS;
        }
        cmbBiller = new JComboBox<>(billers.toArray(String[]::new));
        txtBillReference = new JTextField(22);
        txtBillAmount = new JTextField(22);
        String zeroFormatted = MoneyUtil.format(MoneyUtil.ZERO, config.getDefaultCurrency());
        lblFeePreview = new JLabel(zeroFormatted);
        lblFeePreview.setFont(UiTheme.font(UiTheme.FontRole.LABEL_BOLD));
        lblTotalPreview = new JLabel(zeroFormatted);
        lblTotalPreview.setFont(UiTheme.font(UiTheme.FontRole.LABEL_BOLD));
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
        UiTheme.stylePrimaryButton(btnSubmitBillPayment);
        pnlActions.add(btnSubmitBillPayment, new GridConstraints(0, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        btnBillBackToOverview = new JButton("\u2190 Back to Overview");
        UiTheme.styleSecondaryButton(btnBillBackToOverview);
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
        JLabel label = new JLabel(labelText);
        UiTheme.styleFormLabel(label);
        if (!(field instanceof JLabel)) {
            UiTheme.styleInputField(field);
        }
        panel.add(label, new GridConstraints(row, 0, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));
        panel.add(field, new GridConstraints(row, 1, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));
    }
}
