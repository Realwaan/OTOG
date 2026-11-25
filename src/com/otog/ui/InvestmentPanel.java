package com.otog.ui;

import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import com.intellij.uiDesigner.core.Spacer;
import com.otog.model.InvestMoneyTransaction;
import com.otog.model.Transaction;
import com.otog.model.TransactionType;
import com.otog.model.User;
import com.otog.model.Wallet;
import com.otog.service.AuthService;
import com.otog.service.InvestmentService;
import com.otog.service.SessionManager;
import com.otog.service.WalletOperations;
import com.otog.util.IdGenerator;
import com.otog.util.MoneyUtil;
import com.otog.util.OtogConfig;
import com.otog.util.SwingUiSupport;
import com.otog.util.UiTheme;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Bound IntelliJ Swing form panel for CAP-13 Basic Simulated Investment Workflow.
 * Owns buy/redeem input validation, live unit/amount clearing, preview confirmation,
 * and off-EDT {@link SwingWorker} execution.
 */
public class InvestmentPanel extends JPanel {

    private static final String[] ACTIVITY_COLUMNS = {
            "Date", "Reference", "Type", "Product", "Units", "Amount", "Status", "Description"
    };

    private final WalletOperations walletService;
    private final InvestmentService investmentService;
    private final AuthService authService;
    private final DateTimeFormatter dateFormatter =
            DateTimeFormatter.ofPattern(OtogConfig.getInstance().getUiDatePattern());
    private final AtomicBoolean submitInFlight = new AtomicBoolean(false);

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

    private boolean updatingInvestmentInputs;
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

    public InvestmentPanel() {
        this(null, null, null);
    }

    public InvestmentPanel(WalletOperations walletService,
                           InvestmentService investmentService,
                           AuthService authService) {
        super(new BorderLayout());
        this.walletService = walletService;
        this.investmentService = investmentService;
        this.authService = authService;
        if (pnlRoot == null) {
            $$$setupUI$$$();
        }
        ensureInteractiveComponentsInitialized();
        add(pnlRoot, BorderLayout.CENTER);
        initInteractions();
    }

