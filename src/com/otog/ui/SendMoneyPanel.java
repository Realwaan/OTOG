package com.otog.ui;

import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import com.intellij.uiDesigner.core.Spacer;
import com.otog.model.CompositeFeePolicy;
import com.otog.model.SendMoneyTransaction;
import com.otog.model.Transaction;
import com.otog.model.User;
import com.otog.model.Wallet;
import com.otog.service.AuthService;
import com.otog.service.SendMoneyService;
import com.otog.service.SessionManager;
import com.otog.service.WalletOperations;
import com.otog.util.IdGenerator;
import com.otog.util.MoneyUtil;
import com.otog.util.OtogConfig;
import com.otog.util.SwingUiSupport;
import com.otog.util.UiTheme;

import javax.swing.*;
import java.awt.*;
import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Bound IntelliJ Swing form panel for the Send Money tab.
 * Owns recipient/amount/PIN validation, transfer preview confirmation,
 * configured fee forwarding, and off-EDT {@link SwingWorker} execution.
 */
public class SendMoneyPanel extends JPanel {

    private final WalletOperations walletService;
    private final AuthService authService;
    private final AtomicBoolean submitInFlight = new AtomicBoolean(false);

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

    private volatile boolean lastOperationExecutedOffEdt;

    private Runnable onBackToOverview;
    private BooleanSupplier beforeInteractiveSubmit;
    private BooleanSupplier pinConfiguredSupplier;
    private Runnable onPinNotSet;
    private BooleanSupplier isLogoutHandledSupplier;
    private SwingUiSupport.DialogMessageCallback dialogMessageCallback = SwingUiSupport.defaultMessageCallback();
    private SwingUiSupport.ConfirmDialogCallback confirmDialogCallback = SwingUiSupport.defaultConfirmCallback();
    private Consumer<String> onStatusMessage;
    private Consumer<Boolean> offEdtObserver;
    private Supplier<CompletableFuture<Void>> onRefreshRequested;
    private Consumer<String> onOperationError;

    public SendMoneyPanel() {
        this(null, null);
    }

    public SendMoneyPanel(WalletOperations walletService, AuthService authService) {
        super(new BorderLayout());
        this.walletService = walletService;
        this.authService = authService;
        if (pnlRoot == null) {
            $$$setupUI$$$();
        }
        ensureComponentsInitialized();
        add(pnlRoot, BorderLayout.CENTER);
        initInteractions();
    }

    private void ensureComponentsInitialized() {
        OtogConfig config = OtogConfig.getInstance();
        if (pnlForm != null) {
            pnlForm.setBorder(UiTheme.titledBorder("Wallet-to-Wallet Transfer"));
        }
        UiTheme.styleInputField(txtRecipient);
        UiTheme.styleInputField(txtSendAmount);
        UiTheme.styleInputField(txtSendNote);
        if (txtSendPin != null) {
            txtSendPin.setToolTipText("Enter your " + config.getPinMinLength() + "-" + config.getPinMaxLength()
                    + " digit Transaction PIN to authorize outgoing transfer");
            UiTheme.styleInputField(txtSendPin);
        }
        UiTheme.stylePrimaryButton(btnSubmitSendMoney);
        UiTheme.styleSecondaryButton(btnSendBackToOverview);
        if (lblSendFeedback != null) {
            lblSendFeedback.setFont(UiTheme.font(UiTheme.FontRole.LABEL));
        }
    }

