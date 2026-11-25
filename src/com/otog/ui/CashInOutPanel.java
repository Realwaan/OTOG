package com.otog.ui;

import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import com.intellij.uiDesigner.core.Spacer;
import com.otog.model.CashInOutTransaction;
import com.otog.model.PaymentMethod;
import com.otog.model.PaymentMethodStatus;
import com.otog.model.Transaction;
import com.otog.model.TransactionStatus;
import com.otog.model.TransactionType;
import com.otog.model.User;
import com.otog.model.Wallet;
import com.otog.service.AuthService;
import com.otog.service.BankResponseStatus;
import com.otog.service.PaymentProviderFactory;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Bound IntelliJ Swing form panel for the Cash In / Cash Out tab.
 * Owns input validation, confirmation dialogs, linked payment method selection,
 * and off-EDT {@link SwingWorker} execution.
 */
public class CashInOutPanel extends JPanel {

    private final WalletOperations walletService;
    private final AuthService authService;
    private final AtomicBoolean submitInFlight = new AtomicBoolean(false);

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

    private List<PaymentMethod> loadedPaymentMethods = Collections.emptyList();
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

    public CashInOutPanel(BankResponseStatus initialProviderMode) {
        this(null, null, initialProviderMode);
    }

    public CashInOutPanel(WalletOperations walletService,
                          AuthService authService,
                          BankResponseStatus initialProviderMode) {
        super(new BorderLayout());
        this.walletService = walletService;
        this.authService = authService;
        if (pnlRoot == null) {
            $$$setupUI$$$();
        }
        ensureComponentsInitialized(initialProviderMode);
        add(SwingUiSupport.createResponsiveScrollPane(pnlRoot), BorderLayout.CENTER);
        initInteractions();
    }

    private void ensureComponentsInitialized() {
        ensureComponentsInitialized(null);
    }

    /**
     * Populates combo boxes and applies runtime styling outside {@code $$$setupUI$$$()} so
     * IntelliJ GUI Designer bytecode instrumentation preserves runtime models and theme tokens.
     */
    private void ensureComponentsInitialized(BankResponseStatus initialProviderMode) {
        OtogConfig config = OtogConfig.getInstance();
        if (pnlForm != null) {
            pnlForm.setBorder(UiTheme.titledBorder("Cash In / Cash Out via Linked Bank or Channel"));
        }
        if (cmbCashOperation != null) {
            if (cmbCashOperation.getItemCount() == 0) {
                for (String op : config.getCashOperations()) {
                    cmbCashOperation.addItem(op);
                }
            }
            UiTheme.styleInputField(cmbCashOperation);
        }
        if (cmbPaymentMethod != null) {
            if (cmbPaymentMethod.getItemCount() == 0) {
                cmbPaymentMethod.addItem("Use Selected Channel");
            }
            UiTheme.styleInputField(cmbPaymentMethod);
        }
        if (cmbCashChannel != null) {
            if (cmbCashChannel.getItemCount() == 0) {
                for (String channel : config.getCashChannels()) {
                    cmbCashChannel.addItem(channel);
                }
            }
            UiTheme.styleInputField(cmbCashChannel);
        }
        if (cmbProviderMode != null) {
            if (cmbProviderMode.getItemCount() == 0) {
                for (BankResponseStatus status : BankResponseStatus.values()) {
                    cmbProviderMode.addItem(status.name());
                }
            }
            if (initialProviderMode != null) {
                cmbProviderMode.setSelectedItem(initialProviderMode.name());
            }
            UiTheme.styleInputField(cmbProviderMode);
        }
        UiTheme.styleInputField(txtCashAmount);
        UiTheme.styleInputField(txtCashNote);
        if (txtCashPin != null) {
            txtCashPin.setEnabled(false);
            txtCashPin.setToolTipText("Required for Cash Out transactions ("
                    + config.getPinMinLength() + "-" + config.getPinMaxLength() + " digit PIN)");
            UiTheme.styleInputField(txtCashPin);
        }
        UiTheme.stylePrimaryButton(btnSubmitCashOp);
        UiTheme.styleSecondaryButton(btnCashBackToOverview);
        if (pnlActions != null) {
            SwingUiSupport.applyWrapLayout(pnlActions, FlowLayout.LEFT, 8, 4);
        }
        if (lblCashFeedback != null) {
            lblCashFeedback.setFont(UiTheme.font(UiTheme.FontRole.LABEL));
        }
        SwingUiSupport.makeShrinkable(
                cmbCashOperation, cmbPaymentMethod, cmbCashChannel, cmbProviderMode,
                txtCashAmount, txtCashNote, txtCashPin, lblCashFeedback);
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
        cmbPaymentMethod.addActionListener(e ->
                cmbCashChannel.setEnabled(cmbPaymentMethod.getSelectedIndex() <= 0));

        cmbProviderMode.addActionListener(e -> {
            String modeName = (String) cmbProviderMode.getSelectedItem();
            if (walletService != null && modeName != null) {
                walletService.setPaymentProviderFactory(
                        PaymentProviderFactory.forMode(BankResponseStatus.valueOf(modeName)));
            }
        });

        cmbCashOperation.addActionListener(e -> {
            boolean isCashOut = "CASH_OUT".equals(cmbCashOperation.getSelectedItem());
            txtCashPin.setEnabled(isCashOut);
            if (!isCashOut) {
                txtCashPin.setText("");
            }
        });

        btnCashBackToOverview.addActionListener(e -> {
            if (onBackToOverview != null) {
                onBackToOverview.run();
            }
        });
        btnSubmitCashOp.addActionListener(e -> handleInteractiveSubmit());
        txtCashAmount.addActionListener(e -> handleInteractiveSubmit());
        txtCashNote.addActionListener(e -> handleInteractiveSubmit());
        txtCashPin.addActionListener(e -> handleInteractiveSubmit());
    }