    /**
     * Initializes models, labels, and UiTheme styling outside {@code $$$setupUI$$$()} so
     * IntelliJ GUI Designer bytecode instrumentation never discards runtime configuration.
     */
    private void ensureInteractiveComponentsInitialized() {
        OtogConfig config = OtogConfig.getInstance();
        String defaultCurrency = config.getDefaultCurrency();
        if (cmbInvestOperation != null) {
            if (cmbInvestOperation.getItemCount() == 0) {
                cmbInvestOperation.addItem("BUY");
                cmbInvestOperation.addItem("REDEEM");
            }
            UiTheme.styleInputField(cmbInvestOperation);
        }
        UiTheme.styleInputField(txtInvestUnits);
        UiTheme.styleInputField(txtInvestAmount);
        if (txtInvestPin != null) {
            txtInvestPin.setToolTipText("Enter your Transaction PIN when PIN authorization is enabled");
            UiTheme.styleInputField(txtInvestPin);
        }
        UiTheme.styleSecondaryButton(btnPreviewInvest);
        UiTheme.stylePrimaryButton(btnSubmitInvest);
        UiTheme.styleSecondaryButton(btnInvestBackToOverview);

        if (activityTableModel == null) {
            activityTableModel = new DefaultTableModel(ACTIVITY_COLUMNS, 0) {
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
            UiTheme.styleTable(tblInvestmentActivity, 115, 130, 115, 155, 85, 95, 90, 175);
            UiTheme.alignTableColumnsRight(tblInvestmentActivity, 4, 5);
        }
        if (lblProductName != null) {
            lblProductName.setFont(UiTheme.font(UiTheme.FontRole.SECTION_HEADER));
        }
        if (lblSimulatedPrice != null) {
            lblSimulatedPrice.setFont(UiTheme.font(UiTheme.FontRole.LABEL));
            lblSimulatedPrice.setForeground(UiTheme.color(UiTheme.ColorRole.PRIMARY));
            lblSimulatedPrice.setText(MoneyUtil.format(
                    config.getInvestmentSimulatedUnitPrice(), defaultCurrency) + " / unit [SIMULATED FIXED PRICE]");
        }
        if (lblHoldingsUnits != null) {
            lblHoldingsUnits.setFont(UiTheme.font(UiTheme.FontRole.SECTION_HEADER));
        }
        if (lblHoldingsValue != null) {
            lblHoldingsValue.setFont(UiTheme.font(UiTheme.FontRole.SECTION_HEADER));
            lblHoldingsValue.setForeground(UiTheme.color(UiTheme.ColorRole.SUCCESS));
            lblHoldingsValue.setText(MoneyUtil.format(BigDecimal.ZERO, defaultCurrency));
        }
        if (lblPreviewSummary != null) {
            lblPreviewSummary.setFont(UiTheme.font(UiTheme.FontRole.LABEL));
            lblPreviewSummary.setForeground(UiTheme.color(UiTheme.ColorRole.INFO));
            lblPreviewSummary.setText("Enter units or " + defaultCurrency + " amount, then click Preview Units & Cost.");
        }
        if (lblInvestFeedback != null) {
            lblInvestFeedback.setFont(UiTheme.font(UiTheme.FontRole.LABEL));
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

    public void focusUnits() {
        if (txtInvestUnits != null) {
            txtInvestUnits.requestFocusInWindow();
        }
    }

    private void initInteractions() {
        cmbInvestOperation.addActionListener(e -> {
            boolean isBuy = "BUY".equals(cmbInvestOperation.getSelectedItem());
            txtInvestAmount.setEnabled(isBuy);
            if (!isBuy) {
                setInvestmentInputTexts(txtInvestUnits.getText(), "");
            }
            resetPreviewSummary(isBuy);
        });

        txtInvestUnits.getDocument().addDocumentListener(new DocumentListener() {
            private void onUnitsEdited() {
                if (updatingInvestmentInputs) {
                    return;
                }
                if (txtInvestUnits.getText() != null && !txtInvestUnits.getText().isEmpty()
                        && txtInvestAmount.getText() != null && !txtInvestAmount.getText().isEmpty()) {
                    updatingInvestmentInputs = true;
                    try {
                        txtInvestAmount.setText("");
                    } finally {
                        updatingInvestmentInputs = false;
                    }
                }
            }

            @Override
            public void insertUpdate(DocumentEvent e) {
                onUnitsEdited();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                onUnitsEdited();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                onUnitsEdited();
            }
        });

        txtInvestAmount.getDocument().addDocumentListener(new DocumentListener() {
            private void onAmountEdited() {
                if (updatingInvestmentInputs) {
                    return;
                }
                if (txtInvestAmount.getText() != null && !txtInvestAmount.getText().isEmpty()
                        && txtInvestUnits.getText() != null && !txtInvestUnits.getText().isEmpty()) {
                    updatingInvestmentInputs = true;
                    try {
                        txtInvestUnits.setText("");
                    } finally {
                        updatingInvestmentInputs = false;
                    }
                }
            }

            @Override
            public void insertUpdate(DocumentEvent e) {
                onAmountEdited();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                onAmountEdited();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                onAmountEdited();
            }
        });

        btnInvestBackToOverview.addActionListener(e -> {
            if (onBackToOverview != null) {
                onBackToOverview.run();
            }
        });
        btnPreviewInvest.addActionListener(e -> handleInteractivePreview());
        btnSubmitInvest.addActionListener(e -> handleInteractiveSubmit());
        txtInvestUnits.addActionListener(e -> handleInteractivePreview());
        txtInvestAmount.addActionListener(e -> handleInteractivePreview());
        txtInvestPin.addActionListener(e -> handleInteractiveSubmit());
    }

    public void loadRepeatDraft(Transaction draft) {
        if (draft == null) {
            return;
        }
        String op = draft.getType() == TransactionType.INVESTMENT_REDEEM ? "REDEEM" : "BUY";
        cmbInvestOperation.setSelectedItem(op);
        String unitsStr = (draft instanceof InvestMoneyTransaction imt && imt.getUnits() != null)
                ? imt.getUnits().toPlainString()
                : "";
        setInvestmentInputTexts(unitsStr, "");
        txtInvestPin.setText("");
        lblInvestFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.INFO));
        lblInvestFeedback.setText("Loaded repeat draft (New Ref: " + draft.getReferenceNumber()
                + "). Review units and click Preview or Confirm.");
    }

    public CompletableFuture<InvestmentService.InvestmentPreview> previewInvestmentAsync(
            String operation, String unitsText, String amountText) {
        CompletableFuture<InvestmentService.InvestmentPreview> future = new CompletableFuture<>();
        if (investmentService == null) {
            future.completeExceptionally(new IllegalStateException("InvestmentService is not enabled."));
            return future;
        }
        String rawOp = operation != null ? operation.trim().toUpperCase(Locale.ROOT) : "";

        SwingUiSupport.runOnEdt(() -> {
            if (btnPreviewInvest != null) {
                btnPreviewInvest.setEnabled(false);
            }
            if (lblPreviewSummary != null) {
                lblPreviewSummary.setForeground(UiTheme.color(UiTheme.ColorRole.INFO));
                lblPreviewSummary.setText("Calculating preview...");
            }
        });

        new SwingWorker<InvestmentService.InvestmentPreview, Void>() {
            @Override
            protected InvestmentService.InvestmentPreview doInBackground() {
                String op = requireValidInvestmentOperation(rawOp);
                SessionManager session = SessionManager.getInstance();
                session.requireAuthentication();
                Wallet wallet = session.getCurrentWallet();
                if (wallet == null && walletService != null) {
                    wallet = session.refreshWallet(walletService);
                }
                if (wallet == null) {
                    throw new IllegalStateException("Active wallet is required.");
                }
                BigDecimal units = parseOptionalDecimal(unitsText);
                BigDecimal amount = parseOptionalDecimal(amountText);
                if ("REDEEM".equals(op)) {
                    return investmentService.previewRedeem(wallet.getWalletId(), units);
                }
                return investmentService.previewBuy(wallet.getWalletId(), units, amount);
            }

            @Override
            protected void done() {
                if (btnPreviewInvest != null && !submitInFlight.get()) {
                    btnPreviewInvest.setEnabled(true);
                }
                try {
                    InvestmentService.InvestmentPreview preview = get();
                    setInvestmentInputTexts(
                            preview.getUnits().toPlainString(),
                            "BUY".equals(preview.getOperation()) ? preview.getTotalAmount().toPlainString() : null
                    );
                    if (lblPreviewSummary != null) {
                        lblPreviewSummary.setForeground(UiTheme.color(UiTheme.ColorRole.PRIMARY));
                        lblPreviewSummary.setText(preview.getOperation() + " " + preview.getUnits().toPlainString()
                                + " units @ " + MoneyUtil.format(preview.getSimulatedUnitPrice(), preview.getCurrency())
                                + " [SIMULATED] = " + MoneyUtil.format(preview.getTotalAmount(), preview.getCurrency())
                                + " (Projected holdings: " + preview.getProjectedHoldingUnits().toPlainString() + " units)");
                    }
                    if (lblInvestFeedback != null) {
                        lblInvestFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.INFO));
                        lblInvestFeedback.setText("Preview ready — review units and cost, then click Confirm Investment.");
                    }
                    future.complete(preview);
                } catch (Exception ex) {
                    Throwable cause = SwingUiSupport.unwrapCause(ex);
                    String message = SwingUiSupport.extractErrorMessage(cause);
                    if (lblPreviewSummary != null) {
                        lblPreviewSummary.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
                        lblPreviewSummary.setText("Preview failed: " + message);
                    }
                    if (lblInvestFeedback != null) {
                        lblInvestFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
                        lblInvestFeedback.setText("Preview failed: " + message);
                    }
                    if (onOperationError != null) {
                        onOperationError.accept(message);
                    }
                    future.completeExceptionally(cause);
                }
            }
        }.execute();
        return future;
    }

    public CompletableFuture<InvestMoneyTransaction> submitInvestmentAsync(
            String operation, String unitsText, String amountText, String rawPin) {
        return executeInvestmentAsync(null, operation, unitsText, amountText, rawPin, false);
    }

    private CompletableFuture<InvestMoneyTransaction> executeInvestmentAsync(
            String explicitReference, String operation, String unitsText, String amountText,
            String rawPin, boolean releaseInFlightOnDone) {
        CompletableFuture<InvestMoneyTransaction> future = new CompletableFuture<>();
        if (investmentService == null) {
            if (releaseInFlightOnDone) {
                submitInFlight.set(false);
            }
            future.completeExceptionally(new IllegalStateException("InvestmentService is not enabled."));
            return future;
        }
        String rawOp = operation != null ? operation.trim().toUpperCase(Locale.ROOT) : "";
        String opLabel = "REDEEM".equals(rawOp) ? "Investment Redemption" : "Investment Purchase";

        SwingUiSupport.runOnEdt(() -> {
            if (btnSubmitInvest != null) {
                btnSubmitInvest.setEnabled(false);
            }
            if (btnPreviewInvest != null) {
                btnPreviewInvest.setEnabled(false);
            }
            if (lblInvestFeedback != null) {
                lblInvestFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.INFO));
                lblInvestFeedback.setText("Processing " + opLabel + "...");
            }
            notifyStatus("Processing " + opLabel + "...");
        });

        new SwingWorker<InvestMoneyTransaction, Void>() {
            @Override
            protected InvestMoneyTransaction doInBackground() {
                boolean offEdt = !SwingUtilities.isEventDispatchThread();
                lastOperationExecutedOffEdt = offEdt;
                if (offEdtObserver != null) {
                    offEdtObserver.accept(offEdt);
                }
                String op = requireValidInvestmentOperation(rawOp);
                SessionManager session = SessionManager.getInstance();
                session.requireAuthentication();
                User user = session.getCurrentUser();
                Wallet wallet = session.getCurrentWallet();
                if (wallet == null && walletService != null) {
                    wallet = session.refreshWallet(walletService);
                }
                if (wallet == null) {
                    throw new IllegalStateException("Active wallet is required.");
                }
                BigDecimal units = parseOptionalDecimal(unitsText);
                BigDecimal amount = parseOptionalDecimal(amountText);
                String ref = (explicitReference != null && !explicitReference.trim().isEmpty())
                        ? explicitReference.trim()
                        : IdGenerator.nextReferenceNumber();
                String cleanPin = rawPin != null ? rawPin.trim() : "";

                if (!cleanPin.isEmpty()) {
                    if (authService != null && user != null) {
                        AuthService.verifyPinOrThrow(authService, user.getUserId(), cleanPin);
                        return "REDEEM".equals(op)
                                ? investmentService.redeemInvestment(ref, wallet.getWalletId(), units)
                                : investmentService.buyInvestment(ref, wallet.getWalletId(), units, amount);
                    }
                    return "REDEEM".equals(op)
                            ? investmentService.redeemInvestmentWithPin(ref, wallet.getWalletId(), units, cleanPin)
                            : investmentService.buyInvestmentWithPin(ref, wallet.getWalletId(), units, amount, cleanPin);
                }
                return "REDEEM".equals(op)
                        ? investmentService.redeemInvestment(ref, wallet.getWalletId(), units)
                        : investmentService.buyInvestment(ref, wallet.getWalletId(), units, amount);
            }

            @Override
            protected void done() {
                if (releaseInFlightOnDone) {
                    submitInFlight.set(false);
                }
                if (btnSubmitInvest != null) {
                    btnSubmitInvest.setEnabled(true);
                }
                if (btnPreviewInvest != null) {
                    btnPreviewInvest.setEnabled(true);
                }
                try {
                    InvestMoneyTransaction txn = get();
                    setInvestmentInputTexts("", "");
                    boolean isBuySelected = cmbInvestOperation == null || "BUY".equals(cmbInvestOperation.getSelectedItem());
                    resetPreviewSummary(isBuySelected);
                    if (lblInvestFeedback != null) {
                        lblInvestFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.SUCCESS));
                        lblInvestFeedback.setText(opLabel + " completed: "
                                + (txn.getUnits() != null ? txn.getUnits().toPlainString() : "-")
                                + " units (" + MoneyUtil.format(txn.getAmount(), txn.getCurrency())
                                + ", Ref: " + txn.getReferenceNumber() + ")");
                    }
                    notifyStatus(opLabel + " completed (Ref: " + txn.getReferenceNumber() + ")");
                    CompletableFuture<Void> refresh = onRefreshRequested != null ? onRefreshRequested.get() : null;
                    if (refresh != null) {
                        refresh.whenComplete((v, err) -> future.complete(txn));
                    } else {
                        future.complete(txn);
                    }
                } catch (Exception ex) {
                    Throwable cause = SwingUiSupport.unwrapCause(ex);
                    String message = SwingUiSupport.extractErrorMessage(cause);
                    if (lblInvestFeedback != null) {
                        lblInvestFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
                        lblInvestFeedback.setText(opLabel + " failed: " + message);
                    }
                    notifyStatus(opLabel + " failed: " + message);
                    if (onOperationError != null) {
                        onOperationError.accept(message);
                    }
                    future.completeExceptionally(cause);
                }
            }
        }.execute();
        return future;
    }

    private void handleInteractivePreview() {
        if (submitInFlight.get()) {
            return;
        }
        if ((beforeInteractiveSubmit != null && !beforeInteractiveSubmit.getAsBoolean()) || investmentService == null) {
            return;
        }
        String op = (String) cmbInvestOperation.getSelectedItem();
        String unitsText = txtInvestUnits.getText() != null ? txtInvestUnits.getText().trim() : "";
        String amountText = txtInvestAmount.getText() != null ? txtInvestAmount.getText().trim() : "";
        previewInvestmentAsync(op, unitsText, amountText);
    }

    private void handleInteractiveSubmit() {
        if (!submitInFlight.compareAndSet(false, true)) {
            return;
        }
        boolean asyncStarted = false;
        try {
            if ((beforeInteractiveSubmit != null && !beforeInteractiveSubmit.getAsBoolean()) || investmentService == null) {
                return;
            }
            String op = (String) cmbInvestOperation.getSelectedItem();
            String unitsText = txtInvestUnits.getText() != null ? txtInvestUnits.getText().trim() : "";
            String amountText = txtInvestAmount.getText() != null ? txtInvestAmount.getText().trim() : "";
            String pin = new String(txtInvestPin.getPassword()).trim();

            try {
                String validOp = requireValidInvestmentOperation(op != null ? op.trim().toUpperCase(Locale.ROOT) : "");
                BigDecimal parsedUnits = parseOptionalDecimal(unitsText);
                BigDecimal parsedAmount = parseOptionalDecimal(amountText);
                if ("REDEEM".equals(validOp)) {
                    if (parsedUnits == null || parsedUnits.compareTo(BigDecimal.ZERO) <= 0) {
                        throw new IllegalArgumentException("Please enter a positive number of units to redeem.");
                    }
                } else {
                    if ((parsedUnits == null || parsedUnits.compareTo(BigDecimal.ZERO) <= 0)
                            && (parsedAmount == null || parsedAmount.compareTo(BigDecimal.ZERO) <= 0)) {
                        throw new IllegalArgumentException("Please enter positive units or a positive "
                                + OtogConfig.getInstance().getDefaultCurrency() + " amount to invest.");
                    }
                }
            } catch (RuntimeException ex) {
                String msg = SwingUiSupport.extractErrorMessage(ex);
                lblInvestFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
                lblInvestFeedback.setText("Investment failed: " + msg);
                notifyStatus("Investment validation failed: " + msg);
                showMessage("Invalid Investment Input", msg, JOptionPane.ERROR_MESSAGE);
                txtInvestUnits.requestFocusInWindow();
                return;
            }

            boolean pinSet = pinConfiguredSupplier == null || pinConfiguredSupplier.getAsBoolean();
            if (authService != null && !pinSet) {
                txtInvestPin.setText("");
                if (onPinNotSet != null) {
                    onPinNotSet.run();
                }
                return;
            }
            if (authService != null && pin.isEmpty()) {
                OtogConfig config = OtogConfig.getInstance();
                String msg = "Please enter your " + config.getPinMinLength() + "-" + config.getPinMaxLength()
                        + " digit Transaction PIN to authorize investment order.";
                lblInvestFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
                lblInvestFeedback.setText("Investment failed: " + msg);
                showMessage("PIN Required", msg, JOptionPane.WARNING_MESSAGE);
                txtInvestPin.requestFocusInWindow();
                return;
            }
            txtInvestPin.setText("");

            if (btnSubmitInvest != null) {
                btnSubmitInvest.setEnabled(false);
            }
            if (btnPreviewInvest != null) {
                btnPreviewInvest.setEnabled(false);
            }
            asyncStarted = true;

            previewInvestmentAsync(op, unitsText, amountText).whenComplete((preview, previewErr) -> SwingUiSupport.runOnEdt(() -> {
                if (previewErr != null) {
                    submitInFlight.set(false);
                    if (btnSubmitInvest != null) {
                        btnSubmitInvest.setEnabled(true);
                    }
                    if (btnPreviewInvest != null) {
                        btnPreviewInvest.setEnabled(true);
                    }
                    Throwable cause = SwingUiSupport.unwrapCause(previewErr);
                    showMessage("Investment Preview Failed", SwingUiSupport.extractErrorMessage(cause),
                            JOptionPane.ERROR_MESSAGE);
                    return;
                }
                JTextArea area = new JTextArea(preview.toConfirmationText());
                area.setEditable(false);
                area.setFont(UiTheme.font(UiTheme.FontRole.MONO));
                area.setMargin(new Insets(6, 8, 6, 8));
                int choice = confirmDialogCallback.show(
                        this,
                        "Confirm Investment Order",
                        new JScrollPane(area),
                        JOptionPane.OK_CANCEL_OPTION,
                        JOptionPane.QUESTION_MESSAGE
                );
                if (choice != JOptionPane.OK_OPTION) {
                    submitInFlight.set(false);
                    if (btnSubmitInvest != null) {
                        btnSubmitInvest.setEnabled(true);
                    }
                    if (btnPreviewInvest != null) {
                        btnPreviewInvest.setEnabled(true);
                    }
                    lblInvestFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.MUTED));
                    lblInvestFeedback.setText("Investment order cancelled.");
                    return;
                }
                executeInvestmentAsync(
                        preview.getReferenceNumber(),
                        op,
                        preview.getUnits().toPlainString(),
                        "BUY".equals(preview.getOperation()) ? preview.getTotalAmount().toPlainString() : null,
                        pin,
                        true
                ).whenComplete((txn, err) -> SwingUiSupport.runOnEdt(() -> {
                    boolean loggedOut = isLogoutHandledSupplier != null && isLogoutHandledSupplier.getAsBoolean();
                    if (err != null && !loggedOut) {
                        Throwable cause = SwingUiSupport.unwrapCause(err);
                        showMessage("Investment Failed", SwingUiSupport.extractErrorMessage(cause),
                                JOptionPane.ERROR_MESSAGE);
                    } else if (txn != null) {
                        showMessage(
                                "Investment Complete",
                                "Order executed!\nProduct: " + txn.getProduct()
                                        + "\nUnits: " + txn.getUnits().toPlainString()
                                        + "\nAmount: " + MoneyUtil.format(txn.getAmount(), txn.getCurrency())
                                        + "\nReference: " + txn.getReferenceNumber(),
                                JOptionPane.INFORMATION_MESSAGE);
                    }
                }));
            }));
        } finally {
            if (!asyncStarted) {
                submitInFlight.set(false);
            }
        }
    }

    private void setInvestmentInputTexts(String units, String amount) {
        updatingInvestmentInputs = true;
        try {
            if (txtInvestUnits != null && units != null) {
                txtInvestUnits.setText(units);
            }
            if (txtInvestAmount != null && amount != null) {
                txtInvestAmount.setText(amount);
            }
        } finally {
            updatingInvestmentInputs = false;
        }
    }

    private void resetPreviewSummary(boolean isBuy) {
        if (lblPreviewSummary == null) {
            return;
        }
        String defaultCurrency = OtogConfig.getInstance().getDefaultCurrency();
        lblPreviewSummary.setForeground(UiTheme.color(UiTheme.ColorRole.INFO));
        lblPreviewSummary.setText(isBuy
                ? "Enter units or " + defaultCurrency + " amount, then click Preview Units & Cost."
                : "Enter units to redeem, then click Preview Units & Cost.");
    }

    private static String requireValidInvestmentOperation(String rawOp) {
        if ("BUY".equals(rawOp) || "REDEEM".equals(rawOp)) {
            return rawOp;
        }
        throw new IllegalArgumentException("Unsupported investment operation: " + rawOp);
    }

    private static BigDecimal parseOptionalDecimal(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(text.trim().replace(",", ""));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid numeric value: " + text.trim());
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
        lblSimulatedPrice = new JLabel(MoneyUtil.format(
                config.getInvestmentSimulatedUnitPrice(), config.getDefaultCurrency()) + " / unit [SIMULATED FIXED PRICE]");
        lblHoldingsUnits = new JLabel("0.0000 units");
        lblHoldingsValue = new JLabel(MoneyUtil.format(BigDecimal.ZERO, config.getDefaultCurrency()));

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
        lblPreviewSummary = new JLabel("Enter units or " + config.getDefaultCurrency() + " amount, then click Preview Units & Cost.");

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

        tblInvestmentActivity = new JTable();
        scrollInvestmentActivity = new JScrollPane(tblInvestmentActivity);
        pnlActivity.add(scrollInvestmentActivity, new GridConstraints(0, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW,
                null, null, null, 0, false));
    }

    private static void addSummaryCell(JPanel panel, int row, int labelCol, String labelText, JLabel valueLabel) {
        JLabel label = new JLabel(labelText);
        UiTheme.styleFormLabel(label);
        panel.add(label, new GridConstraints(row, labelCol, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));
        panel.add(valueLabel, new GridConstraints(row, labelCol + 1, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));
    }

    private static void addFormRow(JPanel panel, int row, String labelText, JComponent field) {
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