    public void setOnBackToOverview(Runnable onBackToOverview) {
        this.onBackToOverview = onBackToOverview;
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

    public void setIsLogoutHandledSupplier(BooleanSupplier isLogoutHandledSupplier) {
        this.isLogoutHandledSupplier = isLogoutHandledSupplier;
    }

    public void setDialogMessageCallback(SwingUiSupport.DialogMessageCallback dialogMessageCallback) {
        this.dialogMessageCallback = dialogMessageCallback != null
                ? dialogMessageCallback
                : SwingUiSupport.defaultMessageCallback();
    }

    public void setConfirmDialogCallback(SwingUiSupport.ConfirmDialogCallback confirmDialogCallback) {
        this.confirmDialogCallback = confirmDialogCallback != null
                ? confirmDialogCallback
                : SwingUiSupport.defaultConfirmCallback();
    }

    public void setOnStatusMessage(Consumer<String> onStatusMessage) {
        this.onStatusMessage = onStatusMessage;
    }

    public void setOffEdtObserver(Consumer<Boolean> offEdtObserver) {
        this.offEdtObserver = offEdtObserver;
    }

    public void setOnRefreshRequested(Supplier<CompletableFuture<Void>> onRefreshRequested) {
        this.onRefreshRequested = onRefreshRequested;
    }

    public void setOnOperationError(Consumer<String> onOperationError) {
        this.onOperationError = onOperationError;
    }

    public boolean isLastOperationExecutedOffEdt() {
        return lastOperationExecutedOffEdt;
    }

    private void initInteractions() {
        btnSendBackToOverview.addActionListener(e -> {
            if (onBackToOverview != null) {
                onBackToOverview.run();
            }
        });
        btnSubmitSendMoney.addActionListener(e -> handleInteractiveSubmit());
        txtRecipient.addActionListener(e -> handleInteractiveSubmit());
        txtSendAmount.addActionListener(e -> handleInteractiveSubmit());
        txtSendNote.addActionListener(e -> handleInteractiveSubmit());
        txtSendPin.addActionListener(e -> handleInteractiveSubmit());
    }

    public void focusRecipient() {
        txtRecipient.requestFocusInWindow();
    }

    public void loadRepeatDraft(Transaction draft) {
        if (draft == null) {
            return;
        }
        String recipientTarget = (draft instanceof SendMoneyTransaction smt && smt.getRecipientNumber() != null)
                ? smt.getRecipientNumber()
                : (draft.getTargetWalletId() != null ? draft.getTargetWalletId() : "");
        txtRecipient.setText(recipientTarget);
        txtSendAmount.setText(draft.getAmount() != null ? draft.getAmount().toPlainString() : "");
        txtSendNote.setText(draft.getDescription() != null ? draft.getDescription() : "");
        txtSendPin.setText("");
        lblSendFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.INFO));
        lblSendFeedback.setText("Loaded repeat draft (New Ref: " + draft.getReferenceNumber()
                + "). Review details and enter PIN to send.");
    }

    public CompletableFuture<Transaction> submitSendMoneyAsync(String recipient,
                                                              String amountText,
                                                              String note,
                                                              String rawPin) {
        return executeSendMoneyAsync(null, recipient, amountText, null, note, rawPin, false);
    }

    private CompletableFuture<Transaction> executeSendMoneyAsync(String explicitReference,
                                                               String recipient,
                                                               String amountText,
                                                               BigDecimal explicitFee,
                                                               String note,
                                                               String rawPin,
                                                               boolean releaseInFlightOnDone) {
        CompletableFuture<Transaction> future = new CompletableFuture<>();
        if (walletService == null) {
            if (releaseInFlightOnDone) {
                submitInFlight.set(false);
            }
            future.completeExceptionally(new IllegalStateException("WalletOperations service is unavailable."));
            return future;
        }

        SwingUiSupport.runOnEdt(() -> {
            btnSubmitSendMoney.setEnabled(false);
            lblSendFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.INFO));
            lblSendFeedback.setText("Sending money...");
            notifyStatus("Processing transfer...");
        });

        SwingWorker<Transaction, Void> worker = new SwingWorker<>() {
            @Override
            protected Transaction doInBackground() {
                boolean offEdt = !SwingUtilities.isEventDispatchThread();
                lastOperationExecutedOffEdt = offEdt;
                if (offEdtObserver != null) {
                    offEdtObserver.accept(offEdt);
                }
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
                BigDecimal effectiveFee = explicitFee != null
                        ? MoneyUtil.validateNonNegativeAmount(explicitFee)
                        : CompositeFeePolicy.fromConfig().calculate(amount);
                String ref = (explicitReference != null && !explicitReference.trim().isEmpty())
                        ? explicitReference.trim()
                        : IdGenerator.nextReferenceNumber();
                String cleanPin = rawPin != null ? rawPin.trim() : "";
                if (!cleanPin.isEmpty()) {
                    if (authService != null && user != null) {
                        AuthService.verifyPinOrThrow(authService, user.getUserId(), cleanPin);
                        return walletService.sendMoneyToRecipient(
                                ref, wallet.getWalletId(), recipient.trim(), amount, effectiveFee, note);
                    }
                    return walletService.sendMoneyToRecipientWithPin(
                            ref, wallet.getWalletId(), recipient.trim(), amount, effectiveFee, note, cleanPin);
                }
                return walletService.sendMoneyToRecipient(
                        ref, wallet.getWalletId(), recipient.trim(), amount, effectiveFee, note);
            }

            @Override
            protected void done() {
                if (releaseInFlightOnDone) {
                    submitInFlight.set(false);
                }
                btnSubmitSendMoney.setEnabled(true);
                try {
                    Transaction txn = get();
                    txtSendAmount.setText("");
                    txtSendNote.setText("");
                    lblSendFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.SUCCESS));
                    lblSendFeedback.setText("Transfer successful: "
                            + MoneyUtil.format(txn.getAmount(), txn.getCurrency())
                            + " (Ref: " + txn.getReferenceNumber() + ")");
                    CompletableFuture<Void> refresh = onRefreshRequested != null ? onRefreshRequested.get() : null;
                    if (refresh != null) {
                        refresh.whenComplete((v, err) -> future.complete(txn));
                    } else {
                        future.complete(txn);
                    }
                } catch (Exception ex) {
                    Throwable cause = SwingUiSupport.unwrapCause(ex);
                    String message = SwingUiSupport.extractErrorMessage(cause);
                    lblSendFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
                    lblSendFeedback.setText("Transfer failed: " + message);
                    notifyStatus("Transfer failed: " + message);
                    if (onOperationError != null) {
                        onOperationError.accept(message);
                    }
                    future.completeExceptionally(cause);
                }
            }
        };
        worker.execute();
        return future;
    }

    private void handleInteractiveSubmit() {
        if (!submitInFlight.compareAndSet(false, true)) {
            return;
        }
        boolean asyncStarted = false;
        try {
            if (beforeInteractiveSubmit != null && !beforeInteractiveSubmit.getAsBoolean()) {
                return;
            }
            SessionManager session = SessionManager.getInstance();

            String recipient = txtRecipient.getText() != null ? txtRecipient.getText().trim() : "";
            String amountText = txtSendAmount.getText() != null ? txtSendAmount.getText().trim() : "";
            String note = txtSendNote.getText() != null ? txtSendNote.getText().trim() : "";
            String pin = new String(txtSendPin.getPassword()).trim();

            if (recipient.isEmpty()) {
                String msg = "Please enter a recipient (mobile number, email, name, or wallet ID).";
                lblSendFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
                lblSendFeedback.setText("Transfer failed: " + msg);
                showMessage("Validation Error", msg, JOptionPane.WARNING_MESSAGE);
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
                String msg = SwingUiSupport.extractErrorMessage(ex);
                lblSendFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
                lblSendFeedback.setText("Transfer failed: " + msg);
                notifyStatus("Transfer validation failed: " + msg);
                showMessage("Invalid Amount", msg, JOptionPane.ERROR_MESSAGE);
                txtSendAmount.requestFocusInWindow();
                return;
            }

            boolean pinSet = pinConfiguredSupplier == null || pinConfiguredSupplier.getAsBoolean();
            if (authService != null && !pinSet) {
                txtSendPin.setText("");
                if (onPinNotSet != null) {
                    onPinNotSet.run();
                }
                return;
            }

            OtogConfig config = OtogConfig.getInstance();
            if (pin.isEmpty()) {
                String msg = "Please enter your " + config.getPinMinLength() + "-" + config.getPinMaxLength()
                        + " digit Transaction PIN to authorize Send Money.";
                lblSendFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
                lblSendFeedback.setText("Transfer failed: " + msg);
                showMessage("PIN Required", msg, JOptionPane.WARNING_MESSAGE);
                txtSendPin.requestFocusInWindow();
                return;
            }

            txtSendPin.setText("");
            User currentUser = session.getCurrentUser();
            SendMoneyService sendMoneyService = walletService != null
                    ? walletService.createSendMoneyService(authService)
                    : null;
            if (sendMoneyService != null && currentUser != null) {
                btnSubmitSendMoney.setEnabled(false);
                lblSendFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.INFO));
                lblSendFeedback.setText("Preparing transfer confirmation...");
                notifyStatus("Validating recipient and balance...");
                asyncStarted = true;

                new SwingWorker<SendMoneyService.SendMoneyPreview, Void>() {
                    @Override
                    protected SendMoneyService.SendMoneyPreview doInBackground() {
                        return sendMoneyService.preview(currentUser.getUserId(), recipient, parsedAmount, null, note);
                    }

                    @Override
                    protected void done() {
                        try {
                            SendMoneyService.SendMoneyPreview preview = get();
                            JTextArea previewArea = new JTextArea(preview.toConfirmationText());
                            previewArea.setEditable(false);
                            previewArea.setFont(UiTheme.font(UiTheme.FontRole.MONO));
                            previewArea.setMargin(new Insets(6, 8, 6, 8));
                            JScrollPane previewScroll = new JScrollPane(previewArea);
                            previewScroll.setPreferredSize(new Dimension(400, 220));
                            int choice = confirmDialogCallback.show(
                                    SendMoneyPanel.this,
                                    "Confirm Send Money",
                                    previewScroll,
                                    JOptionPane.OK_CANCEL_OPTION,
                                    JOptionPane.QUESTION_MESSAGE
                            );
                            if (choice != JOptionPane.OK_OPTION) {
                                submitInFlight.set(false);
                                btnSubmitSendMoney.setEnabled(true);
                                lblSendFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.MUTED));
                                lblSendFeedback.setText("Transfer cancelled.");
                                notifyStatus("Transfer cancelled");
                                return;
                            }
                            dispatchConfirmedSendMoney(
                                    preview.getReferenceNumber(), recipient, amountText, preview.getFee(), note, pin);
                        } catch (Exception ex) {
                            submitInFlight.set(false);
                            btnSubmitSendMoney.setEnabled(true);
                            Throwable cause = SwingUiSupport.unwrapCause(ex);
                            String message = SwingUiSupport.extractErrorMessage(cause);
                            lblSendFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
                            lblSendFeedback.setText("Transfer failed: " + message);
                            notifyStatus("Transfer failed: " + message);
                            if (onOperationError != null) {
                                onOperationError.accept(message);
                            }
                            showMessage("Transfer Validation Failed", message, JOptionPane.ERROR_MESSAGE);
                        }
                    }
                }.execute();
            } else {
                BigDecimal fee = CompositeFeePolicy.fromConfig().calculate(parsedAmount);
                StringBuilder confirmMsg = new StringBuilder();
                confirmMsg.append("Recipient: ").append(recipient).append("\n");
                confirmMsg.append("Amount: ").append(MoneyUtil.format(parsedAmount, config.getDefaultCurrency()));
                if (fee.compareTo(BigDecimal.ZERO) > 0) {
                    confirmMsg.append("\nFee: ").append(MoneyUtil.format(fee, config.getDefaultCurrency()));
                }
                if (!note.isEmpty()) {
                    confirmMsg.append("\nNote: ").append(note);
                }
                int choice = confirmDialogCallback.show(
                        this,
                        "Confirm Send Money",
                        confirmMsg.toString(),
                        JOptionPane.OK_CANCEL_OPTION,
                        JOptionPane.QUESTION_MESSAGE
                );
                if (choice != JOptionPane.OK_OPTION) {
                    lblSendFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.MUTED));
                    lblSendFeedback.setText("Transfer cancelled.");
                    notifyStatus("Transfer cancelled");
                    return;
                }
                asyncStarted = true;
                dispatchConfirmedSendMoney(null, recipient, amountText, fee, note, pin);
            }
        } finally {
            if (!asyncStarted) {
                submitInFlight.set(false);
            }
        }
    }

    private void dispatchConfirmedSendMoney(String explicitReference,
                                            String recipient,
                                            String amountText,
                                            BigDecimal fee,
                                            String note,
                                            String pin) {
        executeSendMoneyAsync(explicitReference, recipient, amountText, fee, note, pin, true)
                .whenComplete((txn, err) -> SwingUiSupport.runOnEdt(() -> {
                    boolean loggedOut = isLogoutHandledSupplier != null && isLogoutHandledSupplier.getAsBoolean();
                    if (err != null) {
                        if (!loggedOut) {
                            Throwable cause = SwingUiSupport.unwrapCause(err);
                            showMessage("Transfer Failed", SwingUiSupport.extractErrorMessage(cause),
                                    JOptionPane.ERROR_MESSAGE);
                        }
                    } else if (txn != null) {
                        showMessage(
                                "Transfer Complete",
                                "Send Money successful!\nAmount: "
                                        + MoneyUtil.format(txn.getAmount(), txn.getCurrency())
                                        + "\nReference: " + txn.getReferenceNumber(),
                                JOptionPane.INFORMATION_MESSAGE
                        );
                    }
                }));
    }

    private void showMessage(String title, String message, int messageType) {
        dialogMessageCallback.show(this, title, message, messageType);
    }

    private void notifyStatus(String message) {
        if (onStatusMessage != null) {
            onStatusMessage.accept(message);
        }
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

        OtogConfig config = OtogConfig.getInstance();
        pnlForm = new JPanel();
        pnlForm.setLayout(new GridLayoutManager(6, 2, new Insets(12, 14, 12, 14), 10, 10));
        pnlForm.setBorder(UiTheme.titledBorder("Wallet-to-Wallet Transfer"));
        pnlRoot.add(pnlForm, new GridConstraints(0, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                null, null, null, 0, false));

        txtRecipient = new JTextField(22);
        txtSendAmount = new JTextField(22);
        txtSendNote = new JTextField(22);
        txtSendPin = new JPasswordField(22);

        addRow(pnlForm, 0, "Recipient (Mobile / Email / Name / Wallet ID):", txtRecipient);
        addRow(pnlForm, 1, "Amount (" + config.getDefaultCurrency() + "):", txtSendAmount);
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
        JLabel label = new JLabel(labelText);
        UiTheme.styleFormLabel(label);
        panel.add(label, new GridConstraints(row, 0, 1, 1,
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