    public void prepareForOperation(String operation) {
        if (operation != null) {
            cmbCashOperation.setSelectedItem(operation);
        }
        txtCashAmount.requestFocusInWindow();
    }

    public void showFeedback(String message, Color color) {
        if (lblCashFeedback == null) {
            return;
        }
        if (color != null) {
            lblCashFeedback.setForeground(color);
        }
        lblCashFeedback.setText(message != null ? message : " ");
    }

    public void loadRepeatDraft(Transaction draft) {
        if (draft == null) {
            return;
        }
        String op = draft.getType() == TransactionType.CASH_OUT ? "CASH_OUT" : "CASH_IN";
        cmbCashOperation.setSelectedItem(op);
        if (draft instanceof CashInOutTransaction cio && cio.getChannel() != null && !cio.getChannel().isBlank()) {
            // Reset to "Use Selected Channel" and re-enable channel selector so the drafted channel is not ignored
            if (cmbPaymentMethod != null && cmbPaymentMethod.getItemCount() > 0) {
                cmbPaymentMethod.setSelectedIndex(0);
            }
            if (cmbCashChannel != null) {
                cmbCashChannel.setEnabled(true);
                cmbCashChannel.setSelectedItem(cio.getChannel());
            }
        }
        txtCashAmount.setText(draft.getAmount() != null ? draft.getAmount().toPlainString() : "");
        txtCashNote.setText(draft.getDescription() != null ? draft.getDescription() : "");
        txtCashPin.setText("");
        showFeedback("Loaded repeat draft (New Ref: " + draft.getReferenceNumber()
                + "). Review details and submit when ready.", UiTheme.color(UiTheme.ColorRole.INFO));
    }

    public void populatePaymentMethods(List<PaymentMethod> paymentMethods) {
        if (cmbPaymentMethod == null) {
            return;
        }
        String prevSelectedId = getSelectedPaymentMethodId();
        List<PaymentMethod> activeMethods = new ArrayList<>();
        if (paymentMethods != null) {
            for (PaymentMethod pm : paymentMethods) {
                if (pm.getStatus() == PaymentMethodStatus.ACTIVE) {
                    activeMethods.add(pm);
                }
            }
        }
        this.loadedPaymentMethods = Collections.unmodifiableList(activeMethods);
        cmbPaymentMethod.removeAllItems();
        cmbPaymentMethod.addItem("Use Selected Channel");
        int defaultIndex = 0;
        int prevIndex = -1;
        for (int i = 0; i < activeMethods.size(); i++) {
            PaymentMethod pm = activeMethods.get(i);
            String label = pm.getProviderName() + " (" + pm.getAccountNumber() + ")"
                    + (pm.isDefault() ? " [Default]" : "");
            cmbPaymentMethod.addItem(label);
            if (prevSelectedId != null && prevSelectedId.equals(pm.getPaymentMethodId())) {
                prevIndex = i + 1;
            } else if (pm.isDefault() && defaultIndex == 0) {
                defaultIndex = i + 1;
            }
        }
        cmbPaymentMethod.setSelectedIndex(prevIndex >= 0 ? prevIndex : defaultIndex);
        if (cmbCashChannel != null) {
            cmbCashChannel.setEnabled(cmbPaymentMethod.getSelectedIndex() <= 0);
        }
    }

    public String getSelectedPaymentMethodId() {
        if (cmbPaymentMethod == null) {
            return null;
        }
        int idx = cmbPaymentMethod.getSelectedIndex();
        if (idx <= 0 || idx - 1 >= loadedPaymentMethods.size()) {
            return null;
        }
        return loadedPaymentMethods.get(idx - 1).getPaymentMethodId();
    }

    public CompletableFuture<Transaction> submitCashOperationAsync(String operationType,
                                                                    String channel,
                                                                    String paymentMethodId,
                                                                    String amountText,
                                                                    String note,
                                                                    String rawPin) {
        return submitCashOperationInternal(operationType, channel, paymentMethodId, amountText, note, rawPin, false);
    }

    private CompletableFuture<Transaction> submitCashOperationInternal(String operationType,
                                                                       String channel,
                                                                       String paymentMethodId,
                                                                       String amountText,
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
        String op = operationType != null ? operationType.trim().toUpperCase() : "CASH_IN";
        String opLabel = "CASH_OUT".equals(op) ? "Cash Out" : "Cash In";

        SwingUiSupport.runOnEdt(() -> {
            btnSubmitCashOp.setEnabled(false);
            showFeedback("Processing " + opLabel + "...", UiTheme.color(UiTheme.ColorRole.INFO));
            notifyStatus("Processing " + opLabel + "...");
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

                BigDecimal amount = MoneyUtil.validatePositiveAmount(MoneyUtil.parse(amountText));
                String ref = IdGenerator.nextReferenceNumber();
                String cleanChannel = (channel != null && !channel.trim().isEmpty())
                        ? channel.trim() : "OTOG Partner";
                String cleanNote = (note != null && !note.trim().isEmpty()) ? note.trim() : null;
                String pmId = (paymentMethodId != null && !paymentMethodId.trim().isEmpty())
                        ? paymentMethodId.trim() : null;

                if ("CASH_IN".equals(op)) {
                    String desc = pmId == null
                            ? (cleanNote != null ? cleanChannel + " - " + cleanNote : "Cash In via " + cleanChannel)
                            : cleanNote;
                    return walletService.cashIn(ref, wallet.getWalletId(), pmId, amount, desc);
                } else if ("CASH_OUT".equals(op)) {
                    String desc = pmId == null
                            ? (cleanNote != null ? cleanChannel + " - " + cleanNote : "Cash Out to " + cleanChannel)
                            : cleanNote;
                    String cleanPin = rawPin != null ? rawPin.trim() : "";
                    if (!cleanPin.isEmpty()) {
                        if (authService != null && user != null) {
                            AuthService.verifyPinOrThrow(authService, user.getUserId(), cleanPin);
                            return walletService.cashOut(ref, wallet.getWalletId(), pmId, amount, MoneyUtil.ZERO, desc);
                        }
                        return walletService.cashOutWithPin(
                                ref, wallet.getWalletId(), pmId, amount, MoneyUtil.ZERO, desc, cleanPin);
                    }
                    return walletService.cashOut(ref, wallet.getWalletId(), pmId, amount, MoneyUtil.ZERO, desc);
                }
                throw new IllegalArgumentException("Unsupported cash operation: " + operationType);
            }

            @Override
            protected void done() {
                if (releaseInFlightOnDone) {
                    submitInFlight.set(false);
                }
                btnSubmitCashOp.setEnabled(true);
                try {
                    Transaction txn = get();
                    txtCashAmount.setText("");
                    txtCashNote.setText("");
                    String provRef = txn.getProviderReference() != null
                            ? " | Bank Ref: " + txn.getProviderReference() : "";
                    String provStatus = txn.getProviderStatus() != null
                            ? " [" + txn.getProviderStatus() + "]" : "";
                    if (txn.getStatus() == TransactionStatus.COMPLETED) {
                        showFeedback(opLabel + " successful: "
                                + MoneyUtil.format(txn.getAmount(), txn.getCurrency())
                                + " (Ref: " + txn.getReferenceNumber() + ")" + provRef + provStatus,
                                UiTheme.color(UiTheme.ColorRole.SUCCESS));
                        notifyStatus(opLabel + " completed" + provStatus);
                    } else if (txn.getStatus() == TransactionStatus.FAILED) {
                        showFeedback(opLabel + " failed/rejected: "
                                + MoneyUtil.format(txn.getAmount(), txn.getCurrency())
                                + " (Ref: " + txn.getReferenceNumber() + ")" + provRef + provStatus,
                                UiTheme.color(UiTheme.ColorRole.ERROR));
                        notifyStatus(opLabel + " failed" + provStatus);
                    } else {
                        showFeedback(opLabel + " pending settlement: "
                                + MoneyUtil.format(txn.getAmount(), txn.getCurrency())
                                + " (Ref: " + txn.getReferenceNumber() + ")" + provRef + provStatus,
                                UiTheme.color(UiTheme.ColorRole.WARNING));
                        notifyStatus(opLabel + " pending settlement" + provStatus);
                    }
                    CompletableFuture<Void> refresh = onRefreshRequested != null ? onRefreshRequested.get() : null;
                    if (refresh != null) {
                        refresh.whenComplete((v, err) -> future.complete(txn));
                    } else {
                        future.complete(txn);
                    }
                } catch (Exception ex) {
                    Throwable cause = SwingUiSupport.unwrapCause(ex);
                    String message = SwingUiSupport.extractErrorMessage(cause);
                    showFeedback("Failed: " + message, UiTheme.color(UiTheme.ColorRole.ERROR));
                    notifyStatus(opLabel + " failed: " + message);
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
        boolean dispatched = false;
        try {
            if (beforeInteractiveSubmit != null && !beforeInteractiveSubmit.getAsBoolean()) {
                return;
            }

            String op = (String) cmbCashOperation.getSelectedItem();
            String opLabel = "CASH_OUT".equals(op) ? "Cash Out" : "Cash In";
            String selectedPmId = getSelectedPaymentMethodId();
            String channel = (String) cmbCashChannel.getSelectedItem();
            String amountText = txtCashAmount.getText() != null ? txtCashAmount.getText().trim() : "";
            String note = txtCashNote.getText() != null ? txtCashNote.getText().trim() : "";
            String pin = new String(txtCashPin.getPassword()).trim();

            BigDecimal parsedAmount;
            try {
                if (amountText.isEmpty()) {
                    throw new IllegalArgumentException("Please enter a transaction amount.");
                }
                parsedAmount = MoneyUtil.validatePositiveAmount(MoneyUtil.parse(amountText));
            } catch (RuntimeException ex) {
                String msg = SwingUiSupport.extractErrorMessage(ex);
                showFeedback("Failed: " + msg, UiTheme.color(UiTheme.ColorRole.ERROR));
                notifyStatus(opLabel + " validation failed: " + msg);
                showMessage("Invalid Amount", msg, JOptionPane.ERROR_MESSAGE);
                txtCashAmount.requestFocusInWindow();
                return;
            }

            OtogConfig config = OtogConfig.getInstance();
            if ("CASH_OUT".equals(op)) {
                boolean pinSet = pinConfiguredSupplier == null || pinConfiguredSupplier.getAsBoolean();
                if (authService != null && !pinSet) {
                    txtCashPin.setText("");
                    if (onPinNotSet != null) {
                        onPinNotSet.run();
                    }
                    return;
                }
                if (pin.isEmpty()) {
                    String msg = "Please enter your " + config.getPinMinLength() + "-" + config.getPinMaxLength()
                            + " digit Transaction PIN to authorize Cash Out.";
                    showFeedback("Failed: " + msg, UiTheme.color(UiTheme.ColorRole.ERROR));
                    showMessage("PIN Required", msg, JOptionPane.WARNING_MESSAGE);
                    txtCashPin.requestFocusInWindow();
                    return;
                }
            }

            txtCashPin.setText("");
            String methodSummary = selectedPmId != null
                    ? String.valueOf(cmbPaymentMethod.getSelectedItem())
                    : (channel != null ? channel : "OTOG Partner");
            StringBuilder confirmMsg = new StringBuilder();
            confirmMsg.append("Operation: ").append(opLabel).append("\n");
            confirmMsg.append(selectedPmId != null ? "Account: " : "Channel: ").append(methodSummary).append("\n");
            confirmMsg.append("Amount: ").append(MoneyUtil.format(parsedAmount, config.getDefaultCurrency()));
            if (!note.isEmpty()) {
                confirmMsg.append("\nNote: ").append(note);
            }
            int choice = confirmDialogCallback.show(
                    this,
                    "Confirm " + opLabel,
                    confirmMsg.toString(),
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.QUESTION_MESSAGE
            );
            if (choice != JOptionPane.OK_OPTION) {
                showFeedback(opLabel + " cancelled.", UiTheme.color(UiTheme.ColorRole.MUTED));
                return;
            }

            dispatched = true;
            submitCashOperationInternal(op, channel, selectedPmId, amountText, note, pin, true)
                    .whenComplete((txn, err) -> SwingUiSupport.runOnEdt(() -> {
                        boolean loggedOut = isLogoutHandledSupplier != null && isLogoutHandledSupplier.getAsBoolean();
                        if (err != null) {
                            if (!loggedOut) {
                                Throwable cause = SwingUiSupport.unwrapCause(err);
                                showMessage(opLabel + " Failed", SwingUiSupport.extractErrorMessage(cause),
                                        JOptionPane.ERROR_MESSAGE);
                            }
                        } else if (txn != null) {
                            if (txn.getStatus() == TransactionStatus.FAILED) {
                                showMessage(opLabel + " Failed",
                                        opLabel + " was rejected by provider.\nAmount: "
                                                + MoneyUtil.format(txn.getAmount(), txn.getCurrency())
                                                + "\nReference: " + txn.getReferenceNumber(),
                                        JOptionPane.ERROR_MESSAGE);
                            } else if (txn.getStatus() == TransactionStatus.PENDING) {
                                showMessage(opLabel + " Pending",
                                        opLabel + " is pending provider settlement.\nAmount: "
                                                + MoneyUtil.format(txn.getAmount(), txn.getCurrency())
                                                + "\nReference: " + txn.getReferenceNumber(),
                                        JOptionPane.WARNING_MESSAGE);
                            } else {
                                showMessage(opLabel + " Complete",
                                        opLabel + " successful!\nAmount: "
                                                + MoneyUtil.format(txn.getAmount(), txn.getCurrency())
                                                + "\nReference: " + txn.getReferenceNumber(),
                                        JOptionPane.INFORMATION_MESSAGE);
                            }
                        }
                    }));
        } finally {
            if (!dispatched) {
                submitInFlight.set(false);
            }
        }
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
        pnlRoot.setLayout(new GridLayoutManager(2, 1, new Insets(12, 14, 12, 14), 10, 8));

        OtogConfig config = OtogConfig.getInstance();
        pnlForm = new JPanel();
        pnlForm.setLayout(new GridLayoutManager(9, 2, new Insets(10, 14, 10, 14), 10, 8));
        pnlForm.setBorder(UiTheme.titledBorder("Cash In / Cash Out via Linked Bank or Channel"));
        pnlRoot.add(pnlForm, new GridConstraints(0, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW | GridConstraints.SIZEPOLICY_WANT_GROW,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                null, null, null, 0, false));

        cmbCashOperation = new JComboBox<>(config.getCashOperations().toArray(String[]::new));
        cmbPaymentMethod = new JComboBox<>(new String[]{"Use Selected Channel"});
        cmbCashChannel = new JComboBox<>(config.getCashChannels().toArray(String[]::new));
        String[] providerModes = Arrays.stream(BankResponseStatus.values()).map(Enum::name).toArray(String[]::new);
        cmbProviderMode = new JComboBox<>(providerModes);
        txtCashAmount = new JTextField(20);
        txtCashNote = new JTextField(20);
        txtCashPin = new JPasswordField(20);

        addRow(pnlForm, 0, "Operation:", cmbCashOperation);
        addRow(pnlForm, 1, "Linked Bank Account:", cmbPaymentMethod);
        addRow(pnlForm, 2, "Channel / Provider:", cmbCashChannel);
        addRow(pnlForm, 3, "Mock Bank Mode:", cmbProviderMode);
        addRow(pnlForm, 4, "Amount (" + config.getDefaultCurrency() + "):", txtCashAmount);
        addRow(pnlForm, 5, "Note (Optional):", txtCashNote);
        addRow(pnlForm, 6, "Transaction PIN (Cash Out):", txtCashPin);

        pnlActions = new JPanel();
        pnlActions.setLayout(new FlowLayout(FlowLayout.LEFT, 8, 4));
        pnlForm.add(pnlActions, new GridConstraints(7, 1, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW | GridConstraints.SIZEPOLICY_WANT_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        btnSubmitCashOp = new JButton("Submit Cash Transaction");
        pnlActions.add(btnSubmitCashOp);

        btnCashBackToOverview = new JButton("\u2190 Back to Overview");
        pnlActions.add(btnCashBackToOverview);

        lblCashFeedback = new JLabel(" ");
        pnlForm.add(lblCashFeedback, new GridConstraints(8, 0, 1, 2,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW | GridConstraints.SIZEPOLICY_WANT_GROW,
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
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW | GridConstraints.SIZEPOLICY_WANT_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));
    }

    public JComponent $$$getRootComponent$$$() {
        return pnlRoot;
    }
}
